package io.astrolabe.tool.run

import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.Digest
import io.astrolabe.provider.TokenEstimator
import kotlinx.serialization.Serializable

/** Structured report formats a runner can be asked to write (D-50). */
@Serializable
public enum class ReportKind { JUnitXml, PytestJson, JestJson, Other }

/**
 * A structured report captured for one invocation (D-50, I-13). Only a report written to a **fresh
 * per-invocation destination** — or one whose [provenance] explicitly records a validated build-cache
 * reuse — may become evidence; a filename timestamp alone never does.
 */
@Serializable
public data class ReportArtifact(
    val path: String,
    val kind: ReportKind,
    /** True when this invocation wrote the report to a destination created for it alone. */
    val freshForThisInvocation: Boolean,
    /** How the bytes were obtained; `build-cache:<recorded reuse>` is the only accepted stale form. */
    val provenance: String,
    /** Report bytes read at the capture boundary, so shaping never touches the filesystem (D-50). */
    val content: ByteArray? = null,
    /** Gradle/Maven project this report belongs to; derived from [path] when absent. */
    val module: String? = null,
) {
    /** A stale report without recorded build-cache provenance proves nothing about this invocation. */
    val usableAsEvidence: Boolean get() = rejectionReason == null

    /** Why this report is not evidence for this invocation; null when it is. Rendered in the view, never silent. */
    val rejectionReason: String?
        get() = when {
            !freshForThisInvocation &&
                !(provenance.length > BUILD_CACHE_PREFIX.length && provenance.startsWith(BUILD_CACHE_PREFIX)) ->
                "not written for this invocation and provenance '$provenance' records no validated build-cache reuse"
            content == null -> "its bytes were not captured, so nothing could be parsed before shaping"
            else -> null
        }

    /** `<module>/build/test-results/test/TEST-x.xml` and Maven's `target/surefire-reports` form. */
    val moduleOrDerived: String?
        get() = module ?: derivedModule()

    private fun derivedModule(): String? {
        val parts = path.replace('\\', '/').split('/')
        val at = parts.indexOfFirst { it == "build" || it == "target" }
        if (at <= 0) return null
        return parts[at - 1].takeIf { it.isNotBlank() && it != "." && it != ".." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ReportArtifact &&
                path == other.path && kind == other.kind &&
                freshForThisInvocation == other.freshForThisInvocation &&
                provenance == other.provenance && module == other.module &&
                (content?.contentEquals(other.content ?: ByteArray(0)) ?: (other.content == null))
            )

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + freshForThisInvocation.hashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + (module?.hashCode() ?: 0)
        result = 31 * result + (content?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ReportArtifact($path, $kind, fresh=$freshForThisInvocation, provenance=$provenance, bytes=${content?.size ?: 0})"

    public companion object {
        /** Prefix a caller must use to declare a recorded, validated build-cache reuse (D-50). */
        public const val BUILD_CACHE_PREFIX: String = "build-cache:"
    }
}

/**
 * Everything one runner invocation produced, parsed once at the boundary (§2.3). [output] is the combined
 * stdout+stderr exactly as captured to the log; [captureComplete] is false when the **capture** limit was
 * reached — a different fact from the prompt budget that shortens the view (§5.4 truncation policy).
 *
 * `equals`/`hashCode`/`toString` are written out because [output] is an array.
 */
@Serializable
public data class RunCapture(
    val actionId: String,
    val argv: List<String>,
    val shell: Boolean = false,
    val cwd: String? = null,
    val exitCode: Int?,
    val timedOut: Boolean = false,
    val output: ByteArray = ByteArray(0),
    val captureComplete: Boolean = true,
    val reports: List<ReportArtifact> = emptyList(),
    val runnerVersion: String? = null,
    /** Digest of the check definition this invocation realizes; recorded on the receipt (§8.4). */
    val definitionVersion: Digest? = null,
    /** The check whose evidence this is; flows into every [TestIdentity] (D-27). */
    val checkId: String? = null,
    /** The selector the runner was given (`-k ctx`, `--tests CartTest`); recorded with the report (D-50). */
    val selector: String? = null,
) {
    init {
        require(actionId.isNotBlank()) { "a capture needs its action id (D-50)" }
    }

    /** [output] decoded as UTF-8. */
    public fun text(): String = output.toString(Charsets.UTF_8)

    /** Reports that may become evidence for *this* invocation (D-50). */
    val evidenceReports: List<ReportArtifact> get() = reports.filter { it.usableAsEvidence }

    /** Reports present but rejected: named in the shaped view so a stale green is visible, never silent. */
    val rejectedReports: List<ReportArtifact> get() = reports.filterNot { it.usableAsEvidence }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RunCapture &&
                actionId == other.actionId && argv == other.argv && shell == other.shell && cwd == other.cwd &&
                exitCode == other.exitCode && timedOut == other.timedOut && output.contentEquals(other.output) &&
                captureComplete == other.captureComplete && reports == other.reports &&
                runnerVersion == other.runnerVersion && definitionVersion == other.definitionVersion &&
                checkId == other.checkId && selector == other.selector
            )

    override fun hashCode(): Int {
        var result = actionId.hashCode()
        result = 31 * result + argv.hashCode()
        result = 31 * result + shell.hashCode()
        result = 31 * result + (cwd?.hashCode() ?: 0)
        result = 31 * result + (exitCode ?: 0)
        result = 31 * result + timedOut.hashCode()
        result = 31 * result + output.contentHashCode()
        result = 31 * result + captureComplete.hashCode()
        result = 31 * result + reports.hashCode()
        result = 31 * result + (runnerVersion?.hashCode() ?: 0)
        result = 31 * result + (definitionVersion?.hashCode() ?: 0)
        result = 31 * result + (checkId?.hashCode() ?: 0)
        result = 31 * result + (selector?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RunCapture($actionId, argv=$argv, shell=$shell, exit=$exitCode, timedOut=$timedOut, " +
            "bytes=${output.size}, captureComplete=$captureComplete, reports=${reports.size})"
}

/**
 * A shell construct that swallows the runner's exit status (`pytest -q || true`). When one is present the
 * suite status comes from the parsed runner evidence only — the wrapper's exit 0 is never a pass (§8.3, FX-08).
 */
@Serializable
public data class WrapperDetection(
    val wrapper: String,
    /** The runner invocation to the left of the wrapper, tokenized; null when it could not be split out. */
    val runnerArgv: List<String>? = null,
)

/** The shaped result of one invocation: truthful status, parsed counts, identities and a bounded view. */
@Serializable
public data class Shaped(
    val status: Outcome,
    /** Parsed counts; null when no structured evidence existed — an exit code never becomes a count (§8.3). */
    val counts: Counts?,
    val tests: List<TestResult>,
    val view: String,
    /** The prompt budget shortened the view. */
    val viewTruncated: Boolean,
    /** The capture limit shortened the stored log: those bytes are gone, not merely hidden (FX-10). */
    val captureTruncated: Boolean,
    /** `full: #57 · 5000 lines` — the recall pointer rendered with the truncation marks (§5.4). */
    val recallHint: String? = null,
    val wrapper: WrapperDetection? = null,
    val shaper: String,
    val limitations: List<String> = emptyList(),
) {
    init {
        require(!(status == Outcome.Passed && counts == null)) {
            "a passed status needs parsed counts; missing structured evidence is inconclusive (D-50)"
        }
        require(!(status == Outcome.Passed && counts != null && counts.executed == 0 && counts.discovered == 0)) {
            "a pass with nothing executed is inconclusive, not green (§8.3)"
        }
    }

    /** Identities reported more than once — matching them to a baseline is ambiguous (D-27). */
    val ambiguousIdentities: Set<String> get() = TestResults.ambiguous(tests)
}

/**
 * How much of the shaped view may reach the prompt. The default is `run(budget=1200)` (§5.4); with no
 * [estimator] the planning ratio of D-06 bounds it in characters.
 */
public data class ShapeBudget(
    val tokens: Int = DEFAULT_TOKENS,
    val estimator: TokenEstimator? = null,
    /** Campaign-global alias of the stored log (D-46); [RECALL_PLACEHOLDER] when the caller has none yet. */
    val recallAlias: String? = null,
) {
    init {
        require(tokens > 0) { "view budget must be positive" }
    }

    val alias: String get() = recallAlias ?: RECALL_PLACEHOLDER

    internal fun fits(text: String): Boolean =
        estimator?.let { it.estimate(text).upperBoundTokens <= tokens } ?: (text.length <= tokens * CHARS_PER_TOKEN)

    public companion object {
        public const val DEFAULT_TOKENS: Int = 1_200

        /** Token the runner replaces with the log's alias once it has allocated one. */
        public const val RECALL_PLACEHOLDER: String = "#<log>"

        private const val CHARS_PER_TOKEN: Double = 3.6
    }
}

/** Turns one [RunCapture] into a [Shaped] result. Implementations are pure functions of the capture. */
public interface Shaper {
    public val id: String

    /** Bumped whenever parsing or status rules change; recorded with the receipt (§8.4). */
    public val version: String

    public fun applies(capture: RunCapture): Boolean

    public fun shape(capture: RunCapture, budget: ShapeBudget): Shaped
}

/** Ordered shaper registry (D-09: Python → JS/TS → JVM, generic last). */
public object Shapers {
    @JvmField public val pytest: Shaper = PytestShaper()

    @JvmField public val jest: Shaper = JestShaper()

    @JvmField public val junitXml: Shaper = JUnitXmlShaper()

    @JvmField public val generic: Shaper = GenericShaper()

    @JvmField public val ordered: List<Shaper> = listOf(pytest, jest, junitXml, generic)

    @JvmStatic
    public fun select(capture: RunCapture): Shaper = ordered.firstOrNull { it.applies(capture) } ?: generic

    @JvmStatic
    @JvmOverloads
    public fun shape(capture: RunCapture, budget: ShapeBudget = ShapeBudget()): Shaped =
        select(capture).shape(capture, budget)
}

// ---------------------------------------------------------------------------------------------------
// Shared internals: invocation shape, status rules and view assembly.
// ---------------------------------------------------------------------------------------------------

/** Wrapper detection and runner-argv recovery (§5.4 `cmd` is one shell invocation reported as such). */
internal object Invocations {
    private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ash", "cmd", "powershell", "pwsh")

    /** Constructs that hide a runner's exit status; ordered longest-first so `|| echo` wins over `||`. */
    private val WRAPPERS = listOf("|| true", "|| :", "|| exit 0", "|| echo", "; true", "&& echo")

    fun basename(token: String): String =
        token.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe").removeSuffix(".bat").removeSuffix(".cmd")

    /** The single shell command line, when this capture is one shell invocation. */
    fun shellText(capture: RunCapture): String? {
        if (capture.argv.isEmpty()) return null
        val head = basename(capture.argv[0]).lowercase()
        if (head in SHELLS && capture.argv.size >= 3) return capture.argv.last()
        if (capture.shell) return capture.argv.joinToString(" ")
        return null
    }

    fun detectWrapper(capture: RunCapture): WrapperDetection? {
        val text = shellText(capture) ?: return null
        var best: Pair<String, Int>? = null
        for (w in WRAPPERS) {
            val at = text.indexOf(w)
            if (at >= 0 && text.take(at).isNotBlank() && (best == null || at < best.second)) best = w to at
        }
        val (wrapper, at) = best ?: return null
        val left = text.take(at).trim().trimEnd(';', '&')
        return WrapperDetection(wrapper, tokenize(left).takeIf { it.isNotEmpty() })
    }

    /** Tokens of the runner invocation: inside a wrapper, the wrapped command; otherwise the argv itself. */
    fun runnerTokens(capture: RunCapture): List<String> {
        detectWrapper(capture)?.runnerArgv?.let { return it }
        shellText(capture)?.let { return tokenize(it) }
        return capture.argv
    }

    /** The runner's own name (`pytest`, `gradlew`, `cargo`), skipping interpreters and `-m`. */
    fun runnerName(capture: RunCapture): String? {
        val tokens = runnerTokens(capture)
        val head = tokens.firstOrNull() ?: return null
        val base = basename(head)
        if (base.lowercase().startsWith("python") || base.lowercase() == "py" || base.lowercase() == "node") {
            val m = tokens.indexOf("-m")
            if (m >= 0 && m + 1 < tokens.size) return basename(tokens[m + 1])
        }
        if (base == "npx" || base == "npm" || base == "pnpm" || base == "yarn") {
            tokens.drop(1).firstOrNull { !it.startsWith("-") && it != "run" && it != "exec" }?.let { return basename(it) }
        }
        return base
    }

    /** Whitespace split honouring single and double quotes; good enough to name the wrapped runner. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quote = ' '
        for (c in text) {
            when {
                quote != ' ' -> if (c == quote) quote = ' ' else sb.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}

internal data class StatusInputs(
    val capture: RunCapture,
    val counts: Counts?,
    val wrapper: WrapperDetection?,
    val runnerName: String?,
    /** The runner itself said nothing was collected (`pytest` exit 5, `no tests ran`). */
    val nothingCollected: Boolean = false,
    /** Structured evidence existed but could not be read whole (parse error, truncated report, short totals). */
    val evidenceIncomplete: Boolean = false,
    val infraExitCodes: Set<Int> = emptySet(),
    val inconclusiveExitCodes: Set<Int> = emptySet(),
)

/**
 * The one status rule (§8.3, §8.4, D-50). Applied in order; a parse error, an absent result or a wrapper's
 * exit 0 never reaches [Outcome.Passed], and parsed counts outrank a disagreeing exit code.
 */
internal fun deriveStatus(inputs: StatusInputs, limitations: MutableList<String>): Outcome {
    val capture = inputs.capture
    if (capture.timedOut) return Outcome.Timeout
    val exit = capture.exitCode ?: run {
        limitations += "no exit status was observed: the outcome must be reconciled before any retry (§5.4)"
        return Outcome.UnknownOutcome
    }
    if (runnerMissing(capture, exit, inputs.runnerName)) {
        limitations += "runner '${inputs.runnerName ?: "?"}' could not be executed (exit $exit): nothing was verified (D-09)"
        return Outcome.Unavailable
    }
    if (exit in inputs.infraExitCodes) {
        limitations += "the runner reported a usage or internal error (exit $exit), not a test result"
        return Outcome.InfraError
    }
    val wrapper = inputs.wrapper
    if (wrapper != null) {
        limitations += "wrapper '${wrapper.wrapper}' hides the runner's exit status: status comes from parsed output only (§8.3, FX-08)"
    }
    if (inputs.nothingCollected || exit in inputs.inconclusiveExitCodes) {
        limitations += "the runner collected no tests: an empty selection is inconclusive, never a pass (FX-09)"
        return Outcome.Inconclusive
    }
    val counts = inputs.counts
    if (counts == null) {
        return if (wrapper == null && exit != 0) {
            Outcome.Failed
        } else {
            limitations += "no structured evidence was parsed: exit $exit alone is not proof that anything ran (§8.3, D-50)"
            Outcome.Inconclusive
        }
    }
    // Counts outrank the exit code: a summary claiming failures is believed even at exit 0 (IX-13).
    if (counts.failed > 0 || counts.errors > 0) return Outcome.Failed
    if (counts.executed == 0) {
        limitations += "the runner executed no tests: ${counts.discovered} discovered, 0 executed (D-50)"
        return Outcome.Inconclusive
    }
    if (wrapper != null || exit == 0) {
        if (inputs.evidenceIncomplete) {
            limitations += "the structured evidence could not be read whole: a parse error is never a pass (§8.4)"
            return Outcome.Inconclusive
        }
        return Outcome.Passed
    }
    limitations += "exit $exit disagrees with a summary reporting no failures: the evidence is ambiguous (D-50)"
    return Outcome.Inconclusive
}

private fun runnerMissing(capture: RunCapture, exit: Int, runnerName: String?): Boolean {
    if (exit == 127 || exit == 9009) return true
    val name = runnerName ?: return false
    return capture.text().lineSequence().any { line ->
        line.contains(name) && (
            line.contains("command not found") ||
                line.contains("is not recognized as an internal or external command") ||
                line.contains(": not found") ||
                line.contains("No module named $name") ||
                line.contains("No module named '$name'")
            )
    }
}

internal data class ViewResult(
    val view: String,
    val viewTruncated: Boolean,
    val captureTruncated: Boolean,
    val recallHint: String?,
)

/** One titled block of the shaped view; blocks are added in priority order until the budget is spent. */
internal data class ViewSection(val title: String, val lines: List<String>)

/**
 * Assembles the shaped view (§5.4, §8.3): the counts line and every limitation are mandatory, the failing
 * identities and the raw head/tail fill the remaining budget, and the two truncation facts are rendered
 * distinctly — the prompt budget shortened the *view*, the capture limit shortened the *log* (FX-10).
 */
internal fun buildView(
    shaperId: String,
    capture: RunCapture,
    status: Outcome,
    counts: Counts?,
    tests: List<TestResult>,
    wrapper: WrapperDetection?,
    limitations: List<String>,
    budget: ShapeBudget,
    extraSections: List<ViewSection> = emptyList(),
    headLines: Int = HEAD_LINES,
    tailLines: Int = TAIL_LINES,
): ViewResult {
    val head = ArrayList<String>()
    val runner = listOfNotNull(shaperId, capture.runnerVersion).joinToString(" ")
    // §8.3: the line names the invocation scope, never a bare verdict.
    head += "$runner · ${status.name.lowercase()} · exit ${capture.exitCode?.toString() ?: "none"}" +
        (if (capture.timedOut) " · timed out" else "") +
        (capture.selector?.let { " · selector $it" } ?: "")
    head += counts?.let {
        "counts: ${it.passed} passed · ${it.failed} failed · ${it.errors} errors · ${it.skipped} skipped · ${it.discovered} discovered"
    } ?: "counts: unavailable (no parsed evidence)"
    wrapper?.let { w ->
        head += "wrapper: '${w.wrapper}'" + (w.runnerArgv?.let { " around `${it.joinToString(" ")}`" } ?: "")
    }
    limitations.take(MAX_LIMITATIONS).forEach { head += "! $it" }
    if (limitations.size > MAX_LIMITATIONS) head += "! … ${limitations.size - MAX_LIMITATIONS} more limitations"

    val body = ArrayList<String>()
    val failing = tests.filter { it.failing }
    if (failing.isNotEmpty()) {
        body += "failing tests (${failing.size}):"
        failing.take(MAX_FAILURES).forEach { body += "  " + failureLine(it) }
        if (failing.size > MAX_FAILURES) body += "  … ${failing.size - MAX_FAILURES} more"
    }
    TestResults.ambiguous(tests).takeIf { it.isNotEmpty() }?.let { amb ->
        body += "repeated identities (${amb.size}): multiplicity kept; matching is ambiguous (D-27)"
    }
    extraSections.forEach { section ->
        if (section.lines.isEmpty()) return@forEach
        body += section.title
        section.lines.forEach { body += "  $it" }
    }

    val rawLines = capture.text().lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
    var elided = 0
    if (rawLines.isNotEmpty()) {
        if (rawLines.size <= headLines + tailLines) {
            body += "output (${rawLines.size} lines):"
            rawLines.forEach { body += "  $it" }
        } else {
            elided = rawLines.size - headLines - tailLines
            body += "output (head $headLines / tail $tailLines of ${rawLines.size} lines):"
            rawLines.take(headLines).forEach { body += "  $it" }
            body += "  … $elided lines elided …"
            rawLines.takeLast(tailLines).forEach { body += "  $it" }
        }
    }

    val captureTruncated = !capture.captureComplete
    val maxFooter = footer(viewTruncated = true, captureTruncated = captureTruncated, capture = capture, rawLines = rawLines.size, budget = budget)
    val acc = StringBuilder(head.joinToString("\n"))
    var included = 0
    for (line in body) {
        val candidate = buildString {
            append(acc).append('\n').append(line)
            if (maxFooter.isNotEmpty()) append('\n').append(maxFooter)
        }
        if (!budget.fits(candidate)) break
        acc.append('\n').append(line)
        included++
    }
    val viewTruncated = included < body.size || elided > 0
    val actualFooter = footer(viewTruncated, captureTruncated, capture, rawLines.size, budget)
    if (actualFooter.isNotEmpty()) acc.append('\n').append(actualFooter)
    val recall = if (viewTruncated || captureTruncated) recallHint(capture, rawLines.size, budget) else null
    return ViewResult(acc.toString(), viewTruncated, captureTruncated, recall)
}

private fun failureLine(result: TestResult): String {
    val message = result.message?.replace('\n', ' ')?.trim()?.take(MAX_MESSAGE)
    return result.identity.display + (if (message.isNullOrEmpty()) "" else " — $message")
}

private fun recallHint(capture: RunCapture, rawLines: Int, budget: ShapeBudget): String =
    "full: ${budget.alias} · $rawLines lines" +
        if (capture.captureComplete) "" else " captured (capture limit reached; later bytes were never stored)"

private fun footer(
    viewTruncated: Boolean,
    captureTruncated: Boolean,
    capture: RunCapture,
    rawLines: Int,
    budget: ShapeBudget,
): String {
    val lines = ArrayList<String>(3)
    if (viewTruncated) lines += "view truncated at prompt budget ${budget.tokens} tokens"
    if (captureTruncated) {
        lines += "capture incomplete: log truncated at ${capture.output.size} bytes — " +
            "bytes beyond the capture limit were never stored and cannot be recalled"
    }
    if (viewTruncated || captureTruncated) lines += "(${recallHint(capture, rawLines, budget)})"
    return lines.joinToString("\n")
}

private const val HEAD_LINES = 30
private const val TAIL_LINES = 30
private const val MAX_FAILURES = 25
private const val MAX_LIMITATIONS = 8
private const val MAX_MESSAGE = 160
