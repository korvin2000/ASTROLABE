package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome

/**
 * The fallback shaper: head + tail with every error-shaped line (§5.4); without a recognised runner the raw output
 * fills the view budget, about 40 % head and 60 % tail (D-370). Counts appear only when a
 * recognisable summary does (`cargo test`, `go test`, `unittest`, `dotnet test`, `mocha`); otherwise the
 * result carries `counts = null` and a "no parser" limitation, because a generic exit code never becomes a
 * count (§8.3) and unparsed evidence is never green (D-50). The end-of-turn checkers' diagnostics parsers
 * are [DiagnosticsParser].
 */
public class GenericShaper : Shaper {
    override val id: String = "generic"
    override val version: String = "4"

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
        if (summary.family != null && summary.counts == null) {
            limitations += "the ${summary.family} summary in this output is incomplete (a cut log or mixed output): counts unavailable (§8.3)"
        }
        if (summary.identityIncomplete) limitations +="some Go cases have no package summary; their identities cannot certify pre-existing failures"
        summary.note?.let { limitations += it }
        val status = deriveStatus(
            StatusInputs(
                capture = capture,
                counts = summary.counts,
                wrapper = wrapper,
                runnerName = runner,
                nothingCollected = summary.nothingRan,
                evidenceIncomplete = summary.evidenceIncomplete,
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
        val statusText = if (completedPlainly(capture, status, summary.counts, wrapper)) COMPLETED else null
        val view = buildView(shaperId, capture, status, summary.counts, summary.tests, wrapper, limitations, budget, sections, statusText = statusText, budgetedRaw = summary.family == null)
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
            evidenceIncomplete = summary.evidenceIncomplete,
        )
    }

    private fun errorLines(text: String): List<String> = errorShapedLines(text)

    internal companion object {
        /** How a plain command's exit 0 reads (D-351); its outcome stays [Outcome.Inconclusive], never test evidence (D-50). */
        const val COMPLETED = "completed, exit code 0"

        /** The envelope header status of the same plain exit 0 (D-353); as evidence it stays inconclusive, never green. */
        const val COMPLETED_STATUS = "completed"

        /**
         * A plain `run` outside acceptance — no check, no wrapper, no mounted tool — that exited 0 without test counts.
         * Presentation only (D-351, D-353): the outcome, the receipt and certification are unchanged (§8.3, D-50).
         */
        fun completedPlainly(capture: RunCapture, status: Outcome, counts: Counts?, wrapper: WrapperDetection?): Boolean =
            status == Outcome.Inconclusive && counts == null && wrapper == null && capture.checkId == null &&
                capture.exitCode == 0 && !capture.timedOut && capture.argv.firstOrNull()?.startsWith("mcp:") != true

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
    val identityIncomplete: Boolean = false,
    /** [tests] is a cut record: no complete summary followed the results read (P8.C.15). */
    val evidenceIncomplete: Boolean = false,
    /** A limitation of the per-test record, rendered in the view. */
    val note: String? = null,
)

/**
 * P8.C.15: the per-test lines of `node --test` — the spec reporter's `✔` / `✖` / `﹣` results nested two spaces a level
 * under `▶` groups, and the TAP reporter's `ok` / `not ok` nested four spaces a level under `# Subtest:` lines. Read once,
 * up to the spec reporter's `✖ failing tests:` section, which repeats failures. A result with children is a suite or a
 * parent test: TAP's `type:` says which, the spec reporter only for an older suite (`▶ name (…)`); the summary's count
 * settles the rest, or no identity is recorded at all.
 */
internal object NodeTests {
    enum class Kind { Test, Suite, Parent }

    class Entry(val path: List<String>, val name: String, val outcome: TestOutcome, var kind: Kind, var message: String? = null) {
        fun result(checkId: String?): TestResult =
            TestResult(TestIdentity(check = checkId, suite = path.joinToString(" > ").ifEmpty { null }, name = name), outcome, message)
    }

    /** What the lines report, in order; [consistent] false when a nesting did not close as it opened. */
    class Read(val entries: List<Entry>, val consistent: Boolean)

    private val SPEC_GROUP = Regex("""^( *)▶ (.+?)(?: \(\d+(?:\.\d+)?m?s\))?$""")
    private val SPEC_GROUP_END = Regex("""^ *▶ .+ \(\d+(?:\.\d+)?m?s\)$""")
    private val SPEC_RESULT = Regex("""^( *)([✔✖﹣]) (.+) \(\d+(?:\.\d+)?m?s\)(?: # (SKIP|TODO)\b.*)?$""")
    private const val SPEC_FAILING = "✖ failing tests:"
    private val TAP_SUBTEST = Regex("""^( *)# Subtest: (.+)$""")
    private val TAP_RESULT = Regex("""^( *)(ok|not ok) \d+ - (.*?)(?: # (SKIP|TODO)\b.*)?$""")
    private val TAP_TYPE = Regex("""^type: '(suite|test)'$""")

    fun read(lines: List<String>): Read {
        val spec = spec(lines)
        val tap = tap(lines)
        return Read(spec.entries + tap.entries, spec.consistent && tap.consistent)
    }

    /**
     * The tests of [read] when they add up to [counts] — every unsettled parent a suite, or every one a test — and agree
     * on passed, failed (cancelled included) and skipped (todo included); `null` otherwise.
     */
    fun reconciled(read: Read, counts: Counts, checkId: String?): List<TestResult>? {
        if (!read.consistent) return null
        val tests = read.entries.count { it.kind == Kind.Test }
        val parents = read.entries.count { it.kind == Kind.Parent }
        val chosen = when (counts.discovered) {
            tests -> read.entries.filter { it.kind == Kind.Test }
            tests + parents -> read.entries.filter { it.kind != Kind.Suite }
            else -> return null
        }
        val results = chosen.map { it.result(checkId) }
        val listed = TestResults.counts(results)
        return results.takeIf { listed.passed == counts.passed && listed.failed == counts.failed && listed.skipped == counts.skipped }
    }

    private fun outcomeOf(passed: Boolean, directive: String): TestOutcome = when {
        directive.isNotEmpty() -> TestOutcome.Skipped
        passed -> TestOutcome.Passed
        else -> TestOutcome.Failed
    }

    private fun spec(lines: List<String>): Read {
        val entries = ArrayList<Entry>()
        val open = ArrayList<String>()
        var consistent = true
        fun level(indent: String, step: Int): Int? = if (indent.length % step == 0) indent.length / step else null
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line == SPEC_FAILING) break
            SPEC_RESULT.matchEntire(line)?.let { m ->
                val at = level(m.groupValues[1], 2) ?: run { consistent = false; null } ?: return@let
                val name = m.groupValues[3]
                val outcome = if (m.groupValues[2] == "﹣") TestOutcome.Skipped else outcomeOf(m.groupValues[2] == "✔", m.groupValues[4])
                val closes = open.size > at && open[at] == name
                if (!closes && open.size != at) consistent = false
                if (open.size < at || name.isBlank()) return@let
                entries += Entry(open.take(at), name, outcome, if (closes) Kind.Parent else Kind.Test)
                open.subList(at, open.size).clear()
            } ?: SPEC_GROUP.matchEntire(line)?.let { m ->
                val at = level(m.groupValues[1], 2) ?: run { consistent = false; null } ?: return@let
                val name = m.groupValues[2]
                if (SPEC_GROUP_END.matches(line)) {
                    // An older reporter's suite end: no test of its own.
                    if (open.size > at && open[at] == name) open.subList(at, open.size).clear() else consistent = false
                } else {
                    if (open.size != at) consistent = false
                    if (open.size < at) return@let
                    open.subList(at, open.size).clear()
                    open += name
                }
            }
        }
        return Read(entries, consistent)
    }

    private fun tap(lines: List<String>): Read {
        val entries = ArrayList<Entry>()
        val open = ArrayList<String>()
        var consistent = true
        var lastLevel = -1
        var target: Entry? = null
        var inYaml = false
        var errorNext = false
        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (inYaml) {
                val entry = target
                when {
                    trimmed == "..." -> { inYaml = false; target = null }
                    entry == null -> Unit
                    errorNext -> { entry.message = trimmed; errorNext = false }
                    TAP_TYPE.matches(trimmed) -> entry.kind = if (trimmed.contains("suite")) Kind.Suite else Kind.Test
                    trimmed.startsWith("error: ") -> {
                        val value = trimmed.removePrefix("error: ")
                        if (value.startsWith("|") || value.startsWith(">")) errorNext = true else entry.message = value.removeSurrounding("'").removeSurrounding("\"")
                    }
                }
                continue
            }
            if (trimmed == "---" && target != null) { inYaml = true; continue }
            target = null
            TAP_SUBTEST.matchEntire(line)?.let { m ->
                val indent = m.groupValues[1].length
                if (indent % 4 != 0 || open.size < indent / 4) { consistent = false; return@let }
                open.subList(indent / 4, open.size).clear()
                open += unescape(m.groupValues[2])
            } ?: TAP_RESULT.matchEntire(line)?.let { m ->
                val indent = m.groupValues[1].length
                val at = indent / 4
                val name = unescape(m.groupValues[3])
                if (indent % 4 != 0 || open.size < at || name.isBlank()) { consistent = false; return@let }
                val entry = Entry(open.take(at), name, outcomeOf(m.groupValues[2] == "ok", m.groupValues[4]), if (lastLevel == at + 1) Kind.Parent else Kind.Test)
                entries += entry
                open.subList(at, open.size).clear()
                lastLevel = at
                target = entry
            }
        }
        return Read(entries, consistent)
    }

    /** TAP's escapes in a test name: `\#` and `\\`. */
    private fun unescape(name: String): String {
        if ('\\' !in name) return name
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c == '\\' && i + 1 < name.length && (name[i + 1] == '#' || name[i + 1] == '\\')) {
                out.append(name[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** Cheap summary recognisers for the runners P3.1.4 will parse properly (D-09). */
internal object GenericSummaries {
    private val CARGO = Regex("""test result:\s*(ok|FAILED)\.\s*(\d+)\s*passed;\s*(\d+)\s*failed;\s*(\d+)\s*ignored""")
    private val CARGO_CASE = Regex("""^test\s+(\S+)\s+\.\.\.\s*(ok|FAILED|ignored)""")
    private val GO_CASE = Regex("""^\s*---\s+(PASS|FAIL|SKIP):\s+(\S+)""")
    private val GO_PACKAGE = Regex("""^(ok|FAIL|\?)\s+(\S+)\s+(\[no test files\]|\(cached\)|[\d.]+m?s)""")
    private val UNITTEST_RAN = Regex("""^Ran\s+(\d+)\s+tests?\s+in""")
    private val UNITTEST_FAILED = Regex("""^(?:FAILED|OK)\s*\((.*)\)""")
    private val UNITTEST_COUNT = Regex("""(?:^|,\s*)(failures|errors|skipped)=(\d+)""")
    private val UNITTEST_CASE = Regex("""^(FAIL|ERROR):\s+(\S+)\s*\((.+)\)""")
    private val DOTNET = Regex("""Failed:\s*(\d+),\s*Passed:\s*(\d+),\s*Skipped:\s*(\d+),\s*Total:\s*(\d+)""")
    private val MOCHA_PASSING = Regex("""^(\d+)\s+passing""")
    private val MOCHA_FAILING = Regex("""^(\d+)\s+failing""")
    private val MOCHA_PENDING = Regex("""^(\d+)\s+pending""")

    /** `node --test`: the spec reporter's `ℹ tests 7` lines and the TAP reporter's `# tests 7`; the mark may arrive in another code page. */
    private val NODE_SUMMARY = Regex("""^[^\w\s]{1,3}\s+(tests|pass|fail|cancelled|skipped|todo)\s+(\d+)$""")

    fun parse(text: String, checkId: String?): GenericSummary {
        val lines = text.lines()
        cargo(lines, checkId)?.let { return it }
        go(lines, checkId)?.let { return it }
        unittest(lines, checkId)?.let { return it }
        dotnet(lines)?.let { return it }
        val mocha = mocha(lines)
        val node = node(lines, checkId, mochaSummary = mocha != null)
        // Node summary lines that do not add up to complete summaries: no other reader may turn the same output green.
        if (node != null && node.counts == null) return node
        // One command that ran both runners: the counts add up, so a green summary of one never hides the other's failures.
        if (node != null && mocha != null) {
            val a = node.counts!!
            val b = mocha.counts!!
            return GenericSummary(Counts(a.passed + b.passed, a.failed + b.failed, a.errors + b.errors, a.skipped + b.skipped, a.discovered + b.discovered), emptyList(), "node+mocha")
        }
        (node ?: mocha)?.let { return it }
        return GenericSummary(null, emptyList(), null)
    }

    /**
     * Counts only from complete summaries: every `tests` line has its `pass` and its `fail` line. Several summaries, one
     * per command, add up. `null` when the output holds no `tests` line at all; a summary without counts when it holds
     * some but they are incomplete (a cut log, mixed output) — evidence that is present and not trusted.
     */
    private fun node(lines: List<String>, checkId: String?, mochaSummary: Boolean): GenericSummary? {
        val read = NodeTests.read(lines)
        // P8.C.15: without a complete summary the results read so far are a cut record: only certain tests, never complete.
        val partial = read.entries.filter { it.kind == NodeTests.Kind.Test }.map { it.result(checkId) }
        val cut = "the node test output has no complete summary: ${partial.size} test results read, the record is incomplete"
        val incomplete = GenericSummary(null, partial, "node", evidenceIncomplete = partial.isNotEmpty(), note = cut.takeIf { partial.isNotEmpty() })
        val sums = HashMap<String, Int>()
        val seen = HashMap<String, Int>()
        for (line in lines) {
            val m = NODE_SUMMARY.find(line.trim()) ?: continue
            sums.merge(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: return incomplete, Int::plus)
            seen.merge(m.groupValues[1], 1, Int::plus)
        }
        val summaries = seen["tests"] ?: return incomplete.takeIf { partial.isNotEmpty() && !mochaSummary }
        if (seen["pass"] != summaries || seen["fail"] != summaries) return incomplete
        if (listOf("cancelled", "skipped", "todo").any { key -> seen[key].let { it != null && it != summaries } }) return incomplete
        val total = sums.getValue("tests")
        val failed = sums.getValue("fail") + (sums["cancelled"] ?: 0)
        val skipped = (sums["skipped"] ?: 0) + (sums["todo"] ?: 0)
        val counts = Counts(sums.getValue("pass"), failed, 0, skipped, total)
        val tests = NodeTests.reconciled(read, counts, checkId)
        val note = if (tests == null && read.entries.isNotEmpty()) {
            "the node test lines (${read.entries.size} results) do not add up to the summary's $total tests: no test identities recorded"
        } else {
            null
        }
        return GenericSummary(counts, tests.orEmpty(), "node", nothingRan = total == 0, note = note)
    }

    private fun cargo(lines: List<String>, checkId: String?): GenericSummary? {
        val summaries = lines.mapNotNull { CARGO.find(it) }.ifEmpty { return null }
        val passed = summaries.sumOf { it.groupValues[2].toInt() }
        val failed = summaries.sumOf { it.groupValues[3].toInt() }
        val ignored = summaries.sumOf { it.groupValues[4].toInt() }
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
        val tests = ArrayList<TestResult>()
        val pending = ArrayList<Int>()
        var sawPackageLine = false
        for (line in lines) {
            GO_PACKAGE.find(line)?.let {
                sawPackageLine = true
                if (it.groupValues[1] != "?") {
                    val module = it.groupValues[2]
                    pending.forEach { index -> tests[index] = tests[index].copy(identity = tests[index].identity.copy(module = module)) }
                    pending.clear()
                }
            }
            val m = GO_CASE.find(line) ?: continue
            val path = m.groupValues[2]
            tests += TestResult(
                TestIdentity(
                    check = checkId,
                    suite = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() },
                    name = path.substringAfterLast('/'),
                ),
                when (m.groupValues[1]) {
                    "PASS" -> TestOutcome.Passed
                    "FAIL" -> TestOutcome.Failed
                    else -> TestOutcome.Skipped
                },
            )
            pending += tests.lastIndex
        }
        if (tests.isEmpty() && !sawPackageLine) return null
        if (tests.isEmpty()) return GenericSummary(Counts(discovered = 0), emptyList(), "go", nothingRan = true)
        val identified = tests.filter { it.identity.module != null }
        return GenericSummary(TestResults.counts(tests), identified, "go", identityIncomplete = identified.size != tests.size)
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
