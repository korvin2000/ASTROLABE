package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.cell.Roles
import io.astrolabe.id.ContextId
import io.astrolabe.id.IdGen
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.Tier
import io.astrolabe.verify.Verdict

/** The turns and tokens one child cell runs on: the smaller of its role's default and the packet's reservation. */
public data class ChildBudget(val turns: Int, val tokens: Tokens) {
    init {
        require(turns >= 1 && tokens.value > 0) { "a child runs at least one turn on a positive budget" }
    }
}

/**
 * Where a child cell sits: its own context id and cancellation token, the §11.1 row it is routed by and, after an
 * escalation, the tier it never drops below.
 */
public data class ChildSeat @JvmOverloads constructor(val context: ContextId, val cancellation: Cancellation, val function: RoutingFunction, val tier: Tier? = null)

/**
 * Runs one child cell through the controller's cell path in its child-context form (§3.6, §10.1): a fresh cell in its
 * [ChildSeat], the child [role]'s mask, the runtime-assembled [brief] as its only view of the parent (exact excerpts
 * or the evidence packet, never the parent's transcript — D13), and the role's declared [completion] validator.
 * `null` when the cell was cancelled before it produced an exit.
 */
public fun interface ChildCell {
    public suspend fun run(seat: ChildSeat, role: Role, completion: RoleCompletion, budget: ChildBudget, brief: String): CellExit?
}

/**
 * The [ChildRunner] of the probe and review cells (P4.4.2/P4.4.3): it runs the child on [cell] with its role and
 * validator and turns the validated packet into a [ChildOutcome]; the child's spend is its packet's reported usage.
 * A review child needs its [evidence] packet, assembled by the runtime from records, never from the parent's words.
 */
public class CellChildRunner @JvmOverloads constructor(
    private val cell: ChildCell,
    private val probeBudget: ProbeBudget = ProbeBudget.DEFAULT,
    private val reviewBudget: ReviewBudget = ReviewBudget.DEFAULT,
    private val evidence: ((TaskPacket) -> EvidencePacket?)? = null,
    /** The writer seats (§10.4, P5.1.2); without them a writer child fails. The Delegator refuses writers outside S3. */
    private val writers: Writers? = null,
) : ChildRunner {
    override suspend fun run(child: ChildRun): ChildOutcome = when (child.handle.kind) {
        ChildKind.Probe -> probe(child)
        ChildKind.Review -> review(child)
        ChildKind.Writer -> writers?.run(child) ?: ChildOutcome.Failed("writer ${child.handle.id}: no writer worktrees are configured (S3)", Tokens.ZERO)
    }

    private suspend fun probe(child: ChildRun): ChildOutcome {
        var investigation: InvestigationPacket? = null
        val budget = ChildBudget(probeBudget.turns, Tokens(minOf(probeBudget.tokens.value, child.packet.reservedBudget.value)))
        val seat = ChildSeat(child.handle.child, child.cancellation, RoutingFunction.Probe)
        val exit = cell.run(seat, Roles.probe, Probe.completion(child.packet.base, { investigation = it }), budget, ChildBrief.render(child.packet, Probe.OUTPUT))
            ?: return ChildOutcome.Failed("probe ${child.handle.id} was cancelled before it ended", Tokens.ZERO)
        val spend = spendOf(exit.packet.cost)
        val packet = investigation
        return if (exit is CellExit.Completed && packet != null) ChildOutcome.Published(ChildPacket.Investigation(packet), spend)
        else ChildOutcome.Failed("probe ${child.handle.id} ended ${exit.packet.status.wire}: ${exit.packet.reason ?: "no investigation packet"}", spend)
    }

    private suspend fun review(child: ChildRun): ChildOutcome {
        val packet = evidence?.invoke(child.packet) ?: return ChildOutcome.Failed("review ${child.handle.id}: no evidence packet could be assembled", Tokens.ZERO)
        val scoped = reviewBudget.of(packet.scope)
        val budget = scoped.copy(tokens = Tokens(minOf(scoped.tokens.value, child.packet.reservedBudget.value)))
        val run = judge(cell, ChildSeat(child.handle.child, child.cancellation, ReviewTriggers.function(packet.triggers)), packet, budget)
        val verdict = run.verdict ?: return ChildOutcome.Failed("review ${child.handle.id}: ${run.reason}", run.spend)
        return ChildOutcome.Published(ChildPacket.Review(verdict), run.spend)
    }

    public companion object {
        /** A child's spend: every billable quantity its calls reported (§15.2), counted against the task tree. */
        @JvmStatic
        public fun spendOf(cost: PacketCost): Tokens = Tokens(cost.quantities.values.sum())

        internal suspend fun judge(cell: ChildCell, seat: ChildSeat, packet: EvidencePacket, budget: ChildBudget): JudgeRun {
            var verdict: Verdict? = null
            val exit = cell.run(seat, Roles.review, Judge.completion(packet, { verdict = it }), budget, packet.render(Judge.OUTPUT))
                ?: return JudgeRun(null, Tokens.ZERO, "the review cell was cancelled before it ended")
            val published = verdict.takeIf { exit is CellExit.Completed }
            return JudgeRun(published, spendOf(exit.packet.cost), if (published == null) "the review cell ended ${exit.packet.status.wire}: ${exit.packet.reason ?: "no verdict"}" else null)
        }
    }
}

/**
 * The review cell as a [ReviewJudge] (§8.8): a fresh cell per run under a new context id, the evidence packet as its
 * brief, routed by its trigger's §11.1 row and never below the escalated tier.
 */
public class CellReviewJudge @JvmOverloads constructor(
    private val cell: ChildCell,
    private val idGen: IdGen,
    private val cancellation: Cancellation,
    private val budget: ReviewBudget = ReviewBudget.DEFAULT,
) : ReviewJudge {
    override suspend fun judge(packet: EvidencePacket, tier: Tier): JudgeRun =
        CellChildRunner.judge(cell, ChildSeat(ContextId(idGen.next("review")), cancellation, ReviewTriggers.function(packet.triggers), tier), packet, budget.of(packet.scope))
}

/** The child's brief (§10.1): the packet rendered for its `[T]`, excerpts verbatim with their authority refs. */
public object ChildBrief {
    @JvmStatic
    public fun render(packet: TaskPacket, output: String): String = buildString {
        append("Task packet — role ").append(packet.role).append(", increment ").append(packet.incrementId)
        append(", contract v").append(packet.contractVersion).append(", base @").append(packet.dispatchCandidate.hash8).append('\n')
        if (packet.uncertainties.isNotEmpty()) append("Question: ").append(packet.uncertainties.joinToString("; ")).append('\n')
        if (packet.requirements.isNotEmpty()) {
            append("Requirements (exact excerpts):\n")
            packet.requirements.forEach { append("  ").append(it.id).append(" [").append(it.authorityRef).append("]: ").append(it.text).append('\n') }
        }
        if (packet.constraints.isNotEmpty()) {
            append("Constraints (exact excerpts):\n")
            packet.constraints.forEach { append("  ").append(it.id).append(" [").append(it.authorityRef).append("]: ").append(it.text).append('\n') }
        }
        append("Read scope: ").append(packet.readScope.ifEmpty { listOf("the workspace") }.joinToString(", "))
        append(" · write scope: ").append(packet.writeScope.ifEmpty { listOf("none (read-only)") }.joinToString(", ")).append('\n')
        if (packet.requiredEvidence.isNotEmpty()) append("Evidence the parent already has or needs: ").append(packet.requiredEvidence.joinToString(", ")).append('\n')
        append("Budget: ").append(packet.reservedBudget.value).append(" tokens reserved\n")
        append("Required output: ").append(output)
    }
}
