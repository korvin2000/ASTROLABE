package io.astrolabe.context

import io.astrolabe.budget.Tokens
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.random.Random

class ContextCoverTest {
    @Test fun `mandatory diamond pays for shared dependency once at the exact boundary`() {
        val units = listOf(unit("root", 2, listOf("a", "b"), mandatory = true),
            unit("a", 3, listOf("shared")), unit("b", 4, listOf("shared")), unit("shared", 5))
        val fit = ContextCover.select(units, budget(14))
        assertEquals(ContextSelectionStatus.Fit, fit.status)
        assertEquals(setOf("root", "a", "b", "shared"), fit.selectedIds.map { it.value }.toSet())
        assertEquals(BigInteger.valueOf(14), fit.arithmetic.selectedTokens)
        val overflow = ContextCover.select(units, budget(13))
        assertEquals(ContextSelectionStatus.Capacity, overflow.status)
        assertEquals(fit.selectedIds, overflow.selectedIds)
    }

    @Test fun `greedy recomputes marginal costs after selecting a shared dependency`() {
        val units = listOf(unit("a", 1, listOf("shared"), gain = 20),
            unit("b", 1, listOf("shared"), gain = 10), unit("c", 3, gain = 6), unit("shared", 5, gain = 0))
        val result = ContextCover.select(units, budget(7))
        assertEquals(listOf("a", "b"), result.picks.map { it.root.value })
        assertEquals(BigInteger.valueOf(7), result.arithmetic.selectedTokens)
    }

    @Test fun `system and repository references are charged once and bring their dependencies`() {
        val units = listOf(unit("rule", 8, listOf("acceptance"), placement = ContextPlacement.Repository),
            unit("kernel", 3, placement = ContextPlacement.System), unit("acceptance", 2))
        val input = budget(20).copy(system = cost(3), repository = cost(8), pinnedHistory = cost(2),
            retainedProtocol = cost(1), effectiveHistory = cost(1), reserves = cost(3))
        val result = ContextCover.select(units, input)
        assertEquals(ContextSelectionStatus.Fit, result.status)
        assertEquals(BigInteger.valueOf(2), result.arithmetic.selectedTokens)
        assertEquals(BigInteger.valueOf(20), result.arithmetic.totalTokens)
        assertEquals(units.map { it.id }.toSet(), result.mandatoryIds)
        assertEquals("fixture-v1", result.units.getValue(ContextUnitId("rule")).cost.source)
        assertFailsWith<IllegalArgumentException> { ContextCover.select(units, input.copy(repository = cost(7))) }
    }

    @Test fun `unknown history is never fit and decimal profile allowance rounds down`() {
        val unknown = ContextCover.select(listOf(unit("required", 1, mandatory = true)),
            budget(100).copy(effectiveHistory = null, pinnedHistory = cost(20)))
        assertEquals(ContextSelectionStatus.UnknownHistory, unknown.status)
        assertNull(unknown.arithmetic.availableTokens)
        assertNull(unknown.arithmetic.totalTokens)
        assertEquals(BigInteger.valueOf(20), unknown.arithmetic.knownFixedTokens)
        val fractional = budget(3).copy(alpha = BigDecimal("0.65"))
        assertEquals(ContextSelectionStatus.Capacity,
            ContextCover.select(listOf(unit("required", 2, mandatory = true)), fractional).status)
        assertEquals(BigInteger.ONE, ContextCover.select(emptyList(), fractional).arithmetic.limitTokens)
        val fixedOverflow = ContextCover.select(emptyList(), budget(1).copy(reserves = cost(2)))
        assertEquals(ContextSelectionStatus.Capacity, fixedOverflow.status)
        assertEquals(BigInteger.valueOf(-1), fixedOverflow.arithmetic.availableTokens)
    }

    @Test fun `missing and cyclic optional roots are omitted but mandatory faults refuse selection`() {
        val optional = listOf(unit("a", 1, listOf("b")), unit("b", 1, listOf("a")),
            unit("missing", 1, listOf("absent")), unit("ok", 1))
        val result = ContextCover.select(optional, budget(10))
        assertEquals(ContextSelectionStatus.Fit, result.status)
        assertEquals(setOf(ContextUnitId("ok")), result.selectedIds)
        assertEquals(ContextOmission.Dependency, result.omissions[ContextUnitId("a")])
        assertTrue(result.issues.any { it.dependency.value == "absent" && it.code == ContextIssueCode.MissingDependency })
        val required = ContextCover.select(optional + unit("root", 1, listOf("a", "missing"), mandatory = true), budget(0))
        assertEquals(ContextSelectionStatus.NeedsEvidence, required.status)
        assertEquals(setOf("root", "a", "b", "missing"), required.selectedIds.map { it.value }.toSet())
        assertEquals(ContextOmission.SelectionRefused, required.omissions[ContextUnitId("ok")])
        assertEquals(ContextSelectionStatus.NeedsEvidence,
            ContextCover.select(listOf(unit("self", 1, listOf("self"), mandatory = true)), budget(10)).status)
    }

    @Test fun `priority zero cost zero gain and tie breaks are explicit`() {
        val result = ContextCover.select(listOf(unit("z", 1, gain = 1, priority = ContextPriority.Seeds),
            unit("a", 0, gain = 100), unit("b", 0, gain = 1), unit("c", 0, gain = 0),
            unit("d", 1, gain = 100)), budget(1))
        assertEquals(listOf("z", "a", "b"), result.picks.map { it.root.value })
        assertEquals(ContextOmission.NoGain, result.omissions[ContextUnitId("c")])
        assertEquals(ContextOmission.Budget, result.omissions[ContextUnitId("d")])
        val ties = ContextCover.select(listOf(unit("b", 2, gain = 4), unit("a", 1, gain = 2)), budget(2))
        assertEquals(listOf("a"), ties.picks.map { it.root.value })
    }

    @Test fun `unknown history takes precedence while mandatory dependency failures remain visible`() {
        val result = ContextCover.select(listOf(unit("root", 3, listOf("absent"), mandatory = true)),
            budget(10).copy(effectiveHistory = null, system = cost(2)))
        assertEquals(ContextSelectionStatus.UnknownHistory, result.status)
        assertEquals(setOf(ContextUnitId("root")), result.mandatoryIds)
        assertEquals(listOf(ContextIssue(ContextUnitId("root"), ContextUnitId("absent"),
            ContextIssueCode.MissingDependency)), result.issues)
        assertEquals(BigInteger.valueOf(2), result.arithmetic.knownFixedTokens)
        assertEquals(BigInteger.valueOf(3), result.arithmetic.selectedTokens)
        assertNull(result.arithmetic.totalTokens)
    }

    @Test fun `over budget higher class yields to a feasible root with a cross class prerequisite`() {
        val result = ContextCover.select(listOf(unit("large", 10, priority = ContextPriority.AffectedContracts),
            unit("seed", 2, listOf("background"), priority = ContextPriority.Seeds),
            unit("background", 1, gain = 2, priority = ContextPriority.Background)), budget(3))
        assertEquals(listOf("seed"), result.picks.map { it.root.value })
        assertEquals(listOf("background", "seed"), result.picks.single().addedIds.map { it.value })
        assertEquals(ContextOmission.Budget, result.omissions[ContextUnitId("large")])
    }

    @Test fun `arithmetic and comparisons stay exact above Long and floating precision`() {
        val huge = ContextCover.select(listOf(unit("a", Long.MAX_VALUE, mandatory = true),
            unit("b", Long.MAX_VALUE, mandatory = true)), budget(Long.MAX_VALUE))
        assertEquals(ContextSelectionStatus.Capacity, huge.status)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, huge.arithmetic.selectedTokens)
        val ratio = ContextCover.select(listOf(unit("a", Long.MAX_VALUE, gain = Long.MAX_VALUE - 1),
            unit("b", Long.MAX_VALUE, gain = Long.MAX_VALUE)), budget(Long.MAX_VALUE))
        assertEquals(listOf("b"), ratio.picks.map { it.root.value })
        val fixed = ContextCover.select(emptyList(), budget(0).copy(system = cost(Long.MAX_VALUE), reserves = cost(Long.MAX_VALUE)))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, fixed.arithmetic.knownFixedTokens)
    }

    @Test fun `inputs and outputs own collections and invalid inputs are rejected`() {
        val dependencies = mutableListOf(ContextUnitId("dep"))
        val root = ContextUnit(ContextUnitId("root"), cost(0), dependencies, true)
        dependencies.clear()
        val source = mutableListOf(root, unit("dep", 0))
        val result = ContextCover.select(source, budget(0))
        source.clear()
        assertEquals(2, result.selectedIds.size)
        assertFailsWith<UnsupportedOperationException> { (root.dependsOn as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.selectedIds as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.units as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.issues as MutableList).clear() }
        val picked = ContextCover.select(listOf(unit("p", 0)), budget(0))
        assertFailsWith<UnsupportedOperationException> { (picked.picks.first().addedIds as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { ContextCover.select(listOf(root, root), budget(0)) }
        assertFailsWith<IllegalArgumentException> { ContextUnitId(" bad ") }
        assertFailsWith<IllegalArgumentException> { unit("bad", -1) }
        assertFailsWith<IllegalArgumentException> { unit("bad", 1, gain = -1) }
        assertFailsWith<IllegalArgumentException> { budget(1).copy(alpha = BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { budget(1).copy(alpha = BigDecimal("1.01")) }
        assertFailsWith<IllegalArgumentException> { ContextCost(Tokens(0), " ", true) }
    }

    @Test fun `iterative closure handles a deep chain`() {
        val units = (0 until 700).map { unit("n$it", 1, if (it == 0) emptyList() else listOf("n${it - 1}"), mandatory = it == 699) }
        val result = ContextCover.select(units, budget(700))
        assertEquals(ContextSelectionStatus.Fit, result.status)
        assertEquals(700, result.selectedIds.size)
    }

    @Test fun `random DAGs match an independent matrix oracle and exhaustive feasible sets`() {
        val random = Random(2354)
        var maxGap = 0L
        var totalGap = 0L
        var feasibleCases = 0
        repeat(160) { sample ->
            val n = 8
            val units = (0 until n).map { i -> unit("n$i", random.nextLong(0, 9),
                (0 until i).filter { random.nextInt(4) == 0 }.map { "n$it" },
                mandatory = random.nextInt(12) == 0, gain = random.nextLong(0, 20)) }
            val capacity = random.nextLong(0, 35)
            val closure = Array(n) { i -> BooleanArray(n) { j -> i == j || units[i].dependsOn.contains(units[j].id) } }
            for (k in 0 until n) for (i in 0 until n) for (j in 0 until n)
                closure[i][j] = closure[i][j] || (closure[i][k] && closure[k][j])
            val required = (0 until n).filter { j -> (0 until n).any { units[it].mandatory && closure[it][j] } }.toSet()
            val expected = required.toMutableSet()
            val roots = mutableListOf<String>()
            fun costOf(ids: Set<Int>) = ids.sumOf { units[it].cost.tokens.value }
            if (costOf(required) <= capacity) {
                while (true) {
                    val candidates = (0 until n).filter { it !in expected }.map { i ->
                        val added = (0 until n).filter { closure[i][it] && it !in expected }.toSet()
                        Triple(i, costOf(added), added.sumOf { units[it].gain })
                    }.filter { it.third > 0 && costOf(expected) + it.second <= capacity }
                        .sortedWith(compareByDescending<Triple<Int, Long, Long>> {
                            if (it.second == 0L) Double.POSITIVE_INFINITY else it.third.toDouble() / it.second
                        }.thenBy { units[it.first].id.value })
                    if (candidates.isEmpty()) break
                    val chosen = candidates.first().first
                    roots += units[chosen].id.value
                    expected += (0 until n).filter { closure[chosen][it] }
                }
            }
            val result = ContextCover.select(units, budget(capacity))
            assertEquals(expected.map { units[it].id }.toSet(), result.selectedIds, "seed=2354 sample=$sample")
            assertEquals(roots, result.picks.map { it.root.value })
            val shuffled = ContextCover.select(units.shuffled(random), budget(capacity))
            assertEquals(result.selectedIds, shuffled.selectedIds)
            assertEquals(result.picks.map { it.root }, shuffled.picks.map { it.root })
            assertEquals(result.arithmetic, shuffled.arithmetic)
            if (costOf(required) > capacity) {
                assertEquals(ContextSelectionStatus.Capacity, result.status)
            } else {
                assertEquals(ContextSelectionStatus.Fit, result.status)
                val valid = (0 until (1 shl n)).map { mask -> (0 until n).filter { mask and (1 shl it) != 0 }.toSet() }
                    .filter { set -> set.containsAll(required) && costOf(set) <= capacity &&
                        set.all { i -> (0 until n).all { !closure[i][it] || it in set } } }
                assertTrue(expected in valid)
                val gap = valid.maxOf { set -> set.sumOf { units[it].gain } } - expected.sumOf { units[it].gain }
                assertTrue(gap >= 0)
                maxGap = maxOf(maxGap, gap)
                totalGap += gap
                feasibleCases++
            }
        }
        println("context oracle seed=2354 cases=160 feasible=$feasibleCases totalUtilityGap=$totalGap maxUtilityGap=$maxGap")
    }

    @Test fun `greedy can miss the optimal utility and makes no monotonicity claim`() {
        val result = ContextCover.select(listOf(unit("a", 6, gain = 12), unit("b", 5, gain = 9),
            unit("c", 5, gain = 9)), budget(10))
        assertEquals(setOf(ContextUnitId("a")), result.selectedIds)
        assertEquals(6, 18 - result.selectedIds.sumOf { result.units.getValue(it).gain }.toInt())
    }

    private fun cost(tokens: Long) = ContextCost(Tokens(tokens), "fixture-v1", estimated = false)
    private fun budget(tokens: Long) = ContextBudget(Tokens(tokens), BigDecimal.ONE,
        cost(0), cost(0), cost(0), cost(0), cost(0), cost(0))
    private fun unit(id: String, tokens: Long, dependencies: List<String> = emptyList(),
                     mandatory: Boolean = false, gain: Long = 1,
                     priority: ContextPriority = ContextPriority.LocalImplementation,
                     placement: ContextPlacement = ContextPlacement.K) = ContextUnit(
        ContextUnitId(id), cost(tokens), dependencies.map(::ContextUnitId), mandatory,
        priority, gain, placement)
}
