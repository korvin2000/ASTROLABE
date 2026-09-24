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
 * The §3.7 `impact_prescan` result ([ImpactPrescan], P3.2.6). A `null` field was not assessed: incomplete discovery is
 * unknown, never low risk (D-16). [filesEstimated] is the tier-0 blast, a lower bound; [fanIn] the highest lexical
 * reference count of a request identifier.
 */
public data class Prescan @JvmOverloads constructor(
    val filesEstimated: Int? = null,
    val crossPackage: Boolean? = null,
    val contractTouch: Boolean? = null,
    val fanIn: Long? = null,
) {
    public val unknown: Boolean get() = filesEstimated == null && crossPackage == null && contractTouch == null && fanIn == null

    public companion object {
        /** The P1 stub: nothing is assessed. */
        @JvmField
        public val UNKNOWN: Prescan = Prescan()
    }
}

/** D-16 size classes. */
public enum class SizeClass { S, M, L }

/** `max(blast radius, contract touch ⇒ high, reversibility)`; [Unknown] when nothing assessed it — never read as low. */
public enum class RiskLevel { Low, Medium, High, Unknown }

/**
 * What this build can supply to a selected shape (I-03, D-23). Required capabilities are chosen independently of
 * which automatic roles exist: [humanReview] (`Authority.review`) may substitute for review cells, sequentially;
 * nothing substitutes for probes. Until P4.4 no automatic review or probe cell exists.
 */
public data class ShapeCapabilities @JvmOverloads constructor(
    val reviewCells: Boolean = false,
    val humanReview: Boolean = false,
    val probes: Boolean = false,
)

/** The logged inputs of one `select_shape` call (§3.5, D-16, D-39). */
public data class ShapeInputs(
    val requirements: Int,
    val filesEstimated: Int?,
    val packages: Int,
    val crossPackage: Boolean,
    /** `null` when the file count was not assessed. */
    val size: SizeClass?,
    val risk: RiskLevel,
    val contractTouch: Boolean?,
    val reviewItems: Int,
    val resumeExpected: Boolean,
    val ambiguousBug: Boolean,
) {
    /** One deterministic line for the shape log and the `campaign.shape_selected` event. */
    val log: String
        get() = "requirements=$requirements files=${filesEstimated ?: "unknown"} packages=$packages crossPackage=$crossPackage " +
            "size=${size ?: "unknown"} risk=${risk.name.lowercase()} contractTouch=${contractTouch ?: "unknown"} review=$reviewItems " +
            "resumeExpected=$resumeExpected ambiguousBug=$ambiguousBug"
}

/** A logged, deterministic `select_shape` decision (§3.5). */
public sealed interface ShapeDecision {
    /**
     * [limitations] name what the decision could not assess; they are reported, never read as low risk.
     * [substitutions] record a required capability met another way (D-23: human review for review cells).
     */
    public data class Selected @JvmOverloads constructor(
        val shape: Shape,
        val limitations: List<String>,
        val inputs: ShapeInputs? = null,
        val substitutions: List<String> = emptyList(),
    ) : ShapeDecision

    /** No shape this build provides fits: the campaign ends `blocked` with [reason] (FX-48/49 honesty). */
    public data class Unavailable @JvmOverloads constructor(val reason: String, val inputs: ShapeInputs? = null) : ShapeDecision
}

/**
 * `select_shape(contract, impact, plan=None)` of §3.5. S0 needs positive facts — requirements, packages and a known
 * file count within the small class, no `review:` item, no expected resume — and a risk that is not above low; an
 * unassessed risk or file count does not disqualify S0 but is recorded as a limitation (D-65), so missing coverage
 * never establishes S0 by itself (I-23). Otherwise S2 for `review:` items, high risk, a contract touch or an
 * ambiguous bug (D-39), else S1; the initial pass never chooses S3 (P5.1.4). A required capability this build lacks
 * ends in [ShapeDecision.Unavailable]; required review may be met by the authority, recorded as a substitution.
 */
public object ShapeSelector {
    @JvmStatic
    @JvmOverloads
    public fun select(
        contract: Contract,
        prescan: Prescan,
        policy: ShapePolicy,
        resumeExpected: Boolean = false,
        ambiguousBug: Boolean = false,
        capabilities: ShapeCapabilities = ShapeCapabilities(),
    ): ShapeDecision {
        val inputs = inputs(contract, prescan, policy, resumeExpected, ambiguousBug)
        val small = inputs.requirements <= policy.smallMaxRequirements && inputs.packages <= 1 && !inputs.crossPackage &&
            (inputs.filesEstimated == null || inputs.filesEstimated <= policy.smallMaxFiles)
        if (small && (inputs.risk == RiskLevel.Low || inputs.risk == RiskLevel.Unknown) && inputs.reviewItems == 0 && !resumeExpected) {
            val limitations = buildList {
                if (inputs.risk == RiskLevel.Unknown) add("risk unknown (pre-scan P3.2.6)")
                if (inputs.filesEstimated == null) add("files unestimated (pre-scan P3.2.6)")
            }
            return ShapeDecision.Selected(Shape.S0, limitations, inputs)
        }
        val needsReview = inputs.reviewItems > 0 || inputs.risk == RiskLevel.High || inputs.contractTouch == true
        if (!needsReview && !ambiguousBug) return ShapeDecision.Selected(Shape.S1, limitations(inputs), inputs)
        val missing = ArrayList<String>()
        val substitutions = ArrayList<String>()
        if (needsReview && !capabilities.reviewCells) {
            if (capabilities.humanReview) substitutions += "required review by Authority.review, sequential (D-23)" else missing += "required review"
        }
        if (ambiguousBug && !capabilities.probes) missing += "probes (ambiguous bug, D-39)"
        if (missing.isNotEmpty()) return ShapeDecision.Unavailable("capability unavailable: ${missing.joinToString(", ")}", inputs)
        return ShapeDecision.Selected(Shape.S2, limitations(inputs), inputs, substitutions)
    }

    /**
     * A shape change after the initial pass (§3.5): an upgrade needs traced [evidence] (pressure in a cell, a probe
     * request, a risk floor); a downgrade is taken eagerly but never below S2 while [outstandingReviews] remain.
     */
    @JvmStatic
    public fun adjust(current: Shape, proposed: Shape, evidence: List<String>, outstandingReviews: Int): Shape = when {
        proposed > current -> if (evidence.any { it.isNotBlank() }) proposed else current
        proposed < current && outstandingReviews > 0 && proposed < Shape.S2 -> minOf(current, maxOf(proposed, Shape.S2))
        else -> proposed
    }

    private fun limitations(inputs: ShapeInputs): List<String> = buildList {
        if (inputs.risk == RiskLevel.Unknown) add("risk unknown (pre-scan P3.2.6)")
        if (inputs.filesEstimated == null) add("files unestimated (pre-scan P3.2.6)")
    }

    private fun inputs(contract: Contract, prescan: Prescan, policy: ShapePolicy, resumeExpected: Boolean, ambiguousBug: Boolean): ShapeInputs {
        val packages = contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.command.cwd }.distinct().size
        val crossPackage = packages > 1 || prescan.crossPackage == true
        val requirements = contract.requirements.size
        val files = prescan.filesEstimated
        // D-16: L on any large signal; S needs every small signal, including a known file count.
        val size = when {
            crossPackage || requirements >= policy.largeMinRequirements || (files != null && files >= policy.largeMinFiles) -> SizeClass.L
            files == null -> null
            requirements <= policy.smallMaxRequirements && files <= policy.smallMaxFiles -> SizeClass.S
            else -> SizeClass.M
        }
        val touch = when {
            prescan.contractTouch == true || contract.risk?.contractTouch == true -> true
            prescan.contractTouch == false || contract.risk != null -> false
            else -> null
        }
        val declared = contract.risk
        val risk = when {
            touch == true || declared?.reversibility == Reversibility.Hard -> RiskLevel.High
            // D-94: a lexical fan-in only raises an unassessed risk, never to high and never down to low.
            declared == null -> if ((prescan.fanIn ?: 0) >= policy.largeMinFiles) RiskLevel.Medium else RiskLevel.Unknown
            declared.blastRadius >= policy.largeMinFiles -> RiskLevel.High
            declared.blastRadius > policy.smallMaxFiles -> RiskLevel.Medium
            else -> RiskLevel.Low
        }
        return ShapeInputs(
            requirements, files, packages, crossPackage, size, risk, touch,
            contract.acceptance.count { it is Acceptance.Review }, resumeExpected, ambiguousBug,
        )
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
