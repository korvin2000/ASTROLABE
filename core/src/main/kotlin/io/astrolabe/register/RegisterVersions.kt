package io.astrolabe.register

import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/** Persistence of the register per applied patch (§5.2 `register_versions`): typed records, never Markdown (D-24). */
public interface RegisterVersions {
    public fun save(ids: Identities, register: Register)

    public fun latest(context: ContextId): Register?

    public fun get(context: ContextId, version: Int): Register?
}

public class InMemoryRegisterVersions : RegisterVersions {
    private val rows = LinkedHashMap<Pair<ContextId, Int>, Register>()

    @Synchronized
    override fun save(ids: Identities, register: Register) {
        rows[register.cell to register.version] = register
    }

    @Synchronized
    override fun latest(context: ContextId): Register? = rows.filterKeys { it.first == context }.values.maxByOrNull { it.version }

    @Synchronized
    override fun get(context: ContextId, version: Int): Register? = rows[context to version]
}

/** Register versions in the store (`register_versions` table, keyed by context and version). */
public class SqliteRegisterVersions(private val store: Store, private val clock: Clock) : RegisterVersions {
    override fun save(ids: Identities, register: Register): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT OR REPLACE INTO register_versions (work_id, attempt_id, candidate_id, context_id, version, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ids.work, ids.attempt, ids.candidate, register.cell, register.version, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(Register.serializer(), register),
        )
    }

    override fun latest(context: ContextId): Register? =
        store.db.query("SELECT body FROM register_versions WHERE context_id = ? ORDER BY version DESC LIMIT 1", context) { decode(it.string("body")) }.firstOrNull()

    override fun get(context: ContextId, version: Int): Register? =
        store.db.query("SELECT body FROM register_versions WHERE context_id = ? AND version = ?", context, version) { decode(it.string("body")) }.firstOrNull()

    private fun decode(body: String) = JSON.decodeFromString(Register.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
