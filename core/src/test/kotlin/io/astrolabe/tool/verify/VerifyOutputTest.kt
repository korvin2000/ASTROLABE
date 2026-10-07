package io.astrolabe.tool.verify

import io.astrolabe.budget.HeuristicEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P8.C.17 (D-424): one `verify` call's output — its checks and retries together — under one cap that keeps every failure's first lines. */
class VerifyOutputTest {
    private val estimator = HeuristicEstimator()
    private val checks = "── Checks @ab12cd34 ──\n  CHK-1 ✓ (#1)\n  CHK-2 ✗ (#2)\n  CHK-3 ✗ (#3)"

    private fun view(id: String, failed: Boolean, lines: Int, alias: String?): VerifyOutput.View {
        val head = if (failed) listOf("  $id: pytest · failed · exit 1", "    FAILED tests/test_$id.py::test_first - AssertionError: first failure of $id")
        else listOf("  $id: pytest · passed · exit 0")
        return VerifyOutput.View(id, (head + (1..lines).map { "    $id log line $it of a long, noisy test session" }).joinToString("\n"), failed, alias)
    }

    @Test
    fun `within the cap the body is the checks block and every view byte for byte`() {
        val views = listOf(view("CHK-1", false, 5, "#1"), view("CHK-2", true, 5, "#2"))
        val whole = checks + "\n" + views.joinToString("\n") { it.text }
        assertEquals(whole, VerifyOutput.cap(checks, views, estimator.estimate(whole).tokens, estimator))
    }

    @Test
    fun `past the cap every failed check keeps its first failure and a recall pointer, not only the last check's tail`() {
        // A green check's long output first, then two failed checks (one a retry pair): the old concatenation showed the last tail.
        val views = listOf(view("CHK-1", false, 2_000, "#1"), view("CHK-2", true, 1_000, "#2"), view("CHK-3", true, 1_000, null))
        val budget = 1_500L
        val body = VerifyOutput.cap(checks, views, budget, estimator)

        assertTrue(estimator.estimate(body).tokens <= budget, "shown ${estimator.estimate(body).tokens} of $budget")
        assertTrue(body.startsWith(checks + "\n"), "the checks block stays whole")
        for (id in listOf("CHK-2", "CHK-3")) assertTrue(body.contains("FAILED tests/test_$id.py::test_first - AssertionError: first failure of $id"), body)
        assertTrue(body.contains("  CHK-1: pytest · passed · exit 0"), "every view keeps its first line")
        assertTrue(body.lines().any { it.startsWith("  … ") && it.contains("more lines of CHK-2") && it.endsWith("full output: look(recall, id=#2)") }, body)
        assertTrue(body.lines().any { it.contains("more lines of CHK-3") && it.endsWith("its receipt keeps the full output") }, body)
        val failedShown = body.lines().count { it.startsWith("    CHK-2 log") || it.startsWith("    CHK-3 log") }
        val greenShown = body.lines().count { it.startsWith("    CHK-1 log") }
        assertTrue(failedShown > greenShown, "failed checks take the room first: $failedShown vs $greenShown")
        assertFalse(body.contains("CHK-3 log line 1000 "), "the last tail is not what is kept")
    }

    @Test
    fun `the failed checks share the room line by line`() {
        val views = listOf(view("CHK-2", true, 400, "#2"), view("CHK-3", true, 400, "#3"))
        val body = VerifyOutput.cap(checks, views, 800L, estimator)
        val two = body.lines().count { it.startsWith("    CHK-2 log") }
        val three = body.lines().count { it.startsWith("    CHK-3 log") }
        assertTrue(two > 0 && kotlin.math.abs(two - three) <= 1, "$two vs $three")
        assertTrue(estimator.estimate(body).tokens <= 800L)
    }
}
