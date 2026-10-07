package io.astrolabe.cell

import io.astrolabe.atlas.ChangedDefinition
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.DefinitionChange
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.register.Register
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.store.Tx
import io.astrolabe.verify.AcceptanceSurface
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.verify.Verdict
import io.astrolabe.workset.Entry
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    /** Pressure rebuilds performed in this cell so far (§5.8); each is a decomposition failure (§3.8). */
    val rebuilds: Int = 0,
    /** The contract's requests the cell's latest turn was rendered with (D-411, T-36): messages it has consumed. */
    val requestsSeen: Int = 0,
) {
    init {
        require(turn >= 0) { "turn must be ≥ 0" }
        require(registerVersion >= 0) { "registerVersion must be ≥ 0" }
    }
}

/** One net change as a [CellPacket] keeps it: the path and its versions (diffs stay in the store). */
@Serializable
public data class PacketChange(val path: String, val kind: TouchKind, val before: FileVersion?, val after: FileVersion?, val origin: ChangeOrigin) {
    public fun change(): Change = Change(path, kind, before, after, origin)

    public companion object {
        @JvmStatic
        public fun of(change: Change): PacketChange = PacketChange(change.path, change.kind, change.before, change.after, change.origin)
    }
}

/**
 * The `packets` row of a cell's end (task-workflow §4.1, W9): written by the cell's settle in the transaction of its
 * terminal checkpoint and end export, and built from that checkpoint — never from later state — so a boundary reads the
 * previous cell's summary by its id from the store, in this process or after a reopen, and both give the same carry.
 * [register] is the register at [registerVersion]: its decisions, dead ends and open items travel verbatim. [handoff] is set
 * exactly for a handoff end (A-D.6) and is not encoded otherwise, so the row of every other end keeps its bytes.
 */
@Serializable
public data class CellPacket(
    val cell: ContextId,
    val increment: String,
    val role: String,
    val status: PacketStatus,
    val reason: String?,
    val registerVersion: Int,
    val contractVersion: Int,
    val base: CandidateId?,
    val stamp: CandidateId?,
    val envId: Digest?,
    val register: Register,
    val changes: List<PacketChange>,
    val receipts: List<String>,
    val gaps: List<String>,
    val notesToPersist: List<NoteCandidate> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val handoff: PacketHandoff? = null,
) {
    public companion object {
        public const val KIND: String = "cell_packet"

        /** The row of [packet], which the cell built over [checkpoint]; [handoff] for a handoff end. */
        @JvmStatic
        @JvmOverloads
        public fun of(packet: ResultPacket, checkpoint: CellCheckpoint, handoff: PacketHandoff? = null): CellPacket = CellPacket(
            checkNotNull(packet.ids.context), packet.increment, packet.role, packet.status, packet.reason, checkpoint.registerVersion,
            packet.contractVersion, packet.base?.stamp, packet.stamp, packet.envId, packet.register, packet.changes.map(PacketChange::of),
            packet.receipts, packet.gaps, packet.claims.notesToPersist, packet.claims.openQuestions, handoff,
        )
    }
}

/**
 * A-D.6: a handoff end as the cell's terminal packet keeps it — the typed cause, the hint and the turns taken, and the
 * completion obligations its epoch inherits: the test-integrity [flags] without a verdict (the epoch's completion obtains
 * its own) and the unresolved changed public definitions ([impact]). Written in the transaction of the terminal
 * checkpoint, so a controller that stops before its `returned_handoff` record leaves them durable all the same.
 */
@Serializable
public data class PacketHandoff(
    val cause: HandoffCause,
    val hint: String,
    val turns: Int,
    val flags: List<PacketFlag> = emptyList(),
    val impact: List<PacketImpact> = emptyList(),
)

/** A [TestIntegrityFlag] as a [PacketHandoff] keeps it. */
@Serializable
public data class PacketFlag(
    val path: String,
    val surface: AcceptanceSurface,
    val cause: String,
    val requiredChecks: List<String>,
    val kind: String,
    val reason: String?,
    val verdict: Verdict?,
    val originalObligation: String?,
    val humanOnly: Boolean = false,
) {
    public fun flag(): TestIntegrityFlag = TestIntegrityFlag(path, surface, cause, requiredChecks, kind, reason, verdict, originalObligation, humanOnly)

    public companion object {
        @JvmStatic
        public fun of(flag: TestIntegrityFlag): PacketFlag =
            PacketFlag(flag.path, flag.surface, flag.cause, flag.requiredChecks, flag.kind, flag.reason, flag.verdict, flag.originalObligation, flag.humanOnly)
    }
}

/** An unresolved [ImpactNudge] as a [PacketHandoff] keeps it. */
@Serializable
public data class PacketImpact(
    val path: String,
    val symbol: String,
    val kind: DeclarationKind,
    val change: DefinitionChange,
    val public: Boolean,
    val references: Int,
    val turn: Int,
) {
    public fun nudge(): ImpactNudge = ImpactNudge(ChangedDefinition(path, symbol, kind, change, public), references, turn)

    public companion object {
        @JvmStatic
        public fun of(nudge: ImpactNudge): PacketImpact =
            nudge.definition.let { d -> PacketImpact(d.path, d.symbol, d.kind, d.change, d.public, nudge.references, nudge.turn) }
    }
}

/**
 * Persistence seam for cell checkpoints; the cell runtime is the one writer of `cells`, `turns`, `workset_exports` and
 * its `packets` rows ([CellPacket.KIND]).
 */
public interface Checkpoints {
    /** Saves the turn's checkpoint and the Workset export it goes with, atomically where the store allows. */
    public fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>)

    /**
     * The cell's end (task-workflow §4.1): its terminal [checkpoint], its end [export] and its [packet], in one transaction
     * where the store allows — a crash leaves all three or the previous checkpoint, never an ended cell without its packet.
     */
    public fun settle(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>, packet: CellPacket) {
        save(ids, checkpoint, export)
    }

    /** The packet [cell] settled with; `null` before its end or when the row is missing (a store of the old two-step). */
    public fun packet(cell: ContextId): CellPacket? = null

    public fun latest(cell: ContextId): CellCheckpoint?

    /** Every turn checkpoint of [cell], in turn order. */
    public fun turns(cell: ContextId): List<CellCheckpoint>

    public fun export(cell: ContextId, turn: Int): List<Entry>?
}

public class InMemoryCheckpoints : Checkpoints {
    private val cells = LinkedHashMap<ContextId, CellCheckpoint>()
    private val turns = LinkedHashMap<Pair<ContextId, Int>, CellCheckpoint>()
    private val exports = LinkedHashMap<Pair<ContextId, Int>, List<Entry>>()
    private val packets = LinkedHashMap<ContextId, CellPacket>()

    @Synchronized
    override fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>) {
        cells[checkpoint.cell] = checkpoint
        turns[checkpoint.cell to checkpoint.turn] = checkpoint
        exports[checkpoint.cell to checkpoint.turn] = export.toList()
    }

    @Synchronized
    override fun settle(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>, packet: CellPacket) {
        save(ids, checkpoint, export)
        packets[checkpoint.cell] = packet
    }

    @Synchronized
    override fun packet(cell: ContextId): CellPacket? = packets[cell]

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
    override fun save(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>): Unit = store.db.tx { tx -> write(tx, ids, checkpoint, export) }

    override fun settle(ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>, packet: CellPacket): Unit = store.db.tx { tx ->
        write(tx, ids, checkpoint, export)
        tx.execute(
            "INSERT OR REPLACE INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "packet-${checkpoint.cell.value}", ids.work, ids.attempt, checkpoint.stamp ?: ids.candidate, checkpoint.cell, CellPacket.KIND,
            Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(CellPacket.serializer(), packet),
        )
    }

    override fun packet(cell: ContextId): CellPacket? =
        store.db.query("SELECT body FROM packets WHERE context_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1", cell, CellPacket.KIND) {
            JSON.decodeFromString(CellPacket.serializer(), it.string("body"))
        }.firstOrNull()

    private fun write(tx: Tx, ids: Identities, checkpoint: CellCheckpoint, export: List<Entry>) {
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
