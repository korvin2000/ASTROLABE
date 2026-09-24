package io.astrolabe.kb

import io.astrolabe.cell.Change
import io.astrolabe.cell.ResultPacket
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.Effort
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.register.Register
import io.astrolabe.route.FunctionRow
import io.astrolabe.route.FunctionTable
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.Tier
import io.astrolabe.store.Store
import io.astrolabe.verify.Finding
import java.time.Clock
import java.util.Locale
import kotlin.math.roundToInt

/** The §12.1 candidate kinds; a delta extends or supersedes an existing `BMAP`, `SKILL` or `CAL` note. */
public enum class CandidateKind(public val noteKind: NoteKind) {
    LES(NoteKind.LES), PIT(NoteKind.PIT), BMAP_DELTA(NoteKind.BMAP), NEG(NoteKind.NEG), SKILL_DELTA(NoteKind.SKILL), CAL_DELTA(NoteKind.CAL),
}

/** The §12.2 diagnosis shape: a candidate is a diagnosis, never a dump; [evidence] refs and the [sourceRevision] bind it. */
public data class Diagnosis(
    val symptom: String,
    val conditions: String,
    val attempted: String,
    val observed: String,
    val reason: String,
    val evidence: List<String>,
    val sourceRevision: String,
    val invalidation: String,
) {
    init {
        require(symptom.isNotBlank() && sourceRevision.isNotBlank()) { "a diagnosis names its symptom and source revision" }
        require(evidence.isNotEmpty()) { "a diagnosis cites evidence" }
    }

    /** The note body: every field labelled, one line, so the lint and a later cell read the same shape. */
    public fun render(): String =
        "symptom: $symptom · conditions: $conditions · attempted: $attempted · observed: $observed · reason: $reason" +
            " · evidence: ${evidence.joinToString(", ")} · source: $sourceRevision · invalidation: $invalidation"
}

/** One candidate the extractor produced: a note's front matter with its [diagnosis] as the body (§12.1). */
public data class Candidate @JvmOverloads constructor(
    val kind: CandidateKind,
    /** The `<name>` of `<KIND>-<name>`. */
    val name: String,
    val summary: String,
    val scope: String,
    val diagnosis: Diagnosis,
    val anchors: List<NoteAnchor> = emptyList(),
    val confidence: Double? = null,
    val dependsOn: List<String> = emptyList(),
    /** The `BMAP`/`SKILL` note this delta replaces (never rewritten in place). */
    val supersedes: String? = null,
    /** A `NEG` candidate's typed state (§12.2, P4.2.2): required for `NEG`, absent otherwise. */
    val negative: NegativeEvidence? = null,
) {
    init {
        require(name.isNotBlank() && name.none { it.isWhitespace() }) { "a candidate name is one token" }
        // D-42: the model never writes calibration statistics; the harness aggregates the CAL delta.
        require(kind != CandidateKind.CAL_DELTA) { "a CAL delta is aggregated from CalibrationStats, never proposed" }
        require((kind == CandidateKind.NEG) == (negative != null)) { "a NEG candidate carries its typed state, and only a NEG does" }
    }

    val id: String get() = "${kind.noteKind.name}-$name"

    /** The note; a `NEG` summary is prefixed with its `[state]` and its body opens with the state's own reading. */
    public fun note(origin: NoteOrigin): Note = Note(
        id, kind.noteKind, NoteStatus.Candidate, negative?.let { "[${it.state.wire}] $summary" } ?: summary,
        listOfNotNull(negative?.render(), diagnosis.render()).joinToString(" · "), scope, anchors, confidence,
        NoteBasis(evidenceRefs = diagnosis.evidence), NoteValidity(dependsOn, diagnosis.sourceRevision, diagnosis.invalidation),
        supersedes, origin = origin,
    )
}

/**
 * The archived trace the extractor reads (§3.4 extractor row): the final STATE, the journal digest, the diff
 * summary and the receipts, all inside or beside the Result Packet — never the live cell.
 */
public data class ExtractionTrace @JvmOverloads constructor(
    val packet: ResultPacket,
    val journal: List<JournalEvent> = emptyList(),
    val sourceRevision: String = packet.stamp?.digest?.hex ?: "unstamped",
) {
    val finalState: Register get() = packet.register
    val diffSummary: List<Change> get() = packet.changes
    val receipts: List<String> get() = packet.receipts

    /** The last [maxLines] journal lines, each cut at [maxChars]: `seq kind: text`. */
    @JvmOverloads
    public fun journalDigest(maxLines: Int = 40, maxChars: Int = 160): String =
        journal.takeLast(maxLines).joinToString("\n") { "${it.seq} ${it.kind.name.lowercase()}: ${it.text.take(maxChars)}" }
}

/** What one extraction call produced and what it cost, charged to the originating work. */
public data class ExtractionResult @JvmOverloads constructor(val candidates: List<Candidate> = emptyList(), val tokens: Long = 0) {
    init {
        require(tokens >= 0)
    }
}

/**
 * The model-driven step of §12.1: a function of the archived trace at the extractor row's tier, run post-cell.
 * Tests script it over the fake provider; a provider-backed extractor is P7. Synchronous, so a Java host can supply one.
 */
public fun interface Extraction {
    public fun extract(trace: ExtractionTrace, tier: Tier, effort: Effort?): ExtractionResult

    public companion object {
        /** No model-driven candidates: only the harness-derived ones and the CAL delta. */
        @JvmField
        public val NONE: Extraction = Extraction { _, _, _ -> ExtractionResult() }
    }
}

/** One extraction run: what was queued, what the queue or the lint-exempt writer refused, and a failure that was contained. */
public data class ExtractionReport(
    val enqueued: List<QueueEntry>,
    val refused: Map<String, String>,
    val failure: String?,
    val tokens: Long,
)

/**
 * The post-cell extractor (§12.1, §3.4): from the archived trace it produces candidates — the harness-derived ones
 * (P4.2.2), the [Extraction]'s and the §6.7 `CAL` delta — and enqueues them (P4.1.1); only the curator admits.
 * It runs after the cell, at the curation row's tier, and never blocks the campaign loop: a failure or a refusal
 * is journaled and reported, never thrown.
 */
public class Extractor @JvmOverloads constructor(
    store: Store,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val clock: Clock,
    private val extraction: Extraction = Extraction.NONE,
    private val journal: Journal? = null,
    private val events: Events? = null,
    private val row: FunctionRow = FunctionTable.DEFAULT.row(RoutingFunction.Curation),
) {
    private val notes = Notes(store)
    public val queue: Queue = Queue(store, KbWriter(store, estimator, clock), idGen, clock)

    /**
     * Extracts from [trace] under the originating cell's [ids]: first the record-derived candidates (P4.2.2, from
     * the trace, the campaign review's [findings] and the [earlier] cells' registers), then the model step's.
     */
    @JvmOverloads
    public fun run(trace: ExtractionTrace, ids: Identities, findings: List<Finding> = emptyList(), earlier: List<Register> = emptyList()): ExtractionReport {
        val enqueued = ArrayList<QueueEntry>()
        val refused = LinkedHashMap<String, String>()
        var tokens = 0L
        var failure: String? = null
        try {
            for (candidate in Derived.candidates(trace, findings, earlier)) enqueue(candidate.note(origin(ids, HARNESS_DERIVED)), ids, enqueued, refused)
            val result = extraction.extract(trace, row.defaultTier, row.effort)
            tokens = result.tokens
            for (candidate in result.candidates) enqueue(candidate.note(origin(ids, EXTRACTOR)), ids, enqueued, refused)
        } catch (e: Exception) {
            failure = "${e::class.simpleName}: ${e.message}"
        }
        journal?.append(
            JournalEvent(
                idGen.next("ev"), ids, null, JournalKind.Boundary, refs = enqueued.map { it.noteId },
                text = "extraction of ${trace.packet.increment} (${row.defaultTier.name.lowercase()} tier, $tokens tokens charged to ${ids.work.value}): " +
                    "enqueued ${enqueued.size}, refused ${refused.size}" + refused.entries.joinToString("") { " · ${it.key}: ${it.value}" } + (failure?.let { " · failed: $it" } ?: ""),
                at = clock.instant(),
            ),
        )
        return ExtractionReport(enqueued, refused, failure, tokens)
    }

    /**
     * The §6.7 `CAL-<repo>` delta (D-42): the note is rendered from [stats] — harness data, never model text — and
     * enqueued when it differs from the admitted one, which it names in `supersedes`. [stats] is computed here so
     * a conflicting checkpoint set is contained like any other failure.
     */
    public fun calibrate(stats: () -> CalibrationStats, series: CalibrationSeries, ids: Identities): ExtractionReport {
        val enqueued = ArrayList<QueueEntry>()
        val refused = LinkedHashMap<String, String>()
        var failure: String? = null
        try {
            val rendered = CalibrationNote.render(stats(), series, estimator)
            if (rendered != null) {
                val base = "${NoteKind.CAL.name}-${series.repository}"
                val family = notes.all().filter { it.kind == NoteKind.CAL && (it.id == base || it.id.startsWith("$base-r")) }
                val current = family.firstOrNull { it.status == NoteStatus.Admitted || it.status == NoteStatus.Stale }
                if (current == null || current.body != rendered.body) {
                    val id = family.firstOrNull { it.status == NoteStatus.Candidate }?.id
                        ?: if (family.none { it.id == base }) base else generateSequence(2) { it + 1 }.map { "$base-r$it" }.first { id -> family.none { it.id == id } }
                    val note = Note(
                        id, NoteKind.CAL, NoteStatus.Candidate, rendered.summary, rendered.body, "global", confidence = null,
                        basis = NoteBasis(evidenceRefs = rendered.evidence), validity = NoteValidity(invalidationTrigger = "a new campaign ends in ${series.repository}"),
                        supersedes = current?.id, origin = origin(ids, HARNESS_CALIBRATION + stats().algorithmVersion),
                    )
                    enqueue(note, ids, enqueued, refused)
                }
            }
        } catch (e: Exception) {
            failure = "${e::class.simpleName}: ${e.message}"
        }
        journal?.append(
            JournalEvent(
                idGen.next("ev"), ids, null, JournalKind.Boundary, refs = enqueued.map { it.noteId },
                text = "calibration delta for ${series.repository}: " + (if (enqueued.isEmpty()) "none" else "enqueued ${enqueued.joinToString { it.noteId }}") +
                    refused.entries.joinToString("") { " · ${it.key}: ${it.value}" } + (failure?.let { " · failed: $it" } ?: ""),
                at = clock.instant(),
            ),
        )
        return ExtractionReport(enqueued, refused, failure, 0)
    }

    private fun enqueue(note: Note, ids: Identities, enqueued: MutableList<QueueEntry>, refused: MutableMap<String, String>) {
        try {
            enqueued += queue.enqueue(note, ids)
            events?.emit(AgentEvent.Kb.Proposed(ids, note.id, note.kind.name))
        } catch (e: NoteRefused) {
            refused[note.id] = (e.message ?: "refused").removePrefix("${note.id}: ")
        } catch (e: IllegalArgumentException) {
            refused[note.id] = e.message ?: "invalid"
        }
    }

    private fun origin(ids: Identities, extractor: String) = NoteOrigin(work = ids.work.value, cell = ids.context?.value, extractor = extractor)

    public companion object {
        /** `origin.extractor` of a model-produced candidate. */
        public const val EXTRACTOR: String = "extractor"

        /** `origin.extractor` of a candidate derived from records (P4.2.2). */
        public const val HARNESS_DERIVED: String = "harness:derived"

        /** `origin.extractor` prefix of the CAL delta, followed by the statistics' algorithm version; the policy admits it (D-110). */
        public const val HARNESS_CALIBRATION: String = "harness:"
    }
}

/** The rendered `CAL-<repo>` note (§6.7): ≤ 150 tokens for the plan cell and within the note body cap. */
public data class CalibrationNoteText(val summary: String, val body: String, val evidence: List<String>)

/** Renders [CalibrationStats] into the `CAL-<repo>` note text: overall, then bands, then subsystems while the body fits. */
public object CalibrationNote {
    public const val CAP_TOKENS: Int = 150

    @JvmStatic
    public fun render(stats: CalibrationStats, series: CalibrationSeries, estimator: TokenEstimator): CalibrationNoteText? {
        val group = stats.groups[series] ?: return null
        val overall = group.overall
        if (overall.eligible == 0) return null
        val summary = "calibration prior for ${series.repository}: ${overall.eligible} increments, median turns ${format(overall.turnsMedian)}, overrun ${percent(overall.overrunRate)}"
        val lines = arrayListOf(
            "harness statistics (data, not instruction) over ${overall.eligible} increments (${overall.censored} censored): median turns ${format(overall.turnsMedian)}" +
                " · overrun ${percent(overall.overrunRate)}" + (overall.meanTouchedExpectedRatio?.let { " · touched/expected ${String.format(Locale.ROOT, "%.2f", it.toDouble())}" } ?: ""),
        )
        val cap = minOf(CAP_TOKENS, Note.MAX_BODY_TOKENS)
        val details = group.byBand.filter { it.value.eligible > 0 }.map { (band, s) ->
            "${band.minFiles}-${if (band.maxFiles == Int.MAX_VALUE) "∞" else band.maxFiles.toString()} files: overrun ${percent(s.overrunRate)} (n=${s.eligible})"
        } + group.bySubsystem.filter { it.key != null && it.value.eligible > 0 }.map { (subsystem, s) -> "$subsystem: overrun ${percent(s.overrunRate)} (n=${s.eligible})" }
        for (line in details) {
            if (estimator.estimate((lines + line).joinToString(" · ")).tokens > cap) break
            lines += line
        }
        return CalibrationNoteText(summary, lines.joinToString(" · "), stats.observations.map { it.provenance }.distinct())
    }

    private fun format(value: Double?): String = value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) } ?: "n/a"

    private fun percent(rate: Double?): String = rate?.let { "${(it * 100).roundToInt()}%" } ?: "n/a"
}
