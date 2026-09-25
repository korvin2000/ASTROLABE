package io.astrolabe.route

import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.provider.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CacheScheduleTest {
    private val implMain = CacheKey("implementing", "main")
    private val implHelper = CacheKey("implementing", "helper")
    private val review = CacheKey("review", "main")

    @Test fun `the ordering hint puts cells sharing a role and profile next to each other only where latency allows`() {
        val ready = listOf(CellSlot("a", implMain), CellSlot("b", review), CellSlot("c", implMain), CellSlot("d", review))
        // After an implementing/main cell: its twin c moves ahead of b; then the two review cells run adjacently.
        assertEquals(listOf("a", "c", "b", "d"), CacheSchedule.order(ready, implMain).map { it.id })
        // After a review cell, b runs first, then d, then the implementing pair.
        assertEquals(listOf("b", "d", "a", "c"), CacheSchedule.order(ready, review).map { it.id })
        // Latency: a slot with no tolerated delay is never deferred, whatever the cache says.
        val urgent = listOf(CellSlot("a", review, maxDelay = 0), CellSlot("b", implMain), CellSlot("c", review))
        assertEquals(listOf("a", "c", "b"), CacheSchedule.order(urgent, implMain).map { it.id })
        // A slot is never deferred past its max delay: e waits at most one position for the helper group.
        val bounded = listOf(CellSlot("e", review, maxDelay = 1), CellSlot("f", implHelper), CellSlot("g", implHelper), CellSlot("h", implHelper))
        assertEquals(listOf("f", "e", "g", "h"), CacheSchedule.order(bounded, implHelper).map { it.id })
        // Keys that agree leave the ready order alone.
        assertEquals(listOf("a", "b", "c"), CacheSchedule.order(ready.take(3).map { it.copy(key = implMain) }, review).map { it.id })
    }

    @Test fun `FX-45 retained unrelated context that flatters the cache is judged by accepted-task economics`() {
        val lean = ScheduleEconomics(acceptedTasks = 4, money = Money("USD", BigDecimal("2.00")), cachedInputTokens = 40_000, inputTokens = 100_000)
        // Keeping old unrelated context raises the hit rate and the bill; the same four tasks are accepted.
        val bloated = ScheduleEconomics(acceptedTasks = 4, money = Money("USD", BigDecimal("2.60")), cachedInputTokens = 180_000, inputTokens = 200_000)
        assertTrue(bloated.cacheHitRate!! > lean.cacheHitRate!!)
        assertEquals(ScheduleVerdict.Worse, CacheSchedule.judge(bloated, lean))
        assertEquals(ScheduleVerdict.Better, CacheSchedule.judge(lean, bloated))
        // A better hit rate at the same money per accepted task is no improvement; nothing accepted is unmeasured.
        assertEquals(ScheduleVerdict.Same, CacheSchedule.judge(lean.copy(cachedInputTokens = 90_000), lean))
        assertEquals(ScheduleVerdict.Unmeasured, CacheSchedule.judge(lean.copy(acceptedTasks = 0), lean))
    }

    @Test fun `shadow routing compares profiles offline on a frozen sample and never touches the live router log`() {
        val table = TierTable("t1", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"), Tier.ExtraHigh to setOf("escalation")))
        val policy = RoutingPolicy(table, FakeProfiles.all.filterKeys { it in setOf("helper", "main", "escalation") })
        val sample = ShadowSample("sample-1", listOf(ShadowCase(RoutingFunction.Implementing, RoutingPacket(null, 10_000, 4_000))))
        val live = Router()
        val trials = ArrayList<String>()
        val rows = ShadowRouting.compare(sample, policy, listOf("main", "escalation", "helper"), ShadowTrial { _, _, profile -> trials += profile.id; RoutingOutcome.Accepted })
        assertEquals(listOf("main", "escalation"), trials, "one offline trial per eligible profile")
        assertEquals(RoutingOutcome.Refused, rows.single { it.profile == "helper" }.outcome, "the helper does not serve the implementing floor")
        assertTrue(live.calibration.entries().isEmpty())
    }
}
