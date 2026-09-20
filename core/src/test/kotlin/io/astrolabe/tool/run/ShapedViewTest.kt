package io.astrolabe.tool.run

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapedViewTest {
    private fun longRun(lines: Int): ByteArray = buildString {
        appendLine("============================= test session starts ==============================")
        repeat(lines) {
            appendLine(
                "worker-07 processed batch ${"%05d".format(it)} of the nightly reconciliation queue; " +
                    "checksum 9f2ab41c; no anomalies observed",
            )
        }
        appendLine("============================== 6 passed in 0.12s ===============================")
    }.toByteArray()

    @Test
    fun `the prompt budget and the capture limit are two distinct truncation facts (FX-10)`() {
        val capture = Recorded.capture(
            argv = listOf("pytest", "-q"),
            exitCode = 0,
            output = longRun(5_000),
            captureComplete = false,
        )
        val shaped = Shapers.shape(capture, ShapeBudget(recallAlias = "#57"))
        assertEquals(Outcome.Passed, shaped.status)
        assertTrue(shaped.viewTruncated, "the 1200-token budget cannot hold 5002 lines")
        assertTrue(shaped.captureTruncated, "captureComplete=false is the capture limit, not the prompt budget")
        assertTrue(shaped.view.contains("view truncated at prompt budget 1200 tokens"), shaped.view)
        assertTrue(shaped.view.contains("capture incomplete: log truncated at"), shaped.view)
        assertTrue(shaped.view.contains("full: #57 · 5002 lines"), shaped.view)
        assertEquals(
            "full: #57 · 5002 lines captured (capture limit reached; later bytes were never stored)",
            shaped.recallHint,
        )
        assertTrue(shaped.view.length < capture.output.size / 10, "the view is bounded, the log is not")
        assertTrue(shaped.view.contains("lines elided"), shaped.view)
    }

    @Test
    fun `a complete capture that overflows the prompt marks only the view`() {
        val shaped = Shapers.shape(
            Recorded.capture(argv = listOf("pytest", "-q"), exitCode = 0, output = longRun(5_000)),
            ShapeBudget(recallAlias = "#58"),
        )
        assertTrue(shaped.viewTruncated)
        assertTrue(!shaped.captureTruncated)
        assertTrue(!shaped.view.contains("capture incomplete"), shaped.view)
        assertEquals("full: #58 · 5002 lines", shaped.recallHint)
    }

    @Test
    fun `an output that fits is marked neither way and carries no recall pointer`() {
        val shaped = Shapers.shape(Recorded.capture("pytest-pass.txt", listOf("pytest", "-q"), exitCode = 0))
        assertTrue(!shaped.viewTruncated && !shaped.captureTruncated)
        assertNull(shaped.recallHint)
        assertTrue(!shaped.view.contains("truncated"), shaped.view)
    }

    @Test
    fun `without a recall alias the view carries a placeholder for the runner to replace`() {
        val shaped = Shapers.shape(Recorded.capture(argv = listOf("pytest", "-q"), exitCode = 0, output = longRun(200)))
        assertTrue(shaped.view.contains(ShapeBudget.RECALL_PLACEHOLDER), shaped.view)
    }

    @Test
    fun `a token estimator bounds the view to the mandatory head`() {
        val shaped = Shapers.shape(
            Recorded.capture("pytest-fail-param.txt", listOf("pytest", "-q"), exitCode = 1),
            ShapeBudget(tokens = 40, estimator = HeuristicEstimator(), recallAlias = "#9"),
        )
        assertTrue(shaped.viewTruncated)
        assertTrue(shaped.view.contains("counts: 5 passed · 1 failed"), shaped.view)
        assertTrue(shaped.view.lines().size < 12, shaped.view)
        assertEquals(Outcome.Failed, shaped.status, "shortening the view never changes the status")
    }

    @Test
    fun `the registry selects by argv shape, wrapped runner and output markers`() {
        fun pick(argv: List<String>, resource: String? = null, shell: Boolean = false, output: ByteArray? = null) =
            Shapers.select(Recorded.capture(resource, argv, exitCode = 0, shell = shell, output = output)).id

        assertEquals("pytest", pick(listOf("pytest", "-q")))
        assertEquals("pytest", pick(listOf("python3", "-m", "pytest")))
        assertEquals("pytest", pick(listOf("sh", "-c", "pytest -q || true"), "pytest-wrapper.txt", shell = true))
        assertEquals("jest", pick(listOf("npx", "vitest", "run")))
        assertEquals("jest", pick(listOf("npm", "test"), "jest-pass.txt"))
        assertEquals("junit-xml", pick(listOf("./gradlew", ":cart-core:test"), "gradle-no-tests.txt"))
        assertEquals("junit-xml", pick(listOf("mvn", "-q", "test"), "gradle-no-tests.txt"))
        assertEquals("generic", pick(listOf("make", "check"), output = "ok\n".toByteArray()))
        assertEquals(listOf("pytest", "jest", "junit-xml", "generic"), Shapers.ordered.map { it.id })
    }

    @Test
    fun `wrapper detection names the construct and the wrapped runner`() {
        fun wrapper(cmd: String) = Invocations.detectWrapper(
            Recorded.capture(argv = listOf("sh", "-c", cmd), exitCode = 0, shell = true, output = ByteArray(0)),
        )
        assertEquals("|| true", wrapper("pytest -q || true")?.wrapper)
        assertEquals("; true", wrapper("pytest -q ; true")?.wrapper)
        assertEquals("&& echo", wrapper("pytest -q && echo done")?.wrapper)
        assertEquals(listOf("pytest", "-q", "-k", "cart discount"), wrapper("""pytest -q -k "cart discount" || echo FAIL""")?.runnerArgv)
        assertNull(wrapper("pytest -q"))
        assertNull(Invocations.detectWrapper(Recorded.capture(argv = listOf("pytest", "-q"), exitCode = 0)))
    }

    @Test
    fun `a canonical identity separates every component and escapes its separator`() {
        val core = TestIdentity(check = "tests", module = "cart-core", file = "CartTest", name = "handlesEmpty")
        val web = core.copy(module = "cart-web")
        assertNotEquals(core.canonical, web.canonical)
        assertNotEquals(
            TestIdentity(name = "a", suite = "b").canonical,
            TestIdentity(name = "b", suite = "a").canonical,
        )
        assertNotEquals(
            TestIdentity(module = "a|b", name = "c").canonical,
            TestIdentity(module = "a", name = "b|c").canonical,
        )
        assertEquals("cart-core/CartTest::handlesEmpty", core.display)
        assertEquals(
            "tests/test_x.py::TestA::test_b[3]",
            TestIdentity(file = "tests/test_x.py", suite = "TestA", name = "test_b", parameterization = "3").display,
        )
        assertEquals("test_b" to "3", TestIdentity.splitParameterization("test_b[3]"))
        assertEquals("test_b" to null, TestIdentity.splitParameterization("test_b"))
    }

    @Test
    fun `a shaped result cannot claim green without executed, parsed evidence`() {
        assertFailsWith<IllegalArgumentException> {
            Shaped(Outcome.Passed, null, emptyList(), "", false, false, shaper = "x")
        }
        assertFailsWith<IllegalArgumentException> {
            Shaped(Outcome.Passed, Counts(), emptyList(), "", false, false, shaper = "x")
        }
    }
}
