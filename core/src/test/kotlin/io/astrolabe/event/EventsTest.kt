package io.astrolabe.event

import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Generation
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.StopReason
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventsTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")

    private val samples: List<AgentEvent> = listOf(
        AgentEvent.Campaign.Opened(ids, "U1"),
        AgentEvent.Cell.ModelResponded(ids, "inv-1", StopReason.ToolUse, null),
        AgentEvent.Cell.ToolCalled(ids, 1, "look", "read", Phase.Locate, SpanId("s1"), SpanId("s0")),
        AgentEvent.Edit.Applied(ids, "e1", listOf("src/a.kt")),
        AgentEvent.Cell.Rebuilt(ids, "pressure", Generation(1)),
        AgentEvent.Blocked(ids, "no answerer", "q1"),
        AgentEvent.Warning(ids, "instruction-shaped", "flagged"),
        AgentEvent.Budget.Reserved(ids, 3, 500, "model call", Phase.Understand),
        AgentEvent.Kb.Invalidated(ids, "N-1", "anchor moved"),
    )

    @Test
    fun `every event serializes with its discriminator and round trips`() {
        val json = Json
        for (event in samples) {
            val text = json.encodeToString(AgentEvent.serializer(), event)
            assertTrue(text.contains("\"type\":\""), text)
            assertEquals(event, json.decodeFromString(AgentEvent.serializer(), text))
        }
        val record = EventRecord(7, clock.instant(), samples[0])
        assertEquals(record, json.decodeFromString(EventRecord.serializer(), json.encodeToString(EventRecord.serializer(), record)))
    }

    @Test
    fun `records are sequenced in emission order and delivered to sinks`() {
        Events(clock).use { events ->
            val recorder = EventRecorder()
            events.subscribe(recorder).use {
                val emitted = samples.map { events.emit(it) }
                assertEquals((1L..samples.size).toList(), emitted.map { it.seq })
                assertTrue(recorder.awaitCount(samples.size))
                assertEquals(emitted, recorder.records)
                assertEquals(clock.instant(), recorder.records.first().at)
                assertTrue(recorder.gaps().isEmpty())
            }
        }
    }

    @Test
    fun `a failing or slow listener never disturbs the emitter or other sinks`() {
        Events(clock, replay = 0).use { events ->
            val good = EventRecorder()
            val gate = CountDownLatch(1)
            val slow = EventRecorder()
            val slowSubscription = events.subscribe(
                { record ->
                    if (record.seq == 1L) gate.await(5, TimeUnit.SECONDS)
                    slow.onEvent(record)
                },
                bufferCapacity = 2,
            )
            events.subscribe(good)
            events.subscribe { throw IllegalStateException("boom") }
            val emitted = (1..8).map { events.emit(AgentEvent.Warning(ids, "k", "w$it")) }
            assertEquals(8, emitted.size)
            assertEquals(8L, events.lastSeq)
            assertTrue(good.awaitCount(8))
            assertEquals(emitted.map { it.seq }, good.records.map { it.seq })
            gate.countDown()
            assertTrue(slow.awaitCount(2))
            Thread.sleep(200)
            assertTrue(events.sinkFailures >= 8, "failures=${events.sinkFailures}")
            assertTrue(slow.records.size <= 4, "slow subscriber kept only its buffer: ${slow.records.map { it.seq }}")
            assertTrue(slowSubscription.dropped >= 4, "dropped=${slowSubscription.dropped}")
            assertTrue(slow.gaps().isNotEmpty(), "gaps visible through seq: ${slow.records.map { it.seq }}")
            assertEquals(8L, slow.records.last().seq)
        }
    }

    @Test
    fun `a late subscriber receives the replayed tail`() {
        Events(clock, replay = 3).use { events ->
            (1..5).forEach { events.emit(AgentEvent.Warning(ids, "k", "w$it")) }
            val late = EventRecorder()
            events.subscribe(late)
            assertTrue(late.awaitCount(3))
            assertEquals(listOf(3L, 4L, 5L), late.records.map { it.seq })
        }
    }
}
