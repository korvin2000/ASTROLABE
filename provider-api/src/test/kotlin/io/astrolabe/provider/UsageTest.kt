package io.astrolabe.provider

import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageTest {
    @Test
    fun `each dimension is priced once and mixed cache-write classes keep their own rate`() {
        val usage = BillableUsage(
            mapOf(
                BillingDimension.UNCACHED_INPUT to 1_000_000L,
                BillingDimension.CACHE_READ to 1_000_000L,
                BillingDimension.CACHE_WRITE_5M to 1_000_000L,
                BillingDimension.CACHE_WRITE_1H to 1_000_000L,
                BillingDimension.OUTPUT to 1_000_000L,
            ),
            Fixtures.provenance,
        )
        val money = usage.price(Fixtures.prices)
        assertFalse(money.unknown)
        assertEquals(0, BigDecimal("28.05").compareTo(money.amount), money.amount.toPlainString())
        assertEquals(4_000_000L, usage.totalInput)
        assertEquals(2_000_000L, usage.totalCacheWrite)
    }

    @Test
    fun `an unpriced or unknown dimension yields unknown money never zero`() {
        val hosted = BillingDimension("hosted_tool_web_search")
        val unpriced = BillableUsage(mapOf(BillingDimension.OUTPUT to 2_000_000L, hosted to 3L), Fixtures.provenance)
        val money = unpriced.price(Fixtures.prices)
        assertTrue(money.unknown)
        assertEquals(0, BigDecimal("30").compareTo(money.amount))

        val partial = BillableUsage(mapOf(BillingDimension.OUTPUT to 1L), Fixtures.provenance, unknown = setOf(BillingDimension.CACHE_READ))
        assertTrue(partial.price(Fixtures.prices).unknown)
        assertFalse(partial.isComplete)

        val missing = BillableUsage.missing(Fixtures.provenance, Fixtures.dims)
        assertTrue(missing.price(Fixtures.prices).unknown)
        assertEquals(0L, missing.totalInput)
        assertEquals(Fixtures.dims, missing.unknown)
    }

    @Test
    fun `usage validation and money arithmetic`() {
        assertFailsWith<IllegalArgumentException> { BillableUsage(mapOf(BillingDimension.OUTPUT to -1L), Fixtures.provenance) }
        assertFailsWith<IllegalArgumentException> {
            BillableUsage(mapOf(BillingDimension.OUTPUT to 1L), Fixtures.provenance, unknown = setOf(BillingDimension.OUTPUT))
        }
        assertFailsWith<IllegalArgumentException> { BillingDimension("Cache-Write") }
        assertFailsWith<IllegalArgumentException> { Money.zero("USD") + Money.zero("EUR") }
        val sum = Money("USD", BigDecimal("1.5")) + Money.unknown("USD")
        assertTrue(sum.unknown)
        assertEquals(0, BigDecimal("1.5").compareTo(sum.amount))
    }

    @Test
    fun `usage and price table serialize with decimal strings and round trip`() {
        val usage = BillableUsage(mapOf(BillingDimension.OUTPUT to 5L), Fixtures.provenance, reasoningIncludedInOutput = true)
        val text = Json.encodeToString(BillableUsage.serializer(), usage)
        assertTrue(text.contains("\"quantities\":{\"output\":5}"), text)
        assertEquals(usage, Json.decodeFromString(BillableUsage.serializer(), text))

        val prices = Json.encodeToString(PriceTable.serializer(), Fixtures.prices)
        assertTrue(prices.contains("\"cache_read\":\"0.30\""), prices)
        assertEquals(Fixtures.prices, Json.decodeFromString(PriceTable.serializer(), prices))
    }
}
