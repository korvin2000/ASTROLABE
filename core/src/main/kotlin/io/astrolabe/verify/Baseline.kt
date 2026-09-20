package io.astrolabe.verify

import io.astrolabe.Astrolabe
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.evidence.Aliases
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
            return first.replace(HEX_ADDRESS, "0x…").replace(LONG_HEX, "<hex>").replace(TIMESTAMP, "<time>").replace(TEMP_PATH, "<tmp>")
        }
    }
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
    public suspend fun run(check: Check, contractVersion: Int, s0: CandidateId, timeoutSeconds: Long = 600): BaselineResult {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        val command = requireNotNull(check.command) { "check ${check.id} declares no command" }
        val dir = layout.candidates.resolve("${ids.work.value}-${ids.attempt.value}-s0")
        if (Files.exists(dir)) deleteTree(dir)
        val materialized = shadowRef.materialize(0, dir)
        // The candidate is the whole exported tree (HEAD plus the dirty manifest), so every exported file is a tested input.
        val inputs = materialized.files.filterNot { scratch.isScratch(it) }.sorted().mapNotNull { path ->
            val file = dir.resolve(path)
            if (Files.isRegularFile(file)) path to FileVersion.of(Files.readAllBytes(file)) else null
        }.toMap()
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
            runner.start(spec)
        } catch (failure: IOException) {
            limits += Limit("runner", "cannot start ${command.argv.first()}: ${failure.message}")
            val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, null, Outcome.Unavailable, null, TestedInputs(inputs, InputStability.Isolated), null, limits)
            return BaselineResult(receipt, null, dir, materialized)
        }
        val output = java.io.ByteArrayOutputStream()
        var cursor = 0L
        var lost = false
        try {
            while (!proc.status.isTerminal) {
                val poll = os.poll(proc, cursor, minOf(POLL_SLICE_SECONDS, timeoutSeconds))
                output.write(poll.newBytes)
                cursor = poll.nextCursorBytes
                proc = proc.copy(status = poll.status)
            }
            output.write(os.poll(proc, cursor, 0).newBytes)
        } catch (failure: IOException) {
            lost = true
            limits += Limit("observation", "the observation was lost: ${failure.message}")
        }

        // D-45 `isolated`: the exported candidate is verified against its manifest after the run as well.
        val mutated = inputs.keys.filter { path ->
            val file = dir.resolve(path)
            !Files.isRegularFile(file) || FileVersion.of(Files.readAllBytes(file)) != inputs.getValue(path)
        }.toSet()
        if (mutated.isNotEmpty()) limits += Limit("input_mutation", "the suite changed its own inputs in the candidate: ${mutated.sorted().joinToString(", ")}; the receipt cannot certify them")
        val redacted = redaction.applyBytes(output.toByteArray(), ContentClass.ReusableEvidence)
        val blob = blobs.put(redacted.text.toByteArray(Charsets.UTF_8), BlobKind.LOG, ids)
        val alias = aliases.allocate(ids.work, receiptId, "receipt", ids.context, null).text
        val capture = RunCapture(
            actionId = actionId, argv = command.argv, shell = false, cwd = command.cwd,
            exitCode = (proc.status as? ProcStatus.Exited)?.exitCode, timedOut = proc.status == ProcStatus.DeadlineExceeded,
            output = output.toByteArray(), captureComplete = !lost && proc.status !is ProcStatus.Lost,
            reports = reports(dir, started, actionId), checkId = check.id, selector = check.selector.toString(),
        )
        val shaped = Shapers.shape(capture, ShapeBudget(estimator = estimator, recallAlias = alias))
        shaped.limitations.forEach { limits += Limit("shaper", it) }
        val outcome = when {
            lost || proc.status is ProcStatus.Lost -> Outcome.UnknownOutcome
            proc.status == ProcStatus.DeadlineExceeded -> Outcome.Timeout
            shaped.status == Outcome.Passed && (shaped.counts == null || (shaped.counts.executed == 0 && shaped.counts.discovered == 0)) -> Outcome.Inconclusive
            else -> shaped.status
        }
        val receipt = receipt(receiptId, check, contractVersion, s0, command.argv, command.cwd, capture.exitCode, outcome, shaped.counts, TestedInputs(inputs, InputStability.Isolated, mutated), blob, limits)
        val ledger = if (outcome == Outcome.Passed || outcome == Outcome.Failed || outcome == Outcome.Inconclusive) {
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

    private fun receipt(
        receiptId: String, check: Check, contractVersion: Int, s0: CandidateId, argv: List<String>, cwd: String?, exit: Int?,
        outcome: Outcome, counts: io.astrolabe.evidence.Counts?, tested: TestedInputs, raw: Digest?, limits: List<Limit>,
    ): Receipt {
        val receipt = Receipt(
            receiptId = receiptId, ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds, command = argv, cwd = cwd, shell = false,
            stampBefore = s0, stampAfter = s0, envId = env.envId, verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion,
            contractVersion = contractVersion, outcome = outcome, parsed = counts, inputClosure = check.inputClosure, testedInputs = tested,
            raw = raw, limits = limits, exitCode = exit, at = clock.instant(),
        )
        receipts.record(receipt)
        return receipt
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
    }
}
