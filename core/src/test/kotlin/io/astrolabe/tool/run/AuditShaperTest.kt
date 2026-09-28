package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AuditShaperTest {
    @Test
    fun `successful unittest skips never become passes`() {
        val result = Shapers.shape(Recorded.capture(argv = listOf("python", "-m", "unittest"), exitCode = 0,
            output = "Ran 3 tests in 0.1s\nOK (skipped=3)\n".toByteArray()))
        assertEquals(0, result.counts!!.passed)
        assertEquals(3, result.counts.skipped)
        assertFalse(result.status.green)
    }

    @Test
    fun `unittest expected failures are not counted as failures`() {
        val ok = Shapers.shape(Recorded.capture(argv = listOf("python", "-m", "unittest"), exitCode = 0,
            output = "Ran 2 tests in 0.1s\nOK (expected failures=1)\n".toByteArray()))
        assertEquals(0, ok.counts!!.failed)
        assertEquals(Outcome.Passed, ok.status)
        val failed = Shapers.shape(Recorded.capture(argv = listOf("python", "-m", "unittest"), exitCode = 1,
            output = "Ran 4 tests in 0.1s\nFAILED (failures=1, expected failures=2)\n".toByteArray()))
        assertEquals(1, failed.counts!!.failed)
    }

    @Test
    fun `jest todo tests are counted apart from pending`() {
        val report = """{"numTotalTests":2,"numPassedTests":1,"numFailedTests":0,"numPendingTests":0,"numTodoTests":1,"success":true,""" +
            """"testResults":[{"assertionResults":[{"title":"ok","status":"passed"},{"title":"later","status":"todo"}]}]}"""
        val result = Shapers.shape(Recorded.capture(argv = listOf("jest"), exitCode = 0,
            output = "Tests: 1 todo, 1 passed, 2 total\n".toByteArray(),
            reports = listOf(ReportArtifact("report.json", ReportKind.JestJson, true, "this invocation", report.toByteArray()))))
        assertEquals(Outcome.Passed, result.status)
        assertEquals(1, result.counts!!.skipped)
    }

    @Test
    fun `a wrapped runner with a nonzero exit is never a pass`() {
        val result = Shapers.shape(Recorded.capture(argv = listOf("sh", "-c", "npx jest --coverage && echo ok"), exitCode = 1,
            output = "Tests:       3 passed, 3 total\n".toByteArray()))
        assertEquals(3, result.counts!!.passed)
        assertEquals(Outcome.Inconclusive, result.status)
    }

    @Test
    fun `cargo aggregates every suite including later failures`() {
        val result = Shapers.shape(Recorded.capture(argv = listOf("cargo", "test"), exitCode = 0,
            output = ("test result: ok. 2 passed; 0 failed; 0 ignored\n" +
                "test result: FAILED. 0 passed; 1 failed; 0 ignored\n").toByteArray()))
        assertEquals(Outcome.Failed, result.status)
        assertEquals(2, result.counts!!.passed)
        assertEquals(1, result.counts.failed)
    }

    @Test
    fun `malformed and incomplete fresh reports cannot fall back to green terminal output`() {
        val cases = listOf(
            Triple("jest", ReportKind.JestJson, "{"),
            Triple("pytest", ReportKind.JUnitXml, """<testsuite tests="2"><testcase name="ok"/></testsuite>"""),
            Triple("jest", ReportKind.JestJson, """{"numTotalTests":2,"testResults":[{"assertionResults":[{"title":"ok","status":"passed"}]}]}"""),
            Triple("pytest", ReportKind.PytestJson, """{"summary":{"total":2},"tests":[{"nodeid":"a.py::ok","outcome":"passed"}]}"""),
            Triple("pytest", ReportKind.PytestJson, """{"summary":{"total":1},"tests":[{"nodeid":{},"outcome":"passed"}]}"""),
        )
        for ((runner, kind, report) in cases) {
            val result = Shapers.shape(Recorded.capture(argv = listOf(runner), exitCode = 0,
                output = "Tests: 1 passed, 1 total\n=== 1 passed in 0.1s ===\n".toByteArray(),
                reports = listOf(ReportArtifact("report.json", kind, true, "this invocation", report.toByteArray()))))
            assertFalse(result.status.green, "$runner accepted $report")
        }
    }
}
