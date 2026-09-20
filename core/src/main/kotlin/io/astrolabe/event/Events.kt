package io.astrolabe.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Host-implementable listener (Java lambda friendly); invoked on the bus's own dispatcher, never under a lock. */
public fun interface EventSink {
    public fun onEvent(record: EventRecord)
}

public interface Subscription : AutoCloseable {
    public val active: Boolean

    /** Records this subscriber lost because it fell more than its buffer behind (approximate under contention). */
    public val dropped: Long

    override fun close()
}

/**
 * The outbound event bus of one campaign (TODO P0.4.1, D-26).
 *
 * Delivery semantics: [emit] never suspends and never blocks the emitter; it assigns the next sequence number
 * under a short lock so `seq` is monotonic in emission order. Every subscriber has its own buffer and first
 * receives the last [replay] records; a subscriber slower than its buffer loses its **oldest** pending records
 * (counted in [Subscription.dropped]) and sees the loss as a gap in `seq`, which it repairs by re-reading
 * [Views]. One slow subscriber never affects another. A sink that throws is logged and counted in
 * [sinkFailures]; the emitting operation is unaffected, so a listener can never separate an edit's effects
 * from its receipt.
 */
public class Events(
    private val clock: Clock = Clock.systemUTC(),
    private val replay: Int = DEFAULT_REPLAY,
    private val bufferCapacity: Int = DEFAULT_BUFFER,
) : AutoCloseable {
    private val lock = Any()
    private val counter = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val recent = ArrayDeque<EventRecord>(replay + 1)
    private val subscribers = CopyOnWriteArrayList<Subscriber>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        require(replay >= 0 && bufferCapacity >= 1) { "replay must be ≥ 0 and bufferCapacity ≥ 1" }
    }

    public val lastSeq: Long get() = counter.get()

    public val sinkFailures: Long get() = failures.get()

    public fun emit(event: AgentEvent): EventRecord {
        val record = synchronized(lock) {
            val r = EventRecord(counter.incrementAndGet(), clock.instant(), event)
            if (replay > 0) {
                if (recent.size == replay) recent.removeFirst()
                recent.addLast(r)
            }
            r
        }
        for (s in subscribers) s.offer(record)
        return record
    }

    /** Subscribes a host sink without coroutines; close the subscription to stop delivery. */
    public fun subscribe(sink: EventSink): Subscription = subscribe(sink, bufferCapacity)

    /** As [subscribe], with this subscriber's own buffer size. */
    public fun subscribe(sink: EventSink, bufferCapacity: Int): Subscription {
        require(bufferCapacity >= 1) { "bufferCapacity must be ≥ 1" }
        val subscriber = Subscriber(sink, bufferCapacity)
        synchronized(lock) {
            recent.forEach(subscriber::offer)
            subscribers += subscriber
        }
        subscriber.start()
        return subscriber
    }

    /** Kotlin consumers: the same delivery semantics as [subscribe], as a cold flow per collector. */
    public fun records(): Flow<EventRecord> = callbackFlow {
        val subscription = subscribe(EventSink { trySend(it) })
        awaitClose { subscription.close() }
    }

    override fun close() {
        subscribers.forEach { it.close() }
        scope.cancel()
    }

    private inner class Subscriber(private val sink: EventSink, private val capacity: Int) : Subscription {
        private val channel = Channel<EventRecord>(capacity, BufferOverflow.DROP_OLDEST)
        private val pending = AtomicLong(0)
        private val droppedCount = AtomicLong(0)

        @Volatile
        private var closed = false
        private val job = scope.launch(start = CoroutineStart.LAZY) {
            for (record in channel) {
                pending.decrementAndGet()
                try {
                    sink.onEvent(record)
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                    log.warn("event sink failed on seq {}: {}", record.seq, t.toString())
                }
            }
        }

        fun offer(record: EventRecord) {
            if (closed) return
            if (pending.get() >= capacity) droppedCount.incrementAndGet() else pending.incrementAndGet()
            channel.trySend(record) // DROP_OLDEST: never fails while open
        }

        fun start() = job.start()

        override val active: Boolean get() = job.isActive
        override val dropped: Long get() = droppedCount.get()

        override fun close() {
            closed = true
            subscribers.remove(this)
            channel.close()
            job.cancel()
        }
    }

    public companion object {
        public const val DEFAULT_REPLAY: Int = 256
        public const val DEFAULT_BUFFER: Int = 4096
        private val log = LoggerFactory.getLogger(Events::class.java)
    }
}
