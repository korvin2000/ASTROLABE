package io.astrolabe.eval

import io.astrolabe.eval.samples.RunnerSampleFixtures
import io.astrolabe.eval.samples.RunnerSampleParameterized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureRunnerTest {
    private val sample = RunnerSampleFixtures::class.java.name
    private val sampleClass = FixtureSelection(listOf(sample))

    @Test
    fun `the runner reproduces the JUnit results of core's adapter fixture suite`() {
        val suite = Class.forName("io.astrolabe.fixtures.FakeAdapterTest")
        // Independent oracle: the suite's declared JUnit tests whose names carry a fixture id.
        val declared = suite.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }
            .map { it.name + "()" }.filter { FixtureRunner.fixtureIds(it).isNotEmpty() }.sorted()
        val report = FixtureRunner.run(FixtureSelection(listOf(suite.name)))
        assertEquals(declared, report.results.map { it.name }.sorted())
        assertTrue(report.green && report.failed == 0 && report.skipped == 0)
        assertEquals((1..10).map { "AX-" + it.toString().padStart(2, '0') },
            report.results.flatMap { it.fixtures }.filter { it.startsWith("AX") }.distinct().sorted())
        // Adapter fixtures exercise no §19.4 invariant: every metric stays unmeasured, not zero.
        assertTrue(report.metrics.all { it.violations == null && it.tests == 0 })
        assertFalse(report.invariantsZero)
    }

    @Test
    fun `a parameterized test whose invocations name no fixture is never selected, though its container passes discovery`() {
        val report = FixtureRunner.run(FixtureSelection(listOf(RunnerSampleParameterized::class.java.name)), "D-260")
        assertEquals(emptyList(), report.results, "the invocations run past the discovery filter but must not be counted")
        assertEquals(0, report.passed + report.failed + report.skipped)
        assertFalse(report.green, "nothing ran is not green")
    }

    @Test
    fun `a failing fixture is a nonzero invariant metric and an unexercised invariant is unmeasured`() {
        val report = FixtureRunner.run(sampleClass, "sample")
        assertEquals(4, report.results.size, "the test without a fixture id is not selected")
        assertEquals(Triple(2, 1, 1), Triple(report.passed, report.failed, report.skipped))
        val metric = report.metrics.associateBy { it.invariant }
        assertEquals(InvariantMetric(Invariant.UnseenAnchoredEdits, 2, 1), metric[Invariant.UnseenAnchoredEdits])
        assertNull(metric.getValue(Invariant.FalseGreenIncidents).violations, "FX-08 was skipped, so it measured nothing")
        assertEquals(listOf("FX-08"), report.results.single { it.status == FixtureStatus.Skipped }.fixtures)
        assertFalse(report.green); assertFalse(report.invariantsZero)
        assertTrue(report.results.single { it.status == FixtureStatus.Failed }.message!!.contains("unread body"))
    }

    @Test
    fun `the JUnit XML comparison names every discrepancy`(@TempDir dir: Path) {
        val report = FixtureRunner.run(sampleClass)
        fun case(name: String, body: String = "") =
            """<testcase name="$name" classname="$sample" time="0.001">$body</testcase>"""
        val names = report.results.associate { it.fixtures.first() to it.name }
        Files.writeString(dir.resolve("TEST-$sample.xml"), """<?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="$sample" tests="5">
              ${case(names.getValue("FX-02"))}
              ${case(names.getValue("FX-50"), "<failure message=\"x\">x</failure>")}
              ${case(names.getValue("FX-08"), "<skipped/>")}
              ${case(names.getValue("AX-01"))}
              ${case("a plain test without a fixture id()")}
            </testsuite>""".trimIndent())
        assertEquals(emptyList(), report.discrepancies(FixtureRunner.junitResults(dir)))

        Files.writeString(dir.resolve("TEST-$sample.xml"), """<testsuite name="$sample">
              ${case(names.getValue("FX-02"))}
              ${case(names.getValue("FX-50"))}
              ${case(names.getValue("FX-08"), "<skipped/>")}
              ${case("FX-99 sample - only JUnit ran this()")}
            </testsuite>""".trimIndent())
        val found = report.discrepancies(FixtureRunner.junitResults(dir))
        assertEquals(3, found.size, found.toString())
        assertTrue(found.any { "runner Failed, JUnit Passed" in it })
        assertTrue(found.any { "AX-01" in it && "absent from the JUnit results" in it })
        assertTrue(found.any { "FX-99" in it && "not run by the runner" in it })
    }

    @Test
    fun `the program exits nonzero on a red fixture and writes the machine-readable report`(@TempDir dir: Path) {
        val file = dir.resolve("out/report.json")
        val out = ByteArrayOutputStream()
        val code = FixtureRunner.execute(listOf("--class", sample, "--report", file.toString(), "--configuration", "S0"),
            PrintStream(out, true, Charsets.UTF_8))
        assertEquals(1, code)
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("S0", json.getValue("configuration").jsonPrimitive.content)
        assertEquals(1, json.getValue("counts").jsonObject.getValue("failed").jsonPrimitive.int)
        assertEquals(Invariant.entries.size, json.getValue("invariants").jsonArray.size)
        assertTrue("junit: not compared" in out.toString(Charsets.UTF_8))
        assertEquals(2, FixtureRunner.execute(listOf("--bogus", "x"), PrintStream(ByteArrayOutputStream())))
    }
}
