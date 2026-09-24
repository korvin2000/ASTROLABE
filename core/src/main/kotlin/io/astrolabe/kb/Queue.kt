package io.astrolabe.kb

import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
public enum class QueueStatus(public val wire: String) {
    @SerialName("queued")
    Queued("queued"),

    @SerialName("admitted")
    Admitted("admitted"),

    @SerialName("rejected")
    Rejected("rejected"),
}

/** One candidate awaiting admission (§4.5 `queue/`): the note id, the batch that decided it and the lint verdict. */
@Serializable
public data class QueueEntry(
    val id: String,
    val noteId: String,
    val status: QueueStatus,
    /** The admission batch that admitted or rejected it; `null` while queued. */
    val batch: String? = null,
    val findings: List<LintFinding> = emptyList(),
    val decidedBy: String? = null,
    val reason: String? = null,
    /** The batch a rollback returned this entry from (a queued entry with a history, D-101). */
    val rolledBackFrom: String? = null,
)

/**
 * The admission queue (§4.5 `enqueue`): post-cell extraction and `kb.propose` put candidates here; only the curator
 * takes them out. The note itself is stored as `candidate` through the [KbWriter], so the queue row references it.
 */
public class Queue(private val store: Store, private val writer: KbWriter, private val idGen: IdGen, private val clock: Clock) {
    private val notes = Notes(store)

    /** Stores [candidate] as a `candidate` note (whatever status it claimed) and queues it; a queued id is re-queued as a revision. */
    @Synchronized
    public fun enqueue(candidate: Note, ids: Identities): QueueEntry {
        val existing = notes.get(candidate.id)
        if (existing != null && existing.status != NoteStatus.Candidate) throw NoteRefused("${candidate.id} is ${existing.status.wire}; a new note gets a new id")
        writer.write(candidate.copy(status = NoteStatus.Candidate), ids)
        val open = all().firstOrNull { it.noteId == candidate.id && it.status == QueueStatus.Queued }
        val entry = open ?: QueueEntry(idGen.next("q"), candidate.id, QueueStatus.Queued)
        save(entry, ids)
        return entry
    }

    public fun pending(): List<QueueEntry> = all().filter { it.status == QueueStatus.Queued }

    public fun all(): List<QueueEntry> = store.db.query("SELECT body FROM note_queue ORDER BY created_at, id") { decode(it.string("body")) }

    public fun entry(id: String): QueueEntry? = store.db.query("SELECT body FROM note_queue WHERE id = ?", id) { decode(it.string("body")) }.firstOrNull()

    public fun batch(batchId: String): List<QueueEntry> = all().filter { it.batch == batchId }

    internal fun save(entry: QueueEntry, ids: Identities) {
        val created = store.db.query("SELECT created_at AS c FROM note_queue WHERE id = ?", entry.id) { it.string("c") }.firstOrNull() ?: clock.instant().toString()
        store.db.tx { tx ->
            tx.execute(
                "INSERT OR REPLACE INTO note_queue (id, work_id, attempt_id, candidate_id, context_id, note_id, status, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.id, ids.work, ids.attempt, ids.candidate, ids.context, entry.noteId, entry.status.wire, Migrations.SCHEMA_VERSION, created, NOTE_JSON.encodeToString(QueueEntry.serializer(), entry),
            )
        }
    }

    private fun decode(body: String): QueueEntry = NOTE_JSON.decodeFromString(QueueEntry.serializer(), body)
}
