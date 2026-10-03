package io.astrolabe.tool.run

import io.astrolabe.evidence.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JUnitXmlShaperTest {
    @Test
    fun `report collection includes deeply nested module reports`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val relative = List(13) { "m$it" }.joinToString("/") + "/build/test-results/test/TEST-deep.xml"
        val report = root.resolve(relative)
        java.nio.file.Files.createDirectories(report.parent)
        java.nio.file.Files.writeString(report, "<testsuite tests=\"1\"><testcase name=\"t\"><failure>NEW</failure></testcase></testsuite>")
        assertEquals(listOf(relative), JUnitReports(root, "act-deep").collect().map { it.path })
    }

    @Test
    fun `incomplete report discovery cannot certify a structured pass`() {
        val report = ReportArtifact("TEST.xml", ReportKind.JUnitXml, true, "fresh",
            "<testsuite tests=\"1\"><testcase name=\"t\"/></testsuite>".toByteArray())
        val incomplete = report.copy(collectionComplete = false)
        assertNotEquals(report, incomplete)
        val shaped = Shapers.shape(Recorded.capture(argv = listOf("./gradlew", "test"), exitCode = 0, reports = listOf(incomplete)))
        assertTrue(!shaped.reportComplete)
    }

    @Test
    fun `unreadable unrelated subtrees preserve reports but with incomplete discovery`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        org.junit.jupiter.api.Assumptions.assumeTrue(posix, "directory permissions need POSIX")
        val report = root.resolve("build/test-results/test/TEST-a.xml")
        java.nio.file.Files.createDirectories(report.parent)
        java.nio.file.Files.writeString(report, "<testsuite tests=\"0\"/>")
        val locked = java.nio.file.Files.createDirectories(root.resolve("data/private"))
        java.nio.file.Files.setPosixFilePermissions(locked, emptySet())
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.isReadable(locked), "running with privileges")
            assertTrue(!JUnitReports(root, "act-unreadable").collect().single().collectionComplete)
        } finally {
            java.nio.file.Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `report collection refuses an unreadable report directory`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        org.junit.jupiter.api.Assumptions.assumeTrue(posix, "directory permissions need POSIX")
        val reports = java.nio.file.Files.createDirectories(root.resolve("module/build/test-results"))
        java.nio.file.Files.setPosixFilePermissions(reports, emptySet())
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.isReadable(reports), "running with privileges")
            assertFailsWith<java.io.IOException> { JUnitReports(root, "act-unreadable").collect() }
        } finally {
            java.nio.file.Files.setPosixFilePermissions(reports, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `all failure and error elements remain complete comparison evidence`() {
        val shaped = xmlShape("""
            <testsuite tests="1"><testcase name="t">
              <failure message="new message" type="AssertionError"> NEW 12 0x123 3ms </failure>
              <error message="old message" type="Error">OLD</error>
            </testcase></testsuite>
        """.trimIndent())
        val result = shaped.tests.single()
        assertTrue(shaped.reportComplete)
        assertTrue(result.failureContentComplete)
        assertEquals(listOf("failure", "error"), result.failureContent.map { it.kind })
        assertEquals(listOf(" NEW 12 0x123 3ms ", "OLD"), result.failureContent.map { it.body })
        assertEquals("new message", result.failureContent.first().attributes["message"])
        assertEquals("AssertionError", result.failureContent.first().attributes["type"])
    }

    @Test
    fun `a malformed red report retains observations without certifying its partial pass`() {
        val shaped = xmlShape("""
            <testsuite tests="3"><testcase name="t"/>
            <testcase name="legacy"><failure>OLD</failure></testcase>
            <testcase name="t"><failure>NEW
        """.trimIndent())
        assertEquals(Outcome.Failed, shaped.status)
        assertEquals(listOf(TestOutcome.Passed, TestOutcome.Failed), shaped.tests.map { it.outcome })
        assertTrue(!shaped.reportComplete)
        assertTrue(xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"/></testsuite>", 0).reportComplete)
    }

    @Test
    fun `suite count mismatch and absent test identity make a report incomplete`() {
        val reports = listOf(
            "<testsuite tests=\"2\"><testcase name=\"t\"/></testsuite>",
            "<testsuite tests=\"1\"><testcase/></testsuite>",
        )
        reports.forEach { assertTrue(!xmlShape(it, 0).reportComplete) }
        assertTrue(xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"/></testsuite>", 0).reportComplete)
    }

    @Test
    fun `suite failure and skip totals cannot contradict a parsed pass`() {
        listOf("failures", "errors", "skipped").forEach { counter ->
            val shaped = xmlShape("<testsuite tests=\"1\" $counter=\"1\"><testcase name=\"t\"/></testsuite>", 0)
            assertTrue(!shaped.reportComplete, counter)
        }
    }

    @Test
    fun `suite totals count each testcase for every reported failure kind`() {
        val shaped = xmlShape("<testsuite tests=\"1\" failures=\"1\" errors=\"1\"><testcase name=\"t\"><failure>NEW</failure><error>OLD</error></testcase></testsuite>")
        assertTrue(shaped.reportComplete)
        assertEquals(2, shaped.tests.single().failureContent.size)
    }

    @Test
    fun `a skipped element cannot overwrite a failure outcome`() {
        val shaped = xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"><failure>NEW</failure><skipped/></testcase></testsuite>")
        assertTrue(shaped.tests.single().failing)
    }

    @Test
    fun `nested failure markup is explicitly incomplete`() {
        val shaped = xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"><failure><detail code=\"NEW\">OLD</detail></failure></testcase></testsuite>")
        assertTrue(shaped.tests.single().failing)
        assertTrue(!shaped.tests.single().failureContentComplete)
        assertTrue(!shaped.reportComplete)
    }

    @Test
    fun `failure comments and processing instructions make comparison incomplete`() {
        listOf("<!-- NEW -->", "<?failure NEW?>").forEach { markup ->
            val shaped = xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"><failure>OLD$markup</failure></testcase></testsuite>")
            assertTrue(shaped.tests.single().failing)
            assertTrue(!shaped.reportComplete)
            assertTrue(!shaped.tests.single().failureContentComplete)
        }
    }

    @Test
    fun `legacy Java report constructors remain callable`() {
        assertNotNull(TestResult::class.java.getConstructor(
            TestIdentity::class.java, TestOutcome::class.java, String::class.java, java.lang.Long::class.java, String::class.java,
        ))
        assertNotNull(ReportArtifact::class.java.getConstructor(
            String::class.java, ReportKind::class.java, Boolean::class.javaPrimitiveType,
            String::class.java, ByteArray::class.java, String::class.java,
        ))
        assertNotNull(Shaped::class.java.getConstructor(
            Outcome::class.java, io.astrolabe.evidence.Counts::class.java, List::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
            WrapperDetection::class.java, String::class.java, List::class.java,
        ))
    }

    @Test
    fun `nested suites validate their own counts without counting cases twice`() {
        val shaped = xmlShape("<testsuites><testsuite tests=\"1\"><testsuite tests=\"1\"><testcase name=\"t\"/></testsuite></testsuite></testsuites>", 0)
        assertTrue(shaped.reportComplete)
        assertEquals(1, shaped.counts?.discovered)
        assertEquals(Outcome.Passed, shaped.status)
    }

    @Test
    fun `unexecuted or unsupported testcase outcomes cannot certify a pass`() {
        val notRun = xmlShape("<testsuite tests=\"1\"><testcase name=\"t\" status=\"notrun\" result=\"suppressed\"/></testsuite>", 0)
        assertEquals(TestOutcome.Skipped, notRun.tests.single().outcome)
        val extension = xmlShape("<testsuite tests=\"1\"><testcase name=\"t\"><flakyFailure>NEW</flakyFailure></testcase></testsuite>", 0)
        assertTrue(!extension.reportComplete)
    }

    @Test
    fun `a captured report remains fully parsed when the display alone is shortened`() {
        val xml = "<testsuite tests=\"1\"><testcase name=\"t\"><failure>${"body ".repeat(1000)}</failure></testcase></testsuite>"
        val shaped = JUnitXmlShaper().shape(Recorded.capture(
            argv = listOf("./gradlew", "test"), exitCode = 1,
            output = "long line\n".repeat(500).toByteArray(),
            reports = listOf(ReportArtifact("TEST.xml", ReportKind.JUnitXml, true, "fresh", xml.toByteArray())),
        ), ShapeBudget(tokens = 120))
        assertTrue(shaped.viewTruncated)
        assertTrue(shaped.reportComplete)
        assertEquals("body ".repeat(1000), shaped.tests.single().failureContent.single().body)
    }

    private fun xmlShape(xml: String, exitCode: Int = 1): Shaped = Shapers.shape(Recorded.capture(
        argv = listOf("./gradlew", "test"), exitCode = exitCode,
        reports = listOf(ReportArtifact("TEST-regression.xml", ReportKind.JUnitXml, true, "fresh", xml.toByteArray())),
    ))

    @Test
    fun `report collection skips an unreadable directory elsewhere in the tree`(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) {
        val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        org.junit.jupiter.api.Assumptions.assumeTrue(posix, "directory permissions need POSIX")
        val report = root.resolve("build/test-results/test/TEST-a.xml")
        java.nio.file.Files.createDirectories(report.parent)
        java.nio.file.Files.writeString(report, "<testsuite tests=\"0\"/>")
        val locked = java.nio.file.Files.createDirectories(root.resolve("data/private"))
        java.nio.file.Files.setPosixFilePermissions(locked, emptySet())
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.isReadable(locked), "running with privileges")
            val collected = JUnitReports(root, "act-1").collect()
            assertEquals(listOf("build/test-results/test/TEST-a.xml"), collected.map { it.path })
        } finally {
            java.nio.file.Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }

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
    fun `nested modules with equal leaf names keep distinct identities`() {
        val shaped = Shapers.shape(Recorded.capture(
            argv = gradle,
            exitCode = 1,
            output = "FAILURE: there were failing tests.\n".toByteArray(),
            reports = listOf(
                Recorded.report("junit-module-a.xml", path = "services/a/cart/build/test-results/test/TEST-com.acme.CartTest.xml"),
                Recorded.report("junit-module-b.xml", path = "services/b/cart/build/test-results/test/TEST-com.acme.CartTest.xml"),
            ),
        ))
        val sameName = shaped.tests.filter { it.identity.name == "handlesEmpty" }
        assertEquals(listOf("services/a/cart", "services/b/cart"), sameName.map { it.identity.module })
        assertNotEquals(sameName[0].identity.canonical, sameName[1].identity.canonical)
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
