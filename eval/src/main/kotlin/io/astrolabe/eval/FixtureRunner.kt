package io.astrolabe.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess
import org.w3c.dom.Element

/** JUnit's aborted (failed assumption) is reported as skipped, as the build's JUnit XML reports it. */
public enum class FixtureStatus { Passed, Failed, Skipped }

/** One fixture test; [fixtures] are the canonical §19.3 (`FX-07`) and §15.4 (`AX-03`) ids its name declares. */
@ConsistentCopyVisibility
public data class FixtureResult internal constructor(
    val className: String,
    val name: String,
    val fixtures: List<String>,
    val status: FixtureStatus,
    val message: String?,
)

/**
 * The seven §19.4 invariants that must be zero in every configuration, each with the §19.3 fixtures that exercise
 * it (D-220). A fixture failure is one observed violation; an invariant none of whose fixtures ran is unmeasured.
 */
public enum class Invariant(public val wire: String, fixtures: List<Int>) {
    UnseenAnchoredEdits("ordinary anchored edits to unseen content", listOf(1, 2, 50, 51)),
    DestructiveMissteps("destructive missteps", listOf(3, 4, 5, 6, 40)),
    SilentAcceptanceChanges("silent acceptance changes", listOf(14, 15, 34, 53)),
    StaleBodiesServed("stale bodies served as current", listOf(16, 17, 35, 41, 44)),
    UnauthorizedStagePublications("unauthorized-stage publications", listOf(26, 38, 39, 49, 52)),
    LateSupersededMerges("late superseded results merged", listOf(26, 27, 28)),
    FalseGreenIncidents("false-green incidents", listOf(8, 9, 13, 18, 43, 54, 58)),
    ;

    public val fixtures: List<String> = immutable(fixtures.map { "FX-" + it.toString().padStart(2, '0') })
}

/** [violations] is null when no fixture of the invariant ran: unmeasured, never zero. */
public data class InvariantMetric(val invariant: Invariant, val tests: Int, val violations: Int?)

/** Classes and packages to run; only tests whose display name carries a fixture id are selected. */
public class FixtureSelection @JvmOverloads constructor(
    classes: List<String>,
    packages: List<String> = emptyList(),
    configurationParameters: Map<String, String> = emptyMap(),
) {
    public val classes: List<String> = immutable(classes)
    public val packages: List<String> = immutable(packages)
    public val configurationParameters: Map<String, String> = frozenMap(configurationParameters.toSortedMap())
    init { require(this.classes.isNotEmpty() || this.packages.isNotEmpty()) { "nothing selected" } }
}

/** A machine-readable fixture run (P6.1.1): results, counts, invariant metrics and the verdicts derived from them. */
public class FixtureReport internal constructor(public val configuration: String, results: List<FixtureResult>) {
    public val results: List<FixtureResult> = immutable(results.sortedWith(compareBy(FixtureResult::className, FixtureResult::name)))
    public val passed: Int = this.results.count { it.status == FixtureStatus.Passed }
    public val failed: Int = this.results.count { it.status == FixtureStatus.Failed }
    public val skipped: Int = this.results.count { it.status == FixtureStatus.Skipped }
    public val metrics: List<InvariantMetric> = immutable(Invariant.entries.map { invariant ->
        val ran = this.results.filter { r -> r.status != FixtureStatus.Skipped && r.fixtures.any { it in invariant.fixtures } }
        InvariantMetric(invariant, ran.size, if (ran.isEmpty()) null else ran.count { it.status == FixtureStatus.Failed })
    })

    /** Nothing ran is not green. */
    public val green: Boolean get() = passed > 0 && failed == 0

    /** Every invariant measured and zero; an unmeasured invariant is not a zero. */
    public val invariantsZero: Boolean get() = metrics.all { it.violations == 0 }

    /** Differences from JUnit's own results for the same fixture tests; empty when the run reproduces them. */
    public fun discrepancies(junit: List<FixtureResult>): List<String> {
        val ours = results.associateBy { it.className to it.name }
        val theirs = junit.filter { it.fixtures.isNotEmpty() }.associateBy { it.className to it.name }
        return buildList {
            for ((key, result) in ours) {
                val other = theirs[key]
                when {
                    other == null -> add("${key.first} > ${key.second}: absent from the JUnit results")
                    other.status != result.status -> add("${key.first} > ${key.second}: runner ${result.status}, JUnit ${other.status}")
                }
            }
            (theirs.keys - ours.keys).forEach { add("${it.first} > ${it.second}: not run by the runner") }
        }.sorted()
    }

    public fun json(): String = JSON.encodeToString(buildJsonObject {
        put("kind", "astrolabe.fixture-report/1")
        put("configuration", configuration)
        putJsonObject("counts") {
            put("tests", results.size); put("passed", passed); put("failed", failed); put("skipped", skipped)
        }
        put("green", green)
        put("invariantsZero", invariantsZero)
        putJsonArray("invariants") {
            metrics.forEach { m -> add(buildJsonObject {
                put("invariant", m.invariant.wire); put("tests", m.tests)
                put("violations", m.violations?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        }
        putJsonArray("results") {
            results.forEach { r -> add(buildJsonObject {
                put("class", r.className); put("name", r.name); put("status", r.status.name.lowercase())
                putJsonArray("fixtures") { r.fixtures.forEach { add(JsonPrimitive(it)) } }
                put("message", r.message?.let(::JsonPrimitive) ?: JsonNull)
            }) }
        }
    })

    private companion object {
        val JSON = Json { prettyPrint = true }
    }
}

/**
 * The FX/AX harness suites as a runnable program (P6.1.1): the fixture tests (which inject their own faults) run
 * through the JUnit Platform outside the build, and the result is a [FixtureReport] with the §19.4 invariant metrics.
 */
public object FixtureRunner {
    private val FIXTURE_ID = Regex("""\b(FX|AX)-(\d{1,2})\b""")

    /** The canonical fixture ids a test name declares (`FX-7` and `FX-07` are one id). */
    @JvmStatic
    public fun fixtureIds(name: String): List<String> =
        FIXTURE_ID.findAll(name).map { it.groupValues[1] + "-" + it.groupValues[2].toInt().toString().padStart(2, '0') }.distinct().toList()

    @JvmStatic
    @JvmOverloads
    public fun run(selection: FixtureSelection, configuration: String = "as-written"): FixtureReport {
        val request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selection.classes.map(DiscoverySelectors::selectClass) + selection.packages.map(DiscoverySelectors::selectPackage))
            .filters(PostDiscoveryFilter { descriptor ->
                if (!descriptor.isTest || fixtureIds(descriptor.displayName).isNotEmpty()) FilterResult.included("fixture")
                else FilterResult.excluded("no fixture id")
            })
            .configurationParameters(selection.configurationParameters)
            .build()
        val listener = Collector()
        LauncherFactory.create().execute(request, listener)
        return FixtureReport(configuration, listener.results.values.toList())
    }

    /** The build's JUnit XML reports (`TEST-*.xml`) as results, for [FixtureReport.discrepancies]. */
    @JvmStatic
    public fun junitResults(directory: Path): List<FixtureResult> {
        if (!Files.isDirectory(directory)) return emptyList()
        val files = Files.list(directory).use { s -> s.filter { it.fileName.toString().let { n -> n.startsWith("TEST-") && n.endsWith(".xml") } }.sorted().toList() }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        return files.flatMap { file ->
            val cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase")
            (0 until cases.length).map { cases.item(it) as Element }.map { case ->
                val children = (0 until case.childNodes.length).mapNotNull { case.childNodes.item(it) as? Element }.map { it.tagName }
                val status = when {
                    "failure" in children || "error" in children -> FixtureStatus.Failed
                    "skipped" in children -> FixtureStatus.Skipped
                    else -> FixtureStatus.Passed
                }
                val name = case.getAttribute("name")
                FixtureResult(case.getAttribute("classname"), name, fixtureIds(name), status, null)
            }
        }
    }

    /** `--class C`, `--package P` (repeatable), `--report FILE`, `--junit-xml DIR`, `--configuration LABEL`, `--param K=V`. */
    @JvmStatic
    public fun main(args: Array<String>) {
        exitProcess(execute(args.toList(), System.out))
    }

    /** 0 = green, every invariant measured and zero, JUnit reproduced; 1 = any of those not met; 2 = usage error. */
    internal fun execute(args: List<String>, out: PrintStream): Int {
        val classes = mutableListOf<String>()
        val packages = mutableListOf<String>()
        val params = mutableMapOf<String, String>()
        var report: Path? = null
        var junit: Path? = null
        var configuration = "as-written"
        var i = 0
        while (i < args.size) {
            val value = args.getOrNull(i + 1) ?: run { out.println("usage: missing value for ${args[i]}"); return 2 }
            when (args[i]) {
                "--class" -> classes += value
                "--package" -> packages += value
                "--report" -> report = Paths.get(value)
                "--junit-xml" -> junit = Paths.get(value)
                "--configuration" -> configuration = value
                "--param" -> value.split('=', limit = 2).let { if (it.size == 2) params[it[0]] = it[1] else { out.println("usage: --param K=V"); return 2 } }
                else -> { out.println("usage: unknown option ${args[i]}"); return 2 }
            }
            i += 2
        }
        if (classes.isEmpty() && packages.isEmpty()) { out.println("usage: select at least one --class or --package"); return 2 }
        val result = run(FixtureSelection(classes, packages, params), configuration)
        val discrepancies = junit?.let { dir -> if (Files.isDirectory(dir)) result.discrepancies(junitResults(dir)) else null }
        report?.let { file ->
            file.toAbsolutePath().parent?.let(Files::createDirectories)
            Files.writeString(file, result.json())
        }
        out.println("fixtures: ${result.results.size} tests, ${result.passed} passed, ${result.failed} failed, ${result.skipped} skipped")
        result.metrics.forEach { out.println("invariant ${it.invariant.wire}: ${it.violations ?: "unmeasured"} (${it.tests} tests)") }
        out.println(when (discrepancies) {
            null -> "junit: not compared"
            else -> "junit: ${discrepancies.size} discrepancies"
        })
        discrepancies?.forEach { out.println("  $it") }
        return if (result.green && result.invariantsZero && discrepancies.isNullOrEmpty()) 0 else 1
    }

    private class Collector : TestExecutionListener {
        val results = LinkedHashMap<String, FixtureResult>()
        private var plan: TestPlan? = null

        override fun testPlanExecutionStarted(testPlan: TestPlan) { plan = testPlan }

        override fun executionSkipped(testIdentifier: TestIdentifier, reason: String) =
            tests(testIdentifier).forEach { record(it, FixtureStatus.Skipped, reason) }

        override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
            val message = testExecutionResult.throwable.map { it.toString() }.orElse(null)
            val status = when (testExecutionResult.status) {
                TestExecutionResult.Status.SUCCESSFUL -> FixtureStatus.Passed
                TestExecutionResult.Status.ABORTED -> FixtureStatus.Skipped
                TestExecutionResult.Status.FAILED -> FixtureStatus.Failed
            }
            if (testIdentifier.isTest) record(testIdentifier, status, message)
            // A failed container (e.g. a class-level setup) never started its tests: each of them failed.
            else if (status == FixtureStatus.Failed) tests(testIdentifier).forEach { if (it.uniqueId !in results) record(it, status, message) }
        }

        private fun tests(identifier: TestIdentifier): List<TestIdentifier> =
            if (identifier.isTest) listOf(identifier) else plan?.getDescendants(identifier)?.filter { it.isTest }.orEmpty()

        // PostDiscoveryFilter only prunes the static discovery tree; a @ParameterizedTest/@TestFactory method
        // is a container there (always included) and its invocations are generated at execution time, past
        // the filter's reach. Drop them here so an unrelated dynamic test can never taint counts or green (D-220).
        private fun record(identifier: TestIdentifier, status: FixtureStatus, message: String?) {
            val ids = fixtureIds(identifier.displayName)
            if (ids.isEmpty()) return
            val className = (identifier.source.orElse(null) as? MethodSource)?.className ?: identifier.uniqueId
            results[identifier.uniqueId] = FixtureResult(className, identifier.displayName, ids, status, message)
        }
    }
}
