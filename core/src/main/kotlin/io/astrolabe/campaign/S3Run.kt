package io.astrolabe.campaign

import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.delegate.CellChildRunner
import io.astrolabe.delegate.ChildKind
import io.astrolabe.delegate.Collected
import io.astrolabe.delegate.DelegationLimits
import io.astrolabe.delegate.Delegator
import io.astrolabe.delegate.Dispatch
import io.astrolabe.delegate.DispatchMode
import io.astrolabe.delegate.EvidencePacket
import io.astrolabe.delegate.Excerpt
import io.astrolabe.delegate.Handle
import io.astrolabe.delegate.IncrementReviewInput
import io.astrolabe.delegate.Integration
import io.astrolabe.delegate.IntegrationAuthority
import io.astrolabe.delegate.IntegrationCheck
import io.astrolabe.delegate.IntegrationChecks
import io.astrolabe.delegate.IntegrationHorizon
import io.astrolabe.delegate.Integrator
import io.astrolabe.delegate.Rebase
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.delegate.ReviewOutcome
import io.astrolabe.delegate.ReviewReceipt
import io.astrolabe.delegate.ReviewTriggers
import io.astrolabe.delegate.TaskPacket
import io.astrolabe.delegate.WriterBudget
import io.astrolabe.delegate.WriterCell
import io.astrolabe.delegate.WriterDispatch
import io.astrolabe.delegate.WriterResult
import io.astrolabe.delegate.Writers
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.Receipt
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.route.FunctionTable
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.Layer
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.Verifier
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.WorktreeRefused
import io.astrolabe.workspace.Workspaces
import io.astrolabe.workspace.Worktree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock

/**
 * The tree one cell edits (§10.4, F02): the main line's, or a writer's worktree with its own registry, stamper,
 * dirty state, shadow ref, atlas and check registry, so nothing a writer shows, stamps or certifies is the main line's.
 */
internal class CellTree(
    val workspace: Workspace,
    val registry: VersionRegistry,
    val stamper: Stamper,
    val dirty: DirtyState,
    val shadow: ShadowRef,
    val atlas: Atlas,
    val checks: Checks,
    /** The tree's snapshot 0, the diff base of a campaign-scope review; `null` = none. */
    val s0: CandidateId?,
) {
    companion object {
        fun main(c: OpenedCampaign): CellTree = CellTree(c.workspace, c.registry, c.stamper, c.dirty, c.shadow, c.atlas, c.checks, c.s0.stampId)

        /**
         * A writer's tree over [worktree] (D-240): its shadow ref is opened at the worktree's snapshot 0 and its checks
         * are a fresh seed of the contract's, so a writer's receipts never become the main line's `last` results.
         */
        fun writer(c: OpenedCampaign, worktree: Worktree, env: EnvFingerprint, clock: Clock): CellTree {
            val ws = worktree.workspace
            val stamper = Stamper(ws, env)
            val dirty = DirtyState(ws, c.store.blobs, stamper, c.ids, clock)
            val shadow = ShadowRef(c.ids.work, c.ids.attempt, ws, c.store, dirty, c.os, clock)
            val s0 = shadow.manifest(0) ?: dirty.capture(0).also { shadow.open(it) }
            return CellTree(ws, VersionRegistry(ws), stamper, dirty, shadow, Atlas.build(ws.root), Checks.seed(c.contract, c.commands, qualityGates = c.attempt.config.qualityGates), s0.stampId)
        }
    }
}

/**
 * The S3 branch taken at plan intake (§3.5, D-183, D-241): the admitted plan's pending units, their writer-token
 * estimates and the logged verdict. [units] is empty when S3 was refused.
 */
internal class S3Admission(val units: Set<String>, val estimates: Map<String, Long>, val log: String) {
    /** The next parallel batch: ready S3 units without cells, at most [parallel]; fewer than two runs sequentially. */
    fun batch(graph: RequirementGraph, contract: Contract, parallel: Int): List<Increment> =
        graph.readyFrontier(contract, graph.increments.size).filter { it.id in units && it.cells.isEmpty() && it.status == IncrementStatus.Pending }.take(parallel)
}

internal object S3Intake {
    /**
     * The §3.5 plan records of [graph] (D-241): each pending increment is a unit over its write scope under the
     * contract's protected paths; an interface change is any `CON` anchor in the scope, a touched contract or a
     * pre-scan contract touch, else the declared risk's `contractTouch` (none declared = unassessed); a
     * `resolves:` increment settles a decision. Slack (D-242) is the remaining tokens against the units'
     * estimates plus the verification and recovery reserves, a review per unit in S2+ and nothing for integration
     * (harness checks, no model tokens); `null` estimates leave it unmeasured.
     */
    fun shape(c: OpenedCampaign, graph: RequirementGraph, estimates: Map<String, Long>?, remainingTokens: Long, defaults: Defaults): PlanShape {
        val contract = c.contract
        val anchors = c.kb.contractAnchors()
        val pending = graph.increments.filter { it.status == IncrementStatus.Pending && it.cells.isEmpty() }
        val units = pending.map { inc ->
            val anchored = anchors.values.any { paths -> paths.any { p -> inc.writeScope.any { PathPattern.matches(it, p) } } }
            val declared = (inc.risk ?: contract.risk)?.contractTouch
            val change = if (anchored || contract.contractsTouched.isNotEmpty() || c.impactPrescan.prescan.contractTouch == true) true else declared
            PlanUnit(inc.id, Scope(inc.writeScope, contract.scope.protectedPaths), change, inc.produces is Production.Resolves)
        }
        val slack = estimates?.let { e ->
            val work = pending.sumOf { e[it.id] ?: 0L }
            val reserved = (work * (1.0 + defaults.reserveVerification + defaults.reserveRecoveryAndPersist)).toLong()
            val review = if (contract.shape >= Shape.S2) pending.size.toLong() * defaults.reviewIncrementTokens else 0L
            (reserved + review).takeIf { it > 0 }?.let { Slack(maxOf(0L, remainingTokens), it, 0, defaults.parallelCells) }
        }
        return PlanShape(units, Controller.WORKSPACE, contract.amendmentsPending.isEmpty(), slack, PhysicalAliases.find(c.workspace, units, c.atlas.rows.map { it.path }))
    }

    /** Tokens the contract's budget has left after every billed call of the work so far. */
    fun remainingTokens(c: OpenedCampaign, clock: Clock): Long =
        c.contract.budget.tokens.value - Accounting(c.store, clock).calls(c.ids.work).sumOf { it.quantities.billedUsage ?: 0L }

    /**
     * `select_shape(contract, impact, plan)` at plan intake (D-183): S3 needs the selector's admission and the
     * frozen `flags.s3Writers` (D-73); the verdict is journaled and announced whatever it is.
     */
    fun admit(c: OpenedCampaign, estimates: Map<String, Long>?, capabilities: ShapeCapabilities, events: Events?, idGen: IdGen, clock: Clock): S3Admission {
        val config = c.attempt.config
        val state = checkNotNull(c.state)
        val shape = shape(c, state.graph, estimates, remainingTokens(c, clock), config.defaults)
        val selected = ShapeSelector.select(c.contract, c.prescan, config.defaults.shapePolicy, capabilities = capabilities, plan = shape)
        val inputs = when (selected) {
            is ShapeDecision.Selected -> selected.inputs
            is ShapeDecision.Unavailable -> selected.inputs
        }
        val admitted = selected is ShapeDecision.Selected && selected.shape == Shape.S3
        val runtime = admitted && config.flags.s3Writers
        val verdict = inputs?.log.orEmpty() + if (admitted && !runtime) " · S3 runtime off: flags.s3Writers is false (D-183)" else ""
        val name = if (runtime) Shape.S3.name else c.contract.shape.name
        val log = "plan intake: shape $name · $verdict"
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = shape.units.map { it.increment }, text = log, at = clock.instant()))
        events?.emit(AgentEvent.Campaign.ShapeSelected(c.ids, name, "contract:v${c.contract.version} $verdict"))
        return S3Admission(if (runtime) shape.units.map { it.increment }.toSet() else emptySet(), estimates.orEmpty(), log)
    }
}

/** One persisted integration outcome (`packets`, kind `integration`); the controller is its one writer (D-243). */
@Serializable
internal data class IntegrationRecord(
    val published: Boolean,
    val handles: List<String>,
    val increments: List<String>,
    val integrationBase: String? = null,
    val patchHash: String? = null,
    val resultingStamp: String? = null,
    val envId: String? = null,
    val paths: List<String> = emptyList(),
    val receipts: List<String> = emptyList(),
    val step: String? = null,
    val reason: String? = null,
    val evidence: List<String> = emptyList(),
    val archived: String? = null,
    val returnsToMainLine: Boolean = false,
) {
    companion object {
        const val KIND: String = "integration"
        private val JSON = Json { encodeDefaults = true }

        fun of(integration: Integration, increments: List<String>): IntegrationRecord = when (integration) {
            is Integration.Published -> integration.receipt.let { r ->
                IntegrationRecord(true, r.handles, increments, r.integrationBase.digest.hex, r.patchHash.hex, r.resultingStamp.digest.hex, r.envId.hex, r.paths, r.receipts)
            }
            is Integration.Rejected -> IntegrationRecord(
                false, integration.handles, increments, step = integration.step.name, reason = integration.reason, evidence = integration.evidence,
                archived = integration.archived?.hex, returnsToMainLine = integration.returnsToMainLine,
            )
        }

        fun save(store: Store, ids: Identities, id: String, record: IntegrationRecord, clock: Clock) {
            store.db.tx { tx ->
                tx.execute(
                    "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, ids.work, ids.attempt, ids.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(serializer(), record),
                )
            }
        }

        fun all(store: Store, ids: Identities): List<IntegrationRecord> = store.db.query(
            "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid",
            ids.work, ids.attempt, KIND,
        ) { JSON.decodeFromString(serializer(), it.string("body")) }
    }
}

/**
 * Harness verification over one tree outside any cell (P3.1.4 layers): a fresh seed of the contract's checks,
 * exclusive runs in [Workspace] itself (an integration candidate or a rebased worktree is already isolated), the
 * blast selection over [touched] and the tree's own atlas.
 */
internal class TreeVerification(private val c: OpenedCampaign, private val env: EnvFingerprint, private val idGen: IdGen, private val clock: Clock) {
    class Run(val checks: Checks, val receipts: List<Receipt>, val notTested: List<String>) {
        val failures: List<String> get() = receipts.filter { !it.outcome.green }.map { "${it.checkId}: ${it.outcome.name.lowercase()} (${it.receiptId})" }
    }

    suspend fun run(workspace: Workspace, layer: Layer, acceptanceIds: Collection<String>, touched: Collection<String>): Run {
        val config = c.attempt.config
        val ids = c.ids.copy(context = ContextId(idGen.next("integrate")))
        val registry = VersionRegistry(workspace)
        val stamper = Stamper(workspace, env)
        val checks = Checks.seed(c.contract, c.commands, qualityGates = config.qualityGates)
        val scheduler = Scheduler(checks, workspace, registry, stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, ids, clock)
        val runner = TrustedLocalRunner(c.os)
        val redaction = Redaction(config.redaction)
        val logs = c.store.layout.root.resolve("logs")
        val checker = Checker(checks, runner, c.os, stamper, registry, workspace, c.store.blobs, redaction, idGen, ids, logs)
        val verify = Verify(
            checks = checks, scheduler = scheduler, checker = checker, baseline = null, s0 = null, workspace = workspace, runner = runner, os = c.os, stamper = stamper,
            blobs = c.store.blobs, redaction = redaction, estimator = HeuristicEstimator(), idGen = idGen, ids = ids, contracts = c.contracts, logsDir = logs,
        )
        val atlas = Atlas.build(workspace.root)
        verify.atlas = atlas
        verify.inputs = atlas.rows.map { it.path }
        verify.touched = touched
        val run = verify.runLayer(layer, acceptanceIds)
        return Run(checks, run.receipts, run.notTested)
    }
}

/**
 * The worktree-replaying [Rebase] (§10.4, D-244): a stale result's patch is replayed, each path guarded by its `before`
 * version, into a fresh worktree of the current main candidate, and the unit's acceptance is re-run there. The
 * replayed packet keeps the writer's changes and names the new base, read versions and receipts; any conflict or a
 * red acceptance rejects it with the staleness evidence. No model runs again.
 */
internal class ReplayRebase(
    private val workspaces: Workspaces,
    private val verification: TreeVerification,
    private val env: EnvFingerprint,
    private val accept: (String) -> List<String>,
) : Rebase {
    override suspend fun rebase(result: WriterResult, onto: CandidateId, moved: List<String>): WriterResult? {
        val old = result.dispatch
        val task = old.task
        val tree = try {
            workspaces.createWorktree(task.ids.work, task.ids.attempt, "${task.incrementId}-${result.handle}-rebased")
        } catch (refused: WorktreeRefused) {
            return null
        }
        val kept = try {
            replay(result, tree, onto)
        } catch (failure: RuntimeException) {
            null
        }
        if (kept == null) workspaces.remove(tree)
        return kept
    }

    private suspend fun replay(result: WriterResult, tree: Worktree, onto: CandidateId): WriterResult? {
        if (tree.base != onto) return null
        val versions = VersionRegistry(tree.workspace)
        val reads = result.packet.readVersions.keys.mapNotNull { path -> versions.version(path)?.let { path to it } }.toMap()
        for (change in result.packet.changes) {
            if (versions.version(change.path) != change.before) return null
            val bytes = change.after?.let { after -> read(result.dispatch.worktree.workspace, change.path)?.takeIf { FileVersion(Digest.of(it)) == after } ?: return null }
            if (!write(tree.workspace, change.path, bytes)) return null
        }
        val run = verification.run(tree.workspace, Layer.IncrementAcceptance, accept(result.packet.increment), result.packet.changes.map { it.path })
        if (run.failures.isNotEmpty()) return null
        val report = Stamper(tree.workspace, env).report()
        val packet = result.packet.copy(base = PacketBase(onto, tree.id), readVersions = reads, stamp = report.candidateId, envId = report.env.envId, receipts = run.receipts.map { it.receiptId })
        return WriterResult(WriterDispatch(result.dispatch.handle, result.dispatch.task.copy(dispatchCandidate = onto), tree), packet, result.spend)
    }

    private fun read(workspace: Workspace, path: String): ByteArray? =
        (workspace.resolve(path, Intent.Read) as? PathResolution.Resolved)?.let(workspace::bytes)

    private fun write(workspace: Workspace, path: String, bytes: ByteArray?): Boolean {
        val resolved = workspace.resolve(path, Intent.Mutate) as? PathResolution.Resolved ?: return false
        if (bytes == null) {
            Files.deleteIfExists(resolved.real)
        } else {
            resolved.real.parent?.let(Files::createDirectories)
            Files.write(resolved.real, bytes)
        }
        return true
    }
}

/** What one S3 round left: a stop, or the units it integrated and those that return to the main line. */
internal sealed interface S3Result {
    class Stopped(val outcome: CampaignOutcome, val reason: String, val exit: CellExit?) : S3Result

    class Integrated(val committed: List<String>, val returned: List<String>, val exit: CellExit?) : S3Result
}

/**
 * One S3 round of the campaign (§10.4, P5.8.1): the controller dispatches each unit of [S3Admission.batch] as a writer
 * in its own worktree through the Delegator (cancellation checked before start), collects them, and hands the
 * collected results to one [Integrator] whose steps are real here — the combined-tree layer
 * (`Layer.IntegrationReverification`) over the union, contract lint with the required current review, and the
 * replaying rebase. Only then does the controller record the writer cells in the campaign state and commit the
 * ledger, per integrated unit, from the integration receipt (D-243). A late result is archived and never integrated;
 * a unit that did not integrate returns to the main line.
 */
internal class S3Round(
    private val c: OpenedCampaign,
    private val env: EnvFingerprint,
    private val idGen: IdGen,
    private val clock: Clock,
    private val events: Events?,
    private val writerCell: WriterCell,
    /** The writers' exits by handle, filled by [writerCell]: the records the campaign state is advanced with. */
    private val exits: Map<String, CellExit>,
    /** The increment review cell (S2+ contracts); `null` when no review can be owed. */
    private val reviewCell: ((Increment) -> ReviewCell)?,
) {
    private val verification = TreeVerification(c, env, idGen, clock)
    private var combined: TreeVerification.Run? = null
    private val approvals = HashMap<String, Verdict>()

    suspend fun run(batch: List<Increment>, estimates: Map<String, Long>, packets: MutableList<ResultPacket>): S3Result {
        c.refusal()?.let { return S3Result.Stopped(stopOutcome(), "writers not dispatched: $it", null) }
        val config = c.attempt.config
        val contract = c.contract
        val coordinator = c.ids.copy(context = ContextId(idGen.next("s3")))
        val workspaces = Workspaces(c.workspace, c.store.layout.candidates, env)
        val writers = Writers(workspaces, writerCell)
        val horizon = IntegrationHorizon()
        val coherence = Coherence(c.registry).also { it.register(horizon) }
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val generation = c.lease?.generation ?: ExecutionGeneration.INITIAL
        val delegator = Delegator(
            CellChildRunner({ _, _, _, _, _ -> null }, writers = writers), PublicationAuthority { c.refusal() }, c.cancellation,
            DelegationLimits.of(config.defaults, Tokens(maxOf(1L, S3Intake.remainingTokens(c, clock)))), Shape.S3, scope, idGen, clock, events,
        )
        try {
            val stamp = c.stamper.report().candidateId
            val ceiling = Ceiling.of(contract.authorization, config.executionMode)
            val started = ArrayList<Pair<Increment, Handle>>()
            val refused = ArrayList<String>()
            for (increment in batch) {
                val packet = TaskPacket(
                    coordinator, increment.id, ChildKind.Writer.role,
                    increment.requirementIds.mapNotNull { contract.requirement(it) }.map { Excerpt(it.id, it.text, it.authorityRef) }, emptyList(),
                    contract.version, stamp, Controller.WORKSPACE, increment.accept, emptyList(), emptyList(), increment.writeScope, ceiling,
                    Tokens(maxOf(1L, estimates[increment.id] ?: config.defaults.probeTokens.toLong())), generation,
                )
                events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, increment.id))
                when (val dispatch = delegator.dispatch(ChildKind.Writer, packet, DispatchMode.Async)) {
                    is Dispatch.Started -> started += increment to dispatch.handle
                    is Dispatch.Refused -> refused += "${increment.id}: ${dispatch.reason}"
                }
            }
            journal(coordinator, started.map { it.second.id }, "S3 round: writers ${started.joinToString(", ") { "${it.first.id}=${it.second.id}" }} @${stamp.hash8}" + refused.joinToString("") { " · not dispatched $it" })
            val collected = started.map { (increment, handle) -> Triple(increment, handle, delegator.await(handle)) }
            val results = collected.mapNotNull { (_, handle, outcome) ->
                writers.dispatchOf(handle)?.let { WriterResult.of(outcome, it) }?.also { horizon.track(it.handle, it.packet.readVersions) }
            }
            for ((increment, handle, outcome) in collected) {
                val what = when (outcome) {
                    is Collected.Result -> "published"
                    is Collected.Late -> "late (${outcome.reason})"
                    is Collected.Failed -> "failed (${outcome.reason})"
                    is Collected.Pending, is Collected.Unknown -> outcome::class.simpleName!!.lowercase()
                }
                journal(coordinator, listOf(handle.id), "writer ${handle.id} for ${increment.id}: $what · spend ${delegator.budget.spent.value} of ${delegator.limits.treeBudget.value} tree tokens")
            }
            val byHandle = collected.associate { it.second.id to it.first.id }
            val integrator = Integrator(
                workspaces, c.registry, env, combinedCheck(), gates(), { IntegrationAuthority(c.contract.version, generation, PublicationAuthority { c.refusal() }) },
                c.store.blobs, c.intents, idGen, clock, horizon, ReplayRebase(workspaces, verification, env) { id -> checkNotNull(c.state).graph.increments.first { it.id == id }.accept },
            )
            val outcomes = if (results.isEmpty()) emptyList() else integrator.integrate(results)
            for (outcome in outcomes) {
                val increments = outcome.handles.mapNotNull { byHandle[it] }
                IntegrationRecord.save(c.store, coordinator, idGen.next("integration"), IntegrationRecord.of(outcome, increments), clock)
                journal(coordinator, outcome.handles, when (outcome) {
                    is Integration.Published -> "integrated ${increments.joinToString(", ")} @${outcome.receipt.integrationBase.hash8} → @${outcome.receipt.resultingStamp.hash8} patch ${outcome.receipt.patchHash.hash8}"
                    is Integration.Rejected -> "integration of ${increments.joinToString(", ")} rejected at ${outcome.step.name}: ${outcome.reason}" +
                        outcome.evidence.joinToString("") { " · $it" } + (outcome.archived?.let { " · archived ${it.hash8}" } ?: "") +
                        if (outcome.returnsToMainLine) " · the shared decision returns to the main line" else ""
                })
            }
            val published = outcomes.filterIsInstance<Integration.Published>()
            if (published.isNotEmpty()) adopt(published.last().receipt.resultingStamp)
            return settle(collected.map { it.first to it.second }, published, packets, coordinator)
        } finally {
            coherence.close()
            scope.cancel()
            workspaces.worktrees.forEach(workspaces::remove)
        }
    }

    /**
     * The controller's records of the round: each writer that produced an exit becomes a cell of its increment in the
     * campaign state, and each integrated one is verified against the receipts at the published stamp and committed
     * — unless cancellation or a lost lease arrived first, when it stops with nothing committed.
     */
    private fun settle(started: List<Pair<Increment, Handle>>, published: List<Integration.Published>, packets: MutableList<ResultPacket>, coordinator: Identities): S3Result {
        val receipts = published.flatMap { p -> p.receipt.handles.map { it to p.receipt } }.toMap()
        val committed = ArrayList<String>()
        val returned = ArrayList<String>()
        var last: CellExit? = null
        for ((increment, handle) in started) {
            val exit = exits[handle.id]
            if (exit == null) {
                returned += increment.id
                continue
            }
            packets += exit.packet
            last = exit
            c.advance(Transition.Dispatched(increment.id, handle.child))
            c.advance(Transition.Returned(exit))
            val receipt = receipts[handle.id]
            if (receipt == null || exit !is CellExit.Completed) {
                returned += increment.id
                continue
            }
            val refusal = c.refusal()
            if (refusal != null) {
                journal(coordinator, listOf(handle.id), "integrated ${increment.id} not committed: $refusal")
                return S3Result.Stopped(stopOutcome(), "integrated writer results not committed: $refusal", last)
            }
            val stampNow = c.stamper.report().candidateId
            val current = checkNotNull(c.state)
            val returnedIncrement = current.graph.increments.first { it.id == increment.id }
            val proposal = CompletionProposal(increment.id, "done", c.contract.version, receipt.integrationBase, receipt.resultingStamp, receipt.patchHash, receipt.envId)
            val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock)
            val currencies = c.checks.all().filter { it.last != null }.associate { it.id to scheduler.currency(it, stampNow) }
            val reviews = approvals[increment.id]?.let { verdict -> returnedIncrement.accept.filter { c.contract.acceptance(it) is Acceptance.Review }.associateWith { verdict } }.orEmpty()
            when (val result = Verifier().accept(proposal, c.contract, returnedIncrement, exit.register, current.ledger, stampNow, currencies, reviews = reviews)) {
                is CompletionResult.Accepted -> {
                    c.advance(Transition.Committed(result, stampNow))
                    events?.emit(AgentEvent.Campaign.IncrementClosed(c.ids, increment.id, "verified"))
                    committed += increment.id
                }
                is CompletionResult.Refused, is CompletionResult.NotCompleted -> {
                    journal(coordinator, listOf(handle.id), "integrated ${increment.id} not accepted at @${stampNow.hash8}: ${(result as? CompletionResult.Refused)?.missing?.joinToString("; ") ?: result.toString()}")
                    returned += increment.id
                }
            }
        }
        c.refusal()?.let { return S3Result.Stopped(stopOutcome(), "late writer results archived; publication refused: $it", last) }
        if (returned.isNotEmpty()) journal(coordinator, emptyList(), "S3 off for the rest of the campaign: ${returned.joinToString(", ")} return to the main line")
        return S3Result.Integrated(committed, returned, last)
    }

    /**
     * The combined-tree step (§10.4, D-244): blast radius over the union of the merged edit sets and every affected
     * acceptance — the batch's own items and the regression items of verified increments — on the candidate.
     */
    private fun combinedCheck(): IntegrationChecks = IntegrationChecks { candidate, union, results ->
        val graph = checkNotNull(c.state).graph
        val own = results.flatMap { r -> graph.increments.first { it.id == r.packet.increment }.accept }
        val regressions = graph.increments.filter { it.status == IncrementStatus.Verified }.flatMap { it.accept }
        val run = verification.run(candidate, Layer.IntegrationReverification, (own + regressions).distinct(), union)
        combined = run
        IntegrationCheck(run.failures, run.receipts.map { it.receiptId })
    }

    /**
     * Contract lint and the required current review (§10.4, D-244): every changed path inside the contract's write
     * scope and outside its protected paths, no `CON`-anchored path (an interface change needs the main line first);
     * then, per unit that owes an increment review (§8.8 triggers), an approval of the combined candidate.
     */
    private fun gates(): IntegrationChecks = IntegrationChecks { candidate, union, results ->
        val contract = c.contract
        val failures = ArrayList<String>()
        union.filter { !contract.scope.allowsWrite(it) }.forEach { failures += "contract lint: $it is outside the contract's write scope or protected" }
        for ((id, paths) in c.kb.contractAnchors()) {
            val hit = union.filter { it in paths }
            if (hit.isNotEmpty()) failures += "contract lint: $id anchors ${hit.joinToString(", ")}; an interface change needs a CON/ADR in the main line first"
        }
        if (failures.isNotEmpty()) return@IntegrationChecks IntegrationCheck(failures)
        val graph = checkNotNull(c.state).graph
        val prescan = c.impactPrescan
        val impact = RiskFloorInput(prescan.contractsTouched.size, prescan.complete, prescan.prescan.fanIn, prescan.complete)
        val signed = ArrayList<String>()
        for (result in results) {
            val increment = graph.increments.first { it.id == result.packet.increment }
            val triggers = ReviewTriggers.increment(IncrementReviewInput(contract, increment, Roles.writer, null, result.packet.flags.testIntegrity, result.packet.changes.map { it.path }, c.kb.contractAnchors(), impact))
            if (triggers.isEmpty()) continue
            val cell = reviewCell?.invoke(increment)
            if (cell == null) {
                failures += "required review of ${increment.id} (${triggers.joinToString(", ")}): no review cell in ${contract.shape}"
                continue
            }
            val registry = VersionRegistry(candidate)
            val packet = evidence(candidate, registry, increment, results, triggers, result)
            when (val review = cell.obtain(packet, FunctionTable.DEFAULT.row(ReviewTriggers.function(triggers)).defaultTier, registry::version)) {
                is ReviewOutcome.Approved -> {
                    review.record.verdict?.let { approvals[increment.id] = it }
                    signed += "review:${increment.id}"
                }
                is ReviewOutcome.Declined -> failures += "review of ${increment.id} declined: ${review.reason}"
                is ReviewOutcome.Unavailable -> failures += "review of ${increment.id} unavailable: ${review.reason}"
            }
        }
        IntegrationCheck(failures, signed)
    }

    /** The §8.8 evidence packet of [increment] over the combined candidate: its patch as the diff, the combined receipts. */
    private fun evidence(candidate: Workspace, registry: VersionRegistry, increment: Increment, results: List<WriterResult>, triggers: List<String>, own: WriterResult): EvidencePacket {
        val contract = c.contract
        val stamp = Stamper(candidate, env).report().candidateId
        val diff = buildString {
            for (change in results.flatMap { it.packet.changes }.sortedBy { it.path }) {
                append("--- a/").append(change.path).append('\n').append("+++ b/").append(change.path).append('\n')
                val bytes = (candidate.resolve(change.path, Intent.Read) as? PathResolution.Resolved)?.let(candidate::bytes)
                if (bytes == null) append("-deleted\n") else String(bytes, Charsets.UTF_8).lineSequence().forEach { append('+').append(it).append('\n') }
            }
        }.let { if (it.length <= MAX_DIFF_CHARS) it else it.take(MAX_DIFF_CHARS) + "\n… cut at $MAX_DIFF_CHARS chars" }
        val ref = c.store.blobs.put(diff.toByteArray(Charsets.UTF_8), BlobKind.PACKET, c.ids)
        val receipts = SqliteReceipts(c.store, clock)
        val required = increment.accept.toSet()
        return EvidencePacket(
            id = idGen.next("evidence"), ids = c.ids.withCandidate(stamp), scope = ReviewScope.Increment, incrementId = increment.id, contractVersion = contract.version, candidate = stamp,
            requirements = increment.requirementIds.mapNotNull { contract.requirement(it) }.map { Excerpt(it.id, it.text, it.authorityRef) },
            criteria = EvidencePacket.criteria(contract, increment), diff = diff, diffRef = ref.hex,
            receipts = combined?.receipts.orEmpty().mapNotNull { receipts.get(it.receiptId) }.map { ReviewReceipt.of(it, it.acceptanceIds.any { a -> a in required }) },
            notes = c.kb.contractAnchors().filterValues { anchored -> own.packet.changes.any { it.path in anchored } }.map { (id, paths) -> "$id anchors ${paths.sorted().joinToString(", ")}" },
            testIntegrity = own.packet.flags.testIntegrity, preexisting = emptyList(), coverage = null, rubric = EvidencePacket.RUBRIC,
            evidenceVersions = own.packet.changes.mapNotNull { ch -> registry.version(ch.path)?.let { ch.path to it } }.toMap(), triggers = triggers,
        )
    }

    /** The combined run's results at [stamp] become the main line's (the published tree is those very bytes). */
    private fun adopt(stamp: CandidateId) {
        val run = combined ?: return
        for (check in run.checks.all()) {
            val last = check.last?.takeIf { it.stamp == stamp } ?: continue
            if (c.checks[check.id] != null) c.checks.record(check.id, last) else if (check.kind != CheckKind.Full) c.checks.register(check)
        }
    }

    private fun stopOutcome(): CampaignOutcome = if (c.cancellation.cancelled) CampaignOutcome.Cancelled else CampaignOutcome.BlockedExternal

    private fun journal(ids: Identities, refs: List<String>, text: String) {
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = refs, text = text, at = clock.instant()))
    }

    private companion object {
        const val MAX_DIFF_CHARS: Int = 16_000
    }
}

/** The writer-token estimate of one unit (D-242): its compiled writer `[K]` plus output headroom, for every writer turn. */
internal fun writerEstimate(contextTokens: Long, maxOutputTokens: Int): Long = (contextTokens + maxOutputTokens) * WriterBudget.DEFAULT.turns
