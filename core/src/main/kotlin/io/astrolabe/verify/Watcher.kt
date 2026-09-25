package io.astrolabe.verify

import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId

/**
 * One asynchronous watcher result (§8.1 "why synchronous in the baseline", D12, TODO P5.7.1): the same
 * evidence a synchronous [CheckerResult] reports, tagged with the [version] its watch was dispatched at — a
 * monotonic dispatch counter, never the content [stamp] itself, since two different dispatches can share a
 * stamp only by reverting to it. [WatcherFeed] uses [version] to decide supersession, so a result computed
 * against an older edit can never overwrite one computed against a newer edit merely by arriving later in
 * wall-clock time (§8.3: "Superseded checker results are archived, not presented as current").
 */
public data class WatcherResult(
    val checkId: String,
    val resultId: String,
    val version: Long,
    val stamp: CandidateId,
    val outcome: Outcome,
    val errorLines: List<String>,
) {
    init {
        require(version >= 0) { "version is a monotonic dispatch counter" }
    }

    val counts: Counts?
        get() = when {
            errorLines.isNotEmpty() -> Counts(errors = errorLines.size)
            outcome == Outcome.Passed -> Counts()
            else -> null
        }

    /** The §8.3 line this result renders as, through the same [ChecksRender] the synchronous checker uses. */
    public fun line(): CheckLine = CheckLine(
        label = "async",
        scope = null,
        delta = null,
        state = when (outcome) {
            Outcome.Passed -> CheckState.Green("watcher")
            Outcome.Failed -> CheckState.Red("", errorLines.size)
            Outcome.Timeout -> CheckState.Timeout("watcher time box")
            Outcome.NotRun -> CheckState.NotRun
            Outcome.Unavailable -> CheckState.Unavailable("watcher unavailable")
            else -> CheckState.Inconclusive("watcher: ${outcome.name.lowercase()}")
        },
        stampHash = stamp.hash8,
        receiptAlias = resultId,
    )
}

/**
 * Host-supplied delivery of [WatcherResult]s: a plain synchronous callback (Java-lambda friendly, no
 * suspend/Flow — mirrors [io.astrolabe.event.EventSink], so this SPI needs no separate `io.astrolabe.java`
 * form, D-07's intent is already met). Invoked on the watcher's own dispatcher, never under a lock; a sink
 * that throws must not stop delivery to the rest of the feed.
 */
public fun interface WatcherSink {
    public fun onResult(result: WatcherResult)
}

public interface WatcherSubscription : AutoCloseable {
    override fun close()
}

/**
 * §8.1 "why synchronous in the baseline" / D12 (TODO P5.7.1): an optional async feed that starts delivering
 * results for [checks] dispatched at [version] to [sink], until [WatcherSubscription.close]. Off by default
 * ([io.astrolabe.Flags.asyncChecker]) and never the only verification path — the synchronous [Checker] remains
 * the baseline (D12: async only with a tier-2 [io.astrolabe.atlas.LanguageService] adapter and an ablation). A
 * result for an already-closed subscription must not reach [sink].
 */
public interface Watcher {
    public fun start(checks: List<Check>, version: Long, sink: WatcherSink): WatcherSubscription
}

/**
 * Reconciles pushed [WatcherResult]s against dispatch order (§8.1, §8.3, TODO P5.7.1): [record] keeps only the
 * highest [WatcherResult.version] seen per check as current, archiving every other arrival — including one
 * that arrives later in wall-clock time but names an older version, which is superseded on arrival and never
 * shown; [render] feeds the same [ChecksRender] the synchronous [Checker] uses so the two presentations never
 * diverge.
 */
public class WatcherFeed {
    private val current = LinkedHashMap<String, WatcherResult>()
    private val archive = LinkedHashMap<String, MutableList<WatcherResult>>()

    /** True when [result] became the current result for its check; false when it arrived already superseded. */
    public fun record(result: WatcherResult): Boolean {
        val existing = current[result.checkId]
        if (existing != null && result.version <= existing.version) {
            archive.getOrPut(result.checkId) { ArrayList() } += result
            return false
        }
        existing?.let { archive.getOrPut(result.checkId) { ArrayList() } += it }
        current[result.checkId] = result
        return true
    }

    /** The latest result of every check a [record] has touched, in first-touched order. */
    public fun latest(): List<WatcherResult> = current.values.toList()

    /** Superseded results of [checkId], oldest first; never rendered as current. */
    public fun history(checkId: String): List<WatcherResult> = archive[checkId]?.toList() ?: emptyList()

    public fun render(stampNow: CandidateId?): String = ChecksRender.render(stampNow, current.values.map { it.line() })
}
