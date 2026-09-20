package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenericShaperTest {
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
