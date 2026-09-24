package io.astrolabe.kb

import io.astrolabe.auth.Redaction
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Authority
import io.astrolabe.event.Events
import io.astrolabe.event.Proposer
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.Store
import java.nio.file.Path
import java.time.Clock

/** One versioned admission batch (§4.5): what it admitted, rejected and left waiting, and the index files it rewrote. */
public data class AdmissionBatch(
    val id: String,
    val admitted: List<String>,
    val rejected: Map<String, List<LintFinding>>,
    val waiting: Map<String, String>,
    val indexWritten: List<String>,
)

/** The curator's recheck of stale notes after an invalidation (§4.4, P4.1.2). */
public data class Recheck(val readmitted: List<String>, val deprecated: List<String>, val stale: List<String>)

/** A recurring `LES`/`PIT` promoted to a proposed task (§12.2): never auto-committed; the note became a pointer. */
public data class Promotion(val noteId: String, val pointerId: String, val task: String)

/**
 * The curator (§4.5, §12.1): the one writer of admission. It lints queued candidates ([Lint]), decides by the
 * [AdmissionPolicy] or a person's [Authority.resolve], and adds, supersedes or deprecates notes — never rewriting a
 * body in place. Every admission is a batch whose entries roll back independently of code (only the store and the
 * derived `kb/index` move); the index is regenerated after every batch. All writes are serialized here.
 */
public class Curator @JvmOverloads constructor(
    private val store: Store,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val clock: Clock,
    private val resolver: KbResolver = KbResolver.ALL,
    private val redaction: Redaction = Redaction(),
    private val events: Events? = null,
) {
    private val writer = KbWriter(store, estimator, clock)
    private val notes = Notes(store)
    public val queue: Queue = Queue(store, writer, idGen, clock)
    private val usage = Usage(store, clock)
    private val indexDir: Path = store.layout.kb.resolve("index")

    /** Lints every queued candidate and applies the [mode]'s policy: interactive candidates wait for [admitWith]. */
    @Synchronized
    public fun admit(ids: Identities, mode: AdmissionMode): AdmissionBatch = batch(ids) { entry, note ->
        AdmissionPolicy.decide(note, Lint.check(note, notes.all(), resolver, redaction), mode)
    }

    /**
     * Interactive admission: every lint-passing candidate is put to [authority] as a proposal (§12.2 ADR sign-off);
     * an accepted answer admits it (`admitted_by`, and `signed_by` for an ADR, name the authority), a rejection
     * rejects it and a pending answer leaves it queued.
     */
    public suspend fun admitWith(ids: Identities, authority: Authority, contractRevision: Int): AdmissionBatch = batch(ids) { entry, note ->
        val findings = Lint.check(note, notes.all(), resolver, redaction)
        if (findings.isNotEmpty()) {
            AdmissionPolicy.decide(note, findings, AdmissionMode.Interactive)
        } else {
            val proposal = AmendmentProposal(entry.id, contractRevision, ids, Proposer.Model, "admit ${note.id} (${note.kind}): ${note.summary}", "knowledge admission", weakening = false)
            val resolution = authority.resolve(proposal)
            when (resolution.outcome) {
                ResolutionOutcome.Accepted -> AdmissionDecision.Admit(resolution.byAuthority, signedBy = resolution.byAuthority.takeIf { note.kind == NoteKind.ADR })
                ResolutionOutcome.Rejected -> AdmissionDecision.Reject(emptyList(), resolution.reason ?: "rejected by ${resolution.byAuthority}")
                ResolutionOutcome.Pending -> AdmissionDecision.Wait(resolution.reason ?: "pending ${resolution.byAuthority}")
            }
        }
    }

    /** Returns every note of [batchId] to the queue as a candidate; code is untouched, the index is regenerated. */
    @Synchronized
    public fun rollback(batchId: String, ids: Identities): List<String> {
        val rolled = ArrayList<String>()
        for (entry in queue.batch(batchId)) {
            if (entry.status == QueueStatus.Admitted) {
                val note = checkNotNull(notes.get(entry.noteId))
                writer.write(note.copy(status = NoteStatus.Candidate, signedBy = null, origin = note.origin.copy(admittedBy = null)), ids)
                rolled += entry.noteId
            }
            queue.save(entry.copy(status = QueueStatus.Queued, batch = null, decidedBy = null, reason = "rolled back $batchId", rolledBackFrom = batchId), ids)
        }
        regenerate()
        return rolled
    }

    /** Replaces [oldId] by a linted [replacement] that names it in `supersedes`; the old note stays, marked superseded. */
    @Synchronized
    public fun supersede(oldId: String, replacement: Note, ids: Identities, by: String): Int {
        val findings = Lint.check(replacement, notes.all(), resolver, redaction)
        if (findings.isNotEmpty()) throw NoteRefused("${replacement.id}: " + findings.joinToString("; ") { "${it.rule}: ${it.detail}" })
        val revision = writer.supersede(oldId, replacement.copy(status = NoteStatus.Admitted, origin = replacement.origin.copy(admittedBy = by)), ids)
        events?.emit(AgentEvent.Kb.Admitted(ids, replacement.id))
        regenerate()
        return revision
    }

    @Synchronized
    public fun deprecate(id: String, ids: Identities): Int = writer.setStatus(id, NoteStatus.Deprecated, ids).also { regenerate() }

    /**
     * §4.4 curator recheck after the project horizon marked notes stale: a note whose anchors resolve again at their
     * recorded versions and whose note dependencies are still admitted is readmitted at [stamp]; one depending on a
     * superseded or deprecated note is deprecated; the rest stay stale.
     */
    @Synchronized
    public fun recheck(ids: Identities, currentVersion: (String) -> FileVersion?, stamp: String?): Recheck {
        val all = notes.all()
        val byId = all.associateBy { it.id }
        val readmitted = ArrayList<String>()
        val deprecated = ArrayList<String>()
        val stale = ArrayList<String>()
        for (note in all.filter { it.status == NoteStatus.Stale }) {
            val gone = note.validity.dependsOn.mapNotNull { byId[it.substringBeforeLast('@')] }.filter { it.status == NoteStatus.Superseded || it.status == NoteStatus.Deprecated }
            val anchorsCurrent = note.anchors.all { a -> a.version == null || currentVersion(a.path)?.digest?.hex?.startsWith(a.version) == true }
            val dependenciesCurrent = note.validity.dependsOn.all { dep -> byId[dep.substringBeforeLast('@')]?.let { it.status == NoteStatus.Admitted } ?: true }
            when {
                gone.isNotEmpty() -> {
                    writer.setStatus(note.id, NoteStatus.Deprecated, ids)
                    events?.emit(AgentEvent.Kb.Invalidated(ids, note.id, "depends on ${gone.joinToString(", ") { "${it.id} (${it.status.wire})" }}"))
                    deprecated += note.id
                }
                anchorsCurrent && dependenciesCurrent -> {
                    writer.write(note.copy(status = NoteStatus.Admitted, validity = note.validity.copy(lastValidated = stamp)), ids)
                    readmitted += note.id
                }
                else -> stale += note.id
            }
        }
        if (readmitted.isNotEmpty() || deprecated.isNotEmpty()) regenerate()
        return Recheck(readmitted, deprecated, stale)
    }

    /**
     * Usage-aware pruning (§4.5): an admitted lesson-class note injected at least [minInjected] times and never cited
     * decays — its confidence halves per pass (unset counts as 0.5) — and is deprecated below [floor]. `CON` and
     * `ADR` never decay: they are authority, not advice.
     */
    @Synchronized
    @JvmOverloads
    public fun prune(ids: Identities, minInjected: Int = 5, floor: Double = 0.25): List<String> {
        val counters = usage.all()
        val touched = ArrayList<String>()
        for (note in notes.all()) {
            if (note.status != NoteStatus.Admitted || note.kind !in DECAYING) continue
            val use = counters[note.id] ?: continue
            if (use.injected < minInjected || use.cited > 0) continue
            val decayed = (note.confidence ?: 0.5) / 2
            if (decayed < floor) {
                writer.write(note.copy(status = NoteStatus.Deprecated, confidence = decayed, usage = use), ids)
                events?.emit(AgentEvent.Kb.Invalidated(ids, note.id, "pruned: injected ${use.injected} times, never cited"))
            } else {
                writer.write(note.copy(confidence = decayed, usage = use), ids)
            }
            touched += note.id
        }
        if (touched.isNotEmpty()) regenerate()
        return touched
    }

    /**
     * Executable promotion (§12.2): an admitted `LES`/`PIT` cited at least [minCited] times becomes a proposed task
     * for a test, linter rule or schema check — returned, never committed — and the note is superseded by a pointer.
     */
    @Synchronized
    @JvmOverloads
    public fun promote(ids: Identities, minCited: Int = 3): List<Promotion> {
        val counters = usage.all()
        val out = ArrayList<Promotion>()
        for (note in notes.all()) {
            if (note.status != NoteStatus.Admitted || (note.kind != NoteKind.LES && note.kind != NoteKind.PIT) || note.id.endsWith(POINTER_SUFFIX)) continue
            val use = counters[note.id] ?: continue
            if (use.cited < minCited) continue
            val task = "promote ${note.id}: add a test, linter rule or schema check for '${note.summary}' (cited ${use.cited} times)"
            val pointer = note.copy(
                id = note.id + POINTER_SUFFIX, status = NoteStatus.Admitted, body = "Promoted to a proposed task: $task. The evidence stays in ${note.id} (superseded).",
                supersedes = note.id, usage = NoteUsage(), origin = note.origin.copy(admittedBy = "policy:promote"),
            )
            writer.supersede(note.id, pointer, ids)
            out += Promotion(note.id, pointer.id, task)
        }
        if (out.isNotEmpty()) regenerate()
        return out
    }

    /** Deterministic index regeneration under `kb/index/` (§4.5); returns the files written. */
    @Synchronized
    public fun regenerate(): List<String> = KbIndex.regenerate(notes.all(), estimator, indexDir)

    private inline fun batch(ids: Identities, decide: (QueueEntry, Note) -> AdmissionDecision): AdmissionBatch {
        val batchId = idGen.next("batch")
        val admitted = ArrayList<String>()
        val rejected = LinkedHashMap<String, List<LintFinding>>()
        val waiting = LinkedHashMap<String, String>()
        for (entry in queue.pending()) {
            val note = notes.get(entry.noteId) ?: continue
            when (val decision = decide(entry, note)) {
                is AdmissionDecision.Admit -> {
                    val active = note.copy(status = NoteStatus.Admitted, signedBy = decision.signedBy ?: note.signedBy, origin = note.origin.copy(admittedBy = decision.admittedBy))
                    // A delta names the note it replaces (§12.1 supersession): admitting it marks that one superseded, never rewrites it.
                    val replaced = note.supersedes?.takeIf { notes.get(it) != null }
                    if (replaced != null) writer.supersede(replaced, active, ids) else writer.write(active, ids)
                    queue.save(entry.copy(status = QueueStatus.Admitted, batch = batchId, decidedBy = decision.admittedBy, findings = emptyList(), reason = null), ids)
                    events?.emit(AgentEvent.Kb.Admitted(ids, note.id))
                    admitted += note.id
                }
                is AdmissionDecision.Reject -> {
                    writer.setStatus(note.id, NoteStatus.Rejected, ids)
                    queue.save(entry.copy(status = QueueStatus.Rejected, batch = batchId, findings = decision.findings, reason = decision.reason), ids)
                    rejected[note.id] = decision.findings
                }
                is AdmissionDecision.Wait -> {
                    queue.save(entry.copy(reason = decision.reason), ids)
                    waiting[note.id] = decision.reason
                }
            }
        }
        return AdmissionBatch(batchId, admitted, rejected, waiting, regenerate())
    }

    private companion object {
        const val POINTER_SUFFIX = "-pointer"
        val DECAYING = setOf(NoteKind.LES, NoteKind.PIT, NoteKind.NEG, NoteKind.BMAP, NoteKind.SKILL)
    }
}
