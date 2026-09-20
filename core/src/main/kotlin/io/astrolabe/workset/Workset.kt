package io.astrolabe.workset

import io.astrolabe.id.FileVersion
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import kotlinx.serialization.Serializable

@Serializable
public enum class EntrySource { Look, PostEdit, Seed, Recall }

/**
 * One Workset entry (§5.3): exact source bytes delivered in the current projection, at one version. Only the
 * caller that rendered source bytes registers an entry; outlines, symbol locations and enclosing spans never
 * do. [hidden] lines were redacted in the rendered view and grant no coverage (D-49).
 */
@Serializable
public data class Entry(
    val path: String,
    val range: Ranges,
    val version: FileVersion,
    val source: EntrySource,
    val turn: Int,
    /** The result alias or observation id that carries the bytes (recall target). */
    val resultId: String?,
    val tokens: Long,
    val hidden: Ranges = Ranges.EMPTY,
) {
    init {
        require(path.isNotBlank() && !range.isEmpty) { "an entry needs a path and displayed lines" }
    }

    val coverage: Ranges get() = range - hidden
}

/** A KNOWN entry dropped because its file gained a new version (§5.3 announcement). */
@Serializable
public data class StaleDrop(
    val path: String,
    val range: Ranges,
    val version: FileVersion,
    val cause: String,
    val recallId: String?,
    /** True when the stale body exceeds the immediate-stub threshold and must be stubbed now, not at the batch. */
    val stubNow: Boolean,
) {
    val text: String get() = "$path:$range stale @${version.hash8} ($cause) → " + (recallId?.let { "recall $it or read again" } ?: "read again")
}

/** Immutable coverage snapshot used at dispatch time (FX-50: same-batch reads never authorize an edit). */
public class WorksetView internal constructor(private val entries: List<Entry>) {
    /** True when live entries at exactly [version] cover every line of [range], redacted lines excluded. */
    public fun covers(path: String, version: FileVersion, range: LineRange): Boolean {
        val coverage = entries.filter { it.path == path && it.version == version }.fold(Ranges.EMPTY) { acc, e -> acc + e.coverage }
        return coverage.covers(range)
    }

    public fun coverage(path: String, version: FileVersion): Ranges =
        entries.filter { it.path == path && it.version == version }.fold(Ranges.EMPTY) { acc, e -> acc + e.coverage }

    public val paths: Set<String> get() = entries.map { it.path }.toSet()
}

/**
 * The Workset (§5.3, TODO P1.5.3): KNOWN = entries whose version is current and whose bytes are live in
 * `[T]`/`[K]`; everything else is NOT SEEN. A version change drops the entry and announces it in the same
 * turn; the physical stub happens at the next eviction batch unless the body exceeds [immediateStubTokens].
 */
public class Workset(
    private val immediateStubTokens: Int = 800,
    private val renderCapTokens: Int = 60,
) {
    private val live = ArrayList<Entry>()
    private val stale = ArrayList<Entry>()
    private val drops = ArrayList<StaleDrop>()
    private val stubbedIds = HashSet<String>()

    public val entries: List<Entry> get() = live.toList()

    /** Announcements pending for the current turn's anchor; cleared by [takeAnnouncements]. */
    public val pendingDrops: List<StaleDrop> get() = drops.toList()

    /** Registers rendered source bytes; overlapping coverage at the same version merges. */
    public fun register(entry: Entry) {
        val same = live.indexOfFirst { it.path == entry.path && it.version == entry.version && it.resultId == entry.resultId && it.source == entry.source }
        if (same >= 0) {
            val e = live[same]
            live[same] = e.copy(range = e.range + entry.range, hidden = e.hidden + entry.hidden, tokens = e.tokens + entry.tokens, turn = maxOf(e.turn, entry.turn))
        } else {
            live += entry
        }
    }

    public fun snapshot(): WorksetView = WorksetView(live.toList())

    public fun covers(path: String, version: FileVersion, range: LineRange): Boolean = snapshot().covers(path, version, range)

    /**
     * Coherence hook (§4.4 turn horizon): every live entry of [path] at [from] leaves KNOWN now and is announced;
     * returns the drops so the caller can stub large bodies immediately.
     */
    public fun onVersionChange(path: String, from: FileVersion, cause: String): List<StaleDrop> {
        val affected = live.filter { it.path == path && it.version == from }
        if (affected.isEmpty()) return emptyList()
        live.removeAll(affected)
        stale += affected
        val announced = affected.map { StaleDrop(it.path, it.range, it.version, cause, it.resultId, stubNow = it.tokens > immediateStubTokens) }
        drops += announced
        return announced
    }

    /** Eviction batch (P1.8.6): a stubbed result no longer contributes coverage. */
    public fun stub(resultId: String) {
        stubbedIds += resultId
        live.removeAll { it.resultId == resultId }
        stale.removeAll { it.resultId == resultId }
    }

    /**
     * `recall(id)` re-registers the captured bytes at their recorded version; if the file moved on, the entry
     * is `historical` (returned, not KNOWN at the current version).
     */
    public fun recall(entry: Entry, currentVersion: FileVersion?, turn: Int): RecallResult {
        stubbedIds -= entry.resultId ?: ""
        val recalled = entry.copy(source = EntrySource.Recall, turn = turn)
        return if (currentVersion == null || currentVersion == entry.version) {
            register(recalled)
            RecallResult.Known(recalled)
        } else {
            RecallResult.Historical(recalled, currentVersion)
        }
    }

    public fun takeAnnouncements(): List<StaleDrop> = drops.toList().also { drops.clear() }

    /** Cell-end export (re-served as seeds by the compiler, §6.2). */
    public fun export(): List<Entry> = live.toList()

    /** Seeds re-served by the compiler are KNOWN at the version they were rendered with. */
    public fun seed(entries: List<Entry>) {
        entries.forEach { register(it.copy(source = EntrySource.Seed)) }
    }

    public val knownTokens: Long get() = live.sumOf { it.tokens }

    /** `KNOWN: … · NOT SEEN: everything else` plus named stale drops, capped at [renderCapTokens]. */
    public fun render(estimator: TokenEstimator): String {
        val known = live.sortedWith(compareBy({ it.path }, { it.range.ranges.first().from }))
            .joinToString(" · ") { "${it.path}:${it.range}@${it.version.hash8.take(4)}" }
        val base = "KNOWN: " + (if (known.isEmpty()) "(nothing)" else known) + " · NOT SEEN: everything else"
        val dropLines = drops.map { it.text }
        var text = base
        for (line in dropLines) {
            val candidate = "$text; $line"
            if (estimator.estimate(candidate).tokens > renderCapTokens) {
                text = "$text; +${dropLines.size - dropLines.indexOf(line)} stale drops"
                return truncate(text, estimator)
            }
            text = candidate
        }
        return truncate(text, estimator)
    }

    private fun truncate(text: String, estimator: TokenEstimator): String {
        if (estimator.estimate(text).tokens <= renderCapTokens) return text
        // Drop KNOWN detail before the drops: coverage is authoritative in the registry, this line is a hint.
        val paths = live.map { it.path }.toSet()
        val short = "KNOWN: ${paths.size} files/${live.size} ranges · NOT SEEN: everything else" +
            (if (drops.isEmpty()) "" else "; ${drops.size} stale drops (recall)")
        return short
    }

    public sealed interface RecallResult {
        public data class Known(val entry: Entry) : RecallResult

        public data class Historical(val entry: Entry, val currentVersion: FileVersion) : RecallResult
    }
}
