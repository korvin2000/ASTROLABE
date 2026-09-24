package io.astrolabe.verify

import io.astrolabe.Astrolabe
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.ClosureCompleteness
import io.astrolabe.evidence.ClosureManifest
import io.astrolabe.evidence.Counts
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
import io.astrolabe.tool.run.announceMoved
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import kotlin.io.path.relativeTo

/** What one check invocation produced, as the runner or checker reported it: status from exit code and parser, never model-authored (invariant 4). */
public data class Executed(
    val command: List<String>,
    val cwd: String?,
    val shell: Boolean,
    val exit: Int?,
    val outcome: Outcome,
    val counts: Counts?,
    val raw: Digest?,
    val limits: List<String> = emptyList(),
)

/** Paths a check may write without touching its inputs (declared scratch/output policy, D-45): caches, build output, reports. */
public data class ScratchPolicy(val prefixes: Set<String> = DEFAULT_PREFIXES) {
    public fun isScratch(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return prefixes.any { p -> normalized == p || normalized.startsWith("$p/") || normalized.split('/').any { it == p } }
    }

    public companion object {
        @JvmField
        public val DEFAULT_PREFIXES: Set<String> = setOf(
            ".pytest_cache", "__pycache__", ".mypy_cache", ".ruff_cache", ".hypothesis", ".tox", ".nox",
            "build", "dist", "target", "out", ".gradle", "node_modules", ".cache", "coverage", ".coverage", "tmp", ".astrolabe/tmp",
        )
    }
}

/** The currency of a check's last receipt for the candidate at hand (§8.4 refined by D-45). */
public data class Currency @JvmOverloads constructor(
    val receiptId: String?,
    val applicability: Applicability,
    val eligible: Boolean,
    val green: Boolean,
    val reasons: List<String>,
    /**
     * The receipt's outcome is `failed`: a red verify line (§8.7). An inconclusive, timed-out or unavailable
     * check is not green, but it is not red either — it is missing evidence, which only a required check turns
     * into a refusal. Defaults to `!green` for callers that predate the distinction.
     */
    val red: Boolean = !green,
) {
    /** Only a current, eligible, green receipt certifies the final tree for its check. */
    val certifies: Boolean get() = applicability == Applicability.Current && eligible && green
}

/**
 * Receipt emission and currency (§8.4, D-45, TODO P1.7.4). [runCheck] wraps one check invocation in the
 * exclusive protocol: the workspace mutation lock is held for the whole check, the check's tested inputs —
 * the declared closure's files, plus whatever the caller enumerates for an unknown closure — are hashed and
 * stat-ed before and rescanned after, so a write by the command, by a background process or by a human
 * during the check is recorded in `tested_inputs.mutated_during_check`. A restore-after-mutation writer
 * that leaves the bytes equal still moves the file's metadata and is caught the same way (IX-04). The
 * factual outcome is kept; such a receipt is simply ineligible for the final tree and the check reruns.
 * Declared scratch and output paths are excluded from the inputs, so a cache written by the runner never
 * invalidates a complete closure. `stamp_after == stamp_now` remains necessary, never sufficient.
 */
public class Scheduler(
    private val checks: Checks,
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val stamper: Stamper,
    private val receipts: Receipts,
    private val aliases: Aliases,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    private val verifierVersion: String = Astrolabe.VERSION,
    private val scratch: ScratchPolicy = ScratchPolicy(),
    /** Where `slow|expensive` checks get isolated candidates (`candidates/` of the store layout); `null` runs every check exclusively. */
    private val candidates: Path? = null,
) {
    private val aliasByReceipt = HashMap<String, String>()

    /** The campaign-global `#n` of [receiptId], when this scheduler recorded it. */
    public fun aliasOf(receiptId: String): String? = aliasByReceipt[receiptId]

    /**
     * Runs [execute] for [check] and records the receipt (D-45). `inline|fast` checks — and every check when no
     * [candidates] directory is configured — run under the exclusive protocol in the workspace; `slow|expensive`
     * checks run on an exported isolated candidate ([execute] receives the root to run in). [inputs] is the
     * caller's enumeration of the tree for an unknown closure (the atlas rows, typically); without it an unknown
     * closure run exclusively yields `input_stability = unknown` and the receipt can never be eligible.
     */
    public suspend fun runCheck(check: Check, contractVersion: Int, inputs: Collection<String> = emptyList(), execute: suspend (root: Path) -> Executed): Receipt {
        val isolatedRoot = candidates?.takeIf { check.costClass == CostClass.Slow || check.costClass == CostClass.Expensive }
        if (isolatedRoot != null) runIsolated(check, contractVersion, inputs, isolatedRoot, execute)?.let { return it }
        val paths = testedInputsFor(check, inputs)
        val limits = ArrayList<Limit>()
        return workspace.mutation.withLock {
            val before = stamper.report()
            val seenBefore = paths.associateWith { snapshot(it) }
            val manifest = manifestOf(check.inputClosure)
            val executed = execute(workspace.root)
            val after = stamper.report()
            announceMoved(registry, before, after, "check ${check.id}")
            val mutated = paths.filter { snapshot(it) != seenBefore.getValue(it) }.toSet()
            val stability = when {
                check.inputClosure == Closure.Unknown && paths.isEmpty() -> {
                    limits += Limit("input_stability", "closure unknown and no inputs enumerated: the tested inputs could not be rescanned")
                    InputStability.Unknown
                }
                else -> InputStability.Exclusive
            }
            val versions = seenBefore.mapNotNull { (path, seen) -> seen.version?.let { path to it } }.toMap()
            recordRun(check, contractVersion, executed, before, after.candidateId, TestedInputs(versions, stability, mutated), manifest, limits)
        }
    }

    /**
     * §8.4/D-45 `isolated`: under the mutation lock the stamped tree (tracked ∪ untracked, raw bytes, scratch
     * excluded) is copied into `candidates/<id>/` and every copy is verified against the bytes read; the check then
     * runs there without the lock, and the copy is verified again afterwards — any change of content or metadata
     * outside declared scratch output is a mutation during the check, so a write inside the candidate can never
     * certify it. The receipt describes the exported stamp. `null` when the export could not be verified: the
     * check then runs exclusively.
     */
    private suspend fun runIsolated(check: Check, contractVersion: Int, inputs: Collection<String>, root: Path, execute: suspend (root: Path) -> Executed): Receipt? {
        val dir = root.resolve(idGen.next("cand"))
        val (report, manifest, exported) = workspace.mutation.withLock {
            val report = stamper.report()
            Triple(report, manifestOf(check.inputClosure), export(report, dir))
        }
        if (exported == null) {
            deleteTree(dir)
            return null
        }
        try {
            val executed = execute(dir)
            val after = scan(dir)
            val mutated = (exported.keys + after.keys).filter { exported[it] != after[it] }.toSet()
            val limits = arrayListOf(
                Limit("input_stability", "isolated candidate @${report.candidateId.hash8}, verified before and after the check"),
                Limit("external_services", "mutable external services (network, databases, caches outside the candidate) are not isolated"),
            )
            val paths = testedInputsFor(check, inputs).toSet()
            val tested = exported.filterKeys { check.inputClosure == Closure.Unknown && paths.isEmpty() || it in paths }
            return recordRun(check, contractVersion, executed, report, report.candidateId, TestedInputs(tested.mapNotNull { (path, seen) -> seen.version?.let { path to it } }.toMap(), InputStability.Isolated, mutated), manifest, limits)
        } finally {
            deleteTree(dir)
        }
    }

    private fun recordRun(
        check: Check,
        contractVersion: Int,
        executed: Executed,
        before: StampReport,
        stampAfter: CandidateId,
        testedInputs: TestedInputs,
        manifest: ClosureManifest,
        limits: MutableList<Limit>,
    ): Receipt {
        if (testedInputs.mutatedDuringCheck.isNotEmpty()) {
            limits += Limit("input_mutation", "inputs moved during the check: ${testedInputs.mutatedDuringCheck.sorted().joinToString(", ")}; the receipt is ineligible for the final tree — rerun")
        }
        executed.limits.forEach { limits += Limit("runner", it) }
        val outcome = if (executed.outcome == Outcome.Passed && (executed.counts == null || (executed.counts.executed == 0 && executed.counts.discovered == 0))) {
            limits += Limit("evidence", "a pass without parsed counts is inconclusive, never green (D-50)")
            Outcome.Inconclusive
        } else {
            executed.outcome
        }
        val receipt = Receipt(
            receiptId = idGen.next("rcpt"), ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds,
            command = executed.command, cwd = executed.cwd, shell = executed.shell,
            stampBefore = before.candidateId, stampAfter = stampAfter, envId = before.env.envId,
            verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion, contractVersion = contractVersion,
            outcome = outcome, parsed = executed.counts, inputClosure = check.inputClosure, testedInputs = testedInputs,
            raw = executed.raw, limits = limits, exitCode = executed.exit, at = clock.instant(), closureManifest = manifest,
        )
        receipts.record(receipt)
        aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
        checks.record(check.id, LastResult(receipt.receiptId, receipt.stampAfter, check.definitionVersion, outcome, executed.counts, Applicability.Current))
        return receipt
    }

    /** Copies the stamped tree into [dir]; `null` when a member could not be read or a copy does not verify. */
    private fun export(report: StampReport, dir: Path): Map<String, Seen>? {
        val members = (workspace.git.lsFiles().filter { it.stage == 0 }.map { it.path } + report.untracked.map { it.path })
            .distinct().filterNot { scratch.isScratch(it) }
        val copied = HashMap<String, FileVersion>()
        for (path in members) {
            val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return null
            if (!Files.exists(resolved.real, LinkOption.NOFOLLOW_LINKS)) continue // deleted in the working tree
            val content = registry.read(path) ?: return null
            val target = dir.resolve(path)
            Files.createDirectories(target.parent)
            Files.write(target, content.bytes)
            copied[path] = content.version
        }
        val seen = scan(dir)
        return seen.takeIf { it.keys == copied.keys && copied.all { (path, version) -> seen.getValue(path).version == version } }
    }

    /** Content and metadata of every non-scratch file under [dir], by workspace-relative path. */
    private fun scan(dir: Path): Map<String, Seen> = Files.walk(dir).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
    }.map { it.relativeTo(dir).joinToString("/") { part -> part.toString() } to it }
        .filterNot { (path, _) -> scratch.isScratch(path) }
        .associate { (path, file) ->
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            path to Seen(FileVersion.of(Files.readAllBytes(file)), attributes.size(), attributes.lastModifiedTime().toMillis())
        }

    private fun deleteTree(dir: Path) {
        if (!Files.exists(dir)) return
        runCatching { Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }

    /**
     * §8.10: [first] failed and its isolated rerun [second] disagreed. Records a third receipt over the rerun's
     * candidate and inputs whose outcome is `inconclusive`, naming both attempts; it becomes the check's result, so
     * the favourable attempt can never certify on its own.
     */
    public fun flaky(check: Check, contractVersion: Int, first: Receipt, second: Receipt): Receipt {
        val receipt = second.copy(
            receiptId = idGen.next("rcpt"), contractVersion = contractVersion, outcome = Outcome.Inconclusive, at = clock.instant(), reuseOf = null,
            limits = second.limits + Limit("flaky", "${first.receiptId} ${first.outcome.name.lowercase()}, isolated rerun ${second.receiptId} ${second.outcome.name.lowercase()}: two disagreeing outcomes are inconclusive (§8.10)"),
        )
        receipts.record(receipt)
        aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
        checks.record(check.id, LastResult(receipt.receiptId, receipt.stampAfter, check.definitionVersion, Outcome.Inconclusive, receipt.parsed, Applicability.Current))
        return receipt
    }

    /**
     * Turns an end-of-turn [CheckerResult] into a receipt and makes it the check's current result: the checker
     * ran outside the exclusive protocol (no rescan of its inputs), so the receipt says `input_stability =
     * unknown` — fine for fast type/lint feedback, never eligible as acceptance evidence (D-45).
     */
    public fun record(result: CheckerResult, contractVersion: Int): Receipt {
        val check = requireNotNull(checks[result.checkId]) { "unknown check ${result.checkId}" }
        val command = check.command
        val limits = ArrayList<Limit>()
        result.reason?.let { limits += Limit(result.outcome.name.lowercase(), it) }
        limits += Limit("input_stability", "end-of-turn checker: inputs were not rescanned, stability unknown")
        val receipt = Receipt(
            receiptId = idGen.next("rcpt"), ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds,
            command = command?.let { Checker.argvFor(check, result.touched) } ?: emptyList(), cwd = command?.cwd, shell = false,
            stampBefore = result.stampBefore, stampAfter = result.stampAfter ?: result.stampBefore, envId = stamper.report().env.envId,
            verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion, contractVersion = contractVersion,
            outcome = result.outcome, parsed = result.counts, inputClosure = check.inputClosure,
            testedInputs = TestedInputs(result.touched.mapNotNull { path -> registry.version(path)?.let { path to it } }.toMap(), InputStability.Unknown),
            raw = result.log, limits = limits, exitCode = result.exit, at = clock.instant(),
        )
        receipts.record(receipt)
        aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
        checks.record(check.id, LastResult(receipt.receiptId, receipt.stampAfter, check.definitionVersion, result.outcome, receipt.parsed, Applicability.Current))
        return receipt
    }

    /**
     * §8.4 applicability of every check's last receipt against [stampNow] (P3.1.2): [Applicability.of] with the
     * closure re-pinned over the current bytes, so an unchanged complete closure keeps a moved result current with a
     * reuse proof. [env] is the current environment when the caller holds the stamp report; otherwise it is taken
     * once, only if some receipt could be reused.
     */
    @JvmOverloads
    public fun refresh(stampNow: CandidateId?, env: EnvFingerprint? = null): List<Check> {
        val current = lazy { env ?: stamper.report().env }
        return checks.refresh(stampNow) { check, last -> receipts.get(last.receiptId)?.let { assess(check, it, stampNow, current) } }
    }

    /** §8.4 applicability plus D-45 eligibility of a check's last receipt against [stampNow]. */
    public fun currency(check: Check, stampNow: CandidateId?): Currency {
        val last = checks[check.id]?.last ?: return Currency(null, Applicability.Unknown, false, false, listOf("no receipt for ${check.id}"))
        val receipt = receipts.get(last.receiptId)
        val registered = checks[check.id] ?: check
        val refreshed = if (receipt == null || stampNow == null) {
            checks.refresh(stampNow).firstOrNull { it.id == check.id }?.last ?: last
        } else {
            last.applied(assess(registered, receipt, stampNow, lazy { stamper.report().env })).also { checks.record(check.id, it) }
        }
        val reasons = ArrayList<String>()
        refreshed.staleReason?.let { reasons += it }
        if (receipt == null) reasons += "receipt ${last.receiptId} is not in the store"
        val eligible = receipt?.testedInputs?.eligible ?: false
        if (receipt != null && !eligible) {
            reasons += if (receipt.testedInputs.mutatedDuringCheck.isNotEmpty()) "inputs moved during the check: ${receipt.testedInputs.mutatedDuringCheck.sorted().joinToString(", ")}" else "input stability ${receipt.testedInputs.stability.name.lowercase()} cannot certify the final tree"
        }
        val green = receipt?.outcome?.green ?: false
        if (receipt != null && !green) reasons += "outcome ${receipt.outcome.name.lowercase()}"
        return Currency(last.receiptId, refreshed.applicability, eligible, green, reasons, red = receipt?.outcome == Outcome.Failed)
    }

    private fun assess(check: Check, receipt: Receipt, stampNow: CandidateId?, env: Lazy<EnvFingerprint>): ApplicabilityVerdict {
        // Re-pinning hashes the closure: only worth it when a complete closure could back a reuse proof.
        val reusable = stampNow != null && receipt.stampAfter != stampNow &&
            receipt.closureManifest?.completeness == ClosureCompleteness.Complete && receipt.checkDefinitionVersion == check.definitionVersion
        val now = if (reusable) {
            CandidateNow(stampNow, check.definitionVersion, verifierVersion, env.value.envId, env.value.envKnown, manifestOf(receipt.inputClosure))
        } else {
            CandidateNow(stampNow, check.definitionVersion, verifierVersion)
        }
        return Applicability.of(receipt, now)
    }

    /**
     * The `closure_manifest` of [closure] over the current bytes (§8.1): a package closure walks its directory, so an
     * added or deleted member moves the membership; declared scratch output is excluded from the tree.
     */
    public fun manifestOf(closure: Closure): ClosureManifest {
        val tree = when (closure) {
            is Closure.Known -> closure.paths.toList()
            is Closure.Package -> filesUnder(closure.path)
            Closure.Unknown -> emptyList()
        }.map { it.replace('\\', '/') }.filterNot { scratch.isScratch(it) }
        return ClosureManifest.of(closure, tree, registry::version, excluded = { if (scratch.isScratch(it)) "declared scratch output" else null })
    }

    /** The paths whose stability the receipt vouches for: the declared closure minus scratch, or [inputs] for an unknown closure. */
    public fun testedInputsFor(check: Check, inputs: Collection<String>): List<String> {
        val declared: Collection<String> = when (val closure = check.inputClosure) {
            is Closure.Known -> closure.paths
            is Closure.Package -> filesUnder(closure.path) + inputs
            Closure.Unknown -> inputs
        }
        return declared.map { it.replace('\\', '/') }.filterNot { scratch.isScratch(it) }.distinct().sorted()
    }

    private fun filesUnder(prefix: String): List<String> {
        val resolved = workspace.resolve(prefix, Intent.Read) as? PathResolution.Resolved ?: return emptyList()
        if (!Files.isDirectory(resolved.real)) return listOf(resolved.relative)
        return try {
            Files.walk(resolved.real).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .map { it.relativeTo(workspace.root).joinToString("/") { part -> part.toString() } }
                    .filter { !it.startsWith(".git/") }
                    .toList()
            }
        } catch (failure: IOException) {
            emptyList()
        }
    }

    /** Content and metadata of one input: a restore-after-write leaves the version equal but moves the metadata. */
    private data class Seen(val version: FileVersion?, val sizeBytes: Long?, val modifiedEpochMillis: Long?)

    private fun snapshot(path: String): Seen {
        val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return Seen(null, null, null)
        val attributes = try {
            Files.readAttributes(resolved.real, BasicFileAttributes::class.java)
        } catch (missing: IOException) {
            return Seen(null, null, null)
        }
        if (attributes.isDirectory) return Seen(null, null, null)
        return Seen(registry.read(path)?.version, attributes.size(), attributes.lastModifiedTime().toMillis())
    }
}
