package io.astrolabe.verify

/**
 * Strict regression state machine for CHK-tests-blast and CHK-types-touched.
 * Absent -> Held on every identified failure; unknown failures create an opaque Held obligation.
 * Held accumulates observations in durable receipt order, including incomplete and timeout runs.
 * Held -> Discharged only by a later complete, eligible, untruncated, fully parsed current-tree run
 * reporting that identity exactly once as passed and covering every held command and input closure.
 * A later failure reopens Discharged; a partial, missing, skipped or ambiguous result never discharges.
 * Remaining Held -> Inherited only when every observation is complete, unambiguous and confirmed on this tree,
 * and every complete-content fingerprint matches the same unambiguous failure on initial candidate s0.
 * Remaining Held -> New only with an explicit unique pass in a complete comparable s0 baseline.
 * Remaining Held -> Unclassified otherwise, including opaque obligations and conflicting fingerprints.
 * Inherited is disclosed without a gap or cap; New requires fix and rerun and cannot be acknowledged.
 * Unclassified is disclosed and caps provenance; a current red requires a persisted Open acknowledgement.
 * These are pure projections of persisted evidence, shared by exit, acceptance, finish and reopen.
 */

import io.astrolabe.Astrolabe
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.FailedTest
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.TestOutcomes
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Limit
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.Receipts
import io.astrolabe.evidence.ReceiptClaim
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
import io.astrolabe.tool.run.JUnitReports
import io.astrolabe.tool.run.RunCapture
import io.astrolabe.tool.run.Runner
import io.astrolabe.tool.run.ShapeBudget
import io.astrolabe.tool.run.Shapers
import io.astrolabe.tool.run.TestIdentity
import io.astrolabe.tool.run.TestOutcome
import io.astrolabe.tool.run.TestResult
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.MaterializeResult
import io.astrolabe.workspace.ShadowRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** A display-only baseline failure: redacted identity and message, plus multiplicity (D-27, D-50). */
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
public data class PreexistingLedger @JvmOverloads constructor(
    val receiptId: String,
    val alias: String?,
    val stamp: CandidateId,
    val envId: Digest,
    val entries: List<PreexistingFailure>,
    /** Opaque keys of identities that appeared more than once in the baseline: they can never match. */
    val ambiguous: Set<String>,
    val limitations: List<String> = emptyList(),
    /** Opaque, complete comparison evidence; legacy display signatures never establish inheritance or a new regression. */
    val evidence: TestOutcomes? = null,
) {
    @JvmOverloads
    public fun classify(result: TestResult, envId: Digest = this.envId, roots: List<String> = emptyList()): BaselineMatch {
        if (!result.failing) return BaselineMatch.Ambiguous("this observation is not a failure")
        if (envId != this.envId) return BaselineMatch.Ambiguous("environment ${envId.hash8} differs from the baseline's ${this.envId.hash8}")
        val baseline = evidence ?: return BaselineMatch.Ambiguous("no complete comparison evidence")
        val key = Regressions.key(result.identity)
        if (!baseline.reportComplete || baseline.truncated || baseline.multiplicity[key] != 1 || key in baseline.ambiguous) return BaselineMatch.Ambiguous("identity missing, ambiguous or incompletely reported on s0")
        val before = baseline.failed.singleOrNull { it.key == key }
        if (before == null) return if (baseline.passed.count { it == key } == 1) BaselineMatch.New else BaselineMatch.Ambiguous("not reported passed on s0")
        val now = Regressions.outcomes(listOf(result), { "" }, roots).failed.singleOrNull()
        if (!before.contentComplete || now?.contentComplete != true) return BaselineMatch.Ambiguous("complete failure content missing")
        return if (now.fingerprint == before.fingerprint) BaselineMatch.PreExisting else BaselineMatch.Changed(before.signature)
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
         * Legacy display and recovery summary (D-19), never comparison evidence for the strict regression rule.
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
 * P8.C.10: how one failure of a harness regression check ([Regressions]) relates to the baseline at `s0` (§8.5). Only
 * positive, unambiguous, complete evidence makes a failure [New] or [Inherited]; anything less is [Unclassified].
 */
public enum class RedClass {
    /** It is held on this tree and an unambiguous, complete baseline explicitly reported it passed: no `Open` item clears it. */
    New,

    /** The same identity failed on `s0` with the same fingerprint: inherited, disclosed in the finish receipt, never a gap. */
    Inherited,

    /** Anything else (no usable baseline, a changed fingerprint, an ambiguous identity, not shown on this tree, flaky, unidentified): the D-400 rule. */
    Unclassified,
}

/**
 * P8.C.10: the failures of a harness regression check not shown fixed on the tree at hand, by class — [regressions]
 * ([RedClass.New]), [inherited] and [unclassified], one line each — with [since] the receipts they come from (alias or
 * id), [current] this tree's red receipts of the check (empty when none is current: then an `Open` item is not asked
 * for), and [command] the command whose run shows them fixed.
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
 * The regressions the harness finds itself (P8.C.10): the blast radius and the types of touched files. A red stops
 * mattering in two ways only — its identity shown fixed, or its failure shown inherited — and both take positive,
 * unambiguous, complete evidence; so does [RedClass.New]. Every failure any run of the check reported in the attempt and
 * the workspace counts by its test identity until it executed and passed, alone and unambiguously, in an eligible run on
 * the tree at hand that ran to its end; it is then classified against the baseline its check recorded on `s0`. Pure over
 * receipts, in their insertion order (I-05).
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

    /** A marker receipt (a baseline begun, a rerun begun): it reports nothing of a run. */
    @JvmStatic
    public fun isMarker(receipt: Receipt): Boolean = receipt.limits.any { it.kind == RERUN || it.kind == BASELINE_STARTED }

    /**
     * What a run reported test by test (D-27): a key and a fingerprint for comparison — digests over the unredacted
     * identity and the whole failure ([fingerprint]) — and redacted text to show; every identity reported more than once,
     * whatever its outcomes, is ambiguous; skipped and expected failures are no pass. Bounded; [roots] are the run's roots.
     */
    @JvmStatic
    @JvmOverloads
    public fun outcomes(tests: List<TestResult>, redact: (String) -> String, roots: List<String>, reportComplete: Boolean = false): TestOutcomes {
        val multiplicity = tests.groupingBy { key(it.identity) }.eachCount()
        val repeated = multiplicity.filterValues { it > 1 }.keys
        val failing = tests.filter { it.failing }
        val passing = tests.filter { it.outcome == TestOutcome.Passed }
        val failed = failing.take(MAX_FAILED).map { t ->
            val first = t.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: t.outcome.name.lowercase()
            val content = buildString {
                fun field(value: String) { val normalized = neutralize(value, roots); append(normalized.length).append(':').append(normalized) }
                field(t.outcome.name)
                for (element in t.failureContent) {
                    field(element.kind)
                    append(element.attributes.size).append(':')
                    for ((name, value) in element.attributes.toSortedMap()) { field(name); field(value) }
                    field(element.body)
                }
                if (!t.failureContentComplete) field(t.detail ?: t.message ?: t.outcome.name)
            }
            FailedTest(key(t.identity), redact(t.identity.display).take(MAX_TEXT), Digest.ofUtf8(content).hex, redact(first).take(MAX_TEXT),
                contentComplete = t.failureContentComplete && t.failureContent.isNotEmpty())
        }
        return TestOutcomes(failed, passing.take(MAX_PASSED).map { key(it.identity) }, repeated.take(MAX_FAILED),
            truncated = failing.size > MAX_FAILED || passing.size > MAX_PASSED || repeated.size > MAX_FAILED || multiplicity.size > MAX_IDENTITIES,
            reportComplete = reportComplete, multiplicity = multiplicity.entries.take(MAX_IDENTITIES).associate { it.toPair() })
    }

    /** The comparison key of an identity: a digest of its canonical form, never shown. */
    @JvmStatic
    public fun key(identity: TestIdentity): String = keyOf(identity.canonical)

    private fun keyOf(canonical: String): String = Digest.ofUtf8(canonical).hex.take(KEY_HEX)

    /**
     * The comparison fingerprint of a failure (P8.C.10 D): a digest of its whole unredacted text, with
     * only the run's own root paths — and the separators of the paths under them — made neutral; every other value in it,
     * line numbers, durations and addresses included, tells two failures apart (in doubt they differ: unclassified).
     */
    @JvmStatic
    public fun fingerprint(text: String, roots: List<String>): String = Digest.ofUtf8(neutralize(text, roots)).hex

    private fun neutralize(text: String, roots: List<String>): String {
        val t = text
        val alternatives = roots.filter { it.isNotBlank() }.flatMap { listOf(it.replace('\\', '/'), it.replace('/', '\\')) }
            .map { if (it.length > 1) it.trimEnd('/', '\\') else it }.distinct().sortedByDescending { it.length }
        val matches = if (alternatives.isEmpty()) emptyList() else Regex(
            "(?<![\\w./\\\\-])(?:" + alternatives.joinToString("|") { Regex.escape(it) } + ")(?=$|[\\\\/\\s'\"`:;,()\\[\\]])([\\\\/][^\\s'\"`:;,()\\[\\]]*)?",
        ).findAll(t).toList()
        return buildString {
            fun literal(value: String) { append('L').append(value.length).append(':').append(value) }
            var at = 0
            for (match in matches) {
                literal(t.substring(at, match.range.first))
                val path = match.groupValues[1].replace('\\', '/')
                append('R').append(path.length).append(':').append(path)
                at = match.range.last + 1
            }
            literal(t.substring(at))
        }
    }

    /**
     * P8.C.10 B–C: the hold of one check on the tree [stamp] from its receipts in the attempt and the workspace ([history],
     * no baselines, insertion order) and the attempt's [baselines]. `null` when nothing is held.
     */
    @JvmStatic
    public fun hold(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?, alias: (Receipt) -> String): RegressionHold? =
        held(history, baselines, stamp)?.let { h ->
            RegressionHold(h.from.map(alias).distinct(), h.current.map { it.receiptId }, h.command, h.regressions, h.inherited, h.unclassified)
        }

    /** The red runs of [history] behind the hold on [stamp] whose definition has no receipt on it, marker included: what verify-on-stop reruns once (P8.C.10 A). */
    @JvmStatic
    public fun unconfirmed(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): List<Receipt> {
        val h = held(history, baselines, stamp) ?: return emptyList()
        val onStamp = history.filter { it.stampAfter == stamp }.map { it.checkDefinitionVersion }.toSet()
        return h.from.filter { reported(it) && it.checkDefinitionVersion !in onStamp }.reversed().distinctBy { it.checkDefinitionVersion }
    }

    /** This tree's latest eligible run with identified failures, when the check has no baseline in the attempt yet (P8.C.10 G: one per check), or `null`. */
    @JvmStatic
    public fun baselineDue(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId): Receipt? {
        if (baselines.isNotEmpty()) return null
        return history.lastOrNull { it.stampAfter == stamp && it.testedInputs.eligible && !isMarker(it) && it.tests?.failed?.isNotEmpty() == true }
    }

    private class Held(val from: List<Receipt>, val current: List<Receipt>, val command: String, val regressions: List<String>, val inherited: List<String>, val unclassified: List<String>)

    private data class Observation(val failure: FailedTest, val receipt: Receipt)

    private sealed interface IdentityState {
        data object Discharged : IdentityState
        data class Held(val observations: List<Observation>) : IdentityState
    }

    private sealed interface Evidence {
        data class Failure(val observation: Observation) : Evidence
        data class Pass(val receipt: Receipt) : Evidence
    }

    private fun transition(state: IdentityState, evidence: Evidence): IdentityState = when (evidence) {
        is Evidence.Failure -> IdentityState.Held((state as? IdentityState.Held)?.observations.orEmpty() + evidence.observation)
        is Evidence.Pass -> when (state) {
            IdentityState.Discharged -> state
            is IdentityState.Held -> state.observations.filterNot { covers(evidence.receipt, it.receipt) }
                .takeIf { it.isNotEmpty() }?.let { IdentityState.Held(it) } ?: IdentityState.Discharged
        }
    }

    /** A run that reported a failure: a red outcome, or a failing test of a run that did not finish. */
    private fun reported(r: Receipt): Boolean = !isMarker(r) &&
        (r.outcome == Outcome.Failed || r.tests?.failed?.isNotEmpty() == true || r.limits.any { it.kind == "workspace_attribution" })

    /** A run whose test list is complete evidence: eligible, finished, untruncated. */
    private fun finished(r: Receipt): Boolean =
        r.testedInputs.eligible && !isMarker(r) && (r.outcome == Outcome.Passed || r.outcome == Outcome.Failed) &&
            r.tests?.let { it.reportComplete && !it.truncated } == true

    private fun unique(tests: TestOutcomes, key: String): Boolean =
        tests.multiplicity[key] == 1 && key !in tests.ambiguous && tests.passed.count { it == key } + tests.failed.count { it.key == key } == 1

    /** Exact commands are comparable; a wider declared closure must also have tested every earlier observed input path. */
    private fun covers(fresh: Receipt, held: Receipt): Boolean {
        if (fresh.command != held.command || fresh.cwd != held.cwd || fresh.shell != held.shell || fresh.envId != held.envId) return false
        if (!fresh.testedInputs.versions.keys.containsAll(held.testedInputs.versions.keys)) return false
        if (fresh.testedInputs.workspaceComplete) return true
        return when (val previous = held.inputClosure) {
            is Closure.Known -> when (val current = fresh.inputClosure) {
                is Closure.Known -> current.paths.containsAll(previous.paths) && fresh.testedInputs.versions.keys.containsAll(previous.paths)
                is Closure.Package -> fresh.closureManifest?.completeness == io.astrolabe.evidence.ClosureCompleteness.Complete &&
                    fresh.testedInputs.versions.keys.containsAll(previous.paths) && previous.paths.all { it == current.path || it.startsWith(current.path.trimEnd('/') + "/") }
                Closure.Unknown -> false
            }
            is Closure.Package -> when (val current = fresh.inputClosure) {
                is Closure.Package -> fresh.closureManifest?.completeness == io.astrolabe.evidence.ClosureCompleteness.Complete &&
                    (previous.path == current.path || previous.path.startsWith(current.path.trimEnd('/') + "/"))
                else -> false
            }
            Closure.Unknown -> fresh.testedInputs.workspaceComplete
        }
    }

    private fun held(history: List<Receipt>, baselines: List<Receipt>, stamp: CandidateId?): Held? {
        val runs = history.filter { !isBaseline(it) && !isMarker(it) }
        val reds = runs.filter(::reported)
        if (reds.isEmpty()) return null
        val fresh = if (stamp == null) emptyList() else runs.filter { it.stampAfter == stamp }
        val identities = LinkedHashMap<String, IdentityState>()
        for (run in runs) {
            val tests = run.tests ?: continue
            if (run.stampAfter == stamp && finished(run) && !unidentified(run)) {
                for (key in tests.passed.filter { unique(tests, it) }) {
                    identities[key] = transition(identities[key] ?: IdentityState.Discharged, Evidence.Pass(run))
                }
            }
            for (failure in tests.failed) {
                identities[failure.key] = transition(identities[failure.key] ?: IdentityState.Discharged, Evidence.Failure(Observation(failure, run)))
            }
        }
        val from = LinkedHashSet<Receipt>()
        val regressions = ArrayList<String>()
        val inherited = ArrayList<String>()
        val unclassified = ArrayList<String>()
        for ((key, state) in identities) {
            if (state !is IdentityState.Held) continue
            val observations = state.observations
            observations.forEach { from += it.receipt }
            val (failure, red) = observations.first()
            val now = observations.filter { it.receipt.stampAfter == stamp }
            if (now.isEmpty()) {
                val reason = when {
                    fresh.isEmpty() -> ", not rerun on this tree"
                    fresh.none(::finished) -> "; the run on this tree did not finish"
                    fresh.any { key in it.tests?.ambiguous.orEmpty() || (it.tests?.multiplicity?.get(key) ?: 0) > 1 } -> "; reported more than once on this tree: ambiguous"
                    fresh.any { key in it.tests?.passed.orEmpty() } -> "; the passing run did not cover the held command and inputs"
                    else -> ", not executed on this tree (removed, skipped or renamed)"
                }
                unclassified += "${failure.name}: failed in ${red.receiptId}$reason"
                continue
            }
            val classified = observations.map { observation ->
                classify(observation.failure, observation.receipt,
                    baselines.lastOrNull { it.checkDefinitionVersion == observation.receipt.checkDefinitionVersion })
            }
            val provenNew = observations.zip(classified).firstOrNull { (observation, classification) ->
                observation.receipt.stampAfter == stamp && classification.first == RedClass.New
            }?.second
            when {
                provenNew != null -> regressions += provenNew.second
                observations.map { it.failure.fingerprint }.distinct().size > 1 -> unclassified += "${failure.name}: conflicting failures remain held"
                classified.all { it.first == RedClass.Inherited } && observations.all { old -> old.receipt.stampAfter == stamp || now.any { covers(it.receipt, old.receipt) } } -> inherited += classified.first().second
                else -> unclassified += classified.firstOrNull { it.first == RedClass.Unclassified }?.second
                    ?: "${failure.name}: the current run does not classify every held failure"
            }
        }
        // P8.C.10 B: failures a run counted but did not identify (or cut from its record) stay: nothing shows them fixed one by one.
        for (red in reds.filter(::unidentified)) {
            unclassified += "failures of ${red.receiptId} the runner did not identify one by one"
            from += red
        }
        if (from.isEmpty()) return null
        val current = fresh.filter { it in from && it.testedInputs.eligible && reported(it) }
        val last = current.lastOrNull() ?: from.last()
        return Held(from.toList(), current, (last.command + listOfNotNull(last.cwd?.let { "(in $it)" })).joinToString(" "), regressions, inherited, unclassified)
    }

    /** P8.C.10 C, one failing identity of [red] (eligible, on this tree) against [baseline]: New and Inherited on positive evidence only. */
    private fun classify(failure: FailedTest, red: Receipt, baseline: Receipt?): Pair<RedClass, String> {
        fun unclassified(why: String) = RedClass.Unclassified to "${failure.name} — ${failure.signature} ($why)"
        if (!red.testedInputs.eligible) return unclassified("failed on this tree in a run that cannot certify it")
        if (!finished(red)) return unclassified("the run did not finish with a complete parsed report and lists")
        if (!unique(red.tests!!, failure.key)) return unclassified("reported more than once or without multiplicity: ambiguous")
        if (baseline == null) return unclassified(NO_BASELINE)
        unusable(baseline)?.let { return unclassified("baseline ${baseline.receiptId} $it: $NO_BASELINE") }
        if (red.envId != baseline.envId) return unclassified("environment ${red.envId.hash8} differs from the baseline's ${baseline.envId.hash8}")
        val s0 = baseline.tests!!
        if (!unique(s0, failure.key)) return unclassified(if (failure.key in s0.ambiguous || (s0.multiplicity[failure.key] ?: 0) > 1) "reported more than once: ambiguous" else "baseline ${baseline.receiptId} stopped before showing it on s0")
        val before = s0.failed.firstOrNull { it.key == failure.key }
        return when {
            before != null && (!before.contentComplete || !failure.contentComplete) -> unclassified("complete failure content was not captured")
            before != null && before.fingerprint == failure.fingerprint -> RedClass.Inherited to "${failure.name} — ${failure.signature} (unchanged since baseline ${baseline.receiptId} on s0)"
            before != null -> unclassified("failed on s0 another way: ${before.signature}")
            failure.key in s0.passed -> RedClass.New to "${failure.name} — ${failure.signature} (passed on s0 in baseline ${baseline.receiptId})"
            else -> unclassified("baseline ${baseline.receiptId} stopped before showing it on s0")
        }
    }

    /** Why a baseline is no evidence (not eligible, not finished, an incomplete or unidentified test list), or `null`. */
    private fun unusable(baseline: Receipt): String? {
        val tests = baseline.tests
        return when {
            !baseline.testedInputs.eligible -> "is not eligible"
            baseline.outcome != Outcome.Passed && baseline.outcome != Outcome.Failed -> "is ${baseline.outcome.name.lowercase()}"
            tests == null || tests.truncated || !tests.reportComplete -> "recorded no complete test list"
            unidentified(baseline) -> "has failures the runner did not identify"
            else -> null
        }
    }

    /** A red run with more failures counted than identified, a cut failure list, or a red outcome with none identified. */
    private fun unidentified(red: Receipt): Boolean {
        val identified = red.tests?.failed?.size ?: 0
        val counted = red.parsed?.let { it.failed + it.errors }
        return when {
            red.limits.any { it.kind == "workspace_attribution" } -> true
            identified == 0 && red.outcome == Outcome.Failed -> true
            reported(red) && red.tests?.reportComplete == false -> true
            red.tests?.truncated == true && identified >= MAX_FAILED -> true
            counted != null -> identified < counted
            else -> identified == 0 && red.outcome == Outcome.Failed
        }
    }

    /** The limit kind of a baseline's start marker ([Baseline.begin]). */
    internal const val BASELINE_STARTED: String = "baseline_started"
    private const val KEY_HEX = 64
    private const val MAX_IDENTITIES = MAX_FAILED + MAX_PASSED
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
    public suspend fun run(check: Check, contractVersion: Int, s0: CandidateId, timeoutSeconds: Long = 600): BaselineResult {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
        val actionId = idGen.next("act")
        val receiptId = idGen.next("rcpt")
        val dir = layout.candidates.resolve(actionId)
        val materialized = shadowRef.materialize(0, dir)
        // The candidate is the whole exported tree (HEAD plus the dirty manifest), so every exported file is a tested input.
        val before = snapshot(dir)
        val inputs = before.mapValues { it.value.version }
        val limits = ArrayList<Limit>()
        materialized.limitations.forEach { limits += Limit("materialize", it) }

        if (!materialized.ok) {
            limits += Limit("candidate", "exported candidate differs from its manifest: ${materialized.mismatches.joinToString(", ")}; the baseline did not run (D-53)")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated, materialized.mismatches.toSet()), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }

        val cwd = command.cwd?.let { dir.resolve(it) } ?: dir
        val logsDir = layout.root.resolve("logs")
        Files.createDirectories(logsDir)
        val reports = JUnitReports(dir, actionId)
        try {
            reports.prepare(logsDir.resolve("baseline-reports-$actionId"))
        } catch (failure: IOException) {
            limits += Limit("reports", "cannot prepare baseline reports: ${failure.message}")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        // P8.C.10 (C3r): the time a minutes limit leaves is read again here, after the export, just before the process starts.
        beforeDispatch()
        val left = timeLeft()
        if (left != null && left <= 0) {
            limits += Limit("runner", io.astrolabe.budget.NO_ACTIVE_TIME)
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        val deadline = if (left == null) timeoutSeconds else minOf(timeoutSeconds, left)
        val spec = SpawnSpec(Command.Argv(command.argv), cwd, logsDir.resolve("baseline-${check.id}-$actionId.log"), EnvPolicy(inheritedNames = envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1")), deadline)
        var proc = try {
            runner.start(spec)
        } catch (failure: IOException) {
            limits += Limit("runner", "cannot start ${command.argv.first()}: ${failure.message}")
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
        val redacted = redaction.applyBytes(observed.output, ContentClass.ReusableEvidence)
        val blob = blobs.put(redacted.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val alias = aliases.allocate(ids.work, receiptId, "receipt", ids.context, null).text
        val collected = try { reports.collect() } catch (failure: IOException) {
            limits += Limit("reports", "cannot capture baseline reports: ${failure.message}")
            null
        }
        val capture = RunCapture(
            actionId = actionId, argv = command.argv, shell = false, cwd = command.cwd,
            exitCode = (proc.status as? ProcStatus.Exited)?.exitCode, timedOut = proc.status == ProcStatus.DeadlineExceeded,
            output = observed.output, captureComplete = collected != null && !lost && !observed.truncated && proc.status !is ProcStatus.Lost,
            reports = collected.orEmpty(), checkId = check.id, selector = check.selector.toString(),
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
        val tests = Regressions.outcomes(shaped.tests, { redaction.apply(it, ContentClass.ReusableEvidence).text }, listOf(capture.executionRoot.orEmpty(), dir.toString()),
            reportComplete = shaped.reportComplete && capture.captureComplete && !shaped.captureTruncated)
        val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, capture.exitCode, outcome, shaped.counts, TestedInputs(inputs, InputStability.Isolated, mutated, workspaceComplete = mutated.isEmpty()), blob, limits, tests)
        val ledger = if (receipt.testedInputs.eligible && (outcome == Outcome.Passed || outcome == Outcome.Failed || outcome == Outcome.Inconclusive)) {
            val ledgerLimits = ArrayList<String>()
            if (shaped.tests.isEmpty() && outcome != Outcome.Passed) ledgerLimits += "no test identities parsed by ${shaped.shaper}: nothing can be called pre-existing"
            val ambiguous = tests.ambiguous.toSet()
            if (ambiguous.isNotEmpty()) ledgerLimits += "${ambiguous.size} identities appear more than once and never match"
            val entries = shaped.tests.filter { it.failing }.groupBy { it.identity.canonical }.values
                .map { group ->
                    val test = group.first()
                    val display = redaction.apply(test.identity.display, ContentClass.ReusableEvidence).text
                    val summary = redaction.apply(test.message?.lineSequence()?.firstOrNull() ?: test.outcome.name.lowercase(), ContentClass.ReusableEvidence).text
                    PreexistingFailure(TestIdentity(name = display), summary, group.size)
                }
                .sortedBy { it.identity.canonical }
            PreexistingLedger(receiptId, alias, s0, env.envId, entries, ambiguous, ledgerLimits, tests)
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
        persist: Boolean = true,
    ): Receipt {
        val receipt = Receipt(
            receiptId = receiptId, ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds, command = argv, cwd = cwd, shell = false,
            stampBefore = s0, stampAfter = s0, envId = env.envId, verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion,
            contractVersion = contractVersion, outcome = outcome, parsed = counts, inputClosure = check.inputClosure, testedInputs = tested,
            // P8.C.10: marked, so the check's own history never reads a run on s0 as a run of the change.
            raw = raw, limits = limits + Limit(Regressions.BASELINE, "${check.id} on the captured initial candidate @${s0.hash8}"), exitCode = exit, at = clock.instant(),
            evidenceKind = check.evidenceKind, checkOrigin = check.origin, tests = tests,
        )
        if (persist) receipts.record(receipt)
        return receipt
    }

    /**
     * P8.C.10 G: records, before [run], that [check]'s baseline began — an `unavailable` baseline receipt of its definition —
     * so a run that fails, throws or is interrupted is never retried in the attempt; returns existing evidence if claimed.
     * Automatic dispatch must use [tryBegin] to distinguish a new claim from a previous one.
     */
    public fun begin(check: Check, contractVersion: Int, s0: CandidateId): Receipt {
        return tryBegin(check, contractVersion, s0) ?: receipts.forCheck(check.id).first {
            it.ids.work == ids.work && it.ids.attempt == ids.attempt && Regressions.isBaseline(it)
        }
    }

    /** Atomically consumes this attempt's automatic baseline allowance; null means another dispatch already claimed it. */
    public fun tryBegin(check: Check, contractVersion: Int, s0: CandidateId): Receipt? {
        val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
        val marker = receipt(idGen.next("rcpt"), check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(emptyMap(), InputStability.Isolated), null,
            listOf(Limit(Regressions.BASELINE_STARTED, "the baseline began; a receipt of its end follows it unless it failed, threw or was interrupted")), persist = false)
        return marker.takeIf { receipts.claim(it, ReceiptClaim.Baseline) }
    }

    private companion object {
        const val POLL_SLICE_SECONDS = 5L
        val ARTIFACT_DIRS = setOf("htmlcov", ".pytest_cache", ".nyc_output", "coverage")
    }
}
