package io.astrolabe.route

import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import java.math.BigDecimal
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DagScheduleTest {
    @Test fun `diamond keeps prerequisites and includes fixed and initial charges`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b", "a"), inc("c", "a"), inc("d", "b", "c")))
        val ids = graph.increments.map { it.id }.toSet()
        val costs = costs(ids, mapOf(ScheduleEdge("b", "c") to money("0.25")))
        val result = DagSchedule.solve(ScheduleProblem(graph, ids, emptySet(), emptySet(), true, costs),
            ScheduleLimits(10, 100000, 100000))
        assertEquals(ScheduleStatus.Optimal, result.status)
        assertEquals(listOf("a", "b", "c", "d"), result.order)
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal("11.25")))
    }

    @Test fun `optimal schedule beats nearest next choice`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b"), inc("c")))
        val ids = setOf("a", "b", "c")
        val base = costs(ids, mapOf(ScheduleEdge("a", "b") to money("1"),
            ScheduleEdge("a", "c") to money("2"), ScheduleEdge("b", "c") to money("100"),
            ScheduleEdge("c", "b") to money("1")))
        val matrix = copyCosts(base, initial = mapOf("a" to money("0"), "b" to money("1000"), "c" to money("1000")))
        val result = solve(graph, matrix)
        assertEquals(listOf("a", "c", "b"), result.order)
        assertEquals(0, result.variableCost!!.amount.compareTo(BigDecimal("3")))
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal("9")))
        assertTrue(result.totalCost.amount < BigDecimal("107")) // greedy a,b,c plus fixed charges
    }

    @Test fun `external prerequisites need explicit completion and verified work never reruns`() {
        val graph = RequirementGraph(listOf(inc("a", "outside")))
        val matrix = costs(setOf("a"))
        val missing = solve(graph, matrix)
        assertEquals(ScheduleStatus.UnsatisfiedPrerequisites, missing.status)
        val ready = DagSchedule.solve(ScheduleProblem(graph, setOf("a"), setOf("outside"), emptySet(), true, matrix), limits)
        assertEquals(ScheduleStatus.Optimal, ready.status)
        val verified = inc("a").copy(status = IncrementStatus.Verified)
        assertEquals(ScheduleStatus.InvalidInput, solve(RequirementGraph(listOf(verified)), matrix).status)
        for (status in listOf(IncrementStatus.InProgress, IncrementStatus.Blocked, IncrementStatus.Cancelled)) {
            val node = inc("a").copy(status = status, cancelledReason = if (status == IncrementStatus.Cancelled) "cancelled" else null)
            assertEquals(ScheduleStatus.InvalidInput, solve(RequirementGraph(listOf(node)), matrix).status)
        }
        val contradiction = ScheduleProblem(RequirementGraph(listOf(inc("a", "outside"), inc("outside"))),
            setOf("a"), setOf("outside"), emptySet(), true, matrix)
        assertEquals(ScheduleStatus.UnsatisfiedPrerequisites, DagSchedule.solve(contradiction, limits).status)
        val alreadyVerified = RequirementGraph(listOf(inc("a", "outside"), inc("outside").copy(status = IncrementStatus.Verified)))
        assertEquals(ScheduleStatus.Optimal, DagSchedule.solve(ScheduleProblem(alreadyVerified, setOf("a"),
            setOf("outside"), emptySet(), true, matrix), limits).status)
    }

    @Test fun `additional semantic order is mandatory and cycles are explicit`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b"), inc("c")))
        val ids = setOf("a", "b", "c")
        val precedence = setOf(ScheduleEdge("c", "a"), ScheduleEdge("a", "b"))
        val result = DagSchedule.solve(ScheduleProblem(graph, ids, emptySet(), precedence, true, costs(ids)), limits)
        assertEquals(listOf("c", "a", "b"), result.order)
        val cyclic = DagSchedule.solve(ScheduleProblem(graph, ids, emptySet(),
            precedence + ScheduleEdge("b", "c"), true, costs(ids)), limits)
        assertEquals(ScheduleStatus.InvalidInput, cyclic.status)
        assertTrue(cyclic.issues.single().contains("cycle"))
        val self = RequirementGraph(listOf(inc("a", "a")))
        assertEquals(ScheduleStatus.InvalidInput, solve(self, costs(setOf("a"))).status)
        assertEquals(ScheduleStatus.InvalidInput, DagSchedule.solve(ScheduleProblem(graph, ids, emptySet(),
            setOf(ScheduleEdge("x", "a")), true, costs(ids)), limits).status)
    }

    @Test fun `missing unknown or mixed prices cannot manufacture an optimum`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b")))
        val base = costs(setOf("a", "b"))
        val variants = listOf(copyCosts(base, initial = base.initial - "a"),
            copyCosts(base, fixed = base.fixed + ("a" to money("2").copy(unknown = true))),
            copyCosts(base, switches = emptyMap()), copyCosts(base, contexts = base.contexts - "b"),
            copyCosts(base, switches = base.switches + (ScheduleEdge("a", "b") to money("0").copy(unknown = true))))
        for (variant in variants) {
            val result = solve(graph, variant)
            assertEquals(ScheduleStatus.UnknownCosts, result.status)
            assertTrue(result.order.isEmpty()); assertNull(result.totalCost)
        }
        assertFailsWith<IllegalArgumentException> {
            copyCosts(base, fixed = base.fixed + ("a" to Money("EUR", BigDecimal.ONE)))
        }
        assertFailsWith<IllegalArgumentException> { copyCosts(base, initial = base.initial + ("a" to money("-0.01"))) }
        assertEquals(ScheduleStatus.InvalidInput, solve(graph, copyCosts(base,
            initial = base.initial + ("extra" to money("1")))).status)
    }

    @Test fun `history dependent costs and incomplete constraints are unsupported`() {
        val graph = RequirementGraph(listOf(inc("a")))
        val base = costs(setOf("a"))
        assertEquals(ScheduleStatus.Unsupported, solve(graph, copyCosts(base, model = ScheduleCostModel.HistoryDependent)).status)
        assertEquals(ScheduleStatus.Unsupported,
            DagSchedule.solve(ScheduleProblem(graph, setOf("a"), emptySet(), emptySet(), false, base), limits).status)
    }

    @Test fun `unknown and already completed selected IDs are invalid`() {
        val graph = RequirementGraph(listOf(inc("a")))
        val prices = costs(setOf("a"))
        assertEquals(ScheduleStatus.InvalidInput, DagSchedule.solve(ScheduleProblem(graph,
            setOf("a", "missing"), emptySet(), emptySet(), true, prices), limits).status)
        assertEquals(ScheduleStatus.InvalidInput, DagSchedule.solve(ScheduleProblem(graph,
            setOf("a"), setOf("a"), emptySet(), true, prices), limits).status)
    }

    @Test fun `allocation and transition limits refuse before declaring optimum`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b"), inc("c")))
        val problem = ScheduleProblem(graph, setOf("a", "b", "c"), emptySet(), emptySet(), true, costs(setOf("a", "b", "c")))
        for (limited in listOf(ScheduleLimits(2, 100, 100), ScheduleLimits(3, 23, 100),
            ScheduleLimits(3, 24, 0), ScheduleLimits(3, 24, 3))) {
            val result = DagSchedule.solve(problem, limited)
            assertEquals(ScheduleStatus.ResourceLimit, result.status)
            assertNull(result.totalCost); assertTrue(result.order.isEmpty())
        }
        val exact = DagSchedule.solve(problem, limits)
        assertEquals(ScheduleStatus.Optimal, DagSchedule.solve(problem, ScheduleLimits(3, 24, exact.transitions)).status)
        assertEquals(ScheduleStatus.ResourceLimit, DagSchedule.solve(problem,
            ScheduleLimits(3, 24, exact.transitions - 1)).status)
        for (n in listOf(27, 31, 65)) {
            val huge = RequirementGraph((0 until n).map { inc("n$it") })
            val refusal = DagSchedule.solve(ScheduleProblem(huge, huge.increments.map { it.id }.toSet(),
                emptySet(), emptySet(), true, costs(emptySet())), ScheduleLimits(100, Long.MAX_VALUE, Long.MAX_VALUE))
            assertEquals(ScheduleStatus.ResourceLimit, refusal.status)
        }
    }

    @Test fun `exact decimals zero ties and empty slices retain deterministic witness`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b")))
        val base = costs(setOf("a", "b"))
        val precise = copyCosts(base, initial = base.initial.mapValues { money("0") },
            switches = mapOf(ScheduleEdge("a", "b") to money("0.30000000000000000000000000001"),
                ScheduleEdge("b", "a") to money("0.30000000000000000000000000000")))
        val result = solve(graph, precise)
        assertEquals(listOf("b", "a"), result.order)
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal("4.3")))
        val zero = copyCosts(base, initial = base.initial.mapValues { money("0") },
            switches = base.switches.mapValues { money("0") }, fixed = base.fixed.mapValues { money("0") })
        val z = solve(graph, zero)
        assertEquals(ScheduleStatus.Optimal, z.status)
        assertEquals(0, z.totalCost!!.amount.signum())
        assertEquals(z.order, solve(graph, zero).order)
        val empty = DagSchedule.solve(ScheduleProblem(graph, emptySet(), emptySet(), emptySet(), true, costs(emptySet())),
            ScheduleLimits(0, 0, 0))
        assertEquals(ScheduleStatus.Optimal, empty.status)
        assertEquals(0, empty.totalCost!!.amount.signum())
        assertEquals(0L, empty.states)
    }

    @Test fun `cache versions and provenance are retained without inferring free switches`() {
        val graph = RequirementGraph(listOf(inc("a"), inc("b", "a")))
        val base = costs(setOf("a", "b"))
        val changed = copyCosts(base, contexts = base.contexts + ("b" to base.contexts.getValue("b").copy(
            systemPrefix = Digest.ofUtf8("different S"), rolePrefix = Digest.ofUtf8("different R"))))
        assertNotEquals(base.fingerprint, changed.fingerprint)
        val result = solve(graph, changed)
        assertEquals(0, result.variableCost!!.amount.compareTo(BigDecimal("2")))
        assertEquals(changed.contexts, result.problem.costs.contexts)
        assertNotEquals(base.fingerprint, copyCosts(base, provenance = "another pilot").fingerprint)
        assertNotEquals(base.fingerprint, copyCosts(base, fixed = base.fixed + ("a" to money("3"))).fingerprint)
        val scaled = copyCosts(base, fixed = base.fixed.mapValues { money("2.000") })
        assertEquals(base.fingerprint, scaled.fingerprint)
    }

    @Test fun `input and output collections are defensive snapshots`() {
        val deps = mutableListOf("a")
        val increments = mutableListOf(inc("a"), inc("b").copy(dependsOn = deps))
        val graph = RequirementGraph(increments)
        val selected = mutableSetOf("a", "b")
        val costs = costs(selected)
        val prices = costs.switches.toMutableMap()
        val frozen = copyCosts(costs, switches = prices)
        val constraints = mutableSetOf(ScheduleEdge("a", "b"))
        val problem = ScheduleProblem(graph, selected, emptySet(), constraints, true, frozen)
        val fp = problem.fingerprint
        deps.clear(); increments.clear(); selected.clear(); constraints.clear(); prices.clear()
        val result = DagSchedule.solve(problem, limits)
        assertEquals(fp, problem.fingerprint)
        assertEquals(listOf("a", "b"), result.order)
        assertFailsWith<UnsupportedOperationException> { (problem.selected as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (frozen.switches as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.order as MutableList).clear() }
    }

    @Test fun `subset DP matches every topological order on seeded DAGs`() {
        val random = Random(455)
        repeat(200) { sample ->
            val n = random.nextInt(1, 8)
            val nodes = (0 until n).map { i -> inc("n$i", *(0 until i).filter { random.nextInt(3) == 0 }
                .map { "n$it" }.toTypedArray()) }
            val graph = RequirementGraph(nodes)
            val ids = nodes.map { it.id }.toSet()
            val original = costs(ids)
            fun charge() = Money("USD", BigDecimal(random.nextInt(20)).movePointLeft(2))
            val prices = copyCosts(original, initial = original.initial.mapValues { charge() },
                switches = original.switches.mapValues { charge() }, fixed = original.fixed.mapValues { charge() })
            val precedence = if (n >= 2 && sample % 3 == 0) setOf(ScheduleEdge("n0", "n${n - 1}")) else emptySet()
            val problem = ScheduleProblem(graph, ids, emptySet(), precedence, true, prices)
            val result = DagSchedule.solve(problem, limits)
            val all = topologicalOrders(nodes, precedence)
            val best = all.minOf { cost(it, prices) }
            assertEquals(ScheduleStatus.Optimal, result.status)
            assertEquals(0, best.compareTo(result.totalCost!!.amount), "sample $sample")
            assertTrue(result.order in all, "invalid witness $sample")
            assertEquals(0, cost(result.order, prices).compareTo(result.totalCost.amount))
            val reversedPrices = copyCosts(prices, initial = prices.initial.entries.reversed().associate { it.toPair() },
                switches = prices.switches.entries.reversed().associate { it.toPair() })
            val reversed = ScheduleProblem(RequirementGraph(nodes.reversed().map { it.copy(dependsOn = it.dependsOn.reversed()) }),
                ids.reversed().toSet(), emptySet(), precedence, true, reversedPrices)
            val again = DagSchedule.solve(reversed, limits)
            assertEquals(problem.fingerprint, reversed.fingerprint)
            assertEquals(result.order, again.order)
            assertEquals(result.transitions, again.transitions)
        }
    }

    // Independent permutation oracle: no subset masks or shared graph traversal.
    private fun topologicalOrders(nodes: List<Increment>, edges: Set<ScheduleEdge>): List<List<String>> {
        val orders = mutableListOf<List<String>>()
        fun enumerate(prefix: List<String>, remaining: List<String>) {
            if (remaining.isEmpty()) {
                val index = prefix.withIndex().associate { it.value to it.index }
                if (nodes.all { node -> node.dependsOn.all { index.getValue(it) < index.getValue(node.id) } } &&
                    edges.all { index.getValue(it.from) < index.getValue(it.to) }) orders += prefix
            } else remaining.forEach { enumerate(prefix + it, remaining - it) }
        }
        enumerate(emptyList(), nodes.map { it.id })
        return orders
    }

    private fun cost(order: List<String>, costs: ScheduleCosts): BigDecimal =
        costs.fixed.values.sumOf { it.amount } + costs.initial.getValue(order.first()).amount +
            order.zipWithNext().sumOf { (a, b) -> costs.switches.getValue(ScheduleEdge(a, b)).amount }

    private val limits = ScheduleLimits(10, 100000, 1000000)
    private fun solve(graph: RequirementGraph, costs: ScheduleCosts) = DagSchedule.solve(
        ScheduleProblem(graph, graph.increments.map { it.id }.toSet(), emptySet(), emptySet(), true, costs), limits)
    private fun copyCosts(base: ScheduleCosts, contexts: Map<String, ScheduleContext> = base.contexts,
        initial: Map<String, Money> = base.initial, switches: Map<ScheduleEdge, Money> = base.switches,
        fixed: Map<String, Money> = base.fixed, provenance: String = base.provenance,
        model: ScheduleCostModel = base.model) =
        ScheduleCosts(base.version, base.currency, contexts, initial, switches, fixed, provenance, model)

    private fun inc(id: String, vararg dependencies: String) =
        Increment(id, listOf("req"), emptyList(), emptyList(), 0, dependsOn = dependencies.toList())
    private fun money(amount: String) = Money("USD", BigDecimal(amount))
    private fun costs(ids: Set<String>, special: Map<ScheduleEdge, Money> = emptyMap()): ScheduleCosts {
        val digest = Digest.ofUtf8("frozen")
        val context = ScheduleContext("worker-v1", digest, digest, digest, "cache-v1")
        return ScheduleCosts("v1", "USD", ids.associateWith { context }, ids.associateWith { money("1") },
            ids.flatMap { a -> ids.filter { it != a }.map { b -> ScheduleEdge(a, b) to money("1") } }.toMap() + special,
            ids.associateWith { money("2") }, "synthetic frozen costs", ScheduleCostModel.Pairwise)
    }
}
