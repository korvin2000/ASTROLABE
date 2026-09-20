package io.astrolabe.route

import io.astrolabe.provider.Money
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.random.Random

class AttemptCostTest {
    private fun usd(amount: String) = Money("USD", BigDecimal(amount))
    private val limits = AttemptLimits(100_000)
    private val zero = usd("0")
    private fun policy(states: Map<String, AttemptState>, initial: String = "start", id: String = "policy") =
        AttemptPolicy(id, "v1", "synthetic", initial, "USD", states)
    private fun terminal(outcome: AttemptOutcome = AttemptOutcome.Accepted, cost: String = "0") =
        AttemptState.Terminal(outcome, usd(cost))
    private fun edge(target: String, probability: String = "1", cost: String = "0") =
        AttemptBranch(target, target, BigDecimal(probability), usd(cost))
    private fun single(cost: String, id: String = "policy") = policy(mapOf(
        "start" to AttemptState.Step(usd(cost), zero, listOf(edge("end"))), "end" to terminal(),
    ), id = id)
    private fun input(candidates: List<AttemptCandidate>, remaining: Money = usd("20"), reserve: Money = usd("1")) =
        AttemptSelectionInput(candidates, Budget(2, 10, Tokens(100), 2, cost = usd("20")), 2, remaining, reserve)
    private fun candidate(policy: AttemptPolicy, conservative: String = "10") =
        AttemptCandidate(policy, AttemptGate.Pass, AttemptGate.Pass, usd(conservative))
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual!!))

    @Test fun `cheap first attempt is more expensive after mandatory fallback`() {
        val policy = AttemptPolicy("cheap", "v1", "synthetic conditional outcomes", "start", "USD", mapOf(
            "start" to AttemptState.Step(usd("1"), usd("0"), listOf(
                AttemptBranch("success", "accepted", BigDecimal("0.5"), usd("0")),
                AttemptBranch("failure", "retry", BigDecimal("0.5"), usd("0")),
            )),
            "retry" to AttemptState.Step(usd("8"), usd("0"), listOf(
                AttemptBranch("success", "accepted", BigDecimal.ONE, usd("0")),
            )),
            "accepted" to AttemptState.Terminal(AttemptOutcome.Accepted, usd("0")),
        ))
        val result = AttemptCost.evaluate(policy, 2, limits)
        assertEquals(AttemptEstimateStatus.Known, result.status)
        assertEquals(0, result.expectedCost!!.amount.compareTo(BigDecimal("5")))
        assertEquals(0, result.acceptanceProbability!!.compareTo(BigDecimal.ONE))
        val selected = AttemptCost.select(input(listOf(candidate(policy), candidate(single("4", "direct")))), limits)
        assertEquals(AttemptSelectionStatus.Selected, selected.status)
        assertEquals("direct", selected.selectedPolicyId)
    }

    @Test fun `all terminal reasons and conditional auxiliary charges remain in total cost`() {
        val outcomes = AttemptOutcome.entries
        val states = outcomes.associate { it.name to terminal(it, "2") }.toMutableMap<String, AttemptState>()
        states["start"] = AttemptState.Step(usd("1"), usd("9"), outcomes.map {
            AttemptBranch(it.name, it.name, BigDecimal("0.2"), usd("3"))
        })
        val result = AttemptCost.evaluate(policy(states), 1, limits)
        decimal("6", result.expectedCost?.amount)
        outcomes.forEach { decimal("0.2", result.terminalProbabilities?.get(it)) }
        decimal("1", result.terminalProbabilities!!.values.reduce(BigDecimal::add))
    }

    @Test fun `horizon charges cleanup without attempt and still visits terminal after final attempt`() {
        val p = policy(mapOf("start" to AttemptState.Step(usd("3"), usd("7"), listOf(edge("end", cost = "2"))),
            "end" to terminal(cost = "5")))
        val empty = AttemptCost.evaluate(p, 0, limits)
        decimal("7", empty.expectedCost?.amount)
        decimal("0", empty.acceptanceProbability)
        decimal("1", empty.terminalProbabilities?.get(AttemptOutcome.Exhausted))
        decimal("10", AttemptCost.evaluate(p, 1, limits).expectedCost?.amount)
        decimal("5", AttemptCost.evaluate(policy(mapOf("start" to terminal(cost = "5"))), 0, limits).expectedCost?.amount)
    }

    @Test fun `cyclic failure exhausts a finite shared allowance`() {
        val p = policy(mapOf("start" to AttemptState.Step(usd("2"), usd("7"), listOf(edge("start")))))
        val result = AttemptCost.evaluate(p, 4, limits)
        decimal("15", result.expectedCost?.amount)
        decimal("1", result.terminalProbabilities?.get(AttemptOutcome.Exhausted))
        decimal("0", result.acceptanceProbability)
    }

    @Test fun `conditional second attempt probability is not replaced by a marginal rate`() {
        val p = policy(mapOf(
            "start" to AttemptState.Step(usd("1"), zero, listOf(edge("accepted", "0.5"), edge("retry", "0.5"))),
            "retry" to AttemptState.Step(usd("8"), zero, listOf(edge("accepted", "0.2"), edge("failed", "0.8"))),
            "accepted" to terminal(), "failed" to terminal(AttemptOutcome.Failed),
        ))
        decimal("0.6", AttemptCost.evaluate(p, 2, limits).acceptanceProbability)
    }

    @Test fun `zero probability excludes unknown costs and distributions but positive probability propagates them`() {
        val unknown = zero.copy(unknown = true)
        fun scenario(p: String) = policy(mapOf(
            "start" to AttemptState.Step(usd("1"), zero, listOf(
                edge("accepted", BigDecimal.ONE.subtract(BigDecimal(p)).toPlainString()),
                AttemptBranch("bad", "bad", BigDecimal(p), unknown),
            )),
            "accepted" to terminal(),
            "bad" to AttemptState.Step(unknown, unknown, listOf(AttemptBranch("bad", "bad", null, unknown))),
        ))
        assertEquals(AttemptEstimateStatus.Known, AttemptCost.evaluate(scenario("0"), 2, limits).status)
        val positive = AttemptCost.evaluate(scenario("0.01"), 2, limits)
        assertEquals(AttemptEstimateStatus.Unknown, positive.status)
        assertNull(positive.expectedCost); assertNull(positive.acceptanceProbability)
    }

    @Test fun `unknown price does not erase known acceptance and zero horizon ignores unused probabilities`() {
        val p = policy(mapOf("start" to AttemptState.Terminal(AttemptOutcome.Accepted, zero.copy(unknown = true))))
        val result = AttemptCost.evaluate(p, 0, limits)
        assertEquals(AttemptEstimateStatus.Unknown, result.status)
        assertNull(result.expectedCost); decimal("1", result.acceptanceProbability)
        val unused = policy(mapOf("start" to AttemptState.Step(zero, usd("2"), listOf(
            AttemptBranch("unknown", "start", null, zero),
        ))))
        decimal("2", AttemptCost.evaluate(unused, 0, limits).expectedCost?.amount)
        assertEquals(AttemptEstimateStatus.Unknown, AttemptCost.evaluate(unused, 1, limits).status)
    }

    @Test fun `probabilities and graph references are validated without normalization`() {
        for (p in listOf("0", "0.99999999999999999999")) {
            val invalid = policy(mapOf("start" to AttemptState.Step(zero, zero, listOf(edge("start", p)))))
            assertEquals(AttemptEstimateStatus.InvalidInput, AttemptCost.evaluate(invalid, 1, limits).status)
        }
        for (branches in listOf(emptyList(), listOf(edge("missing")), listOf(edge("start", "0.5"), edge("start", "0.5")),
            listOf(edge("start"), AttemptBranch("unknown", "start", BigDecimal("0.1"), zero)))) {
            assertEquals(AttemptEstimateStatus.InvalidInput, AttemptCost.evaluate(policy(mapOf(
                "start" to AttemptState.Step(zero, zero, branches))), 1, limits).status)
        }
        assertEquals(AttemptEstimateStatus.InvalidInput, AttemptCost.evaluate(policy(emptyMap()), 0, limits).status)
        for (p in listOf("-0.1", "1.01")) assertFailsWith<IllegalArgumentException> { edge("start", p) }
        assertFailsWith<IllegalArgumentException> { single("-1") }
        assertFailsWith<IllegalArgumentException> { policy(mapOf("start" to AttemptState.Terminal(
            AttemptOutcome.Accepted, Money("EUR", BigDecimal.ONE)))) }
        assertFailsWith<IllegalArgumentException> { AttemptCost.evaluate(single("1"), -1, limits) }
    }

    @Test fun `point probabilities are never inferred from remaining mass`() {
        val p = policy(mapOf("start" to AttemptState.Step(zero, zero, listOf(
            edge("end"), AttemptBranch("unknown", "end", null, zero))), "end" to terminal()))
        assertEquals(AttemptEstimateStatus.Unknown, AttemptCost.evaluate(p, 1, limits).status)
    }

    @Test fun `resource limits and exact decimal amounts never become rounded answers`() {
        assertEquals(AttemptEstimateStatus.ResourceLimit, AttemptCost.evaluate(single("1"), Int.MAX_VALUE, limits).status)
        assertEquals(AttemptEstimateStatus.ResourceLimit, AttemptCost.evaluate(single("1"), 1, AttemptLimits(3)).status)
        assertEquals(AttemptEstimateStatus.Known, AttemptCost.evaluate(single("1"), 1, AttemptLimits(4)).status)
        val huge = "10000000000000000000000000000000000000000.00000000000000000001"
        decimal(huge, AttemptCost.evaluate(single(huge), 1, limits).expectedCost?.amount)
        assertEquals(AttemptEstimateStatus.ResourceLimit, AttemptCost.evaluate(single("1E+10001"), 1, limits).status)
        val tiny = policy(mapOf("start" to AttemptState.Step(zero, zero, listOf(edge("start", "1E-2147483647")))))
        assertEquals(AttemptEstimateStatus.ResourceLimit, AttemptCost.evaluate(tiny, 1, limits).status)
        val growing = policy(mapOf("start" to AttemptState.Step(usd("9"), zero, listOf(edge("start")))))
        assertEquals(AttemptEstimateStatus.ResourceLimit, AttemptCost.evaluate(growing, 2, AttemptLimits(100, 1)).status)
        assertEquals(AttemptSelectionStatus.ResourceLimit, AttemptCost.select(input(listOf(candidate(single("1"))),
            reserve = usd("1E-2147483647")), limits).status)
    }

    @Test fun `selection minimizes full expected cost without substituting cost per acceptance`() {
        val rarelyAccepted = policy(mapOf(
            "start" to AttemptState.Step(usd("1"), zero, listOf(edge("yes", "0.01"), edge("no", "0.99"))),
            "yes" to terminal(), "no" to terminal(AttemptOutcome.Failed),
        ), id = "rare")
        val result = AttemptCost.select(input(listOf(candidate(rarelyAccepted), candidate(single("2", "sure")))), limits)
        assertEquals("rare", result.selectedPolicyId)
        decimal("0.01", result.estimates.getValue("rare").acceptanceProbability)
    }

    @Test fun `eligibility and floors exclude cheap policies and reserves use conservative cost`() {
        val good = candidate(single("4", "good"), "10")
        val bad = candidate(single("1", "bad"))
        for (excluded in listOf(bad.copy(eligibility = AttemptGate.Fail), bad.copy(floors = AttemptGate.Fail),
            bad.copy(conservativeCost = usd("19.01")))) {
            val result = AttemptCost.select(input(listOf(good, excluded)), limits)
            assertEquals("good", result.selectedPolicyId)
            assertEquals(1, result.exclusions.size)
        }
        val equality = AttemptCost.select(input(listOf(good.copy(conservativeCost = usd("19")))), limits)
        assertEquals("good", equality.selectedPolicyId)
        assertEquals(AttemptSelectionStatus.Refused, AttemptCost.select(input(listOf(good), reserve = usd("21")), limits).status)
        for (unknown in listOf(bad.copy(eligibility = AttemptGate.Unknown), bad.copy(floors = AttemptGate.Unknown),
            bad.copy(conservativeCost = zero.copy(unknown = true)))) {
            val result = AttemptCost.select(input(listOf(good, unknown)), limits)
            assertEquals(AttemptSelectionStatus.Unknown, result.status); assertNull(result.selectedPolicyId)
        }
        assertEquals(AttemptSelectionStatus.Unknown, AttemptCost.select(input(listOf(good), remaining = zero.copy(unknown = true)), limits).status)
        assertEquals(AttemptSelectionStatus.Unknown, AttemptCost.select(input(listOf(good), reserve = zero.copy(unknown = true)), limits).status)
        assertFailsWith<IllegalArgumentException> { input(listOf(good), remaining = usd("21")) }
        assertFailsWith<IllegalArgumentException> { input(listOf(good, good)) }
        assertFailsWith<IllegalArgumentException> { AttemptSelectionInput(listOf(good), input(listOf(good)).budget, 3, usd("20"), zero) }
    }

    @Test fun `selection reports unknown invalid or limited admitted models without claiming minimum`() {
        val policies = listOf(
            policy(mapOf("start" to AttemptState.Terminal(AttemptOutcome.Failed, zero.copy(unknown = true)))),
            policy(emptyMap()),
        )
        for ((p, status) in policies.zip(listOf(AttemptSelectionStatus.Unknown, AttemptSelectionStatus.InvalidInput))) {
            val result = AttemptCost.select(input(listOf(candidate(p), candidate(single("1", "known")))), limits)
            assertEquals(status, result.status); assertNull(result.selectedPolicyId)
        }
        assertEquals(AttemptSelectionStatus.ResourceLimit, AttemptCost.select(input(listOf(candidate(single("1")))), AttemptLimits(0)).status)
    }

    @Test fun `input collections are immutable and ordering changes neither total nor tie selection`() {
        val branches = mutableListOf(edge("end"))
        val step = AttemptState.Step(usd("1"), zero, branches)
        val states = mutableMapOf<String, AttemptState>("start" to step, "end" to terminal())
        val p = policy(states)
        branches.clear(); states.clear()
        val candidates = mutableListOf(candidate(p), candidate(single("1", "aaa")))
        val frozen = input(candidates); candidates.clear()
        val result = AttemptCost.select(frozen, limits)
        assertEquals("aaa", result.selectedPolicyId)
        assertSame(p, result.estimates.getValue("policy").policy)
        assertFailsWith<UnsupportedOperationException> { (p.states as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (step.branches as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.estimates as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.estimates.getValue("policy").terminalProbabilities as MutableMap).clear() }
    }

    @Test fun `rolling recurrence matches independent enumeration of 300 seeded cyclic policies`() {
        val random = Random(454)
        repeat(300) {
            val count = random.nextInt(1, 6)
            val states = linkedMapOf<String, AttemptState>()
            AttemptOutcome.entries.forEach { states[it.name] = terminal(it, random.nextInt(4).toString()) }
            val targets = (0 until count).map { "s$it" } + AttemptOutcome.entries.map { it.name }
            repeat(count) { i ->
                val p = BigDecimal(random.nextInt(11)).movePointLeft(1)
                states["s$i"] = AttemptState.Step(usd(random.nextInt(8).toString()), usd(random.nextInt(4).toString()), listOf(
                    AttemptBranch("a", targets.random(random), p, usd(random.nextInt(3).toString())),
                    AttemptBranch("b", targets.random(random), BigDecimal.ONE.subtract(p), usd(random.nextInt(3).toString())),
                ))
            }
            val horizon = random.nextInt(6)
            var expectedCost = BigDecimal.ZERO
            val mass = AttemptOutcome.entries.associateWith { BigDecimal.ZERO }.toMutableMap()
            fun paths(id: String, h: Int, probability: BigDecimal, incurred: BigDecimal) {
                when (val state = states.getValue(id)) {
                    is AttemptState.Terminal -> {
                        mass[state.outcome] = mass.getValue(state.outcome).add(probability)
                        expectedCost = expectedCost.add(probability.multiply(incurred.add(state.cost.amount)))
                    }
                    is AttemptState.Step -> if (h == 0) {
                        mass[AttemptOutcome.Exhausted] = mass.getValue(AttemptOutcome.Exhausted).add(probability)
                        expectedCost = expectedCost.add(probability.multiply(incurred.add(state.exhaustedCost.amount)))
                    } else state.branches.forEach { branch -> paths(branch.target, h - 1, probability.multiply(branch.probability!!),
                        incurred.add(state.cost.amount).add(branch.cost.amount)) }
                }
            }
            paths("s0", horizon, BigDecimal.ONE, BigDecimal.ZERO)
            val variants = listOf(states, states.entries.reversed().associate { (id, state) -> id to when (state) {
                is AttemptState.Step -> AttemptState.Step(state.cost, state.exhaustedCost, state.branches.reversed())
                else -> state
            } })
            for (variant in variants) {
                val result = AttemptCost.evaluate(policy(variant, "s0"), horizon, limits)
                assertEquals(AttemptEstimateStatus.Known, result.status)
                decimal(expectedCost.toPlainString(), result.expectedCost?.amount)
                mass.forEach { (outcome, p) -> decimal(p.toPlainString(), result.terminalProbabilities?.get(outcome)) }
            }
        }
    }
}
