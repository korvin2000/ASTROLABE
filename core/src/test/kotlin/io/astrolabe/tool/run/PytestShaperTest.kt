package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PytestShaperTest {
    @Test
    fun `a green run yields parsed counts and the only green status`() {
        val shaped = Shapers.shape(Recorded.capture("pytest-pass.txt", listOf("pytest", "-q"), exitCode = 0))
        assertEquals("pytest", shaped.shaper)
        assertEquals(Outcome.Passed, shaped.status)
        val counts = assertNotNull(shaped.counts)
        assertEquals(6, counts.passed)
        assertEquals(0, counts.failed)
        assertEquals(6, counts.discovered)
        assertNull(shaped.wrapper)
        assertTrue(!shaped.viewTruncated && !shaped.captureTruncated)
        assertNull(shaped.recallHint)
    }

    @Test
    fun `a parameterized failure keeps file, suite, name and parameterization apart (D-27)`() {
        val shaped = Shapers.shape(
            Recorded.capture("pytest-fail-param.txt", listOf("pytest", "-q"), exitCode = 1, checkId = "tests"),
        )
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(5, assertNotNull(shaped.counts).passed)
        assertEquals(1, shaped.counts.failed)
        assertEquals(1, shaped.counts.skipped)
        assertEquals(7, shaped.counts.discovered)
        val failing = shaped.tests.single { it.failing }
        assertEquals("tests", failing.identity.check)
        assertEquals("tests/test_discount.py", failing.identity.file)
        assertEquals("TestDiscount", failing.identity.suite)
        assertEquals("test_tier", failing.identity.name)
        assertEquals("3", failing.identity.parameterization)
        assertEquals("AssertionError: assert 4 == 5", failing.message)
        assertTrue(shaped.view.contains("test_tier[3]"), shaped.view)
    }

    @Test
    fun `exit 5 with no tests collected is inconclusive, never passed (FX-09)`() {
        val shaped = Shapers.shape(
            Recorded.capture("pytest-no-tests.txt", listOf("pytest", "-q", "-k", "nonexistent"), exitCode = 5)
                .copy(selector = "-k nonexistent"),
        )
        assertEquals(Outcome.Inconclusive, shaped.status)
        assertTrue(!shaped.status.green)
        assertEquals(0, assertNotNull(shaped.counts).executed)
        assertTrue(shaped.limitations.any { it.contains("collected no tests") }, "${shaped.limitations}")
        assertTrue(shaped.view.contains("selector -k nonexistent"), shaped.view)
    }

    @Test
    fun `a collection error is red and is not mistaken for a missing runner`() {
        val shaped = Shapers.shape(
            Recorded.capture("pytest-collection-error.txt", listOf("python", "-m", "pytest"), exitCode = 2),
        )
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(1, assertNotNull(shaped.counts).errors)
        val error = shaped.tests.single { it.outcome == TestOutcome.Error }
        assertEquals("tests/test_report.py", error.identity.file)
        assertEquals("(collection)", error.identity.name)
    }

    @Test
    fun `a failed run wrapped in a successful shell command reports the runner status (FX-08)`() {
        val capture = Recorded.capture(
            "pytest-wrapper.txt",
            argv = listOf("sh", "-c", "pytest -q || true"),
            shell = true,
            exitCode = 0,
        )
        assertEquals("pytest", Shapers.select(capture).id)
        val shaped = Shapers.shape(capture)
        assertEquals(Outcome.Failed, shaped.status, "wrapper exit 0 must never become a suite pass")
        val wrapper = assertNotNull(shaped.wrapper)
        assertEquals("|| true", wrapper.wrapper)
        assertEquals(listOf("pytest", "-q"), wrapper.runnerArgv)
        assertEquals(1, assertNotNull(shaped.counts).failed)
        assertEquals(6, shaped.counts.passed)
        assertTrue(shaped.limitations.any { it.contains("hides the runner's exit status") }, "${shaped.limitations}")
        assertTrue(shaped.view.contains("wrapper: '|| true'"), shaped.view)
    }

    @Test
    fun `a stdout summary that disagrees with a red exit code is never a pass (IX-13)`() {
        val shaped = Shapers.shape(Recorded.capture("pytest-forged.txt", listOf("pytest", "-q"), exitCode = 1))
        assertEquals(Outcome.Inconclusive, shaped.status)
        assertTrue(!shaped.status.green)
        assertEquals(3, assertNotNull(shaped.counts).passed)
        assertTrue(shaped.limitations.any { it.contains("disagrees with a summary") }, "${shaped.limitations}")
    }

    @Test
    fun `a fresh report is preferred for identities and a stale one is ignored (D-50)`() {
        val fresh = Shapers.shape(
            Recorded.capture(
                "pytest-fail-param.txt",
                listOf("pytest", "-q", "--junitxml=build/act-7/report.xml"),
                exitCode = 1,
                reports = listOf(Recorded.report("pytest-report.xml")),
            ),
        )
        assertEquals(Outcome.Failed, fresh.status)
        assertEquals(7, fresh.tests.size, "identities come from the report, not the terminal summary")
        val failing = fresh.tests.single { it.failing }
        assertEquals("tests.test_discount.TestDiscount", failing.identity.file)
        assertEquals("test_tier", failing.identity.name)
        assertEquals("3", failing.identity.parameterization)
        assertEquals(7, assertNotNull(fresh.counts).discovered)

        val stale = Shapers.shape(
            Recorded.capture(
                "pytest-fail-param.txt",
                listOf("pytest", "-q"),
                exitCode = 1,
                reports = listOf(
                    Recorded.report("pytest-report.xml", fresh = false, provenance = "found on disk, mtime is recent"),
                ),
            ),
        )
        assertEquals(1, stale.tests.size, "a stale report grants no identities")
        assertTrue(stale.limitations.any { it.contains("ignored") }, "${stale.limitations}")
    }

    @Test
    fun `a runner that could not be executed is unavailable, not failed`() {
        val shaped = Shapers.shape(Recorded.capture("missing-runner.txt", listOf("pytest", "-q"), exitCode = 127))
        assertEquals(Outcome.Unavailable, shaped.status)
        assertNull(shaped.counts)
    }

    @Test
    fun `a timeout and an unobserved exit keep their own status words`() {
        val timedOut = Shapers.shape(
            Recorded.capture("pytest-pass.txt", listOf("pytest"), exitCode = null, timedOut = true),
        )
        assertEquals(Outcome.Timeout, timedOut.status)
        val unknown = Shapers.shape(Recorded.capture("pytest-pass.txt", listOf("pytest"), exitCode = null))
        assertEquals(Outcome.UnknownOutcome, unknown.status)
    }

    @Test
    fun `a pytest usage error is infrastructure, not a verdict`() {
        val shaped = Shapers.shape(
            Recorded.capture(argv = listOf("pytest", "--bogus"), exitCode = 4, output = "ERROR: unrecognized arguments: --bogus\n".toByteArray()),
        )
        assertEquals(Outcome.InfraError, shaped.status)
    }
}
