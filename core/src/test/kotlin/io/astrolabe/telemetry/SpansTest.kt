package io.astrolabe.telemetry

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.UsageProvenance
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.11.1: runtime spans with exclusive cost recorded once, derived inclusive totals, one event per span edge, metrics. */
class SpansTest {
    private val work = WorkId("W-1")
    private val ids = Identities(work, AttemptId("a1"))
    private val cellIds = ids.copy(context = ContextId("cell-1"))
    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    @Test
    fun `inclusive totals are derived from exclusive costs without double counting`() {
        val ticks = AtomicLong(0)
        val spans = Spans(FixedIdGen(), nanos = LongSupplier { ticks.addAndGet(10) })
        val campaign = spans.start(Phase.Plan, ids)
        val cell = spans.start(Phase.Edit, cellIds, campaign)
        val look = spans.start(Phase.Locate, cellIds, cell)
        spans.end(look, usd("0.05"))
        val check = spans.start(Phase.Verify, cellIds, cell)
        spans.end(check, usd("0.05"))
        spans.end(cell, usd("0.25"))
        spans.end(campaign, usd("0.10"))

        val analysis = spans.analyze(work)
        assertEquals(0, BigDecimal("0.45").compareTo(analysis.totalCost!!.amount), "each exclusive cost counted once")
        assertEquals(0, BigDecimal("0.45").compareTo(analysis.inclusiveCosts.getValue(campaign).amount))
        assertEquals(0, BigDecimal("0.35").compareTo(analysis.inclusiveCosts.getValue(cell).amount))
        assertEquals(0, BigDecimal("0.05").compareTo(analysis.inclusiveCosts.getValue(look).amount))
        assertEquals(10L, spans.all().first { it.id == look }.durationNanos)
    }

    @Test
    fun `an unknown child cost keeps the total unknown instead of zero`() {
        val spans = Spans(FixedIdGen())
        val campaign = spans.start(Phase.Plan, ids)
        spans.end(spans.start(Phase.Edit, cellIds, campaign))
        spans.end(campaign, usd("0.10"))
        assertTrue(spans.analyze(work).totalCost!!.unknown)
    }

    @Test
    fun `every span emits one start and one end event with its parent and phase`() {
        Events(FakeClock.at("2026-09-24T10:00:00Z")).use { events ->
            val recorder = EventRecorder().also { events.subscribe(it) }
            val spans = Spans(FixedIdGen(), events)
            val campaign = spans.start(Phase.Plan, ids)
            val cell = spans.start(Phase.Edit, cellIds, campaign)
            spans.end(cell, usd("0.25"))
            spans.end(campaign)
            assertTrue(recorder.awaitCount(4))
            val started = recorder.ofType<AgentEvent.Telemetry.SpanStarted>()
            val ended = recorder.ofType<AgentEvent.Telemetry.SpanEnded>()
            assertEquals(listOf(campaign, cell), started.map { it.span })
            assertEquals(campaign, started.last().parent)
            assertEquals(listOf("USD 0.25", null), ended.map { it.cost }, "an unknown cost is null on the event, never zero")
            assertEquals(Phase.Edit, ended.first().phase)
        }
    }

    @Test
    fun `cell and campaign metrics are derived from events, unknown usage stays unknown`() {
        val cell = ContextId("cell-1")
        val t0 = Instant.parse("2026-09-24T10:00:00Z")
        var seq = 0L
        fun record(event: AgentEvent, seconds: Long = 0) = EventRecord(++seq, t0.plusSeconds(seconds), event)
        val prov = UsageProvenance("fake", "fake-main", "fake/1")
        val known = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 100L, BillingDimension.OUTPUT to 20L), prov)
        val partial = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 50L), prov, unknown = setOf(BillingDimension.OUTPUT))
        val records = listOf(
            record(AgentEvent.Cell.Started(cellIds, "inc-1", "implementing")),
            record(AgentEvent.Cell.TurnStarted(cellIds, 1, 12)),
            record(AgentEvent.Cell.ModelRequested(cellIds, "inv-1", 500, "main")),
            record(AgentEvent.Cell.ModelResponded(cellIds, "inv-1", StopReason.ToolUse, known)),
            record(AgentEvent.Cell.ToolCalled(cellIds, 1, "look", "read", Phase.Locate), 1),
            record(AgentEvent.Cell.ToolResulted(cellIds, 1, "#1", "ok", Phase.Locate), 3),
            record(AgentEvent.Cell.TurnStarted(cellIds, 2, 12)),
            record(AgentEvent.Cell.ModelRequested(cellIds, "inv-2", 500, "main")),
            record(AgentEvent.Cell.ModelResponded(cellIds, "inv-2", StopReason.EndTurn, partial)),
            record(AgentEvent.Check.Finished(cellIds, "CHK-accept-AC-1", "#2", "passed")),
            record(AgentEvent.Cell.Ended(cellIds, "completed", null)),
            record(AgentEvent.Campaign.IncrementClosed(ids, "inc-1", "verified")),
        )
        val metrics = CellMetrics.of(cell, records, mapOf("CHK-accept-AC-1" to "acceptance"))
        assertEquals(150L, metrics.tokensByCacheClass[BillingDimension.UNCACHED_INPUT])
        assertNull(metrics.tokensByCacheClass[BillingDimension.OUTPUT], "one call did not report output: unknown, not 20")
        assertTrue(BillingDimension.OUTPUT in metrics.tokensByCacheClass)
        assertEquals(2, metrics.modelCalls)
        assertEquals(1, metrics.toolCalls)
        assertEquals(2.0, metrics.toolSeconds)
        assertEquals(mapOf("acceptance" to 1), metrics.checksByLayer)
        assertEquals(2, metrics.turns)
        assertEquals("completed", metrics.boundaryReason)
        assertNull(metrics.anchorTokens)

        val campaign = CampaignMetrics.of(work, records, usd("0.90"))
        assertEquals(1, campaign.verified)
        assertEquals(1.0, campaign.firstAttemptPassRate)
        assertEquals(0, BigDecimal("0.90").compareTo(campaign.costPerAcceptedTask!!.amount))
        assertEquals(mapOf("inc-1" to 0), campaign.continuationsPerIncrement)
        assertNull(CampaignMetrics.of(work, records.dropLast(1), usd("0.90")).costPerAcceptedTask, "undefined at zero accepted")
    }

    @Test
    fun `deployment outcomes are reported by the host against their work`() {
        val outcomes = InMemoryOutcomes()
        outcomes.report(work, DeploymentOutcome(work, DeploymentOutcomeKind.Reverted, "reverted after merge", Instant.parse("2026-09-25T10:00:00Z")))
        assertEquals(listOf(DeploymentOutcomeKind.Reverted), ProjectMetrics(deploymentOutcomes = outcomes.reported()).deploymentOutcomes.map { it.kind })
    }
}
