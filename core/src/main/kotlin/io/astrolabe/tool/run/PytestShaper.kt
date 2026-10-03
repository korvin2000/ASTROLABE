package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * pytest — the end-to-end runner slice (D-09). Counts and identities come from the terminal summary, or from
 * a `--junitxml` / `pytest-json-report` artifact when one was written fresh for this invocation (D-50).
 * Exit 5 ("no tests ran") is `inconclusive`, never `passed` (FX-09).
 */
public class PytestShaper : Shaper {
    override val id: String = "pytest"
    override val version: String = "2"

    override fun applies(capture: RunCapture): Boolean {
        val tokens = Invocations.runnerTokens(capture)
        if (tokens.any { Invocations.basename(it).lowercase().removeSuffix("3") == "pytest" }) return true
        if (capture.evidenceReports.any { it.kind == ReportKind.PytestJson }) return true
        return capture.text().contains(SESSION_BANNER)
    }

    override fun shape(capture: RunCapture, budget: ShapeBudget): Shaped {
        val limitations = ArrayList<String>()
        val wrapper = Invocations.detectWrapper(capture)
        val terminal = PytestTerminal.parse(capture.text(), capture.checkId)
        capture.rejectedReports.forEach {
            limitations += "report '${it.path}' ignored: ${it.rejectionReason} (D-50)"
        }

        val reports = capture.evidenceReports.filter { it.kind == ReportKind.PytestJson || it.kind == ReportKind.JUnitXml }
        val parses = reports.map { artifact ->
            val parsed = when (artifact.kind) {
                ReportKind.PytestJson -> PytestJson.parse(artifact.content ?: ByteArray(0), capture.checkId)
                else -> JUnitXml.parse(artifact.content ?: ByteArray(0), artifact.moduleOrDerived, capture.checkId)
            }
            parsed.problems.forEach { limitations += "report '${artifact.path}': $it" }
            parsed
        }
        val fromReport = parses.takeIf { it.isNotEmpty() }?.let { all ->
            XmlParse(all.flatMap { it.tests }, all.mapNotNull { it.declaredTests }.takeIf { it.size == all.size }?.sum(), all.flatMap { it.problems }, all.all { it.complete })
        }

        val useReport = fromReport != null && fromReport.tests.isNotEmpty()
        val tests = if (useReport) fromReport.tests else terminal.tests
        val counts: Counts? = when {
            useReport -> TestResults.counts(fromReport.tests, discovered = fromReport.declaredTests ?: fromReport.tests.size)
            terminal.counts != null -> terminal.counts
            else -> {
                limitations += "no pytest summary line was found in the captured output (§8.3)"
                null
            }
        }
        if (useReport && terminal.counts != null && terminal.counts.executed != counts!!.executed) {
            limitations += "the terminal summary (${terminal.counts.executed} executed) disagrees with the " +
                "report (${counts.executed} executed): the fresh report is authoritative (D-50)"
        }

        val status = deriveStatus(
            StatusInputs(
                capture = capture,
                counts = counts,
                wrapper = wrapper,
                runnerName = Invocations.runnerName(capture) ?: "pytest",
                nothingCollected = terminal.noTestsRan && !useReport,
                evidenceIncomplete = fromReport?.let { !it.complete } == true,
                infraExitCodes = INFRA_EXITS,
                inconclusiveExitCodes = INCONCLUSIVE_EXITS,
            ),
            limitations,
        )
        val view = buildView(id, capture, status, counts, tests, wrapper, limitations, budget)
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
            reportComplete = fromReport?.complete == true && reports.size == capture.reports.size && reports.all { it.collectionComplete },
        )
    }

    private companion object {
        const val SESSION_BANNER = "test session starts"

        /** 3 = internal error, 4 = command-line usage error: infrastructure, not a test verdict. */
        val INFRA_EXITS = setOf(3, 4)

        /** 5 = no tests were collected (FX-09). */
        val INCONCLUSIVE_EXITS = setOf(5)
    }
}

internal data class PytestTerminalParse(
    val counts: Counts?,
    val tests: List<TestResult>,
    val noTestsRan: Boolean,
)

/** The pytest terminal reporter: the trailing summary line plus the `short test summary info` section. */
internal object PytestTerminal {
    private val SUMMARY_TOKEN = Regex("""(\d+)\s+(passed|failed|errors?|skipped|xfailed|xpassed|deselected|warnings?)""")
    private val DURATION = Regex("""\bin\s+[\d.]+\s*s(econds)?\b""")
    private val COLLECTED = Regex("""^collected\s+(\d+)\s+items?""")
    private val VERB = Regex("""^(FAILED|ERROR|PASSED|XFAIL|XPASS)\s+(.+)$""")

    fun parse(text: String, checkId: String?): PytestTerminalParse {
        val lines = text.lines()
        var counts: Counts? = null
        var noTestsRan = false
        var collected: Int? = null
        for (line in lines) {
            val bare = line.trim().trim('=').trim()
            COLLECTED.find(bare)?.let { collected = it.groupValues[1].toIntOrNull() }
            if (bare.startsWith("no tests ran")) {
                noTestsRan = true
                counts = Counts(discovered = collected ?: 0)
                continue
            }
            if (!DURATION.containsMatchIn(bare)) continue
            val tokens = SUMMARY_TOKEN.findAll(bare).toList()
            if (tokens.isEmpty()) continue
            var passed = 0
            var failed = 0
            var errors = 0
            var skipped = 0
            for (t in tokens) {
                val n = t.groupValues[1].toIntOrNull() ?: continue
                when (t.groupValues[2]) {
                    "passed" -> passed += n
                    "failed" -> failed += n
                    "error", "errors" -> errors += n
                    // An expected failure verified no new behaviour; it is not a pass and not a red.
                    "skipped", "xfailed", "xpassed" -> skipped += n
                    else -> Unit // deselected and warnings were never executed
                }
            }
            val executed = passed + failed + errors + skipped
            counts = Counts(passed, failed, errors, skipped, discovered = maxOf(collected ?: 0, executed))
        }

        val tests = ArrayList<TestResult>()
        var inSummary = false
        for (line in lines) {
            val bare = line.trim().trim('=').trim()
            if (bare.startsWith("short test summary info")) {
                inSummary = true
                continue
            }
            if (!inSummary) continue
            if (line.startsWith("=") && bare.isNotEmpty() && VERB.find(bare) == null) break
            val match = VERB.find(line.trim()) ?: continue
            val verb = match.groupValues[1]
            val rest = match.groupValues[2]
            val dash = rest.indexOf(" - ")
            val nodeId = (if (dash >= 0) rest.take(dash) else rest).trim()
            val message = if (dash >= 0) rest.substring(dash + 3).trim() else null
            if (nodeId.isEmpty()) continue
            tests += TestResult(
                identity = identityOf(nodeId, checkId),
                outcome = when (verb) {
                    "FAILED" -> TestOutcome.Failed
                    "ERROR" -> TestOutcome.Error
                    "XFAIL", "XPASS" -> TestOutcome.Skipped
                    else -> TestOutcome.Passed
                },
                message = message,
            )
        }
        return PytestTerminalParse(counts, tests, noTestsRan)
    }

    /** `tests/test_cart.py::TestDiscount::test_tier[3]` → file / suite / name / parameterization (D-27). */
    fun identityOf(nodeId: String, checkId: String?): TestIdentity {
        val parts = nodeId.split("::")
        val file = parts.first().takeIf { it.isNotBlank() }
        if (parts.size == 1) {
            // A module-level collection error has no test name; naming it keeps the identity honest.
            return TestIdentity(check = checkId, file = file, name = COLLECTION)
        }
        val (name, param) = TestIdentity.splitParameterization(parts.last())
        val suite = parts.drop(1).dropLast(1).takeIf { it.isNotEmpty() }?.joinToString("::")
        return TestIdentity(check = checkId, file = file, suite = suite, name = name, parameterization = param)
    }

    const val COLLECTION: String = "(collection)"
}

/** `pytest --json-report` subset: `summary` plus a `tests` array of `nodeid`/`outcome`. */
internal object PytestJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray, checkId: String?): XmlParse = try {
        parseReport(bytes, checkId)
    } catch (_: IllegalArgumentException) {
        XmlParse(emptyList(), null, listOf("invalid or incomplete report"))
    } catch (_: IllegalStateException) {
        XmlParse(emptyList(), null, listOf("invalid or incomplete report"))
    }

    private fun parseReport(bytes: ByteArray, checkId: String?): XmlParse {
        if (bytes.isEmpty()) return XmlParse(emptyList(), null, listOf("empty report"))
        val text = bytes.toString(Charsets.UTF_8)
        val root = json.parseToJsonElement(text).jsonObject
        val uniqueKeys = uniqueJsonKeys(text)
        val problems = ArrayList<String>()
        if (!uniqueKeys) problems += "duplicate JSON keys make failure content ambiguous"
        root["collectors"]?.jsonArray?.forEach { collector ->
            if (collector.jsonObject["outcome"]?.jsonPrimitive?.content !in setOf("passed", "skipped")) {
                problems += "collection errors leave unidentified failures"
            }
        }
        val results = ArrayList<TestResult>()
        val entries = requireNotNull(root["tests"]).jsonArray
        for (entry in entries) {
            val obj = entry.jsonObject
            val node = requireNotNull(obj["nodeid"]?.jsonPrimitive)
            require(node.isString)
            val nodeId = requireNotNull(node.contentOrNullSafe())
            val outcomeField = requireNotNull(obj["outcome"]?.jsonPrimitive)
            require(outcomeField.isString)
            var outcome = when (outcomeField.contentOrNullSafe()) {
                "passed" -> TestOutcome.Passed
                "failed" -> TestOutcome.Failed
                "error" -> TestOutcome.Error
                "skipped", "xfailed", "xpassed", "deselected" -> TestOutcome.Skipped
                else -> error("unknown test outcome")
            }
            if (outcome == TestOutcome.Passed && ("wasxfail" in obj || listOf("setup", "call", "teardown").any {
                    obj[it]?.jsonObject?.containsKey("wasxfail") == true
                })) outcome = TestOutcome.Skipped
            val failures = ArrayList<FailureContent>()
            var contentComplete = uniqueKeys
            var failedPhase = false
            var errorPhase = false
            for (phase in listOf("setup", "call", "teardown")) {
                val stage = obj[phase]?.jsonObject ?: continue
                val longrepr = stage["longrepr"]?.takeUnless { it == JsonNull }
                val phaseOutcome = stage["outcome"]?.jsonPrimitive?.content
                if (phaseOutcome !in setOf(null, "passed", "failed", "error", "skipped", "xfailed", "xpassed")) problems += "unsupported phase outcome"
                failedPhase = failedPhase || phaseOutcome == "failed" || phaseOutcome == "error"
                errorPhase = errorPhase || phaseOutcome == "error"
                if (phaseOutcome !in setOf("failed", "error") && longrepr == null) continue
                if (longrepr == null) contentComplete = false
                val body = if (longrepr is JsonPrimitive && longrepr.isString) longrepr.content else longrepr?.toString().orEmpty()
                val attributes = stage.filterKeys { it in setOf("crash", "traceback") }.mapValues { it.value.toString() } +
                    ("longreprFormat" to if (longrepr is JsonPrimitive && longrepr.isString) "text" else "json")
                failures += FailureContent(phase, attributes, body)
            }
            val failing = outcome == TestOutcome.Failed || outcome == TestOutcome.Error
            if (failing && failures.isEmpty()) contentComplete = false
            if (outcome == TestOutcome.Passed && failures.isNotEmpty()) problems += "test outcome disagrees with a failed phase"
            if (failedPhase && !failing) problems += "test outcome disagrees with a failed phase"
            val observedOutcome = if (failedPhase && !failing) {
                if (errorPhase) TestOutcome.Error else TestOutcome.Failed
            } else outcome
            if (failing && !contentComplete) problems += "failure content for a test is incomplete"
            val message = failures.firstOrNull()?.body?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()
            val duration = obj["duration"]?.jsonPrimitive?.contentOrNullSafe()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            results += TestResult(PytestTerminal.identityOf(nodeId, checkId), observedOutcome, message, duration,
                failureContent = failures, failureContentComplete = contentComplete)
        }
        val summary = root["summary"]?.jsonObject
        val total = summary?.get("total")?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull()
        if (total == null || total != results.size) problems += "report totals disagree with parsed test cases"
        val counts = TestResults.counts(results)
        for ((fields, actual) in listOf(
            listOf("passed") to counts.passed,
            listOf("failed") to counts.failed,
            listOf("error", "errors") to counts.errors,
            listOf("skipped", "xfailed", "xpassed", "deselected") to counts.skipped,
        )) {
            if (summary != null && fields.any { it in summary }) {
                val expected = fields.sumOf { field ->
                    if (field in summary) summary[field]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: -1 else 0
                }
                if (expected != actual) problems += "report outcome totals disagree with parsed test cases"
            }
        }
        return XmlParse(results, total, problems, complete = problems.isEmpty())
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = content.takeIf { it.isNotEmpty() && it != "null" }
}
