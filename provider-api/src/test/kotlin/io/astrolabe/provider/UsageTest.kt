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
    fun `a call is priced at the highest tier its total input exceeds and a tier keeps the base prices it does not state`() {
        val tiered = Fixtures.prices.copy(
            tiers = listOf(
                PriceTier(1_000_000, mapOf(BillingDimension.UNCACHED_INPUT to BigDecimal("12"))),
                PriceTier(200_000, mapOf(BillingDimension.UNCACHED_INPUT to BigDecimal("6"), BillingDimension.OUTPUT to BigDecimal("22.5"))),
            ),
        )
        fun price(uncached: Long, read: Long, output: Long) = BillableUsage(
            mapOf(BillingDimension.UNCACHED_INPUT to uncached, BillingDimension.CACHE_READ to read, BillingDimension.OUTPUT to output), Fixtures.provenance,
        ).price(tiered)
        fun assertMoney(expected: String, money: Money) {
            assertFalse(money.unknown)
            assertEquals(0, BigDecimal(expected).compareTo(money.amount), money.amount.toPlainString())
        }
        assertMoney("0.48", price(150_000, 50_000, 1_000)) // 200 000 input does not exceed the threshold: base prices
        assertMoney("0.9375003", price(150_000, 50_001, 1_000)) // cache reads count toward the total input
        assertMoney("12.015012", price(1_000_001, 0, 1_000)) // the higher tier states no output price: the base one, not the lower tier's
        assertEquals(null, tiered.tier(200_000))
        assertEquals(200_000L, tiered.tier(200_001)?.inputTokensAbove)
        assertEquals(1_000_000L, tiered.tier(5_000_000)?.inputTokensAbove)
        assertEquals(Fixtures.prices.perMillion, tiered.at(10).perMillion)
        assertTrue(tiered.at(300_000).tiers.isEmpty())
        assertFailsWith<IllegalArgumentException> { Fixtures.prices.copy(tiers = listOf(PriceTier(5, emptyMap()), PriceTier(5, emptyMap()))) }
        assertFailsWith<IllegalArgumentException> { PriceTier(-1, emptyMap()) }
    }

    @Test
    fun `a table without tiers serializes as before, also with defaults encoded, and a tiered one round trips`() {
        val stable = Json { encodeDefaults = true }
        val plain = stable.encodeToString(PriceTable.serializer(), Fixtures.prices)
        assertFalse("tiers" in plain, plain)
        val tiered = Fixtures.prices.copy(tiers = listOf(PriceTier(200_000, mapOf(BillingDimension.OUTPUT to BigDecimal("22.5")))))
        val text = stable.encodeToString(PriceTable.serializer(), tiered)
        assertTrue(text.endsWith(""","tiers":[{"inputTokensAbove":200000,"perMillion":{"output":"22.5"}}]}"""), text)
        assertEquals(tiered, stable.decodeFromString(PriceTable.serializer(), text))
        assertEquals(Fixtures.prices, stable.decodeFromString(PriceTable.serializer(), plain))
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
