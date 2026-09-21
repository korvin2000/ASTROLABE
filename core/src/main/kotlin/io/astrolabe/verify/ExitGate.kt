package io.astrolabe.verify

import io.astrolabe.contract.Acceptance
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
import io.astrolabe.register.Mark
import io.astrolabe.register.Register

/**
 * A recorded assessment of a `check:` acceptance item (§8.7): the reviewer or user accepted the **stated
 * criterion** against an evidence reference, at a contract version — never merely an id.
 */
public data class Assessment(
    val acceptanceId: String,
    val criterion: String,
    val evidenceRef: String,
    val accepted: Boolean,
    val by: String,
    val contractVersion: Int,
) {
    init {
        require(acceptanceId.isNotBlank() && criterion.isNotBlank() && evidenceRef.isNotBlank() && by.isNotBlank()) { "an assessment names its item, criterion, evidence and assessor" }
    }
}

/** The exit gate's verdict: accepted, or exactly what is missing (§8.7). */
public sealed interface GateResult {
    public data class Accepted(val receiptIds: List<String>, val evidenceRefs: List<String> = receiptIds) : GateResult

    public data class Refused(val missing: List<String>) : GateResult
}

/**
 * The hard exit gate (§8.7, §5.9, TODO P1.7.7): a completion proposal is accepted only if every `run:` item of
 * the increment has a green **and current, eligible** receipt (D-45), every `check:` item an accepted
 * assessment of its stated criterion at this contract version, every `review:` item an approving signed
 * verdict at this contract version, no red verify line stands without an `Open` item, no `[ ]`/`[>]` step
 * lacks a disposition, every acceptance-surface flag on a required check is justified **and** approved
 * through a supported review path, and no impact nudge for a changed public definition is unresolved.
 * An `Open` item never waives a required acceptance. The gate reads evidence, never the model's word.
 */
public object ExitGate {
    @JvmStatic
    public fun evaluate(
        register: Register,
        contract: Contract,
        increment: Increment,
        currencies: Map<String, Currency>,
        assessments: List<Assessment> = emptyList(),
        reviews: Map<String, Verdict> = emptyMap(),
        flags: List<TestIntegrityFlag> = emptyList(),
        unresolvedImpactNudges: List<String> = emptyList(),
    ): GateResult {
        val missing = ArrayList<String>()
        val receipts = ArrayList<String>()
        val evidence = ArrayList<String>()
        for (id in increment.accept) {
            when (val item = contract.acceptance(id)) {
                null -> missing += "$id: not an acceptance item of contract v${contract.version}"
                is Acceptance.Run -> {
                    val currency = currencies[id] ?: currencies[Checks.acceptId(id)]
                    when {
                        currency == null -> missing += "$id: ${item.criterion} — no receipt"
                        currency.certifies -> receipts += currency.receiptId!!
                        else -> missing += "$id: ${item.criterion} — " + currency.reasons.ifEmpty { listOf("receipt ${currency.receiptId} does not certify the current tree") }.joinToString("; ")
                    }
                }
                is Acceptance.Check -> {
                    val assessment = assessments.firstOrNull { it.acceptanceId == id && it.accepted && it.criterion == item.text && it.contractVersion == contract.version }
                    if (assessment == null) {
                        val why = when {
                            assessments.none { it.acceptanceId == id } -> "no assessment recorded"
                            assessments.any { it.acceptanceId == id && it.contractVersion != contract.version } -> "assessment bound to another contract version"
                            assessments.any { it.acceptanceId == id && it.criterion != item.text } -> "assessment does not name the stated criterion"
                            else -> "assessment not accepted"
                        }
                        missing += "$id: ${item.criterion} — $why"
                    } else evidence += assessment.evidenceRef
                }
                is Acceptance.Review -> {
                    val verdict = reviews[id]
                    when {
                        verdict == null -> missing += "$id: ${item.criterion} — no signed review"
                        verdict.contractRevision != contract.version -> missing += "$id: ${item.criterion} — review signed for contract v${verdict.contractRevision}, not v${contract.version}"
                        !verdict.approved -> missing += "$id: ${item.criterion} — review ${verdict.outcome.name.lowercase()} by ${verdict.signedBy}"
                        else -> evidence += verdict.requestId
                    }
                }
            }
        }
        // A red check outside the increment's required set needs an Open item that names it; a required red is refused above.
        // Inconclusive or unavailable is missing evidence, not red: it blocks only where the check is required.
        val requiredIds = increment.accept.map { Checks.acceptId(it) }.toSet() + increment.accept.toSet()
        val openTexts = register.open.filter { !it.closed }.map { it.text }
        for ((checkId, currency) in currencies) {
            if (checkId in requiredIds || !currency.red || currency.receiptId == null) continue
            if (openTexts.none { it.contains(checkId) }) missing += "$checkId is red without an Open item naming it"
        }
        register.plan.filter { it.mark == Mark.Todo || it.mark == Mark.Cursor }.forEach { step ->
            missing += "step ${step.n} ${step.mark.text} '${step.text}' has no disposition (done, cancelled or an explicit non-completed exit)"
        }
        for (flag in flags) {
            if (flag.requiredChecks.isEmpty()) continue
            if (flag.reason.isNullOrBlank()) missing += "acceptance surface ${flag.path} touches ${flag.requiredChecks.joinToString(", ")} without a recorded justification"
            if (flag.verdict?.approved != true) missing += "acceptance surface ${flag.path} touches ${flag.requiredChecks.joinToString(", ")} without an approving review"
        }
        unresolvedImpactNudges.forEach { missing += "unresolved impact nudge: $it" }
        return if (missing.isEmpty()) GateResult.Accepted(receipts.distinct(), (receipts + evidence).distinct()) else GateResult.Refused(missing)
    }
}

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
    ) : CompletionResult

    /** Not supported by the evidence; the ledger is untouched. After [Verifier.maxFinalizations] refusals the cell goes to gap-directed recovery. */
    public data class Refused(val missing: List<String>, val attempts: Int, val recoveryDirected: Boolean) : CompletionResult

    public data class NotCompleted(val kind: ExitKind, val reason: String) : CompletionResult
}

/**
 * The verifier (§8.7): the only component that accepts completion. It validates the claimed status against
 * receipts and assessments, binds the accepted increment to `base_stamp`, `patch_hash`, `resulting_stamp`
 * and the environment, and hands back the ledger the controller commits. A claim that is not `done` is an
 * explicit non-completed exit; a refused `done` leaves the ledger untouched and, repeated, directs the cell to
 * gap-directed recovery instead of an endless gate.
 */
public class Verifier(public val maxFinalizations: Int = 2) {
    private val finalizations = HashMap<String, Int>()

    init {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
    }

    public fun accept(
        proposal: CompletionProposal,
        contract: Contract,
        increment: Increment,
        register: Register,
        ledger: Ledger,
        stampNow: CandidateId,
        currencies: Map<String, Currency>,
        assessments: List<Assessment> = emptyList(),
        reviews: Map<String, Verdict> = emptyMap(),
        flags: List<TestIntegrityFlag> = emptyList(),
        unresolvedImpactNudges: List<String> = emptyList(),
    ): CompletionResult {
        require(proposal.incrementId == increment.id) { "proposal is for ${proposal.incrementId}, not ${increment.id}" }
        require(register.increment == increment.id) { "register belongs to another increment" }
        exitKind(proposal.claimedStatus)?.let { return CompletionResult.NotCompleted(it, proposal.reason ?: proposal.claimedStatus) }
        require(proposal.claimedStatus == "done") { "unknown status '${proposal.claimedStatus}'" }
        val missing = ArrayList<String>()
        if (proposal.contractVersion != contract.version) missing += "proposal binds contract v${proposal.contractVersion}; the committed contract is v${contract.version}"
        if (proposal.resultingStamp != stampNow) missing += "resulting stamp @${proposal.resultingStamp.hash8} is not the tree now @${stampNow.hash8}"
        for (id in increment.accept) if (contract.acceptance(id) is Acceptance.Review) {
            val verdict = reviews[id] ?: continue
            if (verdict.reviewedCandidate != stampNow) missing += "$id: review does not certify the current candidate"
        }
        for (flag in flags.filter { it.requiredChecks.isNotEmpty() }) {
            val verdict = flag.verdict ?: continue
            if (verdict.contractRevision != contract.version || verdict.reviewedCandidate != stampNow) {
                missing += "acceptance surface ${flag.path}: approval belongs to another contract or candidate"
            }
        }
        val gate = ExitGate.evaluate(register, contract, increment, currencies, assessments, reviews, flags, unresolvedImpactNudges)
        if (gate is GateResult.Refused) missing += gate.missing
        if (missing.isNotEmpty()) {
            val attempts = (finalizations[increment.id] ?: 0) + 1
            finalizations[increment.id] = attempts
            return CompletionResult.Refused(missing, attempts, recoveryDirected = attempts >= maxFinalizations)
        }
        val accepted = gate as GateResult.Accepted
        val receiptIds = accepted.receiptIds
        val entries = ledger.entries.toMutableMap()
        for (requirementId in increment.requirementIds) {
            entries[requirementId] = LedgerEntry(requirementId, RequirementStatus.Verified, accepted.evidenceRefs, stampValid = true)
        }
        finalizations.remove(increment.id)
        return CompletionResult.Accepted(
            increment.id, proposal.baseStamp, proposal.patchHash, proposal.resultingStamp, proposal.envId,
            receiptIds, Ledger(entries), contract.version, contract.workId, increment.definitionDigest(),
            contract.attemptId, accepted.evidenceRefs, register.cell,
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
