package io.astrolabe

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.route.Tier
import kotlinx.serialization.Serializable

/**
 * Declared defaults (§17): one named field per table row, all configurable per task, none hard-coded
 * elsewhere. They are first-round estimates, not derived optima. Harness changes take effect only at attempt
 * boundaries (invariant 12) through [AttemptConfig].
 */
@Serializable
public data class Defaults(
    // Shape (§3.5, D-16)
    val shapePolicy: ShapePolicy = ShapePolicy(),
    // Cell turn budget
    val turnsPerCell: Int = 80,
    val turnNudgeFraction: Double = 0.80,
    // Bounded wait for a provider terminal after the response (D-314)
    val providerTerminalWaitSeconds: Int = 60,
    // α pressure threshold
    val alpha: Double = 0.65,
    // k eviction batch / m turns kept on rebuild
    val k: Int = 8,
    val m: Int = 6,
    // R_max total live results / [A] max
    val rMaxTokens: Int = 16_000,
    val anchorMaxTokens: Int = 2_500,
    // Immediate-stub threshold for stale reads
    val immediateStubTokens: Int = 800,
    // look.budget / run.budget
    val lookBudgetTokens: Int = 1_500,
    val runBudgetTokens: Int = 1_200,
    // Register cap / contract digest cap / patch cap
    val registerCapTokens: Int = 1_200,
    val digestCapTokens: Int = 150,
    // D-270: the digest cap grows per requirement up to a ceiling; 0 per requirement pins [digestCapTokens]
    val digestTokensPerRequirement: Int = 8,
    val digestCapCeilingTokens: Int = 2_000,
    val patchCapTokens: Int = 400,
    // Fact line / note body / note summary
    val factLineMaxChars: Int = 240,
    val noteBodyMaxTokens: Int = 120,
    val noteSummaryMaxChars: Int = 200,
    // Workset seeds per cell / KB injection / focus notes / focus zoom
    val seedsMaxTokens: Int = 4_000,
    val injectionMaxNotes: Int = 8,
    val injectionMaxTokens: Int = 1_500,
    val focusNotesMaxTokens: Int = 300,
    val focusZoomMaxTokens: Int = 300,
    // Touched ledger in [A]
    val touchedInAnchor: Int = 10,
    // Checker time box
    val checkerTimeBoxSeconds: Int = 20,
    // Checker time box for a touched-selector check that fell back to project-wide scope (D-322)
    val checkerFallbackTimeBoxSeconds: Int = 120,
    // θ risk threshold for early slow checks
    val theta: Int = 40,
    // Full-suite cadence
    val fullSuiteCadence: Int = 5,
    // Reserves
    val reserveVerification: Double = 0.15,
    val reserveRecoveryAndPersist: Double = 0.05,
    val campaignRecoveryReserve: Double = 0.10,
    // Stall / loop / repeated signature / doom-loop guard
    val stallTurns: Int = 5,
    val loopIdentical: Int = 2,
    val repeatedSignatureRepairs: Int = 2,
    val doomLoopSameCalls: Int = 3,
    // Probe cell
    val probeTurns: Int = 15,
    val probeTokens: Int = 40_000,
    val probeTier: Tier = Tier.Medium,
    // Review cell
    val reviewLookMax: Int = 10,
    val reviewIncrementTokens: Int = 30_000,
    val reviewCampaignTokens: Int = 60_000,
    val reviewTier: Tier = Tier.High,
    val reviewRoutineTier: Tier = Tier.Medium,
    // Repair helper / substantive attempts per increment / delegation depth / parallel cells
    val repairCalls: Int = 2,
    val attemptsPerIncrement: Int = 2,
    val writerDepth: Int = 1,
    val probeDepth: Int = 2,
    val parallelCells: Int = 3,
    // Campaign cells
    val campaignCells: Int = 12,
    // Flaky policy
    val flakyIsolatedReruns: Int = 1,
    // Memory admission
    val admissionConfidenceMax: Double = 0.6,
    // Profiles
    val profileRoles: ProfileRoles = ProfileRoles(),
    // Mode
    val mode: Mode = Mode.Interactive,
    val executionMode: ExecutionMode = ExecutionMode.TrustedLocal,
    val dClass: DClassPolicy = DClassPolicy.Ask,
    val integrityApproval: IntegrityApproval = IntegrityApproval.Autonomous,
    val unknownOutcomeReconciliation: UnknownOutcomeReconciliation = UnknownOutcomeReconciliation.Host,
    val ceiling: Stage = Stage.Patch,
    // Timeouts
    val runTimeoutSeconds: Int = 120,
    /** Deadline of one git command (D-303); at most one hour, the [io.astrolabe.os.Git] bound. */
    val gitDeadlineSeconds: Int = 600,
) {
    /** Values that would disable a mandatory control or make a bound meaningless (D-48, IX-18). */
    public fun violations(): List<ConfigViolation> {
        val v = ArrayList<ConfigViolation>()
        fun positive(name: String, value: Int) {
            if (value <= 0) v += ConfigViolation(name, "must be positive, got $value")
        }
        fun fraction(name: String, value: Double, exclusiveZero: Boolean) {
            val bad = value.isNaN() || value >= 1.0 || (if (exclusiveZero) value <= 0.0 else value < 0.0)
            if (bad) v += ConfigViolation(name, "must be a fraction in ${if (exclusiveZero) "(0,1)" else "[0,1)"}, got $value")
        }
        fraction("reserveVerification", reserveVerification, exclusiveZero = true)
        fraction("reserveRecoveryAndPersist", reserveRecoveryAndPersist, exclusiveZero = true)
        fraction("campaignRecoveryReserve", campaignRecoveryReserve, exclusiveZero = true)
        if (reserveVerification + reserveRecoveryAndPersist >= 1.0) v += ConfigViolation("reserves", "cell reserves must leave room for work")
        fraction("alpha", alpha, exclusiveZero = true)
        fraction("turnNudgeFraction", turnNudgeFraction, exclusiveZero = true)
        fraction("admissionConfidenceMax", admissionConfidenceMax, exclusiveZero = false)
        positive("turnsPerCell", turnsPerCell)
        positive("providerTerminalWaitSeconds", providerTerminalWaitSeconds)
        positive("k", k)
        if (m < 0) v += ConfigViolation("m", "must be ≥ 0")
        positive("rMaxTokens", rMaxTokens)
        positive("anchorMaxTokens", anchorMaxTokens)
        positive("immediateStubTokens", immediateStubTokens)
        positive("lookBudgetTokens", lookBudgetTokens)
        positive("runBudgetTokens", runBudgetTokens)
        positive("registerCapTokens", registerCapTokens)
        positive("digestCapTokens", digestCapTokens)
        if (digestTokensPerRequirement < 0) v += ConfigViolation("digestTokensPerRequirement", "must be ≥ 0")
        positive("digestCapCeilingTokens", digestCapCeilingTokens)
        positive("patchCapTokens", patchCapTokens)
        positive("factLineMaxChars", factLineMaxChars)
        positive("noteBodyMaxTokens", noteBodyMaxTokens)
        positive("noteSummaryMaxChars", noteSummaryMaxChars)
        positive("touchedInAnchor", touchedInAnchor)
        positive("checkerTimeBoxSeconds", checkerTimeBoxSeconds)
        positive("checkerFallbackTimeBoxSeconds", checkerFallbackTimeBoxSeconds)
        if (theta < 0) v += ConfigViolation("theta", "must be ≥ 0")
        positive("fullSuiteCadence", fullSuiteCadence)
        positive("stallTurns", stallTurns)
        positive("loopIdentical", loopIdentical)
        positive("repeatedSignatureRepairs", repeatedSignatureRepairs)
        positive("doomLoopSameCalls", doomLoopSameCalls)
        positive("probeTurns", probeTurns)
        positive("probeTokens", probeTokens)
        positive("reviewLookMax", reviewLookMax)
        positive("reviewIncrementTokens", reviewIncrementTokens)
        positive("reviewCampaignTokens", reviewCampaignTokens)
        positive("repairCalls", repairCalls)
        positive("attemptsPerIncrement", attemptsPerIncrement)
        positive("writerDepth", writerDepth)
        positive("probeDepth", probeDepth)
        positive("parallelCells", parallelCells)
        positive("campaignCells", campaignCells)
        if (flakyIsolatedReruns < 0) v += ConfigViolation("flakyIsolatedReruns", "must be ≥ 0")
        positive("runTimeoutSeconds", runTimeoutSeconds)
        positive("gitDeadlineSeconds", gitDeadlineSeconds)
        if (gitDeadlineSeconds > 3600) v += ConfigViolation("gitDeadlineSeconds", "must be ≤ 3600")
        v += shapePolicy.violations()
        return v
    }

    /**
     * The contract digest cap for a contract with [requirements] requirements (D-270): every requirement status
     * is mandatory, so the cap grows by [digestTokensPerRequirement] each, never below [digestCapTokens] and
     * never above [digestCapCeilingTokens] unless [digestCapTokens] itself is higher.
     */
    public fun effectiveDigestCapTokens(requirements: Int): Int {
        val scaled = digestCapTokens.toLong() + digestTokensPerRequirement.toLong() * requirements.coerceAtLeast(0)
        return minOf(scaled, maxOf(digestCapTokens, digestCapCeilingTokens).toLong()).toInt()
    }
}

/** Size classes of `select_shape` (§3.5, D-16); S3 stays off until promoted (§10.4). */
@Serializable
public data class ShapePolicy(
    val smallMaxFiles: Int = 3,
    val smallMaxRequirements: Int = 1,
    val largeMinFiles: Int = 11,
    val largeMinRequirements: Int = 4,
    val s3Enabled: Boolean = false,
    /** D-39 measured-slack multiplier over the sequential-S1 estimate; cannot enable S3 before promotion. */
    val slackFactor: Double = 1.5,
    /** Whether an S1 campaign always opens with its plan cell (§4.2) or only when planning has something to decide. */
    val planCell: PlanCellPolicy = PlanCellPolicy.WhenNeeded,
) {
    public fun violations(): List<ConfigViolation> = buildList {
        if (smallMaxFiles < 1 || smallMaxRequirements < 1) add(ConfigViolation("shapePolicy.small*", "must be ≥ 1"))
        if (largeMinFiles <= smallMaxFiles) add(ConfigViolation("shapePolicy.largeMinFiles", "must exceed smallMaxFiles"))
        if (largeMinRequirements <= smallMaxRequirements) add(ConfigViolation("shapePolicy.largeMinRequirements", "must exceed smallMaxRequirements"))
        if (slackFactor < 1.0) add(ConfigViolation("shapePolicy.slackFactor", "must be ≥ 1"))
    }
}

/**
 * When the first cell of an S1 campaign is the plan cell (§4.2). [Always] is §4.2 as written. [WhenNeeded] runs
 * the plan cell only when planning has something to decide: an S1 contract whose acceptance is executable, with no
 * `review:` item, no contract touched and a single-increment graph the plan validator admits as it stands, opens on
 * `G_single(C)` directly. Replans and increment splits always run the plan cell. An attempt frozen before this
 * field existed decodes as [WhenNeeded]; that matters only before its first dispatch, since a planned graph is
 * never re-planned from scratch.
 */
@Serializable
public enum class PlanCellPolicy { WhenNeeded, Always }

/** Which configured profile plays each routing function (§17 "Profiles" row, §11). Ids refer to [Config.profiles]. */
@Serializable
public data class ProfileRoles(
    val main: String = "main",
    val helper: String? = "helper",
    val escalation: String? = null,
)

@Serializable
public data class ConfigViolation(val field: String, val message: String) {
    override fun toString(): String = "$field: $message"
}
