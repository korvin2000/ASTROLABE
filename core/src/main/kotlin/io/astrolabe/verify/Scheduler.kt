package io.astrolabe.verify

import io.astrolabe.Astrolabe
import io.astrolabe.contract.Origin
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.ClosureCompleteness
import io.astrolabe.evidence.ClosureManifest
import io.astrolabe.evidence.Counts
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
import io.astrolabe.id.Stamp
import io.astrolabe.os.FileMode
import io.astrolabe.os.ObjectId
import io.astrolabe.tool.run.announceMoved
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.WorkspacePath
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
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
    val expectedExitCode: Int? = 0,
    /** What a harness regression check's run reported test by test, recorded on the receipt (P8.C.10); `null` otherwise. */
    val tests: TestOutcomes? = null,
    /**
     * P8.C.10: per check sharing one run, its own record — identities bound to that check's id — which replaces [tests]
     * on that check's receipt (one `run` realizing the full suite and the blast radius gives each its own).
     */
    val testsByCheck: Map<String, TestOutcomes> = emptyMap(),
    /** P8.C.10: per regression check, the outcome its receipt records instead of [outcome] (reports that could not be collected). */
    val outcomeByCheck: Map<String, Outcome> = emptyMap(),
) {
    /** The constructor before [tests] (P8.C.10). Kept for Java callers. */
    public constructor(command: List<String>, cwd: String?, shell: Boolean, exit: Int?, outcome: Outcome, counts: Counts?, raw: Digest?, limits: List<String>, expectedExitCode: Int?) :
        this(command, cwd, shell, exit, outcome, counts, raw, limits, expectedExitCode, null)
}

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
    /**
     * The check is mandatory ([Obligations.mandatory]): a red one outside the increment's own items still needs an
     * `Open` item, while an optional red check is recorded by the runtime as known red (plan §4.3, C1b). Defaults to
     * `true`, the stricter rule, for callers that predate the distinction.
     */
    val mandatory: Boolean = true,
    /**
     * C1b: for an optional check that is known red, the red receipt that began it (its alias, else its id) — the first
     * red receipt after the check's last `passed` one in this attempt, whatever tree that pass was on; a timeout, an
     * inconclusive run or a missing receipt never ends it. `null` when the check is not known red, or mandatory.
     */
    val knownRed: String? = null,
    /**
     * P8.C.10: for the blast radius or the types of touched files ([Regressions]), the failures of the attempt not shown
     * fixed on the tree at hand, classified against the baseline at `s0`; `null` when none is held, or when the caller did
     * not compute it (the resolver then reads a current, eligible red as unknown).
     */
    val hold: RegressionHold? = null,
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
    /** Every check, not only `slow|expensive` ones, runs on an isolated candidate (a review cell's `verify(tests)`, §8.8). */
    private val isolateAll: Boolean = false,
    /**
     * Where the §8.10 isolated retry of a failed `slow|expensive` check exports its original candidate when no
     * [candidates] directory is configured (D-294); first runs are unaffected. `null` ⇒ no such retry.
     */
    private val retryCandidates: Path? = null,
) {
    private val aliasByReceipt = HashMap<String, String>()

    /** P8.C.12: the workspace's background runs a stop could not settle ([Workspace.unquiet]); any scheduler of it reads them. */
    private val unquiet: List<String> get() = workspace.unquiet

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
        val isolatedRoot = candidates?.takeIf { isolateAll || check.costClass == CostClass.Slow || check.costClass == CostClass.Expensive }
        if (isolatedRoot != null) runIsolated(check, contractVersion, inputs, isolatedRoot, execute)?.let { return it }
        return exclusive(listOf(check), contractVersion, inputs, execute).receipts.single()
    }

    /**
     * C1a (plan §4.4): one invocation for a `run` that realizes [checks] (the same command), under [runCheck]'s exclusive
     * protocol but always in the workspace — the model ran the command there, so its effects land there. Every check whose
     * tested inputs are the first one's gets its own receipt of the one execution; another gets none.
     */
    internal suspend fun runInWorkspace(checks: List<Check>, contractVersion: Int, inputs: Collection<String>, execute: suspend (root: Path) -> Executed): Scheduled =
        exclusive(checks, contractVersion, inputs, execute)

    private suspend fun exclusive(checks: List<Check>, contractVersion: Int, inputs: Collection<String>, execute: suspend (root: Path) -> Executed): Scheduled {
        val check = checks.first()
        return workspace.mutation.withLock {
            val before = stamper.report(fresh = true)
            val paths = testedInputsFor(check, inputs)
            val sharing = checks.filter { it === check || testedInputsFor(it, inputs) == paths }
            val seenBefore = paths.associateWith { snapshot(it) }
            val manifests = sharing.map { manifestOf(it.inputClosure) }
            val executed = execute(workspace.root)
            val after = stamper.report(fresh = true)
            val changed = announceMoved(registry, before, after, "check ${check.id}")
            val limits = ArrayList<Limit>()
            val tested = rescanned(check, inputs, paths, seenBefore, InputStability.Exclusive, limits)
            Scheduled(sharing.zip(manifests).map { (each, manifest) -> recordRun(each, contractVersion, executed, before, after.candidateId, tested, manifest, ArrayList(limits)) }, changed)
        }
    }

    /** The tested inputs after a check: what moved against [seenBefore] (content or metadata, added or removed) is a mutation. */
    private fun rescanned(check: Check, inputs: Collection<String>, paths: List<String>, seenBefore: Map<String, Seen>, stable: InputStability, limits: MutableList<Limit>): TestedInputs {
        val afterPaths = testedInputsFor(check, inputs)
        val pathSet = paths.toHashSet()
        val afterSet = afterPaths.toHashSet()
        val mutated = (pathSet + afterSet).filter { it !in pathSet || it !in afterSet || snapshot(it) != seenBefore[it] }.toSet()
        val stability = when {
            check.inputClosure == Closure.Unknown && paths.isEmpty() -> {
                limits += Limit("input_stability", "closure unknown and no inputs enumerated: the tested inputs could not be rescanned")
                InputStability.Unknown
            }
            else -> stable
        }
        val versions = seenBefore.mapNotNull { (path, seen) -> seen.version?.let { path to it } }.toMap()
        return TestedInputs(versions, stability, mutated)
    }

    /**
     * C1a (plan §4.4), a recognised background `run`: [checks]' inputs are pinned at its launch (stamp, hashes and metadata,
     * under the lock), and [settle] records the outcome at its end. The workspace is not held in between, so no writer —
     * the model's own tools included — was kept out (D-45): the receipt is evidence of the outcome, never of the final
     * tree (`input_stability = unknown`); a write the rescan sees is named as a mutation besides.
     */
    internal suspend fun pin(checks: List<Check>, inputs: Collection<String>): Pin = workspace.mutation.withLock {
        val check = checks.first()
        val before = stamper.report(fresh = true)
        val paths = testedInputsFor(check, inputs)
        val sharing = checks.filter { it === check || testedInputsFor(it, inputs) == paths }
        Pin(sharing, inputs.toList(), before, paths, paths.associateWith { snapshot(it) }, sharing.map { manifestOf(it.inputClosure) })
    }

    /**
     * The receipts of a [pin]ned background run that ended with [executed]; a check no longer registered gets none. Moved
     * paths are announced by the caller, which diffs the interval itself.
     */
    internal suspend fun settle(pin: Pin, contractVersion: Int, executed: Executed): List<Receipt> = workspace.mutation.withLock {
        val after = stamper.report(fresh = true)
        val limits = arrayListOf(Limit("input_stability", "$BACKGROUND: the workspace was not held between its launch and its end, so no concurrent writer was kept out (D-45); the receipt cannot certify a tree"))
        val tested = rescanned(pin.checks.first(), pin.inputs, pin.paths, pin.seen, InputStability.Unknown, limits)
        pin.checks.zip(pin.manifests).filter { (check, _) -> checks[check.id] != null }.map { (check, manifest) ->
            recordRun(check, contractVersion, executed, pin.before, after.candidateId, tested, manifest, ArrayList(limits))
        }
    }

    internal companion object {
        /** How the limit of a background run's receipt begins (C1a). */
        const val BACKGROUND: String = "background run"

        /** The limit kind of a receipt recorded while a background run the stop could not cancel was live (P8.C.12). */
        const val CONCURRENT: String = "concurrent"

        /** The limit kind of why a check's runner could not run: an `unavailable` receipt's runner limits (P8.C.15). */
        const val UNAVAILABLE: String = "unavailable"

        /** What a not-green [receipt] names as its cause: an unavailable one's first typed reason, else its first limit. */
        fun cause(receipt: Receipt): String? =
            (receipt.limits.firstOrNull { receipt.outcome == Outcome.Unavailable && it.kind == UNAVAILABLE } ?: receipt.limits.firstOrNull())?.detail
    }

    /** The receipts of one recognised `run` and the paths it moved (announced by the scheduler). */
    internal class Scheduled(val receipts: List<Receipt>, val changed: List<String>)

    /** What [pin] took at a background run's launch. */
    internal class Pin(
        val checks: List<Check>,
        val inputs: List<String>,
        val before: StampReport,
        val paths: List<String>,
        val seen: Map<String, Seen>,
        val manifests: List<ClosureManifest>,
    )

    /** A retry must export the original candidate; it never falls back to the live workspace. */
    internal suspend fun retryIsolated(check: Check, contractVersion: Int, first: Receipt, inputs: Collection<String>, execute: suspend (Path) -> Executed): Receipt? {
        val root = candidates ?: retryCandidates?.takeIf { check.costClass == CostClass.Slow || check.costClass == CostClass.Expensive } ?: return null
        return runIsolated(check, contractVersion, inputs, root, execute, first)
    }

    /**
     * §8.4/D-45 `isolated`: under the mutation lock the stamped tree (tracked ∪ untracked, raw bytes, scratch
     * excluded) is copied into `candidates/<id>/` and every copy is verified against the bytes read; the check then
     * runs there without the lock, and the copy is verified again afterwards — any change of content or metadata
     * outside declared scratch output is a mutation during the check, so a write inside the candidate can never
     * certify it. The receipt describes the exported stamp. `null` when the export could not be verified: the
     * check then runs exclusively.
     */
    private suspend fun runIsolated(check: Check, contractVersion: Int, inputs: Collection<String>, root: Path, execute: suspend (root: Path) -> Executed, retryOf: Receipt? = null): Receipt? {
        val dir = root.resolve(idGen.next("cand"))
        val (report, manifest, exported) = workspace.mutation.withLock {
            val report = stamper.report(fresh = true)
            if (retryOf != null && (report.candidateId != retryOf.stampBefore ||
                    report.env.envId != retryOf.envId || !report.env.envKnown)) return null
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
        val concurrent = unquiet.isNotEmpty()
        if (concurrent) limits += Limit(CONCURRENT, "${unquiet.joinToString(", ")} live during the check after its cancellation: the tree was not quiet, so the receipt cannot certify it")
        // P8.C.15: why a check could not run is a typed limit, found by [cause] behind any stability limit recorded first.
        val runnerKind = if (executed.outcome == Outcome.Unavailable) UNAVAILABLE else "runner"
        executed.limits.forEach { limits += Limit(runnerKind, it) }
        val kind = check.evidenceKind
        // Plan §4.4 (D-50 relaxed by the owner): a declared host or user build or typecheck passes on its expected exit, uncounted.
        val passesOnExit = check.evidence?.exitSuffices == true && check.origin !is Origin.Model && executed.counts == null && executed.exit == executed.expectedExitCode
        val outcome = if (executed.outcome == Outcome.Passed && !passesOnExit && (executed.counts == null || (executed.counts.executed == 0 && executed.counts.discovered == 0))) {
            limits += Limit("evidence", "a pass without parsed counts is inconclusive, never green (D-50)")
            Outcome.Inconclusive
        } else {
            executed.outcomeByCheck[check.id]?.takeIf { it != Outcome.Passed } ?: executed.outcome
        }
        val receipt = Receipt(
            receiptId = idGen.next("rcpt"), ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds,
            command = executed.command, cwd = executed.cwd, shell = executed.shell,
            stampBefore = before.candidateId, stampAfter = stampAfter, envId = before.env.envId,
            verifierVersion = verifierVersion, checkDefinitionVersion = check.definitionVersion, contractVersion = contractVersion,
            outcome = outcome, parsed = executed.counts, inputClosure = check.inputClosure, testedInputs = if (concurrent) testedInputs.copy(stability = InputStability.Unknown) else testedInputs,
            raw = executed.raw, limits = limits, exitCode = executed.exit, at = clock.instant(), closureManifest = manifest, expectedExitCode = executed.expectedExitCode,
            evidenceKind = kind, checkOrigin = check.origin, evidenceDeclared = check.evidence != null, tests = executed.testsByCheck[check.id] ?: executed.tests,
        )
        receipts.record(receipt)
        aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
        checks.record(check.id, LastResult(receipt.receiptId, receipt.stampAfter, check.definitionVersion, outcome, executed.counts, Applicability.Current))
        return receipt
    }

    /** Copies the stamped tree into [dir]; `null` when a member could not be read or a copy does not verify. */
    private fun export(report: StampReport, dir: Path): Map<String, Seen>? {
        if (report.unreadable.isNotEmpty() || stamper.stamp().id != report.candidateId) return null
        // A repository without a first commit has no base tree: every member is a dirty one.
        val base = if (report.baseCommit == Stamp.NO_COMMIT) emptyMap() else
            workspace.git.lsTree(ObjectId(report.baseCommit), recursive = true).associateBy { it.path }
        val members = (base.keys + report.members.keys).filterNot { scratch.isScratch(it) }
        Files.createDirectories(dir)
        val exportedPaths = WorkspacePath.of(dir)
        val copied = HashMap<String, FileVersion>()
        for (path in members) {
            val delta = report.members[path]
            if (delta?.type == io.astrolabe.workspace.EntryType.Deleted) continue
            val mode = delta?.mode ?: base.getValue(path).mode
            if (mode != FileMode.REGULAR && mode != FileMode.EXECUTABLE) return null
            // Unchanged files come from the immutable base; dirty bytes must match the stamped digest.
            val bytes = if (delta == null) workspace.git.catFile(base.getValue(path).id) else {
                val content = registry.read(path) ?: return null
                if (content.version.digest != delta.digest) return null
                content.bytes
            }
            val target = (exportedPaths.resolve(path, Intent.Read) as? PathResolution.Resolved)?.real ?: return null
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
            if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                val permissions = Files.getPosixFilePermissions(target)
                val executeBits = setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE)
                Files.setPosixFilePermissions(target, if (mode == FileMode.EXECUTABLE) permissions + executeBits else permissions - executeBits)
                if (Files.isExecutable(target) != (mode == FileMode.EXECUTABLE)) return null
            }
            copied[path] = FileVersion.of(bytes)
        }
        val seen = scan(dir)
        return seen.takeIf { stamper.stamp().id == report.candidateId && it.keys == copied.keys && copied.all { (path, version) -> seen.getValue(path).version == version } }
    }

    /** Content and metadata of every non-scratch file under [dir], by workspace-relative path. */
    private fun scan(dir: Path): Map<String, Seen> = Files.walk(dir).use { stream ->
        stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
    }.map { it.relativeTo(dir).joinToString("/") { part -> part.toString() } to it }
        .filterNot { (path, _) -> scratch.isScratch(path) }
        .associate { (path, file) ->
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            path to Seen(FileVersion.of(Files.readAllBytes(file)), attributes.size(), attributes.lastModifiedTime(), Files.isExecutable(file))
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
            raw = result.log, limits = limits, exitCode = result.exit, at = clock.instant(), evidenceKind = check.evidenceKind, checkOrigin = check.origin, evidenceDeclared = check.evidence != null,
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
        val mandatory = Obligations.mandatory(checks[check.id] ?: check)
        val last = checks[check.id]?.last ?: return Currency(null, Applicability.Unknown, false, false, listOf("no receipt for ${check.id}"), mandatory = mandatory)
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
        val eligible = (receipt?.testedInputs?.eligible ?: false) && unquiet.isEmpty()
        if (receipt != null && !eligible) {
            reasons += when {
                unquiet.isNotEmpty() -> "background run ${unquiet.joinToString(", ")} still live after the stop cancelled it: no receipt certifies the tree"
                receipt.testedInputs.mutatedDuringCheck.isNotEmpty() -> "inputs moved during the check: ${receipt.testedInputs.mutatedDuringCheck.sorted().joinToString(", ")}"
                else -> "input stability ${receipt.testedInputs.stability.name.lowercase()} cannot certify the final tree"
            }
        }
        val green = receipt?.outcome?.green ?: false
        // D-338: an unverified result names its cause for the decider — "cannot start python3", not just "unavailable".
        if (receipt != null && !green) reasons += "outcome ${receipt.outcome.name.lowercase()}" + (cause(receipt)?.let { ": $it" } ?: "")
        return Currency(last.receiptId, refreshed.applicability, eligible, green, reasons, red = receipt?.outcome == Outcome.Failed, mandatory = mandatory,
            knownRed = if (mandatory) null else knownRedSince(check.id), hold = if (Regressions.of(registered)) hold(check.id, stampNow) else null)
    }

    /** C1b ([Currency.knownRed]): walks this attempt's receipts of [checkId] in order; only a later `passed` one ends a red. */
    private fun knownRedSince(checkId: String): String? {
        var since: Receipt? = null
        for (r in history(checkId)) {
            if (r.outcome == Outcome.Failed && since == null) since = r
            if (r.outcome == Outcome.Passed) since = null
        }
        return since?.let(::alias)
    }

    /** P8.C.10 ([Currency.hold]): [Regressions.hold] over this workspace's receipts of [checkId] in the attempt, on [stampNow]. */
    private fun hold(checkId: String, stampNow: CandidateId?): RegressionHold? = Regressions.hold(history(checkId), baselines(checkId), stampNow, ::alias)

    /** P8.C.10 A: the red runs of a regression [check] behind its hold that no run of their definition confirms on [stampNow]. */
    internal fun unconfirmed(check: Check, stampNow: CandidateId): List<Receipt> =
        if (Regressions.of(checks[check.id] ?: check)) Regressions.unconfirmed(history(check.id), baselines(check.id), stampNow) else emptyList()

    /**
     * P8.C.10 A: records, before the stop reruns [check] (a red command with the red run's closure) on the tree now, that the
     * rerun began — a `not_run` marker of its definition on this stamp, in this workspace — so it runs once per definition
     * and tree, a crash or a reopen included. The marker is no run of the change and never the check's last result.
     */
    internal fun beginRerun(check: Check, contractVersion: Int, definition: Digest = check.definitionVersion): Receipt {
        val report = stamper.report(fresh = true)
        val receipt = Receipt(
            receiptId = idGen.next("rcpt"), ids = ids, checkId = check.id, acceptanceIds = check.acceptanceIds, command = check.command?.argv.orEmpty(), cwd = check.command?.cwd,
            shell = false, stampBefore = report.candidateId, stampAfter = report.candidateId, envId = report.env.envId, verifierVersion = verifierVersion,
            checkDefinitionVersion = definition, contractVersion = contractVersion, outcome = Outcome.NotRun, parsed = null, inputClosure = check.inputClosure,
            testedInputs = TestedInputs(emptyMap(), InputStability.Unknown), raw = null, limits = listOf(Limit(Regressions.RERUN, "the stop's rerun of ${check.id} on @${report.candidateId.hash8} began")),
            at = clock.instant(), evidenceKind = check.evidenceKind, checkOrigin = check.origin,
        )
        receipts.record(receipt)
        aliasByReceipt[receipt.receiptId] = aliases.allocate(ids.work, receipt.receiptId, "receipt", ids.context, workspace.id).text
        return receipt
    }

    /** P8.C.10 G: this tree's red run of a regression [check] that the attempt has no baseline for yet, or `null`. */
    internal fun baselineDue(check: Check, stampNow: CandidateId): Receipt? =
        if (Regressions.of(checks[check.id] ?: check)) Regressions.baselineDue(history(check.id), baselines(check.id), stampNow) else null

    /**
     * The receipts of [checkId] this scheduler's workspace recorded in the attempt, in the order they were recorded,
     * baselines aside: a writer's worktree never answers for another's (P8.C.10 F). A receipt whose alias a crash left
     * unwritten belongs to no known workspace and is counted here — a failure is never orphaned (it can only hold more).
     */
    private fun history(checkId: String): List<Receipt> = receipts.forCheck(checkId).filter {
        it.ids.work == ids.work && it.ids.attempt == ids.attempt && !Regressions.isBaseline(it) &&
            (aliasByReceipt.containsKey(it.receiptId) || aliases.byCanonical(ids.work, it.receiptId).let { alias -> alias == null || alias.workspace == workspace.id })
    }

    /** The attempt's baseline receipts of [checkId]: runs on the captured `s0`, whichever workspace asked for them. */
    private fun baselines(checkId: String): List<Receipt> = receipts.forCheck(checkId).filter { it.ids.work == ids.work && it.ids.attempt == ids.attempt && Regressions.isBaseline(it) }

    private fun alias(receipt: Receipt): String = aliasByReceipt[receipt.receiptId] ?: aliases.byCanonical(ids.work, receipt.receiptId)?.text ?: receipt.receiptId

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
            Closure.Unknown -> if (inputs.isEmpty()) emptyList() else filesUnder(".") + inputs
        }
        return declared.map { it.replace('\\', '/') }.filterNot { scratch.isScratch(it) }.distinct().sorted()
    }

    private fun filesUnder(prefix: String): List<String> {
        val root = if (prefix == "." || prefix.isEmpty() || prefix == "/") workspace.root else {
            val resolved = workspace.resolve(prefix, Intent.Read) as? PathResolution.Resolved ?: return emptyList()
            if (!Files.isDirectory(resolved.real)) return listOf(resolved.relative)
            resolved.real
        }
        return try {
            Files.walk(root).use { stream ->
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
    internal data class Seen(val version: FileVersion?, val sizeBytes: Long?, val modified: java.nio.file.attribute.FileTime?, val executable: Boolean? = null)

    private fun snapshot(path: String): Seen {
        val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return Seen(null, null, null)
        val attributes = try {
            Files.readAttributes(resolved.real, BasicFileAttributes::class.java)
        } catch (missing: IOException) {
            return Seen(null, null, null)
        }
        if (attributes.isDirectory) return Seen(null, null, null)
        return Seen(registry.read(path)?.version, attributes.size(), attributes.lastModifiedTime(), Files.isExecutable(resolved.real))
    }
}
