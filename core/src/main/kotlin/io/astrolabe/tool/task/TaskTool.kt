package io.astrolabe.tool.task

import io.astrolabe.auth.InstructionShape
import io.astrolabe.cell.Protocol
import io.astrolabe.Mode
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.EvidencePurpose
import io.astrolabe.contract.MessageKind
import io.astrolabe.contract.Origin
import io.astrolabe.delegate.Assembled
import io.astrolabe.delegate.ChildPacket
import io.astrolabe.delegate.ChildKind
import io.astrolabe.delegate.Collected
import io.astrolabe.delegate.Delegator
import io.astrolabe.delegate.Dispatch
import io.astrolabe.delegate.DispatchMode
import io.astrolabe.delegate.Handle
import io.astrolabe.delegate.Probe
import io.astrolabe.delegate.TaskPackets
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
import io.astrolabe.id.FileVersion
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

/** A `task(finish)` of the direct protocol (A-D.5): the turn it ran in and its summary, `null` when blank. */
internal class FinishRequest(val turn: Int, val text: String?)

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
 * them changes the contract or the graph. `delegate(kind, packet, mode)` (P4.4.1) assembles a [io.astrolabe.delegate.TaskPacket]
 * from the contract's exact excerpts through [TaskPackets] and dispatches it through the [Delegator]; `collect(handle)`
 * reports the child's published packet, a late one, or that it is still running. Both need the controller's delegator (S2+).
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
    private val delegator: Delegator? = null,
    private val packets: TaskPackets? = null,
    /** Current file versions, so a probe's pointers are shown current or stale when collected (§10.2, FX-41). */
    private val versions: (String) -> FileVersion? = { null },
    /**
     * D-344: why this task may not end with an answer now — the tree moved since the campaign's snapshot 0, or an
     * action with effects ran — or `null` when it may. Absent outside the main line: such a cell never answers.
     */
    private val answerCheck: (() -> String?)? = null,
    /**
     * Task-workflow §5.1 (D-435): records a path the task's output on the model's proposal once approved — the harness
     * validates it — and returns why it refused, or `null` once recorded. Absent: this cell cannot declare an output.
     */
    private val declareOutput: ((path: String, reason: String) -> String?)? = null,
    /** `CampaignPolicy.autoDeclareOutputs`: an autonomous campaign records the model's output proposal without a person (off by default). */
    private val autoDeclareOutputs: Boolean = false,
) : ToolExecutor {
    internal var beforeDispatch: () -> Unit = {}

    /** Task-workflow §3.7: the paths of the cell's flagged test edits that wait for an approving review; the cell sets it. */
    internal var flagged: (() -> List<String>)? = null

    /**
     * Task-workflow §3.7: records a person's approval of the flagged [paths] — their answer to [question] — as that
     * person's verdict on the flags, and says so; `null` when it records nothing. The cell sets it; absent: nothing is recorded.
     */
    internal var approveFlags: (suspend (paths: List<String>, question: Question, answer: Answer) -> String?)? = null

    init {
        require(ids.context != null) { "task runs inside a cell: ids.context is its lineage" }
    }

    private val exchanges = ArrayList<Asked>()

    private val handles = LinkedHashMap<String, Handle>()

    /** Every question of this cell with its outcome, oldest first; the compiler pins them (P1.8.2). */
    public val asked: List<Asked> get() = exchanges.toList()

    /** Set when a question got no usable answer; the cell loop ends the cell `blocked` after reconciliation. */
    public var pendingBlock: BlockedRequest? = null
        private set

    private var answered: String? = null

    /**
     * The answer the model ended the task with (D-344), when the facts still hold at the turn's end: the same checks
     * again, since a later call of the same turn may have changed the tree. `null` when there is none or it no
     * longer stands.
     */
    public fun takeAnswer(): String? {
        val text = answered ?: return null
        answered = null
        return text.takeIf { answerCheck?.invoke() == null }
    }

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Task) { "not a task call: ${call.name}" }
        val args = (call.args as Args.Task).args
        if (!mask.allows(call.name)) return result("masked", "${call.name} is masked in this role")
        return when (args.op) {
            "ask" -> ask(args, context)
            "propose" -> propose(args, context)
            "delegate" -> delegate(args)
            "collect" -> collect(args)
            "answer" -> answer(args)
            "finish" -> finish(args, context)
            else -> result("masked", "${call.name} is masked in this role")
        }
    }

    /** The cell's protocol, for the wording of its advice (A-D.7 T6, C4); the cell sets it. */
    internal var protocol: Protocol = Protocol.Structured

    /** A-D.5: the `finish` this cell requested and in which turn; the cell takes it once the turn is dispatched. */
    private var finishRequest: FinishRequest? = null

    /** Whether an `answer` waits for [takeAnswer]: A-D.5 row 1 suppresses a `finish` of the same turn. */
    internal val answerPending: Boolean get() = answered != null

    /** The `finish` requested in [turn], or `null`; an older request is dropped. */
    internal fun takeFinish(turn: Int): FinishRequest? = finishRequest.also { finishRequest = null }?.takeIf { it.turn == turn }

    /** A-D.5 row 2: a question of [turn] got an answer synchronously. */
    internal fun askedAndAnswered(turn: Int): Boolean = exchanges.any { it.turn == turn && it.answer != null }

    /**
     * `finish(text?, after_checks?)` (A-D.5): a completion request the harness decides after the turn — never here, and
     * never by `after_checks`, which is the model's advice only. A second one in the same turn is ignored.
     */
    private fun finish(args: TaskArgs, context: TurnContext): ToolOutcome {
        if (finishRequest?.turn == context.turn) return result("ignored", "duplicate finish ignored")
        finishRequest = FinishRequest(context.turn, args.text?.trim()?.takeIf { it.isNotEmpty() })
        return result("requested", "finish requested: the harness decides after this turn")
    }

    /**
     * `answer(text)` (D-344): the model states that the request needs no change to the files and gives the answer.
     * The harness checks the facts — the tree is the one the campaign opened on and nothing with effects ran — and
     * refuses otherwise; the explicit statement is required, since stopping without changes may just be unfinished work.
     */
    private fun answer(args: TaskArgs): ToolOutcome {
        val check = answerCheck ?: return result("denied", "task.answer ends a task of the main line; this cell cannot end with an answer")
        check()?.let { why ->
            val advice = if (protocol == Protocol.Direct) "finish the work, then call task(finish)" else "Finish the work, then reply with a summary and no tool call: that proposes completion."
            return result("denied", "an answer ends a task that changed nothing, and this one did: $why. $advice")
        }
        answered = args.text!!.trim()
        return result("answered", "answer recorded: the task ends with it and nothing is verified, since nothing changed")
    }

    private suspend fun ask(args: TaskArgs, context: TurnContext): ToolOutcome {
        val contract = contracts.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}")
        // §3.7 (T-42): a question that names every flagged test edit offers the harness's own choice, so a person's approval
        // is structured — the first option chosen — and never read from free text.
        val flags = flagged?.invoke().orEmpty().takeIf { paths -> paths.isNotEmpty() && paths.all { it in args.question!! } }.orEmpty()
        val options = if (flags.isEmpty()) args.options.orEmpty() else listOf("approve the change to ${flags.joinToString(", ")}", "keep it flagged for review")
        val question = Question(idGen.next("q"), contract.version, ids, args.question!!, options)
        events?.emit(AgentEvent.Ask.Question(ids, question.id))
        val answer = authority.ask(question)
        beforeDispatch()
        if (answer == null) return block(question, context, "no answer is available")
        if (answer.questionId != question.id) return block(question, context, "the answer names a different question")
        val current = contracts.current(ids.work)
        if (current == null || current.version != question.contractRevision || Replies.check(answer, current.version) == ReplyValidity.Superseded) {
            return block(question, context, "the answer is for contract v${answer.contractRevision}, superseded by v${current?.version}")
        }
        val text = answer.text.ifBlank { answer.chosenOption?.let { question.options.getOrNull(it) } ?: "" }
        if (text.isBlank()) return block(question, context, "the answer is empty")
        events?.emit(AgentEvent.Ask.Answered(ids, question.id, answer.changesRequirements))
        return if (answer.changesRequirements) {
            // §4.1: the authority is the message itself — appended verbatim, version bumped, no evidence record needed. WR2 (P1 #5,
            // task-workflow §2.4 B, D-317): recorded as this question's answer, which derives its requirement, so its work has a scope.
            val amended = contracts.message(ids.work, MessageKind.Answer, text, answers = question.id, changesRequirements = true)
            exchanges += Asked(question, answer, context.turn, evidenceEventId = null, amendedToVersion = amended.version)
            result("answered", "answered (amends the contract → v${amended.version}): $text\nquestion ${question.id}: ${question.text}")
        } else {
            val event = journal?.append(JournalEvent(idGen.next("ev"), ids, context.turn, JournalKind.Result, text = "answer to ${question.id} (${question.text}): $text", at = clock.instant()))
            exchanges += Asked(question, answer, context.turn, evidenceEventId = event?.eventId, amendedToVersion = null)
            // §3.7: only a person's choice of the approving option is a verdict; a model's or an unknown answerer's resolves nothing.
            val verdict = if (flags.isNotEmpty() && answer.chosenOption == 0 && answer.decider == io.astrolabe.verify.Decider.User) approveFlags?.invoke(flags, question, answer) else null
            result("answered", "answered (factual, recorded as evidence${event?.let { " #event ${it.seq}" } ?: ""}; contract stays v${contract.version}): $text\nquestion ${question.id}: ${question.text}" +
                (verdict?.let { "\n$it" } ?: ""))
        }
    }

    private suspend fun propose(args: TaskArgs, context: TurnContext): ToolOutcome {
        val proposal = args.proposal!!
        if (args.kind == "acceptance") return acceptance(proposal as? JsonObject)
        if (args.kind == "output") return output(proposal as? JsonObject, context)
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
            is ProposalOutcome.Recorded -> result(
                "proposed",
                "${args.kind} proposal ${outcome.id} recorded: ${outcome.summary}; the controller validates it — the contract and the graph are unchanged" +
                    // A-D.7 T6: a direct turn without a call is not a completion request, so it gets no such advice.
                    if (args.kind == "plan" && "gap" !in outcome.summary && protocol != Protocol.Direct) "; end the turn now with a one-line summary and no tool call" else "",
            )
            is ProposalOutcome.Refused -> result("rejected", "${args.kind} proposal refused: ${outcome.reason}")
        }
    }

    /**
     * Task-workflow §3.3 (WD-21): the model states a goal criterion — `{"strengthens": "R1", "run": "<command>", "cwd"?}` or
     * `{"strengthens": "R1", "check": "<claim>"}` — recorded as `model(strengthens R1)`, purpose `goal`: an addition, never
     * a weakening (D-69 applies to change and remove), so an autonomous contract takes it at once and an interactive one
     * asks the authority. It grants no authority to launch anything (D-262): its command runs only through `run`, which
     * binds the receipt to it.
     */
    private suspend fun acceptance(fields: JsonObject?): ToolOutcome {
        val requirement = text(fields, "strengthens")
        val command = text(fields, "run")
        val claim = text(fields, "check")
        if (requirement == null || (command == null) == (claim == null)) {
            return result("rejected", "propose(acceptance) needs {\"strengthens\": \"R1\", \"run\": \"<command>\"} or {\"strengthens\": \"R1\", \"check\": \"<claim>\"}")
        }
        val contract = contracts.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}")
        if (contract.requirement(requirement)?.lapsed != false) return result("rejected", "propose(acceptance) strengthens an open requirement of contract v${contract.version}; $requirement is none")
        var n = contract.acceptance.size + 1
        while (contract.acceptance("AC-$n") != null) n++
        val origin = Origin.Model(requirement)
        // D-52: the version that introduces it — the next one when the authority accepts it in an interactive contract.
        val version = if (contract.mode == Mode.Interactive) contract.version + 1 else contract.version
        val item: Acceptance = if (command != null) {
            Acceptance.Run("AC-$n", Command(command.split(Regex("\\s+")), text(fields, "cwd")), origin, obligationVersion = version, purpose = EvidencePurpose.Goal)
        } else {
            Acceptance.Check("AC-$n", claim!!, origin, obligationVersion = version, purpose = EvidencePurpose.Goal)
        }
        if (contract.acceptance.any { it.origin == origin && it.criterion == item.criterion }) return result("rejected", "the criterion ${item.criterion} already strengthens $requirement")
        val recorded = when (contract.mode) {
            Mode.Autonomous -> contracts.strengthen(ids.work, item).acceptance(item.id) != null
            Mode.Interactive -> {
                val amendment = contracts.propose(ids.work, ids.context, "add acceptance ${item.id} for $requirement: ${item.criterion}", "the model's goal criterion", weakening = false)
                contracts.resolve(ids.work, amendment.id, authority) { c -> c.copy(acceptance = c.acceptance + item) }.acceptance(item.id) != null
            }
        }
        if (!recorded) return result("rejected", "acceptance ${item.id} (${item.criterion}) was not approved; the contract is unchanged")
        val how = if (command != null) "run it through run(${command}) — that run is its evidence; verify does not launch a model-added command (D-262)" else "it needs an accepted evidence reference"
        return result("proposed", "acceptance ${item.id} recorded: ${item.criterion} strengthens $requirement (goal, the model's: an agent test, never independent); $how")
    }

    /**
     * Task-workflow §5.1 (D-435): the model proposes `{"path": …, "reason": …}` as the task's output. Interactive: a question
     * to the user naming the path and the reason; autonomous: refused unless the policy allows auto-declaration. The harness
     * validates every declaration; it takes effect from the next attempt.
     */
    private suspend fun output(fields: JsonObject?, context: TurnContext): ToolOutcome {
        val path = text(fields, "path")
        val reason = text(fields, "reason")
        if (path == null || reason == null) return result("rejected", "propose(output) needs {\"path\": …, \"reason\": …}")
        val declare = declareOutput ?: return result("masked", "propose(output) needs the controller's output intake")
        val contract = contracts.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}")
        if (contract.mode == Mode.Autonomous && !autoDeclareOutputs) {
            return result("rejected", "output $path refused: the policy autoDeclareOutputs is off, so an autonomous task declares no output on the model's proposal; leave it in the candidate")
        }
        if (contract.mode == Mode.Interactive) {
            val question = Question(idGen.next("q"), contract.version, ids, "Declare $path the output of this task (outside the candidate from the next attempt)? $reason", listOf("declare", "keep it in the candidate"))
            events?.emit(AgentEvent.Ask.Question(ids, question.id))
            val answer = authority.ask(question)
            beforeDispatch()
            val approved = answer != null && answer.questionId == question.id && answer.contractRevision == contract.version &&
                (answer.chosenOption == 0 || answer.text.trim().equals("declare", ignoreCase = true) || answer.text.trim().equals("yes", ignoreCase = true))
            exchanges += Asked(question, answer, context.turn, null, null)
            if (!approved) return result("rejected", "output $path not declared: the user did not approve it")
        }
        val refusal = declare(path, reason)
        return if (refusal == null) result("proposed", "output $path declared: it leaves candidate identity from the next attempt; this attempt's candidate is unchanged")
            else result("rejected", "output $path refused: $refusal")
    }

    private fun text(fields: JsonObject?, name: String): String? = (fields?.get(name) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun delegate(args: TaskArgs): ToolOutcome {
        val delegator = delegator ?: return result("masked", "delegate needs the controller's delegator (S2+)")
        val packets = packets ?: return result("masked", "delegate needs the controller's delegator (S2+)")
        val kind = args.kind?.let(ChildKind::of) ?: return result("rejected", "delegate needs kind ${ChildKind.entries.joinToString("|") { it.wire }}, got '${args.kind}'")
        val mode = args.mode?.let(DispatchMode::of) ?: DispatchMode.Sync
        val contract = contracts.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}")
        val packet = when (val assembled = packets.assemble(contract, ids, kind, args.packet)) {
            is Assembled.Invalid -> return result("rejected", "delegate(${kind.wire}) refused: ${assembled.reason}")
            is Assembled.Packet -> assembled.packet
        }
        return when (val dispatch = delegator.dispatch(kind, packet, mode)) {
            is Dispatch.Refused -> result("rejected", "delegate(${kind.wire}) refused (${dispatch.limit.name.lowercase()}): ${dispatch.reason}")
            is Dispatch.Started -> {
                handles[dispatch.handle.id] = dispatch.handle
                val excerpts = packet.requirements.size + packet.constraints.size
                val dispatched = "dispatched ${kind.wire} ${dispatch.handle.id} (${mode.wire}, $excerpts excerpts, ${packet.reservedBudget.value} tokens reserved)"
                if (mode == DispatchMode.Sync) collected(delegator.collect(dispatch.handle), "$dispatched; ")
                else result("dispatched", "$dispatched; collect(handle: ${dispatch.handle.id}) when needed")
            }
        }
    }

    private fun collect(args: TaskArgs): ToolOutcome {
        val delegator = delegator ?: return result("masked", "collect needs the controller's delegator (S2+)")
        val handle = args.handle?.let(handles::get) ?: return result("rejected", "collect needs a handle this cell dispatched, got '${args.handle}'")
        return collected(delegator.collect(handle), "")
    }

    /** §10.1: a child's packet is reported by status and pointers; the parent owns integration and a late result is never integrated. */
    private fun collected(collected: Collected, prefix: String): ToolOutcome = when (collected) {
        is Collected.Pending -> result("pending", "$prefix${collected.handle.id} is still running; collect again later")
        is Collected.Result -> result("collected", "$prefix${collected.handle.id} published a ${collected.packet.kind.wire} packet (${collected.spend.value} tokens); dependencies: ${collected.dependencies.keys.sorted().joinToString(", ").ifEmpty { "none" }}" + view(collected))
        is Collected.Late -> result("rejected", "$prefix${collected.handle.id} is superseded: ${collected.reason}; its ${if (collected.observation == null) "outcome" else "observations are archived and"} cannot be integrated (${collected.spend.value} tokens counted)")
        is Collected.Failed -> result("failed", "$prefix${collected.handle.id} failed: ${collected.reason} (${collected.spend.value} tokens counted)")
        is Collected.Unknown -> result("rejected", "$prefix${collected.handle.id} is not a child of this cell")
    }

    /** §10.2: a probe's findings reach the parent as a bounded summary of pointers, never as KNOWN content. */
    private fun view(collected: Collected.Result): String = when (val packet = collected.packet) {
        is ChildPacket.Investigation -> "\n" + Probe.summary(collected.handle.id, packet.packet, versions, estimator)
        else -> ""
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
