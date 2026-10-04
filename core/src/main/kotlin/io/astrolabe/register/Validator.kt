package io.astrolabe.register

import io.astrolabe.Defaults
import io.astrolabe.cell.Protocol
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.provider.TokenEstimator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the validator may consult: store ids and current check status (§5.2 `tick` and `v` rules). */
public interface ValidationContext {
    /** True when [id] names an existing store artifact (journal result, receipt, observation, alias). */
    public fun evidenceExists(id: String): Boolean

    /** Unknown versions are conservative: an anchored fact cannot claim current evidence. */
    public fun currentVersion(path: String): io.astrolabe.id.FileVersion? = null

    /** Versions of [path] the model may have been shown; a short anchor hash resolves against them (D-365). */
    public fun knownVersions(path: String): Collection<io.astrolabe.id.FileVersion> = listOfNotNull(currentVersion(path))

    /** A-D.4: the one file and displayed version [id] observed, when it names a stored observation of exactly one file. */
    public fun observedFile(id: String): io.astrolabe.evidence.Anchor? = null

    /** True when the step's `accept:` is green at the current version. */
    public fun acceptGreen(accept: String): Boolean

    /** Red verify lines currently rendered (check ids or acceptance ids) that must be recorded in `Open` before `[>]` advances. */
    public val redChecks: Set<String>

    /** Ids of the turn's ops whose run was green / whose edit applied (for conditions). */
    public val greenOps: Set<Int>
    public val appliedOps: Set<Int>
}

@Serializable
public data class Sizes(val registerTokens: Long, val registerCapTokens: Int, val patchTokens: Long, val patchCapTokens: Int)

public sealed interface Validation {
    /**
     * The patch applied atomically; [dropped] lists conditional ops whose condition failed (rendered, §5.5). D-373: [notes]
     * name the ops skipped or normalised one by one; [unbackedTicks] are the steps ticked without usable evidence, which
     * are never a progress event.
     */
    public data class Applied(
        val register: Register,
        val appliedOps: List<Op>,
        val dropped: List<PatchOp>,
        val flags: List<String>,
        val sizes: Sizes,
        val notes: List<String> = emptyList(),
        val unbackedTicks: Set<Int> = emptySet(),
    ) : Validation

    /** Nothing applied; the violated rule and sizes are returned to the model (§5.4 error policy). */
    public data class Rejected(val rule: String, val detail: String, val sizes: Sizes) : Validation
}

/**
 * Harness-enforced register invariants (§5.2, TODO P1.5.2). Conditions are evaluated first; the eligible op
 * list is then validated and committed atomically against the current STATE version. Rejection leaves STATE
 * unchanged; prior world effects of the turn stand.
 *
 * D-373 (STATE is the model's notes; acceptance is the oracle, D-368): an op that breaks its own rule is skipped and
 * named, the rest apply — unless a later op refers to a step, fact or open item a skipped add would have created, or
 * every op was skipped. A tick without usable evidence is recorded without it (never a progress event); a `v` fact whose
 * evidence does not resolve is kept as `h`; a cursor on a done or unknown step is ignored. Whole-patch rules (caps, one
 * Next, one `[>]`, red recorded) still reject the patch.
 */
public class Validator @JvmOverloads constructor(
    private val estimator: TokenEstimator,
    private val registerCapTokens: Int = Defaults().registerCapTokens,
    private val patchCapTokens: Int = 1_200,
    private val factLineMaxChars: Int = Defaults().factLineMaxChars,
    private val referenceMaxChars: Int = 1_000,
    /** The cell's protocol (A-D.4): the direct protocol turns the Next rule off; every other rule is the same. */
    private val protocol: Protocol = Protocol.Structured,
) {
    internal fun schemaRejection(register: Register, rawPatch: String, reason: String): Validation.Rejected =
        Validation.Rejected("schema", reason, Sizes(RegisterRender.tokens(register, estimator), registerCapTokens, estimator.estimate(rawPatch).tokens, patchCapTokens))

    /**
     * [decided] tells a decided amendment line from a pending one; a direct register archives decided lines when a note
     * would exceed the cap (A-D.4).
     */
    @JvmOverloads
    public fun check(register: Register, patch: Patch, context: ValidationContext, decided: (AmendmentLine) -> Boolean = { it.status != "pending" }): Validation {
        val patchTokens = estimator.estimate(Json.encodeToString(Patch.serializer(), patch)).tokens
        var sizes = Sizes(RegisterRender.tokens(register, estimator), registerCapTokens, patchTokens, patchCapTokens)
        fun reject(rule: String, detail: String) = Validation.Rejected(rule, detail, sizes)
        if (patchTokens > patchCapTokens) return reject("patch cap", "patch is $patchTokens tokens > $patchCapTokens")

        val eligible = ArrayList<Op>()
        val dropped = ArrayList<PatchOp>()
        val held = HashSet<Int>()
        for ((index, po) in patch.ops.withIndex()) {
            val c = po.condition
            val met = c == null || when (c.kind) {
                ConditionKind.Green -> c.opId in context.greenOps
                ConditionKind.Applied -> c.opId in context.appliedOps
            }
            if (met) eligible += po.op else { dropped += po; held += index }
        }
        val nextOps = eligible.count { it is Op.Next }
        // D-350: a patch without `next` keeps the Next STATE already has; two still refuse. None yet: D-356 below.
        if (nextOps > 1) return reject("exactly one Next", "patch carries $nextOps next ops")

        var next = register
        val flags = ArrayList<String>()
        val notes = ArrayList<String>()
        val unbacked = LinkedHashSet<Int>()
        val done = ArrayList<Op>()
        var firstSkip: Pair<String, String>? = null
        // The first number a skipped add would have taken, per kind: a later op naming it or one above depends on it.
        val skippedFrom = HashMap<String, Pair<Int, Int>>()
        var cursorMoved = false
        for ((index, po) in patch.ops.withIndex()) {
            if (index in held) continue
            val op = po.op
            val label = "op ${index + 1} (${wire(op)})"
            fun skip(rule: String, detail: String) {
                notes += "$label skipped: $rule — $detail"
                if (firstSkip == null) firstSkip = rule to "$label: $detail"
                kindOfAdd(op)?.let { kind -> skippedFrom.putIfAbsent(kind, index + 1 to nextN(numbers(next, kind))) }
            }
            fun ignore(rule: String, detail: String) {
                notes += "$label ignored: $detail"
                if (firstSkip == null) firstSkip = rule to "$label: $detail"
            }
            referenced(op)?.let { (kind, n) ->
                skippedFrom[kind]?.takeIf { (_, from) -> n >= from }?.let { (at, _) ->
                    return reject("depends on a skipped op", "$label names $kind $n, which op $at (skipped) would have created")
                }
            }
            val tooLong = opText(op).firstNotNullOfOrNull { (text, maxChars) ->
                when {
                    text.length > maxChars -> "line ≤ $maxChars chars" to "${text.length} chars"
                    text.contains("```") || text.contains("~~~") -> "no fenced code" to "contains a code fence"
                    text.any { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' } -> "single line" to "contains a line break"
                    else -> null
                }
            }
            if (tooLong != null) {
                skip(tooLong.first, tooLong.second)
                continue
            }
            next = when (op) {
                is Op.PlanAdd -> next.copy(plan = next.plan + Step(nextN(next.plan.map { it.n }), Mark.Todo, op.text, op.accept, op.after, op.req))
                is Op.PlanCursor -> {
                    val step = next.step(op.n)
                    when {
                        step == null -> { ignore("unknown step", "unknown step ${op.n}"); continue }
                        step.mark == Mark.Cursor -> next
                        step.mark != Mark.Todo -> { ignore("cursor on an open step", "step ${op.n} is ${step.mark.text}"); continue }
                        else -> {
                            cursorMoved = true
                            next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Cursor) else if (s.mark == Mark.Cursor) s.copy(mark = Mark.Todo) else s })
                        }
                    }
                }
                is Op.PlanTick -> {
                    val step = next.step(op.n) ?: run { skip("unknown step", "plan.tick(${op.n})"); null } ?: continue
                    if (step.mark == Mark.Done) {
                        skip("tick needs an open step", "step ${op.n} already done")
                        continue
                    }
                    val evidenceOk = op.evidence != null && context.evidenceExists(op.evidence)
                    val greenOk = step.accept != null && context.acceptGreen(step.accept)
                    if (!evidenceOk && !greenOk) {
                        notes += "tick ${op.n} recorded without evidence: ${unusable(op.evidence)}"
                        unbacked += op.n
                    }
                    if (step.mark == Mark.Cursor) cursorMoved = true
                    val evidence = op.evidence?.takeIf { evidenceOk }
                    next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Done, evidence = evidence ?: s.evidence) else s })
                }
                is Op.PlanCancel -> {
                    if (op.reason.isBlank()) { skip("[~] needs a reason", "plan.cancel(${op.n})"); continue }
                    val step = next.step(op.n) ?: run { skip("unknown step", "plan.cancel(${op.n})"); null } ?: continue
                    if (step.mark == Mark.Cursor) cursorMoved = true
                    next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Cancelled, reason = op.reason) else s })
                }
                is Op.FactAdd -> {
                    val verified = op.kind != ClaimKind.Verified || (op.evidence != null && context.evidenceExists(op.evidence))
                    if (!verified) notes += "$label kept as h: ${unusable(op.evidence)}; a v fact needs a stored result as evidence"
                    val kind = if (verified) op.kind else ClaimKind.Hypothesis
                    val evidence = if (verified) op.evidence else null
                    val staleAt = op.anchor?.let { anchor ->
                        val current = context.currentVersion(anchor.path)
                        if (current == anchor.version) null else current ?: anchor.version
                    }
                    next.copy(facts = next.facts + Fact(nextN(numbers(next, "fact")), kind, op.text, op.anchor, evidence, staleAt))
                }
                is Op.FactRefute -> {
                    val fact = next.fact(op.n) ?: run { skip("unknown fact", "fact.refute(${op.n})"); null } ?: continue
                    if (!context.evidenceExists(op.evidence)) { skip("refute needs an existing evidence id", "fact.refute(${op.n}): ${op.evidence}"); continue }
                    next.copy(facts = next.facts.map { f -> if (f.n == fact.n) f.copy(kind = ClaimKind.Refuted, refutedBy = op.evidence) else f })
                }
                is Op.DeadendAdd -> {
                    if (op.scope.isBlank() || op.reopen.isBlank()) { skip("dead ends need scope and reopen", "deadend.add"); continue }
                    next.copy(deadEnds = next.deadEnds + DeadEnd(nextN(next.deadEnds.map { it.n }), op.text, op.evidence?.takeIf { it.isNotBlank() }, op.scope, op.reopen))
                }
                is Op.DecisionAdd -> next.copy(decisions = next.decisions + Decision(nextN(next.decisions.map { it.n }), op.text, op.because, op.rejected?.takeIf { it.isNotBlank() }, op.probe, op.adrCandidate))
                is Op.OpenAdd -> next.copy(open = next.open + OpenItem(nextN(numbers(next, "open item")), op.text, op.trip, op.needs))
                is Op.OpenClose -> {
                    val item = next.openItem(op.n) ?: run { skip("unknown open item", "open.close(${op.n})"); null } ?: continue
                    if (!context.evidenceExists(op.evidence)) { skip("close needs an existing evidence id", "open.close(${op.n}): ${op.evidence}"); continue }
                    next.copy(open = next.open.map { o -> if (o.n == item.n) o.copy(closed = true, closedEvidence = op.evidence) else o })
                }
                is Op.FocusSet -> next.copy(focus = op.dir)
                is Op.AmendPropose -> next.copy(amendments = next.amendments + AmendmentLine(op.change, op.reason))
                is Op.Next -> next.copy(next = op.text)
            }
            done += op
        }
        firstSkip?.takeIf { done.isEmpty() }?.let { (rule, _) -> return reject(rule, notes.joinToString("; ")) }

        if (next.cursors > 1) return reject("exactly one [>]", "${next.cursors} cursors")
        // D-350: open steps left without [>] get it on the first open step; placing it advances [>], so the red rule below applies.
        val placed = if (next.cursors == 0) next.plan.firstOrNull { it.mark == Mark.Todo } else null
        if (placed != null) {
            next = next.copy(plan = next.plan.map { s -> if (s.n == placed.n) s.copy(mark = Mark.Cursor) else s })
            cursorMoved = true
        }
        // D-356: STATE has no Next yet and the patch names none — Next is the [>] step; with no open step there is nothing to name.
        // A-D.7 V1: a direct register has neither Next nor [>], so the rule is off there.
        if (next.next == null && protocol == Protocol.Structured) {
            val active = next.cursor ?: return reject("exactly one Next", "patch carries 0 next ops, STATE has no Next yet and no open step to take it from: add {\"next\": \"…\"}")
            next = next.copy(next = active.text)
        }
        if (cursorMoved && context.redChecks.isNotEmpty()) {
            val unrecorded = context.redChecks.filter { red -> next.open.none { !it.closed && it.text.contains(red) } }
            if (unrecorded.isNotEmpty()) {
                return reject(
                    "red not recorded",
                    "red ${unrecorded} without an Open item before [>] advances" + (placed?.let { " (the patch left no [>]; it would go to step ${it.n}, the first open step)" } ?: ""),
                )
            }
        }
        next = next.copy(version = register.version + 1)
        var registerTokens = RegisterRender.tokens(next, estimator)
        // A-D.7 V4: a direct register archives first — closed open items, refuted facts, decided amendments — then refuses.
        if (registerTokens > registerCapTokens && protocol == Protocol.Direct) {
            val (archived, moved) = archive(next, decided)
            next = archived
            registerTokens = RegisterRender.tokens(next, estimator)
            if (moved.isNotEmpty() && registerTokens <= registerCapTokens) notes += "archived ${moved.joinToString(" ")} — look(recall, id=notes, range=archive)"
        }
        sizes = sizes.copy(registerTokens = registerTokens)
        if (registerTokens > registerCapTokens) {
            return if (protocol == Protocol.Direct) {
                reject("register cap", "$registerTokens/$registerCapTokens tokens of active notes after archiving; retire notes first: closes: n ends an open note, refutes: n refutes a hypothesis")
            } else {
                reject("register cap", "register would be $registerTokens tokens > $registerCapTokens")
            }
        }
        flags += riskFlags(next)
        return Validation.Applied(next, done, dropped, flags, sizes, notes, unbacked)
    }

    private fun nextN(existing: List<Int>): Int = (existing.maxOrNull() ?: 0) + 1

    /**
     * A-D.4 register capacity: moves archivable notes out of the active register, one at a time and in the fixed order —
     * closed open items, refuted facts (lowest number first), decided amendments (first position first) — until it fits
     * the cap. Nothing else is ever archived. Returns the register and the ids it archived.
     */
    private fun archive(register: Register, decided: (AmendmentLine) -> Boolean): Pair<Register, List<String>> {
        var next = register
        val moved = ArrayList<String>()
        fun fits() = RegisterRender.tokens(next, estimator) <= registerCapTokens
        for (item in register.open.filter { it.closed }.sortedBy { it.n }) {
            if (fits()) return next to moved
            next = next.copy(open = next.open - item, archive = next.archive.copy(open = next.archive.open + item))
            moved += "o${item.n}"
        }
        for (fact in register.facts.filter { it.kind == ClaimKind.Refuted }.sortedBy { it.n }) {
            if (fits()) return next to moved
            next = next.copy(facts = next.facts - fact, archive = next.archive.copy(facts = next.archive.facts + fact))
            moved += "x${fact.n}"
        }
        while (!fits()) {
            val positions = next.amendmentPositions()
            val index = next.amendments.indexOfFirst(decided).takeIf { it >= 0 } ?: break
            next = next.copy(
                amendments = next.amendments.filterIndexed { i, _ -> i != index },
                archive = next.archive.copy(amendments = next.archive.amendments + ArchivedAmendment(positions[index], next.amendments[index])),
            )
            moved += "a${positions[index]}"
        }
        return next to moved
    }

    /** The `kind.add` wire name of an op ([Op] serial names). */
    private fun wire(op: Op): String = when (op) {
        is Op.PlanAdd -> "plan.add"
        is Op.PlanCursor -> "plan.cursor"
        is Op.PlanTick -> "plan.tick"
        is Op.PlanCancel -> "plan.cancel"
        is Op.FactAdd -> "fact.add"
        is Op.FactRefute -> "fact.refute"
        is Op.DeadendAdd -> "deadend.add"
        is Op.DecisionAdd -> "decision.add"
        is Op.OpenAdd -> "open.add"
        is Op.OpenClose -> "open.close"
        is Op.FocusSet -> "focus.set"
        is Op.AmendPropose -> "amend.propose"
        is Op.Next -> "next"
    }

    /** The numbered kind an add creates, for the D-373 dependency rule. */
    private fun kindOfAdd(op: Op): String? = when (op) {
        is Op.PlanAdd -> "step"
        is Op.FactAdd -> "fact"
        is Op.OpenAdd -> "open item"
        else -> null
    }

    /** The numbered item an op refers to. */
    private fun referenced(op: Op): Pair<String, Int>? = when (op) {
        is Op.PlanCursor -> "step" to op.n
        is Op.PlanTick -> "step" to op.n
        is Op.PlanCancel -> "step" to op.n
        is Op.FactRefute -> "fact" to op.n
        is Op.OpenClose -> "open item" to op.n
        else -> null
    }

    private fun numbers(register: Register, kind: String): List<Int> = when (kind) {
        "step" -> register.plan.map { it.n }
        // A-D.4: numbers are never reused — archived notes count (a structured register archives nothing).
        "fact" -> register.facts.map { it.n } + register.archive.facts.map { it.n }
        else -> register.open.map { it.n } + register.archive.open.map { it.n }
    }

    /** Why an evidence value cannot back a tick or a `v` fact (D-373 notes). */
    private fun unusable(evidence: String?): String = when {
        evidence == null -> "no evidence given"
        evidence.startsWith("op:") -> "'$evidence' names no call of this turn; a stored result is #N"
        else -> "'$evidence' is not a stored result; a stored result is #N"
    }

    public companion object {
        /**
         * Heuristic (§5.6 "stale fact in Next"): an `h` or `v(stale)` fact sharing a significant word with Next or
         * the active step. Shared with the cell's stale-fact gate, which evaluates it every turn because the
         * harness marks facts stale without a patch.
         */
        @JvmStatic
        public fun riskFlags(register: Register): List<String> {
            val active = listOfNotNull(register.next, register.cursor?.text).flatMap { words(it) }.toSet()
            if (active.isEmpty()) return emptyList()
            return register.facts
                .filter { it.kind == ClaimKind.Hypothesis || it.stale }
                .filter { words(it.text).any { w -> w in active } }
                .map { f -> "${if (f.stale) "stale" else "h"} fact ${f.n} rests under Next/active step: ${f.text}" }
        }

        private fun words(text: String): Set<String> = text.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 5 }.toSet()
    }

    /**
     * Every model-controlled string rendered in STATE is an inline field. D-276: prose keeps the §17 fact-line cap;
     * verbatim references (accept commands, requirement ids, evidence ids, anchor and focus paths) cannot be
     * paraphrased shorter and get [referenceMaxChars].
     */
    private fun opText(op: Op): List<Pair<String, Int>> {
        fun prose(vararg s: String?) = s.filterNotNull().map { it to factLineMaxChars }
        fun ref(vararg s: String?) = s.filterNotNull().map { it to referenceMaxChars }
        return when (op) {
            is Op.PlanAdd -> prose(op.text) + ref(op.accept, op.req)
            is Op.PlanCursor -> emptyList()
            is Op.PlanTick -> ref(op.evidence)
            is Op.PlanCancel -> prose(op.reason)
            is Op.FactAdd -> prose(op.text) + ref(op.evidence, op.anchor?.path)
            is Op.FactRefute -> ref(op.evidence)
            is Op.DeadendAdd -> prose(op.text, op.scope, op.reopen) + ref(op.evidence)
            is Op.DecisionAdd -> prose(op.text, op.because, op.rejected, op.probe)
            is Op.OpenAdd -> prose(op.text, op.trip, op.needs)
            is Op.OpenClose -> ref(op.evidence)
            is Op.FocusSet -> ref(op.dir)
            is Op.AmendPropose -> prose(op.change, op.reason)
            is Op.Next -> prose(op.text)
        }
    }
}
