package io.astrolabe.evidence

import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/** Intents in the store (`intents` table; the runner is the writer). */
public class SqliteIntentJournal(private val store: Store, private val clock: Clock) : IntentJournal {
    override fun record(intent: Intent): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO intents (intent_id, work_id, attempt_id, candidate_id, context_id, action_id, status, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            intent.intentId, intent.ids.work, intent.ids.attempt, intent.ids.candidate, intent.ids.context, intent.actionId,
            intent.status.name, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(Intent.serializer(), intent),
        )
    }

    override fun update(intentId: String, status: IntentStatus): Unit = store.db.tx { tx ->
        val current = tx.query("SELECT body FROM intents WHERE intent_id = ?", intentId) { decode(it.string("body")) }.firstOrNull()
            ?: throw IllegalArgumentException("unknown intent $intentId")
        require(status.ordinal >= current.status.ordinal || status == IntentStatus.Unknown) {
            "intent $intentId cannot move from ${current.status} to $status"
        }
        val next = current.copy(status = status)
        tx.execute("UPDATE intents SET status = ?, body = ? WHERE intent_id = ?", status.name, JSON.encodeToString(Intent.serializer(), next), intentId)
    }

    override fun open(): List<Intent> = store.db.query(
        "SELECT body FROM intents WHERE status != ? ORDER BY created_at, rowid",
        IntentStatus.Committed.name,
    ) { decode(it.string("body")) }

    override fun get(intentId: String): Intent? =
        store.db.query("SELECT body FROM intents WHERE intent_id = ?", intentId) { decode(it.string("body")) }.firstOrNull()

    private fun decode(body: String) = JSON.decodeFromString(Intent.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}

/** Campaign-global aliases in the store (`aliases` table, D-46): allocated inside one transaction, never recycled. */
public class SqliteAliases(private val store: Store, private val clock: Clock) : Aliases {
    override fun allocate(work: WorkId, canonicalId: String, kind: String, context: ContextId?, workspace: WorkspaceId?): Alias = store.db.tx { tx ->
        val number = tx.query("SELECT coalesce(max(alias_no), 0) AS n FROM aliases WHERE work_id = ?", work) { it.long("n").toInt() }.first() + 1
        val alias = Alias(work, number, canonicalId, kind, context, workspace)
        tx.execute(
            "INSERT INTO aliases (work_id, attempt_id, candidate_id, context_id, alias_no, canonical_id, kind, workspace_id, schema_version, created_at, body) VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)",
            work, ATTEMPT_UNSET, context, number, canonicalId, kind, workspace?.value, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(Alias.serializer(), alias),
        )
        alias
    }

    override fun resolve(work: WorkId, number: Int): Alias? =
        store.db.query("SELECT body FROM aliases WHERE work_id = ? AND alias_no = ?", work, number) { decode(it.string("body")) }.firstOrNull()

    override fun byCanonical(work: WorkId, canonicalId: String): Alias? =
        store.db.query("SELECT body FROM aliases WHERE work_id = ? AND canonical_id = ? ORDER BY alias_no", work, canonicalId) { decode(it.string("body")) }.firstOrNull()

    private fun decode(body: String) = JSON.decodeFromString(Alias.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }

        /** Aliases outlive attempts (never recycled on resume), so the row's attempt column is a placeholder. */
        const val ATTEMPT_UNSET = "campaign"
    }
}
