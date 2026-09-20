package io.astrolabe.tool.edit

import io.astrolabe.atlas.Language
import io.astrolabe.atlas.Outline
import io.astrolabe.auth.ContentClass
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
    /** `scope` · `missing` · `stale_expect` · `anchor` · `outside_displayed` · `overlap` · `exists` · `unsupported` · `divergent` · `unknown` · `io`. */
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
) {
    /** Some ops reached the workspace before the batch stopped (mid-batch failure, §9.1). */
    val partial: Boolean get() = !ok && applied.isNotEmpty()
}

/**
 * The `edit` family (§9.1, §9.3, §9.5, TODO P1.6.4): anchored compare-and-swap hunks, `create`, `delete`,
 * `rename`, `revert:#id` and `revert:turn:N`; a transform is P3.3.
 *
 * Every op is preflighted before any write — committed-contract scope and protected paths through the
 * [ScopeGuard], `expect` re-hashed from raw bytes, anchors located (D-33) inside the dispatch-time displayed
 * coverage, hunks non-overlapping, unsupported kinds refused by name — and a single refusal writes nothing.
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
) : ToolExecutor {
    init {
        require(ids.context != null) { "edit runs inside a cell: ids.context is its lineage" }
        require(viewContextLines >= 0) { "viewContextLines must be ≥ 0" }
    }

    /** The increment whose write scope earns a warning when crossed (§8.6); the cell sets it. */
    public var increment: Increment? = null

    public var generation: Generation = Generation.INITIAL

    override suspend fun execute(call: ToolCall, context: TurnContext): ToolOutcome {
        require(call.family == ToolFamily.Edit) { "not an edit call: ${call.name}" }
        val args = (call.args as Args.Edit).args
        val editId = idGen.next("edit")
        val actionId = idGen.next("act")
        val alias = aliases.allocate(ids.work, editId, "edit", ids.context, workspace.id).text
        val contract = contracts.current(ids.work)
            ?: return render(args, alias, actionId, EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), EditError("unknown", null, null, "no committed contract for ${ids.work}")), context)
        if (!mask.allows(call.name)) {
            return render(args, alias, actionId, EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), EditError("unsupported", null, null, "${call.name} is masked in this role")), context)
        }
        val result = workspace.mutation.withLock { run(args, contract, context, editId, alias) }
        return render(args, alias, actionId, result, context)
    }

    // ------------------------------------------------------------- preflight

    private sealed interface Plan {
        val index: Int
        val path: String
    }

    private class AnchoredPlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray, val oldText: String, val hunks: List<Pair<Located, String>>) : Plan
    private class CreatePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val bytes: ByteArray) : Plan
    private class DeletePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray) : Plan
    private class RenamePlan(override val index: Int, override val path: String, val resolved: PathResolution.Resolved, val to: String, val target: PathResolution.Resolved, val expect: FileVersion, val oldBytes: ByteArray) : Plan
    private class RevertEditPlan(override val index: Int, val editId: String, val paths: List<String>) : Plan {
        override val path: String get() = paths.joinToString(", ")
    }
    private class RevertTurnPlan(override val index: Int, val turn: Int) : Plan {
        override val path: String get() = "turn:$turn"
    }

    private class Refusal(val error: EditError) : RuntimeException(error.detail)

    private fun run(args: EditArgs, contract: Contract, context: TurnContext, editId: String, alias: String): EditResult {
        val none = EditResult(false, editId, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList())
        args.ops.forEachIndexed { i, op ->
            if (op.kind == "transform") return none.copy(error = EditError("unsupported", i + 1, null, "transform is a P3.3 operation; not available in this role"))
            if (op.kind == "invalid") return none.copy(error = EditError("unsupported", i + 1, null, "op ${i + 1} names no supported form"))
        }
        // Scope first (§8.6): every path the batch would touch, against the committed contract only.
        val paths = args.ops.flatMap { op -> listOfNotNull(op.path, op.create, op.delete, op.rename, op.to) + revertPaths(op) }
        val verdict = scopeGuard.check(paths, contract, increment)
        if (verdict is ScopeVerdict.Refused) {
            val first = verdict.refusals.first()
            return none.copy(error = EditError("scope", null, first.path, verdict.refusals.joinToString("; ") { "${it.path}: ${it.kind.name.lowercase()} — ${it.detail}" }))
        }
        val outside = (verdict as ScopeVerdict.Allowed).outsideIncrement
        val plans = try {
            args.ops.mapIndexed { i, op -> preflight(i + 1, op, context) }
        } catch (refusal: Refusal) {
            return none.copy(error = refusal.error, touchedOutsideScope = outside)
        }
        return apply(plans, contract, context, editId, alias, outside)
    }

    private fun revertPaths(op: EditOpArgs): List<String> {
        val target = op.revert ?: return emptyList()
        if (target.startsWith("turn:")) return emptyList()
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
            if (registry.read(path) != null) throw Refusal(EditError("exists", index, path, "'$path' exists; use an anchored edit or delete it first"))
            CreatePlan(index, path, resolved, op.content!!.toByteArray(Charsets.UTF_8))
        }
        "delete" -> {
            val path = op.delete!!
            val resolved = mutable(index, path)
            val content = current(index, path, op.expect!!)
            DeletePlan(index, path, resolved, content.version, content.bytes)
        }
        "rename" -> {
            val from = op.rename!!
            val to = op.to!!
            if (from != to && from.equals(to, ignoreCase = true)) throw Refusal(EditError("unsupported", index, from, "case-only rename '$from' → '$to' is an unsupported mutation kind (§9.5)"))
            val resolved = mutable(index, from)
            val target = mutable(index, to)
            val content = current(index, from, op.expect!!)
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
        val expect = FileVersion(io.astrolabe.id.Digest(op.expect!!))
        val content = current(index, path, expect)
        val text = decodeStrict(content.bytes) ?: throw Refusal(EditError("unsupported", index, path, "'$path' is not valid UTF-8 text; binary changes need an explicit operation (§9.5)"))
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val located = op.hunks!!.map { hunk ->
            when (val location = Anchors.locate(text, hunk.anchor, hunk.near)) {
                is Location.One -> location.span to hunk.new.replace("\r\n", "\n").replace("\n", eol)
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
        return AnchoredPlan(index, path, resolved, expect, content.bytes, text, located)
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

    private fun current(index: Int, path: String, expectHex: String): io.astrolabe.workspace.FileContent = current(index, path, FileVersion(io.astrolabe.id.Digest(expectHex)))

    // ----------------------------------------------------------------- apply

    private fun apply(plans: List<Plan>, contract: Contract, context: TurnContext, editId: String, alias: String, outside: List<String>): EditResult {
        val applied = ArrayList<AppliedOp>()
        val views = ArrayList<View>()
        val versions = LinkedHashMap<String, FileVersion?>()
        val diffstat = LinkedHashMap<String, DiffStat>()
        val written = LinkedHashMap<String, PathResolution.Resolved>()
        val cause = "edit $alias"
        var error: EditError? = null
        loop@ for (plan in plans) {
            try {
                when (plan) {
                    is AnchoredPlan -> {
                        val newText = replace(plan.oldText, plan.hunks)
                        val newBytes = newText.toByteArray(Charsets.UTF_8)
                        val preimage = preimages.saveThenWrite(editId, plan.path, plan.expect, plan.oldBytes) { os.replaceFileAtomically(plan.resolved.real, newBytes) }
                        revalidate(plan)
                        val after = FileVersion.of(newBytes)
                        preimages.recordPostimage(editId, plan.path, after)
                        blobs.put(newBytes, BlobKind.POSTIMAGE, ids, recovery = true)
                        registry.change(plan.path, plan.expect, after, cause)
                        applied += AppliedOp(plan.index, "anchored", plan.path, plan.expect, after, preimage.preimageDigest.hex)
                        versions[plan.path] = after
                        val counts = Preimages.changedRegion(plan.oldBytes, newBytes)
                        diffstat[plan.path] = DiffStat(counts.first, counts.second)
                        written[plan.path] = plan.resolved
                        views += postEditViews(plan, newText, after)
                    }
                    is CreatePlan -> {
                        os.replaceFileAtomically(plan.resolved.real, plan.bytes)
                        val after = FileVersion.of(plan.bytes)
                        blobs.put(plan.bytes, BlobKind.POSTIMAGE, ids, recovery = true)
                        registry.change(plan.path, null, after, cause)
                        applied += AppliedOp(plan.index, "create", plan.path, null, after)
                        versions[plan.path] = after
                        val lines = decodeStrict(plan.bytes)?.let { contentLines(it) } ?: emptyList()
                        diffstat[plan.path] = DiffStat(lines.size, 0)
                        written[plan.path] = plan.resolved
                        if (lines.isNotEmpty()) views += View(plan.path, LineRange(1, lines.size), after, lines.mapIndexed { i, l -> "${i + 1}| $l" }.joinToString("\n"))
                    }
                    is DeletePlan -> {
                        val preimage = preimages.save(editId, plan.path, plan.expect, plan.oldBytes)
                        Files.delete(plan.resolved.real)
                        registry.change(plan.path, plan.expect, null, cause)
                        applied += AppliedOp(plan.index, "delete", plan.path, plan.expect, null, preimage.preimageDigest.hex)
                        versions[plan.path] = null
                        diffstat[plan.path] = DiffStat(0, decodeStrict(plan.oldBytes)?.let { contentLines(it).size } ?: 0)
                    }
                    is RenamePlan -> {
                        val preimage = preimages.save(editId, plan.path, plan.expect, plan.oldBytes)
                        os.replaceFileAtomically(plan.target.real, plan.oldBytes)
                        Files.delete(plan.resolved.real)
                        registry.change(plan.path, plan.expect, null, cause)
                        registry.change(plan.to, null, plan.expect, cause)
                        applied += AppliedOp(plan.index, "rename", plan.path, plan.expect, null, preimage.preimageDigest.hex)
                        applied += AppliedOp(plan.index, "rename", plan.to, null, plan.expect)
                        versions[plan.path] = null
                        versions[plan.to] = plan.expect
                        diffstat[plan.to] = DiffStat(0, 0)
                        written[plan.to] = plan.target
                    }
                    is RevertEditPlan -> {
                        for (path in plan.paths) {
                            when (val result = preimages.revert(plan.editId, path, os)) {
                                is RevertResult.Reverted -> {
                                    val receipt = result.receipt
                                    registry.change(path, receipt.versionBefore, receipt.versionAfter, "revert $alias")
                                    applied += AppliedOp(plan.index, "revert", path, receipt.versionBefore, receipt.versionAfter)
                                    versions[path] = receipt.versionAfter
                                    diffstat[path] = DiffStat(receipt.addedLines, receipt.removedLines)
                                    workspace.resolve(path, Intent.Mutate).let { if (it is PathResolution.Resolved) written[path] = it }
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
                                }
                                for (path in result.deleted) {
                                    registry.change(path, registry.recorded(path), null, "revert $alias")
                                    applied += AppliedOp(plan.index, "revert", path, null, null)
                                    versions[path] = null
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
            } catch (failure: IOException) {
                error = ioError(plan, failure, applied)
                break@loop
            } catch (failure: io.astrolabe.os.OsFailure) {
                error = ioError(plan, failure, applied)
                break@loop
            } catch (refusal: Refusal) {
                error = refusal.error
                break@loop
            }
        }
        val syntaxResults = written.filter { (path, _) -> versions[path] != null }
            .mapValues { (path, resolved) -> syntax.check(path, resolved.real, Language.of(path)) }
        val flags = TestIntegrity.baseline(applied.map { it.path }, cause, contract, checks)
        for (view in views) show(view, alias, context.turn)
        return EditResult(error == null, editId, applied, views, versions, syntaxResults, diffstat, outside, flags, error)
    }

    private fun ioError(plan: Plan, failure: Exception, applied: List<AppliedOp>): EditError = EditError(
        "io", plan.index, plan.path,
        "${failure.message ?: failure::class.simpleName} while applying op ${plan.index} on '${plan.path}'; " +
            (if (applied.isEmpty()) "nothing was written" else "already written: " + applied.joinToString(", ") { "${it.path}" + (it.preimageRef?.let { ref -> " (preimage ${ref.take(8)})" } ?: "") }) +
            "; later ops not attempted, nothing rolled back",
    )

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
            result.partial -> "partial"
            else -> "refused"
        }
        val lines = ArrayList<String>()
        lines += "edit $alias $status · ${args.why}"
        for (op in result.applied) {
            val stat = result.diffstat[op.path]?.let { " $it" } ?: ""
            val syn = result.syntax[op.path]?.let { " · syntax $it" } ?: ""
            lines += "✓ ${op.opIndex} ${op.kind} ${op.path} @${op.versionBefore?.hash8 ?: "new"}→@${op.versionAfter?.hash8 ?: "gone"}$stat$syn"
        }
        for (view in result.views) {
            lines += "  post-edit ${view.path}:${view.range} @${view.version.hash8}"
            lines += redaction.apply(view.text, ContentClass.ModelFacing).text.lines().map { "  $it" }
        }
        result.error?.let { e ->
            lines += "✗ ${e.opIndex?.let { "op $it " } ?: ""}${e.kind}: ${e.detail}"
        }
        if (result.touchedOutsideScope.isNotEmpty()) lines += "outside the increment's write scope (inside the contract): ${result.touchedOutsideScope.joinToString(", ")}"
        result.testIntegrity.forEach { lines += it.line }
        val body = lines.joinToString("\n")
        val blob = blobs.put(body.toByteArray(Charsets.UTF_8), BlobKind.OUTPUT, ids)
        val newVersions = result.versions.filterValues { it != null }.mapValues { it.value!! }
        observations.record(
            Observation(
                id = idGen.next("obs"), ids = ids, actionId = actionId, candidate = null, contentRef = blob,
                paths = result.views.map { it.path }.distinct(), ranges = result.views.groupBy { it.path }.mapValues { (_, v) -> Ranges.of(v.map { it.range }) },
                complete = true, sourceVersions = newVersions, captureComplete = true,
            ),
        )
        val header = EnvelopeHeader(
            resultAlias = alias, tool = "edit", effectClass = EffectClass.W, versions = newVersions, stamp = null, truncated = false,
            effects = if (result.applied.isEmpty()) Effects.None else Effects.Observed, flags = InstructionShape.detect(body).flags,
            runtime = RuntimeFields(
                actionId = actionId, status = status, candidateBefore = null, candidateAfter = null,
                scope = result.applied.map { it.path }.distinct().joinToString(", ").ifEmpty { args.ops.mapNotNull { it.path ?: it.create ?: it.delete ?: it.rename ?: it.revert }.joinToString(", ") },
                completeness = "complete", artifactRefs = listOf(blob.hex), effectsObserved = result.applied.map { "${it.kind} ${it.path}" },
                effectsUnknown = result.error?.kind == "io",
            ),
        )
        return ToolOutcome(body, header, applied = result.ok, tokens = estimator.estimate(body).tokens)
    }

    private fun outlineOf(path: String, bytes: ByteArray): String {
        val outline = Outline.of(path, bytes)
        return "outline $path: " + outline.entries.joinToString(" · ") { "${it.kind.name.lowercase()} ${it.name} ${it.from}-${it.to}" }.ifEmpty { "(no declarations)" }
    }

    /** Lines as an editor counts them: a trailing newline ends the last line, it does not start an empty one. */
    private fun contentLines(text: String): List<String> = text.lines().let { if (text.endsWith("\n") && it.isNotEmpty()) it.dropLast(1) else it }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (malformed: CharacterCodingException) {
        null
    }
}
