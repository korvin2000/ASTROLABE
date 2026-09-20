package io.astrolabe.fixtures

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.event.EventSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Records every delivered event; [awaitCount] waits for asynchronous delivery (TODO P0.6.4). */
public class EventRecorder : EventSink {
    private val recordsMutable = CopyOnWriteArrayList<EventRecord>()
    private val latches = CopyOnWriteArrayList<Pair<Int, CountDownLatch>>()

    public val records: List<EventRecord> get() = recordsMutable.toList()

    public val events: List<AgentEvent> get() = recordsMutable.map { it.event }

    override fun onEvent(record: EventRecord) {
        recordsMutable += record
        val size = recordsMutable.size
        latches.filter { it.first <= size }.forEach { it.second.countDown() }
    }

    /** Returns true when at least [count] records arrived within the timeout. */
    public fun awaitCount(count: Int, timeoutSeconds: Long = 5): Boolean {
        if (recordsMutable.size >= count) return true
        val latch = CountDownLatch(1)
        latches += count to latch
        if (recordsMutable.size >= count) return true
        return latch.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    public inline fun <reified T : AgentEvent> ofType(): List<T> = events.filterIsInstance<T>()

    /** Sequence gaps observed by this recorder (a slow subscriber's dropped records). */
    public fun gaps(): List<LongRange> {
        val seqs = recordsMutable.map { it.seq }
        return seqs.zipWithNext().filter { (a, b) -> b > a + 1 }.map { (a, b) -> (a + 1)..(b - 1) }
    }
}
