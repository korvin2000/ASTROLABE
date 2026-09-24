package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts

/**
 * The fallback shaper: head + tail with every error-shaped line (§5.4). Counts appear only when a
 * recognisable summary does (`cargo test`, `go test`, `unittest`, `dotnet test`, `mocha`); otherwise the
 * result carries `counts = null` and a "no parser" limitation, because a generic exit code never becomes a
 * count (§8.3) and unparsed evidence is never green (D-50). The end-of-turn checkers' diagnostics parsers
 * are [DiagnosticsParser].
 */
public class GenericShaper : Shaper {
    override val id: String = "generic"
    override val version: String = "1"

    /** The registry's last entry: it accepts every capture. */
    override fun applies(capture: RunCapture): Boolean = true

    override fun shape(capture: RunCapture, budget: ShapeBudget): Shaped {
        val limitations = ArrayList<String>()
        val wrapper = Invocations.detectWrapper(capture)
        val runner = Invocations.runnerName(capture)
        capture.rejectedReports.forEach { limitations += "report '${it.path}' ignored: ${it.rejectionReason} (D-50)" }
        val text = capture.text()
        val summary = GenericSummaries.parse(text, capture.checkId)
        if (summary.family == null) {
            limitations += "no shaped parser for '${runner ?: "this command"}': head+tail with error lines only; " +
                "counts unavailable (§8.3)"
        }
        val status = deriveStatus(
            StatusInputs(
                capture = capture,
                counts = summary.counts,
                wrapper = wrapper,
                runnerName = runner,
                nothingCollected = summary.nothingRan,
                infraExitCodes = emptySet(),
            ),
            limitations,
        )
        val errorLines = errorLines(text)
        val sections = if (errorLines.isEmpty()) {
            emptyList()
        } else {
            listOf(ViewSection("error lines (${errorLines.size}):", errorLines.take(MAX_ERROR_LINES)))
        }
        if (errorLines.size > MAX_ERROR_LINES) {
            limitations += "${errorLines.size - MAX_ERROR_LINES} further error-shaped lines are in the stored log only"
        }
        val shaperId = summary.family?.let { "$id/$it" } ?: id
        val view = buildView(shaperId, capture, status, summary.counts, summary.tests, wrapper, limitations, budget, sections)
        return Shaped(
            status = status,
            counts = summary.counts,
            tests = summary.tests,
            view = view.view,
            viewTruncated = view.viewTruncated,
            captureTruncated = view.captureTruncated,
            recallHint = view.recallHint,
            wrapper = wrapper,
            shaper = shaperId,
            limitations = limitations,
        )
    }

    private fun errorLines(text: String): List<String> = errorShapedLines(text)

    internal companion object {
        /** Error-shaped lines of a capture: the checker's count for a tool without a [DiagnosticsParser]. */
        internal fun errorShapedLines(text: String): List<String> = text.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && (ERROR_WORD.containsMatchIn(it) || DIAGNOSTIC.containsMatchIn(it) || TSC_DIAGNOSTIC.containsMatchIn(it)) }
            .toList()

        val ERROR_WORD = Regex("""(?i)\b(error|fail(ed|ure|ing|s)?|exception|panic(ked)?|traceback|warning)\b""")

        /** ruff / eslint / mypy / pyright style `path:line:col:` diagnostics. */
        val DIAGNOSTIC = Regex("""^\s*\S+:\d+:\d+:""")

        /** `tsc` style `src/a.ts(3,5): error TS2322:`. */
        val TSC_DIAGNOSTIC = Regex("""^\s*\S+\(\d+,\d+\):""")

        const val MAX_ERROR_LINES = 40
    }
}

internal data class GenericSummary(
    val counts: Counts?,
    val tests: List<TestResult>,
    /** The recognised runner family, or null when nothing structured was found. */
    val family: String?,
    val nothingRan: Boolean = false,
)

/** Cheap summary recognisers for the runners P3.1.4 will parse properly (D-09). */
internal object GenericSummaries {
    private val CARGO = Regex("""test result:\s*(ok|FAILED)\.\s*(\d+)\s*passed;\s*(\d+)\s*failed;\s*(\d+)\s*ignored""")
    private val CARGO_CASE = Regex("""^test\s+(\S+)\s+\.\.\.\s*(ok|FAILED|ignored)""")
    private val GO_CASE = Regex("""^\s*---\s+(PASS|FAIL|SKIP):\s+(\S+)""")
    private val GO_PACKAGE = Regex("""^(ok|FAIL|\?)\s+(\S+)\s+(\[no test files\]|\(cached\)|[\d.]+m?s)""")
    private val UNITTEST_RAN = Regex("""^Ran\s+(\d+)\s+tests?\s+in""")
    private val UNITTEST_FAILED = Regex("""^FAILED\s*\((.*)\)""")
    private val UNITTEST_COUNT = Regex("""(failures|errors|skipped)=(\d+)""")
    private val UNITTEST_CASE = Regex("""^(FAIL|ERROR):\s+(\S+)\s*\((.+)\)""")
    private val DOTNET = Regex("""Failed:\s*(\d+),\s*Passed:\s*(\d+),\s*Skipped:\s*(\d+),\s*Total:\s*(\d+)""")
    private val MOCHA_PASSING = Regex("""^(\d+)\s+passing""")
    private val MOCHA_FAILING = Regex("""^(\d+)\s+failing""")
    private val MOCHA_PENDING = Regex("""^(\d+)\s+pending""")

    fun parse(text: String, checkId: String?): GenericSummary {
        val lines = text.lines()
        cargo(lines, checkId)?.let { return it }
        go(lines, checkId)?.let { return it }
        unittest(lines, checkId)?.let { return it }
        dotnet(lines)?.let { return it }
        mocha(lines)?.let { return it }
        return GenericSummary(null, emptyList(), null)
    }

    private fun cargo(lines: List<String>, checkId: String?): GenericSummary? {
        val summary = lines.firstNotNullOfOrNull { CARGO.find(it) } ?: return null
        val passed = summary.groupValues[2].toInt()
        val failed = summary.groupValues[3].toInt()
        val ignored = summary.groupValues[4].toInt()
        val tests = lines.mapNotNull { line ->
            val m = CARGO_CASE.find(line.trim()) ?: return@mapNotNull null
            val path = m.groupValues[1]
            TestResult(
                TestIdentity(
                    check = checkId,
                    suite = path.substringBeforeLast("::", "").takeIf { it.isNotEmpty() },
                    name = path.substringAfterLast("::"),
                ),
                when (m.groupValues[2]) {
                    "ok" -> TestOutcome.Passed
                    "FAILED" -> TestOutcome.Failed
                    else -> TestOutcome.Skipped
                },
            )
        }
        return GenericSummary(Counts(passed, failed, 0, ignored, passed + failed + ignored), tests, "cargo")
    }

    private fun go(lines: List<String>, checkId: String?): GenericSummary? {
        var module: String? = null
        val tests = ArrayList<TestResult>()
        var sawPackageLine = false
        for (line in lines) {
            GO_PACKAGE.find(line)?.let {
                module = it.groupValues[2]
                sawPackageLine = true
            }
            val m = GO_CASE.find(line) ?: continue
            val path = m.groupValues[2]
            tests += TestResult(
                TestIdentity(
                    check = checkId,
                    module = module,
                    suite = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() },
                    name = path.substringAfterLast('/'),
                ),
                when (m.groupValues[1]) {
                    "PASS" -> TestOutcome.Passed
                    "FAIL" -> TestOutcome.Failed
                    else -> TestOutcome.Skipped
                },
            )
        }
        if (tests.isEmpty() && !sawPackageLine) return null
        if (tests.isEmpty()) return GenericSummary(Counts(discovered = 0), emptyList(), "go", nothingRan = true)
        // The package line is printed after the cases, so re-stamp identities with the module it named.
        val pkg = module
        val stamped = if (pkg == null) tests else tests.map { it.copy(identity = it.identity.copy(module = pkg)) }
        return GenericSummary(TestResults.counts(stamped), stamped, "go")
    }

    private fun unittest(lines: List<String>, checkId: String?): GenericSummary? {
        val ran = lines.firstNotNullOfOrNull { UNITTEST_RAN.find(it.trim()) } ?: return null
        val total = ran.groupValues[1].toInt()
        var failures = 0
        var errors = 0
        var skipped = 0
        lines.firstNotNullOfOrNull { UNITTEST_FAILED.find(it.trim()) }?.let { failed ->
            UNITTEST_COUNT.findAll(failed.groupValues[1]).forEach {
                val n = it.groupValues[2].toIntOrNull() ?: 0
                when (it.groupValues[1]) {
                    "failures" -> failures = n
                    "errors" -> errors = n
                    "skipped" -> skipped = n
                }
            }
        }
        val tests = lines.mapNotNull { line ->
            val m = UNITTEST_CASE.find(line.trim()) ?: return@mapNotNull null
            val name = m.groupValues[2]
            val dotted = m.groupValues[3]
            TestResult(
                TestIdentity(
                    check = checkId,
                    file = dotted.removeSuffix(".$name").takeIf { it.isNotEmpty() && it != dotted } ?: dotted,
                    name = name,
                ),
                if (m.groupValues[1] == "FAIL") TestOutcome.Failed else TestOutcome.Error,
            )
        }
        val passed = (total - failures - errors - skipped).coerceAtLeast(0)
        return GenericSummary(Counts(passed, failures, errors, skipped, total), tests, "unittest", nothingRan = total == 0)
    }

    private fun dotnet(lines: List<String>): GenericSummary? {
        val m = lines.firstNotNullOfOrNull { DOTNET.find(it) } ?: return null
        val failed = m.groupValues[1].toInt()
        val passed = m.groupValues[2].toInt()
        val skipped = m.groupValues[3].toInt()
        val total = m.groupValues[4].toInt()
        return GenericSummary(Counts(passed, failed, 0, skipped, total), emptyList(), "dotnet", nothingRan = total == 0)
    }

    private fun mocha(lines: List<String>): GenericSummary? {
        val trimmed = lines.map { it.trim() }
        val passing = trimmed.firstNotNullOfOrNull { MOCHA_PASSING.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        val failing = trimmed.firstNotNullOfOrNull { MOCHA_FAILING.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        val pending = trimmed.firstNotNullOfOrNull { MOCHA_PENDING.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        if (passing == null && failing == null) return null
        val passed = passing ?: 0
        val failed = failing ?: 0
        val skipped = pending ?: 0
        return GenericSummary(Counts(passed, failed, 0, skipped, passed + failed + skipped), emptyList(), "mocha")
    }
}
