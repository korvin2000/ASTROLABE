package io.astrolabe.campaign

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Shape
import io.astrolabe.graph.RequirementGraph

/**
 * Whether an S1 campaign's plan cell has anything to decide (§4.2, `ShapePolicy.planCell = WhenNeeded`). A contract
 * is eligible for deterministic single-increment execution when it is S1 (never S2/S3, whose review and delegation
 * paths the plan feeds), carries no `review:` item and touches no contract, and `G_single(C)` passes the same
 * validator a proposed plan passes — refactor mode and uncovered requirements included, which `graph.validate`
 * alone would tolerate.
 */
internal object PlanNeed {
    /** `G_single(C)` when [contract] is eligible, else `null`: the plan cell runs. */
    fun trivialGraph(contract: Contract, conAnchors: Map<String, Set<String>>): RequirementGraph? {
        if (contract.shape != Shape.S1) return null
        if (contract.acceptance.any { it is Acceptance.Review } || contract.contractsTouched.isNotEmpty()) return null
        val graph = ShapeSelector.single(contract)
        val packet = PlanPacket(graph)
        if (PlanPacketValidator.gaps(contract, packet, conAnchors).isNotEmpty()) return null
        if (PlanPacketValidator.refactorGaps(contract, packet).isNotEmpty()) return null
        return graph
    }

    /** The journal's account of why the plan cell was skipped. */
    fun reason(contract: Contract): String {
        val runs = contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.id }
        val checks = contract.acceptance.filterIsInstance<Acceptance.Check>().map { it.id }
        return "acceptance is executable (${runs.joinToString(", ")})" +
            (if (checks.isEmpty()) "" else "; host checks (${checks.joinToString(", ")})") +
            "; no review items; no contracts touched"
    }
}
