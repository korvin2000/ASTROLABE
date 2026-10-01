package io.astrolabe.tool.edit

import io.astrolabe.atlas.Language
import io.astrolabe.atlas.Outline
import io.astrolabe.auth.ContentClass
import io.astrolabe.auth.ExecutionModeLabel
import io.astrolabe.auth.InstructionShape
import io.astrolabe.auth.Redaction
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Increment
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Observation
import io.astrolabe.evidence.Observations
import io.astrolabe.evidence.RedactionMask
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Generation
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Os
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.tool.Args
import io.astrolabe.tool.EditArgs
import io.astrolabe.tool.EditOpArgs
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolCall
import io.astrolabe.tool.ToolExecutor
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.verify.Checks
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.verify.ScopeVerdict
import io.astrolabe.verify.SurfaceChange
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.Ranges
import io.astrolabe.workspace.RestoreResult
import io.astrolabe.workspace.RevertResult
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files

public data class DiffStat(val added: Int, val removed: Int) {
    override fun toString(): String = "+$added −$removed"
}

/** A post-edit view (§9.1): the changed region ±3 lines at the new version, which becomes displayed coverage. */
public data class View(val path: String, val range: LineRange, val version: FileVersion, val text: String)

/** Why the batch was refused or where it stopped; typed so the retry costs no re-read (§5.4 error policy). */
public data class EditError(
    /** `scope` · `missing` · `expect` · `stale_expect` · `anchor` · `outside_displayed` · `overlap` · `exists` · `unsupported` · `divergent` · `unknown` · `io`. */
    val kind: String,
    val opIndex: Int?,
    val path: String?,
    val detail: String,
    val candidates: List<Candidate> = emptyList(),
    val sites: List<LineRange> = emptyList(),
    val diffSinceExpect: String? = null,
    val displayed: Ranges? = null,
)

/** One op that reached the workspace, with the preimage that makes it reversible. */
public data class AppliedOp(
    val opIndex: Int,
    val kind: String,
    val path: String,
    val versionBefore: FileVersion?,
    val versionAfter: FileVersion?,
    val preimageRef: String? = null,
)

/** D-371: the ops of one path group refused in preflight, with the first refused op's diagnostics; the other groups applied. */
public data class RefusedGroup(val paths: List<String>, val ops: List<Int>, val error: EditError)

/** The typed result of one `edit` call (§5.4). [applied] is the actual per-file state, never a claimed rollback. */
public data class EditResult(
    val ok: Boolean,
    val editId: String,
    val applied: List<AppliedOp>,
    val views: List<View>,
    val versions: Map<String, FileVersion?>,
    val syntax: Map<String, SyntaxResult>,
    val diffstat: Map<String, DiffStat>,
    val touchedOutsideScope: List<String>,
    val testIntegrity: List<TestIntegrityFlag>,
    val error: EditError? = null,
    /** The diff receipt when the batch was one `transform` op (§9.2); a rejected transform keeps its receipt. */
    val transform: TransformReceipt? = null,
    /** Every refused path group of the batch (D-371); [error] is the first of them when nothing applied. */
    val refused: List<RefusedGroup> = emptyList(),
    /** Per applied op index: how it was applied when that differs from the plain form (normalised anchor, replace). */
    val notes: Map<Int, String> = emptyMap(),
) {
    /** Some ops reached the workspace before the batch stopped (mid-batch failure, §9.1). */
    val partial: Boolean get() = !ok && (applied.isNotEmpty() || error?.kind == "io")
}

/**
 * The `edit` family (§9.1, §9.3, §9.5, TODO P1.6.4): anchored compare-and-swap hunks, `create`, `delete`,
 * `rename`, `revert:#id`, `revert:turn:N` and, when a [TransformExecution] is given, the scripted `transform`
 * of §9.2 (P3.3), which is a batch of its own (D-95).
 *
 * Every op is preflighted before any write — committed-contract scope and protected paths through the
 * [ScopeGuard], `expect` re-hashed from raw bytes, anchors located (D-33) inside the dispatch-time displayed
 * coverage, hunks non-overlapping, unsupported kinds refused by name. Ops are grouped by the paths they touch
 * (D-371): a refusal writes nothing of its group, and the other groups still apply.
 * Application holds the workspace mutation lock, saves the preimage before each write, publishes the
 * postimage bytes under their version, announces every transition to the version registry (which is what
 * the coherence horizons hear), and reports a mid-batch I/O failure as the actual per-file state with the
 * preimage ids: never "rolled back", never retried. Post-edit views ±3 lines are the new displayed ranges.
 */
public class Edit(
    private val workspace: Workspace,
    private val registry: VersionRegistry,
    private val workset: Workset,
    private val os: Os,
    private val preimages: Preimages,
    private val scopeGuard: ScopeGuard,
    private val contracts: Contracts,
    private val checks: Checks,
    private val observations: Observations,
    private val aliases: Aliases,
    private val blobs: BlobStore,
    private val redaction: Redaction,
    private val estimator: TokenEstimator,
    private val idGen: IdGen,
    private val ids: Identities,
    private val syntax: SyntaxCheck,
    private val shadowRef: ShadowRef? = null,
    private val mask: ToolMask = ToolOps.implementingS0,
    private val viewContextLines: Int = 3,
    /** Without it every `transform` is `unsupported`: no runner means no jail and no post-hoc diff (D-41). */
    transforms: TransformExecution? = null,
) : ToolExecutor {
    init {
        require(ids.context != null) { "edit runs inside a cell: ids.context is its lineage" }
        require(viewContextLines >= 0) { "viewContextLines must be ≥ 0" }
    }

    private val transformRun: TransformRun? = transforms?.let { TransformRun(workspace, registry, workset, os, preimages, blobs, syntax, ids, it, redaction) }

    private val receipts = ArrayList<TransformReceipt>()

    /** Every transform receipt of this cell, accepted or rejected, in order; the Result Packet carries them (§5.9, P4.4.3). */
    public val transforms: List<TransformReceipt> get() = receipts.toList()

    /** The increment whose write scope earns a warning when crossed (§8.6); the cell sets it. */
    public var increment: Increment? = null

    public var generation: Generation = Generation.INITIAL

    internal var beforeDispatch: () -> Unit = {}
        set(value) {
            field = value
            transformRun?.beforeDispatch = value
        }

    private val flagsByAlias = java.util.concurrent.ConcurrentHashMap<String, List<TestIntegrityFlag>>()

    /** The classified acceptance-surface flags of the edit with [alias] (§8.6), its `why` as the recorded reason. */
    public fun flagsOf(alias: String): List<TestIntegrityFlag> = flagsByAlias[alias].orEmpty()

    /** D-371: files whose whole content this cell wrote (create, replace, then its own anchored edits), by identity → the version it left. */
    private val authored = HashMap<String, FileVersion>()

    /** Whether this cell was already warned for crossing the increment's write scope (§8.6: warning once). */
    private var warnedOutsideIncrement = false

    private fun justified(path: String, why: String): Boolean = path in why || path.substringAfterLast('/') in why

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Edit) { "not an edit call: ${call.name}" }
        val args = (call.args as Args.Edit).args
        val editId = idGen.next("edit")
        val actionId = idGen.next("act")
        val alias = aliases.allocate(ids.work, editId, "edit", ids.context, workspace.id).text
        val contract = contracts.current(ids.work)
            ?: return render(args, alias, actionId, EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), EditError("unknown", null, null, "no committed contract for ${ids.work}")), context)
        if (call.operationNames.any { !mask.allows(it) }) {
            return render(args, alias, actionId, EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), EditError("unsupported", null, null, "${call.name} is masked in this role")), context)
        }
        val result = workspace.mutation.withLock {
            beforeDispatch()
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                run(args, contracts.current(ids.work) ?: contract, context, editId, alias, actionId)
            }
        }
        return render(args, alias, actionId, result, context)
    }

    // ------------------------------------------------------------- preflight

    private sealed interface Plan {
        val index: Int
        val path: String
    }

    private class AnchoredPlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray, val oldText: String, val hunks: List<Pair<Located, String>>, val normalised: Boolean) : Plan
    private class CreatePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val bytes: ByteArray) : Plan
    private class DeletePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray) : Plan

    /** D-365: `delete` then `create` of one path in one batch, applied as a whole-file replace at the create's place. */
    private class ReplacePlan(override val index: Int, val deleteIndex: Int, override val path: String, val resolved: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray, val bytes: ByteArray, val note: String) : Plan
    private class RenamePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val to: String, val target: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray) : Plan
    private class RevertEditPlan(override val index: Int, val editId: String, val paths: List<String>) : Plan {
        override val path: String get() = paths.joinToString(", ")
    }
    private class RevertTurnPlan(override val index: Int, val turn: Int) : Plan {
        override val path: String get() = "turn:$turn"
    }

    private class Refusal(val error: EditError) : RuntimeException(error.detail)

    private fun run(args: EditArgs, contract: Contract, context: TurnContext, editId: String, alias: String, actionId: String): EditResult {
        val none = EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
        val transform = args.ops.firstOrNull { it.kind == "transform" }?.transform
        args.ops.forEachIndexed { i, op ->
            if (op.kind == "transform" && transformRun == null) return none.copy(error = EditError("unsupported", i + 1, null, "transform unsupported: this cell has no transform runner (D-41)"))
            if (op.kind == "transform" && args.ops.size > 1) return none.copy(error = EditError("unsupported", i + 1, null, "a transform is a batch of its own (D-95); send it alone"))
            if (op.kind == "invalid") return none.copy(error = EditError("unsupported", i + 1, null, "op ${i + 1} names no supported form"))
            if (op.kind == "revert" && args.ops.size > 1) return none.copy(error = EditError("overlap", i + 1, null, "send a revert alone so its restore paths cannot conflict with another operation"))
        }
        if (transform != null) {
            // Scope first (§8.6): the transform's allowed inventory is resolved here, before dispatch (§9.2).
            val inScope = transformRun!!.inventory(transform.scopeGlob)
            val verdict = scopeGuard.check(inScope, contract, increment)
            if (verdict is ScopeVerdict.Refused) return none.copy(error = scopeError(null, verdict))
            val outside = (verdict as ScopeVerdict.Allowed).outsideIncrement
            // §8.6: the first crossing of the increment's write scope warns; every later one names its paths in `why` (D-74).
            unjustified(outside, args.why)?.let { return none.copy(error = it.copy(opIndex = null), touchedOutsideScope = outside) }
            if (outside.isNotEmpty()) warnedOutsideIncrement = true
            if (inScope.isEmpty()) return none.copy(error = EditError("missing", 1, null, "scope_glob '${transform.scopeGlob}' names no workspace file"), touchedOutsideScope = outside)
            val outcome = transformRun!!.apply(1, transform, editId, alias, actionId, contract, inScope, context.turn) { changed ->
                val current = contracts.current(ids.work)
                if (current == null || current.version != contract.version) "contract changed during transform"
                else (scopeGuard.check(changed, current, increment) as? ScopeVerdict.Refused)?.refusals
                    ?.joinToString("; ") { "${it.path}: ${it.kind.name.lowercase()} — ${it.detail}" }
            }
            val flags = TestIntegrity.classify(outcome.surface, "transform $alias", contract, checks).map { it.copy(reason = transform.why) }
            flagsByAlias[alias] = flags
            outcome.receipt?.let { receipts += it }
            return EditResult(outcome.error == null, editId, outcome.applied, emptyList(), outcome.versions, outcome.syntax, outcome.diffstat, outside, flags, outcome.error, outcome.receipt)
        }
        // D-371: each path group is checked and preflighted on its own; a refusal refuses its group, never the batch.
        val groups = groups(args.ops)
        val refused = LinkedHashMap<Group, EditError>()
        val crossing = LinkedHashMap<Group, List<String>>()
        for (group in groups) {
            when (val verdict = scopeGuard.check(group.paths, contract, increment)) {
                is ScopeVerdict.Refused -> refused[group] = scopeError(group.ops.firstOrNull { verdict.refusals.first().path in group.pathsOf[it].orEmpty() }?.plus(1), verdict)
                is ScopeVerdict.Allowed -> crossing[group] = verdict.outsideIncrement
            }
        }
        // §8.6: the first crossing of the increment's write scope warns; every later one names its paths in `why` (D-74).
        for ((group, paths) in crossing) unjustified(paths, args.why)?.let { refused[group] = it.copy(opIndex = group.ops.first() + 1) }
        val outside = crossing.values.flatten().distinct()
        if (outside.isNotEmpty()) warnedOutsideIncrement = true
        val plans = ArrayList<Plan>()
        for (group in groups) {
            if (group in refused) continue
            try {
                plans += plan(group, args.ops, context)
            } catch (refusal: Refusal) {
                refused[group] = refusal.error
            }
        }
        val refusals = groups.filter { it in refused }.map { RefusedGroup(it.paths, it.ops.map { i -> i + 1 }, refused.getValue(it)) }
        if (plans.isEmpty()) return none.copy(error = refusals.first().error, touchedOutsideScope = outside, refused = refusals)
        val result = apply(plans.sortedBy { it.index }, contract, context, editId, alias, outside, args.why)
        return result.copy(ok = result.ok && refusals.isEmpty(), refused = refusals)
    }

    /** D-371: the ops of a batch that touch one canonical path (a rename both names), in op order; [pathsOf] by op index. */
    private class Group(val ops: List<Int>, val pathsOf: Map<Int, List<String>>) {
        val paths: List<String> get() = ops.flatMap { pathsOf.getValue(it) }.distinct()
    }

    private fun groups(ops: List<EditOpArgs>): List<Group> {
        val pathsOf = ops.indices.associateWith { i -> ops[i].let { op -> listOfNotNull(op.path, op.create, op.delete, op.rename, op.to) + revertPaths(op) } }
        val root = IntArray(ops.size) { it }
        fun find(i: Int): Int = if (root[i] == i) i else find(root[i]).also { root[i] = it }
        val owner = HashMap<String, Int>()
        for (i in ops.indices) {
            for (path in pathsOf.getValue(i)) {
                val key = when (val resolved = workspace.resolve(path, Intent.Mutate)) {
                    is PathResolution.Resolved -> owned(resolved)
                    is PathResolution.Rejected -> "\u0000$path"
                }
                val first = owner.putIfAbsent(key, i) ?: continue
                val (a, b) = find(first) to find(i)
                if (a != b) root[maxOf(a, b)] = minOf(a, b)
            }
        }
        return ops.indices.groupBy(::find).values.map { Group(it, pathsOf) }
    }

    /** One group's plans in op order; the first refused op refuses the group. */
    private fun plan(group: Group, ops: List<EditOpArgs>, context: TurnContext): List<Plan> {
        val planned = ArrayList<Plan>()
        val deleted = HashMap<String, DeletePlan>()
        for (i in group.ops) {
            val op = ops[i]
            // D-365: a create of a path this batch deletes earlier replaces it; the delete's expect guards the bytes.
            val earlier = op.create?.let { deleted.remove(identity(mutable(i + 1, it).real)) }
            val plan = if (earlier != null) {
                planned.remove(earlier)
                ReplacePlan(i + 1, earlier.index, earlier.path, earlier.resolved, earlier.expect, earlier.oldBytes, op.content!!.toByteArray(Charsets.UTF_8), REPLACED_BY_DELETE)
            } else {
                preflight(i + 1, op, context)
            }
            if (plan is DeletePlan) deleted[identity(plan.resolved.real)] = plan
            planned += plan
        }
        val claimed = HashSet<String>()
        for (plan in planned) {
            val targets = when (plan) {
                is AnchoredPlan -> listOf(plan.resolved.real)
                is CreatePlan -> listOf(plan.resolved.real)
                is DeletePlan -> listOf(plan.resolved.real)
                is ReplacePlan -> listOf(plan.resolved.real)
                is RenamePlan -> listOf(plan.resolved.real, plan.target.real)
                is RevertEditPlan, is RevertTurnPlan -> emptyList()
            }
            if (targets.any { !claimed.add(identity(it)) }) {
                throw Refusal(EditError("overlap", plan.index, plan.path, "multiple operations touch the same canonical path; combine hunks in one operation or send separate batches"))
            }
        }
        return planned
    }

    /** The workspace-relative key of a path (groups, [authored]): stable whether or not the file exists when resolved. */
    private fun owned(resolved: PathResolution.Resolved): String =
        if (workspace.paths.caseInsensitive) resolved.relative.lowercase(java.util.Locale.ROOT) else resolved.relative

    /** A path that does not exist yet keeps its typed case, so identity folds case where the filesystem does. */
    private fun identity(path: java.nio.file.Path): String =
        if (workspace.paths.caseInsensitive) path.toString().lowercase(java.util.Locale.ROOT) else path.toString()

    private fun scopeError(opIndex: Int?, verdict: ScopeVerdict.Refused): EditError =
        EditError("scope", opIndex, verdict.refusals.first().path, verdict.refusals.joinToString("; ") { "${it.path}: ${it.kind.name.lowercase()} — ${it.detail}" })

    private fun unjustified(outside: List<String>, why: String): EditError? {
        val unjustified = if (warnedOutsideIncrement) outside.filterNot { justified(it, why) } else emptyList()
        if (unjustified.isEmpty()) return null
        return EditError("scope", null, unjustified.first(), "outside the increment's write scope again: ${unjustified.joinToString(", ")} — name each path in `why` with the reason, or task.propose(increment_split)")
    }

    private fun revertPaths(op: EditOpArgs): List<String> {
        val target = op.revert ?: return emptyList()
        if (target.startsWith("turn:")) return target.removePrefix("turn:").toIntOrNull()?.let { shadowRef?.restorePaths(it) }.orEmpty()
        val editId = editIdOf(target) ?: return emptyList()
        return preimages.of(editId).map { it.path }
    }

    private fun editIdOf(target: String): String? {
        val number = Aliases.parse(target)
        return if (number != null) aliases.resolve(ids.work, number)?.takeIf { it.kind == "edit" }?.canonicalId else target.trim().takeIf { it.isNotEmpty() }
    }

    private fun preflight(index: Int, op: EditOpArgs, context: TurnContext): Plan = when (op.kind) {
        "anchored" -> preflightAnchored(index, op, context)
        "create" -> {
            val path = op.create!!
            val resolved = mutable(index, path)
            val bytes = op.content!!.toByteArray(Charsets.UTF_8)
            val existing = registry.read(path)
            if (existing == null) {
                CreatePlan(index, path, resolved, bytes)
            } else {
                val note = ownership(path, resolved, existing, context)
                    ?: throw Refusal(EditError("exists", index, path, "'$path' exists; read it first, or use {delete} then {create} in one batch"))
                ReplacePlan(index, index, path, resolved, existing.version, existing.bytes, bytes, note)
            }
        }
        "delete" -> {
            val path = op.delete!!
            val resolved = mutable(index, path)
            val content = current(index, path, expected(index, path, op.expect, context))
            DeletePlan(index, path, resolved, content.version, content.bytes)
        }
        "rename" -> {
            val from = op.rename!!
            val to = op.to!!
            if (from != to && from.equals(to, ignoreCase = true)) throw Refusal(EditError("unsupported", index, from, "case-only rename '$from' → '$to' is an unsupported mutation kind (§9.5)"))
            val resolved = mutable(index, from)
            val target = mutable(index, to)
            val content = current(index, from, expected(index, from, op.expect, context))
            if (registry.read(to) != null) throw Refusal(EditError("exists", index, to, "rename target '$to' exists"))
            RenamePlan(index, from, resolved, to, target, content.version, content.bytes)
        }
        "revert" -> {
            val target = op.revert!!
            if (target.startsWith("turn:")) {
                val turn = target.removePrefix("turn:").toIntOrNull() ?: throw Refusal(EditError("unknown", index, null, "revert target must be #id or turn:N, got '$target'"))
                val ref = shadowRef ?: throw Refusal(EditError("unsupported", index, null, "revert:turn:N needs the shadow ref, which this cell has not been given"))
                if (ref.record(turn) == null) throw Refusal(EditError("unknown", index, null, "no shadow snapshot for turn $turn"))
                RevertTurnPlan(index, turn)
            } else {
                val editId = editIdOf(target) ?: throw Refusal(EditError("unknown", index, null, "revert target must be #id or turn:N, got '$target'"))
                val recorded = preimages.of(editId)
                if (recorded.isEmpty()) throw Refusal(EditError("unknown", index, null, "'$target' names no edit with preimages in this attempt"))
                RevertEditPlan(index, editId, recorded.map { it.path })
            }
        }
        else -> throw Refusal(EditError("unsupported", index, null, "unsupported op kind '${op.kind}'"))
    }

    private fun preflightAnchored(index: Int, op: EditOpArgs, context: TurnContext): AnchoredPlan {
        val path = op.path!!
        val resolved = mutable(index, path)
        val expect = expected(index, path, op.expect, context)
        val content = current(index, path, expect)
        val text = decodeStrict(content.bytes) ?: throw Refusal(EditError("unsupported", index, path, "'$path' is not valid UTF-8 text; binary changes need an explicit operation (§9.5)"))
        val eol = dominantEol(text)
        val tabIndented = tabIndented(text)
        val located = op.hunks!!.map { hunk ->
            when (val location = Anchors.locate(text, hunk.anchor, hunk.near)) {
                is Location.One -> {
                    val atLineStart = location.span.start == 0 || text[location.span.start - 1] == '\n'
                    val lf = hunk.new.replace("\r\n", "\n")
                    val replaced = text.substring(location.span.start, location.span.end).replace("\r\n", "\n")
                    location.span to (if (tabIndented) tabsFor(lf, replaced, atLineStart) else lf).replace("\n", eol)
                }
                is Location.None -> throw Refusal(EditError("anchor", index, path, "anchor 0× in '$path'" + (if (location.candidates.isEmpty()) "" else "; nearest: " + location.candidates.joinToString(" · ") { "${it.line}: ${it.text}" }), candidates = location.candidates))
                is Location.Many -> throw Refusal(EditError("anchor", index, path, "anchor ${location.sites.size}× in '$path': sites " + location.sites.joinToString(", ") { "${it.lines}" } + " — add near=", sites = location.sites.map { it.lines }))
            }
        }
        for ((span, _) in located) {
            if (!context.coverage.covers(path, expect, span.lines)) {
                val displayed = context.coverage.coverage(path, expect)
                throw Refusal(
                    EditError(
                        "outside_displayed", index, path,
                        "hunk at '$path:${span.lines}' lies outside the displayed ranges of @${expect.hash8} (${if (displayed.isEmpty) "nothing displayed" else displayed.toString()}); read it first\n" + outlineOf(path, content.bytes),
                        displayed = displayed,
                    ),
                )
            }
        }
        val sorted = located.sortedBy { it.first.start }
        for (i in 1 until sorted.size) {
            if (sorted[i].first.start < sorted[i - 1].first.end) {
                throw Refusal(EditError("overlap", index, path, "hunks overlap in '$path': ${sorted[i - 1].first.lines} and ${sorted[i].first.lines}"))
            }
        }
        return AnchoredPlan(index, path, resolved, expect, content.bytes, text, located, located.any { !it.first.exact })
    }

    /**
     * D-371: a `create` may replace an existing file whose bytes this cell authored (created or replaced it, and
     * nothing else wrote it since) or whose every line is KNOWN at its current version; null keeps the refusal.
     */
    private fun ownership(path: String, resolved: PathResolution.Resolved, existing: io.astrolabe.workspace.FileContent, context: TurnContext): String? {
        if (authored[owned(resolved)] == existing.version) return "create over a file this cell wrote: replaced in place"
        val lines = decodeStrict(existing.bytes)?.let { contentLines(it).size } ?: return null
        if (lines == 0 || context.coverage.covers(path, existing.version, LineRange(1, lines))) return "create over a file KNOWN in full: replaced in place"
        return null
    }

    private fun mutable(index: Int, path: String): PathResolution.Resolved = when (val resolved = workspace.resolve(path, Intent.Mutate)) {
        is PathResolution.Resolved -> resolved
        is PathResolution.Rejected -> throw Refusal(EditError("scope", index, path, "'$path' refused: ${resolved.detail}"))
    }

    /** CAS on `expect` (§9.1): the raw bytes are hashed now; a stale version answers with the diff since `expect`. */
    private fun current(index: Int, path: String, expect: FileVersion): io.astrolabe.workspace.FileContent {
        val content = registry.read(path) ?: throw Refusal(EditError("missing", index, path, "no file at '$path'"))
        if (content.version != expect) {
            val shown = if (blobs.exists(expect.digest)) decodeStrict(blobs.get(expect.digest)) else null
            val diff = shown?.let { decodeStrict(content.bytes)?.let { now -> LineDiff.unified(it, now) } }
            throw Refusal(
                EditError(
                    "stale_expect", index, path,
                    "'$path' is @${content.version.hash8} now, not @${expect.hash8}" + (diff?.let { "; diff since expect:\n$it" } ?: "; the bytes shown as @${expect.hash8} are not in the store: read it again"),
                    diffSinceExpect = diff,
                ),
            )
        }
        return content
    }

    /**
     * The version an op's `expect` names (§9.1, D-346): a full hash as given; an omitted one is the one version KNOWN at
     * dispatch; a short one (≥ [MIN_EXPECT] hex) is the one version shown in this cell it prefixes, KNOWN or dropped
     * as stale. Never the current bytes: [current] still compares the named version with them (stale-write protection).
     */
    private fun expected(index: Int, path: String, expect: String?, context: TurnContext): FileVersion {
        val hex = expect?.trim()?.removePrefix("@")?.lowercase().orEmpty()
        if (hex.length == FULL_EXPECT && hex.all(::isHex)) return FileVersion(io.astrolabe.id.Digest(hex))
        val known = context.coverage.versions(path)
        fun list(versions: Collection<FileVersion>) = versions.joinToString(", ") { "@${it.hash8}" }
        if (hex.isEmpty()) {
            return known.singleOrNull() ?: throw Refusal(
                EditError(
                    "expect", index, path,
                    if (known.isEmpty()) "expect omitted and no version of '$path' is KNOWN: read it first, or send the hash it was shown with"
                    else "expect omitted and ${known.size} versions of '$path' are KNOWN (${list(known)}): send the one you read",
                ),
            )
        }
        if (hex.length !in MIN_EXPECT until FULL_EXPECT || !hex.all(::isHex)) {
            throw Refusal(EditError("expect", index, path, "expect '$expect' is not a content hash: send the hash '$path' was shown with (at least $MIN_EXPECT hex characters) or omit it"))
        }
        val shown = known + workset.history(path)
        val matches = shown.filter { it.digest.hex.startsWith(hex) }
        return matches.singleOrNull() ?: throw Refusal(
            EditError(
                "expect", index, path,
                if (matches.isEmpty()) "expect @$hex names no version of '$path' shown in this cell (${if (shown.isEmpty()) "none shown" else "shown: ${list(shown)}"}); read it first"
                else "expect @$hex matches ${matches.size} versions of '$path' shown in this cell (${list(matches)}); send more characters",
            ),
        )
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

    // ----------------------------------------------------------------- apply

    private fun apply(plans: List<Plan>, contract: Contract, context: TurnContext, editId: String, alias: String, outside: List<String>, why: String): EditResult {
        val applied = ArrayList<AppliedOp>()
        val views = ArrayList<View>()
        val versions = LinkedHashMap<String, FileVersion?>()
        val diffstat = LinkedHashMap<String, DiffStat>()
        val written = LinkedHashMap<String, PathResolution.Resolved>()
        val notes = LinkedHashMap<Int, String>()
        val cause = "edit $alias"
        var error: EditError? = null
        loop@ for (plan in plans) {
            beforeDispatch()
            try {
                when (plan) {
                    is AnchoredPlan -> {
                        val newText = replace(plan.oldText, plan.hunks)
                        val newBytes = newText.toByteArray(Charsets.UTF_8)
                        val preimage = preimages.saveThenWrite(editId, plan.path, plan.expect, plan.oldBytes) { os.replaceFileAtomically(plan.resolved.real, newBytes) }
                        val after = FileVersion.of(newBytes)
                        applied += AppliedOp(plan.index, "anchored", plan.path, plan.expect, after, preimage.preimageDigest.hex)
                        versions[plan.path] = after
                        revalidate(plan)
                        preimages.recordPostimage(editId, plan.path, after)
                        blobs.put(newBytes, BlobKind.POSTIMAGE, ids, recovery = true)
                        registry.change(plan.path, plan.expect, after, cause)
                        val counts = Preimages.changedRegion(plan.oldBytes, newBytes)
                        diffstat[plan.path] = DiffStat(counts.first, counts.second)
                        written[plan.path] = plan.resolved
                        owned(plan.resolved).let { id -> if (authored[id] == plan.expect) authored[id] = after }
                        views += postEditViews(plan, newText, after)
                        if (plan.normalised) notes[plan.index] = "anchor matched after whitespace normalisation"
                    }
                    is CreatePlan -> {
                        os.replaceFileAtomically(plan.resolved.real, plan.bytes)
                        val after = FileVersion.of(plan.bytes)
                        applied += AppliedOp(plan.index, "create", plan.path, null, after)
                        versions[plan.path] = after
                        blobs.put(plan.bytes, BlobKind.POSTIMAGE, ids, recovery = true)
                        registry.change(plan.path, null, after, cause)
                        val lines = decodeStrict(plan.bytes)?.let { contentLines(it) } ?: emptyList()
                        diffstat[plan.path] = DiffStat(lines.size, 0)
                        written[plan.path] = plan.resolved
                        authored[owned(plan.resolved)] = after
                        if (lines.isNotEmpty()) views += View(plan.path, LineRange(1, lines.size), after, lines.mapIndexed { i, l -> "${i + 1}| $l" }.joinToString("\n"))
                    }
                    is ReplacePlan -> {
                        val preimage = preimages.saveThenWrite(editId, plan.path, plan.expect, plan.oldBytes) { os.replaceFileAtomically(plan.resolved.real, plan.bytes) }
                        val after = FileVersion.of(plan.bytes)
                        applied += AppliedOp(plan.index, "replace", plan.path, plan.expect, after, preimage.preimageDigest.hex)
                        notes[plan.index] = plan.note
                        versions[plan.path] = after
                        preimages.recordPostimage(editId, plan.path, after)
                        blobs.put(plan.bytes, BlobKind.POSTIMAGE, ids, recovery = true)
                        registry.change(plan.path, plan.expect, after, cause)
                        val counts = Preimages.changedRegion(plan.oldBytes, plan.bytes)
                        diffstat[plan.path] = DiffStat(counts.first, counts.second)
                        written[plan.path] = plan.resolved
                        authored[owned(plan.resolved)] = after
                        val lines = decodeStrict(plan.bytes)?.let { contentLines(it) } ?: emptyList()
                        if (lines.isNotEmpty()) views += View(plan.path, LineRange(1, lines.size), after, lines.mapIndexed { i, l -> "${i + 1}| $l" }.joinToString("\n"))
                    }
                    is DeletePlan -> {
                        val preimage = preimages.save(editId, plan.path, plan.expect, plan.oldBytes)
                        Files.delete(plan.resolved.real)
                        applied += AppliedOp(plan.index, "delete", plan.path, plan.expect, null, preimage.preimageDigest.hex)
                        versions[plan.path] = null
                        registry.change(plan.path, plan.expect, null, cause)
                        authored.remove(owned(plan.resolved))
                        diffstat[plan.path] = DiffStat(0, decodeStrict(plan.oldBytes)?.let { contentLines(it).size } ?: 0)
                    }
                    is RenamePlan -> {
                        val preimage = preimages.save(editId, plan.path, plan.expect, plan.oldBytes)
                        os.replaceFileAtomically(plan.target.real, plan.oldBytes)
                        applied += AppliedOp(plan.index, "rename", plan.to, null, plan.expect, preimage.preimageDigest.hex)
                        versions[plan.to] = plan.expect
                        Files.delete(plan.resolved.real)
                        applied += AppliedOp(plan.index, "rename", plan.path, plan.expect, null, preimage.preimageDigest.hex)
                        versions[plan.path] = null
                        registry.change(plan.path, plan.expect, null, cause)
                        registry.change(plan.to, null, plan.expect, cause)
                        diffstat[plan.to] = DiffStat(0, 0)
                        written[plan.to] = plan.target
                        if (authored.remove(owned(plan.resolved)) == plan.expect) authored[owned(plan.target)] = plan.expect
                    }
                    is RevertEditPlan -> {
                        for (path in plan.paths) {
                            when (val result = preimages.revert(plan.editId, path, os)) {
                                is RevertResult.Reverted -> {
                                    val receipt = result.receipt
                                    applied += AppliedOp(plan.index, "revert", path, receipt.versionBefore, receipt.versionAfter)
                                    versions[path] = receipt.versionAfter
                                    registry.change(path, receipt.versionBefore, receipt.versionAfter, "revert $alias")
                                    diffstat[path] = DiffStat(receipt.addedLines, receipt.removedLines)
                                    workspace.resolve(path, Intent.Mutate).let { if (it is PathResolution.Resolved) written[path] = it }
                                    disown(path)
                                }
                                is RevertResult.Diverged -> {
                                    error = EditError("divergent", plan.index, path, "'$path' is @${result.actual?.hash8 ?: "gone"} now, not the @${result.expected?.hash8} that edit ${plan.editId} produced; the inverse is refused (FX-05)")
                                    break@loop
                                }
                                is RevertResult.Refused -> {
                                    error = EditError("unknown", plan.index, path, result.reason)
                                    break@loop
                                }
                            }
                        }
                    }
                    is RevertTurnPlan -> {
                        val ref = shadowRef!!
                        when (val result = ref.restore(plan.turn)) {
                            is RestoreResult.Restored -> {
                                for (path in result.written) {
                                    val after = registry.version(path)
                                    registry.change(path, registry.recorded(path), after, "revert $alias")
                                    applied += AppliedOp(plan.index, "revert", path, null, after)
                                    versions[path] = after
                                    workspace.resolve(path, Intent.Mutate).let { if (it is PathResolution.Resolved) written[path] = it }
                                    disown(path)
                                }
                                for (path in result.deleted) {
                                    registry.change(path, registry.recorded(path), null, "revert $alias")
                                    applied += AppliedOp(plan.index, "revert", path, null, null)
                                    versions[path] = null
                                    disown(path)
                                }
                            }
                            is RestoreResult.Divergent -> {
                                error = EditError("divergent", plan.index, null, "turn ${plan.turn} cannot be restored: ${result.paths.joinToString(", ")} changed since the snapshot; the inverse is refused (FX-05)")
                                break@loop
                            }
                            is RestoreResult.Refused -> {
                                error = EditError("unknown", plan.index, null, result.reason)
                                break@loop
                            }
                        }
                    }
                }
            } catch (refusal: Refusal) {
                error = refusal.error
                break@loop
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (failure: Exception) {
                error = ioError(plan, failure, applied)
                break@loop
            }
        }
        val syntaxResults = written.filter { (path, _) -> versions[path] != null }
            .mapValues { (path, resolved) -> beforeDispatch(); syntax.check(path, resolved.real, Language.of(path)) }
        val flags = TestIntegrity.classify(surfaceChanges(plans, applied), cause, contract, checks).map { it.copy(reason = why) }
        flagsByAlias[alias] = flags
        return EditResult(error == null, editId, applied, views, versions, syntaxResults, diffstat, outside, flags, error, notes = notes)
    }

    /** Each applied path's text before and after, for the §8.6 classifier; an unknown before-text stays unknown. */
    private fun surfaceChanges(plans: List<Plan>, applied: List<AppliedOp>): List<SurfaceChange> {
        val oldBytes = HashMap<String, ByteArray>()
        for (plan in plans) {
            when (plan) {
                is AnchoredPlan -> oldBytes[plan.path] = plan.oldBytes
                is DeletePlan -> oldBytes[plan.path] = plan.oldBytes
                is ReplacePlan -> oldBytes[plan.path] = plan.oldBytes
                is RenamePlan -> oldBytes[plan.path] = plan.oldBytes
                else -> Unit
            }
        }
        return applied.map { op ->
            val before = oldBytes[op.path] ?: op.versionBefore?.let { v -> runCatching { blobs.get(v.digest) }.getOrNull() ?: return@map SurfaceChange(op.path, null, null) }
            val after = op.versionAfter?.let { registry.read(op.path)?.bytes ?: return@map SurfaceChange(op.path, null, null) }
            SurfaceChange(op.path, before?.toString(Charsets.UTF_8), after?.toString(Charsets.UTF_8))
        }
    }

    private fun ioError(plan: Plan, failure: Exception, applied: List<AppliedOp>): EditError = EditError(
        "io", plan.index, plan.path,
        "${failure.message ?: failure::class.simpleName} while applying op ${plan.index} on '${plan.path}'; " +
            (if (applied.isEmpty()) "publication outcome unknown; inspect the affected paths before retry" else "already written: " + applied.joinToString(", ") { "${it.path}" + (it.preimageRef?.let { ref -> " (preimage ${ref.take(8)})" } ?: "") }) +
            "; later ops not attempted, nothing rolled back",
    )

    /** Restored bytes are not the cell's own writing (D-371): a later `create` over them needs a read. */
    private fun disown(path: String) {
        (workspace.resolve(path, Intent.Mutate) as? PathResolution.Resolved)?.let { authored.remove(owned(it)) }
    }

    private fun revalidate(plan: AnchoredPlan) {
        val again = workspace.paths.revalidate(plan.resolved)
        if (again is PathResolution.Rejected) throw IOException("'${plan.path}' changed identity during publication: ${again.detail}")
    }

    /** Replaces located spans from the end so earlier offsets stay valid. */
    private fun replace(text: String, hunks: List<Pair<Located, String>>): String {
        val sb = StringBuilder(text)
        for ((span, new) in hunks.sortedByDescending { it.first.start }) sb.replace(span.start, span.end, new)
        return sb.toString()
    }

    /** Post-edit views ±[viewContextLines] around every hunk in the new text, merged per path (§9.1). */
    private fun postEditViews(plan: AnchoredPlan, newText: String, after: FileVersion): List<View> {
        val lines = contentLines(newText)
        var ranges = Ranges.EMPTY
        for ((span, new) in plan.hunks) {
            val newLineCount = if (new.isEmpty()) 0 else new.count { it == '\n' } + 1
            val from = maxOf(1, span.lines.from - viewContextLines)
            val to = minOf(lines.size, span.lines.from + maxOf(newLineCount, 1) - 1 + viewContextLines)
            if (to >= from) ranges += LineRange(from, to)
        }
        return ranges.ranges.map { range -> View(plan.path, range, after, (range.from..range.to).joinToString("\n") { "$it| ${lines[it - 1]}" }) }
    }

    private fun show(view: View, alias: String, turn: Int) {
        val redacted = redaction.apply(view.text, ContentClass.ReusableEvidence)
        val hidden = Ranges.of(redacted.mask.hiddenLines.ranges.map { LineRange(it.from + view.range.from - 1, it.to + view.range.from - 1) })
        val mask = RedactionMask(hidden, redacted.mask.limitations)
        registry.show(ids.context!!, generation, workspace.id, view.path, view.version, Ranges.of(view.range), mask)
        val granted = Ranges.of(view.range) - hidden
        if (!granted.isEmpty) workset.register(Entry(view.path, Ranges.of(view.range), view.version, EntrySource.PostEdit, turn, alias, estimator.estimate(view.text).tokens, hidden))
    }

    // ---------------------------------------------------------------- render

    private fun render(args: EditArgs, alias: String, actionId: String, result: EditResult, context: TurnContext): ToolOutcome {
        val status = when {
            result.ok -> "ok"
            // A rejected transform is not a half-applied batch: its receipt's effect line is the per-file truth (§9.2).
            result.transform != null -> "rejected"
            result.partial -> "partial"
            else -> "refused"
        }
        val lines = ArrayList<String>()
        lines += "edit $alias $status · ${args.why}"
        val receipt = result.transform
        if (receipt != null) {
            lines += transformLines(receipt)
        } else {
            for (op in result.applied) {
                val stat = result.diffstat[op.path]?.let { " $it" } ?: ""
                val syn = result.syntax[op.path]?.let { " · syntax $it" } ?: ""
                lines += "✓ ${op.opIndex} ${op.kind} ${op.path} @${op.versionBefore?.hash8 ?: "new"}→@${op.versionAfter?.hash8 ?: "gone"}$stat$syn" +
                    (result.notes[op.opIndex]?.let { " ($it)" } ?: "")
            }
        }
        for (view in result.views) {
            lines += "  post-edit ${view.path}:${view.range} @${view.version.hash8}"
            lines += view.text.lines().map { "  $it" }
        }
        for (group in result.refused) {
            val e = group.error
            lines += "✗ ${group.paths.joinToString(", ").ifEmpty { "op ${group.ops.joinToString(", ")}" }} refused · ${e.opIndex?.let { "op $it " } ?: ""}${e.kind}: ${e.detail}"
        }
        result.error?.takeIf { e -> result.refused.none { it.error === e } }?.let { e ->
            if (receipt == null) lines += "✗ ${e.opIndex?.let { "op $it " } ?: ""}${e.kind}: ${e.detail}"
        }
        if (result.refused.isNotEmpty()) lines += resendLine(result)
        if (result.touchedOutsideScope.isNotEmpty()) lines += "outside the increment's write scope (inside the contract): ${result.touchedOutsideScope.joinToString(", ")}"
        result.testIntegrity.forEach { lines += it.line }
        val safe = redaction.apply(lines.joinToString("\n"), ContentClass.ReusableEvidence)
        val body = safe.text
        // Coverage follows the final displayed output; omitted or redacted views grant no new reads.
        if (!safe.applied && safe.limitations.isEmpty()) result.views.forEach { show(it, alias, context.turn) }
        val blob = blobs.put(body.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        val newVersions = result.versions.filterValues { it != null }.mapValues { it.value!! }
        observations.record(
            Observation(
                id = result.editId, ids = ids, actionId = actionId, candidate = null, contentRef = blob,
                // This composite report is not a source-aligned capture. Source coverage is registered above.
                paths = result.views.map { it.path }.distinct(), ranges = emptyMap(), redaction = safe.mask,
                complete = safe.limitations.isEmpty(), sourceVersions = newVersions, captureComplete = safe.limitations.isEmpty(),
            ),
        )
        val header = EnvelopeHeader(
            resultAlias = alias, tool = "edit", effectClass = EffectClass.W, versions = newVersions, stamp = null, truncated = safe.limitations.isNotEmpty(),
            effects = when {
                result.applied.isNotEmpty() -> Effects.Observed
                result.error?.kind == "io" -> Effects.Unknown
                else -> Effects.None
            }, flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = actionId, status = status, candidateBefore = null, candidateAfter = null,
                scope = result.applied.map { it.path }.distinct().joinToString(", ").ifEmpty { args.ops.mapNotNull { it.path ?: it.create ?: it.delete ?: it.rename ?: it.revert }.joinToString(", ") },
                completeness = if (safe.limitations.isEmpty()) "complete" else "truncated", artifactRefs = listOf(blob.hex) + listOfNotNull(result.transform?.diffRef?.hex), effectsObserved = result.applied.map { redaction.apply("${it.kind} ${it.path}").text },
                redactionApplied = safe.applied, captureComplete = safe.limitations.isEmpty(), displayTruncated = safe.limitations.isNotEmpty(),
                effectsUnknown = result.error?.kind == "io",
            ),
        )
        val partially = result.refused.isNotEmpty() && result.applied.isNotEmpty()
        return ToolOutcome(
            body, header, applied = result.ok, tokens = estimator.estimate(body).tokens,
            notAppliedReason = if (partially) "the edit batch applied partially (${result.refused.sumOf { it.ops.size }} refused): fix those ops first" else null,
        )
    }

    /** D-371: the one line the model acts on after a refusal — what was written and exactly which ops to resend. */
    private fun resendLine(result: EditResult): String {
        val all = (result.refused.flatMap { it.paths } + result.applied.map { it.path }).distinct()
        val written = result.applied.map { it.path }.distinct()
        val resend = result.refused.joinToString(", ") { g ->
            val ops = (if (g.ops.size == 1) "op " else "ops ") + g.ops.joinToString(", ")
            if (g.paths.isEmpty()) ops else "${g.paths.joinToString(", ")} ($ops)"
        }
        return "${written.size} of ${all.size} files written; resend only the refused ops: $resend"
    }

    /** The §9.2 diff receipt as the model sees it: bounded per-file summary, counts, sites, the two honesty labels. */
    private fun transformLines(r: TransformReceipt): List<String> {
        val lines = ArrayList<String>()
        val expected = r.expectedMatches?.let { " (expected ${it.min}–${it.max})" } ?: " (expected unspecified)"
        val syntaxOk = r.syntax.values.count { it is SyntaxResult.Ok }
        lines += "transform: ${r.filesChanged} files, ${r.hunks} hunks · diff #${r.diffRef.hash8} · match_count ${r.matchCount}$expected · inventory_ok ${r.inventoryOk.wire} · " +
            "touched_outside_scope: ${if (r.touchedOutsideScope.isEmpty()) "none" else r.touchedOutsideScope.joinToString(", ")} · syntax ok $syntaxOk/${r.syntax.size} · exit ${r.exitCode ?: "none"}"
        r.perFile.take(PER_FILE_LINES).forEach { f -> lines += "  ${f.line}" + (r.syntax[f.path]?.takeIf { it !is SyntaxResult.Ok }?.let { " · syntax $it" } ?: "") }
        if (r.perFile.size > PER_FILE_LINES) lines += "  +${r.perFile.size - PER_FILE_LINES} more files (recall the diff)"
        if (r.representativeSites.isNotEmpty()) lines += "  representative: " + r.representativeSites.joinToString(" · ")
        lines += "  unusual: " + (if (r.unusualSites.isEmpty()) "none (every hunk has the same shape)" else r.unusualSites.joinToString(" · "))
        r.limits.forEach { lines += "  limit: $it" }
        lines += "  transformation-based validation: the harness diffed the tree; nobody read every edited byte. Changed files are touched-by-transform (NOT SEEN): read before an anchored edit. Blast-radius tests are required before the increment closes."
        lines += "  execution: ${ExecutionModeLabel.short(r.executionMode)} — a diff is not a jail: effects outside the workspace are unobserved (D-41)."
        if (!r.accepted) {
            lines += "  rejected: ${r.rejection} · effect: ${r.effect?.wire}" +
                (if (r.restored.isEmpty()) "" else " · restored ${r.restored.size}") +
                (if (r.notRestored.isEmpty()) "" else " · not restored: " + r.notRestored.joinToString("; ")) +
                " — no unit rollback and no undo of external effects is claimed"
        }
        return lines
    }

    private fun outlineOf(path: String, bytes: ByteArray): String {
        val outline = Outline.of(path, bytes)
        return "outline $path: " + outline.entries.joinToString(" · ") { "${it.kind.name.lowercase()} ${it.name} ${it.from}-${it.to}" }.ifEmpty { "(no declarations)" }
    }

    /** Lines as an editor counts them: a trailing newline ends the last line, it does not start an empty one. */
    private fun contentLines(text: String): List<String> = text.lines().let { if (text.endsWith("\n") && it.isNotEmpty()) it.dropLast(1) else it }

    /** D-324: the file's majority line ending (a tie keeps CRLF), so a replacement never mixes endings in. */
    private fun dominantEol(text: String): String {
        val crlf = Regex("\r\n").findAll(text).count()
        val lf = text.count { it == '\n' } - crlf
        return if (crlf > 0 && crlf >= lf) "\r\n" else "\n"
    }

    /** Every indented line of the file is indented with tabs only (D-324). */
    private fun tabIndented(text: String): Boolean {
        val indents = text.split('\n').filter { it.isNotBlank() }.map { line -> line.takeWhile { it == ' ' || it == '\t' } }.filter { it.isNotEmpty() }
        return indents.isNotEmpty() && indents.all { indent -> indent.all { it == '\t' } }
    }

    /**
     * D-324: a replacement indented with spaces becomes tab-indented for a tab-indented file when one tab's width
     * follows from the [replaced] lines: the smallest space indent of the replacement over the smallest tab depth
     * of the replaced text (8 spaces over two tabs is 4, never one 8-wide level). Anything ambiguous (tabs, mixed
     * or inconsistent widths, no indented replaced line) is left as written. A first line that continues a line
     * of the file ([atLineStart] false) has no indentation of its own.
     */
    private fun tabsFor(replacement: String, replaced: String, atLineStart: Boolean): String {
        fun ownLines(block: String) = block.split('\n').withIndex().filter { (i, line) -> (i > 0 || atLineStart) && line.isNotBlank() }
        val lines = replacement.split('\n')
        val own = ownLines(replacement)
        val indents = own.map { (_, line) -> line.takeWhile { it == ' ' || it == '\t' } }.filter { it.isNotEmpty() }
        if (indents.isEmpty() || indents.any { indent -> indent.any { it == '\t' } }) return replacement
        val depth = ownLines(replaced).map { (_, line) -> line.takeWhile { it == '\t' }.length }.filter { it > 0 }.minOrNull() ?: return replacement
        val smallest = indents.minOf { it.length }
        if (smallest % depth != 0) return replacement
        val width = smallest / depth
        if (indents.any { it.length % width != 0 }) return replacement
        val converted = own.map { it.index }.toSet()
        return lines.mapIndexed { i, line ->
            if (i !in converted) line else {
                val spaces = line.takeWhile { it == ' ' }.length
                "\t".repeat(spaces / width) + line.substring(spaces)
            }
        }.joinToString("\n")
    }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (malformed: CharacterCodingException) {
        null
    }

    private companion object {
        /** Per-file lines shown in a transform receipt; the rest is in the recallable diff (§9.2 bounded summary). */
        const val PER_FILE_LINES = 12

        /** A short `expect` needs the four hex characters every header shows (D-346); a full one is a SHA-256 in hex. */
        const val MIN_EXPECT = 4
        const val FULL_EXPECT = 64

        const val REPLACED_BY_DELETE = "delete + create of one path in one batch: replaced in place"
    }
}
