package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.ReserveVerdict
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.Digest
import io.astrolabe.register.Mark
import io.astrolabe.register.Register
import io.astrolabe.register.Validation
import io.astrolabe.register.Validator
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.verify.Assessment
import io.astrolabe.verify.Currency
import io.astrolabe.verify.ExitGate
import io.astrolabe.verify.GateResult
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.verify.Verdict
import kotlin.math.ceil

/** The five progress events of §5.6: what distinguishes work from a stall. */
public enum class ProgressKind {
    /** A plan step ticked `[x]` on an evidence id or a green `accept:` (the validator admits no other tick). */
    EvidenceTick,

    /** A `v` fact with an evidence id restating a prior `h` fact: the hypothesis became verified. */
    HypothesisVerified,

    /** An acceptance item newly certified at the current stamp by a green run. */
    GreenAcceptanceRun,

    /** A new `v` fact with an evidence id that was not a prior hypothesis. */
    VerifiedFact,

    /** A new dead end recorded with scope and reopen condition. */
    DeadEnd,
}

/** One progress event: [kind], the [turn] it happened in and the register or acceptance item it refers to. */
public data class ProgressEvent(val kind: ProgressKind, val turn: Int, val ref: String)

/**
 * Detects §5.6 progress events from records only: the register before and after the turn's patch and the
 * acceptance ids certified before and after the turn. Nothing the model says counts; an `h` fact, a plan
 * step added, a decision or a Next line is not progress — only evidence-backed movement is.
 */
public object Progress {
    @JvmStatic
    @JvmOverloads
    public fun events(
        before: Register,
        after: Register,
        turn: Int,
        certifiedBefore: Set<String> = emptySet(),
        certifiedAfter: Set<String> = emptySet(),
    ): List<ProgressEvent> {
        val out = ArrayList<ProgressEvent>()
        val doneBefore = before.plan.filter { it.mark == Mark.Done }.map { it.n }.toSet()
        // A tick passes the validator only with an evidence id or a green `accept:`; both are evidence-backed.
        after.plan.filter { it.mark == Mark.Done && it.n !in doneBefore && (it.evidence != null || it.accept != null) }
            .forEach { out += ProgressEvent(ProgressKind.EvidenceTick, turn, "step ${it.n}") }
        val hypotheses = before.facts.filter { it.kind == ClaimKind.Hypothesis }.map { normalize(it.text) }.toSet()
        val factsBefore = before.facts.map { it.n }.toSet()
        after.facts.filter { it.n !in factsBefore && it.kind == ClaimKind.Verified && it.evidenceId != null }.forEach { fact ->
            val kind = if (normalize(fact.text) in hypotheses) ProgressKind.HypothesisVerified else ProgressKind.VerifiedFact
            out += ProgressEvent(kind, turn, "fact ${fact.n}")
        }
        (certifiedAfter - certifiedBefore).sorted().forEach { out += ProgressEvent(ProgressKind.GreenAcceptanceRun, turn, it) }
        val deadEndsBefore = before.deadEnds.map { it.n }.toSet()
        after.deadEnds.filter { it.n !in deadEndsBefore }.forEach { out += ProgressEvent(ProgressKind.DeadEnd, turn, "dead end ${it.n}") }
        return out
    }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")
}

/**
 * The loop gate's identity of one executed call: `(tool, args, result hash)` (§5.6). Two identical
 * signatures mean the model asked the same question and got the same answer.
 */
public data class CallSignature(val tool: String, val argsDigest: Digest, val resultDigest: Digest) {
    public companion object {
        @JvmStatic
        public fun of(call: ToolCall, outcome: ToolOutcome): CallSignature =
            CallSignature(call.name, Digest.ofUtf8(call.raw.toString()), Digest.ofUtf8(outcome.body))
    }
}

/** Identifies one condition of one gate; a nudge with a key already in [GateState.fired] is not repeated. */
public data class GateKey(val gate: String, val condition: String) {
    init {
        require(gate.isNotBlank() && condition.isNotBlank()) { "a gate key names its gate and condition" }
    }
}

/** What a gate produced: one line for `[A]`, or a refusal the loop must honour. */
public sealed interface GateOutcome {
    public val key: GateKey
    public val line: String

    /** One line in `[A]`, shown once per condition (§5.6). */
    public data class Nudge(override val key: GateKey, override val line: String) : GateOutcome

    /**
     * The action (a patch, a completion proposal, the third identical call) is refused; [details] is what the
     * anchor lists, [endsTurn] and [requiredOp] carry the loop gate's "ends the turn with a required `state` op".
     * A refusal is never deduplicated: a refused action attempted again is refused again.
     */
    public data class Rejection(
        override val key: GateKey,
        override val line: String,
        val details: List<String> = emptyList(),
        val endsTurn: Boolean = false,
        val requiredOp: String? = null,
    ) : GateOutcome
}

/**
 * Everything the harness knows at the point the gates run (after dispatch and the turn's register patch),
 * as records: no gate reads a clock, a store or the model's prose. [fired] is the once-per-condition memory
 * the loop carries between turns and replaces with [GateReport.fired]. Later gates (scope, acceptance
 * surface, impact, contract touch, repeated failure signature) add their inputs here as defaulted fields.
 */
public data class GateState @JvmOverloads constructor(
    /** 1-based turn just executed. */
    val turn: Int,
    val register: Register,
    val contract: Contract,
    val increment: Increment,
    /** The turn's validated calls, in emitted order. */
    val calls: List<ToolCall> = emptyList(),
    /** Every executed call of the cell so far, this turn's included. */
    val signatures: List<CallSignature> = emptyList(),
    /** The turn's register patch when the validator refused it. */
    val patchRejection: Validation.Rejected? = null,
    /** The turn of the last progress event; 0 when none since cell start. */
    val lastProgressTurn: Int = 0,
    /** A run handle is live and produced output this turn: work, not a stall (§5.6). */
    val liveRunOutput: Boolean = false,
    /** Window occupancy in tokens against the model's `C_max`. */
    val contextTokens: Long = 0,
    val contextMaxTokens: Long = 0,
    /** Rebuilds (§5.8) already performed in this cell. */
    val rebuilds: Int = 0,
    val reserve: ReserveVerdict = ReserveVerdict(false, null, null),
    val turnsMax: Int = 0,
    /** True when the turn ended with a `done` completion proposal; the exit gate then runs on the evidence below. */
    val completionProposed: Boolean = false,
    val currencies: Map<String, Currency> = emptyMap(),
    val assessments: List<Assessment> = emptyList(),
    val reviews: Map<String, Verdict> = emptyMap(),
    val flags: List<TestIntegrityFlag> = emptyList(),
    val unresolvedImpactNudges: List<String> = emptyList(),
    val fired: Set<GateKey> = emptySet(),
    val defaults: Defaults = Defaults(),
    /** Paths this turn's edits wrote inside the contract but outside the increment's write scope (§8.6). */
    val outsideIncrement: List<String> = emptyList(),
    /** Acceptance-surface flags raised this turn, with the classifier's kind (§8.6). */
    val surfaceFlags: List<TestIntegrityFlag> = emptyList(),
    /** Paths this turn's edits and runs moved. */
    val editedPaths: Set<String> = emptySet(),
    /** Active `CON` notes as `id@revision` → anchored paths; empty while no CON note exists (the gate stays inactive). */
    val contractAnchors: Map<String, Set<String>> = emptyMap(),
    /** Normalized failure signatures still red after two repairs (§5.6; fingerprints proper arrive with P4.6.2). */
    val repeatedFailures: List<String> = emptyList(),
) {
    init {
        require(turn >= 1) { "turn is 1-based, got $turn" }
        require(lastProgressTurn in 0..turn) { "lastProgressTurn $lastProgressTurn is outside 0..$turn" }
        require(rebuilds >= 0 && contextTokens >= 0 && contextMaxTokens >= 0 && turnsMax >= 0) { "counts are never negative" }
    }
}

/** One gate: a pure function of [GateState]. Later phases register theirs alongside the S0 set. */
public interface Gate {
    public val name: String

    public fun evaluate(state: GateState): List<GateOutcome>
}

/** What [Gates.evaluate] returns: the outcomes in gate order and the once-per-condition memory to carry forward. */
public data class GateReport(val outcomes: List<GateOutcome>, val fired: Set<GateKey>) {
    val nudges: List<GateOutcome.Nudge> get() = outcomes.filterIsInstance<GateOutcome.Nudge>()

    val rejections: List<GateOutcome.Rejection> get() = outcomes.filterIsInstance<GateOutcome.Rejection>()

    /** The `[A]` lines, nudges first; [Anchor] applies its own cap. */
    val lines: List<String> get() = nudges.map { it.line } + rejections.map { it.line }
}

/**
 * The gate set (§5.6): computed by the harness, no judge model, one line each, fired once per condition.
 * Gates run in registration order; a nudge whose [GateKey] is already in [GateState.fired] is dropped and
 * every emitted key joins [GateReport.fired]. Rejections pass every time, because a refusal that is
 * silenced the second time is a gate that opens.
 */
public class Gates(gates: List<Gate>) {
    public val gates: List<Gate> = gates.toList()

    init {
        val names = this.gates.map { it.name }
        require(names.toSet().size == names.size) { "gate names must be unique: $names" }
    }

    /** The registered set plus [more], in that order. */
    public fun with(vararg more: Gate): Gates = Gates(gates + more)

    public fun evaluate(state: GateState): GateReport {
        val fired = LinkedHashSet(state.fired)
        val out = ArrayList<GateOutcome>()
        for (gate in gates) {
            for (outcome in gate.evaluate(state)) {
                require(outcome.key.gate == gate.name) { "gate '${gate.name}' emitted a key of '${outcome.key.gate}'" }
                if (outcome is GateOutcome.Nudge && !fired.add(outcome.key)) continue
                if (outcome is GateOutcome.Rejection) fired.add(outcome.key)
                out += outcome
            }
        }
        return GateReport(out, fired)
    }

    public companion object {
        public const val ENTRY: String = "entry"
        public const val EXIT: String = "exit"
        public const val PRESSURE: String = "pressure"
        public const val STALL: String = "stall"
        public const val LOOP: String = "loop"
        public const val CURSOR: String = "cursor"
        public const val RED_NOT_RECORDED: String = "red-not-recorded"
        public const val STALE_FACT: String = "stale-fact"
        public const val REGISTER: String = "register"
        public const val RESERVE: String = "reserve"
        public const val TURNS: String = "turns"
        public const val CONTRACT_TOUCH: String = "contract-touch"
        public const val REPEATED_FAILURE: String = "repeated-failure"
        public const val SCOPE: String = "scope"
        public const val ACCEPTANCE_SURFACE: String = "acceptance-surface"

        /**
         * The S0 set, in the order of the §5.6 table; contract touch, repeated failure, scope and acceptance surface
         * are registered too (P3.4.3). Impact (P3.2.4) and judge-dependent gates are not.
         */
        @JvmStatic
        public fun s0(): Gates = Gates(listOf(Entry, Exit, Pressure, Stall, Loop, RegisterInvariants, StaleFact, ContractTouch, RepeatedFailure, Scope, AcceptanceSurfaceGate, Reserve, Turns))
    }

    // §5.6 Contract touch: an edit set touches anchors of a CON note; active once any CON note exists.
    private object ContractTouch : Gate {
        override val name: String get() = CONTRACT_TOUCH

        override fun evaluate(state: GateState): List<GateOutcome> = state.contractAnchors.mapNotNull { (note, anchors) ->
            val touched = state.editedPaths.filter { it in anchors }.sorted()
            if (touched.isEmpty()) return@mapNotNull null
            GateOutcome.Nudge(GateKey(name, note), "contract $note touched (${touched.joinToString(", ")}): an ADR in the main line is required before this lands")
        }
    }

    // §5.6 Repeated failure signature: the same normalized error after 2 repairs.
    private object RepeatedFailure : Gate {
        override val name: String get() = REPEATED_FAILURE

        override fun evaluate(state: GateState): List<GateOutcome> = state.repeatedFailures.map { signature ->
            GateOutcome.Nudge(GateKey(name, signature), "same failure twice ($signature): change the hypothesis, record a dead end, or request an alternative attempt")
        }
    }

    // §5.6 Scope: outside the increment, inside the contract — warned once; the edit tool refuses an unjustified repeat (D-74).
    private object Scope : Gate {
        override val name: String get() = SCOPE

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (state.outsideIncrement.isEmpty()) return emptyList()
            return listOf(
                GateOutcome.Nudge(
                    GateKey(name, "outside-increment"),
                    "scope: ${state.outsideIncrement.sorted().joinToString(", ")} outside the increment's write scope — the next crossing needs task.propose(increment_split) or the path justified in why",
                ),
            )
        }
    }

    // §5.6 Acceptance surface: a flagged line with the classifier's kind; justified in the packet; review when it weakens a required check.
    private object AcceptanceSurfaceGate : Gate {
        override val name: String get() = ACCEPTANCE_SURFACE

        override fun evaluate(state: GateState): List<GateOutcome> = state.surfaceFlags.filter { it.blocksCompletion }.map { flag ->
            GateOutcome.Nudge(GateKey(name, "${flag.path}:${flag.kind}"), "acceptance surface: ${flag.path} · ${flag.kind} — justify it in the packet; a weakened required check needs review")
        }
    }

    // §5.6 Entry: first non-register edit while no plan step carries an `accept:` or the increment's acceptance is unresolved.
    private object Entry : Gate {
        override val name: String get() = ENTRY

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (state.calls.none { it.family == ToolFamily.Edit }) return emptyList()
            val noAcceptStep = state.register.plan.none { it.accept != null }
            val unresolved = state.increment.accept.filter { state.contract.acceptance(it) == null }
            val why = when {
                state.increment.accept.isEmpty() -> "the increment declares no acceptance"
                unresolved.isNotEmpty() -> "acceptance ${unresolved.joinToString(", ")} is not in contract v${state.contract.version}"
                noAcceptStep -> "no plan step carries an accept:"
                else -> return emptyList()
            }
            return listOf(GateOutcome.Nudge(GateKey(name, "first-edit"), "entry: editing while $why — write the acceptance crisply, or ask one question (task.ask)"))
        }
    }

    // §5.6 Exit (hard): the P1.7.7 gate, called, never restated.
    private object Exit : Gate {
        override val name: String get() = EXIT

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (!state.completionProposed) return emptyList()
            val result = ExitGate.evaluate(state.register, state.contract, state.increment, state.currencies, state.assessments, state.reviews, state.flags, state.unresolvedImpactNudges)
            if (result !is GateResult.Refused) return emptyList()
            return listOf(
                GateOutcome.Rejection(
                    GateKey(name, "v${state.register.version}:" + Digest.ofUtf8(result.missing.joinToString("\n")).hash8),
                    "exit refused: ${result.missing.size} missing — escape only via state(blocked) or task.ask with evidence",
                    details = result.missing,
                ),
            )
        }
    }

    // §5.6 Pressure: tokens > α·C_max; the first condition asks for a rebuild, the second for a partial exit (P1.8.7 acts).
    private object Pressure : Gate {
        override val name: String get() = PRESSURE

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (state.contextMaxTokens <= 0 || state.contextTokens <= state.defaults.alpha * state.contextMaxTokens) return emptyList()
            val percent = state.contextTokens * 100 / state.contextMaxTokens
            val action = if (state.rebuilds == 0) "fold what matters into STATE; the harness rebuilds" else "second rebuild: partial with a replan hint"
            return listOf(GateOutcome.Nudge(GateKey(name, "rebuild-${state.rebuilds}"), "pressure: context $percent% > α ${(state.defaults.alpha * 100).toInt()}% — $action"))
        }
    }

    // §5.6 Stall: stallTurns turns without a progress event; live build output is work.
    private object Stall : Gate {
        override val name: String get() = STALL

        override fun evaluate(state: GateState): List<GateOutcome> {
            val idle = state.turn - state.lastProgressTurn
            if (state.liveRunOutput || idle < state.defaults.stallTurns) return emptyList()
            // §4.2 decision packets: the latest decision that names a cheap falsifying check is suggested by name.
            val probe = state.register.decisions.lastOrNull { !it.probe.isNullOrBlank() }?.let { " (decision ${it.n}: ${it.probe})" }.orEmpty()
            return listOf(
                GateOutcome.Nudge(
                    GateKey(name, "since-${state.lastProgressTurn}"),
                    "stall: $idle turns without progress — re-read the plan · zoom out · run the pending decision probe$probe · surface the blocker · or request a probe cell",
                ),
            )
        }
    }

    // §5.6 Loop: identical (tool, args, result hash) loopIdentical times nudges; one more ends the turn with a required state op.
    private object Loop : Gate {
        override val name: String get() = LOOP

        override fun evaluate(state: GateState): List<GateOutcome> {
            val counts = HashMap<CallSignature, Int>()
            val out = ArrayList<GateOutcome>()
            for (signature in state.signatures) {
                val n = (counts[signature] ?: 0) + 1
                counts[signature] = n
                val id = signature.argsDigest.hash8 + "/" + signature.resultDigest.hash8
                when {
                    n == state.defaults.loopIdentical -> out += GateOutcome.Nudge(
                        GateKey(name, "${signature.tool}:$id:$n"),
                        "loop: ${signature.tool} returned the same result $n times — change the question or record what you learned",
                    )
                    n > state.defaults.loopIdentical -> out += GateOutcome.Rejection(
                        GateKey(name, "${signature.tool}:$id:$n"),
                        "loop: ${signature.tool} returned the same result $n times — turn ended; a state op is required",
                        endsTurn = true,
                        requiredOp = "state",
                    )
                }
            }
            return out
        }
    }

    // §5.6 No cursor / two cursors and Red not recorded: the validator (P1.5.2) refused the patch with the rule.
    private object RegisterInvariants : Gate {
        override val name: String get() = REGISTER

        override fun evaluate(state: GateState): List<GateOutcome> {
            val rejected = state.patchRejection ?: return emptyList()
            val gate = when (rejected.rule) {
                "exactly one [>]", "one [>] while [ ] exists" -> CURSOR
                "red not recorded" -> RED_NOT_RECORDED
                else -> REGISTER
            }
            return listOf(GateOutcome.Rejection(GateKey(name, "v${state.register.version}:${rejected.rule}"), "$gate: patch rejected — ${rejected.rule}: ${rejected.detail}"))
        }
    }

    // §5.6 Stale fact in Next: evaluated on the register every turn, because the harness marks facts stale without a patch.
    private object StaleFact : Gate {
        override val name: String get() = STALE_FACT

        override fun evaluate(state: GateState): List<GateOutcome> =
            Validator.riskFlags(state.register).map { GateOutcome.Nudge(GateKey(name, it.substringBefore(':')), "risk: $it") }
    }

    // §5.6 Reserve: the budget's verdict (P1.7.6), once.
    private object Reserve : Gate {
        override val name: String get() = RESERVE

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (!state.reserve.reached) return emptyList()
            return listOf(GateOutcome.Nudge(GateKey(name, "reached"), state.reserve.gate ?: CellBudget.GATE))
        }
    }

    // §5.6 Turn budget: turnNudgeFraction of the cell's turns.
    private object Turns : Gate {
        override val name: String get() = TURNS

        override fun evaluate(state: GateState): List<GateOutcome> {
            if (state.turnsMax <= 0 || state.turn < ceil(state.defaults.turnNudgeFraction * state.turnsMax)) return emptyList()
            return listOf(
                GateOutcome.Nudge(
                    GateKey(name, "${(state.defaults.turnNudgeFraction * 100).toInt()}%"),
                    "turn budget: turn ${state.turn}/${state.turnsMax} — reach a coherent boundary and checkpoint",
                ),
            )
        }
    }
}
