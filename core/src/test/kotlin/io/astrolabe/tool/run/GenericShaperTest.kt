package io.astrolabe.tool.run

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericShaperTest {
    @Test
    fun `a plain command outside acceptance that exits 0 without counts reads completed but stays inconclusive`() {
        val plain = RunCapture("act-1", listOf("javac", "Main.java"), exitCode = 0)
        val shaped = Shapers.shape(plain)
        assertEquals(Outcome.Inconclusive, shaped.status, "presentation only: never a pass without counts (D-50)")
        assertNull(shaped.counts)
        assertEquals("generic · completed, exit code 0", shaped.view.lines().first())
        for (capture in listOf(
            plain.copy(checkId = "CHK-1"),
            plain.copy(exitCode = 2),
            plain.copy(argv = listOf("mcp:docs/search", "{}")),
            RunCapture("act-2", listOf("sh", "-c", "javac Main.java || true"), exitCode = 0),
        )) {
            val other = Shapers.shape(capture)
            assertTrue(!other.view.lines().first().contains("completed"), "${capture.argv} ${capture.checkId}: ${other.view.lines().first()}")
        }
        assertEquals("generic · inconclusive · exit 0", Shapers.shape(plain.copy(checkId = "CHK-1")).view.lines().first(), "acceptance keeps the verdict wording")
    }

    @Test
    fun `a long plain output fills the budget with its head and a larger tail`() {
        val output = (1..2_000).joinToString("\n", postfix = "\n") { "step $it of the build" }
        val budget = ShapeBudget(1_000, HeuristicEstimator())
        val shaped = Shapers.shape(RunCapture("act-1", listOf("make", "all"), exitCode = 2, output = output.toByteArray()), budget)
        assertTrue(HeuristicEstimator().estimate(shaped.view).upperBoundTokens <= 1_000, shaped.view)
        assertTrue(shaped.viewTruncated)
        val title = Regex("""output \(head (\d+) / tail (\d+) of 2000 lines\):""").find(shaped.view)
        val (head, tail) = assertNotNull(title, shaped.view).destructured.toList().map { it.toInt() }
        assertTrue(head + tail > 60, "the bigger budget shows more than the old 30 + 30 lines: $head + $tail")
        assertTrue(tail > head, "errors usually come last: $head / $tail")
        assertTrue(shaped.view.contains("  step 1 of the build\n") && shaped.view.contains("  step 2000 of the build\n"), shaped.view)
        assertTrue(shaped.view.contains("  … ${2_000 - head - tail} lines elided …"), shaped.view)

        val oneLine = Shapers.shape(RunCapture("act-2", listOf("node", "x.js"), exitCode = 0, output = "{\"k\":\"${"v".repeat(20_000)}\"}".toByteArray()), budget)
        assertTrue(oneLine.viewTruncated && oneLine.view.contains("{\"k\":\"vvv"), "a single wide line is cut, not dropped: ${oneLine.view.take(300)}")
    }

    @Test
    fun `a first line wider than the head share is cut and the last line still shows`() {
        val output = "x".repeat(3_000) + "\n" + (1..200).joinToString("\n", postfix = "\n") { "line $it " + "y".repeat(70) } + "ZZZ-LAST summary line\n"
        val budget = ShapeBudget(1_000, HeuristicEstimator())
        val shaped = Shapers.shape(RunCapture("act-3", listOf("make", "all"), exitCode = 2, output = output.toByteArray()), budget)

        assertTrue(HeuristicEstimator().estimate(shaped.view).upperBoundTokens <= 1_000, shaped.view)
        assertTrue(shaped.view.contains("  xxx") && shaped.view.contains("x …\n"), "the head line is cut: ${shaped.view.take(400)}")
        assertTrue(Regex("""  … \d+ lines elided …""").containsMatchIn(shaped.view), shaped.view)
        assertTrue(shaped.view.contains("  ZZZ-LAST summary line"), shaped.view)
    }

    @Test
    fun `a short structured output whose first line is huge keeps its last line`() {
        val output = "x".repeat(3_000) + "\nrunning 1 test\ntest a::b ... ok\ntest result: ok. 1 passed; 0 failed; 0 ignored; 0 measured\nZZZ-LAST summary line\n"
        val budget = ShapeBudget(400, HeuristicEstimator())
        val shaped = Shapers.shape(RunCapture("act-4", listOf("cargo", "test"), exitCode = 0, output = output.toByteArray()), budget)

        assertTrue(shaped.view.startsWith("generic/cargo"), shaped.view.take(200))
        assertTrue(HeuristicEstimator().estimate(shaped.view).upperBoundTokens <= 400, shaped.view)
        assertTrue(shaped.view.contains("  ZZZ-LAST summary line"), shaped.view)
    }

    @Test
    fun `cargo test summary and per-test lines`() {
        val shaped = Shapers.shape(Recorded.capture("cargo-fail.txt", listOf("cargo", "test"), exitCode = 101))
        assertEquals("generic/cargo", shaped.shaper)
        assertEquals(Outcome.Failed, shaped.status)
        val counts = assertNotNull(shaped.counts)
        assertEquals(4, counts.passed)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.skipped)
        assertEquals(6, counts.discovered)
        val failing = shaped.tests.single { it.failing }
        assertEquals("discount::tests", failing.identity.suite)
        assertEquals("tier_three", failing.identity.name)
    }

    @Test
    fun `go test names its package as the module and keeps subtests apart`() {
        val shaped = Shapers.shape(Recorded.capture("go-fail.txt", listOf("go", "test", "./..."), exitCode = 1))
        assertEquals("generic/go", shaped.shaper)
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(3, shaped.tests.size)
        assertTrue(shaped.tests.all { it.identity.module == "example.com/shop/cart" }, "${shaped.tests}")
        val subtest = shaped.tests.single { it.identity.name == "tier_3" }
        assertEquals("TestDiscount", subtest.identity.suite)
        assertEquals(2, assertNotNull(shaped.counts).failed)
    }

    @Test
    fun `go test stamps cases with their own package summary`() {
        val output = """
            --- FAIL: TestSame (0.00s)
            FAIL example.com/shop/a 0.01s
            --- FAIL: TestSame (0.00s)
            FAIL example.com/shop/b 0.01s
        """.trimIndent().toByteArray()
        val shaped = Shapers.shape(Recorded.capture(argv = listOf("go", "test", "./..."), exitCode = 1, output = output))
        assertEquals(listOf("example.com/shop/a", "example.com/shop/b"), shaped.tests.map { it.identity.module })
        assertNotEquals(shaped.tests[0].identity.canonical, shaped.tests[1].identity.canonical)
    }

    @Test
    fun `go test without a package summary gives no reusable failure identity`() {
        val output = "--- FAIL: TestSame (0.00s)\n".toByteArray()
        val shaped = Shapers.shape(Recorded.capture(argv = listOf("go", "test", "./..."), exitCode = 1, output = output))
        assertTrue(shaped.tests.isEmpty())
        assertTrue(shaped.limitations.any { it.contains("no package summary") })
    }

    @Test
    fun `unittest derives passes from Ran minus the failure breakdown`() {
        val shaped = Shapers.shape(
            Recorded.capture("unittest-fail.txt", listOf("python", "-m", "unittest", "discover"), exitCode = 1),
        )
        assertEquals("generic/unittest", shaped.shaper)
        assertEquals(Outcome.Failed, shaped.status)
        val counts = assertNotNull(shaped.counts)
        assertEquals(3, counts.passed)
        assertEquals(1, counts.failed)
        assertEquals(1, counts.skipped)
        assertEquals(5, counts.discovered)
        val failing = shaped.tests.single { it.failing }
        assertEquals("test_tier", failing.identity.name)
        assertEquals("tests.test_discount.DiscountTest", failing.identity.file)
    }

    @Test
    fun `mocha and dotnet summaries are recognised`() {
        val mocha = Shapers.shape(Recorded.capture("mocha-fail.txt", listOf("npx", "mocha"), exitCode = 1))
        assertEquals("generic/mocha", mocha.shaper)
        assertEquals(Outcome.Failed, mocha.status)
        assertEquals(2, assertNotNull(mocha.counts).passed)
        assertEquals(1, mocha.counts.failed)

        val dotnet = Shapers.shape(Recorded.capture("dotnet-pass.txt", listOf("dotnet", "test"), exitCode = 0))
        assertEquals("generic/dotnet", dotnet.shaper)
        assertEquals(Outcome.Passed, dotnet.status)
        assertEquals(5, assertNotNull(dotnet.counts).passed)
        assertEquals(5, dotnet.counts.discovered)
    }

    @Test
    fun `the node test runner's summary is recognised under the spec and TAP reporters`() {
        val spec = "✔ creates a note (12.3ms)\n✔ lists notes (1.1ms)\nℹ tests 7\nℹ suites 0\nℹ pass 7\nℹ fail 0\nℹ cancelled 0\nℹ skipped 0\nℹ todo 0\nℹ duration_ms 310.4\n"
        val green = Shapers.shape(Recorded.capture(argv = listOf("npm", "test"), exitCode = 0, output = spec.toByteArray()))
        assertEquals("generic/node", green.shaper)
        assertEquals(Outcome.Passed, green.status)
        assertEquals(7, assertNotNull(green.counts).passed)
        assertEquals(7, green.counts.discovered)
        assertTrue(green.limitations.none { it.contains("no shaped parser") }, "${green.limitations}")

        val tap = "TAP version 13\nok 1 - a\nnot ok 2 - b\n1..3\n# tests 3\n# suites 0\n# pass 1\n# fail 1\n# cancelled 1\n# skipped 0\n# todo 0\n"
        val red = Shapers.shape(Recorded.capture(argv = listOf("node", "--test"), exitCode = 1, output = tap.toByteArray()))
        assertEquals("generic/node", red.shaper)
        assertEquals(Outcome.Failed, red.status)
        assertEquals(1, assertNotNull(red.counts).passed)
        assertEquals(2, red.counts.failed, "a cancelled test did not pass")

        val partial = Shapers.shape(Recorded.capture(argv = listOf("make", "check"), exitCode = 0, output = "# tests 3\n".toByteArray()))
        assertNull(partial.counts, "one summary line alone is not a node summary")
        val cut = "ℹ tests 2\nℹ pass 2\nℹ fail 0\nℹ tests 5\nℹ pass 5\n"
        assertNull(Shapers.shape(Recorded.capture(argv = listOf("npm", "test"), exitCode = 0, output = cut.toByteArray())).counts, "a second summary without its fail line is a cut log")

        // Node lines that do not add up are evidence present and not trusted: mocha's "1 passing" must not turn the run green.
        val hidden = "# tests 2\n# pass 1\n# fail 1\n# tests 1\n  1 passing (3ms)\n"
        val unsure = Shapers.shape(Recorded.capture(argv = listOf("npm", "test"), exitCode = 0, output = hidden.toByteArray()))
        assertEquals("generic/node", unsure.shaper)
        assertNull(unsure.counts)
        assertEquals(Outcome.Inconclusive, unsure.status)

        // A script that ran node's tests and then mocha: the green node summary must not hide mocha's failure behind an exit 0.
        val mixed = spec + "\n  2 passing (12ms)\n  1 failing\n"
        val both = Shapers.shape(Recorded.capture(argv = listOf("npm", "test"), exitCode = 0, output = mixed.toByteArray()))
        assertEquals("generic/node+mocha", both.shaper)
        assertEquals(1, assertNotNull(both.counts).failed)
        assertEquals(9, both.counts.passed)
        assertEquals(Outcome.Failed, both.status)
    }

    @Test
    fun `node test results are read one by one under the spec and TAP reporters with nested suites`() {
        fun outcomes(shaped: Shaped) = shaped.tests.associate { it.identity.display to it.outcome }
        val spec = Shapers.shape(Recorded.capture("node-spec-fail.txt", argv = listOf("node", "--test"), exitCode = 1))
        assertEquals(Outcome.Failed, spec.status)
        assertFalse(spec.evidenceIncomplete)
        assertEquals(7, spec.tests.size, "suites are no tests; the failing-tests section repeats, never adds: ${spec.tests}")
        assertEquals(
            mapOf(
                "math::adds" to TestOutcome.Passed, "math::subtracts" to TestOutcome.Failed, "math > inner::deep pass" to TestOutcome.Passed,
                "math > inner::skipped one" to TestOutcome.Skipped, "math > inner::todo one" to TestOutcome.Skipped,
                "top level" to TestOutcome.Passed, "name (with parens)" to TestOutcome.Passed,
            ),
            outcomes(spec),
        )

        val tap = Shapers.shape(Recorded.capture("node-tap-fail.txt", argv = listOf("node", "--test", "--test-reporter=tap"), exitCode = 1))
        assertEquals(13, tap.tests.size, "a parent test is a test, a suite is not: ${tap.tests}")
        val failing = tap.tests.filter { it.failing }
        assertEquals(listOf("math::subtracts", "parent::child bad", "parent", "b fails"), failing.map { it.identity.display })
        assertEquals("1 == 2", failing.first().message)
        assertEquals(setOf(TestIdentity(name = "dup").canonical), tap.ambiguousIdentities, "one name in two files stays ambiguous")

        // The spec reporter cannot tell a parent test from a suite: the summary's count settles it, or nothing is recorded.
        val parents = "▶ parent\n  ✔ child ok (0.1ms)\n  ✖ child bad (0.2ms)\n✖ parent (0.8ms)\nℹ tests 3\nℹ pass 1\nℹ fail 2\n"
        assertEquals(listOf("parent::child bad", "parent"), Shapers.shape(Recorded.capture(argv = listOf("node", "--test"), exitCode = 1, output = parents.toByteArray())).tests.filter { it.failing }.map { it.identity.display })
        val unsettled = Shapers.shape(Recorded.capture(argv = listOf("node", "--test"), exitCode = 1, output = parents.replace("ℹ tests 3", "ℹ tests 4").replace("ℹ pass 1", "ℹ pass 2").toByteArray()))
        assertEquals(emptyList(), unsettled.tests)
        assertEquals(4, assertNotNull(unsettled.counts).discovered)
        assertTrue(unsettled.limitations.any { it.contains("do not add up") }, "${unsettled.limitations}")

        // A cut output: the results read so far, no summary — an incomplete record, never a pass.
        val cut = Recorded.text("node-spec-fail.txt").substringBefore("ℹ tests").replace("✖ subtracts", "✔ subtracts")
        val partial = Shapers.shape(Recorded.capture(argv = listOf("node", "--test"), exitCode = 0, output = cut.toByteArray()))
        assertTrue(partial.evidenceIncomplete)
        assertNull(partial.counts)
        assertEquals(Outcome.Inconclusive, partial.status)
        assertEquals(TestOutcome.Passed, outcomes(partial)["math::subtracts"])
        assertTrue(partial.limitations.any { it.contains("no complete summary") }, "${partial.limitations}")
    }

    @Test
    fun `an unrecognised command yields no counts and never a green exit code (D-50)`() {
        val green = Shapers.shape(
            Recorded.capture(argv = listOf("make", "check"), exitCode = 0, output = "everything up to date\n".toByteArray()),
        )
        assertEquals("generic", green.shaper)
        assertNull(green.counts, "a generic exit code never becomes a count (§8.3)")
        assertEquals(Outcome.Inconclusive, green.status)
        assertTrue(green.limitations.any { it.contains("no shaped parser") }, "${green.limitations}")
        assertTrue(green.limitations.any { it.contains("not proof that anything ran") }, "${green.limitations}")

        val red = Shapers.shape(
            Recorded.capture(argv = listOf("make", "check"), exitCode = 2, output = "make: *** [check] Error 2\n".toByteArray()),
        )
        assertEquals(Outcome.Failed, red.status)
        assertNull(red.counts)
    }

    @Test
    fun `diagnostic lines are lifted into the view`() {
        val output = buildString {
            repeat(5) { appendLine("compiling module $it") }
            appendLine("src/router.ts(42,17): error TS2322: Type 'string' is not assignable to type 'number'.")
            appendLine("src/cart.py:18:5: F401 'os' imported but unused")
            repeat(5) { appendLine("done step $it") }
        }
        val shaped = Shapers.shape(Recorded.capture(argv = listOf("tsc", "--noEmit"), exitCode = 2, output = output.toByteArray()))
        assertEquals(Outcome.Failed, shaped.status)
        assertTrue(shaped.view.contains("error lines (2):"), shaped.view)
        assertTrue(shaped.view.contains("TS2322"), shaped.view)
        assertTrue(shaped.view.contains("F401"), shaped.view)
    }
}
