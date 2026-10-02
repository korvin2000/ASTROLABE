package io.astrolabe.eval.audit

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditMathTest {
    private val route = Binding("openrouter", "m/one", null)
    private val prices = mapOf(PriceClass.UncachedInput to "0.13959", PriceClass.CacheRead to "0.013959", PriceClass.Output to "0.55836")

    private fun row(uncached: Long, cached: Long, output: Long, extra: String = "0"): FitRow {
        val tokens = mapOf(PriceClass.UncachedInput to uncached, PriceClass.CacheRead to cached, PriceClass.Output to output)
        val billed = tokens.entries.fold(BigDecimal(extra)) { s, (c, n) -> s + BigDecimal(prices.getValue(c)).multiply(BigDecimal(n)).movePointLeft(6) }
        return FitRow(tokens, billed)
    }

    private fun assertPrice(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(assertNotNull(actual)), "$expected vs $actual")

    @Test fun `three billed calls determine the prices and every further call checks them`() {
        val determined = AuditMath.fitPrices(route, listOf(row(6131, 0, 749), row(13302, 4096, 1161), row(4456, 16384, 3383)))
        assertEquals(Agreement.Determined, determined.agreement)
        val exact = AuditMath.fitPrices(route, listOf(row(6131, 0, 749), row(13302, 4096, 1161), row(4456, 16384, 3383), row(1287, 22784, 219)))
        assertEquals(Agreement.Exact, exact.agreement)
        for ((c, p) in prices) assertPrice(p, exact.perMillion[c])
        assertNull(exact.perMillion[PriceClass.CacheWrite])
        assertEquals(0, BigDecimal.ZERO.compareTo(exact.maxResidual))
    }

    @Test fun `a charge off the prices makes the fit approximate and a dependent call set identifies nothing`() {
        val approximate = AuditMath.fitPrices(route, listOf(row(6131, 0, 749), row(13302, 4096, 1161), row(4456, 16384, 3383), row(1287, 22784, 219, "0.0001")))
        assertEquals(Agreement.Approximate, approximate.agreement)
        assertTrue(approximate.maxRelativeResidual!! > 1e-6)
        val collinear = AuditMath.fitPrices(route, listOf(row(100, 10, 1), row(200, 20, 2), row(300, 30, 3), row(400, 40, 4)))
        assertEquals(Agreement.Unidentified, collinear.agreement)
        assertTrue(collinear.perMillion.values.all { it == null })
        assertEquals(Agreement.Unidentified, AuditMath.fitPrices(route, listOf(row(1, 2, 3))).agreement)
    }

    @Test fun `a determinant stays exact on integers`() {
        fun m(vararg rows: List<Int>) = rows.map { r -> r.map { BigInteger.valueOf(it.toLong()) } }
        assertEquals(BigInteger.valueOf(6), AuditMath.determinant(m(listOf(2, 0, 1), listOf(1, 3, 2), listOf(1, 1, 2))))
        assertEquals(BigInteger.valueOf(-1), AuditMath.determinant(m(listOf(0, 1), listOf(1, 0))))
        assertEquals(BigInteger.ZERO, AuditMath.determinant(m(listOf(1, 2), listOf(2, 4))))
    }

    @Test fun `a step breaks beyond block rounding and a full miss keeps no prefix`() {
        val clean = AuditMath.step(previousInput = 20_000, previousAnchor = 1_000, input = 22_000, anchor = 1_000, cached = 18_432)
        assertEquals(19_000L, clean.cacheable)
        assertEquals(568L, clean.shortfall)
        assertFalse(clean.broken)
        val evicted = AuditMath.step(45_675, 904, 32_319, 946, 5_760)
        assertEquals(31_373L, evicted.cacheable)
        assertEquals(25_613L, evicted.shortfall)
        assertTrue(evicted.broken)
        assertFalse(evicted.fullMiss)
        val full = AuditMath.step(56_594, 1_386, 51_558, 1_686, 0)
        assertTrue(full.broken && full.fullMiss)
        assertEquals(49_872L, full.shortfall)
    }

    @Test fun `q hat caps cached tokens at the cacheable prefix and the hit share is a token share`() {
        val steps = listOf(CacheStep(1_000, 1_100, 0, false, false), CacheStep(1_000, 500, 500, false, false))
        assertEquals(0.75, AuditMath.qHat(steps))
        assertNull(AuditMath.qHat(emptyList()))
        assertEquals(0.867, AuditMath.hitShare(867, 1_000))
        assertNull(AuditMath.hitShare(0, 0))
        val miss = AuditMath.missCost(1_000_000, prices.mapValues { BigDecimal(it.value) })
        assertPrice("0.125631", miss)
        assertNull(AuditMath.share(BigDecimal.ONE, BigDecimal.ZERO))
    }
}
