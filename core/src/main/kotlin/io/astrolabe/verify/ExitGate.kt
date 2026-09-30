package io.astrolabe.verify

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.LedgerEntry
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.id.CandidateId
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register

/** What a cell proposes at its end (§5.9): the status is a claim until the verifier accepts it. */
public data class CompletionProposal(
    val incrementId: String,
    /** `done` · `blocked` · `partial` · `replan` · `waiting` · `budget_exhausted` · `cancelled`. */
    val claimedStatus: String,
    val contractVersion: Int,
    val baseStamp: CandidateId,
    val resultingStamp: CandidateId,
    val patchHash: Digest?,
    val envId: Digest,
    val reason: String? = null,
) {
    init {
        require(incrementId.isNotBlank() && claimedStatus.isNotBlank()) { "a proposal names its increment and status" }
    }
}

/** Distinct non-completed exits (invariant 11): never disguised as completion. */
public enum class ExitKind { Blocked, Partial, Replan, Waiting, BudgetExhausted, Cancelled }

public sealed interface CompletionResult {
    /** Completion bound to the candidate and environment the evidence is about; the ledger to commit comes with it. */
    public data class Accepted(
        val incrementId: String,
        val baseStamp: CandidateId,
        val patchHash: Digest?,
        val resultingStamp: CandidateId,
        val envId: Digest,
        val receiptIds: List<String>,
        val ledger: Ledger,
        /** Bound by the verifier so an old completion cannot be committed against an amended graph. */
        val contractVersion: Int = 0,
        val workId: WorkId? = null,
        val incrementDefinition: Digest? = null,
        val attemptId: AttemptId? = null,
        val evidenceRefs: List<String> = receiptIds,
        val contextId: ContextId? = null,
        /** How each item was accepted (I7, D-342): tested, reviewed, or accepted by a decider without verification. */
        val provenance: List<ItemProvenance> = emptyList(),
        /** The acceptance decision this completion applied, if any. */
        val decision: DecisionRecord? = null,
    ) : CompletionResult {
        /** Every item was verified: nothing was accepted on a decider's word alone. */
        val verified: Boolean get() = provenance.none { it.how == ProvenanceKind.Accepted }
    }

    /**
     * The proposal must be reworked (§8.7): an executed check is red, a reviewer rejected, a decision asked for rework
     * or something the agent must close is open. The ledger is untouched; after [Verifier.maxFinalizations] refusals
     * the cell goes to gap-directed recovery.
     */
    public data class Refused(val missing: List<String>, val attempts: Int, val recoveryDirected: Boolean, val resolved: Resolved? = null) : CompletionResult

    /**
     * Obligations remain unverified — or a rejection stands after its rework round — without a decision (D-339): the
     * work is done and waits for an authority; it is neither blocked nor failed (I1).
     */
    public data class Pending(val proposal: CompletionProposal, val code: StopCode, val resolved: Resolved) : CompletionResult {
        val missing: List<String> get() = resolved.missing
    }

    public data class NotCompleted(val kind: ExitKind, val reason: String) : CompletionResult
}

/**
 * The verifier (§8.7): the only component that accepts completion. It resolves the claimed status with [Resolver] —
 * the rule the cell's exit gate applies to the same records — binds an accepted increment to `base_stamp`,
 * `patch_hash`, `resulting_stamp` and the environment, and hands back the ledger the controller commits. A claim that
 * is not `done` is an explicit non-completed exit; a refused `done` leaves the ledger untouched and, repeated, directs
 * the cell to gap-directed recovery instead of an endless gate; an unverified one is [CompletionResult.Pending].
 */
public class Verifier(public val maxFinalizations: Int = 2) {
    private val finalizations = HashMap<String, Int>()

    init {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
    }

    @JvmOverloads
    public fun accept(
        proposal: CompletionProposal,
        contract: Contract,
        increment: Increment,
        register: Register,
        ledger: Ledger,
        stampNow: CandidateId,
        currencies: Map<String, Currency>,
        verdicts: Map<String, Verdict> = emptyMap(),
        unavailable: Map<String, String> = emptyMap(),
        flags: List<TestIntegrityFlag> = emptyList(),
        unresolvedImpactNudges: List<String> = emptyList(),
        decision: DecisionRecord? = null,
        reworkSpent: Boolean = false,
        extra: List<ObligationResult> = emptyList(),
    ): CompletionResult {
        require(proposal.incrementId == increment.id) { "proposal is for ${proposal.incrementId}, not ${increment.id}" }
        require(register.increment == increment.id) { "register belongs to another increment" }
        exitKind(proposal.claimedStatus)?.let { return CompletionResult.NotCompleted(it, proposal.reason ?: proposal.claimedStatus) }
        require(proposal.claimedStatus == "done") { "unknown status '${proposal.claimedStatus}'" }
        val binding = ArrayList<String>()
        if (proposal.contractVersion != contract.version) binding += "proposal binds contract v${proposal.contractVersion}; the committed contract is v${contract.version}"
        if (proposal.resultingStamp != stampNow) binding += "resulting stamp @${proposal.resultingStamp.hash8} is not the tree now @${stampNow.hash8}"
        val resolved = Resolver.increment(register, contract, increment, currencies, verdicts, unavailable, flags, unresolvedImpactNudges, stampNow,
            decision?.takeIf { it.appliesTo(stampNow, contract.version) && it.incrementId == increment.id }, reworkSpent, binding, extra)
        return when (resolved.resolution) {
            Resolution.Rework -> {
                val attempts = (finalizations[increment.id] ?: 0) + 1
                finalizations[increment.id] = attempts
                CompletionResult.Refused(resolved.missing, attempts, recoveryDirected = attempts >= maxFinalizations, resolved)
            }
            Resolution.Await -> CompletionResult.Pending(proposal, checkNotNull(resolved.code), resolved)
            Resolution.Complete -> {
                finalizations.remove(increment.id)
                commit(proposal, contract, increment, register.cell, ledger, resolved)
            }
        }
    }

    /**
     * The accepted completion of [increment] from a resolution that completed — the verifier's own, or one a resume
     * re-derived from a stored pending completion and a decision (D-340).
     */
    public fun commit(proposal: CompletionProposal, contract: Contract, increment: Increment, cell: ContextId?, ledger: Ledger, resolved: Resolved): CompletionResult.Accepted {
        require(resolved.resolution == Resolution.Complete) { "only a complete resolution commits; this one is ${resolved.resolution}" }
        val entries = ledger.entries.toMutableMap()
        for (requirementId in increment.requirementIds) {
            entries[requirementId] = LedgerEntry(requirementId, RequirementStatus.Verified, resolved.evidenceRefs, stampValid = true, provenance = resolved.provenance)
        }
        return CompletionResult.Accepted(
            increment.id, proposal.baseStamp, proposal.patchHash, proposal.resultingStamp, proposal.envId,
            resolved.receiptIds, Ledger(entries), contract.version, contract.workId, increment.definitionDigest(),
            contract.attemptId, resolved.evidenceRefs, cell, resolved.provenance, resolved.decision,
        )
    }

    private fun exitKind(status: String): ExitKind? = when (status) {
        "blocked" -> ExitKind.Blocked
        "partial" -> ExitKind.Partial
        "replan" -> ExitKind.Replan
        "waiting" -> ExitKind.Waiting
        "budget_exhausted" -> ExitKind.BudgetExhausted
        "cancelled" -> ExitKind.Cancelled
        else -> null
    }
}
