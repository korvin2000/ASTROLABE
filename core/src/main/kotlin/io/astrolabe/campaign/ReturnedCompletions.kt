package io.astrolabe.campaign

import io.astrolabe.cell.CellExit
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.route.Tier
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.verify.AcceptanceSurface
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.verify.Verdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * A completed cell's return as the controller verifies it (P8.C.8): the proposal, the register, the test-integrity
 * flags and the agent's text, kept before the `Transition.Returned` row that says the cell completed (artifact before
 * row). [seq] is that row's sequence number: while the campaign row is still at it and no pending completion names
 * [cell], the return's outcome was never applied, and the next open verifies it again from this record with no model
 * call. The record is immutable; any later transition or a pending completion of [cell] retires it.
 */
@Serializable
internal data class ReturnedCompletion(
    val id: String,
    val seq: Long,
    val cell: ContextId,
    val incrementId: String,
    val turns: Int,
    val register: Register,
    val claimedStatus: String,
    val contractVersion: Int,
    val baseStamp: CandidateId,
    val resultingStamp: CandidateId,
    val envId: Digest,
    val reason: String?,
    val flags: List<KeptFlag>,
    val text: String,
    /** The cell deferred its proposal to an authority (D-339): its rework round is spent. */
    val deferred: Boolean,
    val answer: String?,
    /** What the cell touched (the pre-scan refresh) and changed (the increment review's triggers). */
    val touched: List<String>,
    val changed: List<String>,
    /** The tier and profile the cell was routed at and its pre-existing ledger lines: the increment review's and the attempt ladder's inputs. */
    val tier: Tier?,
    val profile: String?,
    val preexisting: List<String>,
    /** The packet's receipt ids: a refused completion's evidence (§11.3). */
    val receipts: List<String>,
) {
    /** The proposal as the cell's packet states it (§8.7). */
    fun proposal(): CompletionProposal = CompletionProposal(incrementId, claimedStatus, contractVersion, baseStamp, resultingStamp, null, envId, reason)

    fun testIntegrity(): List<TestIntegrityFlag> = flags.map { it.flag() }

    companion object {
        const val KIND: String = "returned_completion"

        fun of(id: String, seq: Long, exit: CellExit.Completed, tier: Tier?, profile: String?, preexisting: List<String>): ReturnedCompletion {
            val proposal = exit.packet.proposal()
            return ReturnedCompletion(
                id, seq, checkNotNull(exit.packet.ids.context), proposal.incrementId, exit.turns, exit.register, proposal.claimedStatus, proposal.contractVersion,
                proposal.baseStamp, proposal.resultingStamp, proposal.envId, proposal.reason, exit.packet.flags.testIntegrity.map(KeptFlag::of), exit.text,
                exit.pending != null, exit.answer, exit.checkpoint.touched, exit.packet.changes.map { it.path }, tier, profile, preexisting, exit.packet.receipts,
            )
        }
    }
}

/** A [TestIntegrityFlag] as kept with a returned completion. */
@Serializable
internal data class KeptFlag(
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
    fun flag(): TestIntegrityFlag = TestIntegrityFlag(path, surface, cause, requiredChecks, kind, reason, verdict, originalObligation, humanOnly)

    companion object {
        fun of(flag: TestIntegrityFlag): KeptFlag =
            KeptFlag(flag.path, flag.surface, flag.cause, flag.requiredChecks, flag.kind, flag.reason, flag.verdict, flag.originalObligation, flag.humanOnly)
    }
}

/** The controller's returned completions (P8.C.8; L9: the controller is their one writer), as `packets` rows. */
internal class ReturnedCompletions(private val store: Store, private val clock: Clock) {
    fun save(ids: Identities, record: ReturnedCompletion): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            record.id, ids.work, ids.attempt, record.resultingStamp, record.cell, ReturnedCompletion.KIND, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(ReturnedCompletion.serializer(), record),
        )
    }

    /** The attempt's latest returned completion, if any. */
    fun latest(work: WorkId, attempt: AttemptId): ReturnedCompletion? = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
        work, attempt, ReturnedCompletion.KIND,
    ) { JSON.decodeFromString(ReturnedCompletion.serializer(), it.string("body")) }.firstOrNull()

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
