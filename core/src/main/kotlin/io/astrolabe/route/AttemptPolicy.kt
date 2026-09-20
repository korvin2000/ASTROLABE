package io.astrolabe.route

import io.astrolabe.budget.Budget
import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.util.Collections

public enum class AttemptOutcome { Accepted, Failed, Blocked, Cancelled, Exhausted }

/** Probability is conditional on the complete supplied state; null is unknown, never inferred. */
public data class AttemptBranch(val id: String, val target: String, val probability: BigDecimal?, val cost: Money) {
    init {
        attemptLabel(id); attemptLabel(target)
        require(probability == null || probability >= BigDecimal.ZERO && probability <= BigDecimal.ONE)
    }
}

public sealed interface AttemptState {
    public data class Terminal(val outcome: AttemptOutcome, val cost: Money) : AttemptState

    /** One substantive attempt; conditional helpers/review/integration are charged on its branches. */
    public class Step(public val cost: Money, public val exhaustedCost: Money, branches: List<AttemptBranch>) : AttemptState {
        public val branches: List<AttemptBranch> = attemptList(branches.sortedBy { it.id })
    }
}

/** Frozen offline projection, not a runtime policy or a source of measured probabilities. D-61. */
public class AttemptPolicy(
    public val id: String,
    public val version: String,
    public val provenance: String,
    public val initial: String,
    public val currency: String,
    states: Map<String, AttemptState>,
) {
    public val states: Map<String, AttemptState> = attemptMap(states.toSortedMap())
    init {
        attemptLabel(id); attemptLabel(version); attemptLabel(initial); require(provenance.isNotBlank())
        require(currency.length == 3 && currency.all { it in 'A'..'Z' })
        this.states.forEach { (id, state) ->
            attemptLabel(id)
            val charges = when (state) {
                is AttemptState.Terminal -> listOf(state.cost)
                is AttemptState.Step -> listOf(state.cost, state.exhaustedCost) + state.branches.map { it.cost }
            }
            charges.forEach { require(it.currency == currency && it.amount.signum() >= 0) { "invalid policy charge" } }
        }
    }
}

/** Bound rolling DP work; decimal precision/scale bounds refuse oversized exact arithmetic, never round. */
public data class AttemptLimits @JvmOverloads constructor(
    val maxStateEvaluations: Long,
    val maxDecimalDigits: Int = 10_000,
) {
    init { require(maxStateEvaluations >= 0 && maxDecimalDigits > 0) }
}

public enum class AttemptEstimateStatus { Known, Unknown, InvalidInput, ResourceLimit }

public class AttemptEstimate internal constructor(
    public val policy: AttemptPolicy,
    public val remainingAttempts: Int,
    public val status: AttemptEstimateStatus,
    public val expectedCost: Money?,
    terminalProbabilities: Map<AttemptOutcome, BigDecimal>?,
    issues: List<String>,
) {
    public val terminalProbabilities: Map<AttemptOutcome, BigDecimal>? = terminalProbabilities?.let(::attemptMap)
    public val acceptanceProbability: BigDecimal? get() = terminalProbabilities?.get(AttemptOutcome.Accepted)
    public val issues: List<String> = attemptList(issues)
}

public enum class AttemptGate { Pass, Fail, Unknown }

/** Gate evidence and conservative estimate are supplied by the caller, not derived from expected cost. */
public data class AttemptCandidate(
    val policy: AttemptPolicy,
    val eligibility: AttemptGate,
    val floors: AttemptGate,
    val conservativeCost: Money,
)

/** remainingCost is a caller's current budget snapshot; monetary reserves are explicitly supplied. */
public class AttemptSelectionInput(
    candidates: List<AttemptCandidate>,
    public val budget: Budget,
    public val remainingAttempts: Int,
    public val remainingCost: Money,
    public val reservedCost: Money,
) {
    public val candidates: List<AttemptCandidate> = attemptList(candidates.sortedBy { it.policy.id })
    init {
        require(remainingAttempts in 0..budget.attempts)
        require(this.candidates.map { it.policy.id }.distinct().size == this.candidates.size)
        val currency = remainingCost.currency
        require(currency.length == 3 && currency.all { it in 'A'..'Z' })
        val costs = listOfNotNull(remainingCost, reservedCost, budget.cost) + this.candidates.map { it.conservativeCost }
        costs.forEach { require(it.currency == currency && it.amount.signum() >= 0) }
        require(this.candidates.all { it.policy.currency == currency })
        budget.cost?.takeUnless { it.unknown || remainingCost.unknown }?.let {
            require(remainingCost.amount <= it.amount) { "remaining cost exceeds total budget" }
        }
    }
}

public enum class AttemptSelectionStatus { Selected, Refused, Unknown, InvalidInput, ResourceLimit }

public class AttemptSelection internal constructor(
    public val input: AttemptSelectionInput,
    public val status: AttemptSelectionStatus,
    public val selectedPolicyId: String?,
    estimates: Map<String, AttemptEstimate>,
    exclusions: Map<String, String>,
) {
    public val estimates: Map<String, AttemptEstimate> = attemptMap(estimates)
    public val exclusions: Map<String, String> = attemptMap(exclusions)
}

private fun attemptLabel(value: String) { require(value.isNotBlank() && value == value.trim()) }
private fun <T> attemptList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
private fun <K, V> attemptMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
