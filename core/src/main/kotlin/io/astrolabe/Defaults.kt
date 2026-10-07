package io.astrolabe

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.route.Tier
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

private const val GROWTH_RESERVE_FULL_WINDOW_TOKENS: Int = 65_536

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
    val rMaxTokens: Int = 48_000,
    val anchorMaxTokens: Int = 5_000,
    // Immediate-stub threshold for stale reads
    val immediateStubTokens: Int = 2_400,
    // look.budget / run.budget
    val lookBudgetTokens: Int = 4_000,
    val runBudgetTokens: Int = 4_000,
    // The window from which the compile's growth reserve ([A] max + the larger budget) is reserved in full
    val growthReserveFullWindowTokens: Int = GROWTH_RESERVE_FULL_WINDOW_TOKENS,
    // Register cap / contract digest cap / patch cap
    val registerCapTokens: Int = 3_000,
    val digestCapTokens: Int = 150,
    // D-270: the digest cap grows per requirement up to a ceiling; 0 per requirement pins [digestCapTokens]
    val digestTokensPerRequirement: Int = 8,
    val digestCapCeilingTokens: Int = 2_000,
    val patchCapTokens: Int = 1_200,
    // Fact line / note body / note summary
    val factLineMaxChars: Int = 600,
    val noteBodyMaxTokens: Int = 120,
    val noteSummaryMaxChars: Int = 200,
    // Workset seeds per cell / KB injection / focus notes / focus zoom
    val seedsMaxTokens: Int = 4_000,
    val seedRule: io.astrolabe.context.SeedRule = io.astrolabe.context.SeedRule.V1,
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
    val attemptsPerIncrement: Int = 3,
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
    val runTimeoutSeconds: Int = 600,
    /** Deadline of one git command (D-303); at most one hour, the [io.astrolabe.os.Git] bound. */
    val gitDeadlineSeconds: Int = 600,
    /** D-408: the pressure threshold is `α` of the window but never above this many tokens, whatever the window. */
    val contextCeilingTokens: Int = 128_000,
    /** D-407: the tool calls one response may run; the response is cut at the call past this, or at a call's [doomLoopSameCalls]-th repeat. */
    val callsPerResponseMax: Int = 24,
    /** D-412: the capability set a new contract authorizes, by name ([io.astrolabe.auth.CapabilitySet.BUILT_IN] or a host set). */
    val capabilitySet: String = io.astrolabe.auth.CapabilitySet.WORKSPACE_LOCAL_DEV.name,
    // §5.10-D: the direct anchor's Runs lines, Notes tokens and size target; not encoded at their defaults (A-D.4).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directRunsMaxLines: Int = 5,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directNotesMaxTokens: Int = 200,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val directAnchorTargetTokens: Int = 800,
    /**
     * P8.C.15: the tokens one turn's `run` and `verify` result bodies take in `[T]` together, never below [runBudgetTokens];
     * past it a result is shown short (head, tail, a recall pointer). Not encoded at its default.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val runTurnBudgetTokens: Int = 12_000,
    /**
     * Task workflow §4.2 (C18 item 3): when [seedRule] selects no candidate, the carry ranks the end export by Seeds v2
     * under the same [seedsMaxTokens] and records `seedReason: fallback`. On by default; not encoded at its default.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val seedFallback: Boolean = true,
    /**
     * Task workflow §4.5 (№33): the tokens of the parent's carry block in a follow-up's first cell; cut deterministically —
     * decisions and dead ends kept first, then the touched ledger, then STATUS. Not encoded at its default.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentCarryMaxTokens: Int = 4_000,
) {
    /** The full constructor before [seedFallback]: the carry settings take their defaults. Kept for Java callers. */
    public constructor(
        shapePolicy: ShapePolicy, turnsPerCell: Int, turnNudgeFraction: Double, providerTerminalWaitSeconds: Int, alpha: Double, k: Int, m: Int,
        rMaxTokens: Int, anchorMaxTokens: Int, immediateStubTokens: Int, lookBudgetTokens: Int, runBudgetTokens: Int, growthReserveFullWindowTokens: Int,
        registerCapTokens: Int, digestCapTokens: Int, digestTokensPerRequirement: Int, digestCapCeilingTokens: Int, patchCapTokens: Int,
        factLineMaxChars: Int, noteBodyMaxTokens: Int, noteSummaryMaxChars: Int, seedsMaxTokens: Int, seedRule: io.astrolabe.context.SeedRule,
        injectionMaxNotes: Int, injectionMaxTokens: Int, focusNotesMaxTokens: Int, focusZoomMaxTokens: Int, touchedInAnchor: Int,
        checkerTimeBoxSeconds: Int, checkerFallbackTimeBoxSeconds: Int, theta: Int, fullSuiteCadence: Int, reserveVerification: Double,
        reserveRecoveryAndPersist: Double, campaignRecoveryReserve: Double, stallTurns: Int, loopIdentical: Int, repeatedSignatureRepairs: Int,
        doomLoopSameCalls: Int, probeTurns: Int, probeTokens: Int, probeTier: Tier, reviewLookMax: Int, reviewIncrementTokens: Int,
        reviewCampaignTokens: Int, reviewTier: Tier, reviewRoutineTier: Tier, repairCalls: Int, attemptsPerIncrement: Int, writerDepth: Int,
        probeDepth: Int, parallelCells: Int, campaignCells: Int, flakyIsolatedReruns: Int, admissionConfidenceMax: Double, profileRoles: ProfileRoles,
        mode: Mode, executionMode: ExecutionMode, dClass: DClassPolicy, integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation, ceiling: Stage, runTimeoutSeconds: Int, gitDeadlineSeconds: Int,
        contextCeilingTokens: Int, callsPerResponseMax: Int, capabilitySet: String,
        directRunsMaxLines: Int, directNotesMaxTokens: Int, directAnchorTargetTokens: Int, runTurnBudgetTokens: Int,
    ) : this(
        shapePolicy, turnsPerCell, turnNudgeFraction, providerTerminalWaitSeconds, alpha, k, m,
        rMaxTokens, anchorMaxTokens, immediateStubTokens, lookBudgetTokens, runBudgetTokens, growthReserveFullWindowTokens,
        registerCapTokens, digestCapTokens, digestTokensPerRequirement, digestCapCeilingTokens, patchCapTokens,
        factLineMaxChars, noteBodyMaxTokens, noteSummaryMaxChars, seedsMaxTokens, seedRule,
        injectionMaxNotes, injectionMaxTokens, focusNotesMaxTokens, focusZoomMaxTokens, touchedInAnchor,
        checkerTimeBoxSeconds, checkerFallbackTimeBoxSeconds, theta, fullSuiteCadence, reserveVerification,
        reserveRecoveryAndPersist, campaignRecoveryReserve, stallTurns, loopIdentical, repeatedSignatureRepairs,
        doomLoopSameCalls, probeTurns, probeTokens, probeTier, reviewLookMax, reviewIncrementTokens,
        reviewCampaignTokens, reviewTier, reviewRoutineTier, repairCalls, attemptsPerIncrement, writerDepth,
        probeDepth, parallelCells, campaignCells, flakyIsolatedReruns, admissionConfidenceMax, profileRoles,
        mode, executionMode, dClass, integrityApproval,
        unknownOutcomeReconciliation, ceiling, runTimeoutSeconds, gitDeadlineSeconds,
        contextCeilingTokens, callsPerResponseMax, capabilitySet, directRunsMaxLines, directNotesMaxTokens, directAnchorTargetTokens, runTurnBudgetTokens,
        true, 4_000,
    )

    /** The full constructor before [runTurnBudgetTokens]: it takes its default. Kept for Java callers. */
    public constructor(
        shapePolicy: ShapePolicy, turnsPerCell: Int, turnNudgeFraction: Double, providerTerminalWaitSeconds: Int, alpha: Double, k: Int, m: Int,
        rMaxTokens: Int, anchorMaxTokens: Int, immediateStubTokens: Int, lookBudgetTokens: Int, runBudgetTokens: Int, growthReserveFullWindowTokens: Int,
        registerCapTokens: Int, digestCapTokens: Int, digestTokensPerRequirement: Int, digestCapCeilingTokens: Int, patchCapTokens: Int,
        factLineMaxChars: Int, noteBodyMaxTokens: Int, noteSummaryMaxChars: Int, seedsMaxTokens: Int, seedRule: io.astrolabe.context.SeedRule,
        injectionMaxNotes: Int, injectionMaxTokens: Int, focusNotesMaxTokens: Int, focusZoomMaxTokens: Int, touchedInAnchor: Int,
        checkerTimeBoxSeconds: Int, checkerFallbackTimeBoxSeconds: Int, theta: Int, fullSuiteCadence: Int, reserveVerification: Double,
        reserveRecoveryAndPersist: Double, campaignRecoveryReserve: Double, stallTurns: Int, loopIdentical: Int, repeatedSignatureRepairs: Int,
        doomLoopSameCalls: Int, probeTurns: Int, probeTokens: Int, probeTier: Tier, reviewLookMax: Int, reviewIncrementTokens: Int,
        reviewCampaignTokens: Int, reviewTier: Tier, reviewRoutineTier: Tier, repairCalls: Int, attemptsPerIncrement: Int, writerDepth: Int,
        probeDepth: Int, parallelCells: Int, campaignCells: Int, flakyIsolatedReruns: Int, admissionConfidenceMax: Double, profileRoles: ProfileRoles,
        mode: Mode, executionMode: ExecutionMode, dClass: DClassPolicy, integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation, ceiling: Stage, runTimeoutSeconds: Int, gitDeadlineSeconds: Int,
        contextCeilingTokens: Int, callsPerResponseMax: Int, capabilitySet: String,
        directRunsMaxLines: Int, directNotesMaxTokens: Int, directAnchorTargetTokens: Int,
    ) : this(
        shapePolicy, turnsPerCell, turnNudgeFraction, providerTerminalWaitSeconds, alpha, k, m,
        rMaxTokens, anchorMaxTokens, immediateStubTokens, lookBudgetTokens, runBudgetTokens, growthReserveFullWindowTokens,
        registerCapTokens, digestCapTokens, digestTokensPerRequirement, digestCapCeilingTokens, patchCapTokens,
        factLineMaxChars, noteBodyMaxTokens, noteSummaryMaxChars, seedsMaxTokens, seedRule,
        injectionMaxNotes, injectionMaxTokens, focusNotesMaxTokens, focusZoomMaxTokens, touchedInAnchor,
        checkerTimeBoxSeconds, checkerFallbackTimeBoxSeconds, theta, fullSuiteCadence, reserveVerification,
        reserveRecoveryAndPersist, campaignRecoveryReserve, stallTurns, loopIdentical, repeatedSignatureRepairs,
        doomLoopSameCalls, probeTurns, probeTokens, probeTier, reviewLookMax, reviewIncrementTokens,
        reviewCampaignTokens, reviewTier, reviewRoutineTier, repairCalls, attemptsPerIncrement, writerDepth,
        probeDepth, parallelCells, campaignCells, flakyIsolatedReruns, admissionConfidenceMax, profileRoles,
        mode, executionMode, dClass, integrityApproval,
        unknownOutcomeReconciliation, ceiling, runTimeoutSeconds, gitDeadlineSeconds,
        contextCeilingTokens, callsPerResponseMax, capabilitySet, directRunsMaxLines, directNotesMaxTokens, directAnchorTargetTokens, 12_000,
    )

    /** The full constructor before the §5.10-D numbers: those take their defaults. Kept for Java callers. */
    public constructor(
        shapePolicy: ShapePolicy, turnsPerCell: Int, turnNudgeFraction: Double, providerTerminalWaitSeconds: Int, alpha: Double, k: Int, m: Int,
        rMaxTokens: Int, anchorMaxTokens: Int, immediateStubTokens: Int, lookBudgetTokens: Int, runBudgetTokens: Int, growthReserveFullWindowTokens: Int,
        registerCapTokens: Int, digestCapTokens: Int, digestTokensPerRequirement: Int, digestCapCeilingTokens: Int, patchCapTokens: Int,
        factLineMaxChars: Int, noteBodyMaxTokens: Int, noteSummaryMaxChars: Int, seedsMaxTokens: Int, seedRule: io.astrolabe.context.SeedRule,
        injectionMaxNotes: Int, injectionMaxTokens: Int, focusNotesMaxTokens: Int, focusZoomMaxTokens: Int, touchedInAnchor: Int,
        checkerTimeBoxSeconds: Int, checkerFallbackTimeBoxSeconds: Int, theta: Int, fullSuiteCadence: Int, reserveVerification: Double,
        reserveRecoveryAndPersist: Double, campaignRecoveryReserve: Double, stallTurns: Int, loopIdentical: Int, repeatedSignatureRepairs: Int,
        doomLoopSameCalls: Int, probeTurns: Int, probeTokens: Int, probeTier: Tier, reviewLookMax: Int, reviewIncrementTokens: Int,
        reviewCampaignTokens: Int, reviewTier: Tier, reviewRoutineTier: Tier, repairCalls: Int, attemptsPerIncrement: Int, writerDepth: Int,
        probeDepth: Int, parallelCells: Int, campaignCells: Int, flakyIsolatedReruns: Int, admissionConfidenceMax: Double, profileRoles: ProfileRoles,
        mode: Mode, executionMode: ExecutionMode, dClass: DClassPolicy, integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation, ceiling: Stage, runTimeoutSeconds: Int, gitDeadlineSeconds: Int,
        contextCeilingTokens: Int, callsPerResponseMax: Int, capabilitySet: String,
    ) : this(
        shapePolicy, turnsPerCell, turnNudgeFraction, providerTerminalWaitSeconds, alpha, k, m,
        rMaxTokens, anchorMaxTokens, immediateStubTokens, lookBudgetTokens, runBudgetTokens, growthReserveFullWindowTokens,
        registerCapTokens, digestCapTokens, digestTokensPerRequirement, digestCapCeilingTokens, patchCapTokens,
        factLineMaxChars, noteBodyMaxTokens, noteSummaryMaxChars, seedsMaxTokens, seedRule,
        injectionMaxNotes, injectionMaxTokens, focusNotesMaxTokens, focusZoomMaxTokens, touchedInAnchor,
        checkerTimeBoxSeconds, checkerFallbackTimeBoxSeconds, theta, fullSuiteCadence, reserveVerification,
        reserveRecoveryAndPersist, campaignRecoveryReserve, stallTurns, loopIdentical, repeatedSignatureRepairs,
        doomLoopSameCalls, probeTurns, probeTokens, probeTier, reviewLookMax, reviewIncrementTokens,
        reviewCampaignTokens, reviewTier, reviewRoutineTier, repairCalls, attemptsPerIncrement, writerDepth,
        probeDepth, parallelCells, campaignCells, flakyIsolatedReruns, admissionConfidenceMax, profileRoles,
        mode, executionMode, dClass, integrityApproval,
        unknownOutcomeReconciliation, ceiling, runTimeoutSeconds, gitDeadlineSeconds,
        contextCeilingTokens, callsPerResponseMax, capabilitySet, 5, 200, 800,
    )

    /** The v1.0 full constructor: [growthReserveFullWindowTokens] takes its default. Kept for Java callers. */
    public constructor(
        shapePolicy: ShapePolicy,
        turnsPerCell: Int,
        turnNudgeFraction: Double,
        providerTerminalWaitSeconds: Int,
        alpha: Double,
        k: Int,
        m: Int,
        rMaxTokens: Int,
        anchorMaxTokens: Int,
        immediateStubTokens: Int,
        lookBudgetTokens: Int,
        runBudgetTokens: Int,
        registerCapTokens: Int,
        digestCapTokens: Int,
        digestTokensPerRequirement: Int,
        digestCapCeilingTokens: Int,
        patchCapTokens: Int,
        factLineMaxChars: Int,
        noteBodyMaxTokens: Int,
        noteSummaryMaxChars: Int,
        seedsMaxTokens: Int,
        injectionMaxNotes: Int,
        injectionMaxTokens: Int,
        focusNotesMaxTokens: Int,
        focusZoomMaxTokens: Int,
        touchedInAnchor: Int,
        checkerTimeBoxSeconds: Int,
        checkerFallbackTimeBoxSeconds: Int,
        theta: Int,
        fullSuiteCadence: Int,
        reserveVerification: Double,
        reserveRecoveryAndPersist: Double,
        campaignRecoveryReserve: Double,
        stallTurns: Int,
        loopIdentical: Int,
        repeatedSignatureRepairs: Int,
        doomLoopSameCalls: Int,
        probeTurns: Int,
        probeTokens: Int,
        probeTier: Tier,
        reviewLookMax: Int,
        reviewIncrementTokens: Int,
        reviewCampaignTokens: Int,
        reviewTier: Tier,
        reviewRoutineTier: Tier,
        repairCalls: Int,
        attemptsPerIncrement: Int,
        writerDepth: Int,
        probeDepth: Int,
        parallelCells: Int,
        campaignCells: Int,
        flakyIsolatedReruns: Int,
        admissionConfidenceMax: Double,
        profileRoles: ProfileRoles,
        mode: Mode,
        executionMode: ExecutionMode,
        dClass: DClassPolicy,
        integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation,
        ceiling: Stage,
        runTimeoutSeconds: Int,
        gitDeadlineSeconds: Int,
    ) : this(
        shapePolicy = shapePolicy, turnsPerCell = turnsPerCell, turnNudgeFraction = turnNudgeFraction,
        providerTerminalWaitSeconds = providerTerminalWaitSeconds, alpha = alpha, k = k, m = m, rMaxTokens = rMaxTokens,
        anchorMaxTokens = anchorMaxTokens, immediateStubTokens = immediateStubTokens, lookBudgetTokens = lookBudgetTokens,
        runBudgetTokens = runBudgetTokens, registerCapTokens = registerCapTokens, digestCapTokens = digestCapTokens,
        digestTokensPerRequirement = digestTokensPerRequirement, digestCapCeilingTokens = digestCapCeilingTokens,
        patchCapTokens = patchCapTokens, factLineMaxChars = factLineMaxChars, noteBodyMaxTokens = noteBodyMaxTokens,
        noteSummaryMaxChars = noteSummaryMaxChars, seedsMaxTokens = seedsMaxTokens, injectionMaxNotes = injectionMaxNotes,
        injectionMaxTokens = injectionMaxTokens, focusNotesMaxTokens = focusNotesMaxTokens, focusZoomMaxTokens = focusZoomMaxTokens,
        touchedInAnchor = touchedInAnchor, checkerTimeBoxSeconds = checkerTimeBoxSeconds,
        checkerFallbackTimeBoxSeconds = checkerFallbackTimeBoxSeconds, theta = theta, fullSuiteCadence = fullSuiteCadence,
        reserveVerification = reserveVerification, reserveRecoveryAndPersist = reserveRecoveryAndPersist,
        campaignRecoveryReserve = campaignRecoveryReserve, stallTurns = stallTurns, loopIdentical = loopIdentical,
        repeatedSignatureRepairs = repeatedSignatureRepairs, doomLoopSameCalls = doomLoopSameCalls, probeTurns = probeTurns,
        probeTokens = probeTokens, probeTier = probeTier, reviewLookMax = reviewLookMax, reviewIncrementTokens = reviewIncrementTokens,
        reviewCampaignTokens = reviewCampaignTokens, reviewTier = reviewTier, reviewRoutineTier = reviewRoutineTier,
        repairCalls = repairCalls, attemptsPerIncrement = attemptsPerIncrement, writerDepth = writerDepth, probeDepth = probeDepth,
        parallelCells = parallelCells, campaignCells = campaignCells, flakyIsolatedReruns = flakyIsolatedReruns,
        admissionConfidenceMax = admissionConfidenceMax, profileRoles = profileRoles, mode = mode, executionMode = executionMode,
        dClass = dClass, integrityApproval = integrityApproval, unknownOutcomeReconciliation = unknownOutcomeReconciliation,
        ceiling = ceiling, runTimeoutSeconds = runTimeoutSeconds, gitDeadlineSeconds = gitDeadlineSeconds,
        growthReserveFullWindowTokens = GROWTH_RESERVE_FULL_WINDOW_TOKENS,
    )

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
        positive("contextCeilingTokens", contextCeilingTokens)
        positive("callsPerResponseMax", callsPerResponseMax)
        if (capabilitySet.isBlank()) v += ConfigViolation("capabilitySet", "must name a capability set")
        positive("anchorMaxTokens", anchorMaxTokens)
        positive("immediateStubTokens", immediateStubTokens)
        positive("lookBudgetTokens", lookBudgetTokens)
        positive("runBudgetTokens", runBudgetTokens)
        positive("runTurnBudgetTokens", runTurnBudgetTokens)
        positive("parentCarryMaxTokens", parentCarryMaxTokens)
        positive("growthReserveFullWindowTokens", growthReserveFullWindowTokens)
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

    /**
     * The compile's growth reserve for a window of [contextLimitTokens] (§6.1): the input a cell adds before its first
     * rebuild — `[A]` up to [anchorMaxTokens] and the next observation, the larger of [lookBudgetTokens] and
     * [runBudgetTokens]. A window of [growthReserveFullWindowTokens] or more reserves all of it; a smaller one its
     * proportional share, since the cell cuts its reads to what the window leaves.
     */
    public fun growthReserveTokens(contextLimitTokens: Int): Long {
        val full = anchorMaxTokens.toLong() + maxOf(lookBudgetTokens, runBudgetTokens)
        if (contextLimitTokens >= growthReserveFullWindowTokens) return full
        return full * contextLimitTokens.coerceAtLeast(0) / growthReserveFullWindowTokens
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
