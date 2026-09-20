package io.astrolabe.event

import io.astrolabe.id.Identities
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import kotlinx.serialization.Serializable

/** A question the cell asks through `task.ask` (§5.4); answered by a human or refused by policy. */
@Serializable
public data class Question(
    val id: String,
    val contractRevision: Int,
    val ids: Identities,
    val text: String,
    val options: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && text.isNotBlank()) { "question needs an id and text" }
    }
}

/**
 * A user's answer. [changesRequirements] decides the path (§4.1): a factual answer is recorded as evidence
 * without a new contract revision; one that changes authority or requirements becomes an amendment.
 */
@Serializable
public data class Answer(
    val questionId: String,
    val contractRevision: Int,
    val text: String,
    val chosenOption: Int? = null,
    val changesRequirements: Boolean = false,
)

/** A D-class effect awaiting approval (§4.6): recorded as an intent before dispatch. */
@Serializable
public data class DClassRequest(
    val id: String,
    val contractRevision: Int,
    val ids: Identities,
    val action: String,
    val argv: List<String>,
    val cwd: String?,
    val expectedEffect: String,
    val reason: String,
    /** True when the committed contract allowlists this effect (autonomous approval path). */
    val contractAllowlisted: Boolean = false,
)

@Serializable
public data class Decision(
    val requestId: String,
    val contractRevision: Int,
    val approved: Boolean,
    val reason: String? = null,
)

@Serializable
public enum class Proposer { Model, User }

/** A proposed contract change; weakening proposals are never auto-accepted (§4.1). */
@Serializable
public data class AmendmentProposal(
    val id: String,
    val contractRevision: Int,
    val ids: Identities,
    val by: Proposer,
    val change: String,
    val reason: String,
    val weakening: Boolean,
)

@Serializable
public enum class ResolutionOutcome { Accepted, Rejected, Pending }

@Serializable
public data class Resolution(
    val proposalId: String,
    val contractRevision: Int,
    val outcome: ResolutionOutcome,
    val byAuthority: String,
    val reason: String? = null,
)

/**
 * Inbound host authority (TODO P0.4.2): who answers `blocked` is a policy (§1.2). Every reply names the pending
 * item and the contract revision it answers; the runtime revalidates late replies with [Replies] before use.
 * Returning `null` from [ask] or [review] means no answer is available: the cell ends `blocked`.
 */
public interface Authority {
    public suspend fun ask(question: Question): Answer?

    public suspend fun approve(request: DClassRequest): Decision

    public suspend fun resolve(proposal: AmendmentProposal): Resolution

    /** Human review path (D-23): a signed verdict, or `null` when no reviewer is available. */
    public suspend fun review(request: ReviewRequest): Verdict?
}

/**
 * Autonomous policy (§4.1, §4.6): never auto-accept a weakening; D-class effects are denied unless the
 * contract allowlists them; questions have no answerer, so `ask` ⇒ blocked. Non-weakening proposals stay
 * pending unless [acceptNonWeakening] is set by the host.
 */
@Serializable
public data class AutonomousPolicy(
    val acceptNonWeakening: Boolean = false,
    val reviewer: String? = null,
)

public class AutonomousAuthority(private val policy: AutonomousPolicy = AutonomousPolicy()) : Authority {
    override suspend fun ask(question: Question): Answer? = null

    override suspend fun approve(request: DClassRequest): Decision = Decision(
        requestId = request.id,
        contractRevision = request.contractRevision,
        approved = request.contractAllowlisted,
        reason = if (request.contractAllowlisted) "contract allowlist" else "autonomous mode: D-class effect not allowlisted by the contract",
    )

    override suspend fun resolve(proposal: AmendmentProposal): Resolution = when {
        proposal.weakening -> Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Rejected, AUTHORITY, "policy never auto-accepts a weakening")
        policy.acceptNonWeakening -> Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Accepted, AUTHORITY, "non-weakening, accepted by policy")
        else -> Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Pending, AUTHORITY, "autonomous mode: pending host decision")
    }

    override suspend fun review(request: ReviewRequest): Verdict? = null

    public companion object {
        public const val AUTHORITY: String = "policy:autonomous"
    }
}

public enum class ReplyValidity { Current, Superseded }

/** Late replies are revalidated against the current contract revision before use (TODO P0.4.2). */
public object Replies {
    @JvmStatic
    public fun check(replyRevision: Int, currentRevision: Int): ReplyValidity =
        if (replyRevision == currentRevision) ReplyValidity.Current else ReplyValidity.Superseded

    @JvmStatic
    public fun check(answer: Answer, currentRevision: Int): ReplyValidity = check(answer.contractRevision, currentRevision)

    @JvmStatic
    public fun check(decision: Decision, currentRevision: Int): ReplyValidity = check(decision.contractRevision, currentRevision)

    @JvmStatic
    public fun check(resolution: Resolution, currentRevision: Int): ReplyValidity = check(resolution.contractRevision, currentRevision)

    @JvmStatic
    public fun check(verdict: Verdict, currentRevision: Int): ReplyValidity = check(verdict.contractRevision, currentRevision)
}
