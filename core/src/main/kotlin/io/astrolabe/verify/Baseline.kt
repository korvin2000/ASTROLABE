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
 * P8.C.10: what the baseline at `s0` (§8.5) shows of one failure of a harness regression check ([Regressions]) that is
 * not shown fixed. Only a fix shown on the tree at hand leaves no trace; every class here caps the campaign at `unverified`.
 */
public enum class RedClass {
    /** It fails on this tree, and a finished baseline on `s0` reported it once, passed: a regression no `Open` item clears. */
    New,

    /** A finished baseline on `s0` reported it failing: the runtime acknowledges it — no `Open` item — and discloses it. */
    FailedBefore,

    /** Anything else (no finished baseline, another environment, not or ambiguously reported, not shown on this tree, unidentified): the D-400 rule. */
    Unknown,
}

/**
 * P8.C.10: the failures of a harness regression check not shown fixed on the tree at hand, by class — [regressions]
 * ([RedClass.New]), [failedBefore] and [unknown], one line each — with [since] the receipts they come from (alias or id),
 * [current] this tree's eligible red receipts of the check (empty when none is current: then no `Open` item is asked for),
 * and [command] the command whose run shows them fixed.
 */
public data class RegressionHold(
    val since: List<String>,
    val current: List<String>,
    val command: String,
    val regressions: List<String> = emptyList(),
    val failedBefore: List<String> = emptyList(),
    val unknown: List<String> = emptyList(),
) {
    /** The worst class held: a regression, else an unknown failure, else failures that failed before the change too. */
    val kind: RedClass get() = when {
        regressions.isNotEmpty() -> RedClass.New
        unknown.isNotEmpty() -> RedClass.Unknown
        else -> RedClass.FailedBefore
    }
}

/**
 * The regressions the harness finds itself (P8.C.10): the blast radius and the types of touched files. A red stops
 * mattering in one way only — its test identity reported once, passed, by an eligible run on the tree at hand that
 * finished with a complete record; until then every failure any run of the check reported in the attempt and the
 * workspace is held. A held failure is new only on the same kind of evidence from `s0` (reported once, passed, by a
 * finished baseline), acknowledged by the runtime when `s0` reported it failing, and unknown otherwise; no failure text is
 * ever compared. Pure over receipts, in their insertion order (I-05).
 */
public object Regressions {
    /** The checks the harness runs itself over the change: the blast radius and the types of touched files. */
    @JvmField
    public val CHECKS: Set<String> = setOf(Checks.TESTS_BLAST, Checks.TYPES_TOUCHED)

    /** The limit kind that marks a baseline receipt: one run of a check's command on the captured initial candidate. */
    public const val BASELINE: String = "baseline"

    /** The limit kind that marks the stop's rerun of a red command on a tree as begun (P8.C.10): never a run of the change. */
    public const val RERUN: String = "rerun_started"

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
     * P8.C.10 F: a regression check as its last run [receipt] defined it, with that run as its last result — for a reopened
     * attempt whose runner discovery no longer registers it (a removed `mypy.ini`): its hold, and the cap, come from history.
     */
    @JvmStatic
    public fun restored(receipt: Receipt): Check = if (receipt.checkId == Checks.TESTS_BLAST) Blast.restored(receipt) else
        Check(receipt.checkId, CheckKind.Type, Selector.Touched, receipt.inputClosure, CostClass.Fast, Trigger.OnDemand,
            command = io.astrolabe.contract.Command(receipt.command, receipt.cwd), origin = io.astrolabe.contract.Origin.Harness)
            .copy(last = LastResult(receipt.receiptId, receipt.stampAfter, receipt.checkDefinitionVersion, receipt.outcome, receipt.parsed, Applicability.Current))

    /** A marker receipt (a baseline begun, a rerun begun): it reports nothing of a run. */
    @JvmStatic
    public fun isMarker(receipt: Receipt): Boolean = receipt.limits.any { it.kind == RERUN || it.kind == BASELINE_STARTED }

    /**
     * What a run in the directory [cwd] reported test by test (D-27): a key per identity — a digest of its canonical form
     * and of the run's directory, so one-named tests of two packages never meet — and redacted text to show; every identity
     * reported more than once, whatever its outcomes, is ambiguous; a skipped or expected failure is no pass. Bounded;
     * [complete] false when the capture or the structured report could not be read whole.
     */
    @JvmStatic
    @JvmOverloads
    public fun outcomes(tests: List<TestResult>, redact: (String) -> String, complete: Boolean = true, cwd: String? = null): TestOutcomes {
        val keys = tests.map { key(it.identity, cwd, it.runnerFile) }
        val repeated = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val failing = tests.indices.filter { tests[it].failing }
        val passing = tests.indices.filter { tests[it].outcome == TestOutcome.Passed }
        val failed = failing.take(MAX_FAILED).map { i ->
            val t = tests[i]
            val first = t.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: t.outcome.name.lowercase()
            FailedTest(keys[i], redact(t.identity.display).take(MAX_TEXT), redact(first).take(MAX_TEXT))
        }
        return TestOutcomes(failed, passing.take(MAX_PASSED).map { keys[it] }, repeated.take(MAX_FAILED),
            truncated = failing.size > MAX_FAILED || passing.size > MAX_PASSED || repeated.size > MAX_FAILED, incomplete = !complete)
    }

    /**
     * The comparison key of an identity run in [cwd] — and, when the runner named it beside the identity, in [runnerFile]:
     * a digest of its canonical form, the file and the normalized directory, never shown.
     */
    @JvmStatic
    @JvmOverloads
    public fun key(identity: TestIdentity, cwd: String? = null, runnerFile: String? = null): String =
        keyOf(identity.canonical + (runnerFile?.let { "|file=" + it.replace('\\', '/') } ?: ""), cwd)

    private fun keyOf(canonical: String, cwd: String?): String {
        val dir = cwd?.replace('\\', '/')?.trim()?.trimEnd('/')?.removePrefix("./")?.takeUnless { it.isEmpty() || it == "." }.orEmpty()
        return Digest.ofUtf8("$canonical|cwd=$dir").hex.take(KEY_HEX)
    }

    /**
     * [argv] asking the runner to list its passed tests too, where the harness knows a safe way: a direct `pytest` call (or
     * `python -m pytest`) without its own `-r` report option gets `-rA`; anything else is left as it is.
     */
    @JvmStatic
    public fun listingPasses(argv: List<String>): List<String> {
        fun base(token: String) = token.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe").removeSuffix(".cmd").removeSuffix(".bat")
        val pytest = argv.firstOrNull()?.let(::base) in setOf("pytest", "py.test") ||
            (argv.firstOrNull()?.let(::base)?.startsWith("python") == true && argv.getOrNull(1) == "-m" && argv.getOrNull(2) == "pytest")
        return if (pytest && argv.none { it.startsWith("-r") }) argv + "-rA" else argv
    }

    /**
     * P8.C.10: the hold of one check on the tree [stamp] from its receipts in the attempt and the workspace ([history],
     * no baselines, insertion order) and the attempt's [baselines] (one per check). `null` when nothing is held.
     */
    @JvmStatic
    public fun hold(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?, alias: (Receipt) -> String): RegressionHold? =
        held(history, baselines, stamp)?.let { h ->
            RegressionHold(h.from.map(alias).distinct(), h.current.map { it.receiptId }, h.command, h.regressions, h.failedBefore, h.unknown)
        }

    /**
     * The red runs of [history] behind the hold on [stamp] whose definition has no eligible receipt or marker on it — what
     * verify-on-stop reruns once (P8.C.10 A), latest first, one per definition.
     */
    @JvmStatic
    public fun unconfirmed(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): List<Receipt> {
        val h = held(history, baselines, stamp) ?: return emptyList()
        val onStamp = history.filter { it.stampAfter == stamp && (it.testedInputs.eligible || isMarker(it)) }.map { it.checkDefinitionVersion }.toSet()
        return h.from.filter { reported(it) && it.checkDefinitionVersion !in onStamp }.reversed().distinctBy { it.checkDefinitionVersion }
    }

    /** This tree's latest eligible run with identified failures, when the check has no baseline in the attempt yet (P8.C.10 G: one per check), or `null`. */
    @JvmStatic
    public fun baselineDue(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): Receipt? {
        if (baselines.isNotEmpty()) return null
        return history.lastOrNull { it.stampAfter == stamp && it.testedInputs.eligible && !isMarker(it) && it.tests?.failed?.isNotEmpty() == true }
    }

    private class Held(val from: List<Receipt>, val current: List<Receipt>, val command: String, val regressions: List<String>, val failedBefore: List<String>, val unknown: List<String>)

    /** A run that reported a failure: a red outcome, or a failing test of a run that did not finish. */
    private fun reported(r: Receipt): Boolean = !isMarker(r) && (r.outcome == Outcome.Failed || r.tests?.failed?.isNotEmpty() == true)

    /** A run whose test list is complete evidence: eligible, finished, neither cut nor read in part. */
    private fun finished(r: Receipt): Boolean =
        r.testedInputs.eligible && !isMarker(r) && (r.outcome == Outcome.Passed || r.outcome == Outcome.Failed) && r.tests?.truncated == false && r.tests.incomplete == false

    /** Reported exactly once, passed, by [r]. */
    private fun passedOnce(r: Receipt, key: String): Boolean = r.tests!!.let { key in it.passed && key !in it.ambiguous && it.failed.none { f -> f.key == key } }

    private fun held(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?): Held? {
        val runs = history.filter { !isBaseline(it) && !isMarker(it) }
        val reds = runs.filter(::reported)
        if (reds.isEmpty()) return null
        val fresh = if (stamp == null) emptyList() else runs.filter { it.stampAfter == stamp }
        val finishedNow = fresh.filter(::finished)
        val failures = LinkedHashMap<String, Pair<FailedTest, Receipt>>()
        for (red in reds) for (failure in red.tests?.failed.orEmpty()) failures[failure.key] = failure to red
        // On this tree: a failure any run reported, and the latest eligible run that reported it (P8.C.10: any such run is evidence).
        val failingNow = LinkedHashMap<String, Pair<FailedTest, Receipt>>()
        val failingEligible = LinkedHashMap<String, Pair<FailedTest, Receipt>>()
        for (run in fresh) for (failure in run.tests?.failed.orEmpty()) {
            failingNow[failure.key] = failure to run
            if (run.testedInputs.eligible) failingEligible[failure.key] = failure to run
        }
        val baseline = baselines.lastOrNull()
        val from = LinkedHashSet<Receipt>()
        val regressions = ArrayList<String>()
        val failedBefore = ArrayList<String>()
        val unknown = ArrayList<String>()
        for ((key, earlier) in failures) {
            val (failure, red) = earlier
            // P8.C.10 1: shown fixed — reported once, passed, by a finished eligible run on this tree, failing in none there.
            if (failingNow[key] == null && finishedNow.any { passedOnce(it, key) }) continue
            val eligible = failingEligible[key]
            val source = eligible?.second ?: failingNow[key]?.second ?: red
            from += source
            val usable = baseline?.takeIf { finished(it) && it.envId == source.envId }
            val s0 = usable?.tests
            when {
                s0 != null && s0.failed.any { it.key == key } -> failedBefore += "failed before the change too: ${failure.name}"
                eligible != null && key !in eligible.second.tests!!.ambiguous && usable != null && passedOnce(usable, key) ->
                    regressions += "${failure.name} — ${failure.signature} (passed on s0 in baseline ${usable.receiptId})"
                else -> unknown += "${failure.name}: " + why(key, eligible, failingNow[key], red, fresh, finishedNow, baseline, s0)
            }
        }
        // Failures a run counted but did not identify (or cut from its record) stay: nothing shows them fixed one by one —
        // except for the types of touched files, whose own passed run on this tree, of the same definition, is the trace.
        for (red in reds.filter(::unidentified)) {
            if (red.checkId == Checks.TYPES_TOUCHED && fresh.any { it.testedInputs.eligible && it.outcome == Outcome.Passed && it.checkDefinitionVersion == red.checkDefinitionVersion }) continue
            unknown += if (red.checkId == Checks.TYPES_TOUCHED) "failures of ${red.receiptId}: the typecheck names no failures one by one; a passed run of it on this tree shows them fixed"
                else "failures of ${red.receiptId} the runner did not identify one by one"
            from += red
        }
        if (from.isEmpty()) return null
        val current = fresh.filter { it.testedInputs.eligible && reported(it) }
        val last = current.lastOrNull() ?: from.last()
        return Held(from.toList(), current, (last.command + listOfNotNull(last.cwd?.let { "(in $it)" })).joinToString(" "), regressions, failedBefore, unknown)
    }

    /** Why a held failure is unknown: what is missing of the evidence a fix or a regression would need. */
    private fun why(
        key: String, eligible: Pair<FailedTest, Receipt>?, any: Pair<FailedTest, Receipt>?, red: Receipt, fresh: List<Receipt>, finishedNow: List<Receipt>,
        baseline: Receipt?, s0: TestOutcomes?,
    ): String = when {
        any != null && eligible == null -> "failed on this tree only in a run that cannot certify it"
        eligible != null && key in eligible.second.tests!!.ambiguous -> "reported more than once on this tree: ambiguous"
        eligible != null && finishedNow.any { key in it.tests!!.passed } -> "failed and passed on this tree (flaky)"
        eligible != null && baseline == null -> NO_BASELINE
        eligible != null && s0 == null -> "baseline ${baseline!!.receiptId} ${unfinished(baseline, eligible.second)}: $NO_BASELINE"
        eligible != null && key in s0!!.ambiguous -> "reported more than once on s0: ambiguous"
        eligible != null -> "not reported on s0 by baseline ${baseline!!.receiptId}"
        fresh.isEmpty() -> "failed in ${red.receiptId}, not rerun on this tree"
        finishedNow.isEmpty() && fresh.any { it.testedInputs.eligible && it.tests?.truncated == true && (it.outcome == Outcome.Passed || it.outcome == Outcome.Failed) } ->
            "failed in ${red.receiptId}; the run on this tree passed more tests than the record keeps ($MAX_PASSED)"
        finishedNow.isEmpty() -> "failed in ${red.receiptId}; the run on this tree did not finish with a complete record"
        finishedNow.any { key in it.tests!!.passed } -> "failed in ${red.receiptId}; reported more than once on this tree: ambiguous"
        finishedNow.all { it.tests!!.passed.isEmpty() && (it.parsed?.passed ?: 0) > 0 } -> "failed in ${red.receiptId}; the runner lists no passed tests"
        else -> "failed in ${red.receiptId}, not executed on this tree (removed, skipped or renamed)"
    }

    /** Why [baseline] is no evidence for [run]'s failures. */
    private fun unfinished(baseline: Receipt, run: Receipt): String = when {
        !baseline.testedInputs.eligible -> "is not eligible"
        baseline.outcome != Outcome.Passed && baseline.outcome != Outcome.Failed -> "is ${baseline.outcome.name.lowercase()}"
        baseline.tests == null || baseline.tests.truncated || baseline.tests.incomplete -> "recorded no complete test list"
        baseline.envId != run.envId -> "ran in environment ${baseline.envId.hash8}, not ${run.envId.hash8}"
        else -> "did not finish"
    }

    /** A red run with more failures counted than identified, a cut failure list, or a red outcome with none identified. */
    private fun unidentified(red: Receipt): Boolean {
        val identified = red.tests?.failed?.size ?: 0
        val counted = red.parsed?.let { it.failed + it.errors }
        return when {
            red.tests?.truncated == true && identified >= MAX_FAILED -> true
            counted != null -> identified < counted
            else -> identified == 0 && red.outcome == Outcome.Failed
        }
    }

    /** The limit kind of a baseline's start marker ([Baseline.begin]). */
    internal const val BASELINE_STARTED: String = "baseline_started"
    private const val KEY_HEX = 16
    private const val MAX_TEXT = 200
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

    /** C3r: the whole seconds of active time a minutes limit leaves, read just before the process starts; `null` without one. */
    internal var timeLeft: () -> Long? = { null }

    /** Where the check's `gradle` is looked up before the wrapper replaces it (P8.C.15). */
    internal var hostProbe: io.astrolabe.atlas.HostProbe = io.astrolabe.atlas.HostProbe.system()

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
        // P8.C.10 (C3r): the time a minutes limit leaves is read again here, after the export, just before the process starts.
        val left = timeLeft()
        if (left != null && left <= 0) {
            limits += Limit("runner", io.astrolabe.budget.NO_ACTIVE_TIME)
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        val deadline = if (left == null) timeoutSeconds else minOf(timeoutSeconds, left)
        // P8.C.15: `gradle` off PATH runs through the candidate's own wrapper, as the check does in the workspace.
        val plan = GradleWrapper.plan(command.argv, dir, cwd, hostProbe)
        if (plan is GradleWrapper.Plan.Wrapped) limits += Limit("runner", plan.note)
        val spec = SpawnSpec(Command.Argv(plan.argv), cwd, logsDir.resolve("baseline-${check.id}-$actionId.log"), EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), deadline)
        var proc = try {
            beforeDispatch()
            runner.start(spec)
        } catch (failure: IOException) {
            if (plan is GradleWrapper.Plan.Missing) limits += Limit(Scheduler.UNAVAILABLE, plan.reason)
            limits += Limit(Scheduler.UNAVAILABLE, "cannot start ${command.argv.first()}: ${failure.message}")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        val observed = io.astrolabe.tool.run.Executions.observeCancellable(os, proc, POLL_SLICE_SECONDS, deadline)
        proc = observed.proc
        val lost = observed.lost
        if (lost) limits += Limit("observation", "the process observation was lost; reconcile before retry")

        // D-45 `isolated`: the exported candidate is verified against its manifest after the run as well.
        val after = snapshot(dir)
        // D-323: report and coverage artifacts the suite writes are outputs, not inputs; any other change withholds.
        val mutated = (before.keys + after.keys).filter { before[it] != after[it] && !reportArtifact(it) }.toSet()
        if (mutated.isNotEmpty()) limits += Limit("input_mutation", "the suite changed its own inputs in the candidate: ${mutated.sorted().joinToString(", ")}; the receipt cannot certify them")
        // D-390 (P8.C.10): the capture is a live stream, so a key block it opens and never closes stays hidden in the stored log.
        val redacted = redaction.applyLive(observed.output, ContentClass.ReusableEvidence, openAtEnd = false)
        val blob = blobs.put(redacted.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val alias = aliases.allocate(ids.work, receiptId, "receipt", ids.context, null).text
        val capture = RunCapture(
            actionId = actionId, argv = plan.argv, shell = false, cwd = command.cwd,
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
        if (plan is GradleWrapper.Plan.Missing && outcome == Outcome.Unavailable) limits += Limit(Scheduler.UNAVAILABLE, plan.reason)
        val tests = Regressions.outcomes(shaped.tests, { redaction.apply(it, ContentClass.ReusableEvidence).text }, complete = capture.captureComplete && !shaped.captureTruncated && !shaped.evidenceIncomplete, cwd = command.cwd)
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
            listOf(Limit(Regressions.BASELINE_STARTED, "the baseline began; a receipt of its end follows it unless it failed, threw or was interrupted")))
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
