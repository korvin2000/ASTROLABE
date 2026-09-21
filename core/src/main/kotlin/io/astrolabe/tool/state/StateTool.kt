package io.astrolabe.tool.state

import io.astrolabe.auth.InstructionShape
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterVersions
import io.astrolabe.register.Validation
import io.astrolabe.register.ValidationContext
import io.astrolabe.register.Validator
import io.astrolabe.tool.Args
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.StateArgs
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workspace.VersionChange
import java.time.Clock

/** A `state(blocked)` the cell must end with, after reconciliation (§5.9: `blocked` with a question is a success path). */
public data class BlockedRequest(val reason: String, val evidence: List<String>, val question: String?, val turn: Int)

/** A `state(retrieval_miss)`: a labelled negative — what the model needed and could not find (feeds P4.1.3). */
public data class RetrievalMiss(val need: String, val why: String, val turn: Int, val eventId: String?)

/**
 * The `state` family (§5.2, §5.4, TODO P1.6.8): `patch` applies typed register ops through the [Validator] —
 * rejection returns the violated rule and the sizes and leaves STATE unchanged, an applied patch bumps the
 * register version and persists it; `blocked` records the reason, evidence and question the cell ends with
 * after reconciliation; `retrieval_miss` journals a labelled negative that is searchable in the store.
 */
public class StateTool(
    private val validator: Validator,
    private val versions: RegisterVersions,
    private val journal: Journal?,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    register: Register,
    private val events: Events? = null,
    private val mask: ToolMask = ToolOps.implementingS0,
) : ToolExecutor {
    init {
        require(ids.context != null) { "state runs inside a cell: ids.context is its lineage" }
    }

    /** The cell's current STATE; only an applied patch replaces it. */
    public var register: Register = register
        private set

    /** What the validator may consult this turn; the cell refreshes it before dispatch. */
    public var validation: ValidationContext = EMPTY_CONTEXT

    /** `op:N` → result alias for the turn, so `evidence: "op:2"` resolves to a store id. */
    public var opResults: Map<Int, String> = emptyMap()

    /** Set by `state(blocked)`; the cell loop reads it at the end of the turn. */
    public var pendingBlock: BlockedRequest? = null
        private set

    /** The validator's refusal of the latest `patch`, `null` once a patch applied; the loop feeds it to the register gates (§5.6). */
    public var lastRejection: Validation.Rejected? = null
        private set

    private val misses = ArrayList<RetrievalMiss>()

    /** Harness-side (§4.4 cell horizon): `v` facts anchored at the moved path are marked stale in place; only the model clears the mark, by re-verifying. */
    public fun markStale(change: VersionChange) {
        register = register.markStale(change)
    }

    /** Harness-side (P1.5.2): trips whose predicate matches an edited path fire once, rendered by the anchor. */
    public fun fireTrips(editedPaths: Collection<String>) {
        register = register.fireTrips(editedPaths)
    }

    /** Retrieval misses declared so far, oldest first. */
    public val retrievalMisses: List<RetrievalMiss> get() = misses.toList()

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.State) { "not a state call: ${call.name}" }
        val args = (call.args as Args.State).args
        if (!mask.allows(call.name)) return result("masked", "${call.name} is masked in this role")
        return when (args.op) {
            "patch" -> patch(args, context)
            "blocked" -> blocked(args, context)
            "retrieval_miss" -> retrievalMiss(args, context)
            else -> result("masked", "unknown state op '${args.op}'")
        }
    }

    private fun patch(args: StateArgs, context: TurnContext): ToolOutcome {
        val parsed = when (val p = PatchParser.parse(args.patch.orEmpty(), opResults)) {
            is ParsedPatch.Invalid -> return result("rejected", "STATE v${register.version} unchanged · rejected: schema — ${p.reason}")
            is ParsedPatch.Valid -> p.patch
        }
        return when (val validation = validator.check(register, parsed, validation)) {
            is Validation.Rejected -> {
                lastRejection = validation
                result(
                    "rejected",
                    "STATE v${register.version} unchanged · rejected: ${validation.rule} — ${validation.detail} · register ${validation.sizes.registerTokens}/${validation.sizes.registerCapTokens} tokens · patch ${validation.sizes.patchTokens}/${validation.sizes.patchCapTokens} tokens",
                )
            }
            is Validation.Applied -> {
                lastRejection = null
                val next = if (validation.register.version > register.version) validation.register else validation.register.copy(version = register.version + 1)
                register = next
                versions.save(ids, next)
                events?.emit(AgentEvent.Cell.RegisterPatched(ids, next.version, validation.appliedOps.size))
                val lines = ArrayList<String>()
                lines += "STATE v${next.version} · applied ${validation.appliedOps.size} op${if (validation.appliedOps.size == 1) "" else "s"} · register ${validation.sizes.registerTokens}/${validation.sizes.registerCapTokens} tokens"
                validation.dropped.forEach { d -> lines += "⟨dropped ${d.op::class.simpleName?.lowercase()}: if ${d.condition} not met⟩" }
                validation.flags.forEach { lines += "flag: $it" }
                result("ok", lines.joinToString("\n"), applied = true)
            }
        }
    }

    private fun blocked(args: StateArgs, context: TurnContext): ToolOutcome {
        val b = args.blocked!!
        pendingBlock = BlockedRequest(b.reason, b.evidence, b.question, context.turn)
        events?.emit(AgentEvent.Blocked(ids, b.reason))
        val body = "blocked: ${b.reason}" + (if (b.evidence.isEmpty()) "" else " · evidence: ${b.evidence.joinToString(", ")}") + (b.question?.let { " · question: $it" } ?: "") +
            "\nthe cell ends blocked after reconciliation; a question is a success path, not a failure"
        return result("blocked", body)
    }

    private fun retrievalMiss(args: StateArgs, context: TurnContext): ToolOutcome {
        val m = args.retrievalMiss!!
        val event = journal?.append(
            JournalEvent(
                eventId = idGen.next("ev"), ids = ids, turn = context.turn, kind = JournalKind.Result,
                text = "retrieval_miss: need=${m.need} · why=${m.why}", at = clock.instant(),
            ),
        )
        misses += RetrievalMiss(m.need, m.why, context.turn, event?.eventId)
        return result("ok", "retrieval miss recorded as a labelled negative: need=${m.need} · why=${m.why}" + (event?.let { " (#event ${it.seq})" } ?: ""))
    }

    private fun result(status: String, body: String, applied: Boolean = false): ToolOutcome {
        val header = EnvelopeHeader(
            resultAlias = "#-", tool = "state", effectClass = EffectClass.R, versions = emptyMap(), stamp = null, truncated = false, effects = Effects.None,
            flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(idGen.next("act"), status, null, null, "STATE v${register.version}", "complete"),
        )
        return ToolOutcome(body, header, applied = applied, tokens = estimator.estimate(body).tokens)
    }

    public companion object {
        /** A context with no evidence, no green accepts and no red checks: what a fresh cell starts with. */
        @JvmField
        public val EMPTY_CONTEXT: ValidationContext = object : ValidationContext {
            override fun evidenceExists(id: String): Boolean = false
            override fun acceptGreen(accept: String): Boolean = false
            override val redChecks: Set<String> = emptySet()
            override val greenOps: Set<Int> = emptySet()
            override val appliedOps: Set<Int> = emptySet()
        }
    }
}
