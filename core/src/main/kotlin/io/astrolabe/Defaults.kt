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
    val turnsPerCell: Int = 40,
    val turnNudgeFraction: Double = 0.80,
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
    // θ risk threshold for early slow checks
    val theta: Int = 40,
    // Full-suite cadence
    val fullSuiteCadence: Int = 5,
    // Reserves
    val reserveVerification: Double = 0.15,
    val reserveRecoveryAndPersist: Double = 0.05,
    val campaignRecoveryReserve: Double = 0.10,
    // Stall / loop / repeated signature / doom-loop guard
    val stallTurns: Int = 3,
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
    val ceiling: Stage = Stage.Patch,
    // Timeouts
    val runTimeoutSeconds: Int = 120,
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
        positive("k", k)
        if (m < 0) v += ConfigViolation("m", "must be ≥ 0")
        positive("rMaxTokens", rMaxTokens)
        positive("anchorMaxTokens", anchorMaxTokens)
        positive("immediateStubTokens", immediateStubTokens)
        positive("lookBudgetTokens", lookBudgetTokens)
        positive("runBudgetTokens", runBudgetTokens)
        positive("registerCapTokens", registerCapTokens)
        positive("digestCapTokens", digestCapTokens)
        positive("patchCapTokens", patchCapTokens)
        positive("factLineMaxChars", factLineMaxChars)
        positive("noteBodyMaxTokens", noteBodyMaxTokens)
        positive("noteSummaryMaxChars", noteSummaryMaxChars)
        positive("touchedInAnchor", touchedInAnchor)
        positive("checkerTimeBoxSeconds", checkerTimeBoxSeconds)
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
        v += shapePolicy.violations()
        return v
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
) {
    public fun violations(): List<ConfigViolation> = buildList {
        if (smallMaxFiles < 1 || smallMaxRequirements < 1) add(ConfigViolation("shapePolicy.small*", "must be ≥ 1"))
        if (largeMinFiles <= smallMaxFiles) add(ConfigViolation("shapePolicy.largeMinFiles", "must exceed smallMaxFiles"))
        if (largeMinRequirements <= smallMaxRequirements) add(ConfigViolation("shapePolicy.largeMinRequirements", "must exceed smallMaxRequirements"))
        if (slackFactor < 1.0) add(ConfigViolation("shapePolicy.slackFactor", "must be ≥ 1"))
    }
}

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
