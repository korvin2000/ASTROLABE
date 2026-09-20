package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * jest and vitest (D-09, JS/TS). Counts come from the `Tests:` / `Tests` summary, identities from the
 * `✓`/`✕` lines, jest's `● suite › name` blocks and vitest's `FAIL file > suite > name` lines, or from a
 * fresh `--json` report when one was captured (D-50).
 */
public class JestShaper : Shaper {
    override val id: String = "jest"
    override val version: String = "1"

    override fun applies(capture: RunCapture): Boolean {
        val tokens = Invocations.runnerTokens(capture)
        if (tokens.any { Invocations.basename(it).lowercase() in RUNNERS }) return true
        if (capture.evidenceReports.any { it.kind == ReportKind.JestJson }) return true
        val text = capture.text()
        return text.contains(JEST_SUMMARY) || text.contains(VITEST_FILES)
    }

    override fun shape(capture: RunCapture, budget: ShapeBudget): Shaped {
        val limitations = ArrayList<String>()
        val wrapper = Invocations.detectWrapper(capture)
        capture.rejectedReports.forEach { limitations += "report '${it.path}' ignored: ${it.rejectionReason} (D-50)" }

        val report = capture.evidenceReports.firstOrNull { it.kind == ReportKind.JestJson }
        val fromReport = report?.let { JestJson.parse(it.content ?: ByteArray(0), capture.checkId) }
        fromReport?.problems?.forEach { limitations += "report '${report.path}': $it" }

        val terminal = JestTerminal.parse(capture.text(), capture.checkId)
        val useReport = fromReport != null && fromReport.tests.isNotEmpty()
        val tests = if (useReport) fromReport.tests else terminal.tests
        val counts: Counts? = when {
            useReport -> TestResults.counts(fromReport.tests, discovered = fromReport.declaredTests ?: fromReport.tests.size)
            terminal.counts != null -> terminal.counts
            else -> {
                limitations += "no jest/vitest summary line was found in the captured output (§8.3)"
                null
            }
        }
        if (!useReport && counts != null) {
            val parsedFailing = tests.count { it.failing }
            val reportedFailing = counts.failed + counts.errors
            if (parsedFailing != reportedFailing) {
                limitations += "the summary reports $reportedFailing failing test(s) but $parsedFailing identity/ies " +
                    "could be parsed: identities are incomplete (D-27)"
            }
        }

        val status = deriveStatus(
            StatusInputs(
                capture = capture,
                counts = counts,
                wrapper = wrapper,
                runnerName = Invocations.runnerName(capture),
                nothingCollected = terminal.noTestsFound,
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
        val RUNNERS = setOf("jest", "vitest")
        const val JEST_SUMMARY = "Test Suites:"
        const val VITEST_FILES = "Test Files "
    }
}

internal data class JestTerminalParse(val counts: Counts?, val tests: List<TestResult>, val noTestsFound: Boolean)

/** The shared jest/vitest terminal shapes; both runners print a file header, check marks and a summary. */
internal object JestTerminal {
    private val SUMMARY_TOKEN = Regex("""(\d+)\s+(failed|passed|skipped|pending|todo|total)""")
    private val VITEST_TOTAL = Regex("""\((\d+)\)\s*$""")
    private val DURATION_SUFFIX = Regex("""\s*\(\s*\d+(\.\d+)?\s*(ms|s)?\s*\)\s*$""")
    private val PASS_MARKS = setOf('✓', '✔')
    private val FAIL_MARKS = setOf('✕', '✗', '×')
    private val SKIP_MARKS = setOf('○', '↓', '—')

    fun parse(text: String, checkId: String?): JestTerminalParse {
        val lines = text.lines()
        var counts: Counts? = null
        var noTestsFound = false
        var currentFile: String? = null
        val passes = ArrayList<TestResult>()
        val failures = LinkedHashMap<String, TestResult>()

        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("No tests found") || line.startsWith("No test files found")) {
                noTestsFound = true
                continue
            }
            if (line.startsWith("Tests:") || line.startsWith("Tests ")) {
                counts = summary(line)
                continue
            }
            if (line.startsWith("FAIL ") || line.startsWith("PASS ")) {
                val body = line.substringAfter(' ').trim()
                // vitest: `FAIL src/x.test.ts > suite > name`; jest: ` FAIL  src/x.test.ts` (a file header).
                if (!body.contains(" > ")) {
                    currentFile = body
                } else {
                    val id = identityOf(body.split(" > "), currentFile, checkId)
                    val outcome = if (line.startsWith("FAIL ")) TestOutcome.Failed else TestOutcome.Passed
                    val result = TestResult(id, outcome, if (outcome == TestOutcome.Failed) nextMessage(lines, index) else null)
                    if (result.failing) failures.merge(id.canonical, result, ::richer) else passes += result
                }
                continue
            }
            if (line.startsWith("●") && !line.contains("Console")) {
                // jest failure block: `● describe › test`, detail on the following lines.
                val body = line.removePrefix("●").trim()
                if (body.isEmpty()) continue
                val id = identityOf(body.split(" › ", " > "), currentFile, checkId)
                failures.merge(id.canonical, TestResult(id, TestOutcome.Failed, nextMessage(lines, index)), ::richer)
                continue
            }
            val mark = line.first()
            if (mark == '❯') {
                // vitest's file marker carries no verdict, only the file the following lines belong to.
                currentFile = fileOrNull(line.drop(1).trim()) ?: currentFile
                continue
            }
            val outcome = when (mark) {
                in PASS_MARKS -> TestOutcome.Passed
                in FAIL_MARKS -> TestOutcome.Failed
                in SKIP_MARKS -> TestOutcome.Skipped
                else -> continue
            }
            val body = DURATION_SUFFIX.replace(line.drop(1).trim(), "").trim()
            if (body.isEmpty()) continue
            val asFile = fileOrNull(body)
            if (asFile != null) {
                currentFile = asFile // vitest prints `✓ src/cart.test.ts (3)` as a file line, not a test
                continue
            }
            // A check mark carries no detail; the message belongs to the `●` / `FAIL … > …` block below it.
            val id = identityOf(body.split(" › ", " > "), currentFile, checkId)
            val result = TestResult(id, outcome, null)
            if (result.failing) failures.merge(id.canonical, result, ::richer) else passes += result
        }
        return JestTerminalParse(counts, passes + failures.values, noTestsFound)
    }

    /** jest and vitest report the same failing test twice (check mark and detail block); merge, never double count. */
    private fun richer(a: TestResult, b: TestResult): TestResult =
        if (a.message.isNullOrBlank() && !b.message.isNullOrBlank()) b else a

    private fun summary(line: String): Counts {
        var passed = 0
        var failed = 0
        var skipped = 0
        var total = 0
        SUMMARY_TOKEN.findAll(line).forEach { t ->
            val n = t.groupValues[1].toIntOrNull() ?: return@forEach
            when (t.groupValues[2]) {
                "passed" -> passed += n
                "failed" -> failed += n
                "skipped", "pending", "todo" -> skipped += n
                "total" -> total += n
            }
        }
        VITEST_TOTAL.find(line)?.let { total = maxOf(total, it.groupValues[1].toIntOrNull() ?: 0) }
        return Counts(passed, failed, 0, skipped, discovered = maxOf(total, passed + failed + skipped))
    }

    private fun fileOrNull(body: String): String? {
        val head = body.substringBefore(' ')
        val looksLikeFile = (head.contains(".test.") || head.contains(".spec.")) && !body.contains(" > ") && !body.contains(" › ")
        return head.takeIf { looksLikeFile }
    }

    private fun identityOf(parts: List<String>, file: String?, checkId: String?): TestIdentity {
        val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
        val first = cleaned.firstOrNull()
        val leadsWithFile = first != null && fileOrNull(first) != null
        val resolvedFile = if (leadsWithFile) first else file
        val rest = if (leadsWithFile) cleaned.drop(1) else cleaned
        val (name, param) = TestIdentity.splitParameterization(rest.lastOrNull() ?: resolvedFile ?: "?")
        return TestIdentity(
            check = checkId,
            file = resolvedFile,
            suite = rest.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(" > "),
            name = name,
            parameterization = param,
        )
    }

    private fun nextMessage(lines: List<String>, index: Int): String? =
        lines.asSequence().drop(index + 1).take(6).map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("at ") && it.first() !in PASS_MARKS && it.first() !in FAIL_MARKS }
}

/** `jest --json` subset: `testResults[].assertionResults[]` with ancestor titles and status. */
internal object JestJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(bytes: ByteArray, checkId: String?): XmlParse {
        if (bytes.isEmpty()) return XmlParse(emptyList(), null, listOf("empty report"))
        val root = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrElse {
            return XmlParse(emptyList(), null, listOf("could not be parsed (malformed JSON)"))
        }
        val out = ArrayList<TestResult>()
        val files = runCatching { root["testResults"]?.jsonArray }.getOrNull().orEmpty()
        for (fileEntry in files) {
            val fileObj = fileEntry as? JsonObject ?: continue
            val file = fileObj["name"]?.jsonPrimitive?.content?.replace('\\', '/')?.substringAfterLast('/')
            val assertions = runCatching { fileObj["assertionResults"]?.jsonArray }.getOrNull().orEmpty()
            for (entry in assertions) {
                val obj = entry as? JsonObject ?: continue
                val title = obj["title"]?.jsonPrimitive?.content ?: continue
                val status = when (obj["status"]?.jsonPrimitive?.content) {
                    "passed" -> TestOutcome.Passed
                    "failed" -> TestOutcome.Failed
                    "pending", "skipped", "todo", "disabled" -> TestOutcome.Skipped
                    else -> continue
                }
                val suite = runCatching { obj["ancestorTitles"]?.jsonArray?.map { it.jsonPrimitive.content } }
                    .getOrNull().orEmpty().takeIf { it.isNotEmpty() }?.joinToString(" > ")
                val message = runCatching { obj["failureMessages"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content }
                    .getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                val duration = obj["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                val (name, param) = TestIdentity.splitParameterization(title)
                out += TestResult(
                    TestIdentity(check = checkId, file = file, suite = suite, name = name, parameterization = param),
                    status,
                    message,
                    duration,
                )
            }
        }
        return XmlParse(out, intField(root, "numTotalTests"), emptyList())
    }

    private fun intField(root: JsonObject, key: String): Int? =
        runCatching { root[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()
}
