package io.astrolabe.route

import io.astrolabe.id.AttemptId
import io.astrolabe.recover.FailureClass
import java.util.Collections

/** The stated change an escalated attempt makes (§11.3); without one a new attempt is the old one under a new label. */
public enum class EscalationChange { StrongerModel, ProbeEvidence, NarrowerIncrement, DifferentTool, AlternativeAttempt }

/**
 * One substantive attempt of an increment (§11.3, §13.3): the attempt id that ran it, its tier and profile, the
 * hypothesis it pursued and the change it was opened with (`null` for the initial attempt). Transport retries and
 * scoped repair calls are not substantive attempts; they keep their own bounded counters.
 */
public data class SubstantiveAttempt @JvmOverloads constructor(
    val attempt: AttemptId,
    val tier: Tier,
    val profile: String,
    val hypothesis: String,
    val change: EscalationChange? = null,
    val evidenceRefs: List<String> = emptyList(),
) {
    init {
        require(tier.model) { "a substantive attempt runs a model tier" }
        require(profile.isNotBlank() && hypothesis.isNotBlank()) { "an attempt names its profile and hypothesis" }
    }

    /** What makes two attempts the same attempt: the attempt id and any label are not part of it. */
    internal val substance: Triple<Tier, String, String> get() = Triple(tier, profile, hypothesis.trim().lowercase())
}

/**
 * A failure of an attempt as verification established it (§11.3: escalation happens on **verified** failure only).
 * [question] is the missing information that changes the intended result — an ambiguous business oracle, say —
 * which goes to the user through `task.ask` rather than to a stronger model (§13.2 step 5, FX-31).
 */
public data class VerifiedFailure @JvmOverloads constructor(
    val failureClass: FailureClass,
    val detail: String,
    val evidenceRefs: List<String>,
    val question: String? = null,
) {
    init {
        require(detail.isNotBlank()) { "a verified failure states what failed" }
        require(evidenceRefs.isNotEmpty()) { "a verified failure carries its evidence" }
        require(question == null || question.isNotBlank())
    }
}

/**
 * `budget.attempts` of one increment (§11.3, §13.3; default 2: the initial attempt plus one alternative or escalated
 * attempt). It counts the substantive attempts made, keyed by the increment, never by attempt id: a controller-assigned
 * new attempt id never replenishes it.
 */
public class AttemptAllowance @JvmOverloads constructor(
    public val increment: String,
    public val budget: Int,
    attempts: List<SubstantiveAttempt> = emptyList(),
) {
    public val attempts: List<SubstantiveAttempt> = Collections.unmodifiableList(ArrayList(attempts))

    init {
        require(increment.isNotBlank()) { "an allowance belongs to an increment" }
        require(budget > 0) { "budget.attempts is positive" }
        require(this.attempts.size <= budget) { "${this.attempts.size} attempts exceed budget.attempts $budget" }
    }

    public val used: Int get() = attempts.size
    public val remaining: Int get() = budget - attempts.size

    /** The allowance after [attempt]; refused exactly when [Escalation.admit] refuses it. */
    public fun plus(attempt: SubstantiveAttempt): AttemptAllowance {
        Escalation.admit(this, attempt)?.let { throw IllegalStateException(it) }
        return AttemptAllowance(increment, budget, attempts + attempt)
    }

    override fun equals(other: Any?): Boolean =
        other is AttemptAllowance && other.increment == increment && other.budget == budget && other.attempts == attempts

    override fun hashCode(): Int = (increment.hashCode() * 31 + budget) * 31 + attempts.hashCode()

    override fun toString(): String = "AttemptAllowance($increment, $used/$budget)"
}

/** What the ladder decides after an attempt ended. */
public sealed interface EscalationStep {
    /** Open the next substantive attempt at [tier] with the stated [change] and the failure evidence attached. */
    public data class Escalate(val tier: Tier, val change: EscalationChange, val evidenceRefs: List<String>, val reason: String) : EscalationStep {
        /** The line the next attempt's context carries: the evidence and the stated change (§11.3). */
        public val line: String get() = "escalated to $tier (${change.name}): $reason · evidence ${evidenceRefs.joinToString(", ")}"
    }

    /** Missing information changes the intended result: ask the user; never invent the expected behaviour (FX-31). */
    public data class Ask(val question: String, val evidenceRefs: List<String>) : EscalationStep

    /** `budget.attempts` is spent: the increment is blocked with its evidence. */
    public data class Blocked(val reason: String, val evidenceRefs: List<String>) : EscalationStep

    /** Not an escalation: the outcome is unverified, or the class returns to its handler through `recover()` (§13.1). */
    public data class NotEscalated(val reason: String) : EscalationStep
}

/**
 * The escalation ladder of §11.3 as a pure function of records: attempt at tier N → verification → on a verified
 * failure, N+1 with the evidence and a stated change; at most `budget.attempts` per increment, then blocked. The class
 * of the failure picks the change (D-150); a failure whose missing information changes the intended result asks.
 */
public object Escalation {
    @JvmStatic
    @JvmOverloads
    public fun next(allowance: AttemptAllowance, failure: VerifiedFailure?, row: FunctionRow = FunctionTable.DEFAULT.row(RoutingFunction.Implementing)): EscalationStep {
        if (failure == null) return EscalationStep.NotEscalated("the outcome is unverified: escalation needs a verified failure")
        val last = allowance.attempts.lastOrNull()
            ?: return EscalationStep.NotEscalated("no substantive attempt of ${allowance.increment} is recorded")
        // §13.2 step 5: a stronger model still lacks the intent; only the user can supply it.
        failure.question?.let { return EscalationStep.Ask(it, failure.evidenceRefs) }
        val kind = failure.failureClass
        val change = when (kind) {
            FailureClass.BehaviouralTestFailure -> if (last.tier == Tier.ExtraHigh) EscalationChange.AlternativeAttempt else EscalationChange.StrongerModel
            FailureClass.RepeatedFailedHypothesis -> EscalationChange.AlternativeAttempt
            FailureClass.MissingRepositoryContract -> EscalationChange.ProbeEvidence
            FailureClass.BuildEnvironment -> EscalationChange.DifferentTool
            else -> return EscalationStep.NotEscalated("${kind.name} returns to ${kind.handler.name} through recover(): ${kind.response}")
        }
        if (allowance.remaining <= 0) {
            return EscalationStep.Blocked(
                "budget.attempts ${allowance.budget} spent on ${allowance.increment} (${allowance.attempts.joinToString(", ") { "${it.attempt.value}@${it.tier}" }}); " +
                    "a new attempt id never replenishes it — last failure: ${failure.detail}",
                failure.evidenceRefs,
            )
        }
        val promoted = change == EscalationChange.StrongerModel || change == EscalationChange.AlternativeAttempt
        val tier = listOfNotNull(if (promoted) last.tier.promoted() else last.tier, row.neverBelow).max()
        return EscalationStep.Escalate(tier, change, failure.evidenceRefs, "${kind.name} after ${last.attempt.value}@${last.tier}: ${failure.detail}")
    }

    /**
     * Whether [proposed] may open as the next substantive attempt: within `budget.attempts`, a later attempt states its
     * change, and repeating an earlier attempt — same tier, profile and hypothesis — under a new label is not recovery.
     */
    @JvmStatic
    public fun admit(allowance: AttemptAllowance, proposed: SubstantiveAttempt): String? = when {
        allowance.remaining <= 0 -> "budget.attempts ${allowance.budget} spent on ${allowance.increment}; ${proposed.attempt.value} would be attempt ${allowance.used + 1}"
        allowance.attempts.isNotEmpty() && proposed.change == null -> "a later attempt states its change (§11.3)"
        allowance.attempts.any { it.substance == proposed.substance } ->
            "repeating ${proposed.profile}@${proposed.tier} on the same hypothesis under a new label is not recovery"
        else -> null
    }
}
