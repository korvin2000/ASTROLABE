package io.astrolabe.evidence

import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.store.Tx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.Instant

/** Journal event kinds (§4.3). */
@Serializable
public enum class JournalKind {
    @SerialName("call")
    Call,

    @SerialName("result")
    Result,

    @SerialName("edit-intent")
    EditIntent,

    @SerialName("edit-outcome")
    EditOutcome,

    @SerialName("check")
    Check,

    @SerialName("nudge")
    Nudge,

    @SerialName("boundary")
    Boundary,

    @SerialName("intent")
    Intent,

    @SerialName("reconcile")
    Reconcile,
}

/**
 * One append-only journal event (§4.3). [text] is the searchable view (`look(find, in="store")`); bodies
 * larger than a view live in blobs and are referenced through [refs].
 */
@Serializable
public data class JournalEvent(
    val eventId: String,
    val ids: Identities,
    val turn: Int?,
    val kind: JournalKind,
    val argsDigest: Digest? = null,
    val refs: List<String> = emptyList(),
    val text: String = "",
    val payload: JsonElement? = null,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    /** Assigned by the journal on append: monotone per work. */
    val seq: Long = 0,
) {
    init {
        require(eventId.isNotBlank()) { "journal event needs an id" }
    }
}

public data class JournalScope(val work: WorkId, val context: ContextId? = null, val kinds: Set<JournalKind>? = null)

/** Search hits with the completeness of the declared scope (L8: an empty scoped search is not absence). */
public data class JournalHits(val events: List<JournalEvent>, val scope: JournalScope, val complete: Boolean)

/**
 * The append-only journal (§4.3, TODO P1.4.1): no update or delete exists on this API. Ordering: `seq` is
 * assigned inside the append transaction, so two events of one work never share or reorder sequence numbers.
 */
public class Journal(private val store: Store, private val clock: Clock) {
    public fun append(event: JournalEvent): JournalEvent = store.db.tx { tx -> append(tx, event) }

    /** [append] inside [tx], so the event commits with the other rows the transaction writes (C14: the contract budget). */
    internal fun append(tx: Tx, event: JournalEvent): JournalEvent {
        val seq = tx.query("SELECT coalesce(max(seq), 0) AS s FROM journal WHERE work_id = ?", event.ids.work) { it.long("s") }.first() + 1
        val stamped = event.copy(seq = seq)
        tx.execute(
            "INSERT INTO journal (event_id, work_id, attempt_id, candidate_id, context_id, seq, turn, kind, schema_version, created_at, body) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            stamped.eventId, stamped.ids.work, stamped.ids.attempt, stamped.ids.candidate, stamped.ids.context,
            seq, stamped.turn, stamped.kind.name, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(JournalEvent.serializer(), stamped),
        )
        return stamped
    }

    public fun get(eventId: String): JournalEvent? =
        store.db.query("SELECT body FROM journal WHERE event_id = ?", eventId) { decode(it.string("body")) }.firstOrNull()

    /** Events of a work in sequence order, optionally limited to one context and a set of kinds. */
    public fun events(scope: JournalScope): List<JournalEvent> {
        if (scope.kinds?.isEmpty() == true) return emptyList()
        val (where, params) = where(scope)
        return store.db.query("SELECT body FROM journal WHERE $where ORDER BY seq", *params.toTypedArray()) {
            decode(it.string("body"))
        }
    }

    /**
     * Case-insensitive substring search over the text views inside [scope]. The scan is exhaustive over the
     * scope, so `complete` is true unless [limit] cut the result; either way the scope is named in the hits.
     */
    public fun search(query: String, scope: JournalScope, limit: Int = 50): JournalHits {
        require(limit > 0) { "limit must be positive" }
        if (scope.kinds?.isEmpty() == true) return JournalHits(emptyList(), scope, complete = true)
        val needle = query.lowercase()
        val (where, params) = where(scope)
        val stop = lastSeq(scope.work)
        val matches = ArrayList<JournalEvent>(minOf(limit, PAGE_SIZE))
        var after = 0L
        while (after < stop) {
            val page = store.db.query(
                "SELECT seq, event_id, json_extract(body, '$.text') AS view_text, json_extract(body, '$.refs') AS view_refs " +
                    "FROM journal WHERE $where AND seq > ? AND seq <= ? ORDER BY seq LIMIT ?",
                *(params + listOf(after, stop, PAGE_SIZE)).toTypedArray(),
            ) { row ->
                SearchView(row.long("seq"), row.string("event_id"), row.stringOrNull("view_text").orEmpty(),
                    row.stringOrNull("view_refs")?.let { JSON.decodeFromString<List<String>>(it) }.orEmpty())
            }
            if (page.isEmpty()) break
            for (view in page) {
                if (view.text.lowercase().contains(needle) || view.refs.any { it.lowercase().contains(needle) }) {
                    matches += requireNotNull(get(view.eventId))
                    if (matches.size > limit) return JournalHits(matches.take(limit), scope, complete = false)
                }
            }
            after = page.last().seq
            if (page.size < PAGE_SIZE) break
        }
        return JournalHits(matches, scope, complete = true)
    }

    public fun lastSeq(work: WorkId): Long =
        store.db.query("SELECT coalesce(max(seq), 0) AS s FROM journal WHERE work_id = ?", work) { it.long("s") }.first()

    private fun where(scope: JournalScope): Pair<String, List<Any>> {
        val terms = arrayListOf("work_id = ?")
        val params = arrayListOf<Any>(scope.work)
        scope.context?.let { terms += "context_id = ?"; params += it }
        scope.kinds?.let { kinds ->
            terms += "kind IN (${List(kinds.size) { "?" }.joinToString(",")})"
            params.addAll(kinds.sortedBy { it.ordinal }.map { it.name })
        }
        return terms.joinToString(" AND ") to params
    }

    private data class SearchView(val seq: Long, val eventId: String, val text: String, val refs: List<String>)

    private fun decode(body: String): JournalEvent = JSON.decodeFromString(JournalEvent.serializer(), body)

    private companion object {
        const val PAGE_SIZE = 128
        val JSON = Json { encodeDefaults = true }
    }
}
