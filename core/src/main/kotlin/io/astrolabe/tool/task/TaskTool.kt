package io.astrolabe.tool.task

import io.astrolabe.auth.InstructionShape
import io.astrolabe.contract.Contracts
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.event.Replies
import io.astrolabe.event.ReplyValidity
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.Args
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.TaskArgs
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.state.BlockedRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Clock

/** One asked question with what came back: pinned for the rest of the cell (§5.1 "user messages pinned"). */
public data class Asked(
    val question: Question,
    val answer: Answer?,
    val turn: Int,
    /** The journal event holding a factual answer as evidence, or `null` when the answer amended the contract. */
    val evidenceEventId: String?,
    /** The contract version the answer produced when it changed requirements or authority. */
    val amendedToVersion: Int?,
)

/**
 * The `task` family in S0 (§5.4, §4.1, TODO P1.6.9): `ask(question, options?)` puts the question to the
 * [Authority]. An answer that changes requirements or authority is a user amendment — appended verbatim,
 * version bumped — and everything else is recorded as evidence without a version bump; no answer (the
 * autonomous policy, or nobody available) ends the cell `blocked` with the question, which is a success
 * path. A reply for another contract revision is superseded and never used. `propose` (P2.1.5) hands a plan or
 * an increment split to the controller's [Proposals] intake and an amendment to [Contracts.propose]; none of
 * them changes the contract or the graph. `delegate` and `collect` (P4.4.1) are masked.
 */
public class TaskTool(
    private val authority: Authority,
    private val contracts: Contracts,
    private val journal: Journal?,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    private val events: Events? = null,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val proposals: Proposals? = null,
) : ToolExecutor {
    init {
        require(ids.context != null) { "task runs inside a cell: ids.context is its lineage" }
    }

    private val exchanges = ArrayList<Asked>()

    /** Every question of this cell with its outcome, oldest first; the compiler pins them (P1.8.2). */
    public val asked: List<Asked> get() = exchanges.toList()

    /** Set when a question got no usable answer; the cell loop ends the cell `blocked` after reconciliation. */
    public var pendingBlock: BlockedRequest? = null
        private set

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Task) { "not a task call: ${call.name}" }
        val args = (call.args as Args.Task).args
        if (!mask.allows(call.name)) return result("masked", "${call.name} is masked in this role (propose arrives in P2.1.5, delegate/collect in P4.4.1)")
        return when (args.op) {
            "ask" -> ask(args, context)
            "propose" -> propose(args)
            else -> result("masked", "${call.name} is masked in this role")
        }
    }

    private suspend fun ask(args: TaskArgs, context: TurnContext): ToolOutcome {
        val contract = contracts.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}")
        val question = Question(idGen.next("q"), contract.version, ids, args.question!!, args.options.orEmpty())
        events?.emit(AgentEvent.Ask.Question(ids, question.id))
        val answer = authority.ask(question)
        if (answer == null) return block(question, context, "no answer is available")
        if (Replies.check(answer, contract.version) == ReplyValidity.Superseded) {
            return block(question, context, "the answer is for contract v${answer.contractRevision}, superseded by v${contract.version}")
        }
        val text = answer.text.ifBlank { answer.chosenOption?.let { question.options.getOrNull(it) } ?: "" }
        if (text.isBlank()) return block(question, context, "the answer is empty")
        events?.emit(AgentEvent.Ask.Answered(ids, question.id, answer.changesRequirements))
        return if (answer.changesRequirements) {
            // §4.1: the authority is the message itself — appended verbatim, version bumped, no evidence record needed.
            val amended = contracts.amendByUser(ids.work, text)
            exchanges += Asked(question, answer, context.turn, evidenceEventId = null, amendedToVersion = amended.version)
            result("answered", "answered (amends the contract → v${amended.version}): $text\nquestion ${question.id}: ${question.text}")
        } else {
            val event = journal?.append(JournalEvent(idGen.next("ev"), ids, context.turn, JournalKind.Result, text = "answer to ${question.id} (${question.text}): $text", at = clock.instant()))
            exchanges += Asked(question, answer, context.turn, evidenceEventId = event?.eventId, amendedToVersion = null)
            result("answered", "answered (factual, recorded as evidence${event?.let { " #event ${it.seq}" } ?: ""}; contract stays v${contract.version}): $text\nquestion ${question.id}: ${question.text}")
        }
    }

    private fun propose(args: TaskArgs): ToolOutcome {
        val proposal = args.proposal!!
        if (args.kind == "amendment") {
            val fields = proposal as? JsonObject
            val change = (fields?.get("change") as? JsonPrimitive)?.contentOrNull
            val reason = (fields?.get("reason") as? JsonPrimitive)?.contentOrNull
            if (change.isNullOrBlank() || reason.isNullOrBlank()) return result("rejected", "propose(amendment) needs {\"change\": …, \"reason\": …}")
            if (contracts.current(ids.work) == null) return result("denied", "no committed contract for ${ids.work}")
            // D-69: additions need no amendment (strengthen, plan acceptance), so a model amendment may narrow and is never auto-accepted.
            val amendment = contracts.propose(ids.work, ids.context, change, reason, weakening = true)
            return result("proposed", "amendment ${amendment.id} pending (${amendment.change}); the authority decides — the contract is unchanged until then")
        }
        val intake = proposals ?: return result("masked", "propose(${args.kind}) needs the controller's proposal intake (S1+)")
        return when (val outcome = if (args.kind == "plan") intake.plan(ids, proposal) else intake.incrementSplit(ids, proposal)) {
            is ProposalOutcome.Recorded -> result("proposed", "${args.kind} proposal ${outcome.id} recorded: ${outcome.summary}; the controller validates it — the contract and the graph are unchanged")
            is ProposalOutcome.Refused -> result("rejected", "${args.kind} proposal refused: ${outcome.reason}")
        }
    }

    private fun block(question: Question, context: TurnContext, why: String): ToolOutcome {
        pendingBlock = BlockedRequest("question ${question.id}: $why", emptyList(), question.text, context.turn)
        exchanges += Asked(question, null, context.turn, null, null)
        events?.emit(AgentEvent.Blocked(ids, why, question.id))
        return result("blocked", "blocked with a question ($why): ${question.text}" + (if (question.options.isEmpty()) "" else " · options: ${question.options.joinToString(" | ")}") + "\nthe cell ends blocked after reconciliation; the question is a success path, not a failure")
    }

    private fun result(status: String, body: String): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "task", effectClass = EffectClass.R, versions = emptyMap(), stamp = null, truncated = false, effects = Effects.None,
            flags = InstructionShape.detect(body).flags, runtime = RuntimeFields(idGen.next("act"), status, null, null, "ask", "complete"),
        )
        return ToolOutcome(body, header, tokens = estimator.estimate(body).tokens)
    }
}
