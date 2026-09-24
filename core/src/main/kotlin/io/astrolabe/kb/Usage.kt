package io.astrolabe.kb

import io.astrolabe.id.Identities
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant

/** What happened to an admitted note in a cell (§6.3 `(injected, cited-in-register?, outcome)`). */
@Serializable
public enum class UsageEvent { Injected, Cited }

@Serializable
public data class UsageRecord(val noteId: String, val event: UsageEvent, val outcome: String? = null)

public data class UsageRow(val noteId: String, val context: String, val event: UsageEvent, val outcome: String?, val at: Instant)

/**
 * The usage counters of §4.5 (`usage {injected, cited, last_cited}`), canonical in `note_usage`: one row per
 * `(note, cell, event)`, written by the injection path and the register-citation hook; the curator reads them for
 * pruning and promotion and copies the aggregate into the note's front matter when it revises the note.
 */
public class Usage(private val store: Store, private val clock: Clock) {
    @Synchronized
    public fun record(noteId: String, ids: Identities, event: UsageEvent, outcome: String? = null) {
        val now = clock.instant()
        val context = ids.context?.value ?: "campaign"
        store.db.tx { tx ->
            tx.execute(
                "INSERT OR IGNORE INTO note_usage (note_id, work_id, attempt_id, candidate_id, context_id, used_at, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                noteId, ids.work, ids.attempt, ids.candidate, context, "$now ${event.name.lowercase()}", Migrations.SCHEMA_VERSION, now, NOTE_JSON.encodeToString(UsageRecord.serializer(), UsageRecord(noteId, event, outcome)),
            )
        }
    }

    public fun rows(): List<UsageRow> = store.db.query("SELECT note_id, context_id, created_at, body FROM note_usage ORDER BY created_at, note_id, context_id") { row ->
        val record = NOTE_JSON.decodeFromString(UsageRecord.serializer(), row.string("body"))
        UsageRow(row.string("note_id"), row.string("context_id"), record.event, record.outcome, row.instant("created_at"))
    }

    /** The aggregate front-matter counters of [noteId]. */
    public fun of(noteId: String): NoteUsage = aggregate(rows().filter { it.noteId == noteId })

    /** Counters for every note that has any usage. */
    public fun all(): Map<String, NoteUsage> = rows().groupBy { it.noteId }.mapValues { aggregate(it.value) }

    private fun aggregate(rows: List<UsageRow>): NoteUsage = NoteUsage(
        injected = rows.count { it.event == UsageEvent.Injected },
        cited = rows.count { it.event == UsageEvent.Cited },
        lastCited = rows.filter { it.event == UsageEvent.Cited }.maxOfOrNull { it.at }?.toString(),
    )

    public companion object {
        private val NOTE_ID = Regex("\\b(ADR|CON|LES|PIT|BMAP|NEG|SKILL|CAL)-[A-Za-z0-9_-]+")

        /** The injected note ids [text] (a register render) cites; a pure function. */
        @JvmStatic
        public fun citations(text: String, injected: Set<String>): Set<String> =
            NOTE_ID.findAll(text).map { it.value }.filter { it in injected }.toSet()
    }
}
