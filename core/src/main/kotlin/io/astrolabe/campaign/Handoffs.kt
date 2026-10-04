package io.astrolabe.campaign

import io.astrolabe.cell.Change
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.HandoffCause
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.TouchKind
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.Tier
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Clock

/**
 * A-D.6: a handed-off cell's return, kept before the `Transition.Returned` row of its `Partial(Handoff)` exit (artifact
 * before row), as a returned completion is. The Result Packet of a partial is not stored, so this record is what carries
 * the epoch across a reopen: the carry-forward's packet, the final report's epoch and the identity of the handoff — a
 * reopen that finds it with the increment's last cell still [cell] continues the increment as an epoch.
 */
@Serializable
internal data class ReturnedHandoff(
    val id: String,
    val seq: Long,
    val cell: ContextId,
    val incrementId: String,
    val role: String,
    val turns: Int,
    val registerVersion: Int,
    val contractVersion: Int,
    val stamp: CandidateId?,
    val envId: Digest?,
    val changes: List<KeptChange>,
    val gaps: List<String>,
    val receipts: List<String>,
    val touched: List<String>,
    /** The packet's reason: `Handoff: <hint>`. */
    val reason: String?,
    val hint: String,
    val cause: HandoffCause,
    /** The grant the cell's line ran under when it handed off; `null` when none was journaled. */
    val grant: String?,
    /** How the cell was routed: its continuation is routed with the same function, never as `Continuation` for being one. */
    val function: RoutingFunction,
    val tier: Tier?,
    val profile: String?,
) {
    /** The packet as the carry-forward and the final report read it; what the record does not keep is empty. */
    fun packet(ids: Identities, register: Register): ResultPacket = ResultPacket(
        ids = ids.copy(context = cell, candidate = stamp), increment = incrementId, role = role, contractVersion = contractVersion,
        executionGeneration = ExecutionGeneration.INITIAL, base = null, readVersions = emptyMap(), status = PacketStatus.Partial, reason = reason,
        waiting = null, register = register, worksetExport = emptyList(), changes = changes.map { it.change() }, transforms = emptyList(),
        receipts = receipts, stamp = stamp, envId = envId, coverage = PacketCoverage(0, emptyList()), flags = PacketFlags(emptyList(), emptyList()),
        claims = PacketClaims(), blocked = null, gaps = gaps, evidenceRefs = emptyList(), cost = PacketCost(),
    )

    companion object {
        const val KIND: String = "returned_handoff"

        fun of(id: String, seq: Long, exit: CellExit.Partial, grant: String?, function: RoutingFunction, tier: Tier?, profile: String?): ReturnedHandoff {
            val packet = exit.packet
            return ReturnedHandoff(
                id, seq, checkNotNull(packet.ids.context), packet.increment, packet.role, exit.turns, exit.register.version, packet.contractVersion,
                packet.stamp, packet.envId, packet.changes.map(KeptChange::of), packet.gaps, packet.receipts, exit.checkpoint.touched, packet.reason,
                exit.hint, checkNotNull(exit.handoffCause) { "a handoff exit names its cause" }, grant, function, tier, profile,
            )
        }
    }
}

/** A packet [Change] as kept with a returned handoff: the path and its versions. */
@Serializable
internal data class KeptChange(val path: String, val kind: TouchKind, val before: FileVersion?, val after: FileVersion?, val origin: ChangeOrigin) {
    fun change(): Change = Change(path, kind, before, after, origin)

    companion object {
        fun of(change: Change): KeptChange = KeptChange(change.path, change.kind, change.before, change.after, change.origin)
    }
}

/** The controller's returned handoffs (A-D.6; L9: the controller is their one writer), as `packets` rows. */
internal class ReturnedHandoffs(private val store: Store, private val clock: Clock) {
    fun save(ids: Identities, record: ReturnedHandoff): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            record.id, ids.work, ids.attempt, record.stamp, record.cell, ReturnedHandoff.KIND, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(ReturnedHandoff.serializer(), record),
        )
    }

    /** The attempt's returned handoffs, oldest first. */
    fun all(work: WorkId, attempt: AttemptId): List<ReturnedHandoff> = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid",
        work, attempt, ReturnedHandoff.KIND,
    ) { JSON.decodeFromString(ReturnedHandoff.serializer(), it.string("body")) }

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}

/**
 * A-D.6: the campaign's handoff limit as a durable grant, read from the journal and never from a variable — so neither a
 * reopen after a crash nor `runS0` re-entering itself replenishes it. A grant (`handoff-grant`) is journaled by `run`
 * when the attempt has none, and again when an explicit resume (`campaign-resumed`) is newer than the latest grant; each
 * handoff journals one spend (`handoff`) naming its successor before that cell is dispatched. The remainder is the
 * latest grant's limit minus its spends.
 */
internal class Handoffs(private val journal: Journal, private val idGen: IdGen, private val clock: Clock, private val ids: Identities) {
    class Grant(val id: String, val limit: Int)

    class Spend(val grant: String, val n: Int, val from: ContextId, val to: ContextId, val increment: String, val cause: HandoffCause)

    /** The grant in force, or `null` when the attempt has none or an explicit resume is newer than its latest. */
    fun current(): Grant? {
        var grant: Grant? = null
        for (record in records()) when (record.text("type")) {
            GRANT -> grant = Grant(record.text("grant")!!, (record["limit"] as JsonPrimitive).intOrNull!!)
            RESUMED -> grant = null
        }
        return grant
    }

    /** The grant in force, journaling a new one of [limit] when there is none. */
    fun grant(limit: Int): Grant = current() ?: Grant(idGen.next("grant"), limit).also { g ->
        append(listOf(), "handoff grant ${g.id}: ${g.limit} handoffs", buildJsonObject {
            put("type", GRANT)
            put("grant", g.id)
            put("limit", g.limit)
        })
    }

    /** An explicit resume (`Transition.Resumed` of an ended campaign): the next `run` journals a new grant. */
    fun resumed(reason: String) {
        append(listOf(), "handoff grant renewed at the next run: $reason", buildJsonObject { put("type", RESUMED) })
    }

    fun spends(grant: String): List<Spend> = records().filter { it.text("type") == SPEND && it.text("grant") == grant }.map(::spend)

    /** The spend that paid for continuing [cell], if one was journaled. */
    fun spendOf(cell: ContextId): Spend? = records().filter { it.text("type") == SPEND && it.text("from") == cell.value }.map(::spend).lastOrNull()

    fun spend(grant: Grant, n: Int, from: ContextId, to: ContextId, increment: String, cause: HandoffCause): Spend {
        append(listOf(increment, from.value, to.value), "handoff $n of ${grant.limit} (${cause.wire}): ${from.value} continues as ${to.value}", buildJsonObject {
            put("type", SPEND)
            put("grant", grant.id)
            put("n", n)
            put("from", from.value)
            put("to", to.value)
            put("increment", increment)
            put("cause", cause.wire)
        })
        return Spend(grant.id, n, from, to, increment, cause)
    }

    private fun spend(record: JsonObject): Spend = Spend(
        record.text("grant")!!, (record["n"] as JsonPrimitive).intOrNull!!, ContextId(record.text("from")!!), ContextId(record.text("to")!!),
        record.text("increment")!!, HandoffCause.entries.first { it.wire == record.text("cause") },
    )

    private fun records(): List<JsonObject> = journal.events(JournalScope(ids.work, kinds = setOf(JournalKind.Boundary)))
        .filter { it.ids.attempt == ids.attempt }
        .mapNotNull { (it.payload as? JsonObject)?.takeIf { p -> p.text("type") in TYPES } }

    private fun append(refs: List<String>, text: String, payload: JsonObject) {
        journal.append(JournalEvent(idGen.next("ev"), ids.copy(context = null, candidate = null), null, JournalKind.Boundary, refs = refs, text = text, payload = payload, at = clock.instant()))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val GRANT = "handoff-grant"
        const val SPEND = "handoff"
        const val RESUMED = "campaign-resumed"
        val TYPES = setOf(GRANT, SPEND, RESUMED)
    }
}
