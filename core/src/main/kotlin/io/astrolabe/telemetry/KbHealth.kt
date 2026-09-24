package io.astrolabe.telemetry

import io.astrolabe.kb.KbIndex
import io.astrolabe.kb.KbResolver
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.QueueEntry
import io.astrolabe.kb.QueueStatus
import io.astrolabe.kb.UsageEvent
import io.astrolabe.kb.UsageRow
import io.astrolabe.provider.TokenEstimator
import java.nio.file.Files
import java.nio.file.Path

/**
 * KB health (§12.2): the denominators are instrumented, so a rate is `null` until its denominator is non-zero, and a
 * quantity no producer reports yet is `null` (harmful injections need the outcome judge of P4.4), never zero.
 */
public data class KbHealth(
    val candidates: Int,
    val admitted: Int,
    val rejected: Int,
    val waiting: Int,
    /** admitted / (admitted + rejected) over decided queue entries. */
    val admissionRate: Double?,
    val injections: Int,
    /** cited / injected over usage rows. */
    val citedRate: Double?,
    /** Injections of notes that are stale now (advice that outlived its anchors). */
    val staleInjections: Int,
    val harmfulInjections: Int?,
    /** Admitted `PIT` notes cited in at least two cells: the mistake recurred. */
    val repeatedMistakes: Int,
    /** Whether `kb/index/` equals what the current notes render to. */
    val indexFresh: Boolean,
    /** Resolving anchors / anchors of admitted notes. */
    val locatorValidity: Double?,
) {
    public companion object {
        @JvmStatic
        public fun of(notes: List<Note>, queue: List<QueueEntry>, usage: List<UsageRow>, resolver: KbResolver, indexDir: Path, estimator: TokenEstimator): KbHealth {
            val decidedAdmitted = queue.count { it.status == QueueStatus.Admitted }
            val decidedRejected = queue.count { it.status == QueueStatus.Rejected }
            val decided = decidedAdmitted + decidedRejected
            val injected = usage.filter { it.event == UsageEvent.Injected }
            val cited = usage.filter { it.event == UsageEvent.Cited }
            val stale = notes.filter { it.status == NoteStatus.Stale }.map { it.id }.toSet()
            val anchors = notes.filter { it.status == NoteStatus.Admitted }.flatMap { n -> n.anchors.map { it.path } }
            val rendered = KbIndex.render(notes, estimator)
            val fresh = rendered.all { (name, text) ->
                val path = indexDir.resolve(name)
                Files.exists(path) && Files.readString(path) == text
            } && (!Files.isDirectory(indexDir) || Files.list(indexDir).use { listing -> listing.noneMatch { it.fileName.toString().endsWith(".md") && it.fileName.toString() !in rendered } })
            return KbHealth(
                candidates = notes.count { it.status == NoteStatus.Candidate },
                admitted = notes.count { it.status == NoteStatus.Admitted },
                rejected = notes.count { it.status == NoteStatus.Rejected },
                waiting = queue.count { it.status == QueueStatus.Queued },
                admissionRate = if (decided == 0) null else decidedAdmitted.toDouble() / decided,
                injections = injected.size,
                citedRate = if (injected.isEmpty()) null else cited.size.toDouble() / injected.size,
                staleInjections = injected.count { it.noteId in stale },
                harmfulInjections = null,
                repeatedMistakes = notes.filter { it.kind == NoteKind.PIT && it.status == NoteStatus.Admitted }
                    .count { pit -> cited.filter { it.noteId == pit.id }.map { it.context }.distinct().size >= 2 },
                indexFresh = fresh,
                locatorValidity = if (anchors.isEmpty()) null else anchors.count(resolver::anchorExists).toDouble() / anchors.size,
            )
        }
    }
}
