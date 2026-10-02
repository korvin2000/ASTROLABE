package io.astrolabe.evallive

import io.astrolabe.event.AgentEvent
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.UsageProvenance
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
        assertEquals("0", row["model_requests"], "a count of events is known even when it is zero")
    }
}
