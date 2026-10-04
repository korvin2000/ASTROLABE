package io.astrolabe.evallive

import io.astrolabe.event.AgentEvent
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CallFacts
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.UsageProvenance
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unknown is `null`, never 0 — in the totals and in the summary row. */
class TotalsTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val provenance = UsageProvenance("fake", "fake-main", "fake-chat")
    private val prices = FakeProfiles.main.priceTable

    private fun responded(id: String, usage: BillableUsage?) = AgentEvent.Cell.ModelResponded(ids, id, StopReason.EndTurn, usage)

    @Test
    fun `a dimension one response did not report is null and so is the exact cost`() {
        val full = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000L, BillingDimension.CACHE_READ to 500L, BillingDimension.OUTPUT to 100L), provenance)
        val partial = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000L, BillingDimension.OUTPUT to 100L), provenance, unknown = setOf(BillingDimension.CACHE_READ))
        val events = listOf(
            AgentEvent.Cell.ModelRequested(ids, "i-1", 2_000, "main"), responded("i-1", full),
            AgentEvent.Cell.ModelRequested(ids, "i-2", 2_000, "main"), responded("i-2", partial),
        )

        val totals = Totals.of(events, prices)

        assertEquals(2, totals.modelRequests)
        assertEquals(2_000L, totals.uncachedInputTokens)
        assertEquals(200L, totals.outputTokens)
        assertNull(totals.cacheReadTokens, "one response left cache reads unknown")
        assertNull(totals.cacheWriteTokens, "no response reported cache writes")
        assertNull(totals.cost, "an unknown dimension makes the exact cost unknown")
        // 2 000 × 3 + 500 × 0.30 + 200 × 15 per million.
        assertEquals("0.00915", totals.costPricedPart)
        assertEquals(listOf("fake-main"), totals.providerModels)
    }

    @Test
    fun `failed and cancelled calls after an answered one count in the totals and keep unknown usage unknown`() {
        val usage = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000L, BillingDimension.OUTPUT to 100L), provenance)
        val events = listOf(
            AgentEvent.Cell.ModelRequested(ids, "i-1", 2_000, "main"), responded("i-1", usage),
            AgentEvent.Cell.ModelRequested(ids, "i-2", 2_000, "main"),
            AgentEvent.Cell.ModelResponded(ids, "i-2", StopReason.Cancelled, usage),
            AgentEvent.Cell.ModelRequested(ids, "i-3", 2_000, "main"),
            AgentEvent.Cell.ModelResponded(ids, "i-3", StopReason.Truncated, null, failure = "Transport"),
        )

        val totals = Totals.of(events, prices)

        assertEquals(3, totals.modelRequests)
        assertEquals(3, totals.modelResponses)
        assertEquals(1, totals.modelFailures)
        assertEquals(mapOf("EndTurn" to 1, "Cancelled" to 1, "Truncated" to 1), totals.stops)
        assertNull(totals.uncachedInputTokens, "the failed call's usage is unknown, so the total is, never a partial 2 000")
        assertNull(totals.cost)
        // 2 000 × 3 + 200 × 15 per million: the cancelled call's known usage is priced.
        assertEquals("0.0090", totals.costPricedPart)
    }

    @Test
    fun `each call is priced by the profile its request named, two profiles in one run`() {
        val usage = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000_000L, BillingDimension.OUTPUT to 100_000L), provenance)
        val tables = mapOf("main" to FakeProfiles.main.priceTable, "helper" to FakeProfiles.helper.priceTable)
        val events = listOf(
            AgentEvent.Cell.ModelRequested(ids, "i-1", 2_000, "main"), responded("i-1", usage),
            AgentEvent.Cell.ModelRequested(ids, "i-2", 2_000, "helper"), responded("i-2", usage),
        )

        val totals = Totals.of(events, tables, "USD")

        // main: 1 M × 3 + 0.1 M × 15 = 4.5; helper: 1 M × 0.8 + 0.1 M × 4 = 1.2 — never 9.0 at the run's main table.
        assertEquals(0, BigDecimal("5.7").compareTo(BigDecimal(totals.cost)), "${totals.cost}")
        assertEquals("estimated", totals.costBasis)
        assertEquals(mapOf("helper" to 1, "main" to 1), totals.profiles)
        assertEquals(0, BigDecimal("9.0").compareTo(BigDecimal(Totals.of(events, prices).cost)), "one table for the run prices both calls alike")

        // A call of a profile without a table is unknown, never priced at another profile's prices.
        val unknown = Totals.of(events + listOf(AgentEvent.Cell.ModelRequested(ids, "i-3", 2_000, "escalation"), responded("i-3", usage)), tables, "USD")
        assertNull(unknown.cost)
        assertEquals(0, BigDecimal("5.7").compareTo(BigDecimal(unknown.costPricedPart)))
        assertEquals("estimated", unknown.costBasis, "an unknown amount is no other basis")
        assertEquals(1, unknown.profiles["escalation"])
    }

    @Test
    fun `facts aggregate with known counts and price tiers are buckets never sums`() {
        fun facts(id: String, facts: CallFacts?) = AgentEvent.Cell.ModelResponded(ids, id, StopReason.EndTurn, null, facts = facts)
        val events = listOf(
            facts("i-1", CallFacts(latencyMillis = 1_200, priceTierInputTokensAbove = 200_000)),
            facts("i-2", CallFacts(priceTierInputTokensAbove = 200_000)),
            facts("i-3", CallFacts(latencyMillis = 800)),
            facts("i-4", null),
        )

        val totals = Totals.of(events, prices)

        assertEquals(Quantity("2000", known = 2, calls = 4), totals.responded["facts.latencyMillis"], "a partial sum says how many calls it covers")
        assertNull(totals.responded["facts.priceTierInputTokensAbove"], "a tier threshold is no quantity")
        assertEquals(mapOf("200000" to 2, "none" to 2), totals.priceTiers)
    }

    @Test
    fun `a run without responses has no quantities and an empty summary cell for each`() {
        val totals = Totals.of(listOf(responded("i-1", null)), prices)

        assertEquals(1, totals.modelResponses)
        assertNull(totals.uncachedInputTokens)
        assertNull(totals.outputTokens)
        assertNull(totals.cost)
        assertNull(totals.spanCost)

        val result = RunResult(
            task = "t", taskClass = "bugfix", provider = "fake", model = "m", repeat = 1, order = 1, seed = 0, harnessVersion = "0",
            effort = "Medium", maxCells = 1, profileId = null, contextLimitTokens = null, outputHeadroomTokens = null, workId = null,
            attemptFingerprint = null, shape = null, verification = null, outcome = null, stopCode = null, reason = null, failure = "boom",
            cells = null, policyDecisions = emptyList(), acceptance = null, acceptanceDigest = "d", startedAt = "s", endedAt = "e",
            attemptWallMillis = null, totals = totals, eventsDropped = null, changedFiles = null,
        )
        val row = Summary.COLUMNS.zip(Summary.row(result)).toMap()
        assertNull(row["uncached_input"])
        assertNull(row["cost"])
        assertNull(row["accepted"])
        assertEquals("1", row["model_requests"], "a call is counted by its response, its usage unknown or not")
    }
}
