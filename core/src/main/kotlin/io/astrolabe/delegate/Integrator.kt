package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.cell.Change
import io.astrolabe.cell.ResultPacket
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkspaceId
import io.astrolabe.recover.Fence
import io.astrolabe.store.BlobKind
import io.astrolabe.store.BlobStore
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.Intent as PathIntent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionChange
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.Workspaces
import io.astrolabe.workspace.Worktree
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/** What integration is checked against now (§10.4, F09): the contract version, the execution generation and the lease's authority. */
public data class IntegrationAuthority(val contractVersion: Int, val generation: ExecutionGeneration, val authority: PublicationAuthority)

/**
 * A writer's collected result as the integrator takes it: the recorded [dispatch], the writer's packet and its spend.
 * [superseded] is why the Delegator judged it late (FX-26): such a result is archived, never published.
 */
public data class WriterResult @JvmOverloads constructor(
    val dispatch: WriterDispatch,
    val packet: ResultPacket,
    val spend: Tokens,
    val superseded: String? = null,
) {
    val handle: String get() = dispatch.handle.id

    public companion object {
        /** The integrable view of [collected]: a published or a late writer packet; `null` for anything else. */
        @JvmStatic
        public fun of(collected: Collected, dispatch: WriterDispatch): WriterResult? = when (collected) {
            is Collected.Result -> (collected.packet as? ChildPacket.Result)?.let { WriterResult(dispatch, it.packet, collected.spend) }
            is Collected.Late -> (collected.observation as? ChildPacket.Result)?.let { WriterResult(dispatch, it.packet, collected.spend, collected.reason) }
            else -> null
        }
    }
}

/** The §10.4 `integrate()` steps, in order; a rejection names the step that refused. */
public enum class IntegrationStep { Validate, Freshness, Apply, CombinedCheck, Gates, Publish }

/** What one check step over the integration candidate found; no failures ⇔ the step passed. */
public data class IntegrationCheck @JvmOverloads constructor(val failures: List<String>, val receipts: List<String> = emptyList())

/**
 * One check step over the isolated integration candidate (§10.4): the combined-tree checker, blast radius over the
 * union of merged edit sets and all affected acceptance; or contract lint and the required current review. It runs
 * on [candidate], never on the main line.
 */
public fun interface IntegrationChecks {
    public suspend fun verify(candidate: Workspace, union: Set<String>, results: List<WriterResult>): IntegrationCheck
}

/**
 * Rebases a stale result in the child's worktree onto [onto] and re-runs its acceptance (§10.4); `null` rejects it
 * with the staleness evidence.
 */
public fun interface Rebase {
    public suspend fun rebase(result: WriterResult, onto: CandidateId, moved: List<String>): WriterResult?
}

/**
 * Published: the receipt binds the integration base, the patch hash, the resulting stamp and the environment (§10.4).
 * The controller alone turns it into a ledger commit.
 */
public data class IntegrationReceipt(
    val integrationBase: CandidateId,
    val patchHash: Digest,
    val resultingStamp: CandidateId,
    val envId: Digest,
    val handles: List<String>,
    /** The union of the merged edit sets. */
    val paths: List<String>,
    val receipts: List<String>,
)

public sealed interface Integration {
    public val handles: List<String>

    public data class Published(val receipt: IntegrationReceipt) : Integration {
        override val handles: List<String> get() = receipt.handles
    }

    /**
     * Not published. [archived] is the blob of the late or refused patch kept for reconciliation; [returnsToMainLine]
     * marks a combined-tree disagreement whose shared decision goes back to the main line (§10.4: a clean textual
     * merge proves nothing, and no vote over worker confidence replaces integration).
     */
    public data class Rejected @JvmOverloads constructor(
        override val handles: List<String>,
        val step: IntegrationStep,
        val reason: String,
        val evidence: List<String> = emptyList(),
        val archived: Digest? = null,
        val returnsToMainLine: Boolean = false,
    ) : Integration
}

/**
 * The integration horizon of the coherence protocol (§4.4): a delegated result whose read versions include `(path, v)`
 * turns `stale-for-integration` when `path` moves from `v`. Registered on [io.astrolabe.evidence.Coherence].
 */
public class IntegrationHorizon : ChangeListener {
    private val tracked = ConcurrentHashMap<String, Map<String, FileVersion>>()
    private val moved = ConcurrentHashMap<String, MutableMap<String, FileVersion?>>()

    /** Tracks [handle]'s read versions until [untrack]. */
    public fun track(handle: String, readVersions: Map<String, FileVersion>) {
        tracked[handle] = LinkedHashMap(readVersions)
    }

    public fun untrack(handle: String) {
        tracked.remove(handle)
        moved.remove(handle)
    }

    /** The paths [handle] read that moved since, with their new versions (`null` = deleted); empty ⇔ fresh. */
    public fun stale(handle: String): Map<String, FileVersion?> = moved[handle]?.let { synchronized(it) { LinkedHashMap(it) } } ?: emptyMap()

    override fun onChange(change: VersionChange) {
        for ((handle, reads) in tracked) {
            if (reads[change.path] == change.from && change.from != null) {
                val marks = moved.computeIfAbsent(handle) { LinkedHashMap() }
                synchronized(marks) { marks[change.path] = change.to }
            }
        }
    }
}

/** Serializes integrations per destination (§10.4 "merge queue, serialized under destination ownership"). */
public class MergeQueue {
    private val locks = ConcurrentHashMap<WorkspaceId, Mutex>()

    public suspend fun <T> serialized(destination: WorkspaceId, block: suspend () -> T): T =
        locks.computeIfAbsent(destination) { Mutex() }.withLock { block() }
}

/**
 * The single integrator (§10.4, P5.1.3). [integrate] validates each result against its recorded dispatch base and the
 * current contract version, generation and authority; a result whose base is not the main candidate or whose read
 * dependencies moved is `stale-for-integration` and is rebased ([rebase]) or rejected with evidence. The survivors go
 * through the [queue] together: capture the integration base, apply every patch in an isolated integration candidate
 * (a worktree of the main candidate), run [checks] over the union of their edit sets, then [gates], and publish into
 * the main line only if it still stamps as the integration base and the authority and generation still hold. The
 * publication is an intent in [intents] and every path moves through [registry] so coherence hears it. A late or
 * refused patch is archived in [blobs]. The integrator never touches the ledger.
 */
public class Integrator @JvmOverloads constructor(
    private val workspaces: Workspaces,
    private val registry: VersionRegistry,
    private val env: EnvFingerprint,
    private val checks: IntegrationChecks,
    private val gates: IntegrationChecks,
    private val current: () -> IntegrationAuthority,
    private val blobs: BlobStore,
    private val intents: IntentJournal,
    private val idGen: IdGen,
    private val clock: Clock,
    private val horizon: IntegrationHorizon? = null,
    private val rebase: Rebase? = null,
    public val queue: MergeQueue = MergeQueue(),
) {
    private val main: Workspace get() = workspaces.main

    init {
        require(registry.workspace === workspaces.main) { "the integrator publishes through the main line's registry" }
    }

    public suspend fun integrate(results: List<WriterResult>): List<Integration> {
        val outcomes = ArrayList<Integration>()
        val ready = ArrayList<WriterResult>()
        for (result in results) {
            when (val admitted = admit(result)) {
                is WriterResult -> ready += admitted
                is Integration -> outcomes += admitted
            }
        }
        if (ready.isNotEmpty()) outcomes += queue.serialized(main.id) { merge(ready) }
        return outcomes
    }

    /** Validation and freshness (§10.4 first two lines): the result itself, a rebased result, or a rejection; one rebase per result. */
    private suspend fun admit(result: WriterResult, rebased: Boolean = false): Any {
        result.superseded?.let { return reject(result, IntegrationStep.Validate, "publication authority superseded: $it") }
        val now = current()
        val gaps = Writers.validate(result.dispatch, result.packet) + listOfNotNull(
            if (now.contractVersion != result.dispatch.task.contractVersion) "contract is v${now.contractVersion}, dispatched under v${result.dispatch.task.contractVersion}" else null,
            Fence.publish(result.dispatch.task.executionGeneration, now.generation, now.authority).refusal,
        )
        if (gaps.isNotEmpty()) return reject(result, IntegrationStep.Validate, "the result does not match its recorded dispatch", gaps)
        val stamp = Stamper(main, env).report().candidateId
        val moved = moved(result)
        if (stamp == result.packet.base!!.stamp && moved.isEmpty()) return result
        val evidence = listOfNotNull(if (stamp != result.packet.base.stamp) "main @${stamp.hash8}, result base @${result.packet.base.stamp.hash8}" else null) + moved.map { "read dependency $it moved" }
        val next = rebase?.takeIf { !rebased }?.rebase(result, stamp, moved)
            ?: return Integration.Rejected(listOf(result.handle), IntegrationStep.Freshness, "stale-for-integration", evidence)
        horizon?.untrack(result.handle)
        horizon?.track(next.handle, next.packet.readVersions)
        return admit(next, rebased = true)
    }

    private fun moved(result: WriterResult): List<String> {
        val marked = horizon?.stale(result.handle)?.keys.orEmpty()
        val reread = result.packet.readVersions.filter { (path, version) -> registry.version(path) != version }.keys
        return (marked + reread).sorted().distinct()
    }

    private suspend fun merge(results: List<WriterResult>): List<Integration> {
        val base = Stamper(main, env).report()
        val (fresh, stale) = results.partition { it.packet.base!!.stamp == base.candidateId }
        val outcomes = ArrayList<Integration>()
        stale.forEach { outcomes += Integration.Rejected(listOf(it.handle), IntegrationStep.Freshness, "stale-for-integration", listOf("main moved to @${base.candidateId.hash8} while queued")) }
        // Overlapping ownership serializes (§10.4): a later result touching a path an earlier one edits waits for the next round.
        val claimed = HashSet<String>()
        val batch = ArrayList<WriterResult>()
        for (result in fresh) {
            val paths = result.packet.changes.map { it.path }
            val overlap = paths.filter { it in claimed }
            if (overlap.isEmpty()) {
                batch += result
                claimed += paths
            } else {
                outcomes += Integration.Rejected(listOf(result.handle), IntegrationStep.Apply, "overlapping ownership serializes: re-integrate after the earlier result", overlap.sorted())
            }
        }
        if (batch.isEmpty()) return outcomes
        val handles = batch.map { it.handle }
        val first = batch.first().dispatch.task
        val candidate = workspaces.createWorktree(first.ids.work, first.ids.attempt, "integration-${handles.first()}")
        try {
            if (candidate.base != base.candidateId) return outcomes + Integration.Rejected(handles, IntegrationStep.Apply, "the integration candidate is @${candidate.base.hash8}, the integration base @${base.candidateId.hash8}")
            val changes = batch.flatMap { result -> result.packet.changes.map { result to it } }
            apply(candidate, changes)?.let { return outcomes + Integration.Rejected(handles, IntegrationStep.Apply, it) }
            val union = changes.map { it.second.path }.toSortedSet()
            val combined = checks.verify(candidate.workspace, union, batch)
            if (combined.failures.isNotEmpty()) return outcomes + Integration.Rejected(handles, IntegrationStep.CombinedCheck, "the combined tree fails", combined.failures, returnsToMainLine = true)
            val gated = gates.verify(candidate.workspace, union, batch)
            if (gated.failures.isNotEmpty()) return outcomes + Integration.Rejected(handles, IntegrationStep.Gates, "contract lint or the required review refuses the combined tree", gated.failures, returnsToMainLine = true)
            return outcomes + publish(candidate, base.candidateId, batch, changes.map { it.second }, combined.receipts + gated.receipts)
        } finally {
            workspaces.remove(candidate)
        }
    }

    /** Applies every change in the integration candidate, guarded per path by its `before` version; the refusal or `null`. */
    private fun apply(candidate: Worktree, changes: List<Pair<WriterResult, Change>>): String? {
        val versions = VersionRegistry(candidate.workspace)
        for ((result, change) in changes) {
            if (versions.version(change.path) != change.before) return "${change.path} is not at ${change.before?.hash8 ?: "absent"} in the integration candidate (${result.handle})"
            val bytes = if (change.after == null) null else postimage(result, change) ?: return "${change.path} in ${result.dispatch.worktree.id.value} is no longer at ${change.after.hash8}"
            write(candidate.workspace, change.path, bytes)?.let { return it }
        }
        return null
    }

    private suspend fun publish(candidate: Worktree, integrationBase: CandidateId, batch: List<WriterResult>, changes: List<Change>, receipts: List<String>): Integration {
        val handles = batch.map { it.handle }
        val now = current()
        val patchHash = patchHash(changes)
        return main.mutation.withLock {
            val before = Stamper(main, env).report().candidateId
            if (before != integrationBase) return@withLock Integration.Rejected(handles, IntegrationStep.Publish, "main moved during integration: @${before.hash8}, integration base @${integrationBase.hash8}")
            batch.firstNotNullOfOrNull { Fence.publish(it.dispatch.task.executionGeneration, now.generation, now.authority).refusal }
                ?.let { return@withLock Integration.Rejected(handles, IntegrationStep.Publish, it, archived = archive(batch)) }
            // Every postimage is read and checked before the first byte reaches the main line.
            val staged = changes.map { change ->
                val bytes = change.after?.let { after ->
                    (candidate.workspace.resolve(change.path) as? PathResolution.Resolved)?.let(candidate.workspace::bytes)?.takeIf { FileVersion(Digest.of(it)) == after }
                        ?: return@withLock Integration.Rejected(handles, IntegrationStep.Publish, "${change.path} changed in the integration candidate")
                }
                change to bytes
            }
            val intent = Intent(idGen.next("intent"), batch.first().dispatch.task.ids, "integrate", listOf("integrate") + handles, null, "publish ${changes.size} paths @${integrationBase.hash8} patch ${patchHash.hash8}", at = clock.instant())
            intents.record(intent)
            for ((change, bytes) in staged) {
                write(main, change.path, bytes)?.let { error("publication of ${change.path} refused after the base check: $it") }
                registry.change(change.path, change.before, change.after, "integrated ${handles.joinToString(",")}")
            }
            intents.update(intent.intentId, IntentStatus.Committed)
            val resulting = Stamper(main, env).report().candidateId
            batch.forEach { horizon?.untrack(it.handle) }
            Integration.Published(IntegrationReceipt(integrationBase, patchHash, resulting, env.envId, handles, changes.map { it.path }.sorted(), receipts))
        }
    }

    private fun reject(result: WriterResult, step: IntegrationStep, reason: String, evidence: List<String> = emptyList()): Integration.Rejected =
        Integration.Rejected(listOf(result.handle), step, reason, evidence, archive(listOf(result)))

    /** Keeps [batch]'s postimages and a manifest of them in the blob store (§10.1: effects are archived for reconciliation). */
    private fun archive(batch: List<WriterResult>): Digest {
        val lines = batch.flatMap { result ->
            result.packet.changes.map { change ->
                val stored = change.after?.let { postimage(result, change) }?.let { blobs.put(it, BlobKind.POSTIMAGE, result.packet.ids) }
                "${result.handle} ${change.path} ${change.before?.hash8 ?: "absent"} -> ${change.after?.hash8 ?: "deleted"} ${stored?.hex ?: "unavailable"}"
            }
        }
        return blobs.put(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8), BlobKind.PACKET, batch.first().packet.ids)
    }

    /** The writer's bytes for [change] from its worktree, only while they still hash to the packet's `after`. */
    private fun postimage(result: WriterResult, change: Change): ByteArray? {
        val tree = result.dispatch.worktree.workspace
        val resolved = tree.resolve(change.path) as? PathResolution.Resolved ?: return null
        return tree.bytes(resolved)?.takeIf { FileVersion(Digest.of(it)) == change.after }
    }

    private fun write(workspace: Workspace, path: String, bytes: ByteArray?): String? {
        val resolved = workspace.resolve(path, PathIntent.Mutate) as? PathResolution.Resolved ?: return "the path contract refuses $path in ${workspace.id.value}"
        if (bytes == null) {
            Files.deleteIfExists(resolved.real)
        } else {
            resolved.real.parent?.let(Files::createDirectories)
            Files.write(resolved.real, bytes)
        }
        return null
    }

    public companion object {
        /** The patch identity: its sorted `path before after` lines, count-prefixed. */
        @JvmStatic
        public fun patchHash(changes: List<Change>): Digest = Digest.ofUtf8(
            changes.sortedBy { it.path }.joinToString("\n", prefix = "astrolabe/patch/v1\ncount=${changes.size}\n") { "${it.path}\n${it.before?.digest?.hex ?: "absent"}\n${it.after?.digest?.hex ?: "deleted"}" },
        )
    }
}
