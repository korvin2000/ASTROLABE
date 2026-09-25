package io.astrolabe.recover

import io.astrolabe.context.RebuildReason
import io.astrolabe.id.AttemptId
import io.astrolabe.id.IdGen
import io.astrolabe.provider.Profile
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Decision
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.Escalation
import io.astrolabe.route.EscalationChange
import io.astrolabe.route.EscalationStep
import io.astrolabe.route.SubstantiveAttempt
import io.astrolabe.route.VerifiedFailure
import java.util.Collections

/**
 * An opened alternative attempt (§13.3): a controller-assigned [attempt] id for the same contract, the previous STATE's
 * Dead ends and Decisions attached, an empty transcript tail ([rebuild] has `m = 0`), optionally the escalation
 * [profile]; the previous attempt's receipts are kept, and [allowance] already counts this attempt.
 */
public class AlternativeAttempt internal constructor(
    public val increment: String,
    public val previous: AttemptId,
    public val attempt: AttemptId,
    public val contractVersion: Int,
    deadEnds: List<DeadEnd>,
    decisions: List<Decision>,
    keptReceipts: List<String>,
    public val substantive: SubstantiveAttempt,
    public val profile: Profile?,
    public val allowance: AttemptAllowance,
    public val reason: String,
) {
    public val deadEnds: List<DeadEnd> = Collections.unmodifiableList(ArrayList(deadEnds))
    public val decisions: List<Decision> = Collections.unmodifiableList(ArrayList(decisions))
    public val keptReceipts: List<String> = Collections.unmodifiableList(ArrayList(keptReceipts))

    /** The rebuild that installs the attempt's context: no transcript, dead ends emphasised (P2.5.1). */
    public val rebuild: RebuildReason.AlternativeAttempt get() = RebuildReason.AlternativeAttempt(profile)
}

/** Whether an alternative attempt was opened. */
public sealed interface AlternativeDecision {
    public data class Opened(val alternative: AlternativeAttempt) : AlternativeDecision

    /** Refine in place, recover more cheaply, or stop: [reason] says which rule held. */
    public data class Refused(val reason: String) : AlternativeDecision
}

/** One attempt's acceptance evidence for selection: the requirements its receipts verify at the current stamp. */
public data class AttemptEvidence @JvmOverloads constructor(
    val attempt: AttemptId,
    val receipts: List<String>,
    val verified: Set<String>,
    val required: Set<String>,
    /** The attempt's own account of itself; never read by selection. */
    val claim: String = "",
) {
    init {
        require(required.isNotEmpty()) { "selection needs the requirements to accept" }
    }

    /** Every required requirement is verified by the attempt's receipts. */
    public val accepted: Boolean get() = verified.containsAll(required)
}

/**
 * Alternative attempts (§13.3) as pure policy over records. An alternative opens only when the same hypothesis failed
 * at least twice after simpler recovery (a refinement in place is cheaper for a local defect), with a new
 * controller-assigned id that consumes the increment's `budget.attempts` like any substantive attempt (D-154).
 */
public object Alternative {
    /** Verified failures of one hypothesis before an alternative is warranted (§13.3 "fails twice"). */
    public const val SAME_HYPOTHESIS_FAILURES: Int = 2

    @JvmStatic
    @JvmOverloads
    public fun open(
        allowance: AttemptAllowance,
        failures: List<VerifiedFailure>,
        register: Register,
        keptReceipts: List<String>,
        contractVersion: Int,
        hypothesis: String,
        idGen: IdGen,
        profile: Profile? = null,
    ): AlternativeDecision {
        val previous = allowance.attempts.lastOrNull() ?: return AlternativeDecision.Refused("no substantive attempt of ${allowance.increment} to replace")
        if (failures.size < SAME_HYPOTHESIS_FAILURES) {
            return AlternativeDecision.Refused("the hypothesis failed ${failures.size} time(s): refine in place before branching (§13.3)")
        }
        // §13.3: no speculative branching before simpler recovery — a class the ladder still owns is not a failed hypothesis.
        failures.firstOrNull { it.failureClass != FailureClass.BehaviouralTestFailure && it.failureClass != FailureClass.RepeatedFailedHypothesis }?.let {
            return AlternativeDecision.Refused("${it.failureClass.name} is recovered by ${it.failureClass.handler.name}, not by an alternative attempt")
        }
        val last = failures.last()
        val step = Escalation.next(allowance, VerifiedFailure(FailureClass.RepeatedFailedHypothesis, last.detail, failures.flatMap { it.evidenceRefs }.distinct()))
        val tier = when (step) {
            is EscalationStep.Escalate -> step.tier
            is EscalationStep.Blocked -> return AlternativeDecision.Refused(step.reason)
            is EscalationStep.Ask -> return AlternativeDecision.Refused("ask first: ${step.question}")
            is EscalationStep.NotEscalated -> return AlternativeDecision.Refused(step.reason)
        }
        val attempt = AttemptId(idGen.next("att"))
        val proposed = SubstantiveAttempt(attempt, tier, profile?.id ?: previous.profile, hypothesis, EscalationChange.AlternativeAttempt, step.evidenceRefs)
        Escalation.admit(allowance, proposed)?.let { return AlternativeDecision.Refused(it) }
        return AlternativeDecision.Opened(AlternativeAttempt(
            allowance.increment, previous.attempt, attempt, contractVersion, register.deadEnds, register.decisions,
            (previous.evidenceRefs + keptReceipts).distinct(), proposed, profile, allowance.plus(proposed),
            "${previous.attempt.value} failed '${previous.hypothesis}' ${failures.size} times: ${last.detail}",
        ))
    }

    /**
     * Selection by acceptance evidence, never by plurality over text: the first attempt (in opening order) whose receipts
     * verify every required requirement; `null` when none does. Claims and how many attempts agree are not read.
     */
    @JvmStatic
    public fun select(attempts: List<AttemptEvidence>): AttemptEvidence? = attempts.firstOrNull { it.accepted }
}
