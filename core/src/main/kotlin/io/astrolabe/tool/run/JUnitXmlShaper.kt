package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Gradle and Maven evidence through JUnit XML (D-09). Only reports written to a fresh per-invocation
 * destination — or carrying recorded build-cache provenance — are read (D-50); a stale report beside a run
 * that executed nothing yields `inconclusive`, never the report's old green (I-13).
 */
public class JUnitXmlShaper : Shaper {
    override val id: String = "junit-xml"
    override val version: String = "3"

    override fun applies(capture: RunCapture): Boolean {
        if (capture.evidenceReports.any { it.kind == ReportKind.JUnitXml }) return true
        val tokens = Invocations.runnerTokens(capture)
        return tokens.any { Invocations.basename(it).lowercase() in BUILD_TOOLS }
    }

    override fun shape(capture: RunCapture, budget: ShapeBudget): Shaped {
        val limitations = ArrayList<String>()
        val wrapper = Invocations.detectWrapper(capture)
        capture.rejectedReports.forEach {
            limitations += "report '${it.path}' ignored: ${it.rejectionReason} (D-50, I-13)"
        }
        val usable = capture.evidenceReports.filter { it.kind == ReportKind.JUnitXml }
        val parses = usable.map { it to JUnitXml.parse(it.content ?: ByteArray(0), it.moduleOrDerived, capture.checkId) }
        parses.forEach { (artifact, parse) ->
            parse.problems.forEach { limitations += "report '${artifact.path}': $it" }
        }
        val tests = parses.flatMap { it.second.tests }
        val declaredTotal = parses.mapNotNull { it.second.declaredTests }.takeIf { it.isNotEmpty() }?.sum()
        val counts: Counts? = when {
            usable.isEmpty() -> {
                limitations += "no fresh JUnit XML report was captured for this invocation: " +
                    "the console alone does not prove which tests ran (D-50)"
                null
            }
            tests.isEmpty() && (declaredTotal ?: 0) == 0 -> Counts(discovered = 0)
            else -> TestResults.counts(tests, discovered = declaredTotal ?: tests.size)
        }
        val totalsDisagree = counts != null && declaredTotal != null && declaredTotal != tests.size
        if (totalsDisagree) {
            limitations += "report totals ($declaredTotal declared) disagree with ${tests.size} parsed test cases"
        }
        val status = deriveStatus(
            StatusInputs(
                capture = capture,
                counts = counts,
                wrapper = wrapper,
                runnerName = Invocations.runnerName(capture),
                nothingCollected = false,
                evidenceIncomplete = totalsDisagree || parses.any { !it.second.complete },
            ),
            limitations,
        )
        val sections = buildList {
            if (usable.isNotEmpty()) {
                add(ViewSection("reports (${usable.size}):", usable.map { "${it.path} [${it.provenance}]" }))
            }
        }
        val view = buildView(id, capture, status, counts, tests, wrapper, limitations, budget, sections)
        return Shaped(
            status = status,
            counts = counts,
            tests = tests,
            view = view.view,
            viewTruncated = view.viewTruncated,
            captureTruncated = view.captureTruncated,
            recallHint = view.recallHint,
            wrapper = wrapper,
            shaper = id,
            limitations = limitations,
            reportComplete = parses.isNotEmpty() && parses.size == capture.reports.size &&
                parses.all { it.second.complete && it.first.collectionComplete } && !totalsDisagree,
        )
    }

    private companion object {
        val BUILD_TOOLS = setOf("gradle", "gradlew", "mvn", "mvnw", "maven")
    }
}

internal data class XmlParse(
    val tests: List<TestResult>,
    /** Sum of the `tests` attributes across suites; null when no suite declared one. */
    val declaredTests: Int?,
    val problems: List<String>,
    val complete: Boolean = false,
)

/**
 * Minimal JUnit XML reader over the JDK's StAX. Gradle writes one file per class and Maven one per suite,
 * so several parses are combined by the caller; DTDs and external entities are disabled.
 */
internal object JUnitXml {
    fun parse(bytes: ByteArray, module: String?, checkId: String?): XmlParse {
        if (bytes.isEmpty()) return XmlParse(emptyList(), null, listOf("empty report"))
        val tests = ArrayList<TestResult>()
        val problems = ArrayList<String>()
        var declared: Int? = null
        var rootSeen = false
        try {
            val reader = factory().createXMLStreamReader(bytes.inputStream())
            val suites = ArrayList<PendingSuite>()
            var current: PendingCase? = null
            var failure: PendingFailure? = null
            var depth = 0
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        depth++
                        if (!rootSeen) {
                            rootSeen = true
                            if (reader.localName !in setOf("testsuite", "testsuites")) problems += "unsupported report root"
                        }
                        if (failure != null) {
                            current?.contentComplete = false
                            problems += "nested failure markup is unsupported"
                            continue
                        }
                        when (reader.localName) {
                            "testsuite", "testsuites" -> {
                                val rawCount = reader.attr("tests")
                                val total = rawCount?.toIntOrNull()?.takeIf { it >= 0 }
                                if (rawCount != null && total == null) problems += "invalid suite test count"
                                if (suites.none { it.declaredTests != null } && total != null) declared = (declared ?: 0) + total
                                val outcomes = buildMap {
                                    for (field in listOf("failures", "errors", "skipped", "disabled")) {
                                        val raw = reader.attr(field) ?: continue
                                        val count = raw.toIntOrNull()?.takeIf { it >= 0 }
                                        if (count == null) problems += "invalid suite outcome count" else put(field, count)
                                    }
                                }
                                suites += PendingSuite(reader.attr("name"), reader.attr("package"), total, outcomes, tests.size, depth)
                            }
                            "testcase" -> {
                                if (current != null || suites.isEmpty()) problems += "testcase outside a supported suite"
                                current = PendingCase(
                                    reader.attr("name"), reader.attr("classname"),
                                    reader.attr("time")?.toDoubleOrNull()?.let { (it * 1000).toLong() },
                                    suites.lastOrNull()?.name, suites.lastOrNull()?.pkg, depth,
                                )
                                for (field in listOf("status", "result")) {
                                    when (reader.attr(field)) {
                                        null, "run", "passed", "completed" -> Unit
                                        "notrun", "disabled", "skipped", "suppressed" -> current.outcome = TestOutcome.Skipped
                                        else -> problems += "unsupported testcase outcome attribute"
                                    }
                                }
                            }
                            "failure", "error", "skipped" -> {
                                val test = current
                                if (test == null || depth != test.depth + 1) {
                                    problems += "failure or skip outside a supported testcase"
                                } else {
                                    val kind = reader.localName
                                    if (kind == "skipped" && test.outcome in setOf(TestOutcome.Failed, TestOutcome.Error) ||
                                        kind != "skipped" && test.outcome == TestOutcome.Skipped) problems += "testcase contains both skipped and failing outcomes"
                                    test.outcome = when {
                                        kind == "error" || test.outcome == TestOutcome.Error -> TestOutcome.Error
                                        kind == "failure" || test.outcome == TestOutcome.Failed -> TestOutcome.Failed
                                        else -> TestOutcome.Skipped
                                    }
                                    val attributes = (0 until reader.attributeCount).associate {
                                        reader.getAttributeName(it).toString() to reader.getAttributeValue(it)
                                    }
                                    failure = PendingFailure(kind, attributes, depth)
                                }
                            }
                            else -> if ((current?.depth == depth - 1 || current == null && suites.lastOrNull()?.depth == depth - 1) &&
                                reader.localName !in setOf("system-out", "system-err", "properties")) {
                                problems += "unsupported report child element"
                            }
                        }
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        failure?.body?.append(reader.text)

                    XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE -> problems += "unsupported report entity content"

                    XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION -> if (failure != null) {
                        current?.contentComplete = false
                        problems += "unsupported failure markup"
                    }

                    XMLStreamConstants.END_ELEMENT -> {
                        val pending = failure
                        if (pending != null && pending.depth == depth) {
                            val content = FailureContent(pending.kind, pending.attributes, pending.body.toString())
                            if (pending.kind != "skipped") current?.failures?.add(content)
                            if (current?.message == null) current?.message = pending.attributes["message"] ?: pending.attributes["type"]
                                ?: content.body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                            failure = null
                        }
                        val test = current
                        if (reader.localName == "testcase" && test?.depth == depth) {
                            if (test.name.isNullOrBlank()) problems += "a testcase without a name was skipped"
                            else {
                                val (name, param) = TestIdentity.splitParameterization(test.name)
                                tests += TestResult(
                                    TestIdentity(checkId, module ?: test.pkg, test.className ?: test.suite, test.suite?.takeIf { it != test.className }, name, param),
                                    test.outcome, test.message, test.time,
                                    detail = test.failures.joinToString("\n") { it.body }.ifEmpty { null },
                                    failureContent = test.failures.toList(),
                                    failureContentComplete = test.contentComplete,
                                )
                            }
                            current = null
                        }
                        if (reader.localName in setOf("testsuite", "testsuites") && suites.lastOrNull()?.depth == depth) {
                            val suite = suites.removeAt(suites.lastIndex)
                            if (suite.declaredTests != null && suite.declaredTests != tests.size - suite.start) {
                                problems += "suite totals disagree with parsed test cases"
                            }
                            val cases = tests.subList(suite.start, tests.size)
                            for ((field, expected) in suite.declaredOutcomes) {
                                val actual = when (field) {
                                    "failures" -> cases.count { it.failureContent.any { failure -> failure.kind == "failure" } }
                                    "errors" -> cases.count { it.failureContent.any { failure -> failure.kind == "error" } }
                                    else -> cases.count { it.outcome == TestOutcome.Skipped }
                                }
                                if (actual != expected) problems += "suite outcome totals disagree with parsed test cases"
                            }
                        }
                        depth--
                    }

                    else -> Unit
                }
            }
            reader.close()
        } catch (e: XMLStreamException) {
            // §8.4: a parse error is never mapped to success — it becomes a limitation and, without counts,
            // an inconclusive status.
            problems += "could not be parsed (${e.message?.lineSequence()?.firstOrNull()?.trim() ?: "malformed XML"})"
            return XmlParse(tests, declared, problems)
        }
        return XmlParse(tests, declared, problems, complete = rootSeen && problems.isEmpty())
    }

    private data class PendingSuite(val name: String?, val pkg: String?, val declaredTests: Int?, val declaredOutcomes: Map<String, Int>, val start: Int, val depth: Int)

    private data class PendingCase(
        val name: String?, val className: String?, val time: Long?, val suite: String?, val pkg: String?, val depth: Int,
        var outcome: TestOutcome = TestOutcome.Passed,
        var message: String? = null,
        val failures: MutableList<FailureContent> = ArrayList(),
        var contentComplete: Boolean = true,
    )

    private data class PendingFailure(val kind: String, val attributes: Map<String, String>, val depth: Int, val body: StringBuilder = StringBuilder())

    private fun XMLStreamReader.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name }?.let { getAttributeValue(it) }

    private fun factory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        runCatching { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
        runCatching { setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
        runCatching { setProperty(XMLInputFactory.IS_COALESCING, true) }
    }
}
