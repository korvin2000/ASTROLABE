package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.Project
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Prime
import io.astrolabe.atlas.RulesSnapshot
import io.astrolabe.atlas.Sniffed
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RulesTrust
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Cell
import io.astrolabe.cell.CellContext
import io.astrolabe.cell.CellEvidence
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellModel
import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.route.Routed
import io.astrolabe.route.Router
import io.astrolabe.route.RoutingBudget
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.RoutingOutcome
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.CellTools
import io.astrolabe.cell.CellWorkspace
import io.astrolabe.cell.DispatchAuthority
import io.astrolabe.cell.DispatchRefusal
import io.astrolabe.cell.Gates
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.Role
import io.astrolabe.cell.Roles
import io.astrolabe.cell.SqliteCheckpoints
import io.astrolabe.cell.TouchKind
import io.astrolabe.cell.Touched
import io.astrolabe.context.BoundaryReason
import io.astrolabe.context.CarriedReceipt
import io.astrolabe.context.Carry
import io.astrolabe.context.CarryForward
import io.astrolabe.context.CompileInputs
import io.astrolabe.context.Compiled
import io.astrolabe.context.Compiler
import io.astrolabe.context.Fingerprint
import io.astrolabe.context.Manifest
import io.astrolabe.context.Precompile
import io.astrolabe.context.PrecompileTrigger
import io.astrolabe.context.RebuildReason
import io.astrolabe.context.Seeds
import io.astrolabe.context.SqliteManifests
import io.astrolabe.context.StatusBoundary
import io.astrolabe.context.StatusNotes
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.telemetry.PrecompileMetrics
import io.astrolabe.telemetry.PrecompileOutcome
import io.astrolabe.telemetry.PrecompileSample
import io.astrolabe.evidence.Aliases
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.Outcome
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
import io.astrolabe.evidence.JournalScope
import io.astrolabe.kb.CalibrationSeries
import io.astrolabe.kb.CalibrationStats
import io.astrolabe.kb.Derived
import io.astrolabe.kb.Extraction
import io.astrolabe.kb.ExtractionTrace
import io.astrolabe.kb.Extractor
import io.astrolabe.kb.Injection
import io.astrolabe.kb.InjectionExclusion
import io.astrolabe.kb.InjectionInputs
import io.astrolabe.kb.InjectionResult
import io.astrolabe.kb.Kb
import io.astrolabe.kb.KbIndex
import io.astrolabe.kb.KbInjection
import io.astrolabe.kb.KbNegatives
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.KnowledgeUse
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Notes
import io.astrolabe.kb.Queue
import io.astrolabe.kb.StoreKb
import io.astrolabe.kb.Usage
import io.astrolabe.kb.UsageEvent
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Git
import io.astrolabe.os.LocalOs
import io.astrolabe.os.ProcStatus
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
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.TurnCheckpoint
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.edit.CliSyntax
import io.astrolabe.tool.edit.Edit
import io.astrolabe.tool.edit.TransformExecution
import io.astrolabe.tool.edit.SyntaxCheck
import io.astrolabe.tool.kb.KbTool
import io.astrolabe.tool.look.Look
import io.astrolabe.tool.run.Run
import io.astrolabe.tool.run.SqliteHandles
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.tool.state.StateTool
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.tool.verify.Verify
import io.astrolabe.verify.Baseline
import io.astrolabe.verify.BehaviourSnapshots
import io.astrolabe.verify.CampaignReview
import io.astrolabe.verify.CampaignReviewOutcome
import io.astrolabe.verify.CampaignReviewRecord
import io.astrolabe.verify.Finding
import kotlinx.serialization.json.Json
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Currency
import io.astrolabe.verify.RefactorMode
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
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import io.astrolabe.delegate.CellChildRunner
import io.astrolabe.delegate.CellReviewJudge
import io.astrolabe.delegate.ChildBrief
import io.astrolabe.delegate.ChildBudget
import io.astrolabe.delegate.ChildCell
import io.astrolabe.delegate.DelegationLimits
import io.astrolabe.delegate.Delegator
import io.astrolabe.delegate.EvidencePacket
import io.astrolabe.delegate.Excerpt
import io.astrolabe.delegate.IncrementReview
import io.astrolabe.delegate.IncrementReviewInput
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.delegate.ReviewCellAuthority
import io.astrolabe.delegate.ReviewCriterion
import io.astrolabe.delegate.ReviewOutcome
import io.astrolabe.delegate.ReviewReceipt
import io.astrolabe.delegate.ReviewTriggers
import io.astrolabe.delegate.Probe
import io.astrolabe.delegate.TaskPackets
import io.astrolabe.delegate.WorthTest
import io.astrolabe.route.FunctionTable
import io.astrolabe.verify.ReviewScope
import io.astrolabe.id.ExecutionGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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
    /** Background handles as reattached at open (§13.4): `handle-1 running|exited|lost`; a handle is polled, never relaunched. */
    val handles: List<String> = emptyList(),
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
    /** The logged pre-scan behind [prescan]: its candidate inputs, blast, coverage and unresolved dependencies (P3.2.6). */
    public val impactPrescan: ImpactPrescan,
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
    /** The notes as of open, the `Frozen` arm of `Flags.kbInjection` (§19.5 ablation). */
    public val frozenNotes: List<Note> = emptyList(),
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
    /** Boundary pre-compilation telemetry (§6.6, §19.5): hits, misses and boundary latency, recorded with the flag on. */
    public val precompiles: PrecompileMetrics = PrecompileMetrics(),
    /** The §11.2 router (P4.5.1) asked once per cell; its calibration log holds the `(function, tier, effort, outcome)` quadruples. */
    public val router: Router = Router(),
    /** The post-cell extractor's model step (§12.1, P4.2.1), run at finish from the archived traces; `NONE` leaves the harness-derived candidates and the CAL delta. */
    private val extraction: Extraction = Extraction.NONE,
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
        // P4.1: the store-backed base; with no notes it behaves as the empty base (every search complete and empty).
        val negatives = KbNegatives(journal, idGen, clock)
        val kb = StoreKb(store, request.work, registry::version) { negatives.retrievalMiss(ids, null, it) }
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
        // §3.7 impact_prescan (D-40): incomplete discovery over the request's candidate paths; it feeds both shape selections.
        val impactPrescan = ImpactPrescan.of(atlas, WORKSPACE, ImpactPrescan.inputs(atlas, WORKSPACE, request.text), kb.contractAnchors())
        val derived = contracts.deriveS0(request.work, request.attempt, request.text, atlas, effective, policy.tokens, protected, policy.cost)
        val stored = contracts.current(request.work)
        check(stored == null || stored.attemptId == request.attempt) { "work ${request.work.value} is attempt ${stored?.attemptId?.value}; a new attempt is P2" }
        // §3.5: a new contract carries the shape its campaign runs in; the tool masks derive from it.
        val contract = stored ?: contracts.open(derived.contract.let { d ->
            val initial = ShapeSelector.select(d, impactPrescan.prescan, effective.defaults.shapePolicy, policy.resumeExpected)
            if ((initial as? ShapeDecision.Selected)?.shape == Shape.S1) d.copy(shape = Shape.S1) else d
        })
        val commands = derived.primary?.let(RunnerCommands::of) ?: RunnerCommands()
        val checks = Checks.seed(contract, commands, qualityGates = effective.qualityGates)
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
        // §13.4: live background handles resolve to running, exited or lost by identity, never by pid alone.
        val handles = SqliteHandles(store, clock).open().filter { it.ids.work == request.work }.map { handle ->
            val status = when (runCatching { os.reattach(handle.proc).status }.getOrDefault(ProcStatus.Lost)) {
                ProcStatus.Running -> "running"
                is ProcStatus.Exited -> "exited"
                ProcStatus.DeadlineExceeded -> "deadline_exceeded"
                ProcStatus.Cancelled -> "cancelled"
                ProcStatus.Lost -> "lost"
            }
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(handle.handleId, handle.actionId), text = "open: handle ${handle.handleId} (${handle.argv.joinToString(" ")}) $status · polled, never relaunched", at = clock.instant()))
            "${handle.handleId} $status"
        }
        val reconciliation = Reconciliation(unknown.map { it.intentId }, external, stamp, handles)
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
        val prescan = impactPrescan.prescan
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = impactPrescan.blast, text = "open: impact ${impactPrescan.log}", at = clock.instant()))
        val selected = ShapeSelector.select(contract, prescan, effective.defaults.shapePolicy, policy.resumeExpected)
        events?.emit(AgentEvent.Campaign.Opened(ids, contract.requests.last().id))
        val inputs = when (selected) {
            is ShapeDecision.Selected -> selected.inputs
            is ShapeDecision.Unavailable -> selected.inputs
        }
        val shapeLog = "contract:v${contract.version} ${inputs?.log.orEmpty()} · ${impactPrescan.log}"
        events?.emit(AgentEvent.Campaign.ShapeSelected(ids, (selected as? ShapeDecision.Selected)?.shape?.name ?: "blocked", shapeLog))
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "open: shape ${(selected as? ShapeDecision.Selected)?.shape?.name ?: "blocked"} · $shapeLog", at = clock.instant()))
        // S0 and S1 run here (P2.2.2); S2/S3 need review or parallel paths this build lacks: an honest block.
        val shape = when {
            selected is ShapeDecision.Selected && selected.shape != Shape.S0 && selected.shape != Shape.S1 ->
                ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.shape} selected (${selected.inputs?.log}); this build runs S0 and S1 (S2 review paths P3.5.2/P4.4)", selected.inputs)
            selected is ShapeDecision.Unavailable -> ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.reason}", selected.inputs)
            else -> selected
        }
        if (shape is ShapeDecision.Unavailable && state?.phase == CampaignPhase.Running && state.running == null) {
            state = Lifecycle.apply(state, contract, Transition.Stopped(CampaignOutcome.BlockedExternal, shape.reason)).also(campaigns::save)
        }

        return OpenedCampaign(
            request, ids, store, os, workspace, registry, stamper, dirty, shadow, s0, atlas, derived.sniffed, commands,
            contracts, checks, rules, prime, kb, journal, intents, campaigns, reconciliation, prescan, impactPrescan, shape, state, refusal, owned,
            frozen, lease, leases, frozenNotes = Notes(store).all(),
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
        behaviourSnapshot(campaign)
        val result = spans?.span(Phase.Plan, campaign.ids) { span -> runS0(campaign, model, authority, syntax, span) }
            ?: runS0(campaign, model, authority, syntax, null)
        return finish(campaign, result)
    }

    /**
     * §8.9 item 1 (P3.5.1): in refactor mode the behaviour snapshot — baseline receipts over the affected suites and
     * the characterization outputs as blobs at `s0` — is captured once per attempt before the first cell runs.
     */
    private suspend fun behaviourSnapshot(c: OpenedCampaign) {
        if (c.stop != null || !RefactorMode.isActive(c.contract)) return
        val redaction = Redaction(c.attempt.config.redaction)
        val receipts = SqliteReceipts(c.store, clock)
        val baseline = Baseline(c.shadow, c.store.layout, TrustedLocalRunner(c.os), c.os, receipts, SqliteAliases(c.store, clock), c.store.blobs, redaction, HeuristicEstimator(), idGen, c.ids, clock, EnvFingerprint.compute(env))
        val snapshots = BehaviourSnapshots(baseline, c.shadow, c.store.layout, c.store.blobs, c.store, c.journal, c.ids, idGen, clock)
        if (snapshots.latest() != null) return
        snapshots.capture(c.contract, c.checks, c.s0.stampId)
    }

    /**
     * `campaign()` (§3.7, P2.2.2): an S0 selection takes [runS0]; an S1 selection runs the plan cell once — its
     * proposal is admitted by [PlanIntake] and installed by [Transition.Planned] — then loops: the next ready
     * increment is compiled with the carry-forward and seeds of its previous cell, run, verified against current
     * receipts and committed; a partial continues the same increment, a stop is the honest outcome, and an empty
     * frontier with unverified requirements never ends `completed`. [maxCells] bounds the campaign's cells.
     */
    @JvmOverloads
    public suspend fun run(
        campaign: OpenedCampaign,
        model: CellModel,
        authority: Authority = AutonomousAuthority(),
        syntax: SyntaxCheck = CliSyntax(campaign.os, campaign.workspace.root, campaign.store.layout.root.resolve("logs"), python = null, node = null),
        maxCells: Int = DEFAULT_MAX_CELLS,
    ): S0Run {
        require(maxCells >= 1) { "maxCells must be ≥ 1" }
        if ((campaign.shape as? ShapeDecision.Selected)?.shape != Shape.S1) return runS0(campaign, model, authority, syntax)
        behaviourSnapshot(campaign)
        val packets = ArrayList<ResultPacket>()
        val result = spans?.span(Phase.Plan, campaign.ids) { span -> runS1(campaign, model, authority, syntax, span, maxCells, packets) }
            ?: runS1(campaign, model, authority, syntax, null, maxCells, packets)
        return finish(campaign, result, packets)
    }

    private suspend fun runS1(c: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, maxCells: Int, packets: MutableList<ResultPacket>): S0Run {
        c.stop?.let { return S0Run(c.state, null, null, null) }
        c.refusal()?.let { return S0Run(c.advance(Transition.Stopped(stopOutcome(c), "nothing dispatched: $it")), null, null, null) }
        val opened = checkNotNull(c.state)
        check(opened.phase == CampaignPhase.Running && opened.running == null) { "run needs a reconciled campaign with no running cell; it is ${opened.phase}" }
        // §4.2: the first cell of S1 is the plan cell; the placeholder graph is replaced once, before any dispatch.
        if (opened.graph.increments.none { it.cells.isNotEmpty() || it.status != IncrementStatus.Pending }) {
            plan(c, model, authority, syntax, span, packets)?.let { stop ->
                val outcome = if (c.refusal() != null) stopOutcome(c) else stop.outcome
                return S0Run(c.advance(Transition.Stopped(outcome, stop.reason)), null, null, null)
            }
        }
        var last = S0Run(c.state, null, null, null)
        var cells = 0
        // §6.6 `[O]`: boundary pre-compilation only under the frozen `precompile` flag; off, nothing below runs.
        val precompile = if (c.attempt.config.flags.precompile) Precompile(c.journal, idGen, clock) else null
        var closed: Pair<ContextId, Long>? = null
        // A continuation of a red increment never drops below the tier its failing cell ran at (§11.1).
        val tiers = HashMap<String, Tier>()
        while (true) {
            c.refusal()?.let { return last.copy(state = c.advance(Transition.Stopped(stopOutcome(c), "dispatch refused: $it"))) }
            val state = checkNotNull(c.state)
            val contract = c.contract
            val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c))
            val unverified = state.ledger.unfinished()
            if (unverified.isEmpty()) return last.copy(state = stopOrFinish(c, "requirements remain unverified", scheduler, campaign = true, authority = campaignJudge(c, authority, model, syntax, span)))
            val ready = state.graph.readyFrontier(contract, 1).firstOrNull()
            if (ready == null) {
                // FX-42: verified work is never re-executed; its regression evidence is refreshed from current receipts.
                refreshRegressions(c, scheduler)
                if (checkNotNull(c.state).ledger.unfinished().size < unverified.size) continue
                return last.copy(state = stopOrFinish(c, "no ready increment with ${unverified.size} requirements unverified: an empty frontier never means completed"))
            }
            if (cells >= maxCells) {
                return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BudgetExhausted, "the campaign's $maxCells cells are spent with ${unverified.size} requirements unverified")))
            }
            cells += 1
            // §6.2: a continuation starts from the previous cell's validated register, seeds and packet — never its transcript.
            val carry = ready.cells.lastOrNull()?.let { previous -> carryFrom(c, previous, packets.lastOrNull { it.ids.context == previous }) }
            val seeds = carry?.let { Seeds.render(it.seeds, c.registry::read) }
            val resume = resumeNote(c, ready, carry)
            val knowledge = knowledge(c, ready, Roles.implementing, model, touched = carry?.seeds.orEmpty().map { it.path }.toSet())
            val inputs = CompileInputs(carry = carry, seeds = seeds, currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex)
            val pinned = listOfNotNull(resume)
            val compiler = Compiler(model.estimator, c.attempt.config)
            // §6.6: a pre-compiled [K] is served for cell_end(next_increment) only, on a full-fingerprint and coverage match.
            val take = precompile?.let { p ->
                if (ready.cells.isNotEmpty()) {
                    p.discard("${ready.id} continues; pre-compilation applies to the next increment only")
                    null
                } else {
                    p.take(fingerprint(c, contract, ready, c.stamper.report().candidateId, model, inputs, null, pinned)) { compiled ->
                        (compiled as? Compiled.Ready)?.let { compiler.coverage(contract, ready, it.k, c.prime, pinned, inputs) }.orEmpty()
                    }
                }
            }
            val routing = route(c, if (ready.cells.isEmpty()) RoutingFunction.Implementing else RoutingFunction.Continuation, ready, model, tiers[ready.id], take?.compiled ?: compiler.compile(
                ready, contract, model.profile, Roles.implementing, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs,
            )) { profile -> compiler.compile(ready, contract, profile, Roles.implementing, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs) }
            val compiled = routing.compiled
            val cellModel = routing.model
            closed?.let { (cell, at) -> precompiles.record(PrecompileSample(cell, ready.id, take?.outcome ?: PrecompileOutcome.None, take?.reason, (precompiles.now() - at).coerceAtLeast(0))) }
            closed = null
            // §6.5: why this context is built — a new increment after a closed one, a partial's continuation, or a resume.
            val boundaryReason = when (ready.cells.lastOrNull()?.let { previous -> state.cells.firstOrNull { it.cell == previous }?.status }) {
                null, CellStatus.Completed -> BoundaryReason.Done
                CellStatus.Partial, CellStatus.Blocked -> BoundaryReason.Partial
                CellStatus.Running, CellStatus.Failed, CellStatus.Cancelled -> BoundaryReason.Resume
            }
            when (compiled) {
                is Compiled.Ready -> Unit
                is Compiled.NeedsRescoping -> return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "NEEDS_RESCOPING_OR_LARGER_PROFILE for ${ready.id}: ${compiled.reason} — ask the plan role for an increment_split")), compiled = compiled)
                is Compiled.NeedsEvidence -> return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "NEEDS_MORE_EVIDENCE for ${ready.id}: ${compiled.missing}")), compiled = compiled)
            }
            // FX-32: an unaffordable tier is refused, never clamped; the campaign stops on the router's options.
            routing.refused?.let { return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BudgetExhausted, it.reason)), compiled = compiled) }
            routing.selected?.let { tiers[ready.id] = it.tier }
            val cellId = ContextId(idGen.next("cell"))
            events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, ready.id))
            val dispatched = c.advance(Transition.Dispatched(ready.id, cellId))
            val increment = dispatched.graph.increments.first { it.id == ready.id }
            val register = carry?.register?.copy(cell = cellId, increment = increment.id, incrementTitle = increment.title)
            // The pre-compile job lives in this scope: joined at cell close, cancelled with the cell (§6.6).
            val run = coroutineScope {
                val trigger = precompile?.let { p -> trigger(c, this, p, cellId, increment, cellModel) }
                runCell(c, cellId, increment, Roles.implementing, cellModel, authority, syntax, compiled, span, dispatched.ledger, register, seeds?.shown.orEmpty(), pinned = pinned, boundary = boundaryReason, inputs = inputs, precompile = trigger).also { run ->
                    if (run.exit !is CellExit.Completed) precompile?.discard("cell ${cellId.value} ended ${run.exit?.let { it::class.simpleName!!.lowercase() } ?: "cancelled"}: never a continuation of a red increment")
                }
            }
            if (precompile != null) closed = cellId to precompiles.now()
            val exit = run.exit
            routing.selected?.let { router.record(it, outcomeOf(exit)) }
            if (exit == null) {
                snapshot(c)
                c.advance(Transition.Interrupted(checkNotNull(run.checkpoints.latest(cellId)) { "a cancelled cell settles its checkpoint" }))
                return S0Run(c.advance(Transition.Stopped(CampaignOutcome.Cancelled, checkNotNull(c.cancellation.reason))), null, null, compiled)
            }
            packets += exit.packet
            snapshot(c)
            c.advance(Transition.Returned(exit))
            refreshPrescan(c, run.ids, exit)?.let { return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it)), exit, null, compiled) }
            boundary(c, cellId, RebuildReason.CellEnd(if (exit is CellExit.Completed) RebuildReason.CellEnd.Next.NextIncrement else RebuildReason.CellEnd.Next.Continuation))
            val stampNow = c.stamper.report().candidateId
            // §8.7/§8.8: in S2+ a required increment review must approve before the increment closes; none owed ⇒ null.
            val review = if (exit is CellExit.Completed && c.contract.shape >= Shape.S2) incrementReview(c, increment, exit, routing.selected?.tier, compiled, cellModel, authority, syntax, span) else null
            if (review is ReviewOutcome.Unavailable) return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "required review of ${increment.id} unavailable: ${review.reason}")), exit, null, compiled)
            val completion = if (exit is CellExit.Completed) {
                val returned = checkNotNull(c.state).graph.increments.first { it.id == increment.id }
                if (review is ReviewOutcome.Declined) CompletionResult.Refused(listOf("required review: ${review.reason}"), 1, recoveryDirected = false)
                else Verifier().accept(exit.packet.proposal(), c.contract, returned, exit.register, checkNotNull(c.state).ledger, stampNow, currencies(c, run.scheduler, stampNow), reviews = reviewItems(c.contract, returned, review))
            } else {
                null
            }
            last = S0Run(c.state, exit, completion, compiled)
            when (val disposition = Lifecycle.disposition(exit, completion)) {
                is Disposition.Close -> {
                    c.refusal()?.let { reason ->
                        c.journal.append(JournalEvent(idGen.next("ev"), run.ids, exit.turns, JournalKind.Reconcile, refs = disposition.accepted.receiptIds, text = "late completion of ${increment.id} archived; publication refused: $reason", at = clock.instant()))
                        return last.copy(state = c.advance(Transition.Stopped(stopOutcome(c), "late completion archived; publication refused: $reason")))
                    }
                    c.advance(Transition.Committed(disposition.accepted, stampNow))
                    events?.emit(AgentEvent.Campaign.IncrementClosed(c.ids, increment.id, "verified"))
                    // §7.3 cadence: the full suite every K verified increments; a red result is a regression on record.
                    val verified = checkNotNull(c.state).graph.increments.count { it.status == IncrementStatus.Verified }
                    if (verified % c.attempt.config.defaults.fullSuiteCadence == 0 && checkNotNull(c.state).ledger.unfinished().isNotEmpty()) fullSuite(c, "cadence after $verified verified increments")
                }
                // S1: a partial continues the same increment from its carry-forward; the cell cap bounds it (D-70).
                is Disposition.Continue -> Unit
                is Disposition.Stop -> return last.copy(state = c.advance(Transition.Stopped(disposition.outcome, disposition.reason)))
            }
            last = last.copy(state = c.state)
        }
    }

    /**
     * §3.7/I-23 pre-scan refresh: once a cell's touched paths are known the pre-scan reruns over them; a contract touch
     * it now finds stops an S0/S1 campaign before the commit it no longer allows (S2 with an ADR in the main line).
     */
    private fun refreshPrescan(c: OpenedCampaign, ids: Identities, exit: CellExit): String? {
        val touched = exit.checkpoint.touched
        if (touched.isEmpty()) return null
        val refreshed = ImpactPrescan.of(c.atlas.refresh(touched), WORKSPACE, c.impactPrescan.inputs.copy(touched = touched.sorted()), c.kb.contractAnchors())
        c.journal.append(JournalEvent(idGen.next("ev"), ids, exit.turns, JournalKind.Boundary, refs = refreshed.contractsTouched, text = "impact pre-scan refreshed: ${refreshed.log}", at = clock.instant()))
        if (refreshed.prescan.contractTouch != true || c.contract.shape !in setOf(Shape.S0, Shape.S1)) return null
        return "impact pre-scan refresh: contract ${refreshed.contractsTouched.joinToString(", ")} touched — S2 with an ADR in the main line is required before this lands (I-23)"
    }

    /** The full compile-input fingerprint (§6.6, F06) of an implementing compile of [increment] at [stamp]. */
    private fun fingerprint(c: OpenedCampaign, contract: Contract, increment: Increment, stamp: CandidateId, model: CellModel, inputs: CompileInputs, registerVersion: Int?, pinned: List<String>): Fingerprint =
        Fingerprint.of(stamp, contract, increment, Roles.implementing, model.profile, c.attempt, c.prime, model.estimator, model.maxOutputTokens, inputs, registerVersion, pinned)

    /**
     * §6.6: at a completion proposal of [increment]'s cell that leaves only slow or expensive checks to run, pre-build
     * the `[K]` of the increment the frontier would offer next, locally and in [scope]; a fast check still to run, or
     * no next increment, skips it. Only `cell_end(next_increment)` can consume it (never a continuation).
     */
    private fun trigger(c: OpenedCampaign, scope: CoroutineScope, precompile: Precompile, cell: ContextId, increment: Increment, model: CellModel): PrecompileTrigger =
        PrecompileTrigger { stamp, remaining ->
            val ids = c.ids.copy(context = cell)
            if (!precompile.eligible(remaining)) {
                val fast = remaining.filter { it.costClass != CostClass.Slow && it.costClass != CostClass.Expensive }
                c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "precompile skipped: ${fast.joinToString(", ") { "${it.id} ${it.costClass.name.lowercase()}" }} still to run at the boundary", at = clock.instant()))
                return@PrecompileTrigger
            }
            val contract = c.contract
            val next = checkNotNull(c.state).graph.peekNext(contract, increment.id)
            if (next == null) {
                c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "precompile skipped: no increment ready after ${increment.id}", at = clock.instant()))
                return@PrecompileTrigger
            }
            // The next increment has no previous cell: no carry-forward, no seeds, no resume note (§6.2).
            val knowledge = knowledge(c, next, Roles.implementing, model)
            val inputs = CompileInputs(currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex)
            val compiler = Compiler(model.estimator, c.attempt.config)
            precompile.start(scope, ids, fingerprint(c, contract, next, stamp, model, inputs, null, emptyList()), next.id, remaining) {
                compiler.compile(next, contract, model.profile, Roles.implementing, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs)
            }
        }

    /**
     * Regression obligations (§4.2, FX-42): a verified increment whose evidence no longer holds at the current stamp is
     * re-accepted from the receipts current now — never re-executed. Its green `run:` acceptances are regression
     * obligations the harness re-runs at campaign end (§4.1); one still without current receipts stays unfinished.
     */
    private suspend fun refreshRegressions(c: OpenedCampaign, scheduler: Scheduler) {
        reaccept(c, scheduler)
        val state = checkNotNull(c.state)
        val stale = state.ledger.unfinished().toSet()
        val runs = c.contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.id }.toSet()
        val obligations = state.graph.increments.filter { it.status == IncrementStatus.Verified && it.requirementIds.any { r -> r in stale } }
            .flatMap { it.accept }.filter { it in runs }.distinct()
        if (obligations.isEmpty()) return
        val ids = c.ids.copy(context = ContextId(idGen.next("finish")))
        val outcome = harnessVerify(c, ids, "regression", """{"what":"acceptance","ids":[${obligations.joinToString(",") { "\"$it\"" }}]}""")
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "regression obligations re-run (campaign end): ${obligations.joinToString(", ")} · ${outcome.body.lineSequence().joinToString(" ")}", at = clock.instant()))
        reaccept(c, scheduler)
    }

    private fun reaccept(c: OpenedCampaign, scheduler: Scheduler) {
        val report = c.stamper.report()
        val state = checkNotNull(c.state)
        val stale = state.ledger.unfinished().toSet()
        for (increment in state.graph.increments.filter { it.status == IncrementStatus.Verified && it.requirementIds.any { r -> r in stale } }) {
            val cell = increment.cells.lastOrNull() ?: continue
            val proposal = CompletionProposal(increment.id, PacketStatus.Done.wire, c.contract.version, report.candidateId, report.candidateId, null, report.env.envId)
            val result = Verifier().accept(proposal, c.contract, increment, Register.empty(cell, increment.id, increment.title), checkNotNull(c.state).ledger, report.candidateId, currencies(c, scheduler, report.candidateId))
            if (result is CompletionResult.Accepted) c.advance(Transition.Committed(result, report.candidateId))
        }
    }

    /**
     * The plan cell (§3.4, P2.1.2): `null` once a plan is admitted and installed, else the stop — a budget partial is
     * `budget_exhausted` (FX-49 S1), every other failure to plan blocks.
     */
    private suspend fun plan(c: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, packets: MutableList<ResultPacket>): Transition.Stopped? {
        fun blocked(reason: String) = Transition.Stopped(CampaignOutcome.BlockedExternal, reason)
        val contract = c.contract
        val planning = Increment(PLAN, contract.requirements.map { it.id }, contract.acceptance.map { it.id }, emptyList(), 0, title = "plan ${c.ids.work.value}")
        val knowledge = knowledge(c, planning, Roles.plan, model)
        val planInputs = CompileInputs(notes = knowledge.notes, contractsIndex = knowledge.contractsIndex)
        val compiler = Compiler(model.estimator, c.attempt.config)
        val routing = route(c, RoutingFunction.Plan, planning, model, null, compiler.compile(planning, contract, model.profile, Roles.plan, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = planInputs)) { profile ->
            compiler.compile(planning, contract, profile, Roles.plan, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = planInputs)
        }
        val compiled = routing.compiled
        if (compiled !is Compiled.Ready) return blocked("the plan cell cannot be compiled: $compiled")
        routing.refused?.let { return Transition.Stopped(CampaignOutcome.BudgetExhausted, it.reason) }
        val cellId = ContextId(idGen.next("cell"))
        val proposals = SqlitePlanProposals(c.store, idGen, clock)
        val intake = CampaignProposals(proposals, SqliteSplitRequests(c.store, idGen, clock), { c.contracts.current(c.ids.work) }, { null }, { c.kb.contractAnchors() })
        val completion = io.astrolabe.cell.RoleCompletion.forRole(Roles.plan, mapOf(io.astrolabe.cell.PacketKind.PlanArtifacts to PlanPacketValidator.completion({ c.contract }, proposals, conAnchors = { c.kb.contractAnchors() })))
        val run = runCell(c, cellId, planning, Roles.plan, routing.model, authority, syntax, compiled, span, null, proposals = intake, completion = completion, inputs = planInputs)
        routing.selected?.let { router.record(it, outcomeOf(run.exit)) }
        val exit = run.exit ?: return Transition.Stopped(CampaignOutcome.Cancelled, "cancelled while planning")
        packets += exit.packet
        if (exit !is CellExit.Completed) {
            val outcome = when (val d = Lifecycle.disposition(exit, null)) {
                is Disposition.Stop -> d.outcome
                is Disposition.Continue -> d.fallback.takeIf { it == CampaignOutcome.BudgetExhausted } ?: CampaignOutcome.BlockedExternal
                is Disposition.Close -> CampaignOutcome.BlockedExternal
            }
            return Transition.Stopped(outcome, "the plan cell ended ${exit.packet.status.wire}: ${exit.packet.reason}")
        }
        val stored = proposals.latest(c.ids.work, cellId) ?: return blocked("the plan cell proposed no plan")
        return when (val admission = PlanIntake(c.contracts).admit(c.ids.work, stored, authority)) {
            is PlanAdmission.Admitted -> {
                c.advance(Transition.Planned(admission.graph))
                // §8.9 item 4: without CON notes to validate against, a missing CON reference is a recorded planning gap, not a refusal.
                if (c.kb.contractAnchors().isEmpty()) {
                    for (gap in PlanPacketValidator.planningGaps(c.contract, stored.packet)) {
                        c.journal.append(JournalEvent(idGen.next("ev"), c.ids.copy(context = cellId), null, JournalKind.Boundary, refs = listOf(stored.id), text = gap, at = clock.instant()))
                    }
                }
                boundary(c, cellId, RebuildReason.RoleSwitch(Roles.implementing))
                null
            }
            is PlanAdmission.Refused -> blocked("plan ${stored.id} refused: ${admission.gaps.joinToString("; ")}")
        }
    }

    /**
     * A §5.8 boundary between S1 cells (P2.2.3): the next cell starts a fresh projection — empty tail (m = 0), its role's
     * mask and knowledge view, a fresh provider lineage — so the boundary records the rebuild and writes the STATUS
     * revision (role switch, cell end) with the checks' last receipts and the open intents.
     */
    private fun boundary(c: OpenedCampaign, cell: ContextId, reason: RebuildReason) {
        val ids = c.ids.copy(context = cell)
        val verification = c.checks.all().mapNotNull { check -> check.last?.let { CarriedReceipt(check.id, it.receiptId, it.applicability.name.lowercase()) } }
        val status = StatusNotes(KbWriter(c.store, HeuristicEstimator(), clock), Notes(c.store), c.store.layout.kb)
        status.checkpoint(ids, if (reason is RebuildReason.RoleSwitch) StatusBoundary.RoleSwitch else StatusBoundary.CellEnd, emptyList(), verification, c.intents.open().map { it.intentId })
        val revision = Notes(c.store).revisions(status.id(c.ids.work)).size
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "rebuilt: ${reason.wire} · fresh lineage, empty tail · STATUS revision $revision", at = clock.instant()))
    }

    /**
     * The one-line resume note of §13.4 for a cell that continues [increment] after its previous cell was lost or
     * interrupted: what open reconciled — the tree stamp, external moves, unknown outcomes, background handles — and
     * that KNOWN is the seeds only; the register is never trusted over the workspace.
     */
    private fun resumeNote(c: OpenedCampaign, increment: Increment, carry: Carry?): String? {
        val previous = increment.cells.lastOrNull() ?: return null
        val status = checkNotNull(c.state).cells.firstOrNull { it.cell == previous }?.status ?: return null
        if (status != CellStatus.Failed && status != CellStatus.Cancelled) return null
        val r = c.reconciliation
        return "resumed: cell ${previous.value} ended ${status.name.lowercase()}; tree reconciled @${r.stamp.hash8}" +
            " · external ${r.external.size}" + (if (r.unknownOutcomes.isEmpty()) "" else " · unknown outcomes ${r.unknownOutcomes.joinToString(", ")} (reconcile before any retry)") +
            (if (r.handles.isEmpty()) "" else " · handles ${r.handles.joinToString(", ")} (poll, never relaunch)") +
            " · " + (carry?.known ?: "KNOWN: seeds only (0) · NOT SEEN: everything else")
    }

    /** The carry-forward of [cell] (§6.2): its latest register, its end export and its packet, re-validated now. */
    private fun carryFrom(c: OpenedCampaign, cell: ContextId, packet: ResultPacket?): Carry? {
        val register = withReviewOpenItems(c, SqliteRegisterVersions(c.store, clock).latest(cell) ?: return null)
        val aliases = SqliteAliases(c.store, clock)
        return CarryForward.carry(
            register, Seeds.cellEnd(SqliteCheckpoints(c.store, clock), cell), packet, { c.registry.version(it) },
            { id -> Aliases.parse(id)?.let { aliases.resolve(c.ids.work, it) } != null }, emptyList(), emptyList(),
        )
    }

    /** Every ended campaign leaves a finish receipt, stored and exported, and says so on the bus (§5.9). */
    private fun finish(c: OpenedCampaign, result: S0Run, packets: List<ResultPacket> = listOfNotNull(result.exit?.packet)): S0Run {
        val outcome = result.state?.outcome ?: return result
        val receipts = SqliteReceipts(c.store, clock)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, receipts, SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c))
        val receipt = FinishReceipts.build(c, packets, currencies(c, scheduler, c.stamper.report().candidateId), receipts::get)
        val (ref, _) = FinishReceipts.export(c, receipt)
        events?.emit(AgentEvent.Campaign.Finished(c.ids, outcome.wire, ref))
        extract(c, packets)
        return result.copy(finish = receipt)
    }

    /**
     * §12.1 post-cell extraction at finish (P4.2.1): each archived packet with its cell's journal goes to the
     * extractor under the originating cell's ids (its cost is charged there), then the §6.7 `CAL-<repo>` delta is
     * aggregated over every stored campaign. The extractor contains its own failures; the receipt is already exported.
     */
    private fun extract(c: OpenedCampaign, packets: List<ResultPacket>) {
        val extractor = Extractor(c.store, HeuristicEstimator(), idGen, clock, extraction, c.journal, events)
        val stamp = c.stamper.report().candidateId.digest.hex
        val findings = reviewFindings(c)
        for ((i, packet) in packets.withIndex()) {
            val trace = ExtractionTrace(packet, c.journal.events(JournalScope(c.ids.work, packet.ids.context)), packet.stamp?.digest?.hex ?: stamp)
            // §8.8 findings are the campaign's, derived once: with the last packet; recurring dead ends see the earlier registers.
            extractor.run(trace, packet.ids, if (i == packets.lastIndex) findings else emptyList(), packets.take(i).map { it.register })
        }
        val policy = Calibration.policy(c.attempt.config.defaults.shapePolicy)
        val series = CalibrationSeries(c.workspace.root.fileName?.toString() ?: "repo", c.attempt.harnessVersion, policy.version)
        extractor.calibrate({ CalibrationStats.aggregate(Calibration.observations(c.store, series), policy) }, series, c.ids)
    }

    /** The attempt's latest campaign review findings and its latest increment review's (§8.8), or none. */
    private fun reviewFindings(c: OpenedCampaign): List<Finding> = c.store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
        c.ids.work, c.ids.attempt, CampaignReview.KIND,
    ) { Json.decodeFromString(CampaignReviewRecord.serializer(), it.string("body")) }.firstOrNull()?.verdict?.findings.orEmpty() +
        ReviewCell.latest(c.store, c.ids)?.verdict?.findings.orEmpty()

    /** §8.8 (P4.2.2): findings at or above major not yet in [register] become its `Open` items, numbered after its last. */
    private fun withReviewOpenItems(c: OpenedCampaign, register: Register): Register {
        val findings = reviewFindings(c)
        if (findings.isEmpty()) return register
        val fresh = Derived.openItems(findings, (register.open.maxOfOrNull { it.n } ?: 0) + 1).filter { item -> register.open.none { it.text == item.text } }
        return if (fresh.isEmpty()) register else register.copy(open = register.open + fresh)
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
        // §13.4 rebuild(resume): a cell that continues a lost or interrupted one starts from its validated carry-forward.
        val carry = ready.cells.lastOrNull()?.let { previous -> carryFrom(c, previous, null) }
        val seeds = carry?.let { Seeds.render(it.seeds, c.registry::read) }
        val resume = resumeNote(c, ready, carry)
        val knowledge = knowledge(c, ready, role, model, touched = carry?.seeds.orEmpty().map { it.path }.toSet())
        val inputs = CompileInputs(carry = carry, seeds = seeds, currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex)
        val compiler = Compiler(model.estimator, config)
        val routing = route(c, if (ready.cells.isEmpty()) RoutingFunction.Implementing else RoutingFunction.Continuation, ready, model, null, compiler.compile(
            ready, contract, model.profile, role, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs,
        )) { profile -> compiler.compile(ready, contract, profile, role, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs) }
        val compiled = routing.compiled
        val cellModel = routing.model
        when (compiled) {
            is Compiled.Ready -> Unit
            is Compiled.NeedsRescoping -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "NEEDS_RESCOPING_OR_LARGER_PROFILE: ${compiled.reason}")), null, null, compiled)
            is Compiled.NeedsEvidence -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "NEEDS_MORE_EVIDENCE: acceptance without definition ${compiled.missing}")), null, null, compiled)
        }
        routing.refused?.let { return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BudgetExhausted, it.reason)), null, null, compiled) }

        val cellId = ContextId(idGen.next("cell"))
        events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, ready.id))
        val dispatched = c.advance(Transition.Dispatched(ready.id, cellId))
        val increment = dispatched.graph.increments.first { it.id == ready.id }
        val register = carry?.register?.copy(cell = cellId, increment = increment.id, incrementTitle = increment.title)
        val run = runCell(c, cellId, increment, Roles.implementing, cellModel, authority, syntax, compiled, span, dispatched.ledger, register, seeds?.shown.orEmpty(), pinned = listOfNotNull(resume), inputs = inputs)
        routing.selected?.let { router.record(it, outcomeOf(run.exit)) }
        val ids = run.ids
        val scheduler = run.scheduler
        val exit = run.exit
        if (exit == null) {
            snapshot(c)
            val checkpoint = checkNotNull(run.checkpoints.latest(cellId)) { "a cancelled cell settles its checkpoint" }
            c.advance(Transition.Interrupted(checkpoint))
            return S0Run(c.advance(Transition.Stopped(CampaignOutcome.Cancelled, checkNotNull(c.cancellation.reason))), null, null, compiled)
        }
        // The tree after the cell is the base the next open reconciles against: only moves after this are external.
        snapshot(c)
        c.advance(Transition.Returned(exit))
        refreshPrescan(c, ids, exit)?.let { return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it)), exit, null, compiled) }

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

    /** One cell's routing: the model to run it with, the selection to record, or the refusal that stops the campaign. */
    private class Routing(val model: CellModel, val compiled: Compiled, val selected: Routed.Selected?, val refused: Routed.Refused?)

    /**
     * The P2.2.2 routing hook (§11.2, P4.5.1): asked once per cell, never inside its loop. The candidates are the
     * attempt's configured profiles under its tier table; untiered, the supplied cell model serves every tier, so
     * a fake-profile campaign routes to the profile it was given (D-108). The risk floor reads the increment's (else
     * the contract's) declared risk and the open-time impact pre-scan; tokens are admitted by the cell itself (D-06),
     * so only a monetary budget caps affordability here (D-109). A routed profile other than the supplied one recompiles.
     */
    private fun route(c: OpenedCampaign, function: RoutingFunction, increment: Increment, model: CellModel, previousTier: Tier?, compiled: Compiled, recompile: (io.astrolabe.provider.Profile) -> Compiled): Routing {
        if (compiled !is Compiled.Ready) return Routing(model, compiled, null, null)
        val config = c.attempt.config
        val contract = c.contract
        val tiered = config.tierTable.profiles.isNotEmpty() && config.tierTable.profileIds.all { it in config.profiles }
        val table = if (tiered) config.tierTable else TierTable.single(model.profile.id)
        val candidates = if (tiered) config.profiles else mapOf(model.profile.id to model.profile)
        val arithmetic = compiled.selection.arithmetic
        val contextTokens = (arithmetic.totalTokens ?: arithmetic.knownFixedTokens + arithmetic.selectedTokens).toLong()
        val reserves = contract.budget.reserves
        val cost = contract.budget.cost
        val budget = RoutingBudget(remainingCost = cost, reservedCost = cost?.let { Money(it.currency, it.amount.multiply(java.math.BigDecimal.valueOf(reserves.verification + reserves.recoveryAndPersist)), it.unknown) })
        val policy = RoutingPolicy(table, candidates, budget, configuredEffort = model.effort)
        val packet = RoutingPacket(increment.risk ?: contract.risk, contextTokens, model.maxOutputTokens, previousTier = previousTier, featureClass = "${contract.shape.name.lowercase()}:${increment.expectedFiles}")
        val prescan = c.impactPrescan
        val impact = RiskFloorInput(prescan.contractsTouched.size, prescan.complete, prescan.prescan.fanIn, prescan.complete)
        return when (val routed = router.selectProfile(function, packet, impact, policy)) {
            is Routed.Deterministic -> Routing(model, compiled, null, null)
            is Routed.Refused -> Routing(model, compiled, null, routed)
            is Routed.Selected -> {
                if (routed.profile.id == model.profile.id && routed.effort == model.effort) return Routing(model, compiled, routed, null)
                val cellModel = CellModel(model.adapter, routed.profile, model.estimator, routed.effort, model.maxOutputTokens)
                Routing(cellModel, if (routed.profile.id == model.profile.id) compiled else recompile(routed.profile), routed, null)
            }
        }
    }

    /** The verified outcome of a cell for the calibration log: the harness's exit, never the model's claim. */
    private fun outcomeOf(exit: CellExit?): RoutingOutcome = when (exit) {
        is CellExit.Completed -> RoutingOutcome.Accepted
        is CellExit.Failed -> RoutingOutcome.VerifiedFailure
        is CellExit.Blocked, is CellExit.Partial, is CellExit.Cancelled, null -> RoutingOutcome.Unverified
    }

    /** A cell's outcome as [runCell] hands it back: `null` [exit] when a cancellation interrupted it. */
    private class CellRun(val exit: CellExit?, val ids: Identities, val scheduler: Scheduler, val checkpoints: SqliteCheckpoints)

    /**
     * Builds the tools and context of one cell over [increment] under [role] and runs it (§3.6): shared by the S0 path,
     * the S1 loop's increment cells and the plan cell. Every compiled context leaves a manifest; the cell's cost is
     * priced once for its span.
     */
    /** What one compile carries from the base (§6.3, P4.1.3): the ranked notes under the `kbInjection` arm and the contracts index. */
    private class Knowledge(val notes: List<Note>, val contractsIndex: String?, val log: String)

    private fun knowledge(c: OpenedCampaign, increment: Increment, role: Role, model: CellModel, touched: Set<String> = emptySet()): Knowledge {
        val arm = c.attempt.config.flags.kbInjection
        val all = if (arm == KbInjection.Frozen) c.frozenNotes else Notes(c.store).all()
        if (all.isEmpty()) return Knowledge(emptyList(), null, "no notes")
        val contracts = all.filter { it.kind == NoteKind.CON && it.status == NoteStatus.Admitted }
        val inputs = InjectionInputs(
            role, c.ids.work, increment.writeScope, touched, contractsInPlay = contracts.map { it.id }.toSet(),
            stamp = c.stamper.report().candidateId.digest.hex, usage = Usage(c.store, clock).all(), currentVersion = { c.registry.version(it) },
        )
        val ranked = Injection.select(all, inputs, model.estimator)
        // `Off` keeps F23 (CON in scope compiled in) and drops the ranked advice; `Frozen`/`Live` differ in the base they rank.
        val result = if (arm != KbInjection.Off) ranked else InjectionResult(
            ranked.selected.filter { it.mandatory },
            ranked.excluded + ranked.selected.filter { !it.mandatory }.map { InjectionExclusion(it.note.id, "ranked injection is off (kbInjection arm)") },
        )
        val index = if (contracts.isEmpty()) null else KbIndex.render(all, model.estimator).getValue("contracts.md")
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = result.notes.map { it.id }, text = "kb ${arm.name.lowercase()} for ${increment.id}: ${result.log}", at = clock.instant()))
        return Knowledge(result.notes, index, result.log)
    }

    private suspend fun runCell(
        c: OpenedCampaign,
        cellId: ContextId,
        increment: Increment,
        role: io.astrolabe.cell.Role,
        model: CellModel,
        authority: Authority,
        syntax: SyntaxCheck,
        compiled: Compiled.Ready,
        span: SpanId?,
        ledger: Ledger?,
        register: Register? = null,
        seeds: List<io.astrolabe.workset.Entry> = emptyList(),
        proposals: io.astrolabe.tool.task.Proposals? = null,
        completion: io.astrolabe.cell.RoleCompletion? = null,
        pinned: List<String> = emptyList(),
        boundary: BoundaryReason? = null,
        inputs: CompileInputs = CompileInputs(),
        precompile: PrecompileTrigger? = null,
        child: ChildForm? = null,
    ): CellRun {
        val ids = c.ids.copy(context = cellId)
        val cancellation = child?.cancellation ?: c.cancellation
        val config = c.attempt.config
        val contract = c.contract
        val redaction = Redaction(config.redaction)
        val logs = c.store.layout.root.resolve("logs")
        val estimator = model.estimator
        val runner = TrustedLocalRunner(c.os)
        val workset = Workset(immediateStubTokens = config.defaults.immediateStubTokens).also { it.seed(seeds) }
        val observations = SqliteObservations(c.store, clock)
        val aliases = SqliteAliases(c.store, clock)
        val receipts = SqliteReceipts(c.store, clock)
        val registerVersions = SqliteRegisterVersions(c.store, clock)
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val preimages = Preimages(c.workspace, c.store.blobs, ids, clock)
        val isolated = child?.isolated == true
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, receipts, aliases, idGen, ids, clock, candidates = if (isolated) c.store.layout.candidates else candidates(c), isolateAll = isolated)
        val checker = Checker(c.checks, runner, c.os, c.stamper, c.registry, c.workspace, c.store.blobs, redaction, idGen, ids, logs)
        val verify = Verify(checks = c.checks, scheduler = scheduler, checker = checker, baseline = null, s0 = c.s0.stampId, workspace = c.workspace, runner = runner, os = c.os, stamper = c.stamper, blobs = c.store.blobs, redaction = redaction, estimator = estimator, idGen = idGen, ids = ids, contracts = c.contracts, logsDir = logs, campaignReview = campaignReview(c, authority),
            // §8.8: review(scope=increment) is the review cell for S2+ main-line cells; a review cell never reaches it (no verify.review in its mask).
            incrementReview = if (child == null && contract.shape >= Shape.S2) IncrementReview { why -> reviewCell(c, increment, model, authority, syntax, span).obtain(evidence(c, increment, listOf(why), emptyList(), compiled.k.ledger, authority), Tier.Medium, c.registry::version) } else null,
        )
        verify.inputs = c.atlas.rows.map { it.path }
        val ceiling = Ceiling.of(contract.authorization, config.executionMode)
        val generation = c.lease?.generation ?: ExecutionGeneration.INITIAL
        // §10.1 (D-121): an S2+ main-line cell whose role unmasks task.delegate delegates to child cells; a child never does.
        val children = if (child == null && contract.shape >= Shape.S2 && role.effectiveOps(contract.shape, ceiling).allows("task.delegate")) {
            CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        } else {
            null
        }
        val delegator = children?.let { scope ->
            val reviews: (io.astrolabe.delegate.TaskPacket) -> EvidencePacket = { evidence(c, increment, listOf("delegated by ${cellId.value}"), emptyList(), compiled.k.ledger, authority) }
            val arithmetic = compiled.selection.arithmetic
            val fixed = (arithmetic.totalTokens ?: arithmetic.knownFixedTokens + arithmetic.selectedTokens).toLong()
            val worth = { kind: io.astrolabe.delegate.ChildKind, packet: io.astrolabe.delegate.TaskPacket ->
                WorthTest.estimate(kind, packet, estimator.estimate(ChildBrief.render(packet, Probe.OUTPUT)).upperBoundTokens, fixed, config.defaults)
            }
            Delegator(CellChildRunner(childCell(c, increment, model, authority, syntax, span), evidence = reviews), PublicationAuthority { c.refusal() }, c.cancellation, DelegationLimits(contract.budget.tokens), contract.shape, scope, idGen, clock, events, worth = worth)
        }
        val tools = CellTools(
            state = StateTool(Validator(estimator), registerVersions, c.journal, estimator, idGen, ids, clock, register ?: Register.empty(cellId, increment.id, increment.title), events),
            look = Look(c.workspace, c.registry, workset, c.atlas, Searches.jvm(), c.journal, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, checks = c.checks),
            edit = Edit(
                c.workspace, c.registry, workset, c.os, preimages, ScopeGuard(c.workspace), c.contracts, c.checks, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, syntax,
                // D-99: `revert:turn:N` names the campaign's shadow snapshots (the turn checkpoint records them).
                shadowRef = c.shadow,
                transforms = TransformExecution(runner, c.stamper, logs, config.executionMode, EnvPolicy(inheritedNames = config.redaction.envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1"))),
            ),
            run = Run(c.workspace, c.registry, c.stamper, runner, c.os, c.intents, SqliteHandles(c.store, clock), observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, c.contracts, authority, config, clock, logs),
            verify = verify,
            task = if (proposals == null && delegator == null) TaskTool(authority, c.contracts, c.journal, estimator, idGen, ids, clock, events)
            else TaskTool(
                authority, c.contracts, c.journal, estimator, idGen, ids, clock, events, role.effectiveOps(contract.shape, ceiling), proposals,
                delegator, delegator?.let { TaskPackets(WORKSPACE, ceiling, generation) }, c.registry::version,
            ),
            kb = KbTool(c.kb, estimator, idGen, queue = Queue(c.store, KbWriter(c.store, estimator, clock), idGen, clock), ids = ids, events = events),
        )
        // §6.3: what this cell was given is logged per note; the register-citation hook turns `injected` into `cited`.
        val usage = Usage(c.store, clock)
        val injected = inputs.notes.filter { it.status == NoteStatus.Admitted }.map { it.id }.toSet()
        for (id in injected) usage.record(id, ids, UsageEvent.Injected)
        if (injected.isNotEmpty()) c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = injected.toList(), text = "kb injected: ${injected.joinToString(", ")}", at = clock.instant()))
        // Focus notes follow the same ablation arm as the ranked injection: none when off, the open-time base when frozen.
        val focusBase = when (config.flags.kbInjection) {
            KbInjection.Off -> emptyList()
            KbInjection.Frozen -> c.frozenNotes
            KbInjection.Live -> Notes(c.store).all()
        }
        val knowledge = KnowledgeUse(focusBase, injected, usage, ids, estimator, config.defaults.focusNotesMaxTokens, KbNegatives(c.journal, idGen, clock))
        val coherence = Coherence(c.registry)
        val accounting = Accounting(c.store, clock)
        // §6.5: every compiled context leaves a manifest; the cell's end event links it.
        val manifests = SqliteManifests(c.store, clock)
        val manifest = Manifest.of(idGen.next("manifest"), compiled, increment, contract, ids, model.profile, inputs, register?.version, boundary, model.effort.name.lowercase()).also { manifests.save(ids, it) }
        val ctx = CellContext(
            ids = ids, role = role, contracts = c.contracts, model = model, tools = tools,
            workspace = CellWorkspace(c.workspace, c.registry, coherence, c.stamper, workset, c.checks, scheduler, c.atlas, checker),
            evidence = CellEvidence(c.journal, observations, aliases, receipts, c.intents, registerVersions, checkpoints, preimages),
            prime = c.prime, ledger = ledger, preexisting = compiled.k.ledger, config = config,
            turnCheckpoint = TurnCheckpoint { snapshot(c) },
            accounting = accounting,
            manifest = manifest.id,
            sections = compiled.k.sections,
            pinned = pinned,
            precompile = precompile,
            knowledge = knowledge,
        )
        val budget = child?.budget?.let { CellBudget.of(it.tokens, it.turns, contract.budget.reserves) }
            ?: CellBudget.of(contract.budget.tokens, contract.budget.turnsPerCell, contract.budget.reserves)
        val cellSpan = spans?.start(Phase.Edit, ids, span)
        val dispatch = DispatchAuthority { (cancellation.reason?.let { "cancelled: $it" } ?: c.refusal())?.let { DispatchRefusal(it, cancelled = cancellation.cancelled || c.cancellation.cancelled) } }
        // A cell that finished before the cancellation reached it keeps its exit: a late completion, archived below.
        val finished = AtomicReference<CellExit?>(null)
        val exit = try {
            coroutineScope {
                val job = async { Cell(clock, idGen, config.defaults, Gates.s0(), events, completion, authority = dispatch).run(ctx, increment, budget).also(finished::set) }
                // A cancellation mid-call interrupts the in-flight request; the cell settles its checkpoint first.
                cancellation.onCancel { job.cancel(CancellationException("cancelled: $it")) }.use { job.await() }
            }
        } catch (cancelled: CancellationException) {
            if (!cancellation.cancelled || !currentCoroutineContext().isActive) {
                cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
                throw cancelled
            }
            finished.get()
        } catch (failure: Throwable) {
            cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
            throw failure
        } finally {
            coherence.close()
            children?.cancel()
        }
        accounting.calls(c.ids.work).firstOrNull { it.ids.context == cellId }?.usage?.takeIf { it.isComplete }?.let { manifests.recordFirstUsage(ids, manifest.id, it.totalInput) }
        if (exit == null) {
            cellSpan?.let { spans?.end(it, status = TraceSpanStatus.Cancelled) }
            return CellRun(null, ids, scheduler, checkpoints)
        }
        if (cellSpan != null && spans != null) {
            // The cell's exclusive cost is its own model calls, priced once here and never again by a parent.
            val cost = Accounting.totals(accounting.calls(c.ids.work).filter { it.ids.context == cellId }, 0, spans.currency).money
            spans.end(cellSpan, cost)
        }
        return CellRun(exit, ids, scheduler, checkpoints)
    }

    /**
     * The child-context form of [runCell] (§10.1): the child's own cancellation token and budget; [isolated] runs every
     * check on an isolated copy of the candidate tree under `candidates/` (a review cell's `verify(tests)`, §8.8).
     */
    private class ChildForm(val cancellation: Cancellation, val budget: ChildBudget, val isolated: Boolean = false)

    /**
     * Runs a delegated child (D-121): a fresh cell under the child's context id, compiled on the parent's increment slice
     * with the runtime brief pinned in `[T]` — the parent's transcript never reaches it (D13).
     */
    private fun childCell(c: OpenedCampaign, increment: Increment, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): ChildCell =
        ChildCell { seat, role, completion, budget, brief ->
            val compiler = Compiler(model.estimator, c.attempt.config)
            fun compile(profile: io.astrolabe.provider.Profile) = compiler.compile(increment, c.contract, profile, role, c.prime, pinned = listOf(brief), maxOutputTokens = model.maxOutputTokens)
            // §11.1: a child is routed by its own function row, never by the parent's tier; an escalated tier is its floor.
            val routing = route(c, seat.function, increment, model, seat.tier, compile(model.profile), ::compile)
            val compiled = routing.compiled
            check(compiled is Compiled.Ready) { "the ${role.name} child of ${increment.id} cannot be compiled: $compiled" }
            routing.refused?.let { error("the ${role.name} child of ${increment.id} is unaffordable: ${it.reason}") }
            runCell(c, seat.context, increment, role, routing.model, authority, syntax, compiled, span, null, completion = completion, pinned = listOf(brief), child = ChildForm(seat.cancellation, budget, isolated = role.name == Roles.review.name))
                .exit.also { exit -> routing.selected?.let { router.record(it, outcomeOf(exit)) } }
        }

    private fun reviewCell(c: OpenedCampaign, increment: Increment, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): ReviewCell =
        ReviewCell(CellReviewJudge(childCell(c, increment, model, authority, syntax, span), idGen, c.cancellation), authority, c.store, idGen, clock, c.journal)

    /**
     * The increment-scope review of a completed cell (§8.8), when a trigger owes one: the review cell at its row's tier,
     * a current approval reused, the human path as fallback. `null` when no review is owed.
     */
    private suspend fun incrementReview(c: OpenedCampaign, increment: Increment, exit: CellExit.Completed, tier: Tier?, compiled: Compiled.Ready, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): ReviewOutcome? {
        val prescan = c.impactPrescan
        val impact = RiskFloorInput(prescan.contractsTouched.size, prescan.complete, prescan.prescan.fanIn, prescan.complete)
        val flags = exit.packet.flags.testIntegrity
        val triggers = ReviewTriggers.increment(IncrementReviewInput(c.contract, increment, Roles.implementing, tier, flags, exit.packet.changes.map { it.path }, c.kb.contractAnchors(), impact))
        if (triggers.isEmpty()) return null
        val row = FunctionTable.DEFAULT.row(ReviewTriggers.function(triggers))
        return reviewCell(c, increment, model, authority, syntax, span).obtain(evidence(c, increment, triggers, flags, compiled.k.ledger, authority), row.defaultTier, c.registry::version)
    }

    /**
     * Campaign scope (§8.8, D-124): in S2+ the campaign gate's review request is answered by the review cell over the
     * whole diff, the contract, the receipts and the rubric, with [host] as the human fallback; S0/S1 keep the host.
     */
    private fun campaignJudge(c: OpenedCampaign, host: Authority, model: CellModel, syntax: SyntaxCheck, span: SpanId?): Authority {
        val contract = c.contract
        if (contract.shape < Shape.S2) return host
        val whole = Increment(CAMPAIGN_REVIEW, contract.requirements.map { it.id }, contract.acceptance.map { it.id }, emptyList(), 0, title = "campaign review ${c.ids.work.value}")
        val judge = CellReviewJudge(childCell(c, whole, model, host, syntax, span), idGen, c.cancellation)
        val receipts = SqliteReceipts(c.store, clock)
        return ReviewCellAuthority(host, judge, FunctionTable.DEFAULT.row(io.astrolabe.route.RoutingFunction.ReviewCritical).defaultTier) { request ->
            val text = request.diffRef?.let { ref -> String(c.store.blobs.get(io.astrolabe.id.Digest(ref)), Charsets.UTF_8) } ?: "no diff was published"
            val changed = text.lineSequence().filter { it.startsWith("+++ b/") }.map { it.removePrefix("+++ b/").substringBefore(" (") }.toList()
            EvidencePacket(
                id = request.id, ids = request.ids, scope = ReviewScope.Campaign, incrementId = null, contractVersion = request.contractRevision, candidate = request.candidate,
                requirements = contract.requirements.map { Excerpt(it.id, it.text, it.authorityRef) }, criteria = contract.acceptance.map(ReviewCriterion::of),
                diff = if (text.length <= MAX_REVIEW_DIFF_CHARS) text else text.take(MAX_REVIEW_DIFF_CHARS) + "\n… cut at $MAX_REVIEW_DIFF_CHARS chars; the full diff is blob ${request.diffRef}",
                diffRef = request.diffRef, receipts = request.receipts.mapNotNull(receipts::get).map { ReviewReceipt.of(it, required = true) },
                notes = emptyList(), testIntegrity = emptyList(), preexisting = emptyList(), coverage = null, rubric = request.rubric + EvidencePacket.RUBRIC,
                evidenceVersions = changed.mapNotNull { path -> c.registry.version(path)?.let { path to it } }.toMap(), triggers = listOf(ReviewTriggers.CAMPAIGN),
            )
        }
    }

    /** The verdict each `review:` item of [increment] is signed with (§8.7); only an approval is passed on. */
    private fun reviewItems(contract: Contract, increment: Increment, review: ReviewOutcome?): Map<String, io.astrolabe.verify.Verdict> {
        val verdict = (review as? ReviewOutcome.Approved)?.record?.verdict ?: return emptyMap()
        return increment.accept.filter { contract.acceptance(it) is Acceptance.Review }.associateWith { verdict }
    }

    /**
     * The §8.8 evidence packet of [increment] at the tree now (D-124): the diff `s0 → now`, the increment's complete
     * acceptance definitions, the current receipts with parsed counts (required when they certify its items), the
     * CON/ADR anchors on the changed paths, the test-integrity flags, the pre-existing ledger and the rubric.
     */
    private fun evidence(c: OpenedCampaign, increment: Increment, triggers: List<String>, flags: List<io.astrolabe.verify.TestIntegrityFlag>, preexisting: io.astrolabe.verify.PreexistingLedger?, authority: Authority): EvidencePacket {
        val contract = c.contract
        val stamp = c.stamper.report().candidateId
        val (digest, limits) = campaignReview(c, authority).diffBlob(c.s0.stampId, stamp)
        val text = String(c.store.blobs.get(digest), Charsets.UTF_8)
        val changed = text.lineSequence().filter { it.startsWith("+++ b/") }.map { it.removePrefix("+++ b/").substringBefore(" (") }.toList()
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock)
        val receipts = SqliteReceipts(c.store, clock)
        val required = increment.accept.flatMap { c.checks.forAcceptance(it) }.map { it.id }.toSet()
        val current = currencies(c, scheduler, stamp).mapNotNull { (checkId, currency) -> currency.receiptId?.let(receipts::get)?.let { ReviewReceipt.of(it, checkId in required) } }
        val notes = c.kb.contractAnchors().filterValues { anchored -> changed.any { it in anchored } }.map { (id, paths) -> "$id anchors ${paths.sorted().joinToString(", ")}" }
        return EvidencePacket(
            id = idGen.next("evidence"), ids = c.ids.withCandidate(stamp), scope = ReviewScope.Increment, incrementId = increment.id, contractVersion = contract.version, candidate = stamp,
            requirements = increment.requirementIds.mapNotNull { contract.requirement(it) }.map { Excerpt(it.id, it.text, it.authorityRef) },
            criteria = EvidencePacket.criteria(contract, increment),
            diff = if (text.length <= MAX_REVIEW_DIFF_CHARS) text else text.take(MAX_REVIEW_DIFF_CHARS) + "\n… cut at $MAX_REVIEW_DIFF_CHARS chars; the full diff is blob ${digest.hex}",
            diffRef = digest.hex, receipts = current, notes = notes, testIntegrity = flags,
            preexisting = preexisting?.entries.orEmpty().map { "${it.identity} — ${it.signature}" }, coverage = null,
            rubric = EvidencePacket.RUBRIC + limits.map { "diff limit: $it" },
            evidenceVersions = changed.mapNotNull { path -> c.registry.version(path)?.let { path to it } }.toMap(), triggers = triggers,
        )
    }

    /**
     * `finish` (§3.7) in its S0 form: every requirement verified and every `run:` item re-certified by a current
     * receipt at the final stamp, else an honest stop — never `completed` over a gap.
     */
    private suspend fun stopOrFinish(c: OpenedCampaign, unfinished: String, scheduler: Scheduler? = null, campaign: Boolean = false, authority: Authority? = null): CampaignState {
        val state = checkNotNull(c.state)
        if (state.ledger.unfinished().isNotEmpty() || scheduler == null) {
            return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, unfinished))
        }
        val refactor = RefactorMode.isActive(c.contract)
        // §8.7 campaign gate (P2.2.6): the campaign review predicate names the review owed; P3.5.2 obtains it below, after the evidence.
        val reviewOwed = if (campaign) CampaignFinish.reviewRequired(c.contract, state.graph.increments.count { it.status != IncrementStatus.Cancelled }, refactor) else null
        if (campaign) {
            when (val full = fullSuite(c, "campaign end")) {
                is FullSuite.Red -> return c.advance(Transition.Stopped(CampaignOutcome.Failed, "final full suite red: ${full.detail}"))
                is FullSuite.NotCertified -> return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "final full suite could not certify: ${full.detail}"))
                FullSuite.Green, FullSuite.Undeclared -> Unit
            }
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
        if (reviewOwed != null) {
            // §8.9 items 5–6, D-23: the equivalence evidence, then the signed campaign-scope review — blocked when unavailable, never skipped.
            val reviewer = campaignReview(c, authority ?: return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "$reviewOwed; no authority to review")))
            val equivalence = if (refactor) reviewer.equivalence(stamp, currencies) else null
            if (refactor && equivalence == null) {
                return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "refactor mode without a behaviour snapshot: no equivalence evidence at @${stamp.hash8} (§8.9 item 5)"))
            }
            val current = currencies.values.filter { it.certifies }.mapNotNull { it.receiptId }.distinct()
            when (val review = reviewer.review(c.contract, c.s0.stampId, current, equivalence, reviewOwed)) {
                is CampaignReviewOutcome.Approved -> Unit
                is CampaignReviewOutcome.Declined -> return c.advance(Transition.Stopped(if (review.terminal) CampaignOutcome.Failed else CampaignOutcome.BlockedExternal, "$reviewOwed; ${review.reason}"))
                is CampaignReviewOutcome.Unavailable -> return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "$reviewOwed; ${review.reason}"))
            }
        }
        c.advance(Transition.Finishing(stamp))
        return c.advance(Transition.Finished(stamp, receipts.distinct()))
    }

    /** The D-23 human review path of this campaign: `Authority.review` over the full diff, the receipts and the rubric built from the admitted plan. */
    private fun campaignReview(c: OpenedCampaign, authority: Authority): CampaignReview {
        fun plan(): PlanPacket? = SqlitePlanProposals(c.store, idGen, clock).latest(c.ids.work, null)?.packet
        return CampaignReview(
            authority, c.shadow, c.workspace, c.stamper, c.store, c.journal, SqliteReceipts(c.store, clock), c.checks, idGen, c.ids, clock,
            checklist = { plan()?.refactorChecklist },
            conReferences = { plan()?.let { p -> p.conReferences + p.conCandidates.map { "new: ${it.summary}" } }.orEmpty() },
        )
    }

    private sealed interface FullSuite {
        data object Green : FullSuite
        data object Undeclared : FullSuite
        data class Red(val detail: String) : FullSuite
        data class NotCertified(val detail: String) : FullSuite
    }

    /** Runs the declared full suite through the `verify` tool path (receipts, closures, redaction) and journals the result. */
    private suspend fun fullSuite(c: OpenedCampaign, why: String): FullSuite {
        val check = c.checks[Checks.FULL]
        val ids = c.ids.copy(context = ContextId(idGen.next("finish")))
        // §8.1: the configured quality gates run with the suite, declared or not; a red gate is a red result.
        val gates = c.checks.all().filter { it.kind == CheckKind.Quality }.map { it.id }
        if (gates.isNotEmpty()) harnessVerify(c, ids, "quality", """{"what":"tests","selection":"ids","ids":[${gates.joinToString(",") { "\"$it\"" }}]}""")
        val redGate = gates.mapNotNull { c.checks[it]?.last }.firstOrNull { it.outcome == Outcome.Failed }
        if (check == null) {
            val red = redGate?.let { FullSuite.Red("quality gate ${it.receiptId} failed") }
            c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "full suite ($why): none declared by the repository — an explicit gap, acceptance runs stand" + (red?.let { " · ${it.detail}" } ?: ""), at = clock.instant()))
            return red ?: FullSuite.Undeclared
        }
        harnessVerify(c, ids, "full", """{"what":"tests","selection":"full"}""")
        val last = c.checks[Checks.FULL]?.last
        val stamp = c.stamper.report().candidateId
        // The full suite's closure is unknown (every file), so its evidence is a pass recorded at this very stamp.
        val result = when {
            redGate != null -> FullSuite.Red("quality gate ${redGate.receiptId} failed at @${stamp.hash8}")
            last?.outcome == Outcome.Passed && last.stamp == stamp -> FullSuite.Green
            last?.outcome == Outcome.Failed -> FullSuite.Red("${last.receiptId} failed at @${stamp.hash8}")
            else -> FullSuite.NotCertified("${last?.receiptId ?: "no receipt"} ${last?.outcome?.name?.lowercase() ?: "not run"} at @${stamp.hash8}")
        }
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOfNotNull(last?.receiptId), text = "full suite ($why): ${result::class.simpleName!!.lowercase()}", at = clock.instant()))
        return result
    }

    /** One `verify` call the harness makes on its own authority (full suite, regression obligations), outside any cell. */
    private suspend fun harnessVerify(c: OpenedCampaign, ids: Identities, callId: String, args: String): io.astrolabe.tool.ToolOutcome {
        val config = c.attempt.config
        val redaction = Redaction(config.redaction)
        val logs = c.store.layout.root.resolve("logs")
        val runner = TrustedLocalRunner(c.os)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, ids, clock, candidates = candidates(c))
        val checker = Checker(c.checks, runner, c.os, c.stamper, c.registry, c.workspace, c.store.blobs, redaction, idGen, ids, logs)
        val verify = Verify(checks = c.checks, scheduler = scheduler, checker = checker, baseline = null, s0 = c.s0.stampId, workspace = c.workspace, runner = runner, os = c.os, stamper = c.stamper, blobs = c.store.blobs, redaction = redaction, estimator = HeuristicEstimator(), idGen = idGen, ids = ids, contracts = c.contracts, logsDir = logs)
        // An unknown closure is rescanned over the atlas rows, as in a cell; without them no receipt can certify the tree.
        verify.inputs = c.atlas.rows.map { it.path }
        val call = (ToolCalls.parse(listOf(io.astrolabe.provider.ToolCall(callId, "verify", args))) as ParsedCalls.Valid).calls.single()
        return verify.execute(call, TurnContext(0, Workset().snapshot(), Reservations(Tokens(config.defaults.runBudgetTokens.toLong()))))
    }

    /** The outcome of a refused dispatch or publication: `cancelled` for a cancellation, else the lost lease blocks. */
    private fun stopOutcome(c: OpenedCampaign): CampaignOutcome =
        if (c.cancellation.cancelled) CampaignOutcome.Cancelled else CampaignOutcome.BlockedExternal

    /** D-73: isolated candidates for `slow|expensive` checks only once concurrent writers exist (S3); until then every check runs exclusively. */
    private fun candidates(c: OpenedCampaign): java.nio.file.Path? = c.store.layout.candidates.takeIf { c.attempt.config.flags.s3Writers }

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

        /** The review brief carries at most this much of the diff; the full diff stays a blob it names (D-124). */
        private const val MAX_REVIEW_DIFF_CHARS: Int = 16_000
        private const val CAMPAIGN_REVIEW: String = "campaign-review"

        /** Default cap on an S1 campaign's cells, plan cell excluded (D-70). */
        public const val DEFAULT_MAX_CELLS: Int = 12

        /** The pseudo-increment the plan cell runs under; never part of the graph. */
        public const val PLAN: String = "plan"
    }
}
