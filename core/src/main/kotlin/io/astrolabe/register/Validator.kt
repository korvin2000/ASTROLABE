package io.astrolabe.register

import io.astrolabe.evidence.ClaimKind
import io.astrolabe.provider.TokenEstimator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the validator may consult: store ids and current check status (§5.2 `tick` and `v` rules). */
public interface ValidationContext {
    /** True when [id] names an existing store artifact (journal result, receipt, observation, alias). */
    public fun evidenceExists(id: String): Boolean

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
    /** The patch applied atomically; [dropped] lists conditional ops whose condition failed (rendered, §5.5). */
    public data class Applied(
        val register: Register,
        val appliedOps: List<Op>,
        val dropped: List<PatchOp>,
        val flags: List<String>,
        val sizes: Sizes,
    ) : Validation

    /** Nothing applied; the violated rule and sizes are returned to the model (§5.4 error policy). */
    public data class Rejected(val rule: String, val detail: String, val sizes: Sizes) : Validation
}

/**
 * Harness-enforced register invariants (§5.2, TODO P1.5.2). Conditions are evaluated first; the eligible op
 * list is then validated and committed atomically against the current STATE version. Rejection leaves STATE
 * unchanged; prior world effects of the turn stand.
 */
public class Validator(
    private val estimator: TokenEstimator,
    private val registerCapTokens: Int = 1_200,
    private val patchCapTokens: Int = 400,
    private val factLineMaxChars: Int = 240,
) {
    public fun check(register: Register, patch: Patch, context: ValidationContext): Validation {
        val patchTokens = estimator.estimate(Json.encodeToString(Patch.serializer(), patch)).tokens
        var sizes = Sizes(RegisterRender.tokens(register, estimator), registerCapTokens, patchTokens, patchCapTokens)
        fun reject(rule: String, detail: String) = Validation.Rejected(rule, detail, sizes)
        if (patchTokens > patchCapTokens) return reject("patch cap", "patch is $patchTokens tokens > $patchCapTokens")

        val eligible = ArrayList<Op>()
        val dropped = ArrayList<PatchOp>()
        for (po in patch.ops) {
            val c = po.condition
            val met = c == null || when (c.kind) {
                ConditionKind.Green -> c.opId in context.greenOps
                ConditionKind.Applied -> c.opId in context.appliedOps
            }
            if (met) eligible += po.op else dropped += po
        }
        if (eligible.count { it is Op.Next } != 1) return reject("exactly one Next", "patch carries ${eligible.count { it is Op.Next }} next ops")

        var next = register
        val flags = ArrayList<String>()
        var cursorMoved = false
        for (op in eligible) {
            val text = opText(op)
            if (text != null) {
                if (text.length > factLineMaxChars) return reject("line ≤ $factLineMaxChars chars", "${op::class.simpleName}: ${text.length} chars")
                if (text.contains("```")) return reject("no fenced code", "${op::class.simpleName} contains a code fence")
            }
            next = when (op) {
                is Op.PlanAdd -> next.copy(plan = next.plan + Step(nextN(next.plan.map { it.n }), Mark.Todo, op.text, op.accept, op.after, op.req))
                is Op.PlanCursor -> {
                    val step = next.step(op.n) ?: return reject("unknown step", "plan.cursor(${op.n})")
                    if (step.mark != Mark.Todo) return reject("cursor on an open step", "step ${op.n} is ${step.mark.text}")
                    cursorMoved = true
                    next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Cursor) else if (s.mark == Mark.Cursor) s.copy(mark = Mark.Todo) else s })
                }
                is Op.PlanTick -> {
                    val step = next.step(op.n) ?: return reject("unknown step", "plan.tick(${op.n})")
                    if (step.mark == Mark.Done) return reject("tick needs an open step", "step ${op.n} already done")
                    val evidenceOk = op.evidence != null && context.evidenceExists(op.evidence)
                    val greenOk = step.accept != null && context.acceptGreen(step.accept)
                    if (!evidenceOk && !greenOk) return reject("tick needs green accept or an evidence id", "step ${op.n}: evidence=${op.evidence} accept=${step.accept}")
                    if (step.mark == Mark.Cursor) cursorMoved = true
                    next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Done, evidence = op.evidence ?: s.evidence) else s })
                }
                is Op.PlanCancel -> {
                    if (op.reason.isBlank()) return reject("[~] needs a reason", "plan.cancel(${op.n})")
                    val step = next.step(op.n) ?: return reject("unknown step", "plan.cancel(${op.n})")
                    if (step.mark == Mark.Cursor) cursorMoved = true
                    next.copy(plan = next.plan.map { s -> if (s.n == op.n) s.copy(mark = Mark.Cancelled, reason = op.reason) else s })
                }
                is Op.FactAdd -> {
                    if (op.kind == ClaimKind.Verified && (op.evidence == null || !context.evidenceExists(op.evidence))) {
                        return reject("v needs an existing evidence id", "fact.add(v): evidence=${op.evidence}")
                    }
                    next.copy(facts = next.facts + Fact(nextN(next.facts.map { it.n }), op.kind, op.text, op.anchor, op.evidence))
                }
                is Op.FactRefute -> {
                    val fact = next.fact(op.n) ?: return reject("unknown fact", "fact.refute(${op.n})")
                    if (!context.evidenceExists(op.evidence)) return reject("refute needs an existing evidence id", "fact.refute(${op.n}): ${op.evidence}")
                    next.copy(facts = next.facts.map { f -> if (f.n == fact.n) f.copy(kind = ClaimKind.Refuted, refutedBy = op.evidence) else f })
                }
                is Op.DeadendAdd -> {
                    if (op.scope.isBlank() || op.reopen.isBlank()) return reject("dead ends need scope and reopen", "deadend.add")
                    next.copy(deadEnds = next.deadEnds + DeadEnd(nextN(next.deadEnds.map { it.n }), op.text, op.evidence, op.scope, op.reopen))
                }
                is Op.DecisionAdd -> next.copy(decisions = next.decisions + Decision(nextN(next.decisions.map { it.n }), op.text, op.because, op.rejected, op.probe, op.adrCandidate))
                is Op.OpenAdd -> next.copy(open = next.open + OpenItem(nextN(next.open.map { it.n }), op.text, op.trip, op.needs))
                is Op.OpenClose -> {
                    val item = next.openItem(op.n) ?: return reject("unknown open item", "open.close(${op.n})")
                    if (!context.evidenceExists(op.evidence)) return reject("close needs an existing evidence id", "open.close(${op.n}): ${op.evidence}")
                    next.copy(open = next.open.map { o -> if (o.n == item.n) o.copy(closed = true, closedEvidence = op.evidence) else o })
                }
                is Op.FocusSet -> next.copy(focus = op.dir)
                is Op.AmendPropose -> next.copy(amendments = next.amendments + AmendmentLine(op.change, op.reason))
                is Op.Next -> next.copy(next = op.text)
            }
        }

        if (next.cursors > 1) return reject("exactly one [>]", "${next.cursors} cursors")
        if (next.cursors == 0 && next.todos > 0) return reject("one [>] while [ ] exists", "no cursor with ${next.todos} open steps")
        if (cursorMoved && context.redChecks.isNotEmpty()) {
            val unrecorded = context.redChecks.filter { red -> next.open.none { !it.closed && it.text.contains(red) } }
            if (unrecorded.isNotEmpty()) return reject("red not recorded", "red ${unrecorded} without an Open item before [>] advances")
        }
        next = next.copy(version = register.version + 1)
        val registerTokens = RegisterRender.tokens(next, estimator)
        sizes = sizes.copy(registerTokens = registerTokens)
        if (registerTokens > registerCapTokens) return reject("register cap", "register would be $registerTokens tokens > $registerCapTokens")
        flags += riskFlags(next)
        return Validation.Applied(next, eligible, dropped, flags, sizes)
    }

    private fun nextN(existing: List<Int>): Int = (existing.maxOrNull() ?: 0) + 1

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

    private fun opText(op: Op): String? = when (op) {
        is Op.PlanAdd -> op.text
        is Op.FactAdd -> op.text
        is Op.DeadendAdd -> op.text
        is Op.DecisionAdd -> op.text
        is Op.OpenAdd -> op.text
        is Op.AmendPropose -> op.change
        is Op.Next -> op.text
        is Op.PlanCancel -> op.reason
        else -> null
    }
}
