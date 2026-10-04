package io.astrolabe.tool.state

import io.astrolabe.auth.InstructionShape
import io.astrolabe.cell.Protocol
import io.astrolabe.contract.AmendmentStatus
import io.astrolabe.contract.Contracts
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.register.AmendmentLine
import io.astrolabe.register.NotesRender
import io.astrolabe.register.Op
import io.astrolabe.register.Patch
import io.astrolabe.register.PatchOp
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import io.astrolabe.register.RegisterVersions
import io.astrolabe.register.Sizes
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /** D-373: steps this cell ticked without usable evidence; never a progress event. */
    public val unbackedTicks: Set<Int> get() = unbacked.toSet()
    private val unbacked = LinkedHashSet<Int>()

    /** Harness-side (§4.4 cell horizon): `v` facts anchored at the moved path are marked stale in place; only the model clears the mark, by re-verifying. */
    public fun markStale(change: VersionChange) {
        register = register.markStale(change)
    }

    /**
     * Harness-side (§5.8 rebuild): installs the validated form of the current STATE — stale tags added by the carry,
     * nothing the model wrote removed. Refused when the cell or increment differs.
     */
    public fun validated(next: Register) {
        require(next.cell == register.cell && next.increment == register.increment) { "a rebuild validates this cell's STATE, not another's" }
        register = next
    }

    /** Harness-side (P1.5.2): trips whose predicate matches an edited path fire once, rendered by the anchor. */
    public fun fireTrips(editedPaths: Collection<String>) {
        register = register.fireTrips(editedPaths)
    }

    /** Retrieval misses declared so far, oldest first. */
    public val retrievalMisses: List<RetrievalMiss> get() = misses.toList()

    /** A-D.4: the protocol of the cell this tool serves; only a direct cell executes `note`. */
    private var protocol: Protocol = Protocol.Structured

    /** The mask a direct cell's role declares (it lists `state.note`); `null` keeps [mask]. */
    private var roleMask: ToolMask? = null

    /** Where `note(kind=amend)` records its pending amendment (A-D.4 "Amendments"). */
    private var contracts: Contracts? = null

    /** A-D.4: the loop gate ended the previous turn and this turn answers it; the cell sets it before dispatch. */
    internal var noteRequired: Boolean = false

    /** The [pendingBlock] is the capacity exit of A-D.4, which a later applied note of the same turn withdraws. */
    private var capacityBlock = false

    /** The cell of a direct role calls this once, before its first turn (A-D.4, the review notes of D1: mask, then note). */
    internal fun direct(mask: ToolMask, contracts: Contracts?) {
        protocol = Protocol.Direct
        roleMask = mask
        this.contracts = contracts
    }

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.State) { "not a state call: ${call.name}" }
        val args = (call.args as Args.State).args
        if (!(roleMask ?: mask).allows(call.name)) return result("masked", "${call.name} is masked in this role")
        return when (args.op) {
            "patch" -> patch(args, context)
            // A-D.3 rule 5: the note branch is the direct protocol's; a structured cell's mask never lists it.
            "note" -> if (protocol == Protocol.Direct) note(args, context) else result("masked", "${call.name} is masked in this role")
            "blocked" -> blocked(args, context)
            "retrieval_miss" -> retrievalMiss(args, context)
            else -> result("masked", "unknown state op '${args.op}'")
        }
    }

    /**
     * `state(note)` (kernel contract A-D.4): one note becomes one patch of the existing register ops, validated by the same
     * [Validator] (the direct protocol switches its Next rule off and archives before the cap) and committed atomically.
     * A field its kind does not take refuses the note when it changes the meaning, and is ignored and named otherwise.
     */
    private fun note(args: StateArgs, context: TurnContext): ToolOutcome {
        val raw = args.note as? JsonObject ?: return noteRejected("schema", "state(note) needs note={kind, text, evidence?, closes?, refutes?}", emptyList(), context)
        val notes = ArrayList<String>()
        (raw.keys - NOTE_FIELDS).takeIf { it.isNotEmpty() }?.let { ignored ->
            notes += "ignored ${ignored.joinToString(", ") { "'$it'" }} — note{kind, text, evidence?, closes?, refutes?}"
        }
        val kind = (raw["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
        if (kind !in NOTE_KINDS) {
            return noteRejected("schema", "kind is one of ${NOTE_KINDS.joinToString(", ")}" + (raw["kind"]?.let { " (got $it)" } ?: " (none given)"), notes, context)
        }
        val closes = raw["closes"]?.let { number(it) ?: return noteRejected("schema", "closes takes the number of an open note, e.g. 3", notes, context) }
        val refutes = raw["refutes"]?.let { number(it) ?: return noteRejected("schema", "refutes takes the number of a hypothesis, e.g. 2", notes, context) }
        if (closes != null && kind != "open") return noteRejected("schema", "closes is valid only with kind=open", notes, context)
        if (refutes != null && kind != "deadend") return noteRejected("schema", "refutes is valid only with kind=deadend", notes, context)
        val text = if (closes != null) {
            if (raw["text"] != null) notes += "text ignored with closes"
            ""
        } else {
            (raw["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: return noteRejected("schema", "text is required: one line, no code", notes, context)
        }
        var evidence = raw["evidence"]?.let { evidence(it, notes) ?: return noteRejected("schema", "evidence is #N (a stored result) or op:N (a run or verify call of this turn)", notes, context) }
        if (evidence != null && (kind == "decision" || kind == "amend" || (kind == "open" && closes == null))) {
            notes += "evidence ignored with kind=$kind"
            evidence = null
        }
        val op: List<Op> = when (kind) {
            "hypothesis" -> listOf(
                Op.FactAdd(if (evidence != null) ClaimKind.Verified else ClaimKind.Hypothesis, text, evidence, evidence?.let { validation.observedFile(it) }),
            )
            "decision" -> listOf(Op.DecisionAdd(text, ""))
            "deadend" -> {
                val dead = Op.DeadendAdd(text, evidence, DEADEND_SCOPE, DEADEND_REOPEN)
                if (refutes == null) {
                    listOf(dead)
                } else {
                    // A-D.4: both checked before the patch is built, so a refutation is never skipped while its dead end stays.
                    val proof = evidence ?: return noteRejected("schema", "refutes needs evidence: #N or op:N", notes, context)
                    if (register.fact(refutes) == null) return noteRejected("unknown fact", "fact.refute($refutes)", notes, context)
                    if (!validation.evidenceExists(proof)) return noteRejected("refute needs an existing evidence id", "fact.refute($refutes): $proof", notes, context)
                    listOf(Op.FactRefute(refutes, proof), dead)
                }
            }
            "open" -> if (closes == null) listOf(Op.OpenAdd(text)) else {
                listOf(Op.OpenClose(closes, evidence ?: return noteRejected("schema", "closes needs evidence: #N or op:N", notes, context)))
            }
            else -> listOf(Op.AmendPropose(text, AMEND_REASON))
        }
        return when (val checked = validator.check(register, Patch(op.map { PatchOp(it) }), validation, ::decided)) {
            is Validation.Rejected -> noteRejected(checked, notes, context)
            is Validation.Applied -> {
                // A-D.4: one note is one whole patch — an op the validator skipped (a line rule on the dead end of a
                // refutation, say) refuses the whole note, and nothing is recorded.
                if (checked.appliedOps.size < op.size) {
                    val skipped = checked.notes.filter { "skipped: " in it }
                    val rule = skipped.firstNotNullOfOrNull { Regex("""skipped: (.+?) — """).find(it)?.groupValues?.get(1) } ?: "note"
                    return noteRejected(Validation.Rejected(rule, skipped.joinToString("; "), checked.sizes.copy(registerTokens = RegisterRender.tokens(register, estimator))), notes, context)
                }
                var next = if (checked.register.version > register.version) checked.register else checked.register.copy(version = register.version + 1)
                if (kind == "amend") {
                    val work = contracts ?: return result("denied", "no contract repository is attached to this cell; the note had no effect")
                    val current = work.current(ids.work) ?: return result("denied", "no committed contract for ${ids.work}; the note had no effect")
                    // A-D.4: the pending amendment first, reused when the same change is pending; then the line with its id.
                    val amendment = current.amendmentsPending.firstOrNull { it.status == AmendmentStatus.Pending && it.change.trim() == text }
                        ?: work.propose(ids.work, ids.context, text, AMEND_REASON, weakening = true)
                    next = next.copy(amendments = next.amendments.dropLast(1) + next.amendments.last().copy(id = amendment.id))
                }
                versions.save(ids, next)
                val before = register
                register = next
                lastRejection = null
                // A-D.4: the note the loop gate required is recorded; a later refusal of the turn no longer ends the cell.
                noteRequired = false
                if (capacityBlock) {
                    pendingBlock = null
                    capacityBlock = false
                }
                events?.emit(AgentEvent.Cell.RegisterPatched(ids, next.version, checked.appliedOps.size))
                val id = noteId(kind!!, before, next, closes)
                val lines = ArrayList<String>()
                lines += "STATE v${next.version} · note $id recorded · register ${checked.sizes.registerTokens}/${checked.sizes.registerCapTokens} tokens"
                if (refutes != null) notes += "h$refutes refuted"
                if (closes != null) notes += "o$closes closed"
                (notes + checked.notes).forEach { lines += "note: $it" }
                result("ok", lines.joinToString("\n"), applied = true)
            }
        }
    }

    /** The id of the note [kind] just recorded, from the register before and after (A-D.4 ids). */
    private fun noteId(kind: String, before: Register, after: Register, closes: Int?): String = when (kind) {
        "hypothesis" -> after.facts.filter { f -> before.fact(f.n) == null }.maxByOrNull { it.n }?.let(NotesRender::factId) ?: "h?"
        "decision" -> "d${after.decisions.maxOf { it.n }}"
        "deadend" -> "dead${after.deadEnds.maxOf { it.n }}"
        "open" -> "o${closes ?: after.open.maxOf { it.n }}"
        else -> "a${after.amendmentPositions().last()}"
    }

    /** A pending line is decided once its contract amendment is no longer pending (A-D.4 archive order). */
    private fun decided(line: AmendmentLine): Boolean {
        if (line.status != "pending") return true
        val id = line.id ?: return false
        val current = contracts?.current(ids.work) ?: return false
        return current.amendmentsPending.none { it.id == id && it.status == AmendmentStatus.Pending }
    }

    private fun noteRejected(rule: String, detail: String, notes: List<String>, context: TurnContext): ToolOutcome {
        val tokens = RegisterRender.tokens(register, estimator)
        return noteRejected(Validation.Rejected(rule, detail, Sizes(tokens, validatorCap(), 0, 0)), notes, context)
    }

    private fun noteRejected(rejected: Validation.Rejected, notes: List<String>, context: TurnContext): ToolOutcome {
        lastRejection = rejected
        val sizes = rejected.sizes
        val lines = ArrayList<String>()
        lines += "STATE v${register.version} unchanged · rejected: ${rejected.rule} — ${rejected.detail} · register ${sizes.registerTokens}/${sizes.registerCapTokens} tokens"
        notes.forEach { lines += "note: $it" }
        // A-D.4: the one capacity case that ends the cell — the loop gate requires this note and it cannot be recorded.
        if (rejected.rule == "register cap" && noteRequired && pendingBlock == null) {
            val reason = "register capacity: ${sizes.registerTokens}/${sizes.registerCapTokens} tokens of active notes after archiving; the note the loop gate requires cannot be recorded"
            pendingBlock = BlockedRequest(reason, emptyList(), null, context.turn)
            capacityBlock = true
            events?.emit(AgentEvent.Blocked(ids, reason))
            lines += "the loop gate requires a note and none can be recorded: the cell ends blocked"
        }
        return result("rejected", lines.joinToString("\n"))
    }

    /** The register cap the validator applies; a rejection before validation reports the same denominator. */
    private fun validatorCap(): Int = validator.schemaRejection(register, "", "").sizes.registerCapTokens

    /** `closes`/`refutes`: an integer, or a string of digits with an id prefix (`3`, `"3"`, `"o3"`, `"h2"`). */
    private fun number(value: JsonElement): Int? {
        val p = value as? JsonPrimitive ?: return null
        val text = p.content.trim().lowercase()
        return (Regex("""^[a-z]*(\d+)$""").matchEntire(text)?.groupValues?.get(1) ?: return null).toIntOrNull()?.takeIf { it >= 1 }
    }

    /** One evidence id: `#N` or a stored id kept, `op:N` resolved to this turn's result alias; extra ids are named in [notes]. */
    private fun evidence(value: JsonElement, notes: MutableList<String>): String? {
        val named = when {
            value is JsonPrimitive && value.isString -> value.content.trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
            value is JsonArray && value.all { it is JsonPrimitive && it.isString } -> value.map { (it as JsonPrimitive).content.trim() }.filter { it.isNotEmpty() }
            else -> return null
        }
        val first = named.firstOrNull() ?: return null
        if (named.size > 1) notes += "one evidence slot — kept $first, also cited ${named.drop(1).joinToString(" ")}"
        return if (first.startsWith("op:")) first.removePrefix("op:").toIntOrNull()?.let { opResults[it] } ?: first else first
    }

    private fun patch(args: StateArgs, context: TurnContext): ToolOutcome {
        var notes: List<String> = emptyList()
        val parsed = when (val p = PatchParser.parse(args.patch.orEmpty(), opResults, validation)) {
            is ParsedPatch.Invalid -> {
                lastRejection = validator.schemaRejection(register, kotlinx.serialization.json.JsonArray(args.patch.orEmpty()).toString(), p.reason)
                return result("rejected", "STATE v${register.version} unchanged · rejected: schema — ${p.reason}")
            }
            is ParsedPatch.Valid -> p.patch.also { notes = p.notes }
        }
        val noted = notes.joinToString("") { "\nnote: $it" }
        return when (val validation = validator.check(register, parsed, validation)) {
            is Validation.Rejected -> {
                lastRejection = validation
                result(
                    "rejected",
                    "STATE v${register.version} unchanged · rejected: ${validation.rule} — ${validation.detail} · register ${validation.sizes.registerTokens}/${validation.sizes.registerCapTokens} tokens · patch ${validation.sizes.patchTokens}/${validation.sizes.patchCapTokens} tokens$noted",
                )
            }
            is Validation.Applied -> {
                val next = if (validation.register.version > register.version) validation.register else validation.register.copy(version = register.version + 1)
                versions.save(ids, next)
                register = next
                lastRejection = null
                unbacked += validation.unbackedTicks
                events?.emit(AgentEvent.Cell.RegisterPatched(ids, next.version, validation.appliedOps.size))
                val lines = ArrayList<String>()
                lines += "STATE v${next.version} · applied ${validation.appliedOps.size} op${if (validation.appliedOps.size == 1) "" else "s"} · register ${validation.sizes.registerTokens}/${validation.sizes.registerCapTokens} tokens"
                validation.dropped.forEach { d -> lines += "⟨dropped ${d.op::class.simpleName?.lowercase()}: if ${d.condition} not met⟩" }
                validation.flags.forEach { lines += "flag: $it" }
                (notes + validation.notes).forEach { lines += "note: $it" }
                result("ok", lines.joinToString("\n"), applied = true)
            }
        }
    }

    private fun blocked(args: StateArgs, context: TurnContext): ToolOutcome {
        val b = args.blocked!!
        pendingBlock = BlockedRequest(b.reason, b.evidence, b.question, context.turn)
        // The model's own block replaces a capacity exit: a later applied note must not withdraw it.
        capacityBlock = false
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
        private val NOTE_KINDS = listOf("hypothesis", "decision", "deadend", "open", "amend")
        private val NOTE_FIELDS = setOf("kind", "text", "evidence", "closes", "refutes")

        // A-D.4 field mapping: the defaults satisfy the validator's non-blank rule for dead ends.
        private const val DEADEND_SCOPE = "task"
        private const val DEADEND_REOPEN = "new evidence"
        private const val AMEND_REASON = "stated in the change"

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
