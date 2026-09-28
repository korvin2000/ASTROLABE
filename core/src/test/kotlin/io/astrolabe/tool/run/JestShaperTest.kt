package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JestShaperTest {
    @Test
    fun `a green jest run parses its summary`() {
        val shaped = Shapers.shape(Recorded.capture("jest-pass.txt", listOf("npx", "jest"), exitCode = 0))
        assertEquals("jest", shaped.shaper)
        assertEquals(Outcome.Passed, shaped.status)
        val counts = assertNotNull(shaped.counts)
        assertEquals(12, counts.passed)
        assertEquals(0, counts.failed)
        assertEquals(12, counts.discovered)
    }

    @Test
    fun `a jest failure block names file, describe and test`() {
        val shaped = Shapers.shape(
            Recorded.capture("jest-fail.txt", listOf("npx", "jest"), exitCode = 1, checkId = "tests"),
        )
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(11, assertNotNull(shaped.counts).passed)
        assertEquals(1, shaped.counts.failed)
        val failing = shaped.tests.single { it.failing }
        assertEquals("tests", failing.identity.check)
        assertEquals("src/discount.test.ts", failing.identity.file)
        assertEquals("discount", failing.identity.suite)
        assertEquals("applies tier 3", failing.identity.name)
        assertEquals("expect(received).toBe(expected) // Object.is equality", failing.message)
    }

    @Test
    fun `vitest check marks and the failed-tests block describe one test, not two`() {
        val shaped = Shapers.shape(Recorded.capture("vitest-fail.txt", listOf("npx", "vitest", "run"), exitCode = 1))
        assertEquals(Outcome.Failed, shaped.status)
        val counts = assertNotNull(shaped.counts)
        assertEquals(3, counts.passed)
        assertEquals(1, counts.failed)
        assertEquals(4, counts.discovered)
        assertEquals(1, shaped.tests.count { it.failing }, "the × line and the FAIL block are the same test")
        val failing = shaped.tests.single { it.failing }
        assertEquals("src/discount.test.ts", failing.identity.file)
        assertEquals("discount", failing.identity.suite)
        assertEquals("applies tier 3", failing.identity.name)
        assertEquals("AssertionError: expected 4 to be 5 // Object.is equality", failing.message)
        val passing = shaped.tests.single { it.outcome == TestOutcome.Passed }
        assertEquals("applies tier 1", passing.identity.name)
    }

    @Test
    fun `a jest failure hidden behind a shell wrapper still reports failed (FX-08)`() {
        val capture = Recorded.capture(
            "jest-fail.txt",
            argv = listOf("bash", "-c", "npx jest --ci || echo done"),
            shell = true,
            exitCode = 0,
        )
        val shaped = Shapers.shape(capture)
        assertEquals("jest", shaped.shaper)
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals("|| echo", assertNotNull(shaped.wrapper).wrapper)
        assertEquals(listOf("npx", "jest", "--ci"), shaped.wrapper.runnerArgv)
    }

    @Test
    fun `a jest json report supplies identities directly`() {
        val json = """
            {"numTotalTests":2,"numPassedTests":1,"numFailedTests":1,
             "testResults":[{"name":"/home/dev/shop/src/discount.test.ts","assertionResults":[
               {"ancestorTitles":["discount"],"title":"applies tier 1","status":"passed","duration":2},
               {"ancestorTitles":["discount"],"title":"applies tier 3","status":"failed",
                "failureMessages":["Error: expect(received).toBe(expected)\n    at Object.<anonymous>"],"duration":5}]}]}
        """.trimIndent().toByteArray()
        val shaped = Shapers.shape(
            Recorded.capture(
                "jest-fail.txt",
                listOf("npx", "jest", "--json", "--outputFile=build/act-7/jest.json"),
                exitCode = 1,
                reports = listOf(
                    ReportArtifact(
                        path = "build/act-7/jest.json",
                        kind = ReportKind.JestJson,
                        freshForThisInvocation = true,
                        provenance = "written by this invocation",
                        content = json,
                    ),
                ),
            ),
        )
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(2, shaped.tests.size)
        val failing = shaped.tests.single { it.failing }
        assertEquals("src/discount.test.ts", failing.identity.file)
        assertEquals("discount", failing.identity.suite)
        assertEquals("applies tier 3", failing.identity.name)
        assertEquals(5L, failing.durationMillis)
        assertTrue(shaped.view.contains("applies tier 3"), shaped.view)
    }

    @Test
    fun `jest identities are equal when the same suite runs under different roots`() {
        fun shapedUnder(root: String) = Shapers.shape(Recorded.capture(
            argv = listOf("npx", "jest"), exitCode = 1,
            reports = listOf(ReportArtifact("build/act-7/jest.json", ReportKind.JestJson, true, "written by this invocation",
                """{"numTotalTests":1,"numFailedTests":1,"testResults":[{"name":"$root/services/a/test.ts","assertionResults":[{"title":"fails","status":"failed"}]}]}""".toByteArray())),
        ).copy(cwd = null, executionRoot = root))
        val workspace = shapedUnder("/home/dev/shop").tests.single().identity
        val baseline = shapedUnder("/var/state/candidates/W-1-a1-s0").tests.single().identity
        assertEquals("services/a/test.ts", workspace.file)
        assertEquals(workspace.canonical, baseline.canonical)
    }

    @Test
    fun `jest json keeps directories for equal test basenames`() {
        val report = """
            {"numTotalTests":2,"numFailedTests":2,"testResults":[
              {"name":"/home/dev/shop/services/a/test.ts","assertionResults":[{"title":"fails","status":"failed"}]},
              {"name":"/home/dev/shop/services/b/test.ts","assertionResults":[{"title":"fails","status":"failed"}]}
            ]}
        """.trimIndent().toByteArray()
        val shaped = Shapers.shape(Recorded.capture(
            argv = listOf("npx", "jest"), exitCode = 1,
            reports = listOf(ReportArtifact("build/act-7/jest.json", ReportKind.JestJson, true, "written by this invocation", report)),
        ))
        assertEquals(listOf("services/a/test.ts", "services/b/test.ts"), shaped.tests.map { it.identity.file })
        assertNotEquals(shaped.tests[0].identity.canonical, shaped.tests[1].identity.canonical)
    }
}
