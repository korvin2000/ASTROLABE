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
