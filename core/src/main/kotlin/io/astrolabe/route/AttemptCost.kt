package io.astrolabe.route

import io.astrolabe.provider.Money
import java.math.BigDecimal

/** Finite-horizon evaluation of fixed conditional policies (§11.2–11.3), never a dispatch decision. */
public object AttemptCost {
    @JvmStatic
    public fun evaluate(policy: AttemptPolicy, remainingAttempts: Int, limits: AttemptLimits): AttemptEstimate {
        require(remainingAttempts >= 0)
        fun refuse(status: AttemptEstimateStatus, issues: List<String>) =
            AttemptEstimate(policy, remainingAttempts, status, null, null, issues)
        val numbers = policy.states.values.asSequence().flatMap { state -> when (state) {
            is AttemptState.Terminal -> sequenceOf(state.cost.amount)
            is AttemptState.Step -> sequenceOf(state.cost.amount, state.exhaustedCost.amount) +
                state.branches.asSequence().flatMap { sequenceOf(it.probability, it.cost.amount).filterNotNull() }
        } }
        if (numbers.any { !fits(it, limits) })
            return refuse(AttemptEstimateStatus.ResourceLimit, listOf("input decimal precision/scale limit"))
        val issues = mutableListOf<String>()
        if (policy.initial !in policy.states) issues += "missing initial state ${policy.initial}"
        policy.states.forEach { (id, state) -> if (state is AttemptState.Step) {
            if (state.branches.isEmpty()) issues += "$id has no outcomes"
            if (state.branches.map { it.id }.distinct().size != state.branches.size) issues += "$id has duplicate branches"
            state.branches.filter { it.target !in policy.states }.forEach { issues += "$id missing target ${it.target}" }
            val mass = state.branches.mapNotNull { it.probability }.fold(BigDecimal.ZERO, BigDecimal::add)
            if (mass > BigDecimal.ONE || state.branches.all { it.probability != null } && mass.compareTo(BigDecimal.ONE) != 0)
                issues += "$id probabilities do not sum to one"
        } }
        if (issues.isNotEmpty()) return refuse(AttemptEstimateStatus.InvalidInput, issues)
        if ((remainingAttempts.toLong() + 1) * policy.states.size > limits.maxStateEvaluations)
            return refuse(AttemptEstimateStatus.ResourceLimit, listOf("state evaluation limit"))

        fun bounded(value: BigDecimal): BigDecimal {
            if (!fits(value, limits)) throw DecimalLimit()
            return value
        }
        fun charge(cost: Money): BigDecimal? = if (cost.unknown) null else bounded(cost.amount)
        fun terminal(outcome: AttemptOutcome, cost: Money) = Value(charge(cost),
            AttemptOutcome.entries.associateWith { if (it == outcome) BigDecimal.ONE else BigDecimal.ZERO })

        try {
            var row = policy.states.mapValues { (_, state) -> when (state) {
                is AttemptState.Terminal -> terminal(state.outcome, state.cost)
                is AttemptState.Step -> terminal(AttemptOutcome.Exhausted, state.exhaustedCost)
            } }
            repeat(remainingAttempts) {
                row = policy.states.mapValues { (_, state) -> when (state) {
                    is AttemptState.Terminal -> terminal(state.outcome, state.cost)
                    is AttemptState.Step -> {
                        var cost = charge(state.cost)
                        var mass: MutableMap<AttemptOutcome, BigDecimal>? = AttemptOutcome.entries.associateWith { BigDecimal.ZERO }.toMutableMap()
                        for (branch in state.branches) {
                            val p = branch.probability
                            if (p?.signum() == 0) continue
                            if (p == null) { cost = null; mass = null; continue }
                            val next = row.getValue(branch.target)
                            val edgeCost = charge(branch.cost)
                            cost = if (cost != null && edgeCost != null && next.cost != null)
                                bounded(cost.add(bounded(p.multiply(bounded(edgeCost.add(next.cost)))))) else null
                            if (next.mass == null) mass = null
                            mass?.let { current -> AttemptOutcome.entries.forEach { outcome ->
                                current[outcome] = bounded(current.getValue(outcome).add(bounded(p.multiply(next.mass!!.getValue(outcome)))))
                            } }
                        }
                        Value(cost, mass)
                    }
                } }
            }
            val value = row.getValue(policy.initial)
            val known = value.cost != null && value.mass != null
            return AttemptEstimate(policy, remainingAttempts, if (known) AttemptEstimateStatus.Known else AttemptEstimateStatus.Unknown,
                value.cost?.let { Money(policy.currency, it) }, value.mass, buildList {
                    if (value.cost == null) add("reachable cost or conditional probability is unknown")
                    if (value.mass == null) add("reachable conditional probability is unknown")
                })
        } catch (_: DecimalLimit) {
            return refuse(AttemptEstimateStatus.ResourceLimit, listOf("exact decimal precision/scale limit"))
        }
    }

    /** Compares only proven eligible, floor-compliant and conservatively affordable policies. */
    @JvmStatic
    public fun select(input: AttemptSelectionInput, limits: AttemptLimits): AttemptSelection {
        val estimates = linkedMapOf<String, AttemptEstimate>()
        val exclusions = linkedMapOf<String, String>()
        val charges = listOf(input.remainingCost, input.reservedCost) + input.candidates.map { it.conservativeCost }
        if (charges.any { !fits(it.amount, limits) })
            return AttemptSelection(input, AttemptSelectionStatus.ResourceLimit, null, estimates,
                mapOf("budget" to "input decimal precision/scale limit"))
        var unknown = false
        for (candidate in input.candidates) {
            val id = candidate.policy.id
            when {
                candidate.eligibility == AttemptGate.Fail -> exclusions[id] = "ineligible"
                candidate.floors == AttemptGate.Fail -> exclusions[id] = "floor violation"
                candidate.eligibility == AttemptGate.Unknown || candidate.floors == AttemptGate.Unknown -> {
                    exclusions[id] = "eligibility or floor evidence unknown"; unknown = true
                }
                input.remainingCost.unknown || input.reservedCost.unknown || candidate.conservativeCost.unknown -> {
                    exclusions[id] = "affordability unknown"; unknown = true
                }
                candidate.conservativeCost.amount > input.remainingCost.amount.subtract(input.reservedCost.amount) ->
                    exclusions[id] = "unaffordable after reserves"
                else -> estimates[id] = evaluate(candidate.policy, input.remainingAttempts, limits)
            }
        }
        val status = when {
            estimates.values.any { it.status == AttemptEstimateStatus.InvalidInput } -> AttemptSelectionStatus.InvalidInput
            estimates.values.any { it.status == AttemptEstimateStatus.ResourceLimit } -> AttemptSelectionStatus.ResourceLimit
            unknown || estimates.values.any { it.status == AttemptEstimateStatus.Unknown } -> AttemptSelectionStatus.Unknown
            estimates.isEmpty() -> AttemptSelectionStatus.Refused
            else -> AttemptSelectionStatus.Selected
        }
        val selected = if (status == AttemptSelectionStatus.Selected)
            estimates.values.minWith(compareBy<AttemptEstimate> { it.expectedCost!!.amount }.thenBy { it.policy.id }).policy.id else null
        return AttemptSelection(input, status, selected, estimates, exclusions)
    }
}

private data class Value(val cost: BigDecimal?, val mass: Map<AttemptOutcome, BigDecimal>?)
private class DecimalLimit : RuntimeException()
private fun fits(value: BigDecimal, limits: AttemptLimits): Boolean =
    value.precision() <= limits.maxDecimalDigits && kotlin.math.abs(value.scale().toLong()) <= limits.maxDecimalDigits
