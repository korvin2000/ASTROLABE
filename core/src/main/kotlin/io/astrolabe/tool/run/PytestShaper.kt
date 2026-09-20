package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    override val version: String = "1"

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

        val report = capture.evidenceReports.firstOrNull { it.kind == ReportKind.PytestJson || it.kind == ReportKind.JUnitXml }
        val fromReport = report?.let { artifact ->
            when (artifact.kind) {
                ReportKind.PytestJson -> PytestJson.parse(artifact.content ?: ByteArray(0), capture.checkId)
                else -> JUnitXml.parse(artifact.content ?: ByteArray(0), artifact.moduleOrDerived, capture.checkId)
            }
        }
        fromReport?.problems?.forEach { limitations += "report '${report.path}': $it" }

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
                evidenceIncomplete = fromReport?.problems?.isNotEmpty() == true,
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
                    "passed", "xpassed" -> passed += n
                    "failed" -> failed += n
                    "error", "errors" -> errors += n
                    // An expected failure verified no new behaviour; it is not a pass and not a red.
                    "skipped", "xfailed" -> skipped += n
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
                    "XFAIL" -> TestOutcome.Skipped
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
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(bytes: ByteArray, checkId: String?): XmlParse {
        if (bytes.isEmpty()) return XmlParse(emptyList(), null, listOf("empty report"))
        val root = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrElse {
            return XmlParse(emptyList(), null, listOf("could not be parsed (malformed JSON)"))
        }
        val results = ArrayList<TestResult>()
        val entries = runCatching { root["tests"]?.jsonArray }.getOrNull().orEmpty()
        for (entry in entries) {
            val obj = entry as? JsonObject ?: continue
            val nodeId = obj["nodeid"]?.jsonPrimitive?.contentOrNullSafe() ?: continue
            val outcome = when (obj["outcome"]?.jsonPrimitive?.contentOrNullSafe()) {
                "passed", "xpassed" -> TestOutcome.Passed
                "failed" -> TestOutcome.Failed
                "error" -> TestOutcome.Error
                "skipped", "xfailed" -> TestOutcome.Skipped
                else -> continue
            }
            val message = obj["call"]?.let { it as? JsonObject }?.get("longrepr")?.jsonPrimitive?.contentOrNullSafe()
                ?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()
            val duration = obj["duration"]?.jsonPrimitive?.contentOrNullSafe()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            results += TestResult(PytestTerminal.identityOf(nodeId, checkId), outcome, message, duration)
        }
        val total = runCatching { root["summary"]?.jsonObject?.get("total")?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() }.getOrNull()
        return XmlParse(results, total, emptyList())
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = content.takeIf { it.isNotEmpty() && it != "null" }
}
