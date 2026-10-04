package io.astrolabe.tool.run

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnOutputTest {
    private val estimator = HeuristicEstimator()

    private fun outcome(tool: String, status: String, alias: String, body: String, green: Boolean = status == "passed"): ToolOutcome =
        ToolOutcome(
            body,
            EnvelopeHeader(alias, tool, null, emptyMap(), null, false, Effects.None, runtime = RuntimeFields("act-$alias", status, null, null, "scope", "complete")),
            green = green,
            tokens = estimator.estimate(body).tokens,
        )

    private fun output(tag: String, lines: Int): String = (1..lines).joinToString("\n") { "  $tag output line $it of a long build log" }

    private fun run(alias: String, status: String, lines: Int, failing: List<String> = emptyList()): ToolOutcome {
        val head = listOf("run $alias $status · class R · exit ${if (status == "failed") 1 else 0} · make test", "pytest 8.1 · $status · exit 1") +
            (if (failing.isEmpty()) listOf("counts: 3 passed · 0 failed · 0 errors · 0 skipped · 3 discovered")
            else listOf("counts: 0 passed · ${failing.size} failed · 0 errors · 0 skipped · ${failing.size} discovered", "failing tests (${failing.size}):") + failing.map { "  $it" })
        return outcome("run", status, alias, (head + "output ($lines lines):" + output(alias, lines)).joinToString("\n"))
    }

    private fun tokens(o: ToolOutcome) = estimator.estimate(o.body).tokens

    @Test
    fun `a turn within the budget keeps every result as executed`() {
        val results = listOf(1 to run("#1", "passed", 40), 2 to run("#2", "failed", 40, listOf("test_a — boom")))
        assertEquals(emptyMap(), TurnOutput.fit(results, results.sumOf { tokens(it.second) }.toLong(), estimator))
    }

    @Test
    fun `past the budget each result is shown short within it and keeps its status, counts and a recall pointer`() {
        val results = (1..6).map { it to run("#$it", "passed", 200) }
        val budget = 3_000L
        assertTrue(results.sumOf { tokens(it.second) } > budget)

        val shown = TurnOutput.fit(results, budget, estimator)

        val bodies = results.map { (op, o) -> shown[op] ?: o }
        assertTrue(bodies.sumOf { tokens(it) } <= budget, "shown ${bodies.sumOf { tokens(it) }} of $budget")
        assertEquals((1..6).toSet(), shown.keys)
        for ((op, original) in results) {
            val short = shown.getValue(op)
            assertEquals(original.header, short.header, "the header is the executor's")
            assertEquals(original.green, short.green)
            val lines = short.body.lines()
            assertEquals("run #$op passed · class R · exit 0 · make test", lines.first())
            assertTrue(lines.any { it.startsWith("counts: 3 passed") }, short.body)
            val marker = lines.single { it.contains("passed their output budget of 3000 tokens") }
            assertTrue(marker.contains("full output: look(recall, id=#$op)"), marker)
            assertTrue(short.body.endsWith(original.body.lines().last()), "the tail is kept: " + short.body.takeLast(200))
        }
    }

    @Test
    fun `a failing result takes the remainder first and keeps its first failures when the remainder runs short`() {
        val failing = (1..30).map { "tests/test_x.py::test_$it — AssertionError: expected $it" }
        val green = run("#1", "passed", 300)
        val red = run("#2", "failed", 300, failing)
        val budget = 1_500L

        val shown = TurnOutput.fit(listOf(1 to green, 2 to red), budget, estimator)

        val shownRed = shown.getValue(2)
        val shownGreen = shown.getValue(1)
        assertTrue(tokens(shownRed) > tokens(shownGreen), "red ${tokens(shownRed)} vs green ${tokens(shownGreen)}")
        assertTrue(shownRed.body.contains("failing tests (30):"), shownRed.body)
        assertTrue(shownRed.body.contains(failing.first()), shownRed.body)
        assertFalse(shownRed.green)
        assertTrue(tokens(shownRed) + tokens(shownGreen) <= budget)
    }

    @Test
    fun `green verify results count in the same budget and say their output is not recallable`() {
        val checks = (1..200).joinToString("\n") { "  CHK-$it: pytest 8.1 · passed · exit 0" }
        val verify = outcome("verify", "ok", "#9", "checks @ab12cd34: tests ✓ (#9)\n$checks", green = true)
        val runs = (1..2).map { it to run("#$it", "passed", 150) }
        val budget = 2_000L

        val shown = TurnOutput.fit(runs + (3 to verify), budget, estimator)

        val shownVerify = shown.getValue(3)
        assertEquals("checks @ab12cd34: tests ✓ (#9)", shownVerify.body.lines().first())
        assertTrue(shownVerify.body.contains("the full check output is not recallable"), shownVerify.body)
        assertTrue((runs.map { (op, o) -> shown[op] ?: o } + shownVerify).sumOf { tokens(it) } <= budget)
    }

    @Test
    fun `a failing verify reads ok in its header yet keeps every diagnostic while the other results share what it leaves`() {
        // 20 checks: receipt summaries first, the failing check's diagnostics deep after another check's green output.
        val receipts = (1..20).joinToString("\n") { "receipt CHK-$it: tests ${if (it == 20) "✗ 1 fail" else "✓ 3 pass"} (#$it)" }
        val green = (1..300).joinToString("\n") { "    CHK-1 output line $it" }
        val verify = outcome("verify", "ok", "#20", "$receipts\n  CHK-1: pytest · passed\n$green\n  CHK-20: pytest · failed\n    E   AssertionError: the flaky diagnostic", green = false)
        val runs = (1..39).map { it to run("#$it", "passed", 200) }
        val budget = 12_000L

        val shown = TurnOutput.fit(runs + (40 to verify), budget, estimator)

        assertFalse(40 in shown, "the failing verify is shown whole")
        assertTrue((shown[40] ?: verify).body.contains("E   AssertionError: the flaky diagnostic"))
        assertTrue(runs.sumOf { (op, o) -> tokens(shown[op] ?: o) } <= budget - tokens(verify))
    }

    @Test
    fun `the shown bodies never pass the budget, whatever the punctuation, the number of results or one long line`() {
        val dense = (listOf("run #1 passed · class R · exit 0 · make") + List(650) { "aaa!!!aaaaaaaaaaa" }).joinToString("\n")
        val four = (1..4).map { it to outcome("run", "passed", "#$it", dense) }
        val many = (1..1_000).map { it to run("#$it", "passed", 40) }
        val long = listOf(1 to outcome("run", "passed", "#1", "x!".repeat(25_000)))
        for (results in listOf(four, many, long)) {
            val shown = TurnOutput.fit(results, 12_000L, estimator)
            val total = results.sumOf { (op, o) -> tokens(shown[op] ?: o) }
            assertTrue(total <= 12_000L, "${results.size} results shown in $total tokens")
        }
        val one = TurnOutput.fit(long, 12_000L, estimator).getValue(1).body
        assertTrue(one.startsWith("x!x!") && one.contains("output budget of 12000 tokens; full output: look(recall, id=#1)"), one.takeLast(200))
    }

    @Test
    fun `a result that fits the floor is never cut`() {
        val small = run("#1", "passed", 3)
        val results = listOf(1 to small, 2 to run("#2", "passed", 400))
        val shown = TurnOutput.fit(results, 1_000L, estimator)
        assertFalse(1 in shown)
        assertTrue(2 in shown)
    }
}
