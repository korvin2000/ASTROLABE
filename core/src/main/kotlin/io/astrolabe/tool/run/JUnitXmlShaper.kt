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
    override val version: String = "1"

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
                evidenceIncomplete = totalsDisagree || parses.any { it.second.problems.isNotEmpty() },
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
        try {
            val reader = factory().createXMLStreamReader(bytes.inputStream())
            var suite: String? = null
            var suitePackage: String? = null
            var caseName: String? = null
            var caseClass: String? = null
            var caseTime: Long? = null
            var caseOutcome = TestOutcome.Passed
            var caseMessage: String? = null
            var pendingText: StringBuilder? = null
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "testsuite" -> {
                            suite = reader.attr("name")
                            suitePackage = reader.attr("package")
                            reader.attr("tests")?.toIntOrNull()?.let { declared = (declared ?: 0) + it }
                        }
                        "testcase" -> {
                            caseName = reader.attr("name")
                            caseClass = reader.attr("classname")
                            caseTime = reader.attr("time")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                            caseOutcome = TestOutcome.Passed
                            caseMessage = null
                        }
                        "failure", "error", "skipped" -> if (caseName != null) {
                            caseOutcome = when (reader.localName) {
                                "failure" -> TestOutcome.Failed
                                "error" -> TestOutcome.Error
                                else -> TestOutcome.Skipped
                            }
                            caseMessage = reader.attr("message") ?: reader.attr("type")
                            if (caseMessage == null) pendingText = StringBuilder()
                        }
                        else -> Unit
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        pendingText?.append(reader.text)

                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "failure", "error", "skipped" -> {
                            if (caseMessage == null) {
                                caseMessage = pendingText?.toString()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                            }
                            pendingText = null
                        }
                        "testcase" -> {
                            val raw = caseName
                            if (raw == null) {
                                problems += "a testcase without a name was skipped"
                            } else {
                                val (name, param) = TestIdentity.splitParameterization(raw)
                                tests += TestResult(
                                    identity = TestIdentity(
                                        check = checkId,
                                        module = module ?: suitePackage,
                                        file = caseClass ?: suite,
                                        suite = suite?.takeIf { it != caseClass },
                                        name = name,
                                        parameterization = param,
                                    ),
                                    outcome = caseOutcome,
                                    message = caseMessage,
                                    durationMillis = caseTime,
                                )
                            }
                            caseName = null
                            caseClass = null
                            caseMessage = null
                            caseTime = null
                        }
                        "testsuite" -> {
                            suite = null
                            suitePackage = null
                        }
                        else -> Unit
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
        return XmlParse(tests, declared, problems)
    }

    private fun XMLStreamReader.attr(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name }?.let { getAttributeValue(it) }

    private fun factory(): XMLInputFactory = XMLInputFactory.newInstance().apply {
        runCatching { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
        runCatching { setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
        runCatching { setProperty(XMLInputFactory.IS_COALESCING, true) }
    }
}
