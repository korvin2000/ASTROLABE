package io.astrolabe.verify

import io.astrolabe.Astrolabe
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.FailedTest
import io.astrolabe.evidence.TestOutcomes
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Limit
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.Receipts
import io.astrolabe.evidence.TestedInputs
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Command
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Os
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.store.Layout
import io.astrolabe.tool.run.ReportArtifact
import io.astrolabe.tool.run.ReportKind
import io.astrolabe.tool.run.RunCapture
import io.astrolabe.tool.run.Runner
import io.astrolabe.tool.run.ShapeBudget
import io.astrolabe.tool.run.Shapers
import io.astrolabe.tool.run.TestIdentity
import io.astrolabe.tool.run.TestOutcome
import io.astrolabe.tool.run.TestResult
import io.astrolabe.tool.run.TestResults
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.MaterializeResult
import io.astrolabe.workspace.ShadowRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.relativeTo

/** One pre-existing failure: namespaced identity, normalized failure signature, multiplicity (D-27, D-50). */
public data class PreexistingFailure(val identity: TestIdentity, val signature: String, val multiplicity: Int)

/** How a later red relates to the baseline (§8.5). Only [PreExisting] is "unchanged"; everything else steers. */
public sealed interface BaselineMatch {
    public object PreExisting : BaselineMatch {
        override fun toString(): String = "pre-existing (unchanged)"
    }

    public object New : BaselineMatch {
        override fun toString(): String = "new"
    }

    public data class Changed(val baselineSignature: String) : BaselineMatch

    /** The identity or the environment cannot be compared: never "pre-existing" (D-50). */
    public data class Ambiguous(val reason: String) : BaselineMatch
}

/**
 * The pre-existing-failure ledger (§8.5): the failures of the relevant suite on the initial dirty candidate
 * at `s0`, matched later by identity, comparable environment and failure signature — never by counts or a
 * bare name. A ledger is never permission to waive task acceptance.
 */
public data class PreexistingLedger(
    val receiptId: String,
    val alias: String?,
    val stamp: CandidateId,
    val envId: Digest,
    val entries: List<PreexistingFailure>,
    /** Canonical identities that appeared more than once in the baseline: they can never match. */
    val ambiguous: Set<String>,
    val limitations: List<String> = emptyList(),
) {
    private val byIdentity: Map<String, PreexistingFailure> = entries.associateBy { it.identity.canonical }

    public fun classify(result: TestResult, envId: Digest = this.envId): BaselineMatch {
        if (envId != this.envId) return BaselineMatch.Ambiguous("environment ${envId.hash8} differs from the baseline's ${this.envId.hash8}")
        val canonical = result.identity.canonical
        if (canonical in ambiguous) return BaselineMatch.Ambiguous("'${result.identity}' appears more than once in the baseline")
        val entry = byIdentity[canonical] ?: return BaselineMatch.New
        return if (signatureOf(result) == entry.signature) BaselineMatch.PreExisting else BaselineMatch.Changed(entry.signature)
    }

    /** Rendered once in `[K]`; bounded. */
    public fun render(maxLines: Int = 20): String {
        val head = "── Pre-existing failures (baseline ${alias ?: receiptId} @${stamp.hash8.take(4)}, ${entries.size}) ──"
        if (entries.isEmpty()) return head + " none" + (if (limitations.isEmpty()) "" else " · " + limitations.joinToString(" · "))
        val lines = entries.take(maxLines).map { "  ${it.identity}: ${it.signature}" + (if (it.multiplicity > 1) " (×${it.multiplicity})" else "") }
        val more = if (entries.size > maxLines) listOf("  +${entries.size - maxLines} more") else emptyList()
        val limits = if (limitations.isEmpty()) emptyList() else listOf("  limits: " + limitations.joinToString(" · "))
        return (listOf(head) + lines + more + limits).joinToString("\n")
    }

    public companion object {
        private val HEX_ADDRESS = Regex("""0x[0-9a-fA-F]+""")
        private val LONG_HEX = Regex("""\b[0-9a-f]{7,}\b""")
        private val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")
        private val TEMP_PATH = Regex("""(/tmp/\S+|[A-Za-z]:\\[^\s]*\\Temp\\[^\s]*)""")

        /**
         * The failure signature: the first message line with only identified volatile data normalized
         * (addresses, long hex ids, timestamps, temp paths — D-19); paths, error codes and literals stay.
         */
        @JvmStatic
        public fun signatureOf(result: TestResult): String {
            val first = result.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: result.outcome.name.lowercase()
            return normalize(first)
        }

        /** D-19: normalizes only identified volatile data (addresses, long hex ids, timestamps, temp paths) in [line]. */
        @JvmStatic
        public fun normalize(line: String): String =
            line.replace(HEX_ADDRESS, "0x…").replace(LONG_HEX, "<hex>").replace(TIMESTAMP, "<time>").replace(TEMP_PATH, "<tmp>")
    }
}

/**
 * P8.C.10: how one failure of a harness regression check ([Regressions]) relates to the baseline at `s0` (§8.5).
 */
public enum class RedClass {
    /** The baseline ran the same set on `s0`, the identity is unambiguous and did not fail there: a regression no `Open` item clears. */
    New,

    /** The same identity failed on `s0` with the same fingerprint: inherited, disclosed in the finish receipt, never a gap. */
    Inherited,

    /** Anything else (no usable baseline, a changed fingerprint, an ambiguous identity, not rerun or not executed on this tree, flaky, unidentified): the D-400 rule. */
    Unclassified,
}

/**
 * P8.C.10: the failures of a harness regression check not shown fixed on the tree at hand, by class — [regressions]
 * ([RedClass.New]), [inherited] and [unclassified], one line each — with [since] the red receipts they come from (alias
 * or id), [current] this tree's red receipts of the check (empty when none is current: then an `Open` item is not asked
 * for), and [command] the command whose rerun shows them fixed.
 */
public data class RegressionHold(
    val since: List<String>,
    val current: List<String>,
    val command: String,
    val regressions: List<String> = emptyList(),
    val inherited: List<String> = emptyList(),
    val unclassified: List<String> = emptyList(),
) {
    /** The worst class held: a regression, else an unclassified failure, else inherited ones only. */
    val kind: RedClass get() = when {
        regressions.isNotEmpty() -> RedClass.New
        unclassified.isNotEmpty() -> RedClass.Unclassified
        else -> RedClass.Inherited
    }
}

/**
 * The regressions the harness finds itself (P8.C.10): the blast radius and the types of touched files. A failure counts by
 * its test identity across every red run of the check in the attempt and the workspace, until it executed and passed in a
 * run on the tree at hand; it is then classified against the baseline the same command recorded on `s0`. Pure over
 * receipts, in their insertion order (I-05).
 */
public object Regressions {
    /** The checks the harness runs itself over the change: the blast radius and the types of touched files. */
    @JvmField
    public val CHECKS: Set<String> = setOf(Checks.TESTS_BLAST, Checks.TYPES_TOUCHED)

    /** The limit kind that marks a baseline receipt: one run of a check's command on the captured initial candidate. */
    public const val BASELINE: String = "baseline"

    /** The disclosure when no baseline classifies a failure. */
    public const val NO_BASELINE: String = "no baseline: pre-existing failures cannot be told from regressions"

    /** At most this many failing tests, passed tests and ambiguous identities a receipt records ([TestOutcomes.truncated] beyond). */
    public const val MAX_FAILED: Int = 200
    public const val MAX_PASSED: Int = 2_000

    /** Whether [check] is one of [CHECKS] under the mandatory rule (an acceptance item's check is its item's result instead). */
    @JvmStatic
    public fun of(check: Check): Boolean = check.id in CHECKS && !check.required && Obligations.mandatory(check)

    @JvmStatic
    public fun isBaseline(receipt: Receipt): Boolean = receipt.limits.any { it.kind == BASELINE }

    /**
     * What a run reported test by test (D-27): a key and a fingerprint for comparison — digests over the unredacted
     * identity and failure, the runner's volatile fields normalized ([fingerprint]) — and redacted text to show; every
     * identity reported more than once, whatever its outcome, is ambiguous. Bounded; [roots] are the run's root paths.
     */
    @JvmStatic
    public fun outcomes(tests: List<TestResult>, redact: (String) -> String, roots: List<String>): TestOutcomes {
        val repeated = tests.groupBy { it.identity.canonical }.filterValues { it.size > 1 }.keys
        val failing = tests.filter { it.failing }
        val passing = tests.filter { it.outcome == TestOutcome.Passed }
        val failed = failing.take(MAX_FAILED).map { t ->
            val first = t.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: t.outcome.name.lowercase()
            FailedTest(key(t.identity), redact(t.identity.display).take(MAX_TEXT), fingerprint(t.message ?: t.outcome.name.lowercase(), roots), redact(first).take(MAX_TEXT))
        }
        return TestOutcomes(failed, passing.take(MAX_PASSED).map { key(it.identity) }, repeated.take(MAX_FAILED).map(::keyOf),
            truncated = failing.size > MAX_FAILED || passing.size > MAX_PASSED || repeated.size > MAX_FAILED)
    }

    /** The comparison key of an identity: a digest of its canonical form, never shown. */
    @JvmStatic
    public fun key(identity: TestIdentity): String = keyOf(identity.canonical)

    private fun keyOf(canonical: String): String = Digest.ofUtf8(canonical).hex.take(KEY_HEX)

    /**
     * The comparison fingerprint of a failure (P8.C.10 D): a digest of its unredacted text with only the runner's volatile
     * fields normalized — the run's root paths, `file:line` and `line N`, durations, addresses, timestamps, temp paths —
     * so an assertion's literals, numbers and secrets included, still tell two failures apart.
     */
    @JvmStatic
    public fun fingerprint(text: String, roots: List<String>): String {
        var t = text.replace("\r\n", "\n").trim()
        for (root in roots.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            t = t.replace(root, "<root>").replace(root.replace('\\', '/'), "<root>")
        }
        t = t.replace(FILE_LINE, "$1:<line>").replace(LINE_WORD, "line <n>").replace(DURATION, "<duration>")
            .replace(HEX_ADDRESS, "0x…").replace(TIMESTAMP, "<time>").replace(TEMP_PATH, "<tmp>")
        return Digest.ofUtf8(t).hex.take(KEY_HEX)
    }

    /**
     * P8.C.10 B–C: the hold of one check on the tree [stamp] from its receipts in the attempt and the workspace ([history],
     * no baselines, insertion order) and the attempt's [baselines]. A failure stays until it executed and passed in an
     * eligible run on [stamp] and failed in none there; one that failed there is classified against the latest baseline of
     * that run's definition; one not executed there, or with no run there, is unclassified; the unidentified failures of a
     * red run stay until a passed run of the same command on [stamp]. `null` when nothing is held.
     */
    @JvmStatic
    public fun hold(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?, alias: (Receipt) -> String): RegressionHold? =
        held(history, baselines, stamp)?.let { h ->
            RegressionHold(h.from.map(alias).distinct(), h.current.map { it.receiptId }, h.command, h.regressions, h.inherited, h.unclassified)
        }

    /** The red receipts of [history] behind the hold on [stamp] whose definition has no receipt on it: what verify-on-stop reruns (P8.C.10 A), one per definition. */
    @JvmStatic
    public fun unconfirmed(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): List<Receipt> {
        val h = held(history, baselines, stamp) ?: return emptyList()
        val onStamp = history.filter { it.stampAfter == stamp }.map { it.checkDefinitionVersion }.toSet()
        return h.from.filter { it.outcome == Outcome.Failed && it.checkDefinitionVersion !in onStamp }.reversed().distinctBy { it.checkDefinitionVersion }
    }

    /** This tree's latest red run with identified failures whose definition has no baseline in the attempt yet (P8.C.10 G), or `null`. */
    @JvmStatic
    public fun baselineDue(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): Receipt? {
        val covered = baselines.map { it.checkDefinitionVersion }.toSet()
        return history.lastOrNull { it.stampAfter == stamp && it.testedInputs.eligible && it.outcome == Outcome.Failed && it.tests?.failed?.isNotEmpty() == true }
            ?.takeIf { it.checkDefinitionVersion !in covered }
    }

    private class Held(val from: List<Receipt>, val current: List<Receipt>, val command: String, val regressions: List<String>, val inherited: List<String>, val unclassified: List<String>)

    private fun held(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?): Held? {
        val runs = history.filter { it.testedInputs.eligible && !isBaseline(it) }
        val reds = runs.filter { it.outcome == Outcome.Failed }
        if (reds.isEmpty()) return null
        val fresh = if (stamp == null) emptyList() else runs.filter { it.stampAfter == stamp }
        val failures = LinkedHashMap<String, Pair<FailedTest, Receipt>>()
        for (red in reds) for (failure in red.tests?.failed.orEmpty()) failures[failure.key] = failure to red
        val failingNow = LinkedHashMap<String, Pair<FailedTest, Receipt>>()
        for (run in fresh) for (failure in run.tests?.failed.orEmpty()) failingNow[failure.key] = failure to run
        val passedNow = fresh.flatMap { it.tests?.passed.orEmpty() }.toSet()
        val from = LinkedHashSet<Receipt>()
        val regressions = ArrayList<String>()
        val inherited = ArrayList<String>()
        val unclassified = ArrayList<String>()
        for ((key, earlier) in failures) {
            val (failure, red) = earlier
            val now = failingNow[key]
            when {
                now == null && key in passedNow -> Unit
                now != null && key in passedNow -> { unclassified += "${failure.name}: failed and passed on this tree (flaky)"; from += now.second }
                now != null -> {
                    from += now.second
                    val (kind, line) = classify(now.first, now.second, baselines.lastOrNull { it.checkDefinitionVersion == now.second.checkDefinitionVersion })
                    when (kind) {
                        RedClass.New -> regressions += line
                        RedClass.Inherited -> inherited += line
                        RedClass.Unclassified -> unclassified += line
                    }
                }
                fresh.isEmpty() -> { unclassified += "${failure.name}: failed in ${red.receiptId}, not rerun on this tree"; from += red }
                else -> { unclassified += "${failure.name}: failed in ${red.receiptId}, not executed on this tree (removed, skipped or renamed)"; from += red }
            }
        }
        for (red in reds.filter(::unidentified)) {
            if (fresh.any { it.outcome == Outcome.Passed && it.command == red.command && it.cwd == red.cwd }) continue
            unclassified += "failures of ${red.receiptId} the runner did not identify"
            from += red
        }
        if (from.isEmpty()) return null
        val current = fresh.filter { it.outcome == Outcome.Failed }
        val last = current.lastOrNull() ?: from.last()
        return Held(from.toList(), current, (last.command + listOfNotNull(last.cwd?.let { "(in $it)" })).joinToString(" "), regressions, inherited, unclassified)
    }

    /** P8.C.10 C, one failing identity of [red] against [baseline]. */
    private fun classify(failure: FailedTest, red: Receipt, baseline: Receipt?): Pair<RedClass, String> {
        fun unclassified(why: String) = RedClass.Unclassified to "${failure.name} — ${failure.signature} ($why)"
        if (baseline == null) return unclassified(NO_BASELINE)
        unusable(baseline)?.let { return unclassified("baseline ${baseline.receiptId} $it: $NO_BASELINE") }
        if (red.envId != baseline.envId) return unclassified("environment ${red.envId.hash8} differs from the baseline's ${baseline.envId.hash8}")
        if (failure.key in red.tests?.ambiguous.orEmpty() || failure.key in baseline.tests?.ambiguous.orEmpty()) return unclassified("reported more than once: ambiguous")
        val before = baseline.tests?.failed?.firstOrNull { it.key == failure.key }
        return when {
            before == null -> RedClass.New to "${failure.name} — ${failure.signature} (did not fail on s0 in baseline ${baseline.receiptId})"
            before.fingerprint == failure.fingerprint -> RedClass.Inherited to "${failure.name} — ${failure.signature} (unchanged since baseline ${baseline.receiptId} on s0)"
            else -> unclassified("failed on s0 another way: ${before.signature}")
        }
    }

    /** Why a baseline did not run the set on `s0` (eligible, passed or failed with every failure identified, untruncated), or `null`. */
    private fun unusable(baseline: Receipt): String? {
        val tests = baseline.tests
        return when {
            !baseline.testedInputs.eligible -> "is not eligible"
            baseline.outcome != Outcome.Passed && baseline.outcome != Outcome.Failed -> "is ${baseline.outcome.name.lowercase()}"
            tests == null || tests.truncated -> "recorded no complete test list"
            tests.failed.isEmpty() && tests.passed.isEmpty() -> "executed no identified test"
            unidentified(baseline) -> "has failures the runner did not identify"
            else -> null
        }
    }

    /** A red run with more failures counted than identified. */
    private fun unidentified(red: Receipt): Boolean {
        val identified = red.tests?.failed?.size ?: 0
        val counted = red.parsed?.let { it.failed + it.errors }
        return if (counted == null) identified == 0 && red.outcome == Outcome.Failed else identified < counted
    }

    private const val KEY_HEX = 16
    private const val MAX_TEXT = 200
    private val FILE_LINE = Regex("""([\w.\-/\\]+\.\w+):\d+(:\d+)?""")
    private val LINE_WORD = Regex("""\bline \d+""")
    private val DURATION = Regex("""\b\d+(\.\d+)?\s?(ms|s|sec|secs|seconds)\b""")
    private val HEX_ADDRESS = Regex("""0x[0-9a-fA-F]+""")
    private val TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?""")
    private val TEMP_PATH = Regex("""(/tmp/\S+|[A-Za-z]:\\[^\s]*\\Temp\\[^\s]*)""")
}

public data class BaselineResult(
    val receipt: Receipt,
    /** `null` when the run produced no usable evidence (timeout, unavailable, unknown): nothing may be called pre-existing. */
    val ledger: PreexistingLedger?,
    val candidateDir: Path,
    val materialized: MaterializeResult,
)

/**
 * The baseline receipt (§8.5, D-53, TODO P1.7.5): the relevant suite runs on the **captured initial dirty
 * candidate** — snapshot 0 materialized under `candidates/` and verified against its manifest before the
 * run — never on the current, possibly edited tree. The exported copy is verified again afterwards, so a
 * suite that rewrote its own inputs cannot certify them (`input_stability = isolated`, D-45). The receipt
 * binds `s0`; the ledger holds the failing identities with their signatures and multiplicities.
 */
public class Baseline(
    private val shadowRef: ShadowRef,
    private val layout: Layout,
    private val runner: Runner,
    private val os: Os,
    private val receipts: Receipts,
    private val aliases: Aliases,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    private val env: EnvFingerprint,
    private val verifierVersion: String = Astrolabe.VERSION,
    private val envAllowlist: Set<String> = RedactionConfig.DEFAULT_ENV_ALLOWLIST,
    private val scratch: ScratchPolicy = ScratchPolicy(),
) {
    internal var beforeDispatch: () -> Unit = {}
    public suspend fun run(check: Check, contractVersion: Int, s0: CandidateId, timeoutSeconds: Long = 600): BaselineResult {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
        val dir = layout.candidates.resolve("${ids.work.value}-${ids.attempt.value}-s0")
        if (Files.exists(dir)) deleteTree(dir)
        val materialized = shadowRef.materialize(0, dir)
        // The candidate is the whole exported tree (HEAD plus the dirty manifest), so every exported file is a tested input.
        val before = snapshot(dir)
        val inputs = before.mapValues { it.value.version }
        val limits = ArrayList<Limit>()
        materialized.limitations.forEach { limits += Limit("materialize", it) }
        val actionId = idGen.next("act")
        val receiptId = idGen.next("rcpt")

        if (!materialized.ok) {
            limits += Limit("candidate", "exported candidate differs from its manifest: ${materialized.mismatches.joinToString(", ")}; the baseline did not run (D-53)")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated, materialized.mismatches.toSet()), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }

        val started = System.currentTimeMillis()
        val cwd = command.cwd?.let { dir.resolve(it) } ?: dir
        val logsDir = layout.campaigns.resolve(ids.work.value).resolve("logs")
        Files.createDirectories(logsDir)
        val spec = SpawnSpec(Command.Argv(command.argv), cwd, logsDir.resolve("baseline-${check.id}-$actionId.log"), EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), timeoutSeconds)
        var proc = try {
            beforeDispatch()
            runner.start(spec)
        } catch (failure: IOException) {
            limits += Limit("runner", "cannot start ${command.argv.first()}: ${failure.message}")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        val observed = io.astrolabe.tool.run.Executions.observeCancellable(os, proc, POLL_SLICE_SECONDS, timeoutSeconds)
        proc = observed.proc
        val lost = observed.lost
        if (lost) limits += Limit("observation", "the process observation was lost; reconcile before retry")

        // D-45 `isolated`: the exported candidate is verified against its manifest after the run as well.
        val after = snapshot(dir)
        // D-323: report and coverage artifacts the suite writes are outputs, not inputs; any other change withholds.
        val mutated = (before.keys + after.keys).filter { before[it] != after[it] && !reportArtifact(it) }.toSet()
        if (mutated.isNotEmpty()) limits += Limit("input_mutation", "the suite changed its own inputs in the candidate: ${mutated.sorted().joinToString(", ")}; the receipt cannot certify them")
        val redacted = redaction.applyBytes(observed.output, ContentClass.ReusableEvidence)
        val blob = blobs.put(redacted.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val alias = aliases.allocate(ids.work, receiptId, "receipt", ids.context, null).text
        val capture = RunCapture(
            actionId = actionId, argv = command.argv, shell = false, cwd = command.cwd,
            exitCode = (proc.status as? ProcStatus.Exited)?.exitCode, timedOut = proc.status == ProcStatus.DeadlineExceeded,
            output = observed.output, captureComplete = !lost && !observed.truncated && proc.status !is ProcStatus.Lost,
            reports = reports(dir, started, actionId), checkId = check.id, selector = check.selector.toString(),
            executionRoot = runCatching { cwd.toRealPath() }.getOrDefault(cwd.toAbsolutePath()).toString(),
        )
        val shaped = Shapers.shape(capture, ShapeBudget(estimator = estimator, recallAlias = alias))
        shaped.limitations.forEach { limits += Limit("shaper", it) }
        val outcome = when {
            lost || proc.status is ProcStatus.Lost -> Outcome.UnknownOutcome
            proc.status == ProcStatus.DeadlineExceeded -> Outcome.Timeout
            shaped.status == Outcome.Passed && (shaped.counts == null || (shaped.counts.executed == 0 && shaped.counts.discovered == 0)) -> Outcome.Inconclusive
            else -> shaped.status
        }
        val tests = Regressions.outcomes(shaped.tests, { redaction.apply(it, ContentClass.ReusableEvidence).text }, listOf(capture.executionRoot.orEmpty(), dir.toString()))
        val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, capture.exitCode, outcome, shaped.counts, TestedInputs(inputs, InputStability.Isolated, mutated), blob, limits, tests)
        val ledger = if (receipt.testedInputs.eligible && (outcome == Outcome.Passed || outcome == Outcome.Failed || outcome == Outcome.Inconclusive)) {
            val ledgerLimits = ArrayList<String>()
            if (shaped.tests.isEmpty() && outcome != Outcome.Passed) ledgerLimits += "no test identities parsed by ${shaped.shaper}: nothing can be called pre-existing"
            val ambiguous = TestResults.ambiguous(shaped.tests)
            if (ambiguous.isNotEmpty()) ledgerLimits += "${ambiguous.size} identities appear more than once and never match"
            val entries = shaped.tests.filter { it.failing }.groupBy { it.identity.canonical }.values
                .map { group -> PreexistingFailure(group.first().identity, PreexistingLedger.signatureOf(group.first()), group.size) }
                .sortedBy { it.identity.canonical }
            PreexistingLedger(receiptId, alias, s0, env.envId, entries, ambiguous, ledgerLimits)
        } else {
            null
        }
        return BaselineResult(receipt, ledger, dir, materialized)
    }

    private data class Input(val version: FileVersion, val modified: java.nio.file.attribute.FileTime, val executable: Boolean)

    private fun snapshot(dir: Path): Map<String, Input> = Files.walk(dir).use { files ->
        files.filter { Files.isRegularFile(it) }.toList().associate { file ->
            dir.relativize(file).toString().replace('\\', '/') to file
        }.filterKeys { !scratch.isScratch(it) }.mapValues { (_, file) ->
            Input(FileVersion.of(Files.readAllBytes(file)), Files.getLastModifiedTime(file), Files.isExecutable(file))
        }
    }

    /** Well-known test report and coverage outputs (D-323): `junit*.xml`, `TEST-*.xml`, coverage data and tool caches. */
    private fun reportArtifact(path: String): Boolean {
        val segments = path.split('/')
        val name = segments.last()
        return (name.endsWith(".xml") && (name.startsWith("junit") || name.startsWith("TEST-"))) ||
            name == ".coverage" || name == "coverage.xml" ||
            segments.dropLast(1).any { it in ARTIFACT_DIRS || it.endsWith(".egg-info") }
    }

    private fun receipt(
        receiptId: String, check: Check, contractVersion: Int, s0: CandidateId, argv: List<String>, cwd: String?, exit: Int?,
        outcome: Outcome, counts: io.astrolabe.evidence.Counts?, tested: TestedInputs, raw: Digest?, limits: List<Limit>, tests: TestOutcomes? = null,
    ): Receipt {
        val receipt = Receipt(
            receiptId = receiptId, ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds, command = argv, cwd = cwd, shell = false,
            stampBefore = s0, stampAfter = s0, envId = env.envId, verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion,
            contractVersion = contractVersion, outcome = outcome, parsed = counts, inputClosure = check.inputClosure, testedInputs = tested,
            // P8.C.10: marked, so the check's own history never reads a run on s0 as a run of the change.
            raw = raw, limits = limits + Limit(Regressions.BASELINE, "${check.id} on the captured initial candidate @${s0.hash8}"), exitCode = exit, at = clock.instant(),
            evidenceKind = check.evidenceKind, checkOrigin = check.origin, tests = tests,
        )
        receipts.record(receipt)
        return receipt
    }

    /**
     * P8.C.10 G: records, before [run], that [check]'s baseline began — an `unavailable` baseline receipt of its definition —
     * so a run that fails, throws or is interrupted is never retried in the attempt; a run that ends records its own after it.
     */
    public fun begin(check: Check, contractVersion: Int, s0: CandidateId): Receipt {
        val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
        return receipt(idGen.next("rcpt"), check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(emptyMap(), InputStability.Isolated), null,
            listOf(Limit("baseline_started", "the baseline began; a receipt of its end follows it unless it failed, threw or was interrupted")))
    }

    /** JUnit XML written by this invocation (mtime after the start): fresh, invocation-bound evidence (D-50). */
    private fun reports(dir: Path, startedMillis: Long, actionId: String): List<ReportArtifact> {
        val found = ArrayList<ReportArtifact>()
        val candidates = listOf("build/test-results", "target/surefire-reports", "target/failsafe-reports")
        try {
            Files.walk(dir, REPORT_WALK_DEPTH).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
                    .forEach { file ->
                        val relative = file.relativeTo(dir).joinToString("/") { it.toString() }
                        if (candidates.any { relative.contains(it) }) {
                            val fresh = Files.getLastModifiedTime(file).toMillis() >= startedMillis
                            found += ReportArtifact(relative, ReportKind.JUnitXml, fresh, "candidate:s0:$actionId", if (fresh) Files.readAllBytes(file) else null)
                        }
                    }
            }
        } catch (ignored: IOException) {
            // No reports directory: the shapers work from the capture alone.
        }
        return found
    }

    private fun deleteTree(dir: Path) {
        Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private companion object {
        const val POLL_SLICE_SECONDS = 5L
        const val REPORT_WALK_DEPTH = 8
        val ARTIFACT_DIRS = setOf("htmlcov", ".pytest_cache", ".nyc_output", "coverage")
    }
}
