package io.astrolabe.verify

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import kotlinx.serialization.Serializable

/** Review scopes of §8.8: increment scope and campaign scope. */
@Serializable
public enum class ReviewScope { Increment, Campaign }

/**
 * A request for an independent assessment (§8.8), declared here as the protocol contract consumed by
 * [io.astrolabe.event.Authority.review] (human path) and by the review cell (P4.4.3). The packet it names carries
 * the complete applicable acceptance definitions with origin and obligation version (D-52); [criteria] repeats
 * the criterion texts so a verdict can name what it assessed.
 */
@Serializable
public data class ReviewRequest(
    val id: String,
    val contractRevision: Int,
    val ids: Identities,
    val scope: ReviewScope,
    val candidate: CandidateId,
    /** Evidence packet reference (journal or blob id); never the proposer's transcript. */
    val packetRef: String,
    val criteria: List<String>,
    /** Pre-change tests or assertions beside a weakening diff (§8.6), when any. */
    val originalObligations: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && packetRef.isNotBlank()) { "review request needs an id and a packet reference" }
        require(contractRevision >= 1) { "contractRevision must be ≥ 1" }
    }
}

@Serializable
public enum class VerdictOutcome { Approve, Revise, Reject, InsufficientEvidence, Escalate }

@Serializable
public enum class Severity { Blocker, Major, Minor, Nit }

@Serializable
public enum class FindingKind { Correctness, Contract, Quality, TestIntegrity }

@Serializable
public data class Finding(
    val severity: Severity,
    /** `path:line@hash`. */
    val location: String,
    val issue: String,
    val suggestedFix: String? = null,
    val kind: FindingKind,
)

/** Reviewed coverage from telemetry, never from the reviewer's word (F14). */
@Serializable
public data class ReviewCoverage(
    val filesReviewed: List<String>,
    val ranges: List<String>,
    val unread: List<String>,
)

/**
 * The judge verdict (§8.8). It binds the contract revision, the reviewed candidate and the criteria it assessed;
 * changed dependencies invalidate it (§8.7). A verdict can never override a failed required check.
 */
@Serializable
public data class Verdict(
    val requestId: String,
    val contractRevision: Int,
    val reviewedCandidate: CandidateId,
    val outcome: VerdictOutcome,
    val findings: List<Finding> = emptyList(),
    val coverage: ReviewCoverage? = null,
    val contractViolations: List<String> = emptyList(),
    val confidence: Double,
    /** Judge cell id or the human reviewer's identity. */
    val signedBy: String,
    /** The criterion a judge could not assess, required for [VerdictOutcome.InsufficientEvidence]. */
    val missingCriterion: String? = null,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be within 0..1" }
        require(signedBy.isNotBlank()) { "a verdict must be signed" }
        if (outcome == VerdictOutcome.InsufficientEvidence) {
            require(!missingCriterion.isNullOrBlank()) { "insufficient_evidence must name the missing criterion" }
        }
    }

    val approved: Boolean get() = outcome == VerdictOutcome.Approve
}
