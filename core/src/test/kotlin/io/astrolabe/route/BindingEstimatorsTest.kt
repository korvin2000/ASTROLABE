package io.astrolabe.route

import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BindingEstimatorsTest {
    private val series = CacheSeriesKey("upstream", "session")
    private val scope = PriceScope("route", Tier.Medium)

    private fun cache(
        block: String, cacheable: Long = 100, cached: Long = 60, input: Long = 200,
        key: CacheSeriesKey = series, rewrite: Boolean = false,
    ) = CacheObservation(key, cacheable, cached, input, block, rewrite)

    private fun bill(
        u: Long, c: Long, o: Long, cost: Double = u * 0.002 + c * 0.0005 + o * 0.006,
        write: Long = 0, fees: Double = 0.0, route: PriceScope = scope,
    ) = PriceObservation(route, u, c, o, cost, write, fees)

    private fun prices(vararg rows: PriceObservation) = rows.fold(PriceState(scope), ::updatePrices)

    @Test fun `empty cache returns uniform prior and its equal tail interval`() {
        val estimate = estimateCacheShare(CacheShareState())
        assertNull(estimate.qHat)
        assertNull(estimate.overallCachedShare)
        assertEquals(0, estimate.effectiveCalls)
        assertEquals(BetaParameters(), estimate.posterior)
        assertEquals(0.5, estimate.mean)
        assertEquals(0.05, estimate.interval90.lower, 1e-12)
        assertEquals(0.95, estimate.interval90.upper, 1e-12)
    }

    @Test fun `cache weights tokens but counts independent blocks`() {
        val state = listOf(cache("a", 100, 20), cache("a", 300, 240, 400), cache("b", 100, 40))
            .fold(CacheShareState(), ::updateCacheShare)
        val estimate = estimateCacheShare(state)
        assertEquals(0.6, estimate.qHat)
        assertEquals(2, estimate.effectiveCalls)
        assertEquals(2.2, estimate.posterior.alpha, 1e-12)
        assertEquals(1.8, estimate.posterior.beta, 1e-12)
        assertEquals(0.55, estimate.mean, 1e-12)
        assertEquals(0.375, estimate.overallCachedShare)
        assertTrue(estimate.interval90.lower < estimate.mean && estimate.interval90.upper > estimate.mean)
    }

    @Test fun `synthetic cache series recovers known share without counting tokens as trials`() {
        val state = (1..100).map { cache("block-$it", 10_000, 7_500, 20_000) }
            .fold(CacheShareState(), ::updateCacheShare)
        val estimate = estimateCacheShare(state)
        assertEquals(0.75, estimate.qHat)
        assertEquals(100, estimate.effectiveCalls)
        assertEquals(BetaParameters(76.0, 26.0), estimate.posterior)
        assertEquals(0.75, estimate.mean, 0.005)
        assertTrue(0.75 in estimate.interval90.lower..estimate.interval90.upper)
    }

    @Test fun `rewrites and zero cacheable calls affect accounting without posterior evidence`() {
        val state = listOf(cache("a"), cache("b", 100, 100, rewrite = true), cache("c", 0, 0))
            .fold(CacheShareState(), ::updateCacheShare)
        val estimate = estimateCacheShare(state)
        assertEquals(0.6, estimate.qHat)
        assertEquals(1, estimate.effectiveCalls)
        assertEquals(160.0 / 600.0, estimate.overallCachedShare)
        val onlyRewrite = updateCacheShare(CacheShareState(), cache("b", rewrite = true))
        assertEquals(BetaParameters(), estimateCacheShare(onlyRewrite).posterior)
    }

    @Test fun `upstream and session changes start fresh series even on a rewrite`() {
        val first = updateCacheShare(CacheShareState(), cache("same-block", 100, 100))
        for (key in listOf(series.copy(upstream = "other"), series.copy(sessionId = "other"), series.copy(upstream = null))) {
            val changed = updateCacheShare(first, cache("same-block", 100, 0, key = key))
            val estimate = estimateCacheShare(changed)
            assertEquals(key, changed.seriesKey)
            assertEquals(0.0, estimate.qHat)
            assertEquals(1, estimate.effectiveCalls)
            assertEquals(0.25, estimate.overallCachedShare)
            val reset = updateCacheShare(first, cache("same-block", key = key, rewrite = true))
            assertEquals(BetaParameters(), estimateCacheShare(reset).posterior)
            val returned = updateCacheShare(changed, cache("same-block"))
            assertEquals(1, estimateCacheShare(returned).effectiveCalls)
            assertEquals(0.6, estimateCacheShare(returned).qHat)
        }
    }

    @Test fun `custom prior and extreme shares have known beta quantiles`() {
        val prior = estimateCacheShare(CacheShareState(prior = BetaParameters(2.0, 1.0)))
        assertEquals(0.05.pow(0.5), prior.interval90.lower, 1e-12)
        assertEquals(0.95.pow(0.5), prior.interval90.upper, 1e-12)
        for (cached in listOf(0L, 100L)) {
            val state = (1..20).map { cache("$it", cached = cached) }.fold(CacheShareState(), ::updateCacheShare)
            val interval = estimateCacheShare(state).interval90
            if (cached == 100L) {
                assertEquals(0.05.pow(1.0 / 21.0), interval.lower, 1e-12)
                assertEquals(0.95.pow(1.0 / 21.0), interval.upper, 1e-12)
            } else {
                assertEquals(1.0 - 0.95.pow(1.0 / 21.0), interval.lower, 1e-12)
                assertEquals(1.0 - 0.05.pow(1.0 / 21.0), interval.upper, 1e-12)
            }
        }
    }

    @Test fun `empty latency and single observation remain inseparable`() {
        assertEquals(LatencyEstimate.Inseparable(null), estimateLatency(LatencyState()))
        val single = updateLatency(LatencyState(), LatencyObservation("s", 100, 2.0))
        assertEquals(LatencyEstimate.Inseparable(50.0), estimateLatency(single))
        val zero = updateLatency(LatencyState(), LatencyObservation("s", 0, 0.0))
        assertEquals(LatencyEstimate.Inseparable(null), estimateLatency(zero))
    }

    @Test fun `large prior uses a finite concentrated interval`() {
        val estimate = estimateCacheShare(CacheShareState(prior = BetaParameters(7.5e11, 2.5e11)))
        assertEquals(0.75, estimate.mean)
        assertTrue(estimate.interval90.lower.isFinite() && estimate.interval90.upper.isFinite())
        assertTrue(estimate.interval90.lower < 0.75 && estimate.interval90.upper > 0.75)
        assertTrue(estimate.interval90.upper - estimate.interval90.lower < 1e-5)
    }

    @Test fun `latency recovers known startup and throughput despite an outlier`() {
        val rows = (1..9).map { LatencyObservation("s", it * 100L, 0.4 + it * 2.0) } +
            LatencyObservation("s", 550, 1000.0)
        val estimate = assertIs<LatencyEstimate.Separated>(estimateLatency(rows.fold(LatencyState(), ::updateLatency)))
        assertEquals(0.4, estimate.startupSeconds, 1e-12)
        assertEquals(0.02, estimate.secondsPerOutputToken, 1e-12)
        assertEquals(50.0, estimate.outputTokensPerSecond, 1e-10)
    }

    @Test fun `identical output lengths expose only aggregate rate`() {
        val rows = listOf(LatencyObservation("s", 100, 2.0), LatencyObservation("s", 100, 3.0))
        assertEquals(LatencyEstimate.Inseparable(40.0), estimateLatency(rows.fold(LatencyState(), ::updateLatency)))
    }

    @Test fun `latency coefficients are nonnegative and zero slope has explicit unlimited rate`() {
        val decreasing = listOf(LatencyObservation("s", 100, 3.0), LatencyObservation("s", 200, 1.0))
        val flat = assertIs<LatencyEstimate.Separated>(estimateLatency(decreasing.fold(LatencyState(), ::updateLatency)))
        assertEquals(0.0, flat.secondsPerOutputToken)
        assertEquals(Double.POSITIVE_INFINITY, flat.outputTokensPerSecond)
        assertEquals(2.0, flat.startupSeconds)
        val negativeIntercept = listOf(LatencyObservation("s", 100, 1.0), LatencyObservation("s", 200, 3.0))
        val clamped = assertIs<LatencyEstimate.Separated>(estimateLatency(negativeIntercept.fold(LatencyState(), ::updateLatency)))
        assertEquals(0.0, clamped.startupSeconds)
        assertEquals(0.02, clamped.secondsPerOutputToken)
    }

    @Test fun `latency session change discards old fit`() {
        val old = listOf(LatencyObservation("old", 100, 1.0), LatencyObservation("old", 200, 2.0))
            .fold(LatencyState(), ::updateLatency)
        val changed = updateLatency(old, LatencyObservation("new", 100, 4.0))
        assertEquals("new", changed.sessionId)
        assertEquals(1, changed.observations.size)
        assertEquals(LatencyEstimate.Inseparable(25.0), estimateLatency(changed))
        assertEquals(2, old.observations.size)
    }

    @Test fun `empty single and collinear price rows do not fabricate rates`() {
        val row = bill(100, 20, 30)
        for (state in listOf(PriceState(scope), prices(row), prices(row, bill(200, 40, 60), bill(300, 60, 90)))) {
            assertEquals(PriceEstimate.InsufficientData(PriceInsufficiency.RankDeficient), estimatePrices(state))
        }
        assertEquals(row.billedCost, prices(row).observations.single().billedCost)
    }

    @Test fun `three mixed independent bills determine rates and later bills validate them`() {
        val state = prices(bill(100, 20, 30), bill(20, 100, 40), bill(30, 40, 100), bill(45, 25, 70))
        val estimate = assertIs<PriceEstimate.Determined>(estimatePrices(state))
        assertEquals(0.002, estimate.rates.uncachedInputPerToken, 1e-12)
        assertEquals(0.0005, estimate.rates.cacheReadPerToken, 1e-12)
        assertEquals(0.006, estimate.rates.outputPerToken, 1e-12)
        assertNull(estimate.rates.cacheWritePerToken)
        assertNull(estimate.rates.perFeeUnit)
        assertEquals(PriceProvenance.Inferred, estimate.provenance)
        assertEquals(listOf(PriceProvenance.Billed, PriceProvenance.Documented, PriceProvenance.Inferred),
            PriceProvenance.entries.toList())
    }

    @Test fun `dependent prefix cannot hide an inconsistent bill or prevent later full rank`() {
        val rows = arrayOf(bill(100, 0, 0), bill(200, 0, 0), bill(0, 100, 0), bill(0, 0, 100))
        assertIs<PriceEstimate.Determined>(estimatePrices(prices(*rows)))
        val bad = prices(rows[0], rows[1].copy(billedCost = 0.45), rows[2], rows[3])
        assertIs<PriceEstimate.InsufficientData>(estimatePrices(bad))
    }

    @Test fun `additional noisy bill revokes the algebraic estimate`() {
        val exact = prices(bill(100, 0, 0), bill(0, 100, 0), bill(0, 0, 100))
        assertIs<PriceEstimate.Determined>(estimatePrices(exact))
        val bad = updatePrices(exact, bill(100, 100, 100, cost = 0.9))
        assertEquals(PriceEstimate.InsufficientData(PriceInsufficiency.InconsistentBills), estimatePrices(bad))
        val later = updatePrices(bad, bill(200, 200, 200))
        assertIs<PriceEstimate.InsufficientData>(estimatePrices(later))
    }

    @Test fun `residual threshold accepts rounding and rejects observable noise`() {
        val state = prices(bill(100, 0, 0), bill(0, 100, 0), bill(0, 0, 100))
            .copy(tolerance = PriceTolerance(absoluteCost = 1e-6, relativeCost = 0.0))
        assertIs<PriceEstimate.Determined>(estimatePrices(updatePrices(state, bill(100, 100, 100, cost = 0.8500005))))
        assertIs<PriceEstimate.InsufficientData>(estimatePrices(updatePrices(state, bill(100, 100, 100, cost = 0.85001))))
    }

    @Test fun `near collinear prices are insufficient at declared rank threshold`() {
        val state = prices(bill(1_000_000_000_000, 1_000_000_000_000, 1_000_000_000_000),
            bill(1_000_000_000_001, 1_000_000_000_000, 1_000_000_000_000),
            bill(1_000_000_000_000, 1_000_000_000_001, 1_000_000_000_000))
        assertEquals(PriceEstimate.InsufficientData(PriceInsufficiency.RankDeficient), estimatePrices(state))
    }

    @Test fun `column scaling supports unequal quantities and negative recovered prices are rejected`() {
        val unequal = prices(bill(1_000_000_000, 0, 0), bill(0, 1, 0), bill(0, 0, 100))
        val estimate = assertIs<PriceEstimate.Determined>(estimatePrices(unequal))
        assertEquals(0.0005, estimate.rates.cacheReadPerToken, 1e-12)
        val negative = prices(bill(100, 100, 0, 0.1), bill(0, 100, 0, 0.2), bill(0, 0, 100))
        assertEquals(PriceEstimate.InsufficientData(PriceInsufficiency.NonPhysicalRates), estimatePrices(negative))
    }

    @Test fun `cache write and fee columns require independent evidence of their own`() {
        val rows = listOf(bill(100, 0, 0), bill(0, 100, 0), bill(0, 0, 100),
            bill(0, 0, 0, cost = 0.3, write = 100), bill(0, 0, 0, cost = 0.07, fees = 1.0),
            bill(100, 100, 100, cost = 1.22, write = 100, fees = 1.0))
        val initial = PriceState(scope, includeCacheWrite = true, includeFees = true)
        assertIs<PriceEstimate.InsufficientData>(estimatePrices(rows.take(3).fold(initial, ::updatePrices)))
        val estimate = assertIs<PriceEstimate.Determined>(estimatePrices(rows.fold(initial, ::updatePrices)))
        assertEquals(0.003, estimate.rates.cacheWritePerToken!!, 1e-12)
        assertEquals(0.07, estimate.rates.perFeeUnit!!, 1e-12)
        val feesOnly = PriceState(scope, includeFees = true)
        val withFees = (rows.take(3) + rows[4]).fold(feesOnly, ::updatePrices)
        assertEquals(0.07, assertIs<PriceEstimate.Determined>(estimatePrices(withFees)).rates.perFeeUnit!!, 1e-12)
    }

    @Test fun `price scope and disabled columns cannot be mixed`() {
        val state = PriceState(scope)
        assertFailsWith<IllegalArgumentException> { updatePrices(state, bill(1, 1, 1, route = scope.copy(routeId = "other"))) }
        assertFailsWith<IllegalArgumentException> { updatePrices(state, bill(1, 1, 1, route = scope.copy(tier = Tier.High))) }
        assertFailsWith<IllegalArgumentException> { updatePrices(state, bill(1, 1, 1, write = 1)) }
        assertFailsWith<IllegalArgumentException> { updatePrices(state, bill(1, 1, 1, fees = 1.0)) }
    }

    @Test fun `invalid physical observations and priors fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { cache("a", cached = 101) }
        assertFailsWith<IllegalArgumentException> { cache("a", input = 99) }
        assertFailsWith<IllegalArgumentException> { cache("a", cached = -1) }
        assertFailsWith<IllegalArgumentException> { BetaParameters(0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { BetaParameters(Double.NaN, 1.0) }
        assertFailsWith<IllegalArgumentException> { LatencyObservation("s", 1, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { LatencyObservation("s", -1, 1.0) }
        assertFailsWith<IllegalArgumentException> { bill(1, 1, 1, cost = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { PriceTolerance(rank = 0.0) }
        val full = CacheShareState(totalInputTokens = Long.MAX_VALUE)
        assertFailsWith<ArithmeticException> { updateCacheShare(full, cache("a")) }
    }

    @Test fun `ordered replay produces identical states and estimates at every prefix`() {
        val cacheRows = listOf(cache("a"), cache("a"), cache("b", rewrite = true),
            cache("a", key = series.copy(sessionId = "new")))
        val latencyRows = (1..6).map { LatencyObservation("s", it * 100L, 0.5 + it) } +
            LatencyObservation("new", 20, 0.7)
        val priceRows = listOf(bill(100, 20, 30), bill(20, 100, 40), bill(30, 40, 100), bill(45, 25, 70))
        val cacheReplay = cacheRows.runningFold(CacheShareState(), ::updateCacheShare)
        val latencyReplay = latencyRows.runningFold(LatencyState(), ::updateLatency)
        val priceReplay = priceRows.runningFold(PriceState(scope), ::updatePrices)
        val cacheRepeated = cacheRows.runningFold(CacheShareState(), ::updateCacheShare)
        val latencyRepeated = latencyRows.runningFold(LatencyState(), ::updateLatency)
        val priceRepeated = priceRows.runningFold(PriceState(scope), ::updatePrices)
        assertEquals(cacheReplay, cacheRepeated)
        assertEquals(latencyReplay, latencyRepeated)
        assertEquals(priceReplay, priceRepeated)
        assertEquals(cacheReplay.map(::estimateCacheShare), cacheRepeated.map(::estimateCacheShare))
        assertEquals(latencyReplay.map(::estimateLatency), latencyRepeated.map(::estimateLatency))
        assertEquals(priceReplay.map(::estimatePrices), priceRepeated.map(::estimatePrices))
        assertEquals(CacheShareState(), cacheReplay.first())
    }
}
