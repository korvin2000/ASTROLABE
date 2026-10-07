package io.astrolabe.route

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

/** Upstream and session jointly delimit a cache series; unknown upstream is its own series. */
public data class CacheSeriesKey(val upstream: String?, val sessionId: String) {
    init { require(sessionId.isNotBlank()) }
}

public data class BetaParameters(val alpha: Double = 1.0, val beta: Double = 1.0) {
    init {
        require(alpha.isFinite() && alpha > 0.0 && beta.isFinite() && beta > 0.0)
        require((alpha + beta).isFinite())
    }
}

public data class ProbabilityInterval(val lower: Double, val upper: Double)

public data class CacheObservation(
    val seriesKey: CacheSeriesKey,
    val cacheableTokens: Long,
    val cachedTokens: Long,
    val inputTokens: Long,
    val blockId: String,
    val harnessRewrite: Boolean = false,
) {
    init {
        require(cachedTokens >= 0 && cacheableTokens >= cachedTokens && inputTokens >= cacheableTokens)
        require(blockId.isNotBlank())
    }
}

/** Current-series sufficient statistics and lifetime accounting, including rewrites in lifetime totals. */
public data class CacheShareState(
    val prior: BetaParameters = BetaParameters(),
    val seriesKey: CacheSeriesKey? = null,
    val cacheableTokens: Long = 0,
    val cachedTokens: Long = 0,
    val blockIds: Set<String> = emptySet(),
    val totalInputTokens: Long = 0,
    val totalCachedTokens: Long = 0,
)

public data class CacheShareEstimate(
    val qHat: Double?,
    val effectiveCalls: Int,
    val posterior: BetaParameters,
    val mean: Double,
    val interval90: ProbabilityInterval,
    val overallCachedShare: Double?,
)

/** Apply one ordered event. Zero-cacheable calls and harness rewrites provide no independent evidence. */
public fun updateCacheShare(state: CacheShareState, observation: CacheObservation): CacheShareState {
    val current = if (state.seriesKey == observation.seriesKey) state else CacheShareState(
        prior = state.prior, seriesKey = observation.seriesKey,
        totalInputTokens = state.totalInputTokens, totalCachedTokens = state.totalCachedTokens,
    )
    val eligible = !observation.harnessRewrite && observation.cacheableTokens > 0
    return current.copy(
        cacheableTokens = Math.addExact(current.cacheableTokens, if (eligible) observation.cacheableTokens else 0),
        cachedTokens = Math.addExact(current.cachedTokens, if (eligible) observation.cachedTokens else 0),
        blockIds = if (eligible) current.blockIds + observation.blockId else current.blockIds.toSet(),
        totalInputTokens = Math.addExact(current.totalInputTokens, observation.inputTokens),
        totalCachedTokens = Math.addExact(current.totalCachedTokens, observation.cachedTokens),
    )
}

/**
 * Token-weighted q, but block-count evidence (§10.4). The equal-tail 90 percent Beta interval uses
 * bisection of the regularized incomplete beta CDF, a continued fraction and Lanczos log-gamma.
 * Each quantile has at most 80 bisection steps. Above total shape 1e6, a clamped normal
 * approximation using Beta mean and variance avoids log-gamma cancellation at huge counts.
 */
public fun estimateCacheShare(state: CacheShareState): CacheShareEstimate {
    val q = if (state.cacheableTokens == 0L) null else state.cachedTokens.toDouble() / state.cacheableTokens
    val n = state.blockIds.size
    val posterior = if (q == null) state.prior else BetaParameters(
        state.prior.alpha + n * q, state.prior.beta + n * (1.0 - q),
    )
    return CacheShareEstimate(
        q, n, posterior, posterior.alpha / (posterior.alpha + posterior.beta),
        ProbabilityInterval(betaQuantile(0.05, posterior), betaQuantile(0.95, posterior)),
        if (state.totalInputTokens == 0L) null else state.totalCachedTokens.toDouble() / state.totalInputTokens,
    )
}

/** Elapsed seconds contain model time only; the producer removes tool time and retries. */
public data class LatencyObservation(val sessionId: String, val outputTokens: Long, val elapsedSeconds: Double) {
    init {
        require(sessionId.isNotBlank() && outputTokens >= 0)
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0)
    }
}

/** Exact session history rather than EWMA; changing session discards the previous fit. */
public data class LatencyState(
    val sessionId: String? = null,
    val observations: List<LatencyObservation> = emptyList(),
)

public sealed interface LatencyEstimate {
    /** Null aggregate rate means no observations or zero total elapsed time. */
    public data class Inseparable(val aggregateTokensPerSecond: Double?) : LatencyEstimate

    /** A zero slope denotes unlimited fitted throughput, represented by positive infinity. */
    public data class Separated(
        val startupSeconds: Double,
        val secondsPerOutputToken: Double,
        val outputTokensPerSecond: Double,
    ) : LatencyEstimate
}

public fun updateLatency(state: LatencyState, observation: LatencyObservation): LatencyState =
    LatencyState(observation.sessionId,
        (if (state.sessionId == observation.sessionId) state.observations else emptyList()) + observation)

/**
 * Theil-Sen median pairwise slope and median intercept resist isolated time outliers without a
 * tuning scale. Project both coefficients onto non-negative values. Refit the retained session
 * history after each update; ordered replay is exact, with no EWMA lambda or hidden state (§10.4).
 * ponytail: O(n squared) pairs and storage; a bounded robust window can replace this for long sessions.
 */
public fun estimateLatency(state: LatencyState): LatencyEstimate {
    val observations = state.observations
    val slopes = buildList {
        for (i in observations.indices) for (j in i + 1 until observations.size) {
            val deltaTokens = observations[j].outputTokens - observations[i].outputTokens
            if (deltaTokens != 0L) add(
                (observations[j].elapsedSeconds - observations[i].elapsedSeconds) / deltaTokens.toDouble(),
            )
        }
    }
    if (slopes.isEmpty()) {
        val seconds = observations.sumOf { it.elapsedSeconds }
        return LatencyEstimate.Inseparable(
            if (seconds > 0.0) observations.sumOf { it.outputTokens.toDouble() } / seconds else null,
        )
    }
    val slope = max(0.0, median(slopes))
    val startup = max(0.0, median(observations.map { it.elapsedSeconds - slope * it.outputTokens }))
    return LatencyEstimate.Separated(startup, slope, if (slope == 0.0) Double.POSITIVE_INFINITY else 1.0 / slope)
}

public enum class PriceProvenance { Billed, Documented, Inferred }

/** All costs in a scope use the same currency and token unit; producers partition by currency. */
public data class PriceScope(val routeId: String, val tier: Tier) {
    init { require(routeId.isNotBlank() && tier.model) }
}

/** Fees are a separate quantity, e.g. one billable request, rather than output tokens. */
public data class PriceObservation(
    val scope: PriceScope,
    val uncachedInputTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    val billedCost: Double,
    val cacheWriteTokens: Long = 0,
    val feeUnits: Double = 0.0,
) {
    init {
        require(uncachedInputTokens >= 0 && cacheReadTokens >= 0 && outputTokens >= 0 && cacheWriteTokens >= 0)
        require(billedCost.isFinite() && billedCost >= 0.0 && feeUnits.isFinite() && feeUnits >= 0.0)
    }
}

public data class PriceRates(
    val uncachedInputPerToken: Double,
    val cacheReadPerToken: Double,
    val outputPerToken: Double,
    val cacheWritePerToken: Double? = null,
    val perFeeUnit: Double? = null,
)

/** Residual acceptance is absoluteCost plus relativeCost times the larger observed or predicted cost. */
public data class PriceTolerance(
    val absoluteCost: Double = 1e-8,
    val relativeCost: Double = 1e-8,
    val rank: Double = 1e-10,
) {
    init {
        require(absoluteCost.isFinite() && absoluteCost >= 0.0)
        require(relativeCost.isFinite() && relativeCost >= 0.0)
        require(rank.isFinite() && rank > 0.0 && rank < 1.0)
    }
}

public data class PriceState(
    val scope: PriceScope,
    val includeCacheWrite: Boolean = false,
    val includeFees: Boolean = false,
    val tolerance: PriceTolerance = PriceTolerance(),
    val observations: List<PriceObservation> = emptyList(),
)

public enum class PriceInsufficiency { RankDeficient, InconsistentBills, NonPhysicalRates }

public sealed interface PriceEstimate {
    public data class InsufficientData(val reason: PriceInsufficiency) : PriceEstimate

    /** Provenance is separate from rates; algebraically recovered rates are always inferred. */
    public data class Determined(val rates: PriceRates, val provenance: PriceProvenance) : PriceEstimate
}

/** A scope cannot be mixed, and nonzero optional quantities must have explicitly enabled columns. */
public fun updatePrices(state: PriceState, observation: PriceObservation): PriceState {
    require(observation.scope == state.scope) { "prices require one route and tier" }
    require(state.includeCacheWrite || observation.cacheWriteTokens == 0L)
    require(state.includeFees || observation.feeUnits == 0.0)
    return state.copy(observations = state.observations + observation)
}

/**
 * Solve a full-rank algebraic system, never least squares (§10.4). Columns and rows are scaled
 * before partial-pivot elimination; pivots at or below tolerance.rank are insufficient evidence.
 * Every original bill must pass the residual threshold, including dependent rows before the basis.
 * Three independent bills identify three rates; each enabled optional column needs another rank.
 * Noise in exactly a square system is unidentifiable; only additional bills can expose it.
 */
public fun estimatePrices(state: PriceState): PriceEstimate {
    val quantities = state.observations.map { observation ->
        require(observation.scope == state.scope)
        require(state.includeCacheWrite || observation.cacheWriteTokens == 0L)
        require(state.includeFees || observation.feeUnits == 0.0)
        buildList {
            add(observation.uncachedInputTokens.toDouble())
            add(observation.cacheReadTokens.toDouble())
            add(observation.outputTokens.toDouble())
            if (state.includeCacheWrite) add(observation.cacheWriteTokens.toDouble())
            if (state.includeFees) add(observation.feeUnits)
        }
    }
    val columns = 3 + (if (state.includeCacheWrite) 1 else 0) + (if (state.includeFees) 1 else 0)
    fun insufficient(reason: PriceInsufficiency): PriceEstimate = PriceEstimate.InsufficientData(reason)
    if (quantities.size < columns) return insufficient(PriceInsufficiency.RankDeficient)
    val scales = DoubleArray(columns) { col -> quantities.maxOf { it[col] } }
    if (scales.any { it == 0.0 }) return insufficient(PriceInsufficiency.RankDeficient)
    val matrix = quantities.mapIndexed { i, row ->
        val scaled = DoubleArray(columns + 1) { col ->
            if (col == columns) state.observations[i].billedCost else row[col] / scales[col]
        }
        val rowScale = (0 until columns).maxOf { scaled[it] }
        if (rowScale > 0.0) for (col in scaled.indices) scaled[col] /= rowScale
        scaled
    }.toMutableList()
    for (col in 0 until columns) {
        val pivot = (col until matrix.size).maxBy { abs(matrix[it][col]) }
        if (abs(matrix[pivot][col]) <= state.tolerance.rank) return insufficient(PriceInsufficiency.RankDeficient)
        val temporary = matrix[col]
        matrix[col] = matrix[pivot]
        matrix[pivot] = temporary
        val divisor = matrix[col][col]
        for (j in col..columns) matrix[col][j] /= divisor
        for (i in matrix.indices) if (i != col) {
            val factor = matrix[i][col]
            for (j in col..columns) matrix[i][j] -= factor * matrix[col][j]
        }
    }
    val rates = DoubleArray(columns) { matrix[it][columns] / scales[it] }
    if (rates.any { !it.isFinite() || it < 0.0 }) return insufficient(PriceInsufficiency.NonPhysicalRates)
    for (i in quantities.indices) {
        val predicted = (0 until columns).sumOf { quantities[i][it] * rates[it] }
        val billed = state.observations[i].billedCost
        val threshold = state.tolerance.absoluteCost + state.tolerance.relativeCost * max(abs(predicted), billed)
        if (!predicted.isFinite() || abs(predicted - billed) > threshold) {
            return insufficient(PriceInsufficiency.InconsistentBills)
        }
    }
    return PriceEstimate.Determined(
        PriceRates(rates[0], rates[1], rates[2],
            if (state.includeCacheWrite) rates[3] else null,
            if (state.includeFees) rates[columns - 1] else null),
        PriceProvenance.Inferred,
    )
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
}

private fun betaQuantile(probability: Double, parameters: BetaParameters): Double {
    val total = parameters.alpha + parameters.beta
    if (total > 1e6) {
        val mean = parameters.alpha / total
        val deviation = 1.6448536269514722 * sqrt(mean * (1.0 - mean) / (total + 1.0))
        return (mean + if (probability < 0.5) -deviation else deviation).coerceIn(0.0, 1.0)
    }
    var lower = 0.0
    var upper = 1.0
    repeat(80) {
        val middle = (lower + upper) / 2.0
        if (betaCdf(middle, parameters.alpha, parameters.beta) < probability) lower = middle else upper = middle
    }
    return (lower + upper) / 2.0
}

private fun betaCdf(x: Double, a: Double, b: Double): Double {
    if (x <= 0.0) return 0.0
    if (x >= 1.0) return 1.0
    val factor = exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * ln(x) + b * ln1p(-x))
    return if (x < (a + 1.0) / (a + b + 2.0)) factor * betaFraction(x, a, b) / a
    else 1.0 - factor * betaFraction(1.0 - x, b, a) / b
}

private fun betaFraction(x: Double, a: Double, b: Double): Double {
    fun guarded(value: Double): Double = if (abs(value) < 1e-300) {
        if (value < 0.0) -1e-300 else 1e-300
    } else value
    var c = 1.0
    var d = 1.0 / guarded(1.0 - (a + b) * x / (a + 1.0))
    var fraction = d
    for (m in 1..1000) {
        val twice = 2.0 * m
        val even = m * (b - m) * x / ((a + twice - 1.0) * (a + twice))
        d = 1.0 / guarded(1.0 + even * d)
        c = guarded(1.0 + even / c)
        fraction *= d * c
        val odd = -(a + m) * (a + b + m) * x / ((a + twice) * (a + twice + 1.0))
        d = 1.0 / guarded(1.0 + odd * d)
        c = guarded(1.0 + odd / c)
        val delta = d * c
        fraction *= delta
        if (abs(delta - 1.0) <= 3e-14) return fraction
    }
    error("incomplete beta continued fraction did not converge")
}

private fun logGamma(value: Double): Double {
    if (value < 0.5) return logGamma(value + 1.0) - ln(value)
    val coefficients = doubleArrayOf(
        676.5203681218851, -1259.1392167224028, 771.3234287776531, -176.6150291621406,
        12.507343278686905, -0.13857109526572012, 9.984369578019572e-6, 1.5056327351493116e-7,
    )
    val z = value - 1.0
    var sum = 0.9999999999998099
    for (i in coefficients.indices) sum += coefficients[i] / (z + i + 1.0)
    val t = z + 7.5
    return 0.9189385332046727 + (z + 0.5) * ln(t) - t + ln(sum)
}
