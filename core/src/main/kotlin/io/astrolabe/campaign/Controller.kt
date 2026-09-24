package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.Project
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Prime
import io.astrolabe.atlas.RulesSnapshot
import io.astrolabe.atlas.Sniffed
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RulesTrust
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Cell
import io.astrolabe.cell.CellContext
import io.astrolabe.cell.CellEvidence
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.CellTools
import io.astrolabe.cell.CellWorkspace
import io.astrolabe.cell.DispatchAuthority
import io.astrolabe.cell.DispatchRefusal
import io.astrolabe.cell.Gates
import io.astrolabe.cell.Roles
import io.astrolabe.cell.SqliteCheckpoints
import io.astrolabe.cell.TouchKind
import io.astrolabe.cell.Touched
import io.astrolabe.context.Compiled
import io.astrolabe.context.Compiler
import io.astrolabe.context.Manifest
import io.astrolabe.context.SqliteManifests
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteIntentJournal
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.RandomIdGen
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.kb.EmptyKb
import io.astrolabe.kb.Kb
import io.astrolabe.os.Git
import io.astrolabe.os.LocalOs
import io.astrolabe.os.search.Searches
import io.astrolabe.provider.Money
import io.astrolabe.register.Register
import io.astrolabe.register.SqliteRegisterVersions
import io.astrolabe.register.Validator
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.Spans
import io.astrolabe.telemetry.TraceSpanStatus
import io.astrolabe.tool.TurnCheckpoint
import io.astrolabe.tool.edit.CliSyntax
import io.astrolabe.tool.edit.Edit
import io.astrolabe.tool.edit.SyntaxCheck
import io.astrolabe.tool.kb.KbTool
import io.astrolabe.tool.look.Look
import io.astrolabe.tool.run.Run
import io.astrolabe.tool.run.SqliteHandles
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.tool.state.StateTool
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.Currency
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.verify.Verifier
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Snapshot
import io.astrolabe.workspace.SnapshotEntryKind
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** What a host opens a campaign for (§3.7 `campaign(request, repo, policy)`); a reopen passes the same ids. */
public data class CampaignRequest(val work: WorkId, val attempt: AttemptId, val text: String) {
    init {
        require(text.isNotBlank()) { "a campaign needs a request" }
    }
}

/**
 * The host's campaign policy: the token (and optional money) budget the contract freezes — there is no default
 * campaign budget — and whether a resume is expected, which rules out S0 (§3.5).
 */
public data class CampaignPolicy @JvmOverloads constructor(
    val tokens: Tokens,
    val cost: Money? = null,
    val resumeExpected: Boolean = false,
)

/** What open-time reconciliation found (§13.1), before any consequential action. */
public data class Reconciliation(
    /** Intents that never committed: their outcome is `unknown_outcome` and the runner refuses to relaunch them. */
    val unknownOutcomes: List<String>,
    /** Members that moved while no controller held the store, as `Touched (external)` lines. */
    val external: List<Touched>,
    /** The tree at the end of reconciliation. */
    val stamp: CandidateId,
)

/**
 * An opened campaign (§3.7 first lines): contract, workspace, store and KB are open, pending actions and the tree
 * are reconciled, and the shape is selected. [state] is `null` only when `G_single(C)` does not validate; then
 * [stop] says why and nothing was persisted beyond the contract. Closing releases the store and its project lock.
 */
public class OpenedCampaign internal constructor(
    public val request: CampaignRequest,
    public val ids: Identities,
    public val store: Store,
    public val os: LocalOs,
    public val workspace: Workspace,
    public val registry: VersionRegistry,
    public val stamper: Stamper,
    public val dirty: DirtyState,
    public val shadow: ShadowRef,
    /** The dirty state captured when the campaign first opened: snapshot 0, the floor of every revert. */
    public val s0: Snapshot,
    public val atlas: Atlas,
    public val sniffed: Sniffed,
    public val commands: RunnerCommands,
    public val contracts: Contracts,
    public val checks: Checks,
    /** The approved rules snapshot, or `null`: unbound rules files stay data (D-32). */
    public val rules: RulesSnapshot?,
    public val prime: String,
    public val kb: Kb,
    public val journal: Journal,
    public val intents: IntentJournal,
    public val campaigns: Campaigns,
    public val reconciliation: Reconciliation,
    public val prescan: Prescan,
    public val shape: ShapeDecision,
    state: CampaignState?,
    private val refusal: String?,
    /** False when a [io.astrolabe.Project] owns the store and the OS; then [close] releases nothing of theirs. */
    private val owned: Boolean = true,
    /** The configuration this attempt runs under, frozen at its first open (invariant 12). */
    public val attempt: AttemptConfig,
    /** The workspace lease taken after reconciliation; publication needs it live (§13.1). */
    public val lease: Lease?,
    private val leases: Leases,
) : AutoCloseable {
    /** Cancels this campaign: no further dispatch, no publication; effects already made are archived (D-26). */
    public val cancellation: Cancellation = Cancellation()

    /** Why dispatch or publication is refused now: cancellation first, then the lease; `null` when authorized. */
    public fun refusal(): String? = cancellation.reason?.let { "cancelled: $it" } ?: lease?.let { leases.authority(it).refusal() }

    @Volatile
    public var state: CampaignState? = state
        private set

    public val contract: Contract get() = checkNotNull(contracts.current(ids.work)) { "the campaign's contract is stored at open" }

    /** Why the campaign cannot run now, or `null` when it may dispatch. */
    public val stop: Disposition.Stop?
        get() {
            val current = state ?: return Disposition.Stop(CampaignOutcome.WaitingForInput, checkNotNull(refusal))
            val outcome = current.outcome ?: return null
            return Disposition.Stop(outcome, current.reason ?: outcome.wire)
        }

    /** Applies [transition] and saves the result: the controller is the one writer of the campaign row (L9). */
    public fun advance(transition: Transition): CampaignState {
        val next = Lifecycle.apply(checkNotNull(state) { "no campaign state: ${refusal}" }, contract, transition)
        campaigns.save(next)
        state = next
        return next
    }

    override fun close() {
        if (!owned) return
        try {
            os.close()
        } finally {
            store.close()
        }
    }
}

/** What [Controller.runS0] did: the campaign state it left, the cell's exit, the verifier's result and the compile. */
public data class S0Run(
    val state: CampaignState?,
    val exit: CellExit?,
    val completion: CompletionResult?,
    val compiled: Compiled?,
    /** The finish receipt, for a campaign this run ended (P1.9.5). */
    val finish: FinishReceipt? = null,
) {
    public val outcome: CampaignOutcome? get() = state?.outcome
}

/**
 * The campaign controller (§3.2, §3.7): nothing sits above it. [open] runs the lines of `campaign()` that precede
 * the first dispatch — open, reconcile, pre-scan, select the shape — and leaves the store owned by the returned
 * [OpenedCampaign].
 */
public class Controller @JvmOverloads public constructor(
    private val config: Config,
    private val clock: Clock = Clock.systemUTC(),
    private val idGen: IdGen = RandomIdGen(),
    private val events: Events? = null,
    private val env: EnvInputs = EnvInputs(runnerPolicyId = "trusted-local/v1"),
    private val faults: FaultPoints = FaultPoints.NONE,
    /** Runtime spans (P1.11.1): a campaign-run span and one child span per cell; `null` records none. */
    private val spans: Spans? = null,
    /** How long a workspace lease lasts; its expiry revokes publication authority only (§13.1). */
    private val leaseDuration: Duration = Duration.ofHours(1),
) {
    /**
     * Opens or reopens [request]'s campaign over [repo]. Order (§3.7, §13.1): the store and its project lock, the
     * workspace and the dirty-state capture (snapshot 0 on first open), the contract derived and stored after the
     * capture, then reconciliation of open intents and of tree drift before anything may dispatch, then the
     * pre-scan stub and the shape. A reopen of a campaign that stopped on something outside it resumes it.
     */
    public fun open(repo: Path, request: CampaignRequest, policy: CampaignPolicy): OpenedCampaign {
        val git = Git(repo)
        val store = Store.open(config, git, clock, faults)
        val os = try {
            LocalOs(clock)
        } catch (failure: Throwable) {
            store.close()
            throw failure
        }
        try {
            return open(repo, git, store, os, request, policy, owned = true)
        } catch (failure: Throwable) {
            runCatching { os.close() }
            runCatching { store.close() }
            throw failure
        }
    }

    /** Opens [request]'s campaign in [project], whose store, lock and OS stay the project's (P1.9.6). */
    public fun open(project: Project, request: CampaignRequest, policy: CampaignPolicy): OpenedCampaign =
        open(project.root, project.git, project.store, project.os, request, policy, owned = false)

    private fun open(repo: Path, git: Git, store: Store, os: LocalOs, request: CampaignRequest, policy: CampaignPolicy, owned: Boolean): OpenedCampaign {
        val ids = Identities(request.work, request.attempt)
        val protected = ProtectedPaths()
        val workspace = Workspace(WORKSPACE, repo, git, protected)
        val registry = VersionRegistry(workspace)
        val stamper = Stamper(workspace, EnvFingerprint.compute(env))
        val journal = Journal(store, clock)
        val intents = SqliteIntentJournal(store, clock)
        val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock, events)
        val campaigns = SqliteCampaigns(store, clock)
        val attempts = Attempts(store, clock)
        val frozen = attempts.load(request.work, request.attempt) ?: AttemptConfig.freeze(config).also { attempts.save(request.work, request.attempt, it) }
        val effective = frozen.config
        if (effective != config) {
            events?.emit(AgentEvent.Warning(ids, "config-frozen", "the configuration changed during attempt ${request.attempt.value}; it takes effect at the next attempt (invariant 12)"))
        }
        val dirty = DirtyState(workspace, store.blobs, stamper, ids, clock)
        val shadow = ShadowRef(request.work, request.attempt, workspace, store, dirty, os, clock)

        // Capture before anything else looks at the tree: snapshot 0 is the user's pre-existing state.
        val first = shadow.record(0) == null
        val s0 = if (first) dirty.capture(0).also { shadow.open(it) } else checkNotNull(shadow.manifest(0))
        val external = if (first) emptyList() else drift(shadow, dirty)

        val atlas = Atlas.build(workspace.root)
        val derived = contracts.deriveS0(request.work, request.attempt, request.text, atlas, effective, policy.tokens, protected, policy.cost)
        val stored = contracts.current(request.work)
        check(stored == null || stored.attemptId == request.attempt) { "work ${request.work.value} is attempt ${stored?.attemptId?.value}; a new attempt is P2" }
        val contract = stored ?: contracts.open(derived.contract)
        val commands = derived.primary?.let(RunnerCommands::of) ?: RunnerCommands()
        val checks = Checks.seed(contract, commands)
        val rules = RulesTrust(workspace.root).approved(effective.rulesFile)?.let { RulesSnapshot(it.binding.path, it.digest, it.text) }
        val prime = Prime.render(atlas, derived.sniffed, rules)

        var refusal: String? = null
        var state: CampaignState? = campaigns.load(request.work, request.attempt)
        if (state == null) {
            val graph = ShapeSelector.single(contract)
            val issues = graph.validate(contract)
            if (issues.isEmpty()) {
                state = Lifecycle.open(contract, graph).also(campaigns::save)
            } else {
                refusal = "G_single(C) has nothing to accept against: ${issues.joinToString("; ") { it.detail }} — state a run: acceptance or amend the contract"
            }
        } else if (state.phase == CampaignPhase.Ended && state.outcome?.resumable == true) {
            state = Lifecycle.apply(state, contract, Transition.Resumed("reopened after ${state.outcome?.wire}")).also(campaigns::save)
        }

        // §13.1: every intent that never committed is an unknown outcome until reconciled; none is replayed.
        val unknown = intents.open().filter { it.ids.work == request.work }
        for (intent in unknown) {
            if (intent.status != IntentStatus.Unknown) intents.update(intent.intentId, IntentStatus.Unknown)
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(intent.intentId, intent.actionId), text = "open: intent ${intent.intentId} (${intent.argv.joinToString(" ")}) never committed · unknown_outcome", at = clock.instant()))
            events?.emit(AgentEvent.Run.Reconciled(ids, intent.actionId, "unknown_outcome"))
        }
        val stamp = stamper.report().candidateId
        if (external.isNotEmpty()) {
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = external.map { it.path }, text = "open: ${external.size} paths moved while closed (external) · reconciled @${stamp.hash8}", at = clock.instant()))
        }
        val reconciliation = Reconciliation(unknown.map { it.intentId }, external, stamp)
        // A cell still running in the stored state belonged to a controller that stopped mid-cell: it is lost.
        state?.running?.takeIf { state.phase == CampaignPhase.Running }?.let { running ->
            val checkpoint = SqliteCheckpoints(store, clock).latest(running.cell)
            journal.append(JournalEvent(idGen.next("ev"), ids, checkpoint?.turn, JournalKind.Reconcile, refs = listOf(running.cell.value), text = "open: cell ${running.cell.value} was running when its controller stopped; last checkpoint turn ${checkpoint?.turn ?: "none"} · lost", at = clock.instant()))
            state = Lifecycle.apply(state, contract, Transition.Lost(running.cell, checkpoint)).also(campaigns::save)
        }
        if (state?.phase == CampaignPhase.Opened) {
            state = Lifecycle.apply(state, contract, Transition.Reconciled(reconciliation.unknownOutcomes)).also(campaigns::save)
        }

        // §13.1: the old owner's unknown effects are reconciled above, before this writer is granted the workspace.
        val leases = Leases(store, clock)
        val lease = leases.acquire(WORKSPACE, ids, "controller:${store.holder.pid}", leaseDuration)
        val prescan = Prescan.UNKNOWN
        val selected = ShapeSelector.select(contract, prescan, effective.defaults.shapePolicy, policy.resumeExpected)
        events?.emit(AgentEvent.Campaign.Opened(ids, contract.requests.last().id))
        val inputs = when (selected) {
            is ShapeDecision.Selected -> selected.inputs
            is ShapeDecision.Unavailable -> selected.inputs
        }
        events?.emit(AgentEvent.Campaign.ShapeSelected(ids, (selected as? ShapeDecision.Selected)?.shape?.name ?: "blocked", "contract:v${contract.version} ${inputs?.log.orEmpty()}".trim()))
        // Until the campaign loop (P2.2.2) this controller runs S0 only: any other selection is an honest block.
        val shape = when {
            selected is ShapeDecision.Selected && selected.shape != Shape.S0 ->
                ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.shape} selected (${selected.inputs?.log}); this build runs S0 only (campaign loop P2.2.2)", selected.inputs)
            selected is ShapeDecision.Unavailable -> ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.reason}", selected.inputs)
            else -> selected
        }
        if (shape is ShapeDecision.Unavailable && state?.phase == CampaignPhase.Running && state.running == null) {
            state = Lifecycle.apply(state, contract, Transition.Stopped(CampaignOutcome.BlockedExternal, shape.reason)).also(campaigns::save)
        }

        return OpenedCampaign(
            request, ids, store, os, workspace, registry, stamper, dirty, shadow, s0, atlas, derived.sniffed, commands,
            contracts, checks, rules, prime, EmptyKb, journal, intents, campaigns, reconciliation, prescan, shape, state, refusal, owned,
            frozen, lease, leases,
        )
    }

    /**
     * `Controller.runS0()` (§3.6, §3.7 in the S0 shape): compile the one ready increment, run one implementing
     * cell, reconcile and persist its return, verify the completion against current receipts (never the model's
     * word), commit it if the tree is still the one it is about, and finish at the final stamp — or stop with the
     * honest outcome `dispatch_outcome` gives (S0 has no continuation cell or replan, D-64). The ledger moves only
     * through [Transition.Committed] on a verifier-accepted completion.
     */
    @JvmOverloads
    public suspend fun runS0(
        campaign: OpenedCampaign,
        model: CellModel,
        authority: Authority = AutonomousAuthority(),
        syntax: SyntaxCheck = CliSyntax(campaign.os, campaign.workspace.root, campaign.store.layout.root.resolve("logs"), python = null, node = null),
    ): S0Run {
        val result = spans?.span(Phase.Plan, campaign.ids) { span -> runS0(campaign, model, authority, syntax, span) }
            ?: runS0(campaign, model, authority, syntax, null)
        return finish(campaign, result)
    }

    /** Every ended campaign leaves a finish receipt, stored and exported, and says so on the bus (§5.9). */
    private fun finish(c: OpenedCampaign, result: S0Run): S0Run {
        val outcome = result.state?.outcome ?: return result
        val receipts = SqliteReceipts(c.store, clock)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, receipts, SqliteAliases(c.store, clock), idGen, c.ids, clock)
        val receipt = FinishReceipts.build(c, listOfNotNull(result.exit?.packet), currencies(c, scheduler, c.stamper.report().candidateId), receipts::get)
        val (ref, _) = FinishReceipts.export(c, receipt)
        events?.emit(AgentEvent.Campaign.Finished(c.ids, outcome.wire, ref))
        return result.copy(finish = receipt)
    }

    private suspend fun runS0(campaign: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): S0Run {
        val c = campaign
        c.stop?.let { return S0Run(c.state, null, null, null) }
        // Every later use reads the attempt's frozen configuration, never the controller's live one (invariant 12).
        val config = c.attempt.config
        c.refusal()?.let { return S0Run(c.advance(Transition.Stopped(stopOutcome(c), "nothing dispatched: $it")), null, null, null) }
        val opened = checkNotNull(c.state)
        check(opened.phase == CampaignPhase.Running && opened.running == null) { "runS0 needs a reconciled campaign with no running cell; it is ${opened.phase}" }
        val contract = c.contract
        val ready = opened.graph.readyFrontier(contract, 1).firstOrNull()
            ?: return S0Run(stopOrFinish(c, "no ready increment: an empty frontier never means completed"), null, null, null)
        val role = Roles.implementing
        val compiled = Compiler(model.estimator, config).compile(ready, contract, model.profile, role, c.prime, maxOutputTokens = model.maxOutputTokens)
        when (compiled) {
            is Compiled.Ready -> Unit
            is Compiled.NeedsRescoping -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "NEEDS_RESCOPING_OR_LARGER_PROFILE: ${compiled.reason}")), null, null, compiled)
            is Compiled.NeedsEvidence -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "NEEDS_MORE_EVIDENCE: acceptance without definition ${compiled.missing}")), null, null, compiled)
        }

        val cellId = ContextId(idGen.next("cell"))
        events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, ready.id))
        val dispatched = c.advance(Transition.Dispatched(ready.id, cellId))
        val increment = dispatched.graph.increments.first { it.id == ready.id }
        val ids = c.ids.copy(context = cellId)
        val redaction = Redaction(config.redaction)
        val logs = c.store.layout.root.resolve("logs")
        val estimator = model.estimator
        val runner = TrustedLocalRunner(c.os)
        val workset = Workset(immediateStubTokens = config.defaults.immediateStubTokens)
        val observations = SqliteObservations(c.store, clock)
        val aliases = SqliteAliases(c.store, clock)
        val receipts = SqliteReceipts(c.store, clock)
        val registerVersions = SqliteRegisterVersions(c.store, clock)
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val preimages = Preimages(c.workspace, c.store.blobs, ids, clock)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, receipts, aliases, idGen, ids, clock)
        val checker = Checker(c.checks, runner, c.os, c.stamper, c.registry, c.workspace, c.store.blobs, redaction, idGen, ids, logs)
        val verify = Verify(checks = c.checks, scheduler = scheduler, checker = checker, baseline = null, s0 = c.s0.stampId, workspace = c.workspace, runner = runner, os = c.os, stamper = c.stamper, blobs = c.store.blobs, redaction = redaction, estimator = estimator, idGen = idGen, ids = ids, contracts = c.contracts, logsDir = logs)
        verify.inputs = c.atlas.rows.map { it.path }
        val tools = CellTools(
            state = StateTool(Validator(estimator), registerVersions, c.journal, estimator, idGen, ids, clock, Register.empty(cellId, increment.id, increment.title), events),
            look = Look(c.workspace, c.registry, workset, c.atlas, Searches.jvm(), c.journal, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids),
            edit = Edit(c.workspace, c.registry, workset, c.os, preimages, ScopeGuard(c.workspace), c.contracts, c.checks, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, syntax),
            run = Run(c.workspace, c.registry, c.stamper, runner, c.os, c.intents, SqliteHandles(c.store, clock), observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, c.contracts, authority, config, clock, logs),
            verify = verify,
            task = TaskTool(authority, c.contracts, c.journal, estimator, idGen, ids, clock, events),
            kb = KbTool(c.kb, estimator, idGen),
        )
        val coherence = Coherence(c.registry)
        val accounting = Accounting(c.store, clock)
        // §6.5: every compiled context leaves a manifest; the cell's end event links it.
        val manifests = SqliteManifests(c.store, clock)
        val manifest = Manifest.of(idGen.next("manifest"), compiled, increment, contract, ids, model.profile, effort = model.effort.name.lowercase()).also { manifests.save(ids, it) }
        val ctx = CellContext(
            ids = ids, role = role, contracts = c.contracts, model = model, tools = tools,
            workspace = CellWorkspace(c.workspace, c.registry, coherence, c.stamper, workset, c.checks, scheduler, c.atlas, checker),
            evidence = CellEvidence(c.journal, observations, aliases, receipts, c.intents, registerVersions, checkpoints, preimages),
            prime = c.prime, ledger = dispatched.ledger, preexisting = compiled.k.ledger, config = config,
            turnCheckpoint = TurnCheckpoint { snapshot(c) },
            accounting = accounting,
            manifest = manifest.id,
        )
        val budget = CellBudget.of(contract.budget.tokens, contract.budget.turnsPerCell, contract.budget.reserves)
        val cellSpan = spans?.start(Phase.Edit, ids, span)
        val dispatch = DispatchAuthority { c.refusal()?.let { DispatchRefusal(it, cancelled = c.cancellation.cancelled) } }
        // A cell that finished before the cancellation reached it keeps its exit: a late completion, archived below.
        val finished = AtomicReference<CellExit?>(null)
        val exit = try {
            coroutineScope {
                val job = async { Cell(clock, idGen, config.defaults, Gates.s0(), events, authority = dispatch).run(ctx, increment, budget).also(finished::set) }
                // A cancellation mid-call interrupts the in-flight request; the cell settles its checkpoint first.
                c.cancellation.onCancel { job.cancel(CancellationException("cancelled: $it")) }.use { job.await() }
            }
        } catch (cancelled: CancellationException) {
            if (!c.cancellation.cancelled || !currentCoroutineContext().isActive) {
                cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
                throw cancelled
            }
            finished.get()
        } catch (failure: Throwable) {
            cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
            throw failure
        } finally {
            coherence.close()
        }
        accounting.calls(c.ids.work).firstOrNull { it.ids.context == cellId }?.usage?.takeIf { it.isComplete }?.let { manifests.recordFirstUsage(ids, manifest.id, it.totalInput) }
        if (exit == null) {
            cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
            snapshot(c)
            val checkpoint = checkNotNull(checkpoints.latest(cellId)) { "a cancelled cell settles its checkpoint" }
            c.advance(Transition.Interrupted(checkpoint))
            return S0Run(c.advance(Transition.Stopped(CampaignOutcome.Cancelled, checkNotNull(c.cancellation.reason))), null, null, compiled)
        }
        if (cellSpan != null && spans != null) {
            // The cell's exclusive cost is its own model calls, priced once here and never again by a parent.
            val cost = Accounting.totals(accounting.calls(c.ids.work).filter { it.ids.context == cellId }, 0, spans.currency).money
            spans.end(cellSpan, cost)
        }
        // The tree after the cell is the base the next open reconciles against: only moves after this are external.
        snapshot(c)
        c.advance(Transition.Returned(exit))

        val stampNow = c.stamper.report().candidateId
        val completion = if (exit is CellExit.Completed) {
            val current = c.contract
            val returned = checkNotNull(c.state).graph.increments.first { it.id == increment.id }
            Verifier().accept(exit.packet.proposal(), current, returned, exit.register, checkNotNull(c.state).ledger, stampNow, currencies(c, scheduler, stampNow))
        } else {
            null
        }
        val state = when (val disposition = Lifecycle.disposition(exit, completion)) {
            is Disposition.Close -> {
                // §3.7 publication: a completion that arrives after cancellation or lease loss is archived, never committed.
                c.refusal()?.let { reason ->
                    c.journal.append(JournalEvent(idGen.next("ev"), ids, exit.turns, JournalKind.Reconcile, refs = disposition.accepted.receiptIds, text = "late completion of ${increment.id} archived; publication refused: $reason", at = clock.instant()))
                    return S0Run(c.advance(Transition.Stopped(stopOutcome(c), "late completion archived; publication refused: $reason")), exit, completion, compiled)
                }
                c.advance(Transition.Committed(disposition.accepted, stampNow))
                events?.emit(AgentEvent.Campaign.IncrementClosed(c.ids, increment.id, "verified"))
                stopOrFinish(c, "requirements remain unverified after ${increment.id}", scheduler)
            }
            // S0 has no continuation cell: the fallback is the honest outcome (D-64).
            is Disposition.Continue -> c.advance(Transition.Stopped(disposition.fallback, disposition.reason))
            is Disposition.Stop -> c.advance(Transition.Stopped(disposition.outcome, disposition.reason))
        }
        return S0Run(state, exit, completion, compiled)
    }

    /**
     * `finish` (§3.7) in its S0 form: every requirement verified and every `run:` item re-certified by a current
     * receipt at the final stamp, else an honest stop — never `completed` over a gap.
     */
    private fun stopOrFinish(c: OpenedCampaign, unfinished: String, scheduler: Scheduler? = null): CampaignState {
        val state = checkNotNull(c.state)
        if (state.ledger.unfinished().isNotEmpty() || scheduler == null) {
            return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, unfinished))
        }
        val stamp = c.stamper.report().candidateId
        val currencies = currencies(c, scheduler, stamp)
        val contract = c.contract
        val gaps = ArrayList<String>()
        val receipts = ArrayList<String>()
        for (item in contract.acceptance.filterIsInstance<io.astrolabe.contract.Acceptance.Run>()) {
            val receipt = c.checks.forAcceptance(item.id).firstNotNullOfOrNull { check -> currencies[check.id]?.takeIf { it.certifies }?.receiptId }
            if (receipt == null) gaps += "${item.id}: no current receipt at @${stamp.hash8}" else receipts += receipt
        }
        c.refusal()?.let { return c.advance(Transition.Stopped(stopOutcome(c), "final acceptance not published: $it")) }
        if (gaps.isNotEmpty() || receipts.isEmpty()) {
            return c.advance(Transition.Stopped(CampaignOutcome.Failed, "final acceptance at @${stamp.hash8} failed: ${gaps.ifEmpty { listOf("no run: receipt") }.joinToString("; ")}"))
        }
        c.advance(Transition.Finishing(stamp))
        return c.advance(Transition.Finished(stamp, receipts.distinct()))
    }

    /** The outcome of a refused dispatch or publication: `cancelled` for a cancellation, else the lost lease blocks. */
    private fun stopOutcome(c: OpenedCampaign): CampaignOutcome =
        if (c.cancellation.cancelled) CampaignOutcome.Cancelled else CampaignOutcome.BlockedExternal

    private fun currencies(c: OpenedCampaign, scheduler: Scheduler, stamp: CandidateId): Map<String, Currency> =
        c.checks.all().filter { it.last != null }.associate { it.id to scheduler.currency(it, stamp) }

    /** Records the tree as the next shadow snapshot, when it moved since the last one. */
    private fun snapshot(c: OpenedCampaign) {
        val last = c.shadow.records().last()
        val now = c.dirty.capture(last.turn + 1)
        if (now.manifestDigest != checkNotNull(c.shadow.manifest(last.turn)).manifestDigest) c.shadow.snapshot(now)
    }

    /**
     * Members whose bytes differ from the last snapshot, recorded as the next snapshot so the following open
     * compares against it. A crashed mutation after its snapshot shows here too: the tree, not the model, says
     * what moved (FX-23).
     */
    private fun drift(shadow: ShadowRef, dirty: DirtyState): List<Touched> {
        val last = shadow.records().last()
        val before = checkNotNull(shadow.manifest(last.turn)).entries.associateBy { it.path }
        val now = dirty.capture(last.turn + 1)
        val after = now.entries.associateBy { it.path }
        val moved = (before.keys + after.keys).filter { before[it] != after[it] }.sortedWith(Stamper.PATH_ORDER)
        if (moved.isEmpty()) return emptyList()
        shadow.snapshot(now)
        return moved.map { path ->
            val was = before[path]?.digest?.let(::FileVersion)
            val current = after[path]
            val kind = if (current?.kind == SnapshotEntryKind.Deleted) TouchKind.Deleted else TouchKind.Modified
            Touched(path, kind, from = was, to = current?.digest?.let(::FileVersion), note = "external")
        }
    }

    public companion object {
        /** The one workspace of an S0 campaign; worktrees arrive with S3. */
        @JvmField
        public val WORKSPACE: WorkspaceId = WorkspaceId("main")
    }
}
