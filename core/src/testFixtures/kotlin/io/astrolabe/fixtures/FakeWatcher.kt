package io.astrolabe.fixtures

import io.astrolabe.verify.Check
import io.astrolabe.verify.Watcher
import io.astrolabe.verify.WatcherResult
import io.astrolabe.verify.WatcherSink
import io.astrolabe.verify.WatcherSubscription
import java.util.Collections

/**
 * A scripted [Watcher] for test fixtures (TODO P5.7.1): [start] records the sink and returns a subscription;
 * [push] delivers a result to every subscription still open, so a test can assert that a closed subscription
 * never receives a late result.
 */
public class FakeWatcher : Watcher {
    private class Registered(val sink: WatcherSink) : WatcherSubscription {
        @Volatile
        var open: Boolean = true

        override fun close() {
            open = false
        }
    }

    private val subscriptions = Collections.synchronizedList(ArrayList<Registered>())

    override fun start(checks: List<Check>, version: Long, sink: WatcherSink): WatcherSubscription =
        Registered(sink).also { subscriptions += it }

    /** Delivers [result] to every subscription still open. */
    public fun push(result: WatcherResult) {
        subscriptions.filter { it.open }.forEach { it.sink.onResult(result) }
    }
}
