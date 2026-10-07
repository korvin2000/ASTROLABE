package io.astrolabe.route

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CallFacts
import io.astrolabe.provider.Money
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.store.TEST_CLOCK
import io.astrolabe.store.openStore
import io.astrolabe.telemetry.Accounting
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** E1 (plan §4.6, §10.4): the binding key, `binding_physics` updated from model responses, the per-attempt snapshot. */
class BindingPhysicsTest {
    @TempDir
    lateinit var root: Path

    private val profile = FakeProfiles.main.copy(config = JsonObject(mapOf("gate" to JsonObject(mapOf("api" to JsonPrimitive("openai-responses"))))))
    private val work = WorkId("W-1")
    private fun ids(cell: String, attempt: String = "a1") = Identities(work, AttemptId(attempt), context = ContextId(cell))

    private fun usage(uncached: Long, cached: Long, output: Long, billed: String? = null, reasoning: Long? = null) = BillableUsage(
        mapOf(BillingDimension.UNCACHED_INPUT to uncached, BillingDimension.CACHE_READ to cached, BillingDimension.OUTPUT to output),
        UsageProvenance("fake", "fake-main", "openai-responses"), billed = billed?.let { Money("USD", BigDecimal(it)) }, reasoningTokens = reasoning,
    )

    private fun responded(cell: String, invocation: String, usage: BillableUsage?, upstream: String? = "relace", millis: Long? = 2_000, failure: String? = null) =
        AgentEvent.Cell.ModelResponded(ids(cell), invocation, StopReason.EndTurn, usage, facts = CallFacts(latencyMillis = millis, upstream = upstream), failure = failure)

    /** Two cells on one upstream, a call routed elsewhere, one with no upstream, a failure; bills price u, c, o at 3, 0.3, 15 per million. */
    private val calls: List<BindingObservation> = listOf(
        responded("cell-1", "i1", usage(1_000, 0, 100, "0.0045", reasoning = 40)),
        responded("cell-1", "i2", usage(200, 1_000, 200, "0.0039", reasoning = 60), millis = 3_000),
        responded("cell-2", "i3", usage(600, 1_000, 400, "0.0081"), millis = 5_000),
        responded("cell-1", "i4", usage(300, 1_400, 300, "0.0060"), upstream = "fireworks"),
        responded("cell-1", "i5", usage(500, 1_700, 100), upstream = null),
        responded("cell-2", "i6", null, failure = "Overloaded"),
        responded("cell-1", "i7", usage(1_400, 600, 500, "0.01188"), millis = 6_000),
    ).map { BindingObservation.of(profile, it) }

    @Test
    fun `the binding key keeps an unknown upstream in its own bucket and reads back from its canonical form`() {
        val known = BindingKey.of(profile, "relace")
        val unknown = BindingKey.of(profile)
        assertEquals("fake-main|fake|relace|openai-responses", known.canonical)
        assertEquals("fake-main|fake|?|openai-responses", unknown.canonical)
        assertNull(BindingKey.of(profile, " ").upstream, "a blank upstream is unknown")
        val literal = BindingKey("m|1", "g%", "?", null)
        assertNotEquals(literal.copy(upstream = null).canonical, literal.canonical, "a provider spelled `?` is not the unknown bucket")
        listOf(known, unknown, literal).forEach { assertEquals(it, BindingKey.parse(it.canonical)) }
        assertTrue(known.sameRoute(unknown) && known != unknown)
        assertNull(BindingKey.wireApi(FakeProfiles.main), "a profile without a gate block declares no wire API")
        assertFailsWith<IllegalArgumentException> { BindingKey.parse("a|b|c") }
    }

    @Test
    fun `rows update from model responses deterministically in seq order`() {
        val first = openStore(root.resolve("a")).use { store ->
            val physics = BindingPhysics(store, TEST_CLOCK)
            calls.forEach(physics::observe)
            physics.rows()
        }
        val replay = openStore(root.resolve("b")).use { store ->
            val physics = BindingPhysics(store, TEST_CLOCK)
            calls.forEach(physics::observe)
            physics.rows()
        }
        assertEquals(first, replay, "the same ordered observations rebuild the same rows")
        // The pure fold in seq order is what the writer stored.
        val folded = calls.withIndex().fold(emptyMap<BindingKey, BindingPhysicsRow>()) { rows, (i, o) ->
            rows + (o.key to (rows[o.key] ?: BindingPhysicsRow(o.key)).next(o, i + 1L))
        }
        assertEquals(folded.values.sortedBy { it.key.canonical }, first)
        assertEquals(listOf("fake-main|fake|?|openai-responses", "fake-main|fake|fireworks|openai-responses", "fake-main|fake|relace|openai-responses"),
            first.map { it.key.canonical }, "an unknown upstream is its own row")

        val relace = first.single { it.key.upstream == "relace" }
        assertEquals(7L, relace.updatedSeq)
        assertEquals(5L, relace.calls)
        assertEquals(mapOf("Overloaded" to 1L), relace.errors)
        assertEquals(50.0, relace.reasoning.meanTokens)
        // Evidence: i2 (prefix 1000 of cell-1's previous request, all cached) and i7 (prefix 1200 of i2 on this
        // upstream, 600 cached); i1 and i3 open their transcripts and are no evidence.
        assertEquals(2_200L, relace.cache.cacheableTokens)
        assertEquals(1_600L, relace.cache.cachedTokens)
        assertEquals(listOf("i2", "i7"), relace.cache.blockIds)
        assertEquals(2_600L, relace.cache.totalCachedTokens)
        assertEquals(1_600.0 / 2_200.0, relace.cacheShare().qHat)
        assertEquals(2, relace.cacheShare().effectiveCalls)
        // Latency: the failed call is not a sample; four answered calls of one provider session.
        assertEquals(listOf(100L, 200L, 400L, 500L), relace.latency.samples.map { it.outputTokens })
        val latency = assertIs<LatencyEstimate.Separated>(relace.latencyEstimate())
        assertEquals(1.0, latency.startupSeconds, 1e-9)
        assertEquals(0.01, latency.secondsPerOutputToken, 1e-9)
        // Prices: bills, documented and inferred kept apart; four consistent bills determine the three rates.
        assertEquals(BilledTotals("0.02838", 4), relace.prices.billed["USD"])
        assertEquals("15", relace.prices.documented.getValue("base").perMillion["output"])
        val inferred = relace.prices.inferred.getValue("base/USD")
        assertEquals(4, inferred.bills)
        assertNull(inferred.insufficiency)
        assertEquals(3e-6, inferred.uncachedInputPerToken!!, 1e-12)
        assertEquals(3e-7, inferred.cacheReadPerToken!!, 1e-12)
        assertEquals(1.5e-5, inferred.outputPerToken!!, 1e-12)
        val single = first.single { it.key.upstream == "fireworks" }.prices.inferred.getValue("base/USD")
        assertEquals(PriceInsufficiency.RankDeficient, single.insufficiency, "one bill determines only its own sum")
    }

    @Test
    fun `a snapshot is frozen once per attempt and does not follow the live table`() {
        openStore(root).use { store ->
            val physics = BindingPhysics(store, TEST_CLOCK)
            calls.take(3).forEach(physics::observe)
            val frozen = physics.freeze(ids("cell-1"), listOf(profile, FakeProfiles.helper))
            assertEquals(3L, frozen.asOfSeq)
            assertEquals(listOf(BindingKey.of(FakeProfiles.helper), BindingKey.of(profile)).sortedBy { it.canonical }, frozen.routes)
            assertEquals(physics.rows(), frozen.rows(profile))

            calls.drop(3).forEach(physics::observe)
            assertNotEquals(physics.row(BindingKey.of(profile, "relace")), frozen.rows(profile).single())
            assertEquals(frozen, physics.freeze(ids("cell-9"), listOf(FakeProfiles.escalation)), "a reopen reads the same snapshot")
            assertEquals(frozen, physics.frozen(work, AttemptId("a1")))
            val next = physics.freeze(ids("cell-1", attempt = "a2"), listOf(profile))
            assertEquals(7L, next.asOfSeq)
            assertEquals(3, next.rows(profile).size)
            assertNull(physics.frozen(work, AttemptId("a3")))
        }
    }

    @Test
    fun `the event bus updates the row of the call's profile once per call`() {
        openStore(root).use { store ->
            val accounting = Accounting(store, TEST_CLOCK)
            val physics = BindingPhysics(store, TEST_CLOCK)
            Events(TEST_CLOCK).use { events ->
                accounting.record(ids("cell-1"), "i1", profile, null, usage(1_000, 0, 100))
                events.emit(responded("cell-1", "i1", usage(1_000, 0, 100)))
                // i1 was on the bus before the subscription: its replay is not observed.
                physics.subscribe(events, mapOf(profile.id to profile)).use { subscription ->
                    accounting.record(ids("cell-1"), "i2", profile, null, usage(200, 1_000, 200))
                    events.emit(responded("cell-1", "i2", usage(200, 1_000, 200)))
                    // Never sent: no usage row, so no binding to observe.
                    events.emit(responded("cell-1", "i3", usage(10, 0, 10)))
                    accounting.record(ids("cell-1"), "i4", profile, null, usage(300, 1_200, 50))
                    events.emit(responded("cell-1", "i4", usage(300, 1_200, 50)))
                    val key = BindingKey.of(profile, "relace")
                    var row = physics.row(key)
                    var polls = 0
                    while ((row?.calls ?: 0L) < 2L && polls++ < 500) { Thread.sleep(10); row = physics.row(key) }
                    assertEquals(2L, row?.calls)
                    assertEquals(listOf("i4"), row?.cache?.blockIds, "i2 opens the subscription's view of cell-1; i4 extends it")
                    assertEquals(0L, subscription.dropped)
                }
            }
        }
    }
}
