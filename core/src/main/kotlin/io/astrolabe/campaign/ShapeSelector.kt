package io.astrolabe.campaign

import io.astrolabe.ShapePolicy
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Shape
import io.astrolabe.contract.Scope
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.WorkspaceId
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.Ownership
import io.astrolabe.workspace.OwnershipAdmission
import io.astrolabe.workspace.OwnershipClaim
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Workspace
import java.nio.file.Files

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
    /** The S3 branch's verdict when a validated plan was given (`admitted` or `refused: …`); `null` on the initial pass. */
    val s3: String? = null,
) {
    /** One deterministic line for the shape log and the `campaign.shape_selected` event. */
    val log: String
        get() = "requirements=$requirements files=${filesEstimated ?: "unknown"} packages=$packages crossPackage=$crossPackage " +
            "size=${size ?: "unknown"} risk=${risk.name.lowercase()} contractTouch=${contractTouch ?: "unknown"} review=$reviewItems " +
            "resumeExpected=$resumeExpected ambiguousBug=$ambiguousBug" + (s3?.let { " s3=$it" } ?: "")
}

/**
 * One unit of a validated plan (§3.5 `plan.units`): its increment, its write scope, whether it changes an interface
 * (`null` = unassessed) and whether it settles a design decision (an increment producing an answer to a question).
 */
public data class PlanUnit @JvmOverloads constructor(val increment: String, val writeScope: Scope, val interfaceChange: Boolean? = null, val decision: Boolean = false)

/**
 * D-39 measured slack in tokens: the remaining budget against the sequential-S1 estimate — reservations, review,
 * verification, integration and an uncertainty margin included — and the parallel-cell limit.
 */
public data class Slack(val remainingTokens: Long, val sequentialEstimateTokens: Long, val cellsInFlight: Int, val maxParallelCells: Int) {
    init {
        require(remainingTokens >= 0 && sequentialEstimateTokens > 0 && cellsInFlight >= 0 && maxParallelCells >= 1) { "slack is measured in non-negative tokens against a positive estimate" }
    }
}

/**
 * The records of a validated plan the S3 branch of `select_shape` reads (§3.5, §10.4): its units in one integration
 * [destination], whether every `CON` they rely on is admitted at a fixed version, the measured [slack] (`null` =
 * unmeasured) and the physical aliases between the units' scopes ([PhysicalAliases]; `null` = unchecked).
 */
public data class PlanShape(
    val units: List<PlanUnit>,
    val destination: WorkspaceId,
    val contractsStable: Boolean,
    val slack: Slack?,
    val aliases: List<String>?,
)

/**
 * Physical aliases between plan units (§10.4): lexical disjointness ([ScopeAlgebra]) says nothing about a case alias
 * on a case-insensitive filesystem or a link. Each existing spelling (the given [files] and the units' literal write
 * paths) is resolved through the path contract; a spelling whose on-disk or real name is owned by another unit is an
 * alias. Missing paths cannot alias yet.
 */
public object PhysicalAliases {
    @JvmStatic
    public fun find(workspace: Workspace, units: List<PlanUnit>, files: List<String>): List<String> {
        val literals = units.flatMap { unit -> unit.writeScope.writePaths.filter { p -> p.none { it == '*' || it == '?' } }.map { it.trimEnd('/') } }
        val found = sortedSetOf<String>()
        for (spelling in (files + literals).filter { it.isNotBlank() }.distinct()) {
            val resolved = workspace.resolve(spelling, Intent.Read) as? PathResolution.Resolved ?: continue
            if (!Files.exists(resolved.real)) continue
            val real = workspace.root.relativize(resolved.real).toString().replace('\\', '/').takeUnless { it.startsWith("..") } ?: continue
            val names = sortedSetOf(spelling, resolved.relative, real)
            if (names.size < 2) continue
            val owners = units.filter { unit -> names.any { unit.writeScope.allowsWrite(it) } }.map { it.increment }.distinct()
            if (owners.size > 1) found += "${owners.joinToString("+")}: ${names.joinToString(" = ")}"
        }
        return found.toList()
    }
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
 * ambiguous bug (D-39), else S1. S3 comes only from a validated [PlanShape] whose [s3] refusals are empty — never on
 * the initial pass, which has no plan (P5.1.4); design decisions and interface changes never run in S3 children. A
 * required capability this build lacks ends in [ShapeDecision.Unavailable]; required review may be met by the
 * authority, recorded as a substitution.
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
        plan: PlanShape? = null,
    ): ShapeDecision {
        val initial = selectInitial(contract, prescan, policy, resumeExpected, ambiguousBug, capabilities)
        if (plan == null || initial !is ShapeDecision.Selected || initial.shape == Shape.S0) return initial
        val refusals = s3(plan, policy)
        val inputs = initial.inputs?.copy(s3 = if (refusals.isEmpty()) "admitted" else "refused: ${refusals.joinToString("; ")}")
        return if (refusals.isEmpty()) initial.copy(shape = Shape.S3, inputs = inputs) else initial.copy(inputs = inputs)
    }

    /**
     * The S3 branch of §3.5 over a validated plan: why S3 may not run now, empty ⇔ admitted. It needs S3 promoted
     * ([ShapePolicy.s3Enabled]), at least two units, a proved lexical disjointness of their write scopes ([Ownership];
     * an unproved answer refuses), no physical alias, no interface change (an unassessed one counts) and no design
     * decision in any unit,
     * contracts stable at fixed versions, and measured slack (D-39): remaining ≥ `slackFactor` × the sequential
     * estimate with a parallel cell free.
     */
    @JvmStatic
    public fun s3(plan: PlanShape, policy: ShapePolicy): List<String> = buildList {
        if (!policy.s3Enabled) add("S3 is not enabled (off until promoted, §10.4)")
        if (plan.units.size < 2) add("${plan.units.size} unit(s); S3 needs at least 2")
        plan.units.filter { it.interfaceChange == true }.takeIf { it.isNotEmpty() }?.let { add("interface change in ${it.joinToString(", ") { u -> u.increment }}") }
        plan.units.filter { it.interfaceChange == null }.takeIf { it.isNotEmpty() }?.let { add("interface change unassessed in ${it.joinToString(", ") { u -> u.increment }}") }
        plan.units.filter { it.decision }.takeIf { it.isNotEmpty() }?.let { add("design decision in ${it.joinToString(", ") { u -> u.increment }}") }
        val ownership = Ownership(plan.destination)
        plan.units.forEach { unit ->
            (ownership.claim(OwnershipClaim(unit.increment, unit.writeScope)) as? OwnershipAdmission.Serialized)?.let { add("${unit.increment} not disjoint from ${it.behind}: ${it.reason}") }
        }
        when {
            plan.aliases == null -> add("physical aliases unchecked")
            plan.aliases.isNotEmpty() -> add("physical aliases: ${plan.aliases.joinToString("; ")}")
        }
        if (!plan.contractsStable) add("contracts not stable at fixed versions")
        val slack = plan.slack
        when {
            slack == null -> add("slack unmeasured")
            slack.remainingTokens < policy.slackFactor * slack.sequentialEstimateTokens -> add("slack ${slack.remainingTokens} < ${policy.slackFactor} × ${slack.sequentialEstimateTokens} tokens")
            slack.cellsInFlight >= slack.maxParallelCells -> add("parallel-cell limit exhausted (${slack.cellsInFlight}/${slack.maxParallelCells})")
        }
    }

    private fun selectInitial(
        contract: Contract,
        prescan: Prescan,
        policy: ShapePolicy,
        resumeExpected: Boolean,
        ambiguousBug: Boolean,
        capabilities: ShapeCapabilities,
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
