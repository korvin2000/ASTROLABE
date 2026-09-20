package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JUnitXmlShaperTest {
    private val gradle = listOf("./gradlew", ":cart-core:test")

    @Test
    fun `a stale green report beside a run that executed nothing is inconclusive (IX-13)`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                "gradle-no-tests.txt",
                gradle,
                exitCode = 0,
                reports = listOf(
                    Recorded.report(
                        "junit-stale-green.xml",
                        path = "cart-core/build/test-results/test/TEST-io.astrolabe.id.IdsTest.xml",
                        fresh = false,
                        provenance = "already on disk when the invocation started",
                    ),
                ),
            ),
        )
        assertEquals("junit-xml", shaped.shaper)
        assertEquals(Outcome.Inconclusive, shaped.status)
        assertTrue(!shaped.status.green)
        assertNull(shaped.counts, "an ignored report may not contribute counts")
        assertTrue(shaped.tests.isEmpty())
        assertTrue(shaped.limitations.any { it.contains("records no validated build-cache reuse") }, "${shaped.limitations}")
        assertTrue(shaped.limitations.any { it.contains("no fresh JUnit XML report") }, "${shaped.limitations}")
    }

    @Test
    fun `the same report written fresh for this invocation is green - the rejection is what changed`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                "gradle-no-tests.txt",
                gradle,
                exitCode = 0,
                reports = listOf(Recorded.report("junit-stale-green.xml")),
            ),
        )
        assertEquals(Outcome.Passed, shaped.status)
        assertEquals(6, assertNotNull(shaped.counts).passed)
        assertEquals(6, shaped.tests.size)
    }

    @Test
    fun `a recorded build-cache provenance is the only accepted stale form (D-50)`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                "gradle-no-tests.txt",
                gradle,
                exitCode = 0,
                reports = listOf(
                    Recorded.report(
                        "junit-stale-green.xml",
                        fresh = false,
                        provenance = "${ReportArtifact.BUILD_CACHE_PREFIX}entry 6f2a, inputs unchanged, validated",
                    ),
                ),
            ),
        )
        assertEquals(Outcome.Passed, shaped.status)
        assertEquals(6, assertNotNull(shaped.counts).passed)
    }

    @Test
    fun `the same test name in two modules keeps two identities (D-27, IX-13)`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                argv = gradle,
                exitCode = 1,
                output = "FAILURE: Build failed with an exception.\n".toByteArray(),
                reports = listOf(
                    Recorded.report("junit-module-a.xml", path = "cart-core/build/test-results/test/TEST-com.acme.CartTest.xml"),
                    Recorded.report("junit-module-b.xml", path = "cart-web/build/test-results/test/TEST-com.acme.CartTest.xml"),
                ),
            ),
        )
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(3, shaped.tests.size)
        val sameName = shaped.tests.filter { it.identity.name == "handlesEmpty" }
        assertEquals(2, sameName.size)
        assertEquals(listOf("cart-core", "cart-web"), sameName.map { it.identity.module })
        assertNotEquals(sameName[0].identity.canonical, sameName[1].identity.canonical)
        assertTrue(shaped.ambiguousIdentities.isEmpty(), "different modules are different identities, not ambiguity")
    }

    @Test
    fun `a repeated identity keeps its multiplicity instead of being overwritten (D-27)`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                argv = gradle,
                exitCode = 1,
                output = "FAILURE: there were failing tests.\n".toByteArray(),
                reports = listOf(Recorded.report("junit-duplicate.xml")),
            ),
        )
        assertEquals(2, shaped.tests.size)
        val canonical = shaped.tests.map { it.identity.canonical }.distinct().single()
        assertEquals(2, TestResults.multiplicities(shaped.tests)[canonical])
        assertEquals(setOf(canonical), shaped.ambiguousIdentities)
        assertEquals(Outcome.Failed, shaped.status)
        assertTrue(shaped.view.contains("repeated identities"), shaped.view)
    }

    @Test
    fun `a changed parameterized instance is a different identity, so it cannot be pre-existing (IX-13)`() {
        fun failingOf(resource: String) = Shapers.shape(
            Recorded.capture(
                argv = gradle,
                exitCode = 1,
                output = "FAILURE: there were failing tests.\n".toByteArray(),
                reports = listOf(Recorded.report(resource)),
            ),
        ).tests.single { it.failing }.identity

        val before = failingOf("junit-param-v1.xml")
        val after = failingOf("junit-param-v2.xml")
        assertEquals("tier(int)", before.name)
        assertEquals("tier(int)", after.name)
        assertEquals("3", before.parameterization)
        assertEquals("4", after.parameterization)
        assertNotEquals(before.canonical, after.canonical)
    }

    @Test
    fun `a report that could not be read whole is never a pass`() {
        val shaped = Shapers.shape(
            Recorded.capture(
                "gradle-no-tests.txt",
                gradle,
                exitCode = 0,
                reports = listOf(Recorded.report("junit-malformed.xml")),
            ),
        )
        assertEquals(Outcome.Inconclusive, shaped.status)
        assertTrue(shaped.limitations.any { it.contains("could not be parsed") }, "${shaped.limitations}")
        assertTrue(shaped.limitations.any { it.contains("never a pass") }, "${shaped.limitations}")
    }

    @Test
    fun `a report whose bytes were not captured cannot become evidence`() {
        val artifact = ReportArtifact(
            path = "cart-core/build/test-results/test/TEST-com.acme.CartTest.xml",
            kind = ReportKind.JUnitXml,
            freshForThisInvocation = true,
            provenance = "written by this invocation",
            content = null,
        )
        assertTrue(!artifact.usableAsEvidence)
        assertEquals("cart-core", artifact.moduleOrDerived)
        val shaped = Shapers.shape(Recorded.capture("gradle-no-tests.txt", gradle, exitCode = 0, reports = listOf(artifact)))
        assertEquals(Outcome.Inconclusive, shaped.status)
    }
}
