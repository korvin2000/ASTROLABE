package io.astrolabe.verify

import io.astrolabe.Flags
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Outcome
import io.astrolabe.fixtures.FakeWatcher
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P5.7.1: the async `Watcher` contract, a fake watcher, and `WatcherFeed` supersession by version tag. */
class WatcherTest {
    private val check = Check("CHK-types-touched", CheckKind.Type, Selector.Touched, Closure.Known(setOf("a.py")), CostClass.Fast, Trigger.EveryEdit)
    private fun stamp(seed: String) = CandidateId(Digest.ofUtf8(seed))

    private fun result(version: Long, stampSeed: String, outcome: Outcome = Outcome.Passed, errors: List<String> = emptyList()) =
        WatcherResult(check.id, "wr-$version", version, stamp(stampSeed), outcome, errors)

    @Test
    fun `off by default, the synchronous checker remains the baseline`() {
        assertFalse(Flags().asyncChecker, "async watchers are an ablation, never the default path (D12)")
    }

    @Test
    fun `a higher-version result supersedes the current one, and a late lower-version arrival never overwrites it`() {
        val feed = WatcherFeed()
        assertTrue(feed.record(result(1, "s1", errors = listOf("x.py:1: boom"))))
        assertEquals(1, feed.latest().size)
        assertTrue(feed.record(result(2, "s2")), "a newer dispatch becomes current")
        assertEquals(Outcome.Passed, feed.latest().single().outcome)
        assertEquals(1, feed.history(check.id).size, "the superseded v1 result is archived")
        val late = result(1, "s1", errors = listOf("x.py:1: boom"))
        assertFalse(feed.record(late), "a v1 result arriving after v2 is superseded on arrival")
        assertEquals(Outcome.Passed, feed.latest().single().outcome, "the late arrival never becomes current")
        assertEquals(2, feed.history(check.id).size)
    }

    @Test
    fun `render feeds the same ChecksRender the synchronous checker uses`() {
        val feed = WatcherFeed()
        feed.record(result(1, "s1", outcome = Outcome.Failed, errors = listOf("x.py:1: boom")))
        val rendered = feed.render(stamp("s1"))
        assertTrue(rendered.contains("async:"), rendered)
        assertTrue(rendered.contains("now 1"), rendered)
    }

    @Test
    fun `a fake watcher delivers to open subscriptions and stops on close`() {
        val watcher = FakeWatcher()
        val delivered = ArrayList<WatcherResult>()
        val subscription = watcher.start(listOf(check), version = 1, sink = { delivered += it })
        watcher.push(result(1, "s1"))
        assertEquals(1, delivered.size)
        subscription.close()
        watcher.push(result(2, "s2"))
        assertEquals(1, delivered.size, "a closed subscription receives nothing more")
    }
}
