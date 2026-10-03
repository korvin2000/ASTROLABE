package io.astrolabe

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Profile
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The static balance profiles (plan §4.6, C3; F §4.2 starting values): chosen when a task starts and frozen for its
 * attempt with the rest of the configuration (invariant 12, first tier). [Balanced] is the declared `Defaults` as they
 * stand, so a task that chooses no profile behaves exactly as before.
 */
@Serializable
public enum class BalanceProfile(public val wire: String) {
    Economy("economy"),
    Balanced("balanced"),
    Thorough("thorough"),
}

/** How often the harness re-runs the full suite between increments (F §4.2 "verification depth"); declared acceptance always runs. */
@Serializable
public enum class VerificationDepth(public val wire: String) {
    /** Declared acceptance; the full suite at the campaign end only. */
    Declared("declared"),

    /** `Defaults.fullSuiteCadence` as declared: every 5 verified increments and at the campaign end. */
    Standard("standard"),

    /** The full suite every `⌈0.6 · fullSuiteCadence⌉` verified increments and at the campaign end. */
    Extended("extended"),
}

/** A model's price class for the effort rule (owner: a cheap model → high effort, a dear one → medium). */
@Serializable
public enum class ModelClass { Cheap, Expensive }

/**
 * One profile's parameter vector, relative to the attempt's declared `Defaults` — which are the [BalanceProfile.Balanced]
 * values — so it applies to any host's defaults and its slowdown does not depend on them:
 * - [effortStepCheap] / [effortStepExpensive]: steps from the configured effort for a cheap / an expensive model;
 * - [verification]: the full-suite cadence between increments;
 * - [resultBudgetFactor]: the `look` / `run` result budgets, multiplied;
 * - [contextWindowFraction]: the share of the model's window a cell may use; below `1` the context is also kept below
 *   the model's first price tier;
 * - [stopLossFactor]: the stop-loss threshold `k` — a value for the E4 shadow only, never enforced here.
 */
@Serializable
public data class BalanceVector(
    val effortStepCheap: Int,
    val effortStepExpensive: Int,
    val verification: VerificationDepth,
    val resultBudgetFactor: Double,
    val contextWindowFraction: Double,
    val stopLossFactor: Double,
) {
    init {
        require(resultBudgetFactor > 0.0 && contextWindowFraction > 0.0 && contextWindowFraction <= 1.0) { "factors must be positive and the window fraction at most 1" }
        require(stopLossFactor >= 1.0) { "a stop-loss threshold is at least 1×" }
    }
}

/** A profile's slowdown against [BalanceProfile.Balanced] as [BalanceProfiles.slowdown] bounds it: requests and time. */
public data class Slowdown(val requests: Double, val time: Double) {
    /** The larger of the two ratios. */
    val worst: Double get() = max(requests, time)
}

/**
 * The profile table, how a profile applies to an attempt's configuration, and the slowdown bound behind the owner's
 * ceiling (plan §11 №8: no profile slower than Balanced by more than 2× — soft — and never 3× or more — hard — in
 * requests or time).
 *
 * **Slowdown bound** of a vector `P` against Balanced (an upper bound from the worst case, never a prediction):
 * - requests: `ρ_N = max(1, 1/resultBudgetFactor) · max(1, 1/contextWindowFraction)` — every request a read whose
 *   volume is fixed (a smaller result budget pages it into more calls) and every window refill re-reading it;
 * - time: `ρ_T = ρ_N · λ^max(0, effortStepCheap, effortStepExpensive) · ((1 − κ) + κ · c)`, with `λ` =
 *   [EFFORT_LATENCY_PER_STEP] per effort step up, `κ` = [CHECK_TIME_SHARE] of a task's time in checks, and `c` the
 *   full-suite frequency ratio (`1/0.6` for [VerificationDepth.Extended], else `1`). Speed-ups are never credited.
 *
 * `λ` and `κ` are unmeasured starting values (F §4.2); the D5 benchmark and the E1 binding physics replace them.
 */
public object BalanceProfiles {
    /** The owner's soft ceiling: a profile is at most this many times slower than Balanced. */
    public const val SOFT_SLOWDOWN: Double = 2.0

    /** The owner's hard ceiling: a profile is always less than this many times slower than Balanced. */
    public const val HARD_SLOWDOWN: Double = 3.0

    /** `λ`: the time-per-request multiplier of one effort step up (unmeasured starting value). */
    public const val EFFORT_LATENCY_PER_STEP: Double = 1.5

    /** `κ`: the share of a task's time spent in checks (unmeasured starting value). */
    public const val CHECK_TIME_SHARE: Double = 0.3

    /** A model whose output costs at least this many USD per million tokens is [ModelClass.Expensive]. */
    public const val EXPENSIVE_OUTPUT_USD_PER_MILLION: Int = 5

    /** The extended cadence as a share of the declared one. */
    private const val EXTENDED_CADENCE: Double = 0.6

    private val TABLE: Map<BalanceProfile, BalanceVector> = mapOf(
        BalanceProfile.Economy to BalanceVector(-1, -1, VerificationDepth.Declared, 0.75, 0.75, 2.0),
        BalanceProfile.Balanced to BalanceVector(0, 0, VerificationDepth.Standard, 1.0, 1.0, 3.0),
        BalanceProfile.Thorough to BalanceVector(0, 1, VerificationDepth.Extended, 2.0, 1.0, 4.0),
    )

    /** [profile]'s vector. */
    @JvmStatic
    public fun vector(profile: BalanceProfile): BalanceVector = TABLE.getValue(profile)

    /**
     * [config] under [profile], as an attempt freezes it: [Config.balance] names the profile and its `Defaults` carry the
     * profile's result budgets and cadence. [config]'s own defaults are the Balanced values; Balanced returns it unchanged.
     */
    @JvmStatic
    public fun applied(config: Config, profile: BalanceProfile): Config =
        if (profile == BalanceProfile.Balanced && config.balance == profile) config
        else config.copy(balance = profile, defaults = defaults(config.defaults, vector(profile)))

    /** [defaults] under [vector]: the `look` / `run` budgets scaled and the full-suite cadence of its verification depth. */
    @JvmStatic
    public fun defaults(defaults: Defaults, vector: BalanceVector): Defaults = defaults.copy(
        lookBudgetTokens = scaled(defaults.lookBudgetTokens, vector.resultBudgetFactor),
        runBudgetTokens = scaled(defaults.runBudgetTokens, vector.resultBudgetFactor),
        fullSuiteCadence = when (vector.verification) {
            // Only the campaign-end suite: `verified % MAX_VALUE` is never zero for a verified count in range.
            VerificationDepth.Declared -> Int.MAX_VALUE
            VerificationDepth.Standard -> defaults.fullSuiteCadence
            VerificationDepth.Extended -> maxOf(1, ceil(defaults.fullSuiteCadence * EXTENDED_CADENCE).toInt())
        },
    )

    /** The effort a cell runs at: [configured] moved by the vector's step for [modelClass], kept within `Low..High` once moved. */
    @JvmStatic
    public fun effort(configured: Effort, vector: BalanceVector, modelClass: ModelClass): Effort {
        val step = if (modelClass == ModelClass.Expensive) vector.effortStepExpensive else vector.effortStepCheap
        if (step == 0) return configured
        val lowest = minOf(configured.ordinal, Effort.Low.ordinal)
        return Effort.entries[(configured.ordinal + step).coerceIn(lowest, Effort.High.ordinal)]
    }

    /** [profile]'s price class: output at or above [EXPENSIVE_OUTPUT_USD_PER_MILLION] USD per million tokens; an unpriced or non-USD table is cheap. */
    @JvmStatic
    public fun modelClass(profile: Profile): ModelClass {
        val output = profile.priceTable.perMillion[BillingDimension.OUTPUT]
        val dear = profile.priceTable.currency == "USD" && output != null && output >= BigDecimal.valueOf(EXPENSIVE_OUTPUT_USD_PER_MILLION.toLong())
        return if (dear) ModelClass.Expensive else ModelClass.Cheap
    }

    /**
     * The window a cell of [profile] may use under [vector]: the whole window at a fraction of `1`; below it, that share
     * of the window and never above the first price tier's threshold, so no request is priced at a dearer tier.
     */
    @JvmStatic
    public fun contextLimitTokens(profile: Profile, vector: BalanceVector): Int {
        val window = profile.capabilities.contextLimitTokens
        if (vector.contextWindowFraction >= 1.0) return window
        val share = floor(window * vector.contextWindowFraction).toLong()
        val tier = profile.priceTable.tiers.minOfOrNull { it.inputTokensAbove }
        return maxOf(1L, minOf(share, tier ?: share)).toInt()
    }

    /** [profile] with its window bounded by [contextLimitTokens]; [profile] itself when the bound is its window. */
    @JvmStatic
    public fun bounded(profile: Profile, vector: BalanceVector): Profile {
        val limit = contextLimitTokens(profile, vector)
        return if (limit == profile.capabilities.contextLimitTokens) profile
        else profile.copy(capabilities = profile.capabilities.copy(contextLimitTokens = limit))
    }

    /** The slowdown bound of [vector] against Balanced (see the object's KDoc). */
    @JvmStatic
    public fun slowdown(vector: BalanceVector): Slowdown {
        val requests = max(1.0, 1.0 / vector.resultBudgetFactor) * max(1.0, 1.0 / vector.contextWindowFraction)
        val stepsUp = maxOf(0, vector.effortStepCheap, vector.effortStepExpensive)
        val checks = if (vector.verification == VerificationDepth.Extended) 1.0 / EXTENDED_CADENCE else 1.0
        val time = requests * EFFORT_LATENCY_PER_STEP.pow(stepsUp) * ((1 - CHECK_TIME_SHARE) + CHECK_TIME_SHARE * checks)
        return Slowdown(requests, time)
    }

    private fun scaled(tokens: Int, factor: Double): Int = maxOf(1, (tokens * factor).roundToInt())
}
