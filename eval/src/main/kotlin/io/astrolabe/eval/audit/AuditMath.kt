package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** Billing classes a price is fitted for, in column order. */
public enum class PriceClass(public val id: String) { UncachedInput("uncached_input"), CacheRead("cache_read"), CacheWrite("cache_write"), Output("output") }

/** How well the fitted prices reproduce every billed call. */
public enum class Agreement {
    /** More calls than prices, and each billed amount is reproduced within a millionth of itself. */
    Exact,

    /** More calls than prices, and some billed amount is not reproduced: the route mixes prices (an unlogged upstream, a tier, a fee). */
    Approximate,

    /** As many independent calls as prices: determined, with no call left to check them. */
    Determined,

    /** Fewer independent calls than prices with tokens: no price is claimed. */
    Unidentified,
}

/** One billed call of a price fit: its tokens by class and its billed amount. */
public data class FitRow(val tokens: Map<PriceClass, Long>, val billed: BigDecimal)

/**
 * Prices per million tokens of one [binding], fitted from billed calls. A class no call used has no price (`null`);
 * the other prices do not depend on it. [maxResidual] is the largest `|C_i − x_i·p|` over the calls.
 */
@Serializable
public data class PriceFit(
    val binding: Binding,
    val calls: Int,
    val perMillion: Map<PriceClass, SerializableBigDecimal?>,
    val agreement: Agreement,
    val maxResidual: SerializableBigDecimal?,
    val maxRelativeResidual: Double?,
)

/**
 * Every formula of the offline auditor (§4.7, §10.4; F §2.1, §2.2, §6.1), in one place so it can be checked in one
 * place. Tokens are provider tokens from `usage`, except anchors, which are the harness estimator's count.
 */
public object AuditMath {
    private val MILLION = BigDecimal(1_000_000)
    private val MC = MathContext.DECIMAL128

    /** A step whose shortfall exceeds `max(BREAK_TOKENS, BREAK_SHARE · b)` is a cache break, not block rounding or anchor noise. */
    public const val BREAK_TOKENS: Long = 2_048
    public const val BREAK_SHARE: Double = 0.10

    /** A break that kept at most this share of the cacheable prefix lost the whole prefix, `[S]` included. */
    public const val FULL_MISS_SHARE: Double = 0.05

    /** A fit reproduces a call when `|r_i| ≤ AGREEMENT · C_i`: provider rounding of the charge, not a price difference. */
    public val AGREEMENT: BigDecimal = BigDecimal("1E-6")

    /**
     * Least squares `C_i = x_i · p` (§10.4) over the billed calls of one route, solved exactly: the normal equations
     * `XᵀX p = Xᵀy` have integer coefficients once each charge is scaled by `10^s`, so Cramer's rule with Bareiss
     * determinants gives `p_j = det_j / (det · 10^s)` without rounding; only the final quotient is rounded (DECIMAL128).
     * Classes no call used are left out (no price); a singular system leaves every price unidentified. With
     * `n > d` calls the remaining `n − d` degrees of freedom check the prices ([Agreement]).
     */
    @JvmStatic
    public fun fitPrices(binding: Binding, rows: List<FitRow>): PriceFit {
        val classes = PriceClass.entries.filter { c -> rows.any { (it.tokens[c] ?: 0) > 0 } }
        val none = PriceFit(binding, rows.size, PriceClass.entries.associateWith { null }, Agreement.Unidentified, null, null)
        if (classes.isEmpty() || rows.size < classes.size) return none
        val scale = rows.maxOf { it.billed.stripTrailingZeros().scale() }.coerceAtLeast(0)
        val y = rows.map { it.billed.movePointRight(scale).toBigIntegerExact() }
        val x = rows.map { row -> classes.map { BigInteger.valueOf(row.tokens[it] ?: 0) } }
        val d = classes.size
        val a = List(d) { i -> List(d) { j -> x.fold(BigInteger.ZERO) { s, r -> s + r[i] * r[j] } } }
        val v = List(d) { i -> x.indices.fold(BigInteger.ZERO) { s, n -> s + x[n][i] * y[n] } }
        val det = determinant(a)
        if (det.signum() == 0) return none
        val dets = List(d) { j -> determinant(List(d) { i -> List(d) { k -> if (k == j) v[i] else a[i][k] } }) }
        val denominator = BigDecimal(det).movePointRight(scale)
        val perMillion = PriceClass.entries.associateWith { c ->
            val j = classes.indexOf(c)
            if (j < 0) null else BigDecimal(dets[j]).multiply(MILLION).divide(denominator, MC)
        }
        var maxResidual = BigDecimal.ZERO
        var maxRelative = 0.0
        var agrees = true
        for (n in rows.indices) {
            // r_n · det · 10^s = y_n · det − Σ_j x_nj · det_j, an integer.
            val numerator = y[n] * det - (0 until d).fold(BigInteger.ZERO) { s, j -> s + x[n][j] * dets[j] }
            val residual = BigDecimal(numerator).divide(denominator, MC).abs()
            if (residual > maxResidual) maxResidual = residual
            val charge = rows[n].billed.abs()
            if (charge.signum() > 0) maxRelative = maxOf(maxRelative, residual.divide(charge, MC).toDouble())
            if (residual > charge.multiply(AGREEMENT)) agrees = false
        }
        val agreement = when {
            rows.size == d -> Agreement.Determined
            agrees -> Agreement.Exact
            else -> Agreement.Approximate
        }
        return PriceFit(binding, rows.size, perMillion, agreement, maxResidual.stripTrailingZeros(), maxRelative)
    }

    /** Fraction-free Gaussian elimination (Bareiss): every division is exact, so the determinant of an integer matrix stays an integer. */
    internal fun determinant(matrix: List<List<BigInteger>>): BigInteger {
        val n = matrix.size
        val m = Array(n) { i -> Array(n) { j -> matrix[i][j] } }
        var sign = BigInteger.ONE
        var previous = BigInteger.ONE
        for (k in 0 until n - 1) {
            if (m[k][k].signum() == 0) {
                val pivot = (k + 1 until n).firstOrNull { m[it][k].signum() != 0 } ?: return BigInteger.ZERO
                val row = m[k]; m[k] = m[pivot]; m[pivot] = row
                sign = sign.negate()
            }
            for (i in k + 1 until n) for (j in k + 1 until n) m[i][j] = (m[i][j] * m[k][k] - m[i][k] * m[k][j]) / previous
            previous = m[k][k]
        }
        return sign * m[n - 1][n - 1]
    }

    /** `tokens · perMillion / 10^6`, or `null` when either is unknown. */
    @JvmStatic
    public fun money(tokens: Long?, perMillion: BigDecimal?): BigDecimal? =
        if (tokens == null || perMillion == null) null else BigDecimal.valueOf(tokens).multiply(perMillion).divide(MILLION, MC)

    /** `part / total` as a fraction; `null` when either is unknown or the total is zero. */
    @JvmStatic
    public fun share(part: BigDecimal?, total: BigDecimal?): Double? =
        if (part == null || total == null || total.signum() == 0) null else part.divide(total, MC).toDouble()

    /**
     * The cache step from one request to the next in a lineage (§10.4). The previous request's prefix, without its
     * anchor (`[A]` is replaced every turn, so it is never part of the next prefix), is what the provider could serve
     * from cache: `b = max(0, min(I₋ − A₋, I − A))` — the `min` drops what an eviction removed. The shortfall
     * `s = max(0, b − c)` is the prefix the provider did not serve from cache, which the request paid as uncached.
     */
    @JvmStatic
    public fun step(previousInput: Long, previousAnchor: Long, input: Long, anchor: Long, cached: Long): CacheStep {
        val cacheable = maxOf(0L, minOf(previousInput - previousAnchor, input - anchor))
        val shortfall = maxOf(0L, cacheable - cached)
        val broken = shortfall > maxOf(BREAK_TOKENS, (BREAK_SHARE * cacheable).toLong())
        return CacheStep(cacheable, cached, shortfall, broken, broken && cached <= FULL_MISS_SHARE * cacheable)
    }

    /** `q̂ = Σ min(c_i, b_i) / Σ b_i` over unchanged-prefix steps (§10.4); a `c_i` above `b_i` is anchor-estimate noise and is capped. */
    @JvmStatic
    public fun qHat(steps: List<CacheStep>): Double? {
        val b = steps.sumOf { it.cacheable }
        return if (b == 0L) null else steps.sumOf { minOf(it.cached, it.cacheable) }.toDouble() / b
    }

    /** The hit share: `Σ cache_read / Σ input` over the calls whose input is known (F §6.1's 86,7 %). */
    @JvmStatic
    public fun hitShare(cacheRead: Long, input: Long): Double? = if (input == 0L) null else cacheRead.toDouble() / input

    /** What re-paying [tokens] of prefix cost over reading them from cache: `tokens · (p_u − p_c)`. */
    @JvmStatic
    public fun missCost(tokens: Long, prices: Map<PriceClass, BigDecimal?>): BigDecimal? {
        val uncached = prices[PriceClass.UncachedInput] ?: return null
        return money(tokens, uncached.subtract(prices[PriceClass.CacheRead] ?: return null))
    }
}

/** One request-to-request step: cacheable prefix `b`, cached `c`, shortfall `s`, and whether it broke the cache (fully). */
@Serializable
public data class CacheStep(val cacheable: Long, val cached: Long, val shortfall: Long, val broken: Boolean, val fullMiss: Boolean)
