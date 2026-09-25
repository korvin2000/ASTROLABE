package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.cell.Roles

/** The turns and tokens one child cell runs on: the smaller of its role's default and the packet's reservation. */
public data class ChildBudget(val turns: Int, val tokens: Tokens) {
    init {
        require(turns >= 1 && tokens.value > 0) { "a child runs at least one turn on a positive budget" }
    }
}

/**
 * Runs one child cell through the controller's cell path in its child-context form (§3.6, §10.1): a fresh cell under
 * the child's own context id, the child [role]'s mask and tier, the runtime-assembled [brief] as its only view of the
 * parent (exact excerpts, never the parent's transcript — D13), and the role's declared [completion] validator.
 * `null` when the cell was cancelled before it produced an exit.
 */
public fun interface ChildCell {
    public suspend fun run(child: ChildRun, role: Role, completion: RoleCompletion, budget: ChildBudget, brief: String): CellExit?
}

/**
 * The [ChildRunner] of the probe and review cells (P4.4.2/P4.4.3): it runs the child on [cell] with its role and
 * validator and turns the validated packet into a [ChildOutcome]; the child's spend is its packet's reported usage.
 */
public class CellChildRunner @JvmOverloads constructor(
    private val cell: ChildCell,
    private val probeBudget: ProbeBudget = ProbeBudget.DEFAULT,
) : ChildRunner {
    override suspend fun run(child: ChildRun): ChildOutcome = when (child.handle.kind) {
        ChildKind.Probe -> probe(child)
        ChildKind.Review -> ChildOutcome.Failed("review children run through the review cell (P4.4.3)", Tokens.ZERO)
        // §10.4: writers integrate through isolated candidates (P5.1); the Delegator already refuses them outside S3.
        ChildKind.Writer -> ChildOutcome.Failed("writer cells need the S3 integrator (P5.1)", Tokens.ZERO)
    }

    private suspend fun probe(child: ChildRun): ChildOutcome {
        var investigation: InvestigationPacket? = null
        val budget = ChildBudget(probeBudget.turns, Tokens(minOf(probeBudget.tokens.value, child.packet.reservedBudget.value)))
        val exit = cell.run(child, Roles.probe, Probe.completion(child.packet.base, { investigation = it }), budget, ChildBrief.render(child.packet, Probe.OUTPUT))
            ?: return ChildOutcome.Failed("probe ${child.handle.id} was cancelled before it ended", Tokens.ZERO)
        val spend = spendOf(exit.packet.cost)
        val packet = investigation
        return if (exit is CellExit.Completed && packet != null) ChildOutcome.Published(ChildPacket.Investigation(packet), spend)
        else ChildOutcome.Failed("probe ${child.handle.id} ended ${exit.packet.status.wire}: ${exit.packet.reason ?: "no investigation packet"}", spend)
    }

    public companion object {
        /** A child's spend: every billable quantity its calls reported (§15.2), counted against the task tree. */
        @JvmStatic
        public fun spendOf(cost: PacketCost): Tokens = Tokens(cost.quantities.values.sum())
    }
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
