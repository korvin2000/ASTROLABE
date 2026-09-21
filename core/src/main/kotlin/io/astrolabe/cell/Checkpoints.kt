package io.astrolabe.cell

import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.workset.Entry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Clock

/** Where a cell stands; every word but [Running] is a distinct exit (invariant 11), never a disguised completion. */
@Serializable
public enum class CellStatus { Running, Completed, Blocked, Partial, Failed, Cancelled }

/**
 * The persisted state of a cell at a turn boundary (§3.7 `reconcile_workspace_and_persist_checkpoint`):
 * what turn it reached, the tree it left (a stamp, never a claim), the register version in force, the
 * intents still open — unknown outcomes a resume must reconcile before any action that could duplicate an
 * effect (invariant 6) — and the acceptance-surface flags nobody has resolved. Written at the end of every
 * turn and on every exit path; the `turns` row keeps each one, the `cells` row the latest.
 */
@Serializable
public data class CellCheckpoint(
    val cell: ContextId,
    val increment: String,
    val turn: Int,
    val status: CellStatus,
    val registerVersion: Int,
    val stamp: CandidateId?,
    val knownFiles: Int,
    val knownTokens: Long,
    val openIntents: List<String>,
    val touched: List<String>,
    val unresolvedFlags: List<String>,
    val journalSeq: Long,
    val reason: String? = null,
) {
    init {
        require(turn >= 0) { "turn must be ≥ 0" }
        require(registerVersion >= 0) { "registerVersion must be ≥ 0" }
    }
}

/** Persistence seam for cell checkpoints; the cell runtime is the one writer of `cells`, `turns` and `workset_exports`. */
public interface Checkpoints {
    /** Saves the turn's checkpoint and the Workset export it goes with, atomically where the store allows. */
    public fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>)

    public fun latest(cell: ContextId): CellCheckpoint?

    /** Every turn checkpoint of [cell], in turn order. */
    public fun turns(cell: ContextId): List<CellCheckpoint>

    public fun export(cell: ContextId, turn: Int): List<Entry>?
}

public class InMemoryCheckpoints : Checkpoints {
    private val cells = LinkedHashMap<ContextId, CellCheckpoint>()
    private val turns = LinkedHashMap<Pair<ContextId, Int>, CellCheckpoint>()
    private val exports = LinkedHashMap<Pair<ContextId, Int>, List<Entry>>()

    @Synchronized
    override fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>) {
        cells[checkpoint.cell] = checkpoint
        turns[checkpoint.cell to checkpoint.turn] = checkpoint
        exports[checkpoint.cell to checkpoint.turn] = export.toList()
    }

    @Synchronized
    override fun latest(cell: ContextId): CellCheckpoint? = cells[cell]

    @Synchronized
    override fun turns(cell: ContextId): List<CellCheckpoint> = turns.filterKeys { it.first == cell }.values.sortedBy { it.turn }

    @Synchronized
    override fun export(cell: ContextId, turn: Int): List<Entry>? = exports[cell to turn]
}

/**
 * Checkpoints in the store: the `cells` row is the cell's latest state, one `turns` row per turn boundary and
 * one `workset_exports` row per turn, all three in one transaction so a crash leaves the previous checkpoint
 * whole rather than a turn without its export.
 */
public class SqliteCheckpoints(private val store: Store, private val clock: Clock) : Checkpoints {
    override fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>): Unit = store.db.tx { tx ->
        val body = JSON.encodeToString(CellCheckpoint.serializer(), checkpoint)
        val now = clock.instant()
        val candidate = checkpoint.stamp ?: ids.candidate
        tx.execute(
            "INSERT OR REPLACE INTO cells (work_id, attempt_id, candidate_id, context_id, increment_id, status, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ids.work, ids.attempt, candidate, checkpoint.cell, checkpoint.increment, checkpoint.status.name, Migrations.SCHEMA_VERSION, now, body,
        )
        tx.execute(
            "INSERT OR REPLACE INTO turns (work_id, attempt_id, candidate_id, context_id, turn, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            ids.work, ids.attempt, candidate, checkpoint.cell, checkpoint.turn, Migrations.SCHEMA_VERSION, now, body,
        )
        tx.execute(
            "INSERT OR REPLACE INTO workset_exports (id, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            exportId(checkpoint.turn), ids.work, ids.attempt, candidate, checkpoint.cell, Migrations.SCHEMA_VERSION, now, JSON.encodeToString(ENTRIES, export),
        )
    }

    override fun latest(cell: ContextId): CellCheckpoint? =
        store.db.query("SELECT body FROM cells WHERE context_id = ?", cell) { decode(it.string("body")) }.firstOrNull()

    override fun turns(cell: ContextId): List<CellCheckpoint> =
        store.db.query("SELECT body FROM turns WHERE context_id = ? ORDER BY turn", cell) { decode(it.string("body")) }

    override fun export(cell: ContextId, turn: Int): List<Entry>? =
        store.db.query("SELECT body FROM workset_exports WHERE context_id = ? AND id = ?", cell, exportId(turn)) { JSON.decodeFromString(ENTRIES, it.string("body")) }.firstOrNull()

    private fun exportId(turn: Int): String = "turn-$turn"

    private fun decode(body: String): CellCheckpoint = JSON.decodeFromString(CellCheckpoint.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
        val ENTRIES = ListSerializer(Entry.serializer())
    }
}
