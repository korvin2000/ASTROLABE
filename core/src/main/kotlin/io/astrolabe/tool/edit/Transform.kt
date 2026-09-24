package io.astrolabe.tool.edit

import io.astrolabe.atlas.Language
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.EffectPolicy
import io.astrolabe.auth.ExecutionDecision
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.ExecutionModeLabel
import io.astrolabe.auth.Executors
import io.astrolabe.contract.Contract
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.os.Command
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Os
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import io.astrolabe.os.StatusEntry
import io.astrolabe.os.UntrackedFiles
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.ExpectedMatches
import io.astrolabe.tool.RunArgs
import io.astrolabe.tool.TransformArgs
import io.astrolabe.tool.run.Runner
import io.astrolabe.verify.SurfaceChange
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path

/**
 * How a transform script is dispatched (§9.2, D-41): the runner, the stamper that takes the post-hoc diff and the log
 * directory. A confined [runner] is the jail; the trusted-local one runs only when [executionMode] is authorized, and
 * its diff is not a jail: effects outside the workspace stay unobserved and every receipt says so.
 */
public data class TransformExecution @JvmOverloads constructor(
    val runner: Runner,
    val stamper: Stamper,
    val logsDir: Path,
    val executionMode: ExecutionMode = ExecutionMode.TrustedLocal,
    val environment: EnvPolicy = EnvPolicy(extra = mapOf("CI" to "1", "NO_COLOR" to "1")),
    val timeoutSeconds: Long = 600,
    val hostSets: Map<String, CapabilitySet> = emptyMap(),
) {
    init {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
    }
}

/** `inventory_ok: true | false | unspecified` (§9.2): an omitted inventory is explicitly unspecified, never ok. */
public enum class InventoryVerdict(public val wire: String) { Ok("true"), Failed("false"), Unspecified("unspecified") }

/** What a rejected transform left behind (§9.2): the actual effect, never a promised rollback. */
public enum class TransformEffect(public val wire: String) { Restored("restored"), Partial("partial"), UnknownOutcome("unknown_outcome") }

/** One changed file of the receipt: `+n −m`, its hunks and the versions before and after. */
public data class TransformFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: Int,
    val versionBefore: FileVersion?,
    val versionAfter: FileVersion?,
) {
    val line: String get() = "$path +$added −$removed @${versionBefore?.hash8 ?: "new"}→@${versionAfter?.hash8 ?: "gone"}"
}

/** A site the model may inspect (§9.2 representative and unusual sites): one hunk's first line. */
public data class TransformSite(val path: String, val line: Int, val text: String) {
    override fun toString(): String = "$path:$line ${text.ifEmpty { "(no text)" }}"
}

/**
 * The diff receipt of one `edit(transform)` (§9.2). It is *transformation-based validation*: the harness diffed the
 * tree, nobody read every edited byte. [accepted] is false when the transform was rejected (P3.3.2: out-of-scope
 * writes, a match count outside `expected_matches`, or a non-zero exit); [effect] then says what the guarded inverse
 * achieved, with [restored] and [notRestored] as the actual per-file state.
 */
public data class TransformReceipt(
    val editId: String,
    val scriptHash: Digest,
    val filesChanged: Int,
    val hunks: Int,
    val perFile: List<TransformFile>,
    /** The full unified diff, recallable by this blob id. */
    val diffRef: Digest,
    val syntax: Map<String, SyntaxResult>,
    val touchedOutsideScope: List<String>,
    val inventoryOk: InventoryVerdict,
    val matchCount: Int,
    val expectedMatches: ExpectedMatches?,
    val representativeSites: List<TransformSite>,
    val unusualSites: List<TransformSite>,
    val exitCode: Int?,
    val executionMode: ExecutionMode,
    val accepted: Boolean,
    val rejection: String? = null,
    val effect: TransformEffect? = null,
    val restored: List<String> = emptyList(),
    /** `path: reason` for every changed path the inverse could not or did not restore. */
    val notRestored: List<String> = emptyList(),
    val limits: List<String> = emptyList(),
)

/** What a transform op hands back to the edit batch: the receipt plus the per-file state the batch result carries. */
internal class TransformOutcome(
    val receipt: TransformReceipt?,
    val applied: List<AppliedOp>,
    val versions: Map<String, FileVersion?>,
    val diffstat: Map<String, DiffStat>,
    val syntax: Map<String, SyntaxResult>,
    val surface: List<SurfaceChange>,
    val error: EditError?,
)

/**
 * The scripted transform path (§9.2, P3.3.1–P3.3.2). Order is structural: the allowed inventory, the preconditions
 * and the expected-count policy are resolved before dispatch; a preimage of every in-scope file is recorded before
 * the script starts; the script runs through the [Runner] under the execution-mode authority of `run` (D-41); the
 * harness diffs the whole tree, so out-of-scope writes are seen; every changed file gets inline syntax; changed files
 * are announced to the registry (the coherence horizons refresh atlas rows and drop stale reads) and enter the
 * Workset as `touched-by-transform (NOT SEEN)`, so an anchored edit on one needs a fresh read.
 */
internal class TransformRun(
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val workset: Workset,
    private val os: Os,
    private val preimages: Preimages,
    private val blobs: BlobStore,
    private val syntax: SyntaxCheck,
    private val ids: Identities,
    private val exec: TransformExecution,
    private val pollSliceSeconds: Long = 5,
) {
    /** The workspace files the glob names right now, in path order; the scope guard sees this list before dispatch. */
    fun inventory(scopeGlob: String): List<String> {
        val tracked = workspace.git.lsFiles().map { it.path }
        val untracked = workspace.git.status(UntrackedFiles.ALL).entries.filterIsInstance<StatusEntry.Untracked>().map { it.path }
        return (tracked + untracked).distinct().filter { PathPattern.matches(scopeGlob, it) && registry.read(it) != null }.sortedWith(Stamper.PATH_ORDER)
    }

    fun apply(index: Int, args: TransformArgs, editId: String, alias: String, actionId: String, contract: Contract, inScope: List<String>, turn: Int): TransformOutcome {
        // Authority before any effect (D-41): the capability ceiling and execution mode of `run`, D-class refused outright.
        val argv = args.argv ?: listOf(args.script!!)
        val runArgs = if (args.argv != null) RunArgs(argv = args.argv) else RunArgs(cmd = args.script)
        val classification = EffectPolicy.classify(runArgs, workspace.root.toString(), contract.scope.protectedPaths)
        val ceiling = try {
            Ceiling.of(contract.authorization, exec.executionMode, exec.hostSets)
        } catch (misconfigured: IllegalArgumentException) {
            return refused(EditError("unsupported", index, null, "transform denied: ${misconfigured.message}"))
        }
        ceiling.allows(classification)?.let { return refused(EditError("scope", index, null, "transform denied by the capability ceiling: ${it.detail}")) }
        val decision = Executors.require(exec.executionMode)
        if (decision is ExecutionDecision.Refused) return refused(EditError("unsupported", index, null, "transform unsupported: ${decision.refusal.detail} (D-41)"))
        if (exec.runner.mode != exec.executionMode) return refused(EditError("unsupported", index, null, "transform unsupported: the runner is ${ExecutionModeLabel.short(exec.runner.mode)} but ${ExecutionModeLabel.short(exec.executionMode)} is configured (D-41)"))
        if (classification.effectClass == EffectClass.D) return refused(EditError("unsupported", index, null, "transform script is D-class (${classification.reasons.joinToString("; ")}); D-class effects go through run(intent=…), never a transform"))
        // Preconditions and the inventory are resolved before dispatch (§9.2): `path@version` pins, checked now.
        for (pin in args.preconditions.orEmpty()) {
            val at = pin.lastIndexOf('@')
            if (at <= 0 || at == pin.length - 1) return refused(EditError("unknown", index, null, "precondition '$pin' is not path@version"))
            val path = pin.substring(0, at)
            val version = pin.substring(at + 1).lowercase()
            val current = registry.read(path) ?: return refused(EditError("missing", index, path, "precondition '$pin': no file at '$path'"))
            if (!current.version.digest.hex.startsWith(version)) return refused(EditError("stale_expect", index, path, "precondition '$pin' fails: '$path' is @${current.version.hash8} now"))
        }
        val inventory = args.inventory?.map { it.replace('\\', '/').removePrefix("./") }?.toSet()
        val scriptHash = Digest.ofUtf8(argv.joinToString("\u0000"))

        // Preimages of every in-scope file before mutation (§9.2, F05), never after discovering the diff.
        val before = LinkedHashMap<String, Pair<FileVersion, ByteArray>>()
        for (path in inScope) {
            val content = registry.read(path) ?: continue
            preimages.save(editId, path, content.version, content.bytes)
            before[path] = content.version to content.bytes
        }
        val stampBefore = exec.stamper.report()
        val spec = SpawnSpec(
            command = if (args.argv != null) Command.Argv(args.argv) else Command.Shell(args.script!!),
            workingDirectory = workspace.root,
            logPath = logPath(actionId),
            environment = exec.environment,
            deadlineSeconds = exec.timeoutSeconds,
        )
        val proc = try {
            observe(exec.runner.start(spec))
        } catch (failure: IOException) {
            return refused(EditError("io", index, null, "transform cannot start: ${failure.message ?: failure::class.simpleName}; nothing was written (preimages ${before.size} recorded)"))
        }
        val stampAfter = exec.stamper.report()

        // The harness computes the diff over the whole tree: stamped members that moved plus in-scope files whose bytes moved.
        val changed = LinkedHashSet<String>()
        changed += Stamper.diff(stampBefore, stampAfter)
        for ((path, saved) in before) {
            val now = registry.read(path)
            if (now?.version != saved.first) changed += path
        }
        val changedInScope = changed.filter { PathPattern.matches(args.scopeGlob, it) }.sortedWith(Stamper.PATH_ORDER)
        val outside = changed.filterNot { PathPattern.matches(args.scopeGlob, it) }.sortedWith(Stamper.PATH_ORDER)
        val cause = "transform $alias"
        val postimages = LinkedHashMap<String, FileVersion?>()
        val perFile = ArrayList<TransformFile>()
        val hunksByFile = LinkedHashMap<String, List<LineDiff.Hunk>>()
        val limits = ArrayList<String>()
        val diffText = StringBuilder()
        val surface = ArrayList<SurfaceChange>()
        val applied = ArrayList<AppliedOp>()
        for (path in changedInScope) {
            val saved = before[path]
            val now = registry.read(path)
            val versionAfter = now?.version
            postimages[path] = versionAfter
            if (saved != null && versionAfter != null) preimages.recordPostimage(editId, path, versionAfter)
            if (now != null) blobs.put(now.bytes, BlobKind.POSTIMAGE, ids, recovery = true)
            registry.change(path, saved?.first ?: fromStamp(stampBefore, path), versionAfter, cause)
            val oldText = saved?.second?.let(::decodeStrict)
            val newText = now?.bytes?.let(::decodeStrict)
            val hunks = when {
                saved == null && newText != null -> LineDiff.hunks("", newText)
                now == null && oldText != null -> LineDiff.hunks(oldText, "")
                oldText != null && newText != null -> LineDiff.hunks(oldText, newText)
                else -> null
            }
            if (hunks == null) limits += "$path: binary or too large to diff; counted as one hunk"
            hunksByFile[path] = hunks ?: listOf(LineDiff.Hunk(1, 1, emptyList(), listOf("(binary or too large to diff)")))
            val added = hunks?.sumOf { it.added.size } ?: 0
            val removed = hunks?.sumOf { it.removed.size } ?: 0
            perFile += TransformFile(path, added, removed, hunksByFile[path]!!.size, saved?.first, versionAfter)
            surface += SurfaceChange(path, oldText, newText)
            applied += AppliedOp(index, "transform", path, saved?.first, versionAfter, saved?.let { preimages.of(editId, path)?.preimageDigest?.hex })
            diffText.append("=== $path @${saved?.first?.hash8 ?: "new"}→@${versionAfter?.hash8 ?: "gone"} +$added −$removed\n")
            diffText.append(if (hunks == null) "(binary or too large to diff)" else LineDiff.unified(oldText ?: "", newText ?: "", context = 2, maxOutputLines = MAX_DIFF_LINES_PER_FILE) ?: "").append("\n")
        }
        for (path in outside) {
            // Outside the glob there is no preimage: the move is announced so the horizons drop reads, and it is reported, not hidden.
            registry.change(path, fromStamp(stampBefore, path) ?: registry.recorded(path), registry.read(path)?.version, cause)
            applied += AppliedOp(index, "transform", path, null, registry.read(path)?.version)
            diffText.append("=== $path outside scope_glob ${args.scopeGlob}: no preimage, no diff\n")
        }
        val diffRef = blobs.put(diffText.toString().toByteArray(Charsets.UTF_8), BlobKind.DIFF, ids)
        val syntaxResults = LinkedHashMap<String, SyntaxResult>()
        for (path in changedInScope) {
            if (postimages[path] == null) continue
            val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: continue
            syntaxResults[path] = syntax.check(path, resolved.real, Language.of(path))
        }
        val matchCount = hunksByFile.values.sumOf { it.size }
        val inventoryOk = when {
            inventory == null -> InventoryVerdict.Unspecified
            changedInScope.all { it in inventory } -> InventoryVerdict.Ok
            else -> InventoryVerdict.Failed
        }
        val exitCode = (proc.status as? ProcStatus.Exited)?.exitCode
        val expected = args.expectedMatches
        val rejection = when {
            outside.isNotEmpty() -> "touched outside scope_glob ${args.scopeGlob}: ${outside.joinToString(", ")}"
            expected != null && matchCount !in expected.min..expected.max -> "match count $matchCount outside expected ${expected.min}–${expected.max}"
            proc.status == ProcStatus.DeadlineExceeded -> "script exceeded ${exec.timeoutSeconds}s; the process tree was killed"
            proc.status is ProcStatus.Lost || proc.status is ProcStatus.Cancelled -> "script observation lost (${proc.status})"
            exitCode != null && exitCode != 0 -> "script exited $exitCode"
            else -> null
        }
        var receipt = TransformReceipt(
            editId = editId, scriptHash = scriptHash, filesChanged = changedInScope.size, hunks = matchCount, perFile = perFile, diffRef = diffRef,
            syntax = syntaxResults, touchedOutsideScope = outside, inventoryOk = inventoryOk, matchCount = matchCount, expectedMatches = expected,
            representativeSites = representative(changedInScope, hunksByFile), unusualSites = unusual(hunksByFile), exitCode = exitCode,
            executionMode = exec.executionMode, accepted = rejection == null, rejection = rejection, limits = limits,
        )
        val versions = LinkedHashMap<String, FileVersion?>()
        applied.forEach { versions[it.path] = it.versionAfter }
        if (rejection == null) {
            for (path in changedInScope) {
                val now = registry.read(path) ?: continue
                val lines = decodeStrict(now.bytes)?.let { contentLines(it).size } ?: 1
                if (lines > 0) workset.register(Entry(path, Ranges.single(1, lines), now.version, EntrySource.Transform, turn, null, 0, Ranges.single(1, lines)))
            }
            return TransformOutcome(receipt, applied, versions, perFile.associate { it.path to DiffStat(it.added, it.removed) }, syntaxResults, surface, null)
        }
        // P3.3.2: the transform is not accepted. No isolated candidate was used (D-96), so the guarded inverse runs where the
        // current bytes are still this transform's own postimages; everything else is reported as it is.
        val restored = ArrayList<String>()
        val notRestored = ArrayList<String>()
        for (path in changedInScope) {
            val saved = before[path]
            val postimage = postimages[path]
            val current = registry.read(path)?.version
            if (current != postimage) {
                notRestored += "$path: diverged since the transform (@${current?.hash8 ?: "gone"} now); the inverse is refused (FX-05)"
                continue
            }
            val resolved = workspace.resolve(path, Intent.Mutate)
            if (resolved !is PathResolution.Resolved) {
                notRestored += "$path: ${(resolved as PathResolution.Rejected).detail}"
                continue
            }
            try {
                if (saved == null) Files.delete(resolved.real) else os.replaceFileAtomically(resolved.real, saved.second)
            } catch (failure: Exception) {
                notRestored += "$path: ${failure.message ?: failure::class.simpleName}"
                continue
            }
            registry.change(path, postimage, saved?.first, "$cause inverse")
            versions[path] = saved?.first
            restored += path
        }
        for (path in outside) notRestored += "$path: outside scope_glob, no preimage recorded"
        val effect = when {
            proc.status is ProcStatus.Lost || proc.status is ProcStatus.Cancelled -> TransformEffect.UnknownOutcome
            notRestored.isEmpty() -> TransformEffect.Restored
            else -> TransformEffect.Partial
        }
        receipt = receipt.copy(effect = effect, restored = restored, notRestored = notRestored)
        val finalApplied = applied.map { it.copy(versionAfter = versions[it.path]) }
        val error = EditError("transform", index, outside.firstOrNull(), "$rejection · effect: ${effect.wire}" + (if (notRestored.isEmpty()) "" else " · not restored: " + notRestored.joinToString("; ")))
        return TransformOutcome(receipt, finalApplied, versions, perFile.associate { it.path to DiffStat(it.added, it.removed) }, syntaxResults, surface, error)
    }

    private fun refused(error: EditError) = TransformOutcome(null, emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), error)

    private fun fromStamp(report: StampReport, path: String): FileVersion? = report.members[path]?.digest?.let(::FileVersion)

    /** Observes the process to its terminal state; the deadline kills the tree, nothing replays. */
    private fun observe(start: Proc): Proc {
        var current = start
        var cursor = 0L
        while (!current.status.isTerminal) {
            val poll = os.poll(current, cursor, minOf(pollSliceSeconds, exec.timeoutSeconds + 5))
            cursor = poll.nextCursorBytes
            current = current.copy(status = poll.status)
        }
        return current
    }

    private fun logPath(actionId: String): Path {
        Files.createDirectories(exec.logsDir)
        return exec.logsDir.resolve("$actionId.log")
    }

    /** The first hunk of the first, middle and last changed file (§9.2 three representative sites). */
    private fun representative(paths: List<String>, hunks: Map<String, List<LineDiff.Hunk>>): List<TransformSite> {
        if (paths.isEmpty()) return emptyList()
        val picks = listOf(0, paths.size / 2, paths.size - 1).distinct().map { paths[it] }
        return picks.mapNotNull { path -> hunks[path]?.firstOrNull()?.let { site(path, it) } }
    }

    /** The three largest hunks whose shape (removed × added) is not the modal shape; empty when every hunk looks alike. */
    private fun unusual(hunks: Map<String, List<LineDiff.Hunk>>): List<TransformSite> {
        val all = hunks.flatMap { (path, list) -> list.map { path to it } }
        if (all.isEmpty()) return emptyList()
        val mode = all.groupingBy { it.second.removed.size to it.second.added.size }.eachCount().maxByOrNull { it.value }!!.key
        return all.filter { (it.second.removed.size to it.second.added.size) != mode }
            .sortedWith(compareByDescending<Pair<String, LineDiff.Hunk>> { it.second.removed.size + it.second.added.size }.thenBy(Stamper.PATH_ORDER) { it.first }.thenBy { it.second.newFrom })
            .take(3)
            .map { site(it.first, it.second) }
    }

    private fun site(path: String, hunk: LineDiff.Hunk): TransformSite =
        TransformSite(path, if (hunk.added.isEmpty()) hunk.oldFrom else hunk.newFrom, (hunk.added.firstOrNull() ?: hunk.removed.firstOrNull()).orEmpty().trim().take(80))

    private fun contentLines(text: String): List<String> = text.lines().let { if (text.endsWith("\n") && it.isNotEmpty()) it.dropLast(1) else it }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (malformed: CharacterCodingException) {
        null
    }

    internal companion object {
        const val MAX_DIFF_LINES_PER_FILE: Int = 2_000
    }
}
