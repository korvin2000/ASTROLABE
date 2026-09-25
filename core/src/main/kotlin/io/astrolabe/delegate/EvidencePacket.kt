package io.astrolabe.delegate

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.id.CandidateId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.TestIntegrityFlag

/** One acceptance definition as the judge receives it (D-52): complete, with its origin and obligation version. */
public data class ReviewCriterion(val id: String, val criterion: String, val origin: String, val obligationVersion: Int) {
    init {
        require(id.isNotBlank() && criterion.isNotBlank()) { "a criterion names its item and text" }
    }

    public companion object {
        @JvmStatic
        public fun of(item: Acceptance): ReviewCriterion = ReviewCriterion(item.id, item.criterion, item.origin.toString(), item.obligationVersion)
    }
}

/** A receipt as the judge receives it (§8.8): outcome and parsed counts; [required] when it certifies an item under review. */
public data class ReviewReceipt(val receiptId: String, val checkId: String, val outcome: Outcome, val counts: String?, val required: Boolean) {
    /** A required check that ran and failed: no review can override it (§8.8). */
    val failedRequired: Boolean get() = required && (outcome == Outcome.Failed || outcome == Outcome.Timeout)

    public companion object {
        @JvmStatic
        public fun of(receipt: Receipt, required: Boolean): ReviewReceipt = ReviewReceipt(
            receipt.receiptId, receipt.checkId, receipt.outcome,
            receipt.parsed?.let { "${it.passed} passed, ${it.failed} failed, ${it.errors} errors, ${it.skipped} skipped" }, required,
        )
    }
}

/**
 * The evidence packet of a review (§8.8): the contract slice with complete acceptance definitions, the diff, the
 * receipts with parsed counts, the CON/ADR notes touching the paths, the test-integrity flags beside their original
 * obligations, the pre-existing ledger, the coverage report and the rubric — never the proposer's transcript.
 * [evidenceVersions] are the reviewed paths at the versions the diff shows; an assessment binds them with the
 * contract version, the [candidate] and the criteria, so a changed dependency invalidates the approval (§8.7).
 */
public data class EvidencePacket(
    val id: String,
    val ids: Identities,
    val scope: ReviewScope,
    val incrementId: String?,
    val contractVersion: Int,
    val candidate: CandidateId,
    val requirements: List<Excerpt>,
    val criteria: List<ReviewCriterion>,
    val diff: String,
    val diffRef: String?,
    val receipts: List<ReviewReceipt>,
    val notes: List<String>,
    val testIntegrity: List<TestIntegrityFlag>,
    val preexisting: List<String>,
    val coverage: String?,
    val rubric: List<String>,
    val evidenceVersions: Map<String, FileVersion>,
    /** Why the review is owed (§8.8 triggers or the campaign predicate). */
    val triggers: List<String>,
) {
    init {
        require(id.isNotBlank()) { "a packet has an id" }
        require(contractVersion >= 1) { "contract version starts at 1" }
        require(scope == ReviewScope.Campaign || !incrementId.isNullOrBlank()) { "an increment-scope packet names its increment" }
    }

    /** Required checks that failed: they stand whatever the verdict says. */
    val failedRequired: List<ReviewReceipt> get() = receipts.filter { it.failedRequired }

    /** The same evidence as a request for the human path ([io.astrolabe.event.Authority.review], D-23). */
    public fun request(): ReviewRequest = ReviewRequest(
        id = id, contractRevision = contractVersion, ids = ids, scope = scope, candidate = candidate, packetRef = diffRef ?: id,
        criteria = criteria.map { "${it.id}: ${it.criterion}" },
        originalObligations = testIntegrity.mapNotNull { f -> f.originalObligation?.let { "${f.path}: $it" } },
        diffRef = diffRef, receipts = receipts.map { it.receiptId }, rubric = rubric,
    )

    /** The judge's view (its `[T]` brief): acceptance first, executable evidence next, opinion last (§8.8 rules). */
    public fun render(output: String): String = buildString {
        append("Evidence packet ").append(id).append(" — ").append(scope.name.lowercase()).append(" review")
        incrementId?.let { append(" of ").append(it) }
        append(", contract v").append(contractVersion).append(", candidate @").append(candidate.hash8).append('\n')
        if (triggers.isNotEmpty()) append("Owed because: ").append(triggers.joinToString("; ")).append('\n')
        append("Acceptance criteria (score these first):\n")
        criteria.forEach { append("  ").append(it.id).append(" [").append(it.origin).append(", v").append(it.obligationVersion).append("]: ").append(it.criterion).append('\n') }
        if (requirements.isNotEmpty()) {
            append("Requirements (exact excerpts):\n")
            requirements.forEach { append("  ").append(it.id).append(" [").append(it.authorityRef).append("]: ").append(it.text).append('\n') }
        }
        append("Receipts (executable checks outrank opinion):\n")
        if (receipts.isEmpty()) append("  none\n")
        receipts.forEach { r ->
            append("  ").append(r.receiptId).append(' ').append(r.checkId).append(": ").append(r.outcome.name.lowercase())
            r.counts?.let { append(" (").append(it).append(')') }
            if (r.required) append(" · required")
            append('\n')
        }
        if (testIntegrity.isNotEmpty()) {
            append("Test-integrity flags (original obligation beside the change):\n")
            testIntegrity.forEach { f ->
                append("  ").append(f.path).append(": ").append(f.kind)
                f.reason?.let { append(" — why: ").append(it) }
                f.originalObligation?.let { append("\n    original: ").append(it.replace("\n", "\n    ")) }
                append('\n')
            }
        }
        if (notes.isNotEmpty()) append("CON/ADR notes touching the paths:\n").append(notes.joinToString("\n") { "  $it" }).append('\n')
        if (preexisting.isNotEmpty()) append("Pre-existing failures (not this change's): ").append(preexisting.joinToString("; ")).append('\n')
        coverage?.let { append("Coverage: ").append(it).append('\n') }
        if (rubric.isNotEmpty()) append("Rubric:\n").append(rubric.joinToString("\n") { "  - $it" }).append('\n')
        append("Diff:\n").append(diff.trimEnd()).append('\n')
        append("Required output: ").append(output)
    }

    public companion object {
        /** The §8.8 structural rubric, one line per item. */
        @JvmField
        public val RUBRIC: List<String> = listOf(
            "module boundaries and compatibility: does the change fit the existing architecture?",
            "reuse of existing mechanisms: does it duplicate a subsystem?",
            "public contracts: does it change one unintentionally?",
            "comprehensibility: no unnecessary abstraction, duplication or dead code",
            "error handling on every new path",
        )

        /** The contract slice of [increment] as review criteria: its acceptance items, complete (D-52). */
        @JvmStatic
        public fun criteria(contract: Contract, increment: Increment): List<ReviewCriterion> =
            increment.accept.mapNotNull { contract.acceptance(it) }.map(ReviewCriterion::of)
    }
}
