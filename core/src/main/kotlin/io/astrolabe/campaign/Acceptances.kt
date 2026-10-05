package io.astrolabe.campaign

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.DecisionKey
import io.astrolabe.verify.DecisionKind
import io.astrolabe.verify.DecisionRecord
import io.astrolabe.verify.Gap
import io.astrolabe.verify.ObligationResult
import io.astrolabe.verify.Resolved
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.StopCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

@Serializable
public enum class PendingStatus { Open, Applied, Void }

/**
 * A completion proposal whose acceptance waits for an authority (D-339), kept durably so that a decision can be applied
 * after a stop without another cell (D-340): the candidate it is about, the results it was resolved from and why it waits.
 * [incrementId] is `null` for the campaign gate (§8.7). [requestId] is the stable id of the decision request asked about
 * it, so a host's answer binds this completion and no other.
 */
@Serializable
public data class PendingCompletion(
    val id: String,
    val work: WorkId,
    val attempt: AttemptId,
    val incrementId: String?,
    val cell: ContextId?,
    val contractVersion: Int,
    val baseStamp: CandidateId,
    val resultingStamp: CandidateId,
    val patchHash: Digest?,
    val envId: Digest,
    val registerVersion: Int?,
    /** Test-integrity flags of the proposal, as their lines. */
    val flags: List<String>,
    val results: List<ObligationResult>,
    /** Conditions beside the results the proposal was resolved with (none for a pending one, kept for re-resolution). */
    val other: List<String>,
    val gaps: List<Gap>,
    val code: StopCode,
    /** Receipt and review ids the results rest on. */
    val evidence: List<String>,
    /** The agent's final text, a claim shown to the decider. */
    val summary: String?,
    val requestId: String,
    val status: PendingStatus = PendingStatus.Open,
    val closedReason: String? = null,
    /** P8.C.10: the red receipts the proposal's `Open` items acknowledged ([Resolved.acknowledged]); a commit keeps them. */
    val acknowledged: List<String> = emptyList(),
    /**
     * W1: the campaign gate's obligation set the [results] answer — the contract's acceptance items and the end checks'
     * definitions, digested — so the results are reused only for the same set; `null` for an increment, or a record before W1.
     */
    val obligationSet: String? = null,
    /**
     * WR (P1-1): the inputs outside candidate identity the [results]' receipts pinned, at their bytes when the completion was
     * asked about; the [results] are reused only while they still read so, and the [key] names them.
     */
    val outsideInputs: Map<String, FileVersion> = emptyMap(),
) {
    /** The constructor before [outsideInputs] (WR). Kept for Java callers. */
    public constructor(
        id: String, work: WorkId, attempt: AttemptId, incrementId: String?, cell: ContextId?, contractVersion: Int, baseStamp: CandidateId,
        resultingStamp: CandidateId, patchHash: Digest?, envId: Digest, registerVersion: Int?, flags: List<String>, results: List<ObligationResult>,
        other: List<String>, gaps: List<Gap>, code: StopCode, evidence: List<String>, summary: String?, requestId: String, status: PendingStatus, closedReason: String?,
        acknowledged: List<String>, obligationSet: String?,
    ) : this(id, work, attempt, incrementId, cell, contractVersion, baseStamp, resultingStamp, patchHash, envId, registerVersion, flags, results, other, gaps, code,
        evidence, summary, requestId, status, closedReason, acknowledged, obligationSet, emptyMap())

    /** The constructor before [obligationSet] (W1). Kept for Java callers. */
    public constructor(
        id: String, work: WorkId, attempt: AttemptId, incrementId: String?, cell: ContextId?, contractVersion: Int, baseStamp: CandidateId,
        resultingStamp: CandidateId, patchHash: Digest?, envId: Digest, registerVersion: Int?, flags: List<String>, results: List<ObligationResult>,
        other: List<String>, gaps: List<Gap>, code: StopCode, evidence: List<String>, summary: String?, requestId: String, status: PendingStatus, closedReason: String?,
        acknowledged: List<String>,
    ) : this(id, work, attempt, incrementId, cell, contractVersion, baseStamp, resultingStamp, patchHash, envId, registerVersion, flags, results, other, gaps, code,
        evidence, summary, requestId, status, closedReason, acknowledged, null, emptyMap())

    /** The constructor before [acknowledged] (P8.C.10). Kept for Java callers. */
    public constructor(
        id: String, work: WorkId, attempt: AttemptId, incrementId: String?, cell: ContextId?, contractVersion: Int, baseStamp: CandidateId,
        resultingStamp: CandidateId, patchHash: Digest?, envId: Digest, registerVersion: Int?, flags: List<String>, results: List<ObligationResult>,
        other: List<String>, gaps: List<Gap>, code: StopCode, evidence: List<String>, summary: String?, requestId: String, status: PendingStatus, closedReason: String?,
    ) : this(id, work, attempt, incrementId, cell, contractVersion, baseStamp, resultingStamp, patchHash, envId, registerVersion, flags, results, other, gaps, code,
        evidence, summary, requestId, status, closedReason, emptyList(), null, emptyMap())

    init {
        require(id.isNotBlank() && requestId.isNotBlank()) { "a pending completion has an id and a request id" }
        require(contractVersion >= 1) { "contract version starts at 1" }
    }

    /** The proposal it keeps, as the verifier would bind it on commit. */
    public fun proposal(): CompletionProposal =
        CompletionProposal(incrementId ?: "campaign", "done", contractVersion, baseStamp, resultingStamp, patchHash, envId)

    /**
     * Re-resolves the stored results with [decision] (D-340): the same rule, the same inputs, now with a decider's word.
     * A pending completion is past its cell's rework round, so a standing rejection or an open gap awaits the decider.
     */
    public fun resolve(decision: DecisionRecord?): Resolved =
        Resolver.resolve(results, other, decision?.takeIf { it.appliesTo(resultingStamp, contractVersion) && it.incrementId == incrementId }, reworkSpent = true)
            .copy(acknowledged = acknowledged)

    /**
     * The [DecisionKey] of the request asked about this completion: its scope, candidate, contract version, undecided
     * obligations (WD-10) and pinned inputs outside identity (WR).
     */
    public fun key(): String = DecisionKey.of(incrementId, resultingStamp, contractVersion, resolve(null).undecided.map { it.obligation }, outsideInputs)
}

/**
 * The controller's acceptance records (D-339, D-340; L9: the controller is their one writer): pending completions and
 * the acceptance decisions an authority made about them.
 */
public class Acceptances(private val store: Store, private val clock: Clock) {
    public fun save(ids: Identities, pending: PendingCompletion): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT OR REPLACE INTO pending_completions (id, work_id, attempt_id, candidate_id, context_id, increment_id, status, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            pending.id, ids.work, ids.attempt, pending.resultingStamp, pending.cell, pending.incrementId, pending.status.name, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(PendingCompletion.serializer(), pending),
        )
    }

    /** The attempt's open pending completion, if any (there is at most one: a campaign waits for one decision at a time). */
    public fun open(work: WorkId, attempt: AttemptId): PendingCompletion? = store.db.query(
        "SELECT body FROM pending_completions WHERE work_id = ? AND attempt_id = ? AND status = ? ORDER BY created_at DESC, rowid DESC LIMIT 1",
        work, attempt, PendingStatus.Open.name,
    ) { JSON.decodeFromString(PendingCompletion.serializer(), it.string("body")) }.firstOrNull()

    public fun pending(work: WorkId, attempt: AttemptId): List<PendingCompletion> = store.db.query(
        "SELECT body FROM pending_completions WHERE work_id = ? AND attempt_id = ? ORDER BY created_at, rowid",
        work, attempt,
    ) { JSON.decodeFromString(PendingCompletion.serializer(), it.string("body")) }

    /** Closes [pending] as applied or void with [reason]; the record stays as history. */
    public fun close(ids: Identities, pending: PendingCompletion, status: PendingStatus, reason: String) {
        require(status != PendingStatus.Open) { "closing needs a final status" }
        save(ids, pending.copy(status = status, closedReason = reason))
    }

    public fun record(ids: Identities, record: DecisionRecord): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT OR REPLACE INTO acceptance_decisions (id, work_id, attempt_id, candidate_id, context_id, increment_id, request_id, kind, spent, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            record.id, ids.work, ids.attempt, record.decision.candidate, ids.context, record.incrementId, record.decision.requestId, record.decision.kind.name,
            if (record.spent) 1 else 0, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(DecisionRecord.serializer(), record),
        )
    }

    /** Every decision recorded for the attempt, oldest first. */
    public fun decisions(work: WorkId, attempt: AttemptId): List<DecisionRecord> = store.db.query(
        "SELECT body FROM acceptance_decisions WHERE work_id = ? AND attempt_id = ? ORDER BY created_at, rowid",
        work, attempt,
    ) { JSON.decodeFromString(DecisionRecord.serializer(), it.string("body")) }

    /** The latest unspent decision about [incrementId] that speaks for [candidate] at [contractVersion]. */
    public fun current(work: WorkId, attempt: AttemptId, incrementId: String?, candidate: CandidateId, contractVersion: Int): DecisionRecord? =
        decisions(work, attempt).lastOrNull { !it.spent && it.incrementId == incrementId && it.appliesTo(candidate, contractVersion) }

    /** Whether a `rework` decision was already spent on [candidate] (D-340): the rejection then goes back to the authority. */
    public fun reworkSpent(work: WorkId, attempt: AttemptId, incrementId: String?, candidate: CandidateId, contractVersion: Int): Boolean =
        decisions(work, attempt).any { it.spent && it.incrementId == incrementId && it.decision.kind == DecisionKind.Rework && it.appliesTo(candidate, contractVersion) }

    /** Marks [record] spent: its one continuation run has been dispatched. */
    public fun spend(ids: Identities, record: DecisionRecord): Unit = record(ids, record.copy(spent = true))

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
