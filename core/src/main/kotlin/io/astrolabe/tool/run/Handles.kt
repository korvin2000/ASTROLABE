package io.astrolabe.tool.run

import io.astrolabe.id.Identities
import io.astrolabe.os.Proc
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * A persisted background run (§5.4 `bg=true`, D-43): the backend [proc] carries the owner token, identity
 * key, log path and cursor that let a later poll — in this process or after a restart — reattach to the
 * same process and never launch a duplicate (FX-22). [status] is the last observed process status wire
 * name; [cursor] is where the next poll continues.
 */
@Serializable
public data class Handle(
    val handleId: String,
    val ids: Identities,
    val actionId: String,
    val alias: String,
    val argv: List<String>,
    val shell: Boolean,
    val cwd: String?,
    val proc: Proc,
    val status: String,
    val cursor: Long,
    /** The candidate identity before dispatch; the diff is taken when the process ends. */
    val stampBefore: String,
) {
    init {
        require(handleId.isNotBlank() && actionId.isNotBlank()) { "handle needs ids" }
        require(cursor >= 0) { "cursor must be ≥ 0" }
    }
}

/** Persistence seam for background handles; durable independently of the calling coroutine (D-43). */
public interface Handles {
    public fun save(handle: Handle)

    public fun get(handleId: String): Handle?

    /** Handles whose last observed status was not terminal: the reconciliation input at open time. */
    public fun open(): List<Handle>
}

public class InMemoryHandles : Handles {
    private val rows = LinkedHashMap<String, Handle>()

    @Synchronized
    override fun save(handle: Handle) {
        rows[handle.handleId] = handle
    }

    @Synchronized
    override fun get(handleId: String): Handle? = rows[handleId]

    @Synchronized
    override fun open(): List<Handle> = rows.values.filter { it.status == RUNNING }

    private companion object {
        const val RUNNING = "running"
    }
}

/** Handles in the store (`handles` table; the runner is the writer). */
public class SqliteHandles(private val store: Store, private val clock: Clock) : Handles {
    override fun save(handle: Handle): Unit = store.db.tx { tx ->
        val body = JSON.encodeToString(Handle.serializer(), handle)
        val exists = tx.query("SELECT handle_id FROM handles WHERE handle_id = ?", handle.handleId) { it.string("handle_id") }.isNotEmpty()
        if (exists) {
            tx.execute("UPDATE handles SET status = ?, cursor = ?, body = ? WHERE handle_id = ?", handle.status, handle.cursor, body, handle.handleId)
        } else {
            tx.execute(
                "INSERT INTO handles (handle_id, work_id, attempt_id, candidate_id, context_id, status, log_path, cursor, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                handle.handleId, handle.ids.work, handle.ids.attempt, handle.ids.candidate, handle.ids.context, handle.status, handle.proc.logPath, handle.cursor,
                Migrations.SCHEMA_VERSION, clock.instant(), body,
            )
        }
    }

    override fun get(handleId: String): Handle? =
        store.db.query("SELECT body FROM handles WHERE handle_id = ?", handleId) { decode(it.string("body")) }.firstOrNull()

    override fun open(): List<Handle> =
        store.db.query("SELECT body FROM handles WHERE status = ? ORDER BY created_at, rowid", "running") { decode(it.string("body")) }

    private fun decode(body: String) = JSON.decodeFromString(Handle.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
