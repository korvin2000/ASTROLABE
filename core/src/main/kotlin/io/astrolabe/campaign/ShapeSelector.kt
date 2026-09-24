package io.astrolabe.campaign

import io.astrolabe.ShapePolicy
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Shape
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph

/**
 * The §3.7 `impact_prescan` result. Every field is `null` until the pre-scan exists (P3.2.6): incomplete
 * discovery is unknown, never low risk (D-16).
 */
public data class Prescan(
    val filesEstimated: Int? = null,
    val crossPackage: Boolean? = null,
    val contractTouch: Boolean? = null,
) {
    public val unknown: Boolean get() = filesEstimated == null && crossPackage == null && contractTouch == null

    public companion object {
        /** The P1 stub: nothing is assessed. */
        @JvmField
        public val UNKNOWN: Prescan = Prescan()
    }
}

/** A logged, deterministic `select_shape` decision (§3.5). */
public sealed interface ShapeDecision {
    /** [limitations] name what the decision could not assess; they are reported, never read as low risk. */
    public data class Selected(val shape: Shape, val limitations: List<String>) : ShapeDecision

    /** No shape this build provides fits: the campaign ends `blocked` with [reason] (FX-48/49 honesty). */
    public data class Unavailable(val reason: String) : ShapeDecision
}

/**
 * `select_shape(contract, impact, plan=None)` of §3.5 in its P1 form: only S0 exists, so a request S0 does not fit
 * is `blocked("shape S1+ unavailable")`, never squeezed into S0. D-65: an unassessed risk or file count does not
 * disqualify S0 (the pre-scan is P3.2.6) but is recorded as a limitation; every mandatory control stays on.
 */
public object ShapeSelector {
    @JvmStatic
    @JvmOverloads
    public fun select(contract: Contract, prescan: Prescan, policy: ShapePolicy, resumeExpected: Boolean = false): ShapeDecision {
        val misfit = ArrayList<String>()
        if (contract.requirements.size > policy.smallMaxRequirements) misfit += "${contract.requirements.size} requirements"
        if (contract.acceptance.any { it is Acceptance.Review }) misfit += "review: acceptance"
        if (resumeExpected) misfit += "resume expected"
        val packages = contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.command.cwd }.distinct()
        if (packages.size > 1 || prescan.crossPackage == true) misfit += "cross-package"
        prescan.filesEstimated?.let { if (it > policy.smallMaxFiles) misfit += "$it files estimated" }
        if (prescan.contractTouch == true || contract.risk?.contractTouch == true) misfit += "contract touch"
        if (contract.risk?.reversibility == Reversibility.Hard) misfit += "hard to reverse"
        if (misfit.isNotEmpty()) return ShapeDecision.Unavailable("shape S1+ unavailable: ${misfit.joinToString(", ")}")
        val limitations = buildList {
            if (contract.risk == null && prescan.contractTouch == null) add("risk unknown (pre-scan P3.2.6)")
            if (prescan.filesEstimated == null) add("files unestimated (pre-scan P3.2.6)")
        }
        return ShapeDecision.Selected(Shape.S0, limitations)
    }

    /**
     * `G_single(C)`: one increment covering every requirement and acceptance item within the contract's write
     * scope. It opens a campaign only when it validates — a contract without a `run:` item has nothing executable
     * to accept against.
     */
    @JvmStatic
    public fun single(contract: Contract): RequirementGraph = RequirementGraph(
        listOf(
            Increment(
                id = SINGLE,
                requirementIds = contract.requirements.map { it.id },
                accept = contract.acceptance.map { it.id },
                writeScope = contract.scope.writePaths,
                expectedFiles = 0,
                title = contract.requests.firstOrNull()?.text.orEmpty(),
                produces = Production.Artifact,
            ),
        ),
    )

    /** The id of the one increment of `G_single(C)`. */
    public const val SINGLE: String = "inc-1"
}
