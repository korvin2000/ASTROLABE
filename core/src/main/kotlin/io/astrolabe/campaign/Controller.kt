package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.BalanceProfile
import io.astrolabe.BalanceProfiles
import io.astrolabe.Config
import io.astrolabe.PlanCellPolicy
import io.astrolabe.Project
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Prime
import io.astrolabe.atlas.RulesSnapshot
import io.astrolabe.atlas.HostFacts
import io.astrolabe.atlas.HostProbe
import io.astrolabe.atlas.Sniffed
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.RulesTrust
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.LimitDecision
import io.astrolabe.budget.LimitRule
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.TaskLimits
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Cell
import io.astrolabe.cell.CellContext
import io.astrolabe.cell.CellEvidence
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PartialReason
import io.astrolabe.cell.CellModel
import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.route.CacheKey
import io.astrolabe.route.EscalationStep
import io.astrolabe.route.FunctionTable
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
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleTexts
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
import io.astrolabe.context.ContextSelectionStatus
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
import io.astrolabe.contract.EvidencePurpose
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.MessageKind
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
import io.astrolabe.kb.BmapStore
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
import io.astrolabe.kb.NoteHorizon
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.KnowledgeUse
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Skill
import io.astrolabe.kb.SkillConflict
import io.astrolabe.kb.SkillStore
import io.astrolabe.kb.Skills
import io.astrolabe.kb.StateChange
import io.astrolabe.kb.StateChangeKind
import io.astrolabe.kb.Notes
import io.astrolabe.kb.Queue
import io.astrolabe.kb.StoreKb
import io.astrolabe.kb.Usage
import io.astrolabe.kb.UsageEvent
import io.astrolabe.os.EnvPolicy
import io.astrolabe.os.Git
import io.astrolabe.os.LocalOs
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.search.Search
import io.astrolabe.os.search.Searches
import io.astrolabe.provider.Money
import io.astrolabe.recover.AcceptanceCheck
import io.astrolabe.recover.CellRepairRunner
import io.astrolabe.recover.GuardLimits
import io.astrolabe.recover.GuardVerdict
import io.astrolabe.recover.OriginalAcceptance
import io.astrolabe.recover.Recovery
import io.astrolabe.recover.Repair
import io.astrolabe.provider.Profile
import io.astrolabe.register.Register
import io.astrolabe.register.SqliteRegisterVersions
import io.astrolabe.register.Validator
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.telemetry.PhaseMark
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
import io.astrolabe.verify.TestIntegrity
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Currency
import io.astrolabe.verify.RefactorMode
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.ScopeGuard
import io.astrolabe.verify.Verifier
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.DecisionItem
import io.astrolabe.verify.DecisionKey
import io.astrolabe.verify.DecisionKind
import io.astrolabe.verify.DecisionRecord
import io.astrolabe.verify.ObligationKind
import io.astrolabe.verify.ObligationResult
import io.astrolabe.verify.Obligations
import io.astrolabe.verify.Resolution
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.ResultStatus
import io.astrolabe.verify.StopCode
import io.astrolabe.event.Replies
import io.astrolabe.event.ReplyValidity
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.Preimages
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Snapshot
import io.astrolabe.workspace.SnapshotEntryKind
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.UnreadableInput
import io.astrolabe.workspace.movedPathsHint
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.Collections
import org.slf4j.LoggerFactory
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference
import io.astrolabe.delegate.CellChildRunner
import io.astrolabe.delegate.CellReviewJudge
import io.astrolabe.delegate.ChildBrief
import io.astrolabe.delegate.ChildBudget
import io.astrolabe.delegate.ChildCell
import io.astrolabe.delegate.ChildNotStarted
import io.astrolabe.delegate.DelegationLimits
import io.astrolabe.delegate.Delegator
import io.astrolabe.delegate.EvidencePacket
import io.astrolabe.delegate.Excerpt
import io.astrolabe.delegate.IncrementReview
import io.astrolabe.delegate.IncrementReviewInput
import io.astrolabe.delegate.ReviewBudget
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.delegate.ReviewCellAuthority
import io.astrolabe.delegate.ReviewCriterion
import io.astrolabe.delegate.ReviewOutcome
import io.astrolabe.delegate.ReviewReceipt
import io.astrolabe.delegate.ReviewTriggers
import io.astrolabe.delegate.Probe
import io.astrolabe.delegate.TaskPackets
import io.astrolabe.delegate.WorthTest
import io.astrolabe.delegate.WriterCell
import java.util.concurrent.ConcurrentHashMap
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

/**
 * What a host opens a campaign for (§3.7 `campaign(request, repo, policy)`); a reopen passes the same ids. [parentWork]
 * names the work a follow-up follows (task-workflow §1.3, §1.4): it must have a final outcome, and the first open records it.
 */
public data class CampaignRequest @JvmOverloads constructor(val work: WorkId, val attempt: AttemptId, val text: String, val parentWork: WorkId? = null) {
    init {
        require(text.isNotBlank()) { "a campaign needs a request" }
        require(parentWork != work) { "a work does not follow itself" }
    }
}

/**
 * The host's campaign policy: the contract's token (and optional money) budget — there is no default campaign budget —
 * and whether a resume is expected, which rules out S0 (§3.5). The contract takes [tokens] and [cost] at its first open; on
 * a reopen [tokens] raises the contract's tokens when above them and is otherwise ignored (C14: a value at or below keeps
 * the stored ones), never for a finished campaign; [cost] stays as the contract froze it.
 */
public data class CampaignPolicy @JvmOverloads constructor(
    val tokens: Tokens,
    val cost: Money? = null,
    val resumeExpected: Boolean = false,
    /**
     * The host's instructions for this campaign (D-345): pinned in every main-line cell as notes from the host —
     * never a contract request, so the contract's objective and pinned requests stay the user's own words.
     */
    val hostNotes: List<String> = emptyList(),
    /**
     * The user's limits on this task (plan §4.6, C3): money, active minutes, model requests. `null` keeps the limits kept
     * with the campaign (none on a first open); [TaskLimits.NONE] lifts them all; any other value replaces them. Only the
     * host sets them — the model and the harness never raise one. A campaign a limit stopped continues, the same attempt,
     * when it is reopened with limits that leave room again.
     */
    val limits: TaskLimits? = null,
    /**
     * The balance profile chosen for this task (plan §4.6, C3); `null` takes [Config.balance]. It is frozen with the
     * attempt at its first open (invariant 12): a reopen naming another one changes nothing until the next attempt.
     */
    val balance: BalanceProfile? = null,
    /**
     * WF-1 (task-workflow §3.6): acceptance items the host declares for a new contract — its saved test command, a review
     * check — added to the derived contract before it is stored (ids renumbered after the derived ones, every requirement
     * bound to them), so a project the core derives nothing executable for opens once. Ignored once a contract is stored.
     * A saved command of purpose `regression` replaces the sniffed suite of its package ([Contracts.declared], §3.6).
     */
    val declaredChecks: List<Acceptance> = emptyList(),
    /**
     * Task-workflow §5.1 (D-435): an autonomous campaign records the model's `task.propose(output)` without a person. Off by
     * default: the proposal is refused with this policy named.
     */
    val autoDeclareOutputs: Boolean = false,
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
    /** The host's instructions (D-345), pinned as host notes in every main-line cell. */
    public val hostNotes: List<String> = emptyList(),
    /** The user's limits on this task in force at this open: the host's, or the ones kept with the campaign (C3). */
    public val limits: TaskLimits = TaskLimits.NONE,
    /**
     * What still holds a `budget_exhausted` campaign this open could not continue (C14): the task limit or the contract
     * budget (with its cause) to raise next; `null` when the campaign was not stopped on a budget or this open continued it.
     * A `budget_exhausted` state written before C3 carries no `BudgetStop`: nothing typed holds it (`null`), and its
     * `CampaignState.reason` says why it stopped.
     */
    public val limitHold: LimitHold? = null,
    /** T-24 (WF-4 at open): the path this reopen could not read; the campaign stopped `blocked_external` on it, resumably. */
    public val unreadable: String? = null,
) : AutoCloseable {
    /**
     * T-28: untracked files the attempt's output policy kept out of the latest snapshot this open took (W3, I-12) —
     * counted, never read; the open's capture when it took none since.
     */
    public fun scratchCount(): Int = dirty.latest?.scratchCount ?: s0.scratchCount

    /** Cancels this campaign: no further dispatch, no publication; effects already made are archived (D-26). */
    public val cancellation: Cancellation = Cancellation()

    /** The task limits' running state for this open: session, latched reserve, a cell's limit stop, counters (C3). */
    internal val limitState: LimitState = LimitState()

    /** C11: the increment reviews asked of the host for a person in this open (increment, candidate, revision): asked once. */
    internal val personAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** §7.2 phase counter: finalization attempts (the campaign gate entered with every requirement verified) in this open. */
    internal val finishAttempts: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()

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
            return Disposition.Stop(outcome, current.reason ?: outcome.wire, current.stopCode)
        }

    /** Applies [transition] and saves the result: the controller is the one writer of the campaign row (L9). */
    public fun advance(transition: Transition): CampaignState = advance(transition) { }

    /** [advance] with [before] given the next state ahead of its save: what the row refers to is kept first (artifact before row). */
    internal fun advance(transition: Transition, before: (CampaignState) -> Unit): CampaignState {
        val next = Lifecycle.apply(checkNotNull(state) { "no campaign state: ${refusal}" }, contract, transition)
        before(next)
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
public data class S0Run @JvmOverloads constructor(
    val state: CampaignState?,
    val exit: CellExit?,
    val completion: CompletionResult?,
    val compiled: Compiled?,
    /** The finish receipt, for a campaign this run ended (P1.9.5). */
    val finish: FinishReceipt? = null,
    /** How a task limit ended the campaign, with its best verified candidate (C3); `null` for every other ending. */
    val limit: LimitStop? = null,
) {
    public val outcome: CampaignOutcome? get() = state?.outcome

    /** Which ceiling ended a `budget_exhausted` campaign (C3); `null` for every other ending. */
    public val budgetStop: BudgetStop? get() = state?.budgetStop
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
    /** The host's optional layers (tier-1 index, dense retrieval, generated tools, mounts), each read only under its flag. */
    private val layers: OptionalLayers = OptionalLayers(),
    /** Per-profile admission estimators for routed cells (D-06, D-327); `null` keeps the supplied cell model's estimator. */
    private val estimators: io.astrolabe.provider.EstimatorFactory? = null,
    /** What the prime's host block reports (D-366): probed once per campaign open, never by starting a process. */
    private val host: HostProbe = HostProbe.system(),
) {
    // Resolved once per campaign at open, its attempt boundary (§12.2).
    private val plugged = Collections.synchronizedMap(WeakHashMap<OpenedCampaign, PluggedLayers>())

    private fun plugged(c: OpenedCampaign): PluggedLayers = plugged[c] ?: PluggedLayers.of(layers, c.attempt.config.flags, c.journal, c.ids, idGen, clock).also { plugged[c] = it }

    // C18: the search backend, chosen once per campaign open and logged in one line — ripgrep when `rg` resolves on the host's PATH, else the JVM.
    private val searches = Collections.synchronizedMap(WeakHashMap<OpenedCampaign, Search>())

    /** The search backend of [c]'s cells: ripgrep when `rg` resolves on the host's `PATH`, else the in-process JVM backend. */
    internal fun search(c: OpenedCampaign): Search = searches[c] ?: Searches.auto { host.onPath(RIPGREP) }.also {
        searches[c] = it
        LoggerFactory.getLogger(Controller::class.java).info("search backend {} for {}/{}", it.backend.name.lowercase(), c.ids.work.value, c.ids.attempt.value)
    }

    /** The task limits' spend, sessions and gate (C3). */
    private val limitControl = TaskLimitControl(idGen, clock, events)

    /** §7.2 phase counter: campaign opens by this controller (WF-1 counts them per host action). */
    private val opens = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Publishes a finished campaign's candidate beyond `patch` when the host asks for it (§14.2, D-192, D-250): one
     * [Publisher] for the attempt, each requested stage a separate D-class grant through [authority], journaled before
     * and after, stopping at the first stage not published. Nothing calls this by default. The returned receipt, also
     * re-exported, reports the stage actually reached.
     */
    @JvmOverloads
    public suspend fun publish(campaign: OpenedCampaign, run: S0Run, request: PublicationRequest, authority: Authority = AutonomousAuthority(), deployer: Deployer? = null): PublicationRun {
        val finish = requireNotNull(run.finish) { "publication follows a finished campaign: this run has no finish receipt" }
        return Publications(idGen, clock).publish(campaign, finish, request, authority, deployer)
    }

    /**
     * Opens or reopens [request]'s campaign over [repo]. Order (§3.7, §13.1): the store and its project lock, the
     * workspace and the dirty-state capture (snapshot 0 on first open), the contract derived and stored after the
     * capture, then reconciliation of open intents and of tree drift before anything may dispatch, then the
     * pre-scan stub and the shape. A reopen of a campaign that stopped on something outside it resumes it.
     */
    public fun open(repo: Path, request: CampaignRequest, policy: CampaignPolicy): OpenedCampaign {
        // §7.2, T-13: the open counts its own calls from here — the store's commands included — and no other caller's.
        val counting = PhaseMark.begin()
        return counting.run {
            val git = Git(repo, timeoutMillis = config.defaults.gitDeadlineSeconds * 1000L)
            var opened: Store? = null
            val os = try {
                opened = Store.open(config, git, clock, faults)
                LocalOs(clock)
            } catch (failure: Throwable) {
                opened?.close()
                // §7.2: an open the store or its project lock refused still reports what it cost.
                events?.emit(counting.counted(Identities(request.work, request.attempt), CountedPhase.Open, opens.incrementAndGet(), 0))
                throw failure
            }
            val store = checkNotNull(opened)
            try {
                open(repo, git, store, os, request, policy, owned = true, counting)
            } catch (failure: Throwable) {
                runCatching { os.close() }
                runCatching { store.close() }
                throw failure
            }
        }
    }

    /** Opens [request]'s campaign in [project], whose store, lock and OS stay the project's (P1.9.6). */
    public fun open(project: Project, request: CampaignRequest, policy: CampaignPolicy): OpenedCampaign {
        // T-13: the host's own commands on the project's shared git, before or during the open, are not the open's.
        val counting = PhaseMark.begin()
        return counting.run { open(project.root, project.git, project.store, project.os, request, policy, owned = false, counting) }
    }

    private fun open(
        repo: Path, git: Git, store: Store, os: LocalOs, request: CampaignRequest, policy: CampaignPolicy, owned: Boolean,
        counting: PhaseMark,
    ): OpenedCampaign {
        val ids = Identities(request.work, request.attempt)
        val protected = ProtectedPaths()
        val workspace = Workspace(WORKSPACE, repo, git, protected)
        val opened = opens.incrementAndGet()
        try {
            return opening(repo, git, store, os, request, policy, owned, ids, protected, workspace, counting, opened)
        } catch (failure: Throwable) {
            // §7.2: an open that fails or stops still reports what it cost.
            events?.emit(counting.counted(ids, CountedPhase.Open, opened, 0))
            throw failure
        }
    }

    private fun opening(
        repo: Path, git: Git, store: Store, os: LocalOs, request: CampaignRequest, policy: CampaignPolicy, owned: Boolean,
        ids: Identities, protected: ProtectedPaths, workspace: Workspace, counting: PhaseMark, opened: Int,
    ): OpenedCampaign {
        val registry = VersionRegistry(workspace)
        val journal = Journal(store, clock)
        // C3: a run that died mid-session is closed at its last event before this open writes anything (minutes limit).
        LimitSessions.closeDangling(journal, ids, idGen)
        // P4.1: the store-backed base; with no notes it behaves as the empty base (every search complete and empty).
        val negatives = KbNegatives(journal, idGen, clock)
        val kb = StoreKb(store, request.work, registry::version) { negatives.retrievalMiss(ids, null, it) }
        val intents = SqliteIntentJournal(store, clock)
        io.astrolabe.delegate.IntegrationPublication.recover(workspace, registry, store.blobs, intents)
        val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock, events)
        val campaigns = SqliteCampaigns(store, clock)
        val attempts = Attempts(store, clock)
        val storedAttempt = attempts.load(request.work, request.attempt)
        // C3: the balance profile applies once, when the attempt freezes; a reopen asks for the frozen one unless it names another.
        val requested = BalanceProfiles.applied(config, policy.balance ?: storedAttempt?.config?.balance ?: config.balance)
        // Task-workflow §5.1: the attempt's effective output policy — the base plus the contract's outputs at its first open — frozen before its s0.
        // §5.2 (WR2, P1 #4): and the toolchain directories in the tree its known checks launch from stay in identity.
        val knownContract = contracts.current(request.work)
        val launched = (knownContract?.acceptance.orEmpty().filterIsInstance<Acceptance.Run>().map { it.command } + policy.declaredChecks.filterIsInstance<Acceptance.Run>().map { it.command } +
            requested.qualityGates).map { command -> command.cwd?.takeIf { it.isNotBlank() && it != "." }?.let { "${it.trimEnd('/', '\\')}/${command.argv.first()}" } ?: command.argv.first() }
        val frozen = storedAttempt ?: AttemptConfig.freeze(requested).withOutputs(knownContract?.outputs.orEmpty().map { it.path }).withToolchains(launched)
            .also { attempts.save(request.work, request.attempt, it) }
        val effective = frozen.config
        if (effective != io.astrolabe.configSnapshot(requested)) {
            events?.emit(AgentEvent.Warning(ids, "config-frozen", "the configuration changed during attempt ${request.attempt.value}; it takes effect at the next attempt (invariant 12)"))
        }
        // W3: the output policy the attempt froze before its s0 (none for one frozen earlier); every stamper of it uses this one.
        val stamper = Stamper(workspace, EnvFingerprint.compute(env), scratch = frozen.scratch)
        val dirty = DirtyState(workspace, store.blobs, stamper, ids, clock)
        val shadow = ShadowRef(request.work, request.attempt, workspace, store, dirty, os, clock)

        // Capture before anything else looks at the tree: snapshot 0 is the user's pre-existing state.
        // T-03/T-25: the files the capture reads hand the atlas their outlines from that read (D-427, one read per file).
        val tap = io.astrolabe.atlas.AtlasTap(workspace.root)
        workspace.tap = tap
        val first = shadow.record(0) == null
        // T-24 (WF-4 at open): a file another process holds stops the reopened campaign resumably by its path below.
        var unreadable: UnreadableInput? = null
        val s0: io.astrolabe.workspace.Snapshot
        val external: List<Touched>
        try {
            s0 = if (first) dirty.capture(0, fresh = true).also { shadow.open(it) } else checkNotNull(shadow.manifest(0))
            external = if (first) emptyList() else try {
                drift(shadow, dirty)
            } catch (input: UnreadableInput) {
                unreadable = input
                emptyList()
            }
        } finally {
            workspace.tap = null
        }

        // W3: files the atlas does not parse take their hash from this open's own capture instead of a second read.
        val atlas = Atlas.build(workspace.root, dirty.latest?.entries?.associateBy { it.path }.orEmpty(), tap)
        val layered = PluggedLayers.of(layers, effective.flags, journal, ids, idGen, clock)
        // §3.7 impact_prescan (D-40): incomplete discovery over the request's candidate paths; it feeds both shape selections.
        val impactPrescan = ImpactPrescan.of(atlas, WORKSPACE, ImpactPrescan.inputs(atlas, WORKSPACE, request.text), kb.contractAnchors(), layered.tiers)
        val derived = contracts.deriveS0(request.work, request.attempt, request.text, atlas, effective, policy.tokens, protected, policy.cost, scratch = frozen.scratch)
        val stored = contracts.current(request.work)
        check(stored == null || stored.attemptId == request.attempt) { "work ${request.work.value} is attempt ${stored?.attemptId?.value}; a new attempt is P2" }
        // №31 (task-workflow §1.3): a follow-up opens only after a parent with a final outcome; one that can go on is resumed.
        if (stored == null) request.parentWork?.let { parent -> followable(contracts, campaigns, parent)?.let { throw IllegalArgumentException(it) } }
        // §3.5: a new contract carries the shape its campaign runs in; the tool masks derive from it.
        val contract = stored ?: contracts.open(Contracts.declared(derived.contract, policy.declaredChecks).copy(parentWork = request.parentWork).let { d ->
            val initial = (ShapeSelector.select(d, impactPrescan.prescan, effective.defaults.shapePolicy, policy.resumeExpected, capabilities = CAPABILITIES) as? ShapeDecision.Selected)?.shape
            if (initial == Shape.S1 || initial == Shape.S2) d.copy(shape = initial) else d
        })
        val commands = derived.primary?.let(RunnerCommands::of) ?: RunnerCommands()
        workspace.paths.bindWriteProtection { path, ignoreCase -> contracts.current(request.work)?.scope?.protects(path, ignoreCase) != false }
        val checks = Checks.seed(contract, commands, qualityGates = effective.qualityGates, packageManifest = TestIntegrity.packageManifests(workspace))
        // P8.C.10 F: a reopened attempt gets back the last run of the blast radius and of the types of touched files before any
        // resume, reacceptance or finish, so their holds are seen as the live run saw them.
        for (id in io.astrolabe.verify.Regressions.CHECKS) {
            val last = SqliteReceipts(store, clock).forCheck(id).lastOrNull {
                it.ids.work == request.work && it.ids.attempt == request.attempt && !io.astrolabe.verify.Regressions.isBaseline(it) && !io.astrolabe.verify.Regressions.isMarker(it)
            } ?: continue
            val known = checks[id]
            when {
                known == null -> checks.register(io.astrolabe.verify.Regressions.restored(last))
                known.last == null -> checks.record(id, io.astrolabe.verify.LastResult(last.receiptId, last.stampAfter, last.checkDefinitionVersion, last.outcome, last.parsed, io.astrolabe.verify.Applicability.Current))
            }
        }
        val rules =RulesTrust(workspace.root).approved(effective.rulesFile)?.let { RulesSnapshot(it.binding.path, it.digest, it.text) }
        val prime = Prime.render(atlas, derived.sniffed, rules, host = HostFacts.of(host, atlas))

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
        }
        val priorVersion = state?.contractVersion
        // A-D.6: a handoff kept for the very row its process died before writing is that return, never a lost cell, so its
        // increment continues as an epoch paid from the grant. It is applied first: its record names the row's `seq`, which
        // any transition below (a host fix's unblock) would move past it.
        state?.running?.takeIf { state.phase == CampaignPhase.Running }?.let { running ->
            val checkpoint = SqliteCheckpoints(store, clock).latest(running.cell) ?: return@let
            val stored = checkNotNull(state)
            val kept = ReturnedHandoffs(store, clock).all(request.work, request.attempt).lastOrNull { it.cell == running.cell && it.seq == stored.seq + 1 } ?: return@let
            val register = SqliteRegisterVersions(store, clock).latest(running.cell)
                ?: Register.empty(running.cell, running.increment, stored.graph.increments.first { it.id == running.increment }.title)
            journal.append(JournalEvent(idGen.next("ev"), ids, checkpoint.turn, JournalKind.Reconcile, refs = listOf(running.cell.value, kept.id), text = "open: cell ${running.cell.value} handed off before its controller stopped · its kept return ${kept.id} applied", at = clock.instant()))
            state = Lifecycle.apply(stored, contract, Transition.Returned(kept.exit(ids, register, checkpoint))).also(campaigns::save)
        }
        if (state?.phase == CampaignPhase.Finishing) {
            state = Lifecycle.apply(state, contract, Transition.Resumed("finalization interrupted; revalidate acceptance before completing")).also(campaigns::save)
        }
        // A-D.6: an explicit reopen — a resumable outcome or a limit stop that may continue — renews the handoff grant at the
        // next run, so a spent grant never reads as a dead end; only a direct line ever hands off.
        fun renewGrant(reason: String) {
            if (effective.protocol == Protocol.Direct) Handoffs(journal, idGen, clock, ids).resumed(reason)
        }
        if (state?.phase == CampaignPhase.Ended && state.resumable) {
            val reason = "reopened after ${state.outcome?.wire}" + if (state.failedResumably) " (a cell's failure, WF-10)" else ""
            state = Lifecycle.apply(state, contract, Transition.Resumed(reason)).also(campaigns::save)
            renewGrant(reason)
        }
        // C3 (K): the limits in force — the host's when it names them, else the ones kept with the campaign.
        val limits = TaskLimitControl.atOpen(journal, ids, idGen, clock, policy.limits)
        // C14: the host's notes and resume expectation, kept for a facade reopen that names no policy.
        HostPolicy.atOpen(journal, ids, idGen, clock, policy)
        // C3r: a reserve latched under these limits holds until the host changes them.
        val latched = TaskLimitControl.latched(journal, request.work)
        val budgetStop = state?.takeIf { it.phase == CampaignPhase.Ended && it.outcome == CampaignOutcome.BudgetExhausted }?.budgetStop
        // C14: what still holds a budget stop this open cannot continue, typed for the host.
        var limitHold: LimitHold? = null
        if (budgetStop == BudgetStop.CellCap) {
            val reason = "reopened after the run's cell cap: the cap counts per run"
            state = Lifecycle.apply(checkNotNull(state), contract, Transition.LimitRaised(reason)).also(campaigns::save)
            renewGrant(reason)
        }
        // C3: a task limit's stop continues the same attempt only once the host's limits leave room again — priced at the
        // call it was refused at — and says which limit still holds it otherwise.
        if (budgetStop?.taskLimit == true) {
            val spend = limitControl.spend(store, journal, request.work, limits)
            val decision = LimitRule.decide(limits, spend, LimitRule.nextCost(spend, TaskLimitControl.recorded(journal, request.work)?.status?.nextCallCost))
                .let { if (it == LimitDecision.Within) latched ?: it else it }
            if (decision == LimitDecision.Within) {
                val raised = "${LimitSessions.RAISED}: $limits; spent ${spend.requests} requests, ${spend.elapsedMillis} ms active" +
                    (spend.cost?.let { ", ${it.amount.toPlainString()} ${it.currency} (${spend.costBasis.wire})" } ?: "")
                state = Lifecycle.apply(checkNotNull(state), contract, Transition.LimitRaised(raised)).also(campaigns::save)
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, text = raised, at = clock.instant()))
                renewGrant(raised)
            } else {
                val (kind, why) = when (decision) {
                    is LimitDecision.Reserve -> decision.kind to decision.reason
                    is LimitDecision.Exhausted -> decision.kind to decision.reason
                    LimitDecision.Within -> error("unreachable")
                }
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, text = "${LimitSessions.STILL} (${kind.wire}) at $limits: $why", at = clock.instant()))
                events?.emit(AgentEvent.Budget.LimitReached(ids, kind.wire, TaskLimitControl.STOPPED_STAGE, why, LimitRule.status(limits, spend)))
                limitHold = LimitHold(BudgetStop.of(kind), LimitRule.status(limits, spend), why)
            }
        }

        // §13.1: every intent that never committed is an unknown outcome until reconciled; none is replayed.
        if (state?.phase in setOf(CampaignPhase.Opened, CampaignPhase.Running) && priorVersion != null && contract.version > priorVersion) {
            for (blocked in state!!.graph.increments.filter { it.status == IncrementStatus.Blocked }) {
                val ref = contract.requests.last().id
                state = Lifecycle.apply(state!!, contract, Transition.Unblocked(blocked.id, ref)).also(campaigns::save)
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(ref), text = "unblocked ${blocked.id} after host contract amendment v${contract.version}", at = clock.instant()))
            }
        }

        val unknown = intents.open().filter { it.ids.work == request.work }
        for (intent in unknown) {
            if (intent.status != IntentStatus.Unknown) intents.update(intent.intentId, IntentStatus.Unknown)
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(intent.intentId, intent.actionId), text = "open: intent ${intent.intentId} (${intent.argv.joinToString(" ")}) never committed · unknown_outcome", at = clock.instant()))
            events?.emit(AgentEvent.Run.Reconciled(ids, intent.actionId, "unknown_outcome"))
        }
        val stamp = try {
            stamper.report().candidateId
        } catch (input: UnreadableInput) {
            unreadable = unreadable ?: input
            shadow.records().last().stampId
        }
        restoreReceipts(checks, SqliteReceipts(store, clock), ids, stamp)
        if (external.isNotEmpty()) {
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = external.map { it.path }, text = "open: ${external.size} paths moved while closed (external) · reconciled @${stamp.hash8}", at = clock.instant()))
        }
        // §13.4: live background handles resolve to running, exited or lost by identity, never by pid alone.
        val handleRows = SqliteHandles(store, clock)
        val handles = handleRows.open().filter { it.ids.work == request.work }.map { handle ->
            val polled = runCatching { os.reattach(handle.proc).status }.getOrDefault(ProcStatus.Lost)
            val status = when (polled) {
                ProcStatus.Running -> "running"
                is ProcStatus.Exited -> "exited"
                ProcStatus.DeadlineExceeded -> "deadline_exceeded"
                ProcStatus.Cancelled -> "cancelled"
                ProcStatus.Lost -> "lost"
            }
            // A process known to be over no longer fences the workspace hand-off; running and lost ones still do (§13.1).
            if (polled.isTerminal && polled != ProcStatus.Lost) handleRows.save(handle.copy(proc = handle.proc.copy(status = polled), status = status))
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(handle.handleId, handle.actionId), text = "open: handle ${handle.handleId} (${handle.argv.joinToString(" ")}) $status · polled, never relaunched", at = clock.instant()))
            "${handle.handleId} $status"
        }
        // D-321: under Automatic, only outcomes the stamp fully observes close here; D-class, external and live/lost
        // background effects keep the §13.1 fence for the host.
        val automatic = if (effective.unknownOutcomeReconciliation != io.astrolabe.UnknownOutcomeReconciliation.Automatic) emptySet() else {
            val live = handleRows.open().map { it.actionId }.toSet()
            unknown.filter { (it.replaySafe || it.workspaceConfined) && it.actionId !in live }.map { intent ->
                val evidence = if (intent.workspaceConfined) "auto: workspace effects observed @${stamp.hash8}" else "auto: replay-safe read-only @${stamp.hash8}"
                intents.reconcile(intent.intentId, evidence)
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(intent.intentId, intent.actionId), text = "open: intent ${intent.intentId} reconciled automatically · $evidence", at = clock.instant()))
                events?.emit(AgentEvent.Run.Reconciled(ids, intent.actionId, "auto_reconciled"))
                intent.intentId
            }.toSet()
        }
        val reconciliation = Reconciliation(unknown.map { it.intentId }.filter { it !in automatic }, external, stamp, handles)
        // A cell still running in the stored state belonged to a controller that stopped mid-cell: it is lost.
        state?.running?.takeIf { state.phase == CampaignPhase.Running }?.let { running ->
            val checkpoint = SqliteCheckpoints(store, clock).latest(running.cell)
            journal.append(JournalEvent(idGen.next("ev"), ids, checkpoint?.turn, JournalKind.Reconcile, refs = listOf(running.cell.value), text = "open: cell ${running.cell.value} was running when its controller stopped; last checkpoint turn ${checkpoint?.turn ?: "none"} · lost", at = clock.instant()))
            state = Lifecycle.apply(state, contract, Transition.Lost(running.cell, checkpoint)).also(campaigns::save)
        }
        if (state?.phase == CampaignPhase.Opened) {
            state = Lifecycle.apply(state, contract, Transition.Reconciled(reconciliation.unknownOutcomes)).also(campaigns::save)
        }
        unreadable?.let { input ->
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, refs = listOf(input.path), text = "open: unreadable input ${input.path} · the campaign stops until it can be read", at = clock.instant()))
            if (state?.phase == CampaignPhase.Running && state.running == null) {
                state = Lifecycle.apply(state, contract, Transition.Stopped(CampaignOutcome.BlockedExternal, "unreadable input ${input.path}: ${input.message}")).also(campaigns::save)
            }
        }

        // §13.1: the old owner's unknown effects are reconciled above, before this writer is granted the workspace.
        val leases = Leases(store, clock)
        // D-171: another work's intents that never committed nor were reconciled fence a new holder (Fence.grant).
        val unreconciled = intents.open().map { it.intentId } + SqliteHandles(store, clock).open().map { it.handleId }
        val lease = leases.acquire(WORKSPACE, ids, "controller:${store.holder.pid}", leaseDuration, unreconciled)
        // C14: under the lease, the contract's tokens follow the host's policy upward only — not for a finished campaign or a
        // stop more tokens cannot lift — and a contract budget stop continues by its recorded cause, else says what holds it.
        if (ContractTokens.raisable(state)) ContractTokens.raise(contracts, journal, ids, idGen, clock, policy.tokens)
        if (budgetStop == BudgetStop.ContractBudget) {
            val current = checkNotNull(contracts.current(request.work))
            val held = ContractTokens.held(store, clock, current, state?.contractStop)
            if (held == null) {
                val reason = "${ContractTokens.CONTINUED} (${state?.contractStop?.cause?.wire}) at ${current.budget.tokens.value} tokens"
                state = Lifecycle.apply(checkNotNull(state), current, Transition.LimitRaised(reason)).also(campaigns::save)
                renewGrant(reason)
                state = Lifecycle.apply(checkNotNull(state), current, Transition.Reconciled(reconciliation.unknownOutcomes)).also(campaigns::save)
            } else {
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, text = "${ContractTokens.STILL}: ${held.second}", at = clock.instant()))
                limitHold = LimitHold(BudgetStop.ContractBudget, LimitRule.status(limits, limitControl.spend(store, journal, request.work, limits)), held.second, held.first)
            }
        }
        val prescan = impactPrescan.prescan
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = impactPrescan.blast, text = "open: impact ${impactPrescan.log}", at = clock.instant()))
        val selected = ShapeSelector.select(contract, prescan, effective.defaults.shapePolicy, policy.resumeExpected, capabilities = CAPABILITIES)
        events?.emit(AgentEvent.Campaign.Opened(ids, contract.requests.last().id, parentWork = contract.parentWork?.value))
        val inputs = when (selected) {
            is ShapeDecision.Selected -> selected.inputs
            is ShapeDecision.Unavailable -> selected.inputs
        }
        val shapeLog = "contract:v${contract.version} ${inputs?.log.orEmpty()} · ${impactPrescan.log}"
        events?.emit(AgentEvent.Campaign.ShapeSelected(ids, (selected as? ShapeDecision.Selected)?.shape?.name ?: "blocked", shapeLog))
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "open: shape ${(selected as? ShapeDecision.Selected)?.shape?.name ?: "blocked"} · $shapeLog", at = clock.instant()))
        // S0, S1 and S2 run here (D-170); S3 runs only behind its switch and the writer flag (D-183) and is never selected on this initial pass.
        val s3Runtime = effective.defaults.shapePolicy.s3Enabled && effective.flags.s3Writers
        val shape = when {
            selected is ShapeDecision.Selected && selected.shape == Shape.S3 && !s3Runtime ->
                ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.shape} selected (${selected.inputs?.log}); the S3 runtime needs shapePolicy.s3Enabled and flags.s3Writers (D-183)", selected.inputs)
            // The S2 review paths key on the contract's shape: a contract opened below S2 never skips its required review.
            selected is ShapeDecision.Selected && selected.shape == Shape.S2 && contract.shape < Shape.S2 ->
                ShapeDecision.Unavailable("shape S1+ unavailable: S2 selected (${selected.inputs?.log}) but contract v${contract.version} is ${contract.shape}; amend it to S2 (D-170)", selected.inputs)
            selected is ShapeDecision.Unavailable -> ShapeDecision.Unavailable("shape S1+ unavailable: ${selected.reason}", selected.inputs)
            else -> selected
        }
        if (shape is ShapeDecision.Unavailable && state?.phase == CampaignPhase.Running && state.running == null) {
            state = Lifecycle.apply(state, contract, Transition.Stopped(CampaignOutcome.BlockedExternal, shape.reason)).also(campaigns::save)
        }

        NoteHorizon(KbWriter(store, HeuristicEstimator(), clock), Notes(store), ids).reconcile(registry::version, mapOf("contract" to "v${contract.version}"))
        return OpenedCampaign(
            request, ids, store, os, workspace, registry, stamper, dirty, shadow, s0, atlas, derived.sniffed, commands,
            contracts, checks, rules, prime, kb, journal, intents, campaigns, reconciliation, prescan, impactPrescan, shape, state, refusal, owned,
            frozen, lease, leases, frozenNotes = Notes(store).all(), hostNotes = policy.hostNotes.filter { it.isNotBlank() }, limits = limits, limitHold = limitHold,
            unreadable = unreadable?.path,
        ).also {
            plugged[it] = layered
            it.limitState.reserve = latched
            it.limitState.reserveAnnounced = latched != null
            events?.emit(counting.counted(ids, CountedPhase.Open, opened, 0))
        }
    }

    /**
     * `Controller.runS0()` (§3.6, §3.7 in the S0 shape): compile the one ready increment, run one implementing
     * cell, reconcile and persist its return, verify the completion against current receipts (never the model's
     * word), commit it if the tree is still the one it is about, and finish at the final stamp — or stop with the
     * honest outcome `dispatch_outcome` gives (S0 has no continuation cell or replan, D-64). The ledger moves only
     * through [Transition.Committed] on a verifier-accepted completion. A direct cell's handoff is the one exception
     * (A-D.6): the same increment continues in an epoch, at most [maxHandoffs] times per grant.
     */
    @JvmOverloads
    public suspend fun runS0(
        campaign: OpenedCampaign,
        model: CellModel,
        authority: Authority = AutonomousAuthority(),
        syntax: SyntaxCheck = CliSyntax(campaign.os, campaign.workspace.root, campaign.store.layout.root.resolve("logs"), python = null, node = null),
        maxHandoffs: Int = DEFAULT_MAX_HANDOFFS,
    ): S0Run {
        require(maxHandoffs >= 0) { "maxHandoffs must be ≥ 0" }
        grant(campaign, maxHandoffs)
        val session = limitControl.begin(campaign)
        // C3: the host's answers are a person's wait, not active time.
        val host = limitControl.pausing(campaign, authority)
        try {
            behaviourSnapshot(campaign)
            val epochs = ArrayList<ResultPacket>()
            val result = spans?.span(Phase.Plan, campaign.ids) { span -> runS0(campaign, model, host, syntax, span, maxHandoffs = maxHandoffs, epochs = epochs) }
                ?: runS0(campaign, model, host, syntax, null, maxHandoffs = maxHandoffs, epochs = epochs)
            // A-D.6: the final report covers every epoch of this run, then the last exit.
            return finish(campaign, result, epochs + listOfNotNull(result.exit?.packet))
        } catch (input: UnreadableInput) {
            return unreadable(campaign, input)
        } finally {
            limitControl.end(campaign, session)
        }
    }

    /**
     * WD-05 (WF-4): a file of the tree another process holds stops the run where it was met — a fresh stamp after a
     * cell, a snapshot, a finalization — resumably and by its path, never as a failed run; the running cell, if any, is
     * reconciled first ([Lifecycle.unreadableSettlement]). A campaign that already ended keeps its outcome.
     */
    private fun unreadable(c: OpenedCampaign, input: UnreadableInput): S0Run {
        val state = c.state
        if (state == null || state.phase == CampaignPhase.Ended) return S0Run(state, null, null, null)
        state.running?.let { running -> c.advance(Lifecycle.unreadableSettlement(running.cell, SqliteCheckpoints(c.store, clock).latest(running.cell))) }
        return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "unreadable input ${input.path}: ${input.message}")), null, null, null)
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
     * frontier with unverified requirements never ends `completed`. [maxCells] bounds the campaign's cells; a cell that
     * continues a direct cell's handoff is not counted there but in the grant of [maxHandoffs] (A-D.6, owner №4).
     */
    @JvmOverloads
    public suspend fun run(
        campaign: OpenedCampaign,
        model: CellModel,
        authority: Authority = AutonomousAuthority(),
        syntax: SyntaxCheck = CliSyntax(campaign.os, campaign.workspace.root, campaign.store.layout.root.resolve("logs"), python = null, node = null),
        maxCells: Int = DEFAULT_MAX_CELLS,
        maxHandoffs: Int = DEFAULT_MAX_HANDOFFS,
    ): S0Run {
        require(maxCells >= 1) { "maxCells must be ≥ 1" }
        require(maxHandoffs >= 0) { "maxHandoffs must be ≥ 0" }
        // D-170: S2 is the S1 loop with the S2+ paths (increment review, delegation, campaign judge) switched on by the contract's shape.
        val shape = (campaign.shape as? ShapeDecision.Selected)?.shape
        if (shape != Shape.S1 && shape != Shape.S2 && shape != Shape.S3) return runS0(campaign, model, authority, syntax, maxHandoffs)
        grant(campaign, maxHandoffs)
        val session = limitControl.begin(campaign)
        val host = limitControl.pausing(campaign, authority)
        try {
            behaviourSnapshot(campaign)
            val packets = ArrayList<ResultPacket>()
            val result = spans?.span(Phase.Plan, campaign.ids) { span -> runS1(campaign, model, host, syntax, span, maxCells, packets, maxHandoffs) }
                ?: runS1(campaign, model, host, syntax, null, maxCells, packets, maxHandoffs)
            return finish(campaign, result, packets)
        } catch (input: UnreadableInput) {
            return unreadable(campaign, input)
        } finally {
            limitControl.end(campaign, session)
        }
    }

    /**
     * A-D.6: the handoff grant of this run — journaled when the attempt has none or an explicit resume is newer than its
     * latest — for a campaign that may still dispatch. Only a direct main line hands off, so a structured one journals none.
     */
    private fun grant(c: OpenedCampaign, maxHandoffs: Int) {
        if (c.stop != null || c.attempt.config.protocol != Protocol.Direct) return
        Handoffs(c.journal, idGen, clock, c.ids).grant(maxHandoffs)
    }

    /** A handed-off cell whose increment waits for its epoch (A-D.6): the kept record and the packet the epoch carries from. */
    private class Epoch(val kept: ReturnedHandoff, val packet: ResultPacket)

    /**
     * The epoch the campaign owes, or `null`: a returned handoff whose cell is still its increment's last, that increment
     * in progress and no split requested by the cell — a split replans first and is never continued as an epoch.
     * [memory] holds this run's packets; after a reopen the packet comes from the kept record.
     */
    private fun pendingEpoch(c: OpenedCampaign, memory: List<ResultPacket>): Epoch? {
        val state = checkNotNull(c.state)
        val kept = ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).lastOrNull { kept ->
            val increment = state.graph.increments.firstOrNull { it.id == kept.incrementId }
            increment?.status == IncrementStatus.InProgress && increment.cells.lastOrNull() == kept.cell &&
                state.cells.firstOrNull { it.cell == kept.cell }?.status == CellStatus.Partial
        } ?: return null
        if (SqliteSplitRequests(c.store, idGen, clock).forPlanRole(c.ids.work).any { it.cell == kept.cell }) return null
        return Epoch(kept, memory.lastOrNull { it.ids.context == kept.cell } ?: keptPacket(c, kept))
    }

    private fun keptPacket(c: OpenedCampaign, kept: ReturnedHandoff): ResultPacket {
        val increment = checkNotNull(c.state).graph.increments.first { it.id == kept.incrementId }
        val register = SqliteRegisterVersions(c.store, clock).latest(kept.cell) ?: Register.empty(kept.cell, increment.id, increment.title)
        return kept.packet(c.ids, register)
    }

    /**
     * The successor of [epoch]'s cell (A-D.6): the id its spend already names — a crash between the spend and the
     * `Dispatched` row is replayed without a second charge and without asking the remainder — or a new spend from the grant
     * in force. With no remainder, the stop the campaign takes instead.
     */
    private fun successor(c: OpenedCampaign, epoch: Epoch, maxHandoffs: Int): Pair<ContextId?, Transition.Stopped?> {
        val handoffs = Handoffs(c.journal, idGen, clock, c.ids)
        handoffs.spendOf(epoch.kept.cell)?.let { return it.to to null }
        val grant = handoffs.grant(maxHandoffs)
        val spent = handoffs.spends(grant.id).size
        if (spent >= grant.limit) {
            val unverified = checkNotNull(c.state).ledger.unfinished().size
            // The host sees the per-run cap it already continues (`cell_cap`): a reopen renews the grant, never a dead end.
            return null to Transition.Stopped(CampaignOutcome.BudgetExhausted, "the campaign's ${grant.limit} handoffs are spent with $unverified requirements unverified", budget = BudgetStop.CellCap)
        }
        val to = ContextId(idGen.next("cell"))
        handoffs.spend(grant, spent + 1, epoch.kept.cell, to, epoch.kept.incrementId, epoch.kept.cause)
        return to to null
    }

    private suspend fun reassessBlocked(c: OpenedCampaign, authority: Authority) {
        for (increment in c.state?.graph?.increments.orEmpty().filter { it.status == IncrementStatus.Blocked }) {
            val checkpoint = increment.cells.lastOrNull()?.let { SqliteCheckpoints(c.store, clock).latest(it) }
            val question = io.astrolabe.event.Question(idGen.next("unblock"), c.contract.version, c.ids,
                "May ${increment.id} resume? Resolve its prerequisite and provide the answer or evidence: ${checkpoint?.reason.orEmpty()}")
            val answer = authority.ask(question) ?: continue
            if (answer.questionId != question.id || answer.contractRevision != c.contract.version || answer.text.isBlank()) continue
            if (answer.changesRequirements) {
                c.contracts.amendByUser(c.ids.work, "Resume ${increment.id}: ${answer.text}")
                c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Reconcile, refs = listOf(question.id), text = "host unblocked ${increment.id}: ${answer.text}", at = clock.instant()))
            } else {
                // D-317 (§4.1): a factual answer is evidence, not an amendment, so assessments bound to this version stay valid.
                c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Reconcile, refs = listOf(question.id, increment.id),
                    text = "$HOST_ANSWER${increment.id} (factual, contract stays v${c.contract.version}): ${answer.text}", at = clock.instant()))
            }
            c.advance(Transition.Unblocked(increment.id, question.id))
        }
    }

    /** D-317: the factual host answers that unblocked [increment]; no amendment carries them, so its cells pin them. */
    private fun hostAnswers(c: OpenedCampaign, increment: Increment): List<String> =
        c.journal.events(JournalScope(c.ids.work, kinds = setOf(JournalKind.Reconcile)))
            .filter { increment.id in it.refs && it.text.startsWith(HOST_ANSWER) }.map { it.text }

    private suspend fun runS1(c: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, maxCells: Int, packets: MutableList<ResultPacket>, maxHandoffs: Int): S0Run {
        c.stop?.let { return S0Run(c.state, null, null, null) }
        c.refusal()?.let { return S0Run(c.advance(Transition.Stopped(stopOutcome(c), "nothing dispatched: $it")), null, null, null) }
        // D-340: a completion waiting for a decision is settled first — no cell, no budget check, no model call.
        var resumed: CompletionResult? = null
        // A continuation of a red increment never drops below the tier its failing cell ran at (§11.1).
        val tiers = HashMap<String, Tier>()
        // §11.3: verified failures escalate with evidence, at most budget.attempts per increment, then blocked (P4.5.2).
        val attempts = IncrementAttempts(c.journal, idGen, clock)
        // §13.1–§13.3 (D-171, D-254): verified failures go through the ladder and the campaign's guards, rebuilt from the journal.
        val recovery = CampaignRecovery(c.journal, idGen, clock, c.ids.work, GuardLimits.of(c.attempt.config.defaults))
        val review: suspend (Increment, ReturnedCompletion) -> ReviewOutcome? = { increment, kept ->
            if (c.contract.shape >= Shape.S2 && c.refusal() == null) incrementReview(c, increment, kept, model, authority, syntax, span) else null
        }
        val refused: suspend (Increment, ReturnedCompletion, List<String>) -> CampaignState? = { increment, kept, missing ->
            kept.tier?.let { tiers[increment.id] = it }
            verifiedFailure(c, c.contract, recovery, attempts, c.ids.copy(context = kept.cell), increment, missing, kept.register, kept.receipts, kept.tier, kept.profile, model, authority, syntax, span)
        }
        when (val pending = resumePending(c, authority) ?: resumeReturned(c, authority, refused, review)) {
            null, Resumed.Continue -> Unit
            is Resumed.Committed -> resumed = pending.result
            is Resumed.Stopped -> return S0Run(pending.state, null, null, null)
        }
        // C3: the plan cell (first or replan) is dispatched only within the task limits' working part.
        if (checkNotNull(c.state).ledger.unfinished().isNotEmpty()) limitStop(c, authority)?.let { return S0Run(it, null, resumed, null) }
        val pendingSplits = SqliteSplitRequests(c.store, idGen, clock).forPlanRole(c.ids.work).filter { split ->
            c.state?.graph?.increments?.any { it.id == split.split.increment && split.cell in it.cells && it.status !in setOf(IncrementStatus.Verified, IncrementStatus.Cancelled) } == true
        }
        if (pendingSplits.isNotEmpty()) plan(c, model, authority, syntax, span, packets, pendingSplits)?.let { return S0Run(onLimit(c, authority) ?: c.advance(it), null, null, null) }
        reassessBlocked(c, authority)
        val opened = checkNotNull(c.state)
        check(opened.phase == CampaignPhase.Running && opened.running == null) { "run needs a reconciled campaign with no running cell; it is ${opened.phase}" }
        // §4.2: the first cell of S1 is the plan cell; the placeholder graph is replaced once, before any dispatch.
        var s3: S3Admission? = null
        if (opened.graph.increments.none { it.cells.isNotEmpty() || it.status != IncrementStatus.Pending }) {
            // planCell = WhenNeeded: a contract that already is the plan installs G_single(C) without a model call.
            val trivial = if (c.attempt.config.defaults.shapePolicy.planCell == PlanCellPolicy.WhenNeeded) PlanNeed.trivialGraph(c.contract, c.kb.contractAnchors()) else null
            if (trivial != null) {
                c.checks.synchronizeAcceptance(c.contract)
                c.advance(Transition.Planned(trivial))
                c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary,
                    text = "plan cell skipped: ${PlanNeed.reason(c.contract)}; single increment ${ShapeSelector.SINGLE}", at = clock.instant()))
            } else run {
                // Task-workflow §3.1: an S1 contract that is otherwise its own plan, but whose acceptance is regression only.
                PlanNeed.regressionOnly(c.contract)?.let { why ->
                    c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, text = "plan cell runs: $why", at = clock.instant()))
                }
                plan(c, model, authority, syntax, span, packets)
            }?.let { stop ->
                // C3: a plan cell the task limits ended stops on the limit, so a raised limit resumes it; any other stop keeps its cause.
                onLimit(c, authority)?.let { return S0Run(it, null, null, null) }
                val outcome = if (c.refusal() != null) stopOutcome(c) else stop.outcome
                return S0Run(c.advance(Transition.Stopped(outcome, stop.reason, budget = stop.budget.takeIf { outcome == CampaignOutcome.BudgetExhausted },
                    contract = stop.contract.takeIf { outcome == CampaignOutcome.BudgetExhausted })), null, null, null)
            }
            // §3.5 select_shape(contract, impact, plan): the S3 branch reads the admitted plan's records (D-183, P5.8.1).
            s3 = S3Intake.admit(c, writerEstimates(c, model), CAPABILITIES, events, idGen, clock).takeIf { it.units.isNotEmpty() }
        }
        intake(c)?.let { return S0Run(it, null, resumed, null) }
        var last = S0Run(c.state, null, resumed, null)
        var cells = 0
        val writerExits = ConcurrentHashMap<String, CellExit>()
        // §6.6 `[O]`: boundary pre-compilation only under the frozen `precompile` flag; off, nothing below runs.
        val precompile = if (c.attempt.config.flags.precompile) Precompile(c.journal, idGen, clock) else null
        var closed: Pair<ContextId, Long>? = null
        var lastKey: CacheKey? = null
        while (true) {
            c.refusal()?.let { return last.copy(state = c.advance(Transition.Stopped(stopOutcome(c), "dispatch refused: $it"))) }
            // Task-workflow §2.4: a message recorded during this run gets its work at the next boundary.
            intake(c)?.let { return last.copy(state = it) }
            val state = checkNotNull(c.state)
            val contract = c.contract
            val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c), retryCandidates = c.store.layout.candidates)
            val unverified = state.ledger.unfinished()
            if (unverified.isEmpty()) return last.copy(state = stopOrFinish(c, "requirements remain unverified", scheduler, campaign = true, authority = campaignJudge(c, authority, model, syntax, span)))
            // §10.4: ready S3 units run as parallel writers and integrate once; a unit that does not integrate turns S3 off.
            val batch = s3?.batch(state.graph, contract, c.attempt.config.defaults.parallelCells).orEmpty()
            if (s3 != null && batch.size >= 2 && cells + batch.size <= maxCells) {
                limitStop(c, authority)?.let { return last.copy(state = it) }
                cells += batch.size
                val reviews = if (contract.shape >= Shape.S2) { increment: Increment -> reviewCell(c, increment, model, authority, syntax, span) } else null
                val round = S3Round(c, EnvFingerprint.compute(env), idGen, clock, events, writerCell(c, model, authority, syntax, span, writerExits), writerExits, reviews).run(batch, s3.estimates, packets)
                snapshot(c)
                when (round) {
                    is S3Result.Stopped -> return S0Run(c.advance(Transition.Stopped(round.outcome, round.reason)), round.exit, null, null)
                    is S3Result.Integrated -> {
                        if (round.returned.isNotEmpty()) s3 = null
                        last = S0Run(c.state, round.exit, null, null)
                        continue
                    }
                }
            }
            // A-D.1: the main line's role, read once at the cell's start from the attempt's protocol and the contract's shape.
            val role = mainLine(c, contract)
            val frontier = state.graph.readyFrontier(contract, state.graph.increments.size)
            // A-D.6: a handed-off increment continues in its epoch before any other ready increment.
            val epoch = pendingEpoch(c, packets)?.takeIf { e -> frontier.any { it.id == e.kept.incrementId } }
            // §11.4 ordering hint (P4.5.3): among ready increments, the one sharing the last cell's prefix goes first.
            val ready = epoch?.let { e -> frontier.first { it.id == e.kept.incrementId } } ?: CellOrder.next(frontier, lastKey, role) { inc ->
                listOfNotNull(tiers[inc.id], attempts.tier(inc.id), FunctionTable.DEFAULT.row(RoutingFunction.Implementing).defaultTier, Router.riskFloor(inc.risk ?: contract.risk, null)).max()
            }
            if (ready == null) {
                // FX-42: verified work is never re-executed; its regression evidence is refreshed from current receipts.
                refreshRegressions(c, scheduler, authority)?.let { return last.copy(state = it) }
                if (checkNotNull(c.state).ledger.unfinished().size < unverified.size || checkNotNull(c.state).graph.readyFrontier(c.contract, 1).isNotEmpty()) continue
                return last.copy(state = stopOrFinish(c, "no ready increment with ${unverified.size} requirements unverified: an empty frontier never means completed"))
            }
            // C3 (plan §4.6): no cell starts once the task limits' working part is spent; the rest is held for verifying and
            // reporting. Regression refresh and the final acceptance above are the harness's own work and still run.
            limitStop(c, authority)?.let { return last.copy(state = it) }
            // A-D.6: an epoch is paid from the handoff grant, never from the cell cap.
            if (epoch == null && cells >= maxCells) {
                return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BudgetExhausted,
                    "the run's $maxCells cells are spent with ${unverified.size} requirements unverified; reopen the task to continue with a fresh cap", budget = BudgetStop.CellCap)))
            }
            attempts.exhausted(c.ids.work, ready.id, contract.budget.attempts)?.let {
                return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it)))
            }
            if (epoch == null) cells += 1
            // §6.2: a continuation starts from the previous cell's validated register, seeds and packet — never its transcript.
            // Task workflow §4.1–§4.5: from the store — the increment's previous cell, the last closed increment, or the parent.
            val crossing = crossing(c, ready, role, handoff = epoch != null)
            val carry = crossing?.carry
            val seeds = carry?.let { Seeds.render(it.seeds, c.registry::read) }
            val resume = resumeNote(c, ready, carry)
            val knowledge = knowledge(c, ready, role, model, touched = carry?.seeds.orEmpty().map { it.path }.toSet())
            val inputs = CompileInputs(carry = carry, seeds = seeds, currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex, skills = knowledge.skills, skillConflicts = knowledge.skillConflicts)
            val (reworkLines, reworkRecords) = reworkNotes(c, ready)
            val pinned = listOfNotNull(resume, crossing?.note, attempts.line(ready.id)) + recovery.lines(ready.id) + hostAnswers(c, ready) + reworkLines
            val compiler = Compiler(model.estimator, c.attempt.config)
            // §6.6: a pre-compiled [K] is served for cell_end(next_increment) only, on a full-fingerprint and coverage match.
            val take = precompile?.let { p ->
                if (ready.cells.isNotEmpty()) {
                    p.discard("${ready.id} continues; pre-compilation applies to the next increment only")
                    null
                } else if (carry != null) {
                    // Owner decision (W9): a K pre-built before the boundary cannot hold the carry the boundary produces
                    // (task-workflow §4.3); the layer serves nothing here and the cell compiles fresh with its carry.
                    p.discard("${ready.id} starts from the carry of ${carry.source ?: carry.register.cell.value}; pre-compilation serves no carried increment")
                    null
                } else {
                    p.take(fingerprint(c, contract, ready, c.stamper.report().candidateId, model, inputs, null, pinned)) { compiled ->
                        (compiled as? Compiled.Ready)?.let { compiler.coverage(contract, ready, it.k, c.prime, pinned, inputs) }.orEmpty()
                    }
                }
            }
            // A-D.6: an epoch is routed with the function recorded for the cell it continues.
            val function = epoch?.kept?.function ?: if (ready.cells.isEmpty()) RoutingFunction.Implementing else RoutingFunction.Continuation
            val routing = route(c, function, ready, model, listOfNotNull(tiers[ready.id], attempts.tier(ready.id)).maxOrNull(), take?.compiled) { bound ->
                Compiler(bound.estimator, c.attempt.config).compile(ready, contract, bound.profile, role, c.prime, maxOutputTokens = bound.maxOutputTokens, inputs = inputs)
            }
            val compiled = routing.compiled
            val cellModel = routing.model
            closed?.let { (cell, at) -> precompiles.record(PrecompileSample(cell, ready.id, take?.outcome ?: PrecompileOutcome.None, take?.reason, (precompiles.now() - at).coerceAtLeast(0))) }
            closed = null
            // §6.5: why this context is built — a new increment after a closed one, a partial's continuation, or a resume.
            val boundaryReason = if (epoch != null) BoundaryReason.Epoch else when (ready.cells.lastOrNull()?.let { previous -> state.cells.firstOrNull { it.cell == previous }?.status }) {
                null, CellStatus.Completed -> BoundaryReason.Done
                CellStatus.Partial, CellStatus.Blocked -> BoundaryReason.Partial
                CellStatus.Running, CellStatus.Failed, CellStatus.Cancelled -> BoundaryReason.Resume
            }
            when (compiled) {
                is Compiled.Ready -> Unit
                is Compiled.NeedsRescoping -> return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "NEEDS_RESCOPING_OR_LARGER_PROFILE for ${ready.id}: ${compiled.reason} — ask the plan role for an increment_split${profileBound(c, cellModel)}")), compiled = compiled)
                is Compiled.NeedsEvidence -> return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "NEEDS_MORE_EVIDENCE for ${ready.id}: ${compiled.missing}")), compiled = compiled)
            }
            // FX-32: an unaffordable tier is refused, never clamped; the campaign stops on the router's options.
            routing.refused?.let { return last.copy(state = c.advance(contractBudget(c, it.reason, null)), compiled = compiled) }
            routing.selected?.let { tiers[ready.id] = it.tier }
            lastKey = routing.selected?.let { CellOrder.key(role, it.tier) }
            val cellId = if (epoch == null) ContextId(idGen.next("cell")) else successor(c, epoch, maxHandoffs).let { (to, stop) ->
                to ?: return last.copy(state = c.advance(checkNotNull(stop)), compiled = compiled)
            }
            events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, ready.id))
            val dispatched = c.advance(Transition.Dispatched(ready.id, cellId, epoch = epoch != null))
            spendReworks(c, cellId, reworkRecords)
            val increment = dispatched.graph.increments.first { it.id == ready.id }
            val register = crossing?.takeIf { it.continuation }?.carry?.register?.copy(cell = cellId, increment = increment.id, incrementTitle = increment.title)
            // The pre-compile job lives in this scope: joined at cell close, cancelled with the cell (§6.6).
            val run = coroutineScope {
                val trigger = precompile?.let { p -> trigger(c, this, p, cellId, increment, cellModel) }
                runCell(c, cellId, increment, role, cellModel, authority, syntax, compiled, span, dispatched.ledger, register, seeds?.shown.orEmpty(), pinned = pinned, boundary = boundaryReason, inputs = inputs, precompile = trigger, rework = reworkLines.isNotEmpty(), continues = epoch?.kept).also { run ->
                    if (run.exit !is CellExit.Completed) precompile?.discard("cell ${cellId.value} ended ${run.exit?.let { it::class.simpleName!!.lowercase() } ?: "cancelled"}: never a continuation of a red increment")
                }
            }
            if (precompile != null) closed = cellId to precompiles.now()
            val exit = run.exit
            // A completed cell's outcome is recorded once the verifier resolved it (A3); a handoff is never recorded (A-D.6).
            if (exit !is CellExit.Completed && !exit.handoff) routing.selected?.let { router.record(it, outcomeOf(exit)) }
            if (exit == null) {
                snapshot(c)
                c.advance(Transition.Interrupted(checkNotNull(run.checkpoints.latest(cellId)) { "a cancelled cell settles its checkpoint" }))
                return S0Run(c.advance(Transition.Stopped(CampaignOutcome.Cancelled, checkNotNull(c.cancellation.reason))), null, null, compiled)
            }
            packets += exit.packet
            snapshot(c)
            val kept = returned(c, run.ids, exit, routing.selected, compiled.k.ledger, function, run.impact)
            refreshPrescan(c, run.ids, exit.checkpoint.touched, exit.turns)?.let { return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it)), exit, null, compiled) }
            boundary(c, cellId, RebuildReason.CellEnd(if (exit is CellExit.Completed) RebuildReason.CellEnd.Next.NextIncrement else RebuildReason.CellEnd.Next.Continuation))
            val stampNow = c.stamper.report(fresh = true).candidateId
            if (exit is CellExit.Completed && exit.answer != null) {
                routing.selected?.let { router.record(it, RoutingOutcome.Accepted) }
                return S0Run(c.advance(Transition.Answered(stampNow, exit.answer)), exit, null, compiled)
            }
            // §8.7/§8.8: in S2+ a required increment review must approve before the increment closes; none owed ⇒ null.
            // A completion that can no longer publish (cancelled, lease lost) is archived below, never reviewed (D-170).
            // D-343: an unavailable or declined increment review is a result like any other — unverified or rejected — never a block.
            val review = if (kept != null && c.contract.shape >= Shape.S2 && c.refusal() == null) incrementReview(c, increment, kept, cellModel, authority, syntax, span) else null
            val completion = kept?.let {
                val returned = checkNotNull(c.state).graph.increments.first { it.id == increment.id }
                verify(c, kept, returned, stampNow, currencies(c, run.scheduler, stampNow), review)
            }
            if (exit is CellExit.Completed) routing.selected?.let { router.record(it, outcomeOf(exit, completion)) }
            last = S0Run(c.state, exit, completion, compiled)
            val splits = SqliteSplitRequests(c.store, idGen, clock).forPlanRole(c.ids.work).filter { it.cell == cellId && it.split.increment == increment.id }
            if (splits.isNotEmpty() && exit !is CellExit.Completed && cells < maxCells && c.refusal() == null) {
                limitStop(c, authority)?.let { return last.copy(state = it) }
                cells += 1
                plan(c, model, authority, syntax, span, packets, splits)?.let { return last.copy(state = onLimit(c, authority) ?: c.advance(it)) }
                s3 = null
                continue
            }
            when (val disposition = Lifecycle.disposition(exit, completion)) {
                is Disposition.Close -> {
                    commit(c, run.ids, exit.turns, increment, disposition.accepted, stampNow)?.let { return last.copy(state = it) }
                    cadence(c)
                }
                // S1: a partial continues the same increment from its carry-forward; the cell cap bounds it (D-70).
                // §11.3: a refused or stalled completion is a verified failure of the increment's attempt.
                is Disposition.Continue -> IncrementAttempts.verifiedFailure(exit, completion)?.let { missing ->
                    verifiedFailure(c, contract, recovery, attempts, run.ids, increment, missing, exit.register, exit.packet.receipts, routing.selected?.tier, routing.selected?.profile?.id, cellModel, authority, syntax, span)
                        ?.let { return last.copy(state = it) }
                }
                is Disposition.Stop -> {
                    if (completion !is CompletionResult.Pending) return last.copy(state = c.advance(stopped(exit, disposition)))
                    when (val settled = settle(c, run.ids, increment, checkNotNull(kept), completion, authority)) {
                        is Settled.Commit -> {
                            val returned = checkNotNull(c.state).graph.increments.first { it.id == increment.id }
                            commit(c, run.ids, exit.turns, increment, Verifier().commit(completion.proposal, c.contract, returned, kept.cell, checkNotNull(c.state).ledger, settled.resolved), stampNow)?.let {
                                closePending(c, run.ids, settled.pending, PendingStatus.Void, "publication refused")
                                return last.copy(state = it)
                            }
                            closePending(c, run.ids, settled.pending, PendingStatus.Applied, settled.why)
                            cadence(c)
                        }
                        // D-340: the loop's next cell continues the increment with the decider's text pinned.
                        is Settled.Rework -> closePending(c, run.ids, settled.pending, PendingStatus.Void, "rework requested by ${settled.record.decision.by}")
                        is Settled.Wait -> return last.copy(state = c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, settled.reason, settled.code)))
                        is Settled.Void -> closePending(c, run.ids, settled.pending, PendingStatus.Void, settled.reason)
                    }
                }
            }
            last = last.copy(state = c.state)
        }
    }

    /**
     * §11.3, §13.1–§13.3: a refused or stalled completion of [increment] is a verified failure of its attempt — the
     * campaign's guards, the scoped repair, the escalation ladder and the alternative — whether the cell returned live
     * or its kept return was verified again on open (P8.C.8). The stop state when a guard or the ladder stops the
     * campaign; `null` lets the continuation cell run.
     */
    private suspend fun verifiedFailure(
        c: OpenedCampaign, contract: Contract, recovery: CampaignRecovery, attempts: IncrementAttempts, ids: Identities, increment: Increment, missing: List<String>,
        register: Register, receipts: List<String>, tier: Tier?, profile: String?, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?,
    ): CampaignState? {
        val kind = CampaignRecovery.classify(increment.accept.flatMap { c.checks.forAcceptance(it) }.mapNotNull { it.last?.outcome })
        val hypothesis = CampaignRecovery.hypothesis(register)
        val routed = recovery.failed(ids, increment.id, kind, missing.joinToString("; "), receipts, hypothesis, c.stamper.report().candidateId)
        (routed.verdict as? GuardVerdict.Trip)?.let { return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, it.line)) }
        if (routed.recovery is Recovery.Repair) repair(c, recovery, ids, increment, routed, model, authority, syntax, span)
        when (val step = attempts.refused(ids, increment.id, contract.budget.attempts, tier, profile, register, missing, receipts, kind)) {
            is EscalationStep.Ask -> return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, step.question))
            is EscalationStep.Blocked -> return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, step.reason))
            is EscalationStep.Escalate, is EscalationStep.NotEscalated -> Unit
        }
        recovery.alternative(ids, attempts.allowance(c.ids.work, increment.id, contract.budget.attempts), register, receipts, contract.version, hypothesis)
        return null
    }

    /** D-344, task-workflow §3.5: why this campaign may not end with an answer, or `null` — [Answers]' one predicate. */
    private fun answerable(c: OpenedCampaign): String? = Answers.refusal(c)

    /**
     * C3 (plan §4.6) at a cell boundary: once the task limits leave no working part, nothing more is dispatched. The
     * tree is checkpointed, the verified increments are re-accepted on current receipts (the ordinary acceptance, nothing
     * re-executed) and the best verified candidate is named — the latest stamp the acceptance verified on the main line,
     * with what was verified there, what only at an earlier stamp and what a decider accepted without verification; the
     * user's tree is never replaced. The campaign ends `budget_exhausted` with its [BudgetStop] and the reserve kept.
     * `null` when the limits leave room, or a cancellation or a lost lease decides the stop instead.
     */
    /** C3: when the attempt's balance profile narrowed [model]'s window, the rescoping stop names that bound. */
    private fun profileBound(c: OpenedCampaign, model: CellModel): String {
        val window = c.attempt.config.profiles[model.profile.id]?.capabilities?.contextLimitTokens ?: return ""
        val bound = model.profile.capabilities.contextLimitTokens
        return if (bound >= window) "" else " (the ${c.attempt.config.balance.wire} balance profile bounds ${model.profile.id}'s window to $bound of $window tokens; a Balanced task uses all of it)"
    }

    private suspend fun onLimit(c: OpenedCampaign, authority: Authority): CampaignState? = if (c.limitState.block == null) null else limitStop(c, authority)

    /**
     * C14: a `contract_budget` stop with what spent the budget — [partial] the main-line cell's reason, `null` a router
     * refusal — and the contract's tokens then: what a reopen reads to continue it, named in the reason too.
     */
    private fun contractBudget(c: OpenedCampaign, reason: String, partial: PartialReason?): Transition.Stopped {
        val stop = ContractBudgetStop(ContractTokens.cause(c.store, clock, c.contract, partial), c.contract.budget.tokens.value)
        return Transition.Stopped(CampaignOutcome.BudgetExhausted, "$reason — contract budget (${stop.cause.wire})", budget = BudgetStop.ContractBudget, contract = stop)
    }

    private suspend fun limitStop(c: OpenedCampaign, authority: Authority): CampaignState? {
        if (c.refusal() != null) return null
        val (spend, decision, price) = limitControl.boundary(c)
        val (kind, why) = when (decision) {
            LimitDecision.Within -> return null
            is LimitDecision.Reserve -> decision.kind to decision.reason
            is LimitDecision.Exhausted -> decision.kind to decision.reason
        }
        snapshot(c)
        reaccept(c, scheduler(c), authority, settle = false)
        val state = checkNotNull(c.state)
        val contract = c.contract
        val current = c.stamper.report(fresh = true).candidateId
        val done = state.graph.increments.filter { it.status == IncrementStatus.Verified }.mapNotNull { inc -> state.graph.evidence[inc.id]?.let { inc to it } }
        // The main line only moves forward: the latest accepted stamp carries the work of every earlier one.
        val best = done.maxByOrNull { (_, e) -> if (e.stamp == current) state.cells.size else state.cells.indexOfFirst { it.cell == e.contextId } }?.second?.stamp
        val active = state.graph.increments.filter { it.status != IncrementStatus.Cancelled }
        val complete = contract.requirements.filter { r -> active.filter { r.id in it.requirementIds }.let { it.isNotEmpty() && it.all { inc -> inc.status == IncrementStatus.Verified } } }.map { it.id }
        val accepted = contract.requirements.filter { r ->
            r.id in complete && done.any { (inc, e) -> r.id in inc.requirementIds && e.provenance.any { it.item in r.acceptance && it.how == io.astrolabe.verify.ProvenanceKind.Accepted } }
        }.map { it.id }
        val atBest = best?.let { s -> state.graph.ledger(contract, s).entries.values.filter { it.status == io.astrolabe.contract.RequirementStatus.Verified }.map { it.requirementId } }.orEmpty()
        val verified = atBest.filter { it !in accepted }.sorted()
        val earlier = complete.filter { it !in atBest && it !in accepted }.sorted()
        val stop = LimitStop(kind, why, LimitRule.status(c.limits, spend, price), best.takeIf { verified.isNotEmpty() || earlier.isNotEmpty() || accepted.isNotEmpty() },
            verified, best == current, current, earlier, accepted.sorted())
        val parts = listOfNotNull(
            verified.takeIf { it.isNotEmpty() }?.let { "verified ${it.joinToString(", ")}" },
            earlier.takeIf { it.isNotEmpty() }?.let { "verified at an earlier stamp, not re-checked here: ${it.joinToString(", ")}" },
            stop.accepted.takeIf { it.isNotEmpty() }?.let { "accepted without verification: ${it.joinToString(", ")}" },
        ).joinToString("; ")
        val named = when {
            stop.bestCandidate == null -> "no verified candidate: nothing is verified yet"
            stop.workingTree -> "best verified candidate @${stop.bestCandidate.hash8} ($parts) — the tree as left"
            else -> "best verified candidate @${stop.bestCandidate.hash8} ($parts); the tree as left @${current.hash8} holds unverified changes after it and is not replaced"
        }
        val open = contract.requirements.map { it.id }.filter { it !in complete }
        val reason = "${TaskLimitControl.LIMIT_REACHED} (${kind.wire}): $why · $named" + (if (open.isEmpty()) "" else " · unverified: ${open.joinToString(", ")}") +
            " · ${TaskLimitControl.RAISE_TO_CONTINUE}"
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = listOfNotNull(stop.bestCandidate?.digest?.hex),
            text = "${LimitSessions.REACHED} (${kind.wire}): $named", payload = TaskLimitControl.JSON.encodeToJsonElement(LimitStop.serializer(), stop), at = clock.instant()))
        events?.emit(AgentEvent.Budget.LimitReached(c.ids, kind.wire, TaskLimitControl.STOPPED_STAGE, why, stop.status, stop.bestCandidate?.toString()))
        return c.advance(Transition.Stopped(CampaignOutcome.BudgetExhausted, reason, budget = BudgetStop.of(kind)))
    }

    /** §7.3 cadence: the full suite every K verified increments; a red result is a regression on record. */
    private suspend fun cadence(c: OpenedCampaign) {
        val verified = checkNotNull(c.state).graph.increments.count { it.status == IncrementStatus.Verified }
        if (verified % c.attempt.config.defaults.fullSuiteCadence == 0 && checkNotNull(c.state).ledger.unfinished().isNotEmpty()) fullSuite(c, "cadence after $verified verified increments")
    }

    /**
     * The scoped capsule repair the ladder granted (§13.2 step 4, D-137): the fresh repair cell behind [CellRepairRunner],
     * routed by the `RepairHelper` row; its `fixed` claim counts only when the increment's acceptance, re-run by the
     * harness, is green at the current stamp. Below S2 the helper is not called and the outcome is an escalation.
     */
    private suspend fun repair(c: OpenedCampaign, recovery: CampaignRecovery, ids: Identities, increment: Increment, routed: RoutedFailure, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?) {
        val config = c.attempt.config
        val acceptance = OriginalAcceptance {
            harnessVerify(c, ids.copy(context = ContextId(idGen.next("repair-check"))), "repair-check", """{"what":"acceptance","ids":[${increment.accept.joinToString(",") { "\"$it\"" }}]}""")
            val stamp = c.stamper.report(fresh = true).candidateId
            val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c), retryCandidates = c.store.layout.candidates)
            val checks = increment.accept.flatMap { c.checks.forAcceptance(it) }
            val green = checks.isNotEmpty() && checks.all { scheduler.currency(it, stamp).certifies }
            AcceptanceCheck(green, "acceptance ${increment.accept.joinToString(", ")} ${if (green) "green" else "not green"} at @${stamp.hash8}")
        }
        val helper = Repair(router, CellRepairRunner(childCell(c, increment, model, authority, syntax, span), idGen, c.cancellation, model.estimator), acceptance, model.estimator, config.defaults, RoleTexts.worded(Roles.repair, config.role(Roles.repair.name)), io.astrolabe.route.RoutingLog(c.store, clock), ids)
        val tiered = config.tierTable.profiles.isNotEmpty() && config.tierTable.profileIds.all { it in config.profiles }
        val policy = RoutingPolicy(if (tiered) config.tierTable else TierTable.single(model.profile.id), if (tiered) config.profiles else mapOf(model.profile.id to model.profile), RoutingBudget(remainingCost = Accounting(c.store, clock).remainingCost(c.ids.work, c.contract.budget.cost)), configuredEffort = model.effort)
        val packet = RoutingPacket(increment.risk ?: c.contract.risk, CellRepairRunner.DEFAULT_BUDGET.tokens.value, model.maxOutputTokens, featureClass = "repair:${increment.id}")
        val versions = c.atlas.rows.map { it.path }.filter { p -> increment.writeScope.any { PathPattern.matches(it, p) } }.mapNotNull { p -> c.registry.version(p)?.let { p to it } }.toMap()
        recovery.repair(ids, increment.id, routed, increment.accept, versions, c.contract.budget.tokens, c.contract.shape, packet, policy, helper)
    }

    /**
     * §3.7/I-23 pre-scan refresh: once a cell's touched paths are known the pre-scan reruns over them; a contract touch
     * it now finds stops an S0/S1 campaign before the commit it no longer allows (S2 with an ADR in the main line).
     * With [once], a refresh the cell's return already logged is not logged again (a kept return verified on open).
     */
    private fun refreshPrescan(c: OpenedCampaign, ids: Identities, touched: List<String>, turns: Int, once: Boolean = false): String? {
        if (touched.isEmpty()) return null
        val refreshed = ImpactPrescan.of(c.atlas.refresh(touched), WORKSPACE, c.impactPrescan.inputs.copy(touched = touched.sorted()), c.kb.contractAnchors(), plugged(c).tiers)
        val text = "$PRESCAN_REFRESHED${refreshed.log}"
        val logged = once && c.journal.events(JournalScope(c.ids.work, kinds = setOf(JournalKind.Boundary))).any { it.ids.context == ids.context && it.text == text }
        if (!logged) c.journal.append(JournalEvent(idGen.next("ev"), ids, turns, JournalKind.Boundary, refs = refreshed.contractsTouched, text = text, at = clock.instant()))
        if (refreshed.prescan.contractTouch != true || c.contract.shape !in setOf(Shape.S0, Shape.S1)) return null
        return "impact pre-scan refresh: contract ${refreshed.contractsTouched.joinToString(", ")} touched — S2 with an ADR in the main line is required before this lands (I-23)"
    }

    /** The full compile-input fingerprint (§6.6, F06) of a main-line compile of [increment] at [stamp]. */
    private fun fingerprint(c: OpenedCampaign, contract: Contract, increment: Increment, stamp: CandidateId, model: CellModel, inputs: CompileInputs, registerVersion: Int?, pinned: List<String>): Fingerprint =
        Fingerprint.of(stamp, contract, increment, mainLine(c, contract), model.profile, c.attempt, c.prime, model.estimator, model.maxOutputTokens, inputs, registerVersion, pinned)

    /** A-D.1 (row R): the main line's role from the attempt's protocol and [contract]'s shape — never the campaign's shape decision. */
    private fun mainLine(c: OpenedCampaign, contract: Contract = c.contract): Role = Roles.mainLine(c.attempt.config.protocol, contract.shape)

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
            val role = mainLine(c, contract)
            val knowledge = knowledge(c, next, role, model)
            val inputs = CompileInputs(currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex, skills = knowledge.skills, skillConflicts = knowledge.skillConflicts)
            val compiler = Compiler(model.estimator, c.attempt.config)
            precompile.start(scope, ids, fingerprint(c, contract, next, stamp, model, inputs, null, emptyList()), next.id, remaining) {
                compiler.compile(next, contract, model.profile, role, c.prime, maxOutputTokens = model.maxOutputTokens, inputs = inputs)
            }
        }

    /**
     * Regression obligations (§4.2, FX-42): a verified increment whose evidence no longer holds at the current stamp is
     * re-accepted from the receipts current now — never re-executed. Its green `run:` acceptances are regression
     * obligations the harness re-runs at campaign end (§4.1); one still without current receipts stays unfinished. A
     * re-acceptance that needs a decision waits for one (D-343); the stop state is returned when it does.
     */
    private suspend fun refreshRegressions(c: OpenedCampaign, scheduler: Scheduler, authority: Authority): CampaignState? {
        restoreFinal(c, scheduler)
        for (increment in checkNotNull(c.state).graph.increments.filter { it.status == IncrementStatus.Verified }) {
            val assessed = completionEvidence(c, increment)
            if (increment.accept.any { id -> (c.contract.acceptance(id) is Acceptance.Check || c.contract.acceptance(id) is Acceptance.Review) &&
                    id !in assessed.verdicts && id !in assessed.unavailable }) {
                hostAssessment(c, increment, authority)
            }
        }
        // Current receipts first; what still needs a decision is asked only after the regression checks re-ran.
        reaccept(c, scheduler, authority, settle = false)
        val state = checkNotNull(c.state)
        val stale = stale(c)
        val runs = c.contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.id }.toSet()
        val obligations = state.graph.increments.filter { it.status == IncrementStatus.Verified && it.requirementIds.any { r -> r in stale } }
            .flatMap { it.accept }.filter { it in runs }.distinct()
        if (obligations.isEmpty()) return reaccept(c, scheduler, authority)
        val ids = c.ids.copy(context = ContextId(idGen.next("finish")))
        val outcome = harnessVerify(c, ids, "regression", """{"what":"acceptance","ids":[${obligations.joinToString(",") { "\"$it\"" }}]}""")
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "regression obligations re-run (campaign end): ${obligations.joinToString(", ")} · ${outcome.body.lineSequence().joinToString(" ")}", at = clock.instant()))
        return reaccept(c, scheduler, authority)
    }

    /**
     * WR P1-4: a finalization interrupted between `Finishing` and `Finished` reopens with its ledger reset (`Resumed`). When
     * the campaign gate's applied record still speaks for this candidate — contract, environment, obligation set and pinned
     * inputs outside identity unchanged — the verified increments are accepted again with the gate's decision ([rebind]):
     * no check and no question, before the ordinary re-acceptance, which reads the increments' own decisions only.
     */
    private fun restoreFinal(c: OpenedCampaign, scheduler: Scheduler) {
        if (checkNotNull(c.state).ledger.unfinished().isEmpty()) return
        val acceptances = Acceptances(c.store, clock)
        val applied = acceptances.pending(c.ids.work, c.ids.attempt).lastOrNull { it.incrementId == null && it.status == PendingStatus.Applied } ?: return
        val report = c.stamper.report(fresh = true)
        if (applied.resultingStamp != report.candidateId || applied.contractVersion != c.contract.version || applied.envId != report.env.envId ||
            applied.obligationSet != obligationSet(c, c.contract) || applied.outsideInputs != outsideInputs(c, scheduler, applied.results)) return
        rebind(c, scheduler, report, decisionFor(c, null, report.candidateId, c.contract.version, applied.key()), restoring = true)
    }

    /**
     * Requirements whose verification no longer holds: unfinished in the durable ledger (reset when an interrupted
     * finalization resumes) or in the ledger derived from the current graph identity (a contract amendment).
     */
    private fun stale(c: OpenedCampaign): Set<String> {
        val state = checkNotNull(c.state)
        return state.ledger.unfinished().toSet() + state.graph.ledger(c.contract, c.stamper.report().candidateId).unfinished()
    }

    /** Re-accepts stale verified increments from current receipts; with [settle], one that needs a decision asks for it. */
    private suspend fun reaccept(c: OpenedCampaign, scheduler: Scheduler, authority: Authority, settle: Boolean = true): CampaignState? {
        val report = c.stamper.report(fresh = true)
        val state = checkNotNull(c.state)
        val stale = stale(c)
        for (increment in state.graph.increments.filter { it.status == IncrementStatus.Verified && it.requirementIds.any { r -> r in stale } }) {
            val cell = increment.cells.lastOrNull() ?: continue
            val ids = c.ids.copy(context = cell)
            val proposal = CompletionProposal(increment.id, PacketStatus.Done.wire, c.contract.version, report.candidateId, report.candidateId, null, report.env.envId)
            val evidence = completionEvidence(c, increment)
            // No cell reworks a verified increment (FX-42): a standing rejection goes to the authority at once.
            val result = Verifier().accept(proposal, c.contract, increment, Register.empty(cell, increment.id, increment.title), checkNotNull(c.state).ledger, report.candidateId,
                currencies(c, scheduler, report.candidateId), evidence.verdicts, evidence.unavailable, decision = evidence.decision, reworkSpent = true, acknowledged = acknowledged(c))
            when (result) {
                is CompletionResult.Accepted -> c.advance(Transition.Committed(result, report.candidateId))
                is CompletionResult.Pending -> {
                    if (!settle) continue
                    val record = PendingCompletion(
                        idGen.next("pending"), c.ids.work, c.ids.attempt, increment.id, cell, proposal.contractVersion, proposal.baseStamp, proposal.resultingStamp,
                        null, proposal.envId, null, emptyList(), result.resolved.results, result.resolved.other, result.resolved.gaps, result.code,
                        result.resolved.results.mapNotNull { it.evidenceRef }.distinct(), null, idGen.next("decide"), acknowledged = result.resolved.acknowledged,
                        outsideInputs = outsideInputs(c, scheduler, result.resolved.results),
                    )
                    Acceptances(c.store, clock).save(ids, record)
                    when (val settled = decide(c, ids, record, authority)) {
                        is Settled.Commit -> {
                            c.advance(Transition.Committed(Verifier().commit(proposal, c.contract, increment, cell, checkNotNull(c.state).ledger, settled.resolved), report.candidateId))
                            closePending(c, ids, record, PendingStatus.Applied, settled.why)
                        }
                        is Settled.Rework -> {
                            closePending(c, ids, record, PendingStatus.Void, "rework requested by ${settled.record.decision.by}")
                            return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "rework of verified ${increment.id} requested by ${settled.record.decision.by}: ${settled.record.decision.reason} — amend the contract to reopen it"))
                        }
                        is Settled.Wait -> return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, settled.reason, settled.code))
                        is Settled.Void -> closePending(c, ids, record, PendingStatus.Void, settled.reason)
                    }
                }
                is CompletionResult.Refused, is CompletionResult.NotCompleted -> Unit
            }
        }
        return null
    }

    /**
     * The plan cell (§3.4, P2.1.2): `null` once a plan is admitted and installed, else the stop — a budget partial is
     * `budget_exhausted` (FX-49 S1), every other failure to plan blocks.
     */
    private suspend fun plan(c: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, packets: MutableList<ResultPacket>, splits: List<StoredSplit> = emptyList()): Transition.Stopped? {
        fun blocked(reason: String) = Transition.Stopped(CampaignOutcome.BlockedExternal, reason)
        val contract = c.contract
        val pinnedSplits = if (splits.isEmpty()) emptyList() else listOf("Replan the full authorized graph. Keep completed definitions unchanged; replace requested unfinished increments. " +
            "Give every replacement increment a new id: an id already in the current graph must keep its definition unchanged.\n" +
            splits.joinToString("\n") { "${it.split.increment}: ${it.split.reason}; parts ${it.split.parts.joinToString()}" } +
            "\nCurrent graph: " + Json.encodeToString(io.astrolabe.graph.RequirementGraph.serializer(), checkNotNull(c.state).graph))
        val planning = Increment(PLAN, contract.requirements.map { it.id }, contract.acceptance.map { it.id }, emptyList(), 0, title = "plan ${c.ids.work.value}")
        val knowledge = knowledge(c, planning, Roles.plan, model)
        val planInputs = CompileInputs(notes = knowledge.notes, contractsIndex = knowledge.contractsIndex, skills = knowledge.skills, skillConflicts = knowledge.skillConflicts)
        val routing = route(c, RoutingFunction.Plan, planning, model, null, null) { bound ->
            Compiler(bound.estimator, c.attempt.config).compile(planning, contract, bound.profile, Roles.plan, c.prime, maxOutputTokens = bound.maxOutputTokens, inputs = planInputs, pinned = pinnedSplits)
        }
        val compiled = routing.compiled
        if (compiled !is Compiled.Ready) return blocked("the plan cell cannot be compiled: $compiled")
        routing.refused?.let { return contractBudget(c, it.reason, null) }
        val cellId = ContextId(idGen.next("cell"))
        val proposals = SqlitePlanProposals(c.store, idGen, clock)
        // The plan cell's own decisions travel with its packet: its register as last patched (handoff debt 1).
        val intake = CampaignProposals(proposals, SqliteSplitRequests(c.store, idGen, clock), { c.contracts.current(c.ids.work) }, { SqliteRegisterVersions(c.store, clock).latest(cellId) }, { c.kb.contractAnchors() })
        val completion = io.astrolabe.cell.RoleCompletion.forRole(Roles.plan, mapOf(io.astrolabe.cell.PacketKind.PlanArtifacts to PlanPacketValidator.completion({ c.contract }, proposals, conAnchors = { c.kb.contractAnchors() })))
        val run = runCell(c, cellId, planning, Roles.plan, routing.model, authority, syntax, compiled, span, null, proposals = intake, completion = completion, inputs = planInputs, pinned = pinnedSplits)
        routing.selected?.let { router.record(it, outcomeOf(run.exit)) }
        val exit = run.exit ?: return Transition.Stopped(CampaignOutcome.Cancelled, "cancelled while planning")
        packets += exit.packet
        if (exit !is CellExit.Completed) {
            val outcome = when (val d = Lifecycle.disposition(exit, null)) {
                is Disposition.Stop -> d.outcome
                is Disposition.Continue -> d.fallback.takeIf { it == CampaignOutcome.BudgetExhausted } ?: CampaignOutcome.BlockedExternal
                is Disposition.Close -> CampaignOutcome.BlockedExternal
            }
            val reason = "the plan cell ended ${exit.packet.status.wire}: ${exit.packet.reason}"
            return if (outcome == CampaignOutcome.BudgetExhausted) contractBudget(c, reason, (exit as? CellExit.Partial)?.reason ?: PartialReason.TokenBudget)
            else Transition.Stopped(outcome, reason)
        }
        val stored = proposals.latest(c.ids.work, cellId) ?: return blocked("the plan cell proposed no plan")
        return when (val admission = PlanIntake(c.contracts).admit(c.ids.work, stored, authority)) {
            is PlanAdmission.Admitted -> {
                c.checks.synchronizeAcceptance(c.contract)
                val previous = checkNotNull(c.state).graph
                val replaced = splits.map { it.split.increment }.toSet()
                val kept = previous.increments.filter { it.cells.isNotEmpty() || it.status != IncrementStatus.Pending }.associateBy { it.id }
                val merged = admission.graph.increments.map { proposed ->
                    val old = kept[proposed.id]
                    if (old != null) {
                        // D-316: never auto-rename; dependents' `depends_on` would silently pick the old or the new definition.
                        if (old.definitionDigest() != proposed.definitionDigest()) return blocked("replan changes dispatched definition ${old.id}; " +
                            if (old.id in replaced) "give the replacement a new id instead of ${old.id}" else "keep ${old.id} unchanged")
                        old
                    } else proposed
                }.toMutableList()
                for (old in kept.values.filter { old -> merged.none { it.id == old.id } }) {
                    if (old.id !in replaced || old.status in setOf(IncrementStatus.Verified, IncrementStatus.Cancelled)) return blocked("replan drops ${old.id}")
                    merged += old.copy(status = IncrementStatus.Cancelled, cancelledReason = "split: ${splits.first { it.split.increment == old.id }.id}")
                }
                val graph = admission.graph.copy(increments = merged, evidence = previous.evidence)
                val gaps = graph.validate(c.contract)
                if (gaps.isNotEmpty()) return blocked("replacement graph refused: ${gaps.joinToString { it.detail }}")
                c.advance(Transition.Planned(graph, replaced))
                SqliteSplitRequests(c.store, idGen, clock).consumed(splits)
                // §8.9 item 4: without CON notes to validate against, a missing CON reference is a recorded planning gap, not a refusal.
                if (c.kb.contractAnchors().isEmpty()) {
                    for (gap in PlanPacketValidator.planningGaps(c.contract, stored.packet)) {
                        c.journal.append(JournalEvent(idGen.next("ev"), c.ids.copy(context = cellId), null, JournalKind.Boundary, refs = listOf(stored.id), text = gap, at = clock.instant()))
                    }
                }
                boundary(c, cellId, RebuildReason.RoleSwitch(mainLine(c)))
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
        status.checkpoint(ids, if (reason is RebuildReason.RoleSwitch) StatusBoundary.RoleSwitch else StatusBoundary.CellEnd, retainedFacts(c, cell)?.archived.orEmpty(), verification, c.intents.open().map { it.intentId })
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

    private fun retainedFacts(c: OpenedCampaign, cell: ContextId): io.astrolabe.context.Retention? {
        val register = SqliteRegisterVersions(c.store, clock).latest(cell) ?: return null
        val aliases = SqliteAliases(c.store, clock)
        val observations = SqliteObservations(c.store, clock)
        val receipts = SqliteReceipts(c.store, clock)
        return io.astrolabe.context.FactRetention.capture(c.store, c.ids.copy(context = cell), register, c.registry::version,
            { id ->
                val canonical = Aliases.parse(id)?.let { aliases.resolve(c.ids.work, it)?.canonicalId } ?: id
                observations.get(canonical) != null || receipts.get(canonical) != null || c.journal.get(canonical) != null
            }, HeuristicEstimator(), c.attempt.config.defaults.registerCapTokens, clock,
            io.astrolabe.context.FactRetention.protocolOf(c.store, clock, cell, c.attempt.config.roles, mainLine(c).protocol))
    }

    /** What crosses into a cell (task-workflow §4): the [carry], and whether its register seeds the cell's STATE ([continuation]). */
    private class Crossing(val carry: Carry, val continuation: Boolean) {
        /** The resume note's line naming where a carry across an increment or a work comes from (§4.3). */
        val note: String? get() = carry.source?.takeIf { !continuation }?.let { "carried from $it" }
    }

    /**
     * Task workflow §4.1–§4.5: what [ready]'s next cell starts from, read from the store by cell id, never from process
     * memory — the increment's previous cell (a continuation); else the last cell of the latest closed increment, whose
     * register travels as data and never as the new increment's STATE (§4.3, a response or an amendment's increment
     * alike); else, for a follow-up's first cell, its direct parent's last cell (§4.5).
     */
    private fun crossing(c: OpenedCampaign, ready: Increment, role: Role, handoff: Boolean): Crossing? {
        ready.cells.lastOrNull()?.let { previous -> return carryFrom(c, previous, role, handoff)?.let { Crossing(it, continuation = true) } }
        val state = checkNotNull(c.state)
        val closed = state.graph.increments.filter { it.status == IncrementStatus.Verified && it.cells.isNotEmpty() }.map { it.id }.toSet()
        state.cells.lastOrNull { it.increment in closed && it.increment != ready.id }?.let { previous ->
            val carry = carryFrom(c, previous.cell, role) ?: return null
            return Crossing(carry.copy(source = "${previous.increment} · ${previous.cell.value}", status = statusNotes(c).summary(c.ids.work)), continuation = false)
        }
        if (state.cells.isNotEmpty()) return null
        return c.contract.parentWork?.let { parent -> parentCarry(c, parent, role) }?.let { Crossing(it, continuation = false) }
    }

    private fun statusNotes(c: OpenedCampaign): StatusNotes = StatusNotes(KbWriter(c.store, HeuristicEstimator(), clock), Notes(c.store), c.store.layout.kb)

    /**
     * Task workflow §4.5 (№33): the direct parent's last ended cell as data for a follow-up's first cell — its validated
     * register's decisions, dead ends and open items, its touched ledger, its last verification status and STATUS — capped
     * at `parentCarryMaxTokens`, with seeds from its end export re-served at current versions.
     */
    private fun parentCarry(c: OpenedCampaign, parent: WorkId, role: Role): Carry? {
        val contract = c.contracts.current(parent) ?: return null
        val state = SqliteCampaigns(c.store, clock).load(parent, contract.attemptId) ?: return null
        val last = state.cells.lastOrNull { it.status != CellStatus.Running }?.cell ?: return null
        val register = SqliteRegisterVersions(c.store, clock).latest(last) ?: return null
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val packet = checkpoints.packet(last)
        val receipts = SqliteReceipts(c.store, clock)
        val verification = packet?.receipts.orEmpty().mapNotNull { id -> receipts.get(id)?.let { CarriedReceipt(it.checkId, it.receiptId, it.outcome.name.lowercase()) } }
        val defaults = c.attempt.config.defaults
        val estimator = HeuristicEstimator()
        return CarryForward.parent(
            parent.value, register, Seeds.cellEnd(checkpoints, last), packet, { c.registry.version(it) }, { true }, verification,
            statusNotes(c).summary(parent), defaults.parentCarryMaxTokens.toLong(), { estimator.estimate(it).tokens }, defaults.seedsMaxTokens.toLong(),
            io.astrolabe.context.SeedRule.of(role.protocol, defaults.seedRule).selector, defaults.seedFallback,
            touched = if (packet == null) checkpoints.latest(last)?.touched.orEmpty() else emptyList(),
        )
    }

    /**
     * The carry-forward of [cell] (§6.2) for the next cell of [role]: its latest register, its end export and its packet
     * row, re-validated now — read by cell id from the store, so a boundary after a reopen carries the bytes an in-process
     * boundary carries (task-workflow §4.1). A cell without its packet row (a store of the old two-step end) carries from
     * its checkpoint and export alone and says so (`packetMissing`). [handoff]: the next cell is [cell]'s epoch (A-D.6).
     */
    private fun carryFrom(c: OpenedCampaign, cell: ContextId, role: Role, handoff: Boolean = false): Carry? {
        val retained = retainedFacts(c, cell) ?: return null
        if (retained.archived.isNotEmpty()) {
            val status = StatusNotes(KbWriter(c.store, HeuristicEstimator(), clock), Notes(c.store), c.store.layout.kb)
            if (!status.archived(c.ids.work).containsAll(retained.archived)) {
                status.checkpoint(c.ids.copy(context = cell), StatusBoundary.CellEnd, retained.archived,
                    c.checks.all().mapNotNull { check -> check.last?.let { CarriedReceipt(check.id, it.receiptId, it.applicability.name.lowercase()) } },
                    c.intents.open().map { it.intentId })
            }
        }
        val register = withReviewOpenItems(c, retained.register)
        val aliases = SqliteAliases(c.store, clock)
        val receipts = SqliteReceipts(c.store, clock)
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val stored = checkpoints.packet(cell)
        val touched = if (stored == null) checkpoints.latest(cell)?.touched.orEmpty() else emptyList()
        // D-398: the cell boundary selects seeds by the attempt's seed rule, as the pressure rebuild does; v1 keeps its bytes.
        // A-D.7 K1: a direct cell always takes Seeds v2. Task workflow §4.2: a rule that selects nothing falls back to v2.
        val carry = CarryForward.carry(
            register, Seeds.cellEnd(checkpoints, cell), null, { c.registry.version(it) },
            { id -> Aliases.parse(id)?.let { aliases.resolve(c.ids.work, it) } != null }, emptyList(), emptyList(),
            selector = io.astrolabe.context.SeedRule.of(role.protocol, c.attempt.config.defaults.seedRule).selector, touched = touched,
            latestReceipts = c.checks.all().mapNotNull { it.last?.receiptId?.let(receipts::get) }, handoff = handoff,
            fallback = c.attempt.config.defaults.seedFallback, stored = stored,
        ).copy(capacityGap = retained.capacityGap)
        if (stored != null) return carry
        return carry.copy(packetMissing = true, touched = touched.sorted().map { io.astrolabe.context.CarriedTouch(it, c.registry.version(it)) })
    }

    /** Every ended campaign leaves a finish receipt, stored and exported, and says so on the bus (§5.9). */
    private fun finish(c: OpenedCampaign, result: S0Run, current: List<ResultPacket> = listOfNotNull(result.exit?.packet)): S0Run {
        val outcome = result.state?.outcome ?: return result
        // A-D.6: the report covers every epoch; those of an earlier run come from their kept records.
        val reported = current.mapNotNullTo(HashSet()) { it.ids.context }
        val packets = ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).filter { it.cell !in reported }.map { keptPacket(c, it) } + current
        val receipts = SqliteReceipts(c.store, clock)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, receipts, SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c), retryCandidates = c.store.layout.candidates)
        extract(c, packets)
        val report = c.stamper.report(fresh = true)
        // C3: a limit's stop names its best verified candidate in the receipt; the provenance class is the receipt's own (C2).
        val built = FinishReceipts.build(c, packets, currencies(c, scheduler, report.candidateId), report, receipts::get)
        // The stop's labels follow the receipt's own provenance calculation, never a second one.
        val limit = result.state?.takeIf { it.budgetStop?.taskLimit == true }?.let { TaskLimitControl.recorded(c.journal, c.ids.work) }?.labelledBy(built)
        val receipt = if (limit == null) built else built.copy(limit = limit)
        val (ref, _) = FinishReceipts.export(c, receipt)
        // C3: the counter's last word, with or without limits.
        limitControl.report(c)
        events?.emit(AgentEvent.Campaign.Finished(c.ids, outcome.wire, ref, stopCode = result.state?.stopCode?.wire, provenanceClass = receipt.provenanceClass.wire,
            budgetStop = result.state?.budgetStop?.wire))
        return result.copy(finish = receipt, limit = limit)
    }

    /**
     * §12.1 post-cell extraction at finish (P4.2.1): each archived packet with its cell's journal goes to the
     * extractor under the originating cell's ids (its cost is charged there), then the §6.7 `CAL-<repo>` delta is
     * aggregated over every stored campaign before the finish receipt is exported.
     */
    private fun extract(c: OpenedCampaign, packets: List<ResultPacket>) {
        // C3: the extractor's call counts against the task limits, in the transaction of its hold; without a price bound it
        // is refused under a money limit (fail closed).
        val accounting = Accounting(c.store, clock, limitControl.reportAdmission(c))
        val bound = extraction.maxTokens.coerceAtLeast(1)
        val cap = c.contract.budget.cost
        val currency = cap?.currency ?: c.attempt.config.profiles.values.firstOrNull()?.priceTable?.currency ?: "USD"
        val extractor = Extractor(c.store, HeuristicEstimator(), idGen, clock, Extraction.NONE, c.journal, events)
        val stamp = c.stamper.report().candidateId.digest.hex
        val findings = reviewFindings(c)
        for ((i, packet) in packets.withIndex()) {
            val trace = ExtractionTrace(packet, c.journal.events(JournalScope(c.ids.work, packet.ids.context)), packet.stamp?.digest?.hex ?: stamp)
            // §8.8 findings are the campaign's, derived once: with the last packet; recurring dead ends see the earlier registers.
            val invocation = "extraction:${packet.ids.context?.value ?: packet.increment}"
            if (accounting.calls(c.ids.work).any { it.invocationId == invocation }) continue
            val permitted = extraction !== Extraction.NONE && accounting.reserveExtraction(packet.ids, invocation, bound,
                extraction.maxCost, c.contract.budget.tokens.value, cap, currency)
            val active = if (permitted) Extractor(c.store, HeuristicEstimator(), idGen, clock, extraction, c.journal, events) else extractor
            val report = active.run(trace, packet.ids, if (i == packets.lastIndex) findings else emptyList(), packets.take(i).map { it.register })
            if (permitted) accounting.extraction(packet.ids, invocation, report.tokens.takeIf { report.failure == null },
                if (report.failure == null) report.tokens else maxOf(bound, report.tokens), report.money, currency,
                report.money ?: extraction.maxCost)
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

    /** §8.8 (P4.2.2): the review findings as [register]'s `Open` items ([withOpenItems]). */
    private fun withReviewOpenItems(c: OpenedCampaign, register: Register): Register = withOpenItems(register, reviewFindings(c))

    /**
     * The S0 loop body. It re-enters itself for a rework or a void (D-340) and, for a direct cell, after a handoff (A-D.6):
     * the handoff's epoch runs on re-entry, its packets collected in [epochs] for the final report.
     */
    private suspend fun runS0(
        campaign: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, reworks: Int = 0,
        maxHandoffs: Int = DEFAULT_MAX_HANDOFFS, epochs: MutableList<ResultPacket> = ArrayList(),
    ): S0Run {
        val c = campaign
        c.stop?.let { return S0Run(c.state, null, null, null) }
        // Every later use reads the attempt's frozen configuration, never the controller's live one (invariant 12).
        val config = c.attempt.config
        c.refusal()?.let { return S0Run(c.advance(Transition.Stopped(stopOutcome(c), "nothing dispatched: $it")), null, null, null) }
        // D-340: a completion waiting for a decision is settled first — no cell, no budget check, no model call.
        when (val resumed = resumePending(c, authority) ?: resumeReturned(c, authority, refused = null) { _, _ -> null }) {
            null, Resumed.Continue -> Unit
            is Resumed.Committed -> {
                // Task-workflow §2.4 A: a message sent while the decision was pending gets its response increment now.
                intake(c)?.let { return S0Run(it, null, resumed.result, null) }
                if (checkNotNull(c.state).ledger.unfinished().isEmpty()) {
                    return S0Run(stopOrFinish(c, "requirements remain unverified after ${resumed.result.incrementId}", scheduler(c), authority = authority), null, resumed.result, null)
                }
            }
            is Resumed.Stopped -> return S0Run(resumed.state, null, null, null)
        }
        reassessBlocked(c, authority)
        intake(c)?.let { return S0Run(it, null, null, null) }
        val opened = checkNotNull(c.state)
        check(opened.phase == CampaignPhase.Running && opened.running == null) { "runS0 needs a reconciled campaign with no running cell; it is ${opened.phase}" }
        val contract = c.contract
        val ready = opened.graph.readyFrontier(contract, 1).firstOrNull()
            ?: run {
                val scheduler = scheduler(c)
                refreshRegressions(c, scheduler, authority)?.let { return S0Run(it, null, null, null) }
                return S0Run(stopOrFinish(c, "no ready increment: an empty frontier never means completed", scheduler, authority = authority), null, null, null)
            }
        // C3 (plan §4.6): the cell starts only within the task limits' working part.
        limitStop(c, authority)?.let { return S0Run(it, null, null, null) }
        val role = mainLine(c, contract)
        // A-D.6: the epoch a handoff left; it continues from the predecessor's packet.
        val epoch = pendingEpoch(c, epochs)?.takeIf { it.kept.incrementId == ready.id }
        // §13.4 rebuild(resume): a cell that continues a lost or interrupted one starts from its validated carry-forward.
        val crossing = crossing(c, ready, role, handoff = epoch != null)
        val carry = crossing?.carry
        val seeds = carry?.let { Seeds.render(it.seeds, c.registry::read) }
        val resume = resumeNote(c, ready, carry)
        val knowledge = knowledge(c, ready, role, model, touched = carry?.seeds.orEmpty().map { it.path }.toSet())
        val inputs = CompileInputs(carry = carry, seeds = seeds, currentVersion = { c.registry.version(it) }, notes = knowledge.notes, contractsIndex = knowledge.contractsIndex, skills = knowledge.skills, skillConflicts = knowledge.skillConflicts)
        val function = epoch?.kept?.function ?: if (ready.cells.isEmpty()) RoutingFunction.Implementing else RoutingFunction.Continuation
        val routing = route(c, function, ready, model, null, null) { bound ->
            Compiler(bound.estimator, config).compile(ready, contract, bound.profile, role, c.prime, maxOutputTokens = bound.maxOutputTokens, inputs = inputs)
        }
        val compiled = routing.compiled
        val cellModel = routing.model
        when (compiled) {
            is Compiled.Ready -> Unit
            is Compiled.NeedsRescoping -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, "NEEDS_RESCOPING_OR_LARGER_PROFILE: ${compiled.reason}${profileBound(c, cellModel)}")), null, null, compiled)
            is Compiled.NeedsEvidence -> return S0Run(c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "NEEDS_MORE_EVIDENCE: acceptance without definition ${compiled.missing}")), null, null, compiled)
        }
        routing.refused?.let { return S0Run(c.advance(contractBudget(c, it.reason, null)), null, null, compiled) }

        val cellId = if (epoch == null) ContextId(idGen.next("cell")) else successor(c, epoch, maxHandoffs).let { (to, stop) ->
            to ?: return S0Run(c.advance(checkNotNull(stop)), null, null, compiled)
        }
        events?.emit(AgentEvent.Campaign.IncrementSelected(c.ids, ready.id))
        val (reworkLines, reworkRecords) = reworkNotes(c, ready)
        val dispatched = c.advance(Transition.Dispatched(ready.id, cellId, epoch = epoch != null))
        spendReworks(c, cellId, reworkRecords)
        val increment = dispatched.graph.increments.first { it.id == ready.id }
        val register = crossing?.takeIf { it.continuation }?.carry?.register?.copy(cell = cellId, increment = increment.id, incrementTitle = increment.title)
        // A-D.6: S0 has no continuation of its own, so its cell may hand off on a spent turn budget.
        val run = runCell(c, cellId, increment, role, cellModel, authority, syntax, compiled, span, dispatched.ledger, register, seeds?.shown.orEmpty(), pinned = listOfNotNull(resume, crossing?.note) + hostAnswers(c, ready) + reworkLines,
            boundary = if (epoch != null) BoundaryReason.Epoch else null, inputs = inputs, rework = reworkLines.isNotEmpty(), turnBudgetHandoff = true, continues = epoch?.kept)
        val ids = run.ids
        val scheduler = run.scheduler
        val exit = run.exit
        if (exit == null) {
            routing.selected?.let { router.record(it, outcomeOf(null)) }
            snapshot(c)
            val checkpoint = checkNotNull(run.checkpoints.latest(cellId)) { "a cancelled cell settles its checkpoint" }
            c.advance(Transition.Interrupted(checkpoint))
            return S0Run(c.advance(Transition.Stopped(CampaignOutcome.Cancelled, checkNotNull(c.cancellation.reason))), null, null, compiled)
        }
        // The tree after the cell is the base the next open reconciles against: only moves after this are external.
        snapshot(c)
        val kept = returned(c, ids, exit, routing.selected, compiled.k.ledger, function, run.impact)
        refreshPrescan(c, ids, exit.checkpoint.touched, exit.turns)?.let {
            if (!exit.handoff) routing.selected?.let { selected -> router.record(selected, outcomeOf(exit)) }
            return S0Run(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it)), exit, null, compiled)
        }

        val stampNow = c.stamper.report(fresh = true).candidateId
        // D-344: an answer ends the campaign `answered`; the facts were checked by the tool and again at the turn's end.
        if (exit is CellExit.Completed && exit.answer != null) {
            routing.selected?.let { router.record(it, RoutingOutcome.Accepted) }
            return S0Run(c.advance(Transition.Answered(stampNow, exit.answer)), exit, null, compiled)
        }
        val completion = kept?.let {
            val returned = checkNotNull(c.state).graph.increments.first { it.id == increment.id }
            verify(c, kept, returned, stampNow, currencies(c, scheduler, stampNow))
        }
        // The router learns the verified outcome, never the cell's own word (A3): an unverified completion is not an acceptance.
        if (!exit.handoff) routing.selected?.let { router.record(it, outcomeOf(exit, completion)) }
        val state = when (val disposition = Lifecycle.disposition(exit, completion)) {
            is Disposition.Close -> commit(c, ids, exit.turns, increment, disposition.accepted, stampNow)
                ?: run {
                    // Task-workflow §2.4: another increment a message derived runs next, and verified work is re-accepted at the
                    // tree it left (FX-42) before the finish.
                    if (checkNotNull(c.state).ledger.unfinished().isNotEmpty() && checkNotNull(c.state).graph.increments.size > 1) {
                        return runS0(c, model, authority, syntax, span, reworks, maxHandoffs, epochs)
                    }
                    stopOrFinish(c, "requirements remain unverified after ${increment.id}", scheduler, authority = authority)
                }
            // A-D.6: a handoff continues the increment in an epoch — the user's limits still stop it first.
            is Disposition.Continue if exit.handoff -> onLimit(c, authority) ?: run {
                epochs += exit.packet
                return runS0(c, model, authority, syntax, span, reworks, maxHandoffs, epochs)
            }
            // S0 has no continuation cell of its own: the fallback is the honest outcome (D-64) — the limit's own when a task limit ended the cell (C3).
            is Disposition.Continue -> onLimit(c, authority) ?: c.advance(
                if (disposition.fallback == CampaignOutcome.BudgetExhausted) contractBudget(c, disposition.reason, (exit as? CellExit.Partial)?.reason ?: PartialReason.TokenBudget)
                else Transition.Stopped(disposition.fallback, disposition.reason))
            is Disposition.Stop -> if (completion is CompletionResult.Pending) {
                when (val settled = settle(c, ids, increment, checkNotNull(kept), completion, authority)) {
                    is Settled.Commit -> commit(c, ids, exit.turns, increment, Verifier().commit(completion.proposal, c.contract, checkNotNull(c.state).graph.increments.first { it.id == increment.id }, kept.cell, checkNotNull(c.state).ledger, settled.resolved), stampNow)
                        ?.also { closePending(c, ids, settled.pending, PendingStatus.Void, "publication refused") }
                        ?: run {
                            closePending(c, ids, settled.pending, PendingStatus.Applied, settled.why)
                            stopOrFinish(c, "requirements remain unverified after ${increment.id}", scheduler, authority = authority)
                        }
                    // D-340: a `rework` answer runs one continuation cell with the decider's text pinned.
                    is Settled.Rework -> {
                        closePending(c, ids, settled.pending, PendingStatus.Void, "rework requested by ${settled.record.decision.by}")
                        if (reworks < MAX_REWORKS_PER_RUN) return runS0(c, model, authority, syntax, span, reworks + 1, maxHandoffs, epochs)
                        c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "rework requested again (${settled.record.decision.reason}); resume to continue"))
                    }
                    is Settled.Wait -> c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, settled.reason, settled.code))
                    is Settled.Void -> {
                        closePending(c, ids, settled.pending, PendingStatus.Void, settled.reason)
                        if (reworks < MAX_REWORKS_PER_RUN) return runS0(c, model, authority, syntax, span, reworks + 1, maxHandoffs, epochs)
                        c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "${settled.reason}; resume to continue"))
                    }
                }
            } else c.advance(stopped(exit, disposition))
        }
        return S0Run(state, exit, completion, compiled)
    }

    /**
     * The stop a returned cell's [disposition] ends the run with. WF-10 (task-workflow §1.3): a cell that failed — an
     * exception inside it — ends that cell `failed` and the run as a `failed` stop a reopen continues, naming the error;
     * the next open resumes the increment from its carry. `failed` stays final for the campaign gate's red end checks.
     */
    private fun stopped(exit: CellExit, disposition: Disposition.Stop): Transition.Stopped =
        if (exit is CellExit.Failed) {
            Transition.Stopped(CampaignOutcome.Failed, "cell ${exit.packet.ids.context?.value} failed: ${disposition.reason} — reopen to continue the same work", resumable = true)
        } else Transition.Stopped(disposition.outcome, disposition.reason, disposition.code)

    /**
     * The verifier over a completed cell's proposal (§8.7, D-337): current receipts, the reviewers' results at this
     * candidate, the increment review [review] owes (S2+), the current decision and whether a rework round is spent —
     * the cell's own pending marker says so for a rejection that stood after its round (D-341).
     */
    private fun verify(c: OpenedCampaign, kept: ReturnedCompletion, increment: Increment, stampNow: CandidateId, currencies: Map<String, Currency>, review: ReviewOutcome? = null): CompletionResult {
        val evidence = completionEvidence(c, increment, acceptanceFlags(c, kept.testIntegrity()))
        val reviewed = withIncrementReview(c.contract, increment, review, evidence, stampNow)
        return Verifier().accept(
            kept.proposal(), c.contract, increment, kept.register, checkNotNull(c.state).ledger, stampNow, currencies,
            reviewed.verdicts, reviewed.unavailable, evidence.flags, decision = evidence.decision,
            // The cell deferred only past its rework round, or with nothing to rework: the same rule, the same answer.
            reworkSpent = evidence.reworkSpent || kept.deferred, extra = reviewed.extra, acknowledged = acknowledged(c),
        )
    }

    /** P8.C.10: the red receipts accepted increments acknowledged with an `Open` item — one answer on every path (D-337). */
    private fun acknowledged(c: OpenedCampaign): List<String> = checkNotNull(c.state).graph.evidence.values.flatMap { it.acknowledged }.distinct()

    /** The increment review an S2+ campaign obtained (§8.8): the verdict of its `review:` items, or its own obligation when it has none. */
    private class Reviewed(val verdicts: Map<String, io.astrolabe.verify.Verdict>, val unavailable: Map<String, String>, val extra: List<ObligationResult>)

    private fun withIncrementReview(contract: Contract, increment: Increment, review: ReviewOutcome?, evidence: io.astrolabe.cell.CompletionEvidence, stampNow: CandidateId): Reviewed {
        if (review == null) return Reviewed(evidence.verdicts, evidence.unavailable, emptyList())
        val verdict = review.record.verdict?.takeIf { review.record.unavailable == null }
        val why = (review as? ReviewOutcome.Unavailable)?.reason ?: review.record.unavailable
        val items = increment.accept.filter { contract.acceptance(it) is Acceptance.Review }
        if (items.isEmpty()) {
            val result = io.astrolabe.verify.Obligations.verdict("review:${increment.id}", io.astrolabe.verify.ObligationKind.Review, "increment review (${review.record.path.joinToString(" → ").ifEmpty { "owed" }})", verdict, contract.version, stampNow, why)
            return Reviewed(evidence.verdicts, evidence.unavailable, listOf(result))
        }
        return if (verdict != null) Reviewed(evidence.verdicts + items.associateWith { verdict }, evidence.unavailable - items.toSet(), emptyList())
        else Reviewed(evidence.verdicts - items.toSet(), evidence.unavailable + items.associateWith { why ?: "no increment review verdict" }, emptyList())
    }

    /** Commits an accepted completion while publication is authorized (§3.7); the stop state when it is not. */
    private fun commit(c: OpenedCampaign, ids: Identities, turns: Int?, increment: Increment, accepted: CompletionResult.Accepted, stampNow: CandidateId): CampaignState? {
        // §3.7 publication: a completion that arrives after cancellation or lease loss is archived, never committed.
        c.refusal()?.let { reason ->
            c.journal.append(JournalEvent(idGen.next("ev"), ids, turns, JournalKind.Reconcile, refs = accepted.receiptIds, text = "late completion of ${increment.id} archived; publication refused: $reason", at = clock.instant()))
            return c.advance(Transition.Stopped(stopOutcome(c), "late completion archived; publication refused: $reason"))
        }
        // WR2 (P1 #5, task-workflow §2.4 B): an amendment recorded while the cell ran — an amending answer to its question —
        // derived a requirement no increment covers yet; the commit validates the graph against that contract, so the
        // amendment's increment is derived first (the cell has returned: no cell runs).
        if (checkNotNull(c.state).graph.validate(c.contract).isNotEmpty()) intake(c)?.let { return it }
        if (checkNotNull(c.state).graph.increments.first { it.id == increment.id }.status == IncrementStatus.Cancelled) {
            c.journal.append(JournalEvent(idGen.next("ev"), ids, turns, JournalKind.Reconcile, refs = accepted.receiptIds, text = "completion of ${increment.id} archived: the user cancelled its requirements while it ran", at = clock.instant()))
            return null
        }
        c.advance(Transition.Committed(accepted, stampNow))
        // I7: an increment accepted on a decider's word is `accepted`, never `verified`.
        events?.emit(AgentEvent.Campaign.IncrementClosed(c.ids, increment.id, if (accepted.verified) "verified" else "accepted"))
        return null
    }

    /** What an acceptance decision settled for a pending completion (D-339, D-340). */
    private sealed interface Settled {
        val pending: PendingCompletion

        class Commit(override val pending: PendingCompletion, val resolved: io.astrolabe.verify.Resolved, val why: String) : Settled
        class Rework(override val pending: PendingCompletion, val record: DecisionRecord) : Settled
        class Wait(override val pending: PendingCompletion, val reason: String, val code: StopCode) : Settled

        /** The completion no longer speaks for the tree or contract: the work continues in a cell. */
        class Void(override val pending: PendingCompletion, val reason: String) : Settled
    }

    /**
     * D-339: records a pending completion of [increment] durably before anything is asked — so a stop at any point can
     * resume it — then settles it with the authority's decision.
     */
    private suspend fun settle(c: OpenedCampaign, ids: Identities, increment: Increment, kept: ReturnedCompletion, pending: CompletionResult.Pending, authority: Authority): Settled {
        val proposal = pending.proposal
        val record = PendingCompletion(
            idGen.next("pending"), c.ids.work, c.ids.attempt, increment.id, kept.cell, proposal.contractVersion,
            proposal.baseStamp, proposal.resultingStamp, proposal.patchHash, proposal.envId, kept.register.version,
            acceptanceFlags(c, kept.testIntegrity()).map { it.line }, pending.resolved.results, pending.resolved.other, pending.resolved.gaps, pending.code,
            pending.resolved.results.mapNotNull { it.evidenceRef }.distinct(), kept.text.take(MAX_SUMMARY_CHARS), idGen.next("decide"),
            acknowledged = pending.resolved.acknowledged, outsideInputs = outsideInputs(c, scheduler(c), pending.resolved.results),
        )
        Acceptances(c.store, clock).save(ids, record)
        c.journal.append(JournalEvent(idGen.next("ev"), ids, kept.turns, JournalKind.Boundary, refs = record.evidence,
            text = "completion of ${increment.id} awaits ${pending.code.wire} (${record.id}): ${pending.missing.joinToString("; ")}", at = clock.instant()))
        return decide(c, ids, record, authority)
    }

    /**
     * D-340: the stored results of [pending] resolved again with a decision — a stored one that still speaks for its
     * candidate, else the authority's answer to one request, validated before use. Asking costs no model call.
     */
    private suspend fun decide(c: OpenedCampaign, ids: Identities, pending: PendingCompletion, authority: Authority): Settled {
        val waiting = pending.resolve(null)
        // C11: a policy's word settles nothing only a person settles — such a request is asked again, so a person can answer.
        // WR P1-2: an accept answers the obligations its request named; one that leaves any undecided is asked anew, under a new key.
        val decision = decisionFor(c, pending.incrementId, pending.resultingStamp, pending.contractVersion, pending.key())
            ?.takeUnless { it.decision.decider == io.astrolabe.verify.Decider.Policy && waiting.undecided.any { r -> r.humanOnly } }
            ?.takeIf { it.decision.kind != DecisionKind.Accept || waiting.undecided.all { r -> r.obligation in it.obligations } }
            ?: ask(c, ids, pending, waiting, authority)
        val resolved = pending.resolve(decision)
        // The authority may take its time: a decision applies only to the tree and contract it was asked about.
        val now = c.stamper.report(fresh = true).candidateId
        if (now != pending.resultingStamp || c.contract.version != pending.contractVersion) {
            return Settled.Void(pending, "the tree or contract moved while the decision was asked (@${now.hash8}, v${c.contract.version})")
        }
        // WR2 (P1 #1): and the inputs outside identity its evidence pinned, re-read now, still read as they did when asked.
        if (outsideInputs(c, scheduler(c), pending.results) != pending.outsideInputs) {
            return Settled.Void(pending, "inputs outside the candidate its evidence pinned changed while the decision was asked")
        }
        return when (resolved.resolution) {
            Resolution.Complete -> Settled.Commit(pending, resolved, decision?.let { "${it.decision.kind.name.lowercase()} by ${it.decision.by}: ${it.decision.reason}" } ?: "all obligations settled")
            Resolution.Rework -> Settled.Rework(pending, checkNotNull(decision?.takeIf { it.decision.kind == DecisionKind.Rework }) { "a pending completion reworks only on a rework decision" })
            Resolution.Await -> Settled.Wait(pending, Lifecycle.pendingReason(CompletionResult.Pending(pending.proposal(), checkNotNull(resolved.code), resolved)), checkNotNull(resolved.code))
        }
    }

    /** One acceptance-decision request for [pending] (D-338); an answer for another request, revision or candidate is no answer. */
    private suspend fun ask(c: OpenedCampaign, ids: Identities, pending: PendingCompletion, waiting: io.astrolabe.verify.Resolved, authority: Authority): DecisionRecord? {
        val items = waiting.decisionItems
        if (items.isEmpty()) return null
        val diff = runCatching { campaignReview(c, authority).diffBlob(c.s0.stampId, pending.resultingStamp).first.hex }.getOrNull()
        val request = AcceptanceDecisionRequest(
            pending.requestId, pending.contractVersion, c.ids.withCandidate(pending.resultingStamp).withContext(pending.cell), pending.incrementId,
            pending.resultingStamp, waiting.code ?: pending.code, items, diff, pending.evidence, pending.summary, pending.outsideInputs,
            obligationSet = pending.obligationSet,
        )
        val reply = authority.decide(request)
        val invalid = when {
            reply == null -> "no decision available"
            reply.requestId != request.id -> "the decision answers ${reply.requestId}, not ${request.id}"
            Replies.check(reply, c.contract.version) != ReplyValidity.Current -> "the decision is for contract v${reply.contractRevision}, not v${c.contract.version}"
            reply.candidate != pending.resultingStamp -> "the decision is about @${reply.candidate.hash8}, not @${pending.resultingStamp.hash8}"
            else -> null
        }
        if (invalid != null) {
            c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(request.id), text = "acceptance decision ${request.id}: $invalid · the campaign waits", at = clock.instant()))
            return null
        }
        val record = DecisionRecord(idGen.next("decision"), pending.incrementId, checkNotNull(reply), items.map { it.obligation },
            key = pending.key(), outsideInputs = pending.outsideInputs)
        Acceptances(c.store, clock).record(ids, record)
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(request.id),
            text = "acceptance decision ${request.id}: ${reply.kind.name.lowercase()} by ${reply.by} (${reply.decider.name.lowercase()}): ${reply.reason}", at = clock.instant()))
        return record
    }

    private fun closePending(c: OpenedCampaign, ids: Identities, pending: PendingCompletion, status: PendingStatus, reason: String) {
        Acceptances(c.store, clock).close(ids, pending, status, reason)
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "pending completion ${pending.id} ${status.name.lowercase()}: $reason", at = clock.instant()))
    }

    /** What resuming a pending completion did (D-340). */
    private sealed interface Resumed {
        /** Nothing to apply now: the normal path runs (a cell, or final acceptance). */
        data object Continue : Resumed

        class Committed(val result: CompletionResult.Accepted) : Resumed

        class Stopped(val state: CampaignState) : Resumed
    }

    /**
     * D-340 resume, one path for S0 and S1: after open reconciled the effects and before any cell or budget check. An
     * open pending completion that still speaks for the tree, the contract, the environment and the increment's latest
     * cell is settled with a decision — `accept` commits with no model call, `rework` lets the continuation cell run,
     * none stops again. One that no longer does is void and the work continues in a cell. `null`: nothing pending.
     */
    private suspend fun resumePending(c: OpenedCampaign, authority: Authority): Resumed? {
        val pending = Acceptances(c.store, clock).open(c.ids.work, c.ids.attempt) ?: return null
        val ids = c.ids.copy(context = pending.cell)
        val state = checkNotNull(c.state)
        val report = c.stamper.report()
        val increment = pending.incrementId?.let { id -> state.graph.increments.firstOrNull { it.id == id } }
        val applied = increment?.status == IncrementStatus.Verified && state.graph.evidence[increment.id]?.let { it.stamp == pending.resultingStamp && it.contractVersion == pending.contractVersion } == true
        val void = when {
            applied -> null
            pending.contractVersion != c.contract.version -> "the contract moved to v${c.contract.version}"
            pending.resultingStamp != report.candidateId -> "the tree moved to @${report.candidateId.hash8}"
            pending.envId != report.env.envId -> "the environment changed"
            pending.incrementId == null -> null
            // WR P1-1: its results rest on inputs outside the candidate that no longer read as they did when asked.
            pending.outsideInputs != outsideInputs(c, scheduler(c), pending.results) -> "inputs outside the candidate its evidence pinned changed"
            increment == null -> "${pending.incrementId} is no longer in the graph"
            increment.status != IncrementStatus.InProgress && increment.status != IncrementStatus.Verified -> "${increment.id} is ${increment.status}"
            increment.cells.lastOrNull() != pending.cell || state.cells.firstOrNull { it.cell == pending.cell }?.status != CellStatus.Completed -> "a later cell superseded ${pending.cell?.value}"
            else -> null
        }
        if (applied) {
            // A stop between the commit and closing the record: the decision was applied once already.
            closePending(c, ids, pending, PendingStatus.Applied, "already committed at @${pending.resultingStamp.hash8}")
            return Resumed.Continue
        }
        if (void != null) {
            // Task-workflow §2.4 D: a request of an older revision is superseded, its record kept with the new revision.
            if (pending.contractVersion != c.contract.version) closePending(c, ids, pending, PendingStatus.Superseded, "superseded by contract v${c.contract.version}")
            else closePending(c, ids, pending, PendingStatus.Void, "$void; the work continues in a cell")
            return null
        }
        // The campaign gate's own pending completion is settled where final acceptance runs.
        if (increment == null) return Resumed.Continue
        // A commit goes on to final acceptance, which reads the green receipts this tree already has (C11 resume).
        heldReceipts(c)
        // WF-5 (c4): nothing moved since this completion was asked about, so a wait it comes to again says what lifts it.
        val settled = decide(c, ids, personReviewed(c, increment, pending, authority), authority)
        return resumed(c, ids, increment, if (settled is Settled.Wait) Settled.Wait(settled.pending, unchanged(settled.pending, settled.reason), settled.code) else settled)
    }

    /**
     * WD-09: a reopened attempt gets back every check's last receipt of the attempt — not only the regression checks'
     * (P8.C.10 F) — before any resume, reacceptance or finish: one about the candidate at [stamp] certifies it again, any
     * other is stale with its reason (§8.4: currency is still computed at use). Baselines and rerun markers are no result.
     */
    private fun restoreReceipts(checks: Checks, receipts: SqliteReceipts, ids: Identities, stamp: CandidateId) {
        for (check in checks.all().filter { it.last == null }) {
            val receipt = receipts.forCheck(check.id).lastOrNull {
                it.ids.work == ids.work && it.ids.attempt == ids.attempt && !io.astrolabe.verify.Regressions.isBaseline(it) && !io.astrolabe.verify.Regressions.isMarker(it)
            } ?: continue
            val last = io.astrolabe.verify.LastResult(receipt.receiptId, receipt.stampAfter, receipt.checkDefinitionVersion, receipt.outcome, receipt.parsed, io.astrolabe.verify.Applicability.Current)
            checks.record(check.id, when {
                receipt.checkDefinitionVersion != check.definitionVersion -> last.applied(io.astrolabe.verify.ApplicabilityVerdict(io.astrolabe.verify.Applicability.Stale, "check definition changed since ${receipt.receiptId}"))
                receipt.stampAfter != stamp -> last.applied(io.astrolabe.verify.ApplicabilityVerdict(io.astrolabe.verify.Applicability.Stale, "candidate moved @${receipt.stampAfter.hash8} → @${stamp.hash8} since ${receipt.receiptId}"))
                else -> last
            })
        }
    }

    /** A fresh registry knows no result: each check's last receipt is the one the stopped controller held, re-assessed now. */
    private fun heldReceipts(c: OpenedCampaign) {
        val receipts = SqliteReceipts(c.store, clock)
        for (check in c.checks.all().filter { it.last == null }) {
            // P8.C.10 F: a baseline (a run on s0) is never a check's last result.
            val receipt = receipts.forCheck(check.id).lastOrNull { it.ids.work == c.ids.work && it.ids.attempt == c.ids.attempt && !io.astrolabe.verify.Regressions.isBaseline(it) } ?: continue
            c.checks.record(check.id, io.astrolabe.verify.LastResult(receipt.receiptId, receipt.stampAfter, receipt.checkDefinitionVersion, receipt.outcome, receipt.parsed, io.astrolabe.verify.Applicability.Current))
        }
    }

    /**
     * C11 resume: under [IntegrityApproval.Human] the reviewer's results of [pending] again from the review records now —
     * its flags ([acceptanceFlags]: the kept cell's and the tree's) asked of the host once more, for a person, while no
     * person's usable verdict is stored — so neither a model's approval nor a result stored before C11 settles a flag on
     * resume, and one fresh record settles the packet's integrity, `check:` and `review:` results alike. An integrity
     * result whose flag is no longer on the tree waits for a person.
     */
    private suspend fun personReviewed(c: OpenedCampaign, increment: Increment, pending: PendingCompletion, authority: Authority): PendingCompletion {
        if (c.attempt.config.integrityApproval != io.astrolabe.IntegrityApproval.Human) return pending
        val kept = ReturnedCompletions(c.store, clock).latest(c.ids.work, c.ids.attempt)?.takeIf { it.cell == pending.cell }
        val flags = acceptanceFlags(c, kept?.testIntegrity().orEmpty().map { it.copy(verdict = null) })
        if (pending.results.none { it.kind == ObligationKind.Integrity } && flags.none { it.needsPerson }) return pending
        if (flags.any { it.needsPerson }) {
            personReview(c, authority, increment, flags, { evidence(c, increment, listOf("completion acceptance"), flags, kept?.preexisting.orEmpty(), authority) }, Tier.Medium)
        }
        val record = latestReview(c, increment, flags)
        val assessed = completionEvidence(c, increment, flags)
        val integrity = assessed.flags.mapNotNull { Obligations.flag(it, pending.contractVersion, pending.resultingStamp) }.associateBy { it.obligation }
        fun reviewed(r: ObligationResult): ObligationResult {
            if (record == null) return r
            val item = c.contract.acceptance(r.obligation)
            return when {
                item is Acceptance.Check || item is Acceptance.Review -> Obligations.verdict(r.obligation, if (item is Acceptance.Review) ObligationKind.Review else ObligationKind.Check, item.criterion,
                    assessed.verdicts[r.obligation], pending.contractVersion, pending.resultingStamp, assessed.unavailable[r.obligation]).copy(origin = item.origin)
                r.obligation == "review:${increment.id}" -> Obligations.verdict(r.obligation, ObligationKind.Review, "increment review (${record.path.joinToString(" → ")})",
                    record.verdict?.takeIf { record.unavailable == null }, pending.contractVersion, pending.resultingStamp, record.unavailable)
                else -> r
            }
        }
        val results = pending.results.map { r ->
            when {
                r.kind != ObligationKind.Integrity -> reviewed(r)
                r.obligation in integrity -> integrity.getValue(r.obligation)
                r.status == ResultStatus.Passed -> r.copy(status = ResultStatus.Unverified, detail = "${r.obligation}: ${Obligations.HUMAN_REVIEW}", humanOnly = true)
                else -> r.copy(humanOnly = true)
            }
        }
        return pending.copy(results = results + integrity.values.filter { flag -> results.none { it.obligation == flag.obligation } })
    }

    /** What a pending completion of [increment] settled outside a live return comes to on resume (D-340). */
    private fun resumed(c: OpenedCampaign, ids: Identities, increment: Increment, settled: Settled): Resumed? {
        val pending = settled.pending
        return when (settled) {
            is Settled.Commit -> {
                val accepted = Verifier().commit(pending.proposal(), c.contract, increment, pending.cell, checkNotNull(c.state).ledger, settled.resolved)
                commit(c, ids, null, increment, accepted, pending.resultingStamp)?.let {
                    closePending(c, ids, pending, PendingStatus.Void, "publication refused")
                    return Resumed.Stopped(it)
                }
                closePending(c, ids, pending, PendingStatus.Applied, settled.why)
                Resumed.Committed(accepted)
            }
            is Settled.Rework -> {
                closePending(c, ids, pending, PendingStatus.Void, "rework requested by ${settled.record.decision.by}")
                Resumed.Continue
            }
            is Settled.Wait -> Resumed.Stopped(c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, settled.reason, settled.code)))
            is Settled.Void -> {
                closePending(c, ids, pending, PendingStatus.Void, "${settled.reason}; the work continues in a cell")
                null
            }
        }
    }

    /**
     * Returns [exit]; a completed one is kept first (P8.C.8), with the sequence number of the row that says it returned.
     * [preexisting] is the compiled context's pre-existing ledger, which an owed increment review shows (§8.8).
     */
    private fun returned(
        c: OpenedCampaign, ids: Identities, exit: CellExit, selected: Routed.Selected?, preexisting: io.astrolabe.verify.PreexistingLedger?, function: RoutingFunction,
        impact: io.astrolabe.cell.ImpactNudges,
    ): ReturnedCompletion? {
        if (exit is CellExit.Partial && exit.handoff) {
            // A-D.6: the handoff's kept record precedes its row, as a returned completion's does; it carries the obligations.
            val grant = Handoffs(c.journal, idGen, clock, c.ids).current()?.id
            c.advance(Transition.Returned(exit)) { next ->
                ReturnedHandoffs(c.store, clock).save(ids, ReturnedHandoff.of(idGen.next("returned"), next.seq, exit, grant, function, selected?.tier, selected?.profile?.id, impact.unresolvedPublic))
            }
            return null
        }
        if (exit !is CellExit.Completed) {
            c.advance(Transition.Returned(exit))
            return null
        }
        var kept: ReturnedCompletion? = null
        c.advance(Transition.Returned(exit)) { next ->
            kept = ReturnedCompletion.of(idGen.next("returned"), next.seq, exit, selected?.tier, selected?.profile?.id, preexistingLines(preexisting)).also { ReturnedCompletions(c.store, clock).save(ids, it) }
        }
        return kept
    }

    /**
     * P8.C.8 resume, after [resumePending]: a completed cell whose return was saved while its outcome never was — the
     * campaign row is still the one the return wrote and no pending completion names the cell — is verified again from
     * its kept record with no cell and no model call, then takes the live return's paths: commit, a pending completion
     * settled with a decision, or a stop. An owed S2+ increment review comes from [review]: a current one is reused.
     * A refused completion is a verified failure counted by [refused] like a live one, then the continuation cell runs;
     * without [refused] (S0, D-64) it stops with its fallback. A tree, contract or environment that moved since voids it:
     * the work continues in a cell. `null`: nothing to verify.
     */
    private suspend fun resumeReturned(
        c: OpenedCampaign, authority: Authority,
        refused: (suspend (Increment, ReturnedCompletion, List<String>) -> CampaignState?)?, review: suspend (Increment, ReturnedCompletion) -> ReviewOutcome?,
    ): Resumed? {
        val kept = ReturnedCompletions(c.store, clock).latest(c.ids.work, c.ids.attempt) ?: return null
        val state = checkNotNull(c.state)
        // Every outcome of a return is a later transition or a pending completion of its cell: either retires the record.
        if (state.seq != kept.seq || Acceptances(c.store, clock).pending(c.ids.work, c.ids.attempt).any { it.cell == kept.cell }) return null
        // A stop between the record and its `Returned` row leaves the cell running; open marks it lost at that same seq.
        val increment = state.graph.increments.firstOrNull { it.id == kept.incrementId }
        if (increment?.status != IncrementStatus.InProgress || increment.cells.lastOrNull() != kept.cell ||
            state.cells.firstOrNull { it.cell == kept.cell }?.status != CellStatus.Completed) return null
        val ids = c.ids.copy(context = kept.cell)
        val report = c.stamper.report(fresh = true)
        val void = when {
            kept.contractVersion != c.contract.version -> "the contract moved to v${c.contract.version}"
            kept.resultingStamp != report.candidateId -> "the tree moved to @${report.candidateId.hash8}"
            kept.envId != report.env.envId -> "the environment changed"
            else -> null
        }
        c.journal.append(JournalEvent(idGen.next("ev"), ids, kept.turns, JournalKind.Reconcile, refs = listOf(kept.id),
            text = "open: cell ${kept.cell.value} returned but its outcome was never applied · " +
                (void?.let { "$it; the work continues in a cell" } ?: "verified again at @${kept.resultingStamp.hash8} with no model call"), at = clock.instant()))
        if (void != null) return null
        heldReceipts(c)
        refreshPrescan(c, ids, kept.touched, kept.turns, once = true)?.let { return Resumed.Stopped(c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, it))) }
        kept.answer?.let { return Resumed.Stopped(c.advance(Transition.Answered(report.candidateId, it))) }
        val completion = verify(c, kept, increment, report.candidateId, currencies(c, scheduler(c), report.candidateId), review(increment, kept))
        return when (val disposition = Lifecycle.completed(completion)) {
            is Disposition.Close -> commit(c, ids, kept.turns, increment, disposition.accepted, report.candidateId)?.let { Resumed.Stopped(it) } ?: Resumed.Committed(disposition.accepted)
            is Disposition.Continue -> if (refused == null) Resumed.Stopped(c.advance(Transition.Stopped(disposition.fallback, disposition.reason)))
                else IncrementAttempts.refusedFailure(completion as CompletionResult.Refused)?.let { missing -> refused(increment, kept, missing)?.let { Resumed.Stopped(it) } } ?: Resumed.Continue
            is Disposition.Stop -> if (completion is CompletionResult.Pending) resumed(c, ids, increment, settle(c, ids, increment, kept, completion, authority))
                else Resumed.Stopped(c.advance(Transition.Stopped(disposition.outcome, disposition.reason, disposition.code)))
        }
    }

    /**
     * D-340/D-341: the unspent `rework` decisions about [increment] as pinned blocks for its continuation cell — who
     * asked, what to change, and the reviewer findings the decision answered — with the records to spend on dispatch.
     */
    private fun reworkNotes(c: OpenedCampaign, increment: Increment): Pair<List<String>, List<DecisionRecord>> {
        val acceptances = Acceptances(c.store, clock)
        val records = acceptances.decisions(c.ids.work, c.ids.attempt).filter { !it.spent && it.incrementId == increment.id && it.decision.kind == DecisionKind.Rework }
        if (records.isEmpty()) return emptyList<String>() to emptyList()
        val pendings = acceptances.pending(c.ids.work, c.ids.attempt).associateBy { it.requestId }
        val lines = records.map { r ->
            buildString {
                append("rework requested by ").append(r.decision.by).append(": ").append(r.decision.reason)
                pendings[r.decision.requestId]?.results?.filter { it.reviewFailure }?.forEach { rejection ->
                    append("\nreview of ").append(rejection.obligation).append(" by ").append(rejection.by ?: "the reviewer").append(" found:")
                    rejection.findings.forEach { f -> append("\n- ").append(f.severity.name.lowercase()).append(' ').append(f.location).append(": ").append(f.issue) }
                }
            }
        }
        return lines to records
    }

    /** A `rework` decision acts on one continuation run (D-340): spent once its cell is dispatched. */
    private fun spendReworks(c: OpenedCampaign, cell: ContextId, records: List<DecisionRecord>) {
        val acceptances = Acceptances(c.store, clock)
        records.forEach { acceptances.spend(c.ids.copy(context = cell), it) }
    }

    private fun scheduler(c: OpenedCampaign): Scheduler =
        Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, c.ids, clock, candidates = candidates(c), retryCandidates = c.store.layout.candidates)

    /**
     * The model the host supplied behind each model [route] bound (C3r), by identity and held weakly: a child is routed
     * from its parent's routed model, and the balance profile must apply to the supplied one once, never again.
     */
    private val boundFrom: MutableMap<CellModel, CellModel> = java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    /** One cell's routing: the model to run it with, the selection to record, or the refusal that stops the campaign. */
    private class Routing(val model: CellModel, val compiled: Compiled, val selected: Routed.Selected?, val refused: Routed.Refused?)

    /**
     * The P2.2.2 routing hook (§11.2, P4.5.1): asked once per cell, never inside its loop. The candidates are the
     * attempt's configured profiles under its tier table; untiered, the supplied cell model serves every tier, so
     * a fake-profile campaign routes to the profile it was given (D-108). The risk floor reads the increment's (else
     * the contract's) declared risk and the open-time impact pre-scan; tokens are admitted by the cell itself (D-06),
     * so only a monetary budget caps affordability here (D-109). The router sees the compiled context's wire input,
     * growth reserve and output headroom apart. [compile] builds the context for a bound model — its own estimator and
     * output headroom; [initial] is the supplied model's context when one was already built (§6.6).
     *
     * Capacity fallback: a context the supplied profile's window cannot hold is compiled again on the candidates with a
     * larger window, smallest first; a routed profile whose window turns out too small for its own compile leaves the
     * candidates and the router is asked again. Only when no candidate fits does the context stay a rescoping.
     */
    private fun route(c: OpenedCampaign, function: RoutingFunction, increment: Increment, supplied: CellModel, previousTier: Tier?, initial: Compiled?, compile: (CellModel) -> Compiled): Routing {
        val config = c.attempt.config
        val contract = c.contract
        // C3: the attempt's balance profile bounds every candidate's window and steps the configured effort; Balanced changes neither.
        // C3r: it applies once, to the model the host supplied — a child routed from its parent's routed model starts from that one.
        val vector = BalanceProfiles.vector(config.balance)
        val base = boundFrom[supplied] ?: supplied
        val model = base.let { m ->
            val profile = BalanceProfiles.bounded(m.profile, vector)
            // C14: the host's explicit effort is stronger than the profile's step.
            val effort = BalanceProfiles.effort(m, vector)
            // A profile that changes nothing (Balanced) routes the supplied model as it always did.
            if (profile === m.profile && effort == m.effort) supplied else CellModel(m.adapter, profile, m.estimator, effort, m.maxOutputTokens, m.narrowedOutput, m.effortExplicit).also { boundFrom[it] = base }
        }
        val tiered = config.tierTable.profiles.isNotEmpty() && config.tierTable.profileIds.all { it in config.profiles }
        val table = if (tiered) config.tierTable else TierTable.single(model.profile.id)
        val candidates = if (tiered) config.profiles.mapValues { BalanceProfiles.bounded(it.value, vector) } else mapOf(model.profile.id to model.profile)
        val factory = estimators ?: io.astrolabe.provider.EstimatorFactory { model.estimator }
        // Always bound from the supplied model, so a narrowing is capped by each routed limit once, never compounded.
        fun bind(profile: Profile, effort: io.astrolabe.provider.Effort) =
            if (profile.id == model.profile.id && effort == model.effort) model else model.rebind(profile, effort, factory).also { boundFrom[it] = base }
        val first = initial ?: compile(model)
        val misfits = LinkedHashMap<String, String>()
        val fallback = first.windowBound()
        if (!fallback && first !is Compiled.Ready) return Routing(model, first, null, null)
        val bases = if (!fallback) sequenceOf(model to first) else {
            misfits[model.profile.id] = first.why()
            val window = model.profile.capabilities.contextLimitTokens
            candidates.values.filter { it.capabilities.contextLimitTokens > window }
                .sortedWith(compareBy<Profile>({ it.capabilities.contextLimitTokens }, { it.id })).asSequence()
                .mapNotNull { profile ->
                    val bound = bind(profile, model.effort)
                    val compiled = compile(bound)
                    if (compiled is Compiled.Ready) bound to compiled else null.also { misfits[profile.id] = compiled.why() }
                }
        }
        val reserves = contract.budget.reserves
        val cost = Accounting(c.store, clock).remainingCost(c.ids.work, contract.budget.cost)
        val budget = RoutingBudget(remainingCost = cost, reservedCost = cost?.let { Money(it.currency, it.amount.multiply(java.math.BigDecimal.valueOf(reserves.verification + reserves.recoveryAndPersist)), it.unknown) })
        val prescan = c.impactPrescan
        val impact = RiskFloorInput(prescan.contractsTouched.size, prescan.complete, prescan.prescan.fanIn, prescan.complete)
        // One base's routing: asked from that compile until a selection holds its own compile, or refused.
        fun routeFrom(current: CellModel, compiled: Compiled): Routing {
            while (true) {
                val arithmetic = compiled.selection.arithmetic
                val wire = (arithmetic.wireTokens ?: arithmetic.knownFixedTokens - arithmetic.reserveTokens - arithmetic.outputTokens + arithmetic.selectedTokens).toLong()
                val packet = RoutingPacket(increment.risk ?: contract.risk, wire, current.maxOutputTokens, previousTier = previousTier,
                    featureClass = "${contract.shape.name.lowercase()}:${increment.expectedFiles}", reserveTokens = arithmetic.reserveTokens.toLong())
                val policy = RoutingPolicy(table, candidates - misfits.keys, budget, configuredEffort = model.effort)
                when (val routed = router.selectProfile(function, packet, impact, policy, io.astrolabe.route.RoutingLog(c.store, clock), c.ids)) {
                    is Routed.Deterministic -> return Routing(current, compiled, null, null)
                    is Routed.Refused -> {
                        if (misfits.isEmpty()) return Routing(current, compiled, null, routed)
                        // The refusal names the candidates set aside for their window beside the router's own exclusions.
                        val excluded = misfits.mapValues { (_, why) -> "its window does not hold the context: $why" } + routed.excluded
                        return Routing(current, compiled, null, Routed.Refused(routed.function, routed.tier, routed.options, excluded, routed.trace))
                    }
                    is Routed.Selected -> {
                        if (routed.profile.id == current.profile.id && routed.effort == current.effort) return Routing(current, compiled, routed, null)
                        val bound = bind(routed.profile, routed.effort)
                        // The same profile keeps its estimator and headroom; another one compiles with its own.
                        val again = if (routed.profile.id == current.profile.id) compiled else compile(bound)
                        if (!again.windowBound()) return Routing(bound, again, routed, null)
                        misfits[routed.profile.id] = again.why()
                    }
                }
            }
        }
        var refusal: Routing? = null
        for ((current, compiled) in bases) {
            val routing = routeFrom(current, compiled)
            // A refused fallback base gives way to the next: its own output headroom may admit candidates this one's excluded.
            if (routing.refused == null || !fallback) return routing
            refusal = routing
        }
        return refusal ?: Routing(model, (first as Compiled.NeedsRescoping).copy(reason = fallbackReason(first.reason, misfits)), null, null)
    }

    /** A context a larger window could hold: the selection itself overflowed, not a carry-forward gap or missing evidence. */
    private fun Compiled.windowBound(): Boolean = this is Compiled.NeedsRescoping && selection.status == ContextSelectionStatus.Capacity

    private fun Compiled.why(): String = when (this) {
        is Compiled.NeedsRescoping -> reason
        is Compiled.NeedsEvidence -> "needs evidence $missing"
        is Compiled.Ready -> "ready"
    }

    private fun fallbackReason(reason: String, misfits: Map<String, String>): String =
        if (misfits.size <= 1) reason else "$reason; no candidate window fits — " + misfits.entries.joinToString("; ") { (id, why) -> "$id: $why" }

    /**
     * The verified outcome of a cell for the calibration log: the harness's exit and the verifier's resolution, never the
     * model's claim (A3). A completion accepted only on a decider's word, or waiting for one, is unverified.
     */
    private fun outcomeOf(exit: CellExit?, completion: CompletionResult? = null): RoutingOutcome = when (exit) {
        is CellExit.Completed -> when (completion) {
            is CompletionResult.Accepted -> if (completion.verified) RoutingOutcome.Accepted else RoutingOutcome.Unverified
            is CompletionResult.Refused -> RoutingOutcome.VerifiedFailure
            is CompletionResult.Pending -> RoutingOutcome.Unverified
            is CompletionResult.NotCompleted, null -> if (exit.pending != null) RoutingOutcome.Unverified else RoutingOutcome.Accepted
        }
        is CellExit.Failed -> RoutingOutcome.VerifiedFailure
        is CellExit.Blocked, is CellExit.Partial, is CellExit.Cancelled, null -> RoutingOutcome.Unverified
    }

    /** A cell's outcome as [runCell] hands it back: `null` [exit] when a cancellation interrupted it; [impact] is its nudge ledger. */
    private class CellRun(val exit: CellExit?, val ids: Identities, val scheduler: Scheduler, val checkpoints: SqliteCheckpoints, val impact: io.astrolabe.cell.ImpactNudges)

    /**
     * Builds the tools and context of one cell over [increment] under [role] and runs it (§3.6): shared by the S0 path,
     * the S1 loop's increment cells and the plan cell. Every compiled context leaves a manifest; the cell's cost is
     * priced once for its span.
     */
    /** What one compile carries from the base (§6.3, P4.1.3): the ranked notes under the `kbInjection` arm and the contracts index. */
    private class Knowledge(val notes: List<Note>, val contractsIndex: String?, val log: String, val skills: List<Skill> = emptyList(), val skillConflicts: List<SkillConflict> = emptyList())

    private fun knowledge(c: OpenedCampaign, increment: Increment, role: Role, model: CellModel, touched: Set<String> = emptySet()): Knowledge {
        val arm = c.attempt.config.flags.kbInjection
        val all = if (arm == KbInjection.Frozen) c.frozenNotes else Notes(c.store).all()
        if (all.isEmpty()) return Knowledge(emptyList(), null, "no notes")
        val contracts = all.filter { it.kind == NoteKind.CON && it.status == NoteStatus.Admitted }
        val inputs = InjectionInputs(
            role, c.ids.work, increment.writeScope, touched, contractsInPlay = contracts.map { it.id }.toSet(),
            stamp = c.stamper.report().candidateId.digest.hex, usage = Usage(c.store, clock).all(), currentVersion = { c.registry.version(it) },
            dependencyVersions = Notes(c.store).versions(),
        )
        val ranked = Injection.select(all, inputs, model.estimator)
        // `Off` keeps F23 (CON in scope compiled in) and drops the ranked advice; `Frozen`/`Live` differ in the base they rank.
        val result = if (arm != KbInjection.Off) ranked else InjectionResult(
            ranked.selected.filter { it.mandatory },
            ranked.excluded + ranked.selected.filter { !it.mandatory }.map { InjectionExclusion(it.note.id, "ranked injection is off (kbInjection arm)") },
        )
        val index = if (contracts.isEmpty()) null else KbIndex.render(all, model.estimator).getValue("contracts.md")
        // D-112/D-160: triggers are evaluated at the increment's opening (a StateChange), never per turn; the compiler
        // applies the role's skill filter. The task's authority is the CON/ADR notes this compile carries.
        val skillStore = SkillStore(c.store)
        val admittedSkills = all.filter { it.kind == NoteKind.SKILL && it.status == NoteStatus.Admitted }.mapNotNull { skillStore.load(it) }
        val opened = StateChange(StateChangeKind.IncrementOpened, (c.atlas.rows.map { it.path }.filter { p -> increment.writeScope.any { PathPattern.matches(it, p) } } + touched).distinct().sorted(), increment.title)
        val skills = Skills.resolve(admittedSkills, opened, result.notes.filter { it.kind == NoteKind.CON || it.kind == NoteKind.ADR }.map { it.id }.toSet())
        val skillLog = if (skills.active.isEmpty()) "" else " · skills ${skills.active.joinToString(", ") { it.id }}" + skills.conflicts.joinToString("") { " · ${it.line}" }
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = result.notes.map { it.id }, text = "kb ${arm.name.lowercase()} for ${increment.id}: ${result.log}$skillLog", at = clock.instant()))
        return Knowledge(result.notes, index, result.log, skills.active, skills.conflicts)
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
        /** The tree the cell edits: the main line's unless a writer runs in its worktree (§10.4). */
        tree: CellTree = CellTree.main(c),
        /** D-340: the controller dispatches this cell on a decider's `rework` answer (C1b reads it as `GateState.reworked`). */
        rework: Boolean = false,
        /** A-D.6: the loop has no continuation of its own (`runS0`); the cell reads it as `CellContext.turnBudgetHandoff`. */
        turnBudgetHandoff: Boolean = false,
        /** A-D.6: the handed-off cell this epoch continues; its completion obligations bind this cell's. */
        continues: ReturnedHandoff? = null,
    ): CellRun {
        val ids = c.ids.copy(context = cellId)
        val cancellation = child?.cancellation ?: c.cancellation
        // C3: under a minutes limit, the default `run` deadline and the check time boxes never exceed the time left.
        val config = limitControl.bounded(c, c.attempt.config)
        val contract = c.contract
        val redaction = Redaction(config.redaction)
        tree.workspace.paths.bindWriteProtection { path, ignoreCase -> c.contracts.current(c.ids.work)?.scope?.protects(path, ignoreCase) != false }
        val logs = c.store.layout.root.resolve("logs")
        val estimator = model.estimator
        val runner = TrustedLocalRunner(c.os)
        val visibleSeeds = Seeds.selected(seeds, compiled)
        val workset = Workset(immediateStubTokens = config.defaults.immediateStubTokens).also { it.seed(visibleSeeds) }
        val observations = SqliteObservations(c.store, clock)
        val aliases = SqliteAliases(c.store, clock)
        val receipts = SqliteReceipts(c.store, clock)
        val registerVersions = SqliteRegisterVersions(c.store, clock)
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val preimages = Preimages(tree.workspace, c.store.blobs, ids, clock, c.store)
        val isolated = child?.isolated == true
        val scheduler = Scheduler(tree.checks, tree.workspace, tree.registry, tree.stamper, receipts, aliases, idGen, ids, clock, candidates = if (isolated) c.store.layout.candidates else candidates(c), isolateAll = isolated, retryCandidates = c.store.layout.candidates)
        val checker = Checker(tree.checks, runner, c.os, tree.stamper, tree.registry, tree.workspace, c.store.blobs, redaction, idGen, ids, logs)
        val layered = plugged(c)
        // P8.C.10: verify-on-stop classifies a held red of the blast radius or the types of touched files against s0 (main line
        // only); the model's own verify(baseline) stays unconfigured, as before.
        val baseline = if (child == null) Baseline(c.shadow, c.store.layout, runner, c.os, receipts, aliases, c.store.blobs, redaction, estimator, idGen, ids, clock, EnvFingerprint.compute(env)) else null
        val verify = Verify(checks = tree.checks, scheduler = scheduler, checker = checker, baseline = null, s0 = tree.s0, workspace = tree.workspace, runner = runner, os = c.os, stamper = tree.stamper, blobs = c.store.blobs, redaction = redaction, estimator = estimator, idGen = idGen, ids = ids, contracts = c.contracts, logsDir = logs, checkerTimeBoxSeconds = config.defaults.checkerTimeBoxSeconds.toLong(), checkerFallbackTimeBoxSeconds = config.defaults.checkerFallbackTimeBoxSeconds.toLong(), campaignReview = campaignReview(c, authority),
            // §8.8: review(scope=increment) is the review cell for S2+ main-line cells; a review cell never reaches it (no verify.review in its mask).
            incrementReview = if (child == null && contract.shape >= Shape.S2) IncrementReview { why -> reviewCell(c, increment, model, authority, syntax, span).obtain(evidence(c, increment, listOf(why), emptyList(), preexistingLines(compiled.k.ledger), authority), Tier.Medium, c.registry::version) } else null,
            tiers = layered.tiers,
        )
        verify.inputs = tree.atlas.rows.map { it.path }
        verify.regressionBaseline = baseline
        // C3r: every check and run deadline is cut at its dispatch to the active time the minutes limit leaves then.
        val timeLeft = limitControl.timeLeft(c)
        verify.timeLeft = timeLeft
        val ceiling = Ceiling.of(contract.authorization, config.executionMode)
        val generation = c.lease?.generation ?: ExecutionGeneration.INITIAL
        // §10.1 (D-121): an S2+ main-line cell whose role unmasks task.delegate delegates to child cells; a child never does.
        val children = if (child == null && contract.shape >= Shape.S2 && role.effectiveOps(contract.shape, ceiling).allows("task.delegate")) {
            CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        } else {
            null
        }
        val delegator = children?.let { scope ->
            val reviews: (io.astrolabe.delegate.TaskPacket) -> EvidencePacket = { evidence(c, increment, listOf("delegated by ${cellId.value}"), emptyList(), preexistingLines(compiled.k.ledger), authority) }
            val arithmetic = compiled.selection.arithmetic
            val fixed = (arithmetic.wireTokens ?: arithmetic.knownFixedTokens - arithmetic.reserveTokens - arithmetic.outputTokens + arithmetic.selectedTokens).toLong()
            val worth = { kind: io.astrolabe.delegate.ChildKind, packet: io.astrolabe.delegate.TaskPacket ->
                WorthTest.estimate(kind, packet, estimator.estimate(ChildBrief.render(packet, Probe.OUTPUT)).upperBoundTokens, fixed, config.defaults)
            }
            Delegator(CellChildRunner(childCell(c, increment, model, authority, syntax, span), reviewBudget = reviewBudget(c), evidence = reviews), PublicationAuthority { c.refusal() }, c.cancellation, DelegationLimits.of(config.defaults, contract.budget.tokens), contract.shape, scope, idGen, clock, events, worth = worth)
        }
        val tools = CellTools(
            state = StateTool(Validator(estimator, registerCapTokens = config.defaults.registerCapTokens, patchCapTokens = config.defaults.patchCapTokens, factLineMaxChars = config.defaults.factLineMaxChars, protocol = role.protocol), registerVersions, c.journal, estimator, idGen, ids, clock, register ?: Register.empty(cellId, increment.id, increment.title), events),
            look = Look(tree.workspace, tree.registry, workset, tree.atlas, search(c), c.journal, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, checks = tree.checks, mounts = layered.mounts, bmaps = BmapStore(c.store), tools = layered.tools, tiers = layered.tiers, budgetTokens = config.defaults.lookBudgetTokens),
            edit = Edit(
                tree.workspace, tree.registry, workset, c.os, preimages, ScopeGuard(tree.workspace), c.contracts, tree.checks, observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, syntax,
                // D-99: `revert:turn:N` names the tree's shadow snapshots (the turn checkpoint records them).
                shadowRef = tree.shadow,
                transforms = TransformExecution(runner, tree.stamper, logs, config.executionMode, EnvPolicy(inheritedNames = config.redaction.envAllowlist, extra = mapOf("CI" to "1", "NO_COLOR" to "1"))),
            ),
            run = Run(tree.workspace, tree.registry, tree.stamper, runner, c.os, c.intents, SqliteHandles(c.store, clock), observations, aliases, c.store.blobs, redaction, estimator, idGen, ids, c.contracts, authority, config, clock, logs, catalog = layered.mounts, tools = layered.tools,
                readOnlyRole = role.name.takeIf { Roles.readOnlyRuns(role) }),
            verify = verify,
            task = TaskTool(
                authority, c.contracts, c.journal, estimator, idGen, ids, clock, events, role.effectiveOps(contract.shape, ceiling), proposals ?: if (child == null && contract.shape >= Shape.S1) CampaignProposals(
                    SqlitePlanProposals(c.store, idGen, clock), SqliteSplitRequests(c.store, idGen, clock),
                    { c.contracts.current(c.ids.work) }, { registerVersions.latest(cellId) }, { c.kb.contractAnchors() }) else null,
                delegator, delegator?.let { TaskPackets(WORKSPACE, ceiling, generation) }, tree.registry::version,
                answerCheck = if (child == null && role.packetKind == io.astrolabe.cell.PacketKind.Result) { { answerable(c) } } else null,
                // Task-workflow §5.1: the model's output proposal, validated by the harness; it applies from the next attempt.
                declareOutput = if (child == null) { { path, reason ->
                    DeclaredOutputs.refusal(c, path) ?: run { DeclaredOutputs.declare(c, path, io.astrolabe.contract.OutputDeclarer.Model, reason, ids.context, clock); null }
                } } else null,
                autoDeclareOutputs = HostPolicy.stored(c.journal, c.ids.work).autoDeclareOutputs,
            ),
            kb = KbTool(c.kb, estimator, idGen, queue = Queue(c.store, KbWriter(c.store, estimator, clock), idGen, clock), ids = ids, events = events, deniedKinds = role.deniedNoteKinds, dense = layered.dense, redaction = redaction),
        )
        (tools.run as Run).timeLeft = timeLeft
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
        val knowledge = KnowledgeUse(focusBase, injected, usage, ids, estimator, config.defaults.focusNotesMaxTokens, KbNegatives(c.journal, idGen, clock),
            inputs = { InjectionInputs(role, ids.work, increment.writeScope, currentVersion = tree.registry::version, dependencyVersions = Notes(c.store).versions()) },
            currentNotes = {
                val live = Notes(c.store).all().associateBy { it.id }
                focusBase.mapNotNull { frozen -> live[frozen.id]?.takeIf { it == frozen } }
            },
        )
        val coherence = Coherence(tree.registry)
        // C3: every cell, a child's included, asks the task limits before each turn and each admission, and its accounting
        // checks them again in the transaction that writes the call's hold, so concurrent cells never pass a limit together.
        val cellLimits = limitControl.cell(c, model)
        val accounting = Accounting(c.store, clock, cellLimits.admission)
        // §6.5: every compiled context leaves a manifest; the cell's end event links it.
        val manifests = SqliteManifests(c.store, clock)
        val manifest = Manifest.of(idGen.next("manifest"), compiled, increment, contract, ids, model.profile, inputs, register?.version, boundary, model.effort.name.lowercase()).also { manifests.save(ids, it) }
        // D-345: the host's notes come first, marked as the host's; a child cell's brief is its parent's business.
        val hostBlock = if (child == null && c.hostNotes.isNotEmpty()) listOf(HOST_NOTES + c.hostNotes.joinToString("\n") { "- $it" }) else emptyList()
        val impact = io.astrolabe.cell.ImpactNudges().also { it.carry(continues?.impactNudges().orEmpty()) }
        val ctx = CellContext(
            ids = ids, role = RoleTexts.worded(role, config.role(role.name)), contracts = c.contracts, model = model, tools = tools,
            workspace = CellWorkspace(tree.workspace, tree.registry, coherence, tree.stamper, workset, tree.checks, scheduler, tree.atlas, checker),
            evidence = CellEvidence(c.journal, observations, aliases, receipts, c.intents, registerVersions, checkpoints, preimages),
            prime = c.prime, ledger = ledger, preexisting = compiled.k.ledger, config = config,
            turnCheckpoint = TurnCheckpoint { snapshot(c, tree, ids) },
            generation = generation,
            accounting = accounting,
            manifest = manifest.id,
            sections = compiled.k.sections,
            pinned = hostBlock + pinned,
            precompile = precompile,
            rework = rework,
            turnBudgetHandoff = turnBudgetHandoff,
            carriedFlags = continues?.testIntegrity().orEmpty(),
            impact = impact,
            acknowledged = acknowledged(c),
            knowledge = knowledge,
            completionEvidence = if (child == null && role.packetKind == io.astrolabe.cell.PacketKind.Result) { raised ->
                val flags = acceptanceFlags(c, raised)
                val required = increment.accept.any { c.contract.acceptance(it) is Acceptance.Check || c.contract.acceptance(it) is Acceptance.Review } || flags.any { it.blocksCompletion }
                if (required) {
                    // D-261/D-320: a flag-only review (no Check/Review item) goes to the review cell in every shape unless Human.
                    val flagOnly = increment.accept.none { c.contract.acceptance(it) is Acceptance.Check || c.contract.acceptance(it) is Acceptance.Review }
                    val packet = { evidence(c, increment, listOf("completion acceptance"), flags, preexistingLines(compiled.k.ledger), authority) }
                    if (humanIntegrity(c, flags)) personReview(c, authority, increment, flags, packet, Tier.Medium)
                    else if (c.contract.shape >= Shape.S2 && increment.accept.none { c.contract.acceptance(it) is Acceptance.Check } || flagOnly)
                        reviewCell(c, increment, model, authority, syntax, span).obtain(packet(), Tier.Medium, c.registry::version)
                    else hostReviewer(c, authority).obtain(packet(), Tier.Medium, c.registry::version)
                }
                completionEvidence(c, increment, flags)
            } else null,
            noteHorizon = if (tree.workspace === c.workspace) NoteHorizon(KbWriter(c.store, estimator, clock), Notes(c.store), ids) else null,
        // WD-16: a child is admitted on its own budget, its output bounded by what that budget leaves.
        ).also {
            it.boundedOutput = child != null
            // §3.7 (T-42): a person's approval of the flagged test edits, given through task.ask, is that person's verdict on them.
            if (child == null && role.packetKind == io.astrolabe.cell.PacketKind.Result &&
                increment.accept.none { a -> c.contract.acceptance(a) is Acceptance.Check || c.contract.acceptance(a) is Acceptance.Review }) {
                it.flagApproval = { paths, raised, question, _ -> personFlagVerdict(c, ids, increment, paths, raised, question) }
            }
        }
        val limits = cellLimits.gate
        val budget = child?.budget?.let { CellBudget.of(it.tokens, it.turns, contract.budget.reserves, limits = limits) }
            ?: CellBudget.of(Tokens(Accounting(c.store, clock).remainingTokens(c.ids.work, contract.budget.tokens.value).coerceAtLeast(3)), contract.budget.turnsPerCell, contract.budget.reserves, limits = limits)
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
            return CellRun(null, ids, scheduler, checkpoints, impact)
        }
        if (cellSpan != null && spans != null) {
            // The cell's exclusive cost is its own model calls, priced once here and never again by a parent.
            val cost = Accounting.totals(accounting.calls(c.ids.work).filter { it.ids.context == cellId }, 0, spans.currency).money
            spans.end(cellSpan, cost)
        }
        return CellRun(exit, ids, scheduler, checkpoints, impact)
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
        // C3r: a child is a branch of the run: a host wait elsewhere stops the minutes clock only while it waits too.
        ChildCell { seat, declared, completion, budget, brief -> limitControl.branch(c) {
            // D-38: the frozen attempt configuration words the child's role; its mask and packet stay the caller's.
            val role = RoleTexts.worded(declared, c.attempt.config.role(declared.name))
            // §11.1: a child is routed by its own function row, never by the parent's tier; an escalated tier is its floor.
            val routing = route(c, seat.function, increment, model, seat.tier, null) { bound ->
                Compiler(bound.estimator, c.attempt.config).compile(increment, c.contract, bound.profile, role, c.prime, pinned = listOf(brief), maxOutputTokens = bound.maxOutputTokens)
            }
            val compiled = routing.compiled as? Compiled.Ready ?: throw ChildNotStarted("the ${role.name} child of ${increment.id} cannot be compiled: ${routing.compiled}")
            routing.refused?.let { throw ChildNotStarted("the ${role.name} child of ${increment.id} is unaffordable: ${it.reason}") }
            runCell(c, seat.context, increment, role, routing.model, authority, syntax, compiled, span, null, completion = completion, pinned = listOf(brief), child = ChildForm(seat.cancellation, budget, isolated = role.name == Roles.review.name))
                .exit.also { exit -> routing.selected?.let { router.record(it, outcomeOf(exit)) } }
        } }

    /**
     * The controller-side writer cell (§10.4, D-181): the child-context form of [runCell] over the writer's worktree —
     * its own workspace, registry, stamper, shadow ref and checks — with the writer role, the slice brief pinned and
     * the child's cancellation and budget. Its exit is kept in [exits] for the campaign state (D-243).
     */
    private fun writerCell(c: OpenedCampaign, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?, exits: MutableMap<String, CellExit>): WriterCell =
        WriterCell { seat, dispatch, declared, budget, brief -> limitControl.branch(c) {
            val increment = checkNotNull(c.state).graph.increments.first { it.id == dispatch.task.incrementId }
            val role = RoleTexts.worded(declared, c.attempt.config.role(declared.name))
            val routing = route(c, seat.function, increment, model, seat.tier, null) { bound ->
                Compiler(bound.estimator, c.attempt.config).compile(increment, c.contract, bound.profile, role, c.prime, pinned = listOf(brief), maxOutputTokens = bound.maxOutputTokens)
            }
            val compiled = routing.compiled as? Compiled.Ready ?: throw ChildNotStarted("the writer of ${increment.id} cannot be compiled: ${routing.compiled}")
            routing.refused?.let { throw ChildNotStarted("the writer of ${increment.id} is unaffordable: ${it.reason}") }
            val tree = CellTree.writer(c, dispatch.worktree, EnvFingerprint.compute(env), clock)
            runCell(c, seat.context, increment, role, routing.model, authority, syntax, compiled, span, checkNotNull(c.state).ledger, pinned = listOf(brief), child = ChildForm(seat.cancellation, budget), tree = tree)
                .exit.also { exit ->
                    // The writer's final bytes stay in its own shadow ref after the worktree is removed (§10.4).
                    snapshot(c, tree)
                    routing.selected?.let { router.record(it, outcomeOf(exit)) }
                    exit?.let { exits[dispatch.handle.id] = it }
                }
        } }

    /** D-242: each pending unit's writer-token estimate from its compiled writer `[K]`; `null` (slack unmeasured) when one cannot compile. */
    private fun writerEstimates(c: OpenedCampaign, model: CellModel): Map<String, Long>? {
        val role = RoleTexts.worded(Roles.writer, c.attempt.config.role(Roles.writer.name))
        val compiler = Compiler(model.estimator, c.attempt.config)
        return checkNotNull(c.state).graph.increments.filter { it.status == IncrementStatus.Pending }.associate { increment ->
            val compiled = compiler.compile(increment, c.contract, model.profile, role, c.prime, maxOutputTokens = model.maxOutputTokens) as? Compiled.Ready ?: return null
            val arithmetic = compiled.selection.arithmetic
            // The writer's turn input is its wire context and growth; writerEstimate adds the output once.
            increment.id to writerEstimate((arithmetic.totalTokens ?: arithmetic.knownFixedTokens + arithmetic.selectedTokens).toLong() - arithmetic.outputTokens.toLong(), model.maxOutputTokens)
        }
    }

    private fun reviewCell(c: OpenedCampaign, increment: Increment, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): ReviewCell =
        ReviewCell(CellReviewJudge(childCell(c, increment, model, authority, syntax, span), idGen, c.cancellation, reviewBudget(c)), authority, c.store, idGen, clock, c.journal)

    /** The §8.8 review budgets the attempt's settings carry (`reviewLookMax`, `reviewIncrementTokens`, `reviewCampaignTokens`). */
    private fun reviewBudget(c: OpenedCampaign): ReviewBudget = c.attempt.config.defaults.let {
        ReviewBudget(it.reviewLookMax, Tokens(it.reviewIncrementTokens.toLong()), Tokens(it.reviewCampaignTokens.toLong()))
    }

    /**
     * The increment-scope review of a completed cell (§8.8), when a trigger owes one: the review cell at its row's tier,
     * a current approval reused, the human path as fallback. `null` when no review is owed.
     */
    private suspend fun incrementReview(c: OpenedCampaign, increment: Increment, kept: ReturnedCompletion, model: CellModel, authority: Authority, syntax: SyntaxCheck, span: SpanId?): ReviewOutcome? {
        val prescan = c.impactPrescan
        val impact = RiskFloorInput(prescan.contractsTouched.size, prescan.complete, prescan.prescan.fanIn, prescan.complete)
        val flags = acceptanceFlags(c, kept.testIntegrity())
        val triggers = ReviewTriggers.increment(IncrementReviewInput(c.contract, increment, mainLine(c), kept.tier, flags, kept.changed, c.kb.contractAnchors(), impact))
        if (triggers.isEmpty()) return null
        val row = FunctionTable.DEFAULT.row(ReviewTriggers.function(triggers))
        val packet = { evidence(c, increment, triggers, flags, kept.preexisting, authority) }
        // C11: a review that waits for a person is the host's, never cut short by a task limit — it costs no model call here.
        if (humanIntegrity(c, flags)) return personReview(c, authority, increment, flags, packet, row.defaultTier)
        val outcome = reviewCell(c, increment, model, authority, syntax, span).obtain(packet(), row.defaultTier, c.registry::version)
        // C3: a review a task limit cut short says so — an unverified result, never a block and never another cause.
        val block = c.limitState.block?.decision
        if (outcome !is ReviewOutcome.Unavailable || block == null) return outcome
        val why = "review unavailable: task limit (" + when (block) {
            is LimitDecision.Exhausted -> "${block.kind.wire}): ${block.reason}"
            is LimitDecision.Reserve -> "${block.kind.wire}): ${block.reason}"
            LimitDecision.Within -> "none)"
        }
        return ReviewOutcome.Unavailable(outcome.record.copy(unavailable = why), why)
    }

    private fun preexistingLines(ledger: io.astrolabe.verify.PreexistingLedger?): List<String> = ledger?.entries.orEmpty().map { "${it.identity} — ${it.signature}" }

    /** D-320: under [IntegrityApproval.Human] a blocking test-integrity flag is resolved only through `Authority.review`. */
    private fun humanIntegrity(c: OpenedCampaign, flags: List<io.astrolabe.verify.TestIntegrityFlag>): Boolean =
        c.attempt.config.integrityApproval == io.astrolabe.IntegrityApproval.Human && flags.any { it.blocksCompletion }

    /**
     * C11: the test-integrity flags acceptance resolves. Under [IntegrityApproval.Human] they are those [raised] by the cell
     * and every acceptance-surface change since s0 on the tree now ([FinishReceipts.surfaceChanges], D-396) — so a later
     * cell of the increment that says `done` without touching the test leaves no change unreviewed — each one only a
     * person's approving verdict resolves; a change from the tree keeps the justification an earlier cell recorded for
     * its path. Otherwise [raised] as they are.
     */
    private fun acceptanceFlags(c: OpenedCampaign, raised: List<io.astrolabe.verify.TestIntegrityFlag>): List<io.astrolabe.verify.TestIntegrityFlag> {
        if (c.attempt.config.integrityApproval != io.astrolabe.IntegrityApproval.Human) return raised
        val known = raised.map { it.path }.toSet()
        val tree = FinishReceipts.surfaceChanges(c, c.stamper.report().members.keys + c.s0.paths, SINCE_S0).filter { it.path !in known }
        if (tree.isEmpty()) return raised.map { it.copy(humanOnly = true) }
        val reasons = ReturnedCompletions(c.store, clock).all(c.ids.work, c.ids.attempt).flatMap { it.flags }.mapNotNull { f -> f.reason?.let { f.path to it } }.toMap()
        return (raised + tree.map { it.copy(reason = reasons[it.path]) }).map { it.copy(humanOnly = true) }
    }

    /**
     * C11: the host's review of [increment] for a person — asked once per open for this candidate and contract revision; a
     * later ask in the same open reads the stored answer, so a host without a person is not asked on every proposal (a
     * reopen asks again, under the question's first request id: [ReviewCell.obtain]).
     */
    private suspend fun personReview(c: OpenedCampaign, authority: Authority, increment: Increment, flags: List<io.astrolabe.verify.TestIntegrityFlag>, packet: () -> EvidencePacket, tier: Tier): ReviewOutcome {
        val stored = latestReview(c, increment, flags)
        if (!c.personAsked.add("${increment.id}@${c.stamper.report().candidateId.hash8}@v${c.contract.version}") && stored != null) return when {
            stored.unavailable != null -> ReviewOutcome.Unavailable(stored, stored.unavailable)
            stored.approved -> ReviewOutcome.Approved(stored)
            else -> ReviewOutcome.Declined(stored, "review ${stored.verdict?.outcome?.name?.lowercase()} by ${stored.verdict?.signedBy}", emptyList())
        }
        return hostReviewer(c, authority).obtain(packet(), tier, c.registry::version)
    }

    /**
     * Task-workflow §3.7 (T-42): a person's approval of the flagged test edits [paths] — their answer to the model's
     * [question] — recorded as that person's verdict on the cell's flags: a review record on the human path bound to the
     * candidate, the contract revision and the paths' versions like a review (§5.9), which the completion's evidence reads
     * and no review asks again. Its criteria are the increment's items, none of them a `check:` or `review:` (the caller's rule).
     */
    private fun personFlagVerdict(c: OpenedCampaign, ids: Identities, increment: Increment, paths: List<String>, raised: List<io.astrolabe.verify.TestIntegrityFlag>, question: io.astrolabe.event.Question): String {
        val flags = acceptanceFlags(c, raised)
        val stamp = c.stamper.report().candidateId
        val verdict = io.astrolabe.verify.Verdict(question.id, c.contract.version, stamp, io.astrolabe.verify.VerdictOutcome.Approve, confidence = 1.0, signedBy = "user",
            reviewer = io.astrolabe.verify.ReviewerKind.Human)
        val record = io.astrolabe.delegate.ReviewRecord(question.id, ReviewScope.Increment, increment.id, c.contract.version, stamp, increment.accept,
            paths.mapNotNull { path -> c.registry.version(path)?.let { path to it } }.toMap(), verdict, path = listOf(io.astrolabe.delegate.ReviewRecord.HUMAN),
            integrity = flags.map { it.copy(verdict = null).line + it.originalObligation.orEmpty() })
        ReviewCell.save(c.store, idGen, clock, ids, record)
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(question.id),
            text = "flagged ${paths.joinToString(", ")} approved by the user's answer to ${question.id}: a person's verdict on the flag (§3.7)", at = clock.instant()))
        return "recorded as your verdict on the flagged ${paths.joinToString(", ")} at @${stamp.hash8}: a later change asks again"
    }

    private fun hostReviewer(c: OpenedCampaign, authority: Authority): ReviewCell =
        ReviewCell(io.astrolabe.delegate.ReviewJudge { _, _ -> io.astrolabe.delegate.JudgeRun(null, Tokens(0), "host assessment required") }, authority, c.store, idGen, clock, c.journal)

    /**
     * Campaign scope (§8.8, D-124): in S2+ the campaign gate's review request is answered by the review cell over the
     * whole diff, the contract, the receipts and the rubric, with [host] as the human fallback; S0/S1 keep the host.
     */
    private fun campaignJudge(c: OpenedCampaign, host: Authority, model: CellModel, syntax: SyntaxCheck, span: SpanId?): Authority {
        val contract = c.contract
        if (contract.shape < Shape.S2) return host
        val whole = Increment(CAMPAIGN_REVIEW, contract.requirements.map { it.id }, contract.acceptance.map { it.id }, emptyList(), 0, title = "campaign review ${c.ids.work.value}")
        val judge = CellReviewJudge(childCell(c, whole, model, host, syntax, span), idGen, c.cancellation, reviewBudget(c))
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

    private suspend fun hostAssessment(c: OpenedCampaign, increment: Increment, authority: Authority) {
        ReviewCell(io.astrolabe.delegate.ReviewJudge { _, _ -> io.astrolabe.delegate.JudgeRun(null, Tokens(0), "host assessment required") },
            authority, c.store, idGen, clock, c.journal).obtain(
            evidence(c, increment, listOf("current acceptance assessment"), emptyList(), emptyList(), authority), Tier.Medium, c.registry::version)
    }

    /**
     * The reviewers' results for [increment] at the tree now (D-337, D-341): the latest review recorded for this
     * candidate and contract version — an approval, a rejection or no usable verdict alike — the current acceptance
     * decision and whether a rework round was spent on this candidate. An item never reviewed at this candidate is in
     * neither map.
     */
    private fun completionEvidence(c: OpenedCampaign, increment: Increment, flags: List<io.astrolabe.verify.TestIntegrityFlag> = emptyList()): io.astrolabe.cell.CompletionEvidence {
        val stamp = c.stamper.report().candidateId
        val contract = c.contract
        val record = latestReview(c, increment, flags)
        val acceptances = Acceptances(c.store, clock)
        val decision = decisionFor(c, increment.id, stamp, contract.version)
        val reworkSpent = acceptances.reworkSpent(c.ids.work, c.ids.attempt, increment.id, stamp, contract.version)
        val independent = increment.accept.filter { contract.acceptance(it) is Acceptance.Check || contract.acceptance(it) is Acceptance.Review }
        val verdict = record?.verdict?.takeIf { record.unavailable == null }
        val human = c.attempt.config.integrityApproval == io.astrolabe.IntegrityApproval.Human
        // D-320: a review-cell verdict still speaks for Check/Review items, but under Human only the host's path speaks for
        // a flag — C11: and only a person's verdict resolves it; a model's stays attached as the person's information.
        val flagVerdict = verdict?.takeUnless { human && record.path.lastOrNull() != io.astrolabe.delegate.ReviewRecord.HUMAN }
        return io.astrolabe.cell.CompletionEvidence(
            verdicts = if (verdict != null) independent.associateWith { verdict } else emptyMap(),
            unavailable = if (record != null && verdict == null) independent.associateWith { record.unavailable ?: "no usable verdict" } else emptyMap(),
            flags = flags.map { it.copy(verdict = flagVerdict, humanOnly = human) },
            decision = decision,
            reworkSpent = reworkSpent,
        )
    }

    /** The latest review of [increment] recorded for the tree now, its criteria and its [flags]' integrity evidence. */
    private fun latestReview(c: OpenedCampaign, increment: Increment, flags: List<io.astrolabe.verify.TestIntegrityFlag>): io.astrolabe.delegate.ReviewRecord? {
        val stamp = c.stamper.report().candidateId
        val integrity = flags.map { it.copy(verdict = null).line + it.originalObligation.orEmpty() }
        return c.store.db.query("SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC",
            c.ids.work, c.ids.attempt, ReviewCell.KIND) { Json.decodeFromString(io.astrolabe.delegate.ReviewRecord.serializer(), it.string("body")) }
            .firstOrNull { it.incrementId == increment.id && it.criteria.containsAll(increment.accept) && (flags.isEmpty() || it.integrity == integrity) &&
                it.freshness(c.contract.version, stamp, c.registry::version) == io.astrolabe.delegate.Freshness.Current }
    }

    /**
     * The §8.8 evidence packet of [increment] at the tree now (D-124): the diff `s0 → now`, the increment's complete
     * acceptance definitions, the current receipts with parsed counts (required when they certify its items), the
     * CON/ADR anchors on the changed paths, the test-integrity flags, the pre-existing ledger and the rubric.
     */
    private fun evidence(c: OpenedCampaign, increment: Increment, triggers: List<String>, flags: List<io.astrolabe.verify.TestIntegrityFlag>, preexisting: List<String>, authority: Authority): EvidencePacket {
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
            preexisting = preexisting, coverage = null,
            rubric = EvidencePacket.RUBRIC + limits.map { "diff limit: $it" },
            evidenceVersions = changed.mapNotNull { path -> c.registry.version(path)?.let { path to it } }.toMap(), triggers = triggers,
        )
    }

    /**
     * `finish` (§3.7) with the campaign gate (§8.7, D-343): every requirement verified, then the campaign's obligations —
     * the full suite, every acceptance item at the final stamp, the owed campaign review — resolved by the same rule as
     * an increment. A red executed check fails; what cannot be verified waits for a decision, never blocks or fails
     * (I1); an item a decider accepted for this candidate keeps that provenance and is not asked again. A stored campaign
     * pending completion at this candidate is decided on its stored results: nothing runs twice (I3).
     */
    private suspend fun stopOrFinish(c: OpenedCampaign, unfinished: String, scheduler: Scheduler? = null, campaign: Boolean = false, authority: Authority? = null): CampaignState {
        val state = checkNotNull(c.state)
        if (state.ledger.unfinished().isNotEmpty() || scheduler == null) {
            return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, unfinished))
        }
        val attempt = c.finishAttempts.incrementAndGet()
        val counting = PhaseMark.begin()
        try {
            return counting.runSuspending { finalAttempt(c, scheduler, campaign, authority) }
        } finally {
            events?.emit(counting.counted(c.ids, CountedPhase.Finish, opens.get(), attempt))
        }
    }

    /**
     * One finalization attempt of [stopOrFinish], every requirement verified (§8.7). WD-08: the end checks — the full suite
     * and the quality gates — run before the candidate is fixed, so what they write belongs to the candidate their evidence
     * is about, and the increments are accepted again at it ([rebind]). WF-5, WF-6: a campaign pending completion at the
     * candidate the attempt starts on — open, or voided by a decision that still stands — means nothing changed since it
     * was asked: it is decided on its stored results and nothing runs (I3). WD-10: a request is reissued under the id it
     * was first asked with while its [DecisionKey] holds; a stop it comes to again says so and what lifts it.
     */
    private suspend fun finalAttempt(c: OpenedCampaign, scheduler: Scheduler, campaign: Boolean, authority: Authority?): CampaignState {
        val acceptances = Acceptances(c.store, clock)
        val contract = c.contract
        val entry = c.stamper.report(fresh = true)
        // The same obligations too: an item added at the same revision is new information. An applied record counts — a
        // stop between closing it and `Finishing` resumes on it, with no end check run again after the accept (WF-6).
        val obligations = obligationSet(c, contract)
        // WR P1-1: and the pinned inputs outside identity still read as they did when it was asked; else its evidence is void.
        val stored = acceptances.pending(c.ids.work, c.ids.attempt).lastOrNull {
            it.incrementId == null && it.resultingStamp == entry.candidateId && it.contractVersion == contract.version && it.envId == entry.env.envId && it.obligationSet == obligations
        }?.takeIf { it.outsideInputs == outsideInputs(c, scheduler, it.results) }
        val suite = if (stored == null && campaign) fullSuite(c, "campaign end") else null
        val report = if (suite == null) entry else c.stamper.report(fresh = true)
        val stamp = report.candidateId
        val ids = c.ids.withCandidate(stamp)
        val carried = carriedAcceptances(c, stamp)
        val results = stored?.results ?: campaignResults(c, scheduler, campaign, suite, authority, stamp)
            .filterNot { it.status != ResultStatus.Passed && it.obligation in carried }
        val outside = stored?.outsideInputs ?: outsideInputs(c, scheduler, results)
        val key = DecisionKey.of(null, stamp, contract.version, Resolver.resolve(results, reworkSpent = true).undecided.map { it.obligation }, outside)
        // There is no cell to rework a campaign-level rejection: it goes to the authority at once. WR2 (P1 #1): only a decision
        // made under this very key speaks for it — another key's pins or obligations were another question.
        var resolved = Resolver.resolve(results, decision = decisionFor(c, null, stamp, contract.version, key), reworkSpent = true)
        val asked = stored ?: acceptances.pending(c.ids.work, c.ids.attempt).lastOrNull {
            it.incrementId == null && it.status != PendingStatus.Applied && it.contractVersion == contract.version && it.envId == report.env.envId && it.obligationSet == obligations && it.key() == key
        }
        var pending = stored
        if (resolved.resolution == Resolution.Await && authority != null) {
            val record = stored?.takeIf { it.status == PendingStatus.Open } ?: (
                asked?.copy(results = results, gaps = resolved.gaps, code = checkNotNull(resolved.code), evidence = results.mapNotNull { it.evidenceRef }.distinct(), status = PendingStatus.Open, closedReason = null)
                    ?: PendingCompletion(
                        idGen.next("pending"), c.ids.work, c.ids.attempt, null, null, contract.version, c.s0.stampId, stamp, null, report.env.envId, null,
                        emptyList(), results, emptyList(), resolved.gaps, checkNotNull(resolved.code), results.mapNotNull { it.evidenceRef }.distinct(), null, idGen.next("decide"),
                        obligationSet = obligations, outsideInputs = outside,
                    )
                ).also {
                    // One campaign question at a time: an open one about another obligation set is superseded, never answered.
                    acceptances.pending(c.ids.work, c.ids.attempt).filter { p -> p.incrementId == null && p.status == PendingStatus.Open && p.id != it.id }
                        .forEach { p -> closePending(c, ids, p, PendingStatus.Void, "superseded by ${it.id}") }
                    acceptances.save(ids, it)
                    c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = it.evidence, text = "final acceptance awaits ${it.code.wire} (${it.id}, request ${it.requestId}" +
                        (if (asked != null) ", reissued under key $key" else "") + "): ${resolved.missing.joinToString("; ")}", at = clock.instant()))
                }
            pending = record
            when (val settled = decide(c, ids, record, authority)) {
                is Settled.Commit -> resolved = settled.resolved
                is Settled.Rework -> {
                    closePending(c, ids, record, PendingStatus.Void, "rework requested by ${settled.record.decision.by}")
                    return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "rework requested at campaign scope by ${settled.record.decision.by}: ${settled.record.decision.reason} — amend the contract or start a follow-up task"))
                }
                is Settled.Wait -> return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, if (asked != null) unchanged(record, settled.reason) else settled.reason, settled.code))
                is Settled.Void -> {
                    closePending(c, ids, record, PendingStatus.Void, settled.reason)
                    val after = c.stamper.report()
                    val why = if (after.candidateId != report.candidateId || c.contract != contract) movedDuringReview(c, report, after, contract)
                        else "${settled.reason} (request ${record.requestId}): verification and review must be repeated"
                    return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, why))
                }
            }
        }
        when (resolved.resolution) {
            Resolution.Complete -> Unit
            Resolution.Await -> return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, "final acceptance needs a decision: ${resolved.missing.joinToString("; ")}", resolved.code))
            Resolution.Rework -> return c.advance(
                if (resolved.results.any { it.executedFailure }) Transition.Stopped(CampaignOutcome.Failed, "final acceptance at @${stamp.hash8} failed: ${resolved.missing.joinToString("; ")}")
                else Transition.Stopped(CampaignOutcome.WaitingForInput, "final acceptance at @${stamp.hash8}: ${resolved.missing.joinToString("; ")}" +
                    (asked?.let { " — unchanged since request ${it.requestId} was decided about @${stamp.hash8}: amending the contract or starting a follow-up task lifts it" } ?: "")),
            )
        }
        val receipts = (resolved.evidenceRefs + carried.values.mapNotNull { it.evidenceRef }).distinct()
        if (receipts.isEmpty()) return c.advance(Transition.Stopped(CampaignOutcome.Failed, "final acceptance at @${stamp.hash8}: no acceptance evidence"))
        val now = c.stamper.report(fresh = true)
        if (now.candidateId != stamp || c.contract != contract) {
            return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal, movedDuringReview(c, report, now, contract)))
        }
        // WR2 (P1 #1): the pinned inputs outside identity are re-read before the commit too; the stamp does not see them.
        if (outsideInputs(c, scheduler, results) != outside) {
            return c.advance(Transition.Stopped(CampaignOutcome.BlockedExternal,
                "inputs outside the candidate the final evidence pinned changed during final review (@${stamp.hash8}): verification and review must be repeated"))
        }
        c.refusal()?.let { return c.advance(Transition.Stopped(stopOutcome(c), "final acceptance not published: $it")) }
        rebind(c, scheduler, report, resolved.decision)?.let { return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput, it)) }
        pending?.let { closePending(c, ids, it, PendingStatus.Applied, resolved.decision?.let { d -> "accepted by ${d.decision.by}: ${d.decision.reason}" } ?: "final acceptance held") }
        crashAfterFinalApplied?.invoke()
        c.advance(Transition.Finishing(stamp))
        crashAfterFinishing?.invoke()
        return c.advance(Transition.Finished(stamp, receipts))
    }

    /**
     * Test fault point (W1): runs after the campaign pending completion is closed applied and before `Finishing` is saved,
     * where a crash leaves an applied record and an unfinished campaign; the reopen must not run an end check again.
     */
    internal var crashAfterFinalApplied: (() -> Unit)? = null

    /**
     * Test fault point (WR): runs after `Finishing` is saved and before `Finished`, where a crash leaves the campaign
     * finishing; the reopen resets its ledger and must restore it from the applied record without a check or a question.
     */
    internal var crashAfterFinishing: (() -> Unit)? = null

    /**
     * WR2 (P1 #1): the latest unspent decision about [incrementId] that speaks for [candidate] at [contractVersion] and for
     * the question asked: under [key] when the caller knows it, and in any case only while the inputs outside identity its
     * request pinned still read at those bytes, re-read now. A record from before the key was kept matches as it did.
     */
    private fun decisionFor(c: OpenedCampaign, incrementId: String?, candidate: CandidateId, contractVersion: Int, key: String? = null): DecisionRecord? {
        val scheduler by lazy { scheduler(c) }
        return Acceptances(c.store, clock).decisions(c.ids.work, c.ids.attempt).lastOrNull {
            !it.spent && it.incrementId == incrementId && it.appliesTo(candidate, contractVersion) &&
                (key == null || it.key == null || it.key == key) && (it.outsideInputs.isEmpty() || scheduler.readsNow(it.outsideInputs))
        }
    }

    /** WR P1-1: the inputs outside candidate identity the receipts behind [results] pinned, at their bytes now ([Scheduler.outsideIdentity]). */
    private fun outsideInputs(c: OpenedCampaign, scheduler: Scheduler, results: List<ObligationResult>): Map<String, FileVersion> {
        val receipts = SqliteReceipts(c.store, clock)
        return scheduler.outsideIdentity(results.mapNotNull { it.evidenceRef }.distinct().mapNotNull { receipts.get(it) })
    }

    /**
     * The campaign gate's obligation set (W1), digested: the contract's acceptance items — a `run:` item's command is its
     * check's definition — and the definitions of the end checks (the full suite, the quality gates). At one candidate,
     * revision and environment only an item added at the same revision (strengthening) can change it.
     */
    private fun obligationSet(c: OpenedCampaign, contract: Contract): String {
        val items = contract.acceptance.sortedBy { it.id }.map { item ->
            val command = (item as? Acceptance.Run)?.let { Json.encodeToString(io.astrolabe.contract.Command.serializer(), it.command) }.orEmpty()
            DecisionKey.delimited(listOf(item.id, item::class.simpleName.orEmpty(), item.criterion, item.obligationVersion.toString(), command))
        }
        val checks = c.checks.all().filter { it.trigger == io.astrolabe.verify.Trigger.CampaignEnd }.sortedBy { it.id }.map { "${it.id}=${it.definitionVersion.hex}" }
        return io.astrolabe.id.Digest.ofUtf8(io.astrolabe.id.CanonicalEncoding.encode("campaign-obligations", 1,
            listOf("acceptance" to DecisionKey.delimited(items), "checks" to DecisionKey.delimited(checks)))).hex
    }

    /**
     * WD-08: the verified increments accepted at another candidate than [report]'s — the end checks moved the tree after
     * them — accepted again there from the current receipts and the campaign gate's [decision], with no cell and no model
     * call (FX-42), so the ledger the finish records holds at that candidate. `null` when every one holds; else why not.
     * [restoring] (WR P1-4): also those accepted there already whose ledger entries an interrupted finalization reset — each
     * with the decision its accepted items name, which an earlier gate request may have made.
     */
    private fun rebind(c: OpenedCampaign, scheduler: Scheduler, report: io.astrolabe.workspace.StampReport, decision: DecisionRecord?, restoring: Boolean = false): String? {
        val stamp = report.candidateId
        val state = checkNotNull(c.state)
        val graph = state.graph
        val reset = if (restoring) state.ledger.unfinished().toSet() else emptySet()
        val decisions = if (restoring) Acceptances(c.store, clock).decisions(c.ids.work, c.ids.attempt) else emptyList()
        for (increment in graph.increments.filter { it.status == IncrementStatus.Verified && (graph.evidence[it.id]?.stamp != stamp || it.requirementIds.any { r -> r in reset }) }) {
            val cell = increment.cells.lastOrNull() ?: return "final acceptance at @${stamp.hash8}: ${increment.id} has no cell to accept it again"
            val proposal = CompletionProposal(increment.id, PacketStatus.Done.wire, c.contract.version, stamp, stamp, null, report.env.envId)
            val evidence = completionEvidence(c, increment)
            // An accepted item's provenance names the request its decision answered (I7).
            val named = graph.evidence[increment.id]?.takeIf { it.stamp == stamp }?.provenance.orEmpty()
                .filter { it.how == io.astrolabe.verify.ProvenanceKind.Accepted }.mapNotNull { it.evidenceRef }.distinct().singleOrNull()
            val own = named?.let { request -> decisions.lastOrNull { !it.spent && it.decision.requestId == request && it.appliesTo(stamp, c.contract.version) } }
            // The gate's decision named these items at this candidate; it speaks for the increment's own (I7: accepted, never verified).
            val result = Verifier().accept(proposal, c.contract, increment, Register.empty(cell, increment.id, increment.title), checkNotNull(c.state).ledger, stamp,
                currencies(c, scheduler, stamp), evidence.verdicts, evidence.unavailable, decision = (own ?: decision)?.copy(incrementId = increment.id) ?: evidence.decision, reworkSpent = true, acknowledged = acknowledged(c))
            if (result !is CompletionResult.Accepted) {
                val why = (result as? CompletionResult.Pending)?.missing ?: (result as? CompletionResult.Refused)?.missing
                return "final acceptance at @${stamp.hash8}: verified ${increment.id} does not hold at the candidate the end checks left: ${why?.joinToString("; ") ?: result::class.simpleName}"
            }
            c.advance(Transition.Committed(result, stamp))
        }
        return null
    }

    /** WF-5: a stop the same pending completion comes to again — nothing changed since it was asked — names the request and what lifts it. */
    private fun unchanged(pending: PendingCompletion, reason: String): String =
        "$reason — unchanged since request ${pending.requestId} (key ${pending.key()}) was asked about @${pending.resultingStamp.hash8}: an accept or rework answer to it, or a change to the tree or the contract, lifts it"

    /** WD-15: why the candidate a final decision or review was about no longer stands — the moved paths, or the contract. */
    private fun movedDuringReview(c: OpenedCampaign, before: io.astrolabe.workspace.StampReport, after: io.astrolabe.workspace.StampReport, contract: Contract): String {
        val moved = Stamper.diff(before, after)
        val what = when {
            c.contract.version != contract.version -> "contract v${contract.version} → v${c.contract.version}"
            c.contract != contract -> "contract v${contract.version} changed"
            moved.isEmpty() -> "the environment fingerprint changed"
            else -> "moved: " + moved.take(MAX_MOVED).joinToString(", ") + (if (moved.size > MAX_MOVED) " (+${moved.size - MAX_MOVED} more)" else "")
        }
        return "candidate or contract changed during final review (@${before.candidateId.hash8} → @${after.candidateId.hash8}, $what); verification and review must be repeated"
    }

    /** Acceptance items a decider accepted for [stamp] at increment level (I7): their provenance stands at the campaign gate. */
    private fun carriedAcceptances(c: OpenedCampaign, stamp: CandidateId): Map<String, io.astrolabe.verify.ItemProvenance> {
        val state = checkNotNull(c.state)
        return state.graph.increments.filter { it.status == IncrementStatus.Verified }
            .mapNotNull { state.graph.evidence[it.id] }
            .filter { it.stamp == stamp && it.contractVersion == c.contract.version }
            .flatMap { it.provenance }.filter { it.how == io.astrolabe.verify.ProvenanceKind.Accepted }
            .associateBy { it.item }
    }

    /**
     * The campaign gate's obligations at [stamp] (§8.7, D-343), as results — never as stops: the declared full suite and
     * quality gates ([suite], already run on the tree [stamp] names: WD-08), every `run:` item's current receipt, every
     * independent item's assessment, and the campaign review when the §8.8 predicate owes one (with the refactor-mode
     * equivalence evidence).
     */
    private suspend fun campaignResults(c: OpenedCampaign, scheduler: Scheduler, campaign: Boolean, suite: FullSuite?, authority: Authority?, stamp: CandidateId): List<ObligationResult> {
        val state = checkNotNull(c.state)
        val contract = c.contract
        val refactor = RefactorMode.isActive(contract)
        val reviewOwed = if (campaign) CampaignFinish.reviewRequired(contract, state.graph.increments.count { it.status != IncrementStatus.Cancelled }, refactor) else null
        val results = ArrayList<ObligationResult>()
        when (suite) {
            is FullSuite.Red -> results += ObligationResult(FULL_SUITE, ObligationKind.Run, ResultStatus.Failed, "final full suite red: ${suite.detail}")
            is FullSuite.NotCertified -> results += ObligationResult(FULL_SUITE, ObligationKind.Run, ResultStatus.Unverified, "final full suite could not certify: ${suite.detail}", suite.receipt, rewrittenInputs = suite.rewritten)
            FullSuite.Green -> results += ObligationResult(FULL_SUITE, ObligationKind.Run, ResultStatus.Passed, "final full suite green", c.checks[Checks.FULL]?.last?.receiptId)
            FullSuite.Undeclared, null -> Unit
        }
        val currencies = currencies(c, scheduler, stamp)
        for (item in contract.acceptance.filterIsInstance<Acceptance.Run>()) {
            val checks = c.checks.forAcceptance(item.id)
            val currency = checks.firstNotNullOfOrNull { check -> currencies[check.id]?.takeIf { it.certifies } } ?: checks.firstNotNullOfOrNull { currencies[it.id] }
            results += Obligations.run(item.id, item.criterion, currency)
        }
        for (increment in state.graph.increments.filter { it.status == IncrementStatus.Verified }) {
            var assessed = completionEvidence(c, increment)
            val independent = increment.accept.filter { contract.acceptance(it) is Acceptance.Check || contract.acceptance(it) is Acceptance.Review }
            if (independent.any { id -> id !in assessed.verdicts && id !in assessed.unavailable } && authority != null) {
                hostAssessment(c, increment, authority)
                assessed = completionEvidence(c, increment)
            }
            for (id in independent) {
                val item = contract.acceptance(id) ?: continue
                val kind = if (item is Acceptance.Review) ObligationKind.Review else ObligationKind.Check
                results += Obligations.verdict(id, kind, item.criterion, assessed.verdicts[id], contract.version, stamp, assessed.unavailable[id])
            }
        }
        // WR2 (P1 #6, task-workflow §3.3): a goal claim the model stated after the plan is in no increment's acceptance; the
        // final decides each one still strengthening a live requirement — unverified until a decider takes it, never skipped.
        val assessedItems = results.mapTo(HashSet()) { it.obligation }
        for (item in contract.acceptance.filterIsInstance<Acceptance.Check>().filter { it.id !in assessedItems }) {
            val strengthens = (item.origin as? io.astrolabe.contract.Origin.Model)?.strengthens?.split('+') ?: continue
            if (strengthens.none { contract.requirement(it)?.lapsed == false }) continue
            results += Obligations.verdict(item.id, ObligationKind.Check, item.criterion, null, contract.version, stamp,
                "the model's goal criterion for ${strengthens.joinToString("+")}, stated after the plan: no increment assessed it").copy(origin = item.origin)
        }
        if (reviewOwed != null) {
            if (authority == null) {
                results += ObligationResult(CAMPAIGN_REVIEW, ObligationKind.Review, ResultStatus.Unverified, "$reviewOwed; no authority to review")
            } else {
                // §8.9 items 5–6, D-23: the equivalence evidence, then the signed campaign-scope review — unverified when unavailable, never skipped.
                val reviewer = campaignReview(c, authority)
                val equivalence = if (refactor) reviewer.equivalence(stamp, currencies) else null
                if (refactor && equivalence == null) {
                    results += ObligationResult(EQUIVALENCE, ObligationKind.Run, ResultStatus.Unverified, "refactor mode without a behaviour snapshot: no equivalence evidence at @${stamp.hash8} (§8.9 item 5)")
                } else {
                    val current = currencies.values.filter { it.certifies }.mapNotNull { it.receiptId }.distinct()
                    results += when (val review = reviewer.review(contract, c.s0.stampId, current, equivalence, reviewOwed)) {
                        is CampaignReviewOutcome.Approved -> ObligationResult(CAMPAIGN_REVIEW, ObligationKind.Review, ResultStatus.Passed, "$reviewOwed; approved by ${review.record.verdict?.signedBy}", review.record.request.id, review.record.verdict?.signedBy)
                        is CampaignReviewOutcome.Declined -> Obligations.verdict(CAMPAIGN_REVIEW, ObligationKind.Review, reviewOwed, review.record.verdict, contract.version, stamp, review.reason)
                        is CampaignReviewOutcome.Unavailable -> ObligationResult(CAMPAIGN_REVIEW, ObligationKind.Review, ResultStatus.Unverified, "$reviewOwed; ${review.reason}")
                    }
                }
            }
        }
        return results.distinctBy { it.obligation }
    }

    /** The D-23 human review path of this campaign: `Authority.review` over the full diff, the receipts and the rubric built from the admitted plan. */
    private fun campaignReview(c: OpenedCampaign, authority: Authority): CampaignReview {
        fun plan(): PlanPacket? = SqlitePlanProposals(c.store, idGen, clock).latest(c.ids.work, null)?.packet
        return CampaignReview(
            authority, c.shadow, c.workspace, c.stamper, c.store, c.journal, SqliteReceipts(c.store, clock), c.checks, idGen, c.ids, clock,
            checklist = { plan()?.refactorChecklist },
            conReferences = { plan()?.let { p -> p.conReferences + p.conCandidates.map { "new: ${it.summary}" } }.orEmpty() },
        ).also { review -> (authority as? ReviewCellAuthority)?.let { judged -> review.unanswered = judged::unanswered } }
    }

    private sealed interface FullSuite {
        data object Green : FullSuite
        data object Undeclared : FullSuite
        data class Red(val detail: String) : FullSuite
        /** [receipt]: the first uncertifying check's last receipt (WR2, P1 #1: its pinned inputs outside identity are in the key). */
        data class NotCertified(val detail: String, val rewritten: List<String> = emptyList(), val receipt: String? = null) : FullSuite
    }

    /** Runs the declared full suite through the `verify` tool path (receipts, closures, redaction) and journals the result. */
    private suspend fun fullSuite(c: OpenedCampaign, why: String): FullSuite {
        val check = c.checks[Checks.FULL]
        val ids = c.ids.copy(context = ContextId(idGen.next("finish")))
        // §8.1: every declared gate must certify the same final candidate as the suite.
        val gates = c.checks.all().filter { it.kind == CheckKind.Quality }.map { it.id }
        val before = c.stamper.report()
        if (gates.isNotEmpty()) harnessVerify(c, ids, "quality", """{"what":"tests","selection":"ids","ids":[${gates.joinToString(",") { "\"$it\"" }}]}""")
        if (check != null) harnessVerify(c, ids, "full", """{"what":"tests","selection":"full"}""")
        val after = c.stamper.report(fresh = true)
        val stamp = after.candidateId
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, ids, clock)
        val required = (gates + listOfNotNull(check?.id)).mapNotNull { c.checks[it] }
        val currency = required.associate { it.id to scheduler.currency(it, stamp) }
        // W3 (P1-4): a factual red stays red when its check also rewrote an input; only certifying needs eligibility.
        val red = required.firstOrNull { currency.getValue(it.id).let { c -> c.red && c.applicability == io.astrolabe.verify.Applicability.Current && (c.eligible || c.rewrittenInputs.isNotEmpty()) } }
        val gap = required.firstOrNull { !currency.getValue(it.id).certifies }
        val certification = when {
            red != null -> FullSuite.Red("${red.id} ${red.last?.receiptId} failed at @${stamp.hash8}")
            gap != null -> FullSuite.NotCertified("${gap.id}: ${currency.getValue(gap.id).reasons.joinToString("; ").ifEmpty { gap.last?.outcome?.name?.lowercase() ?: "not run" }} at @${stamp.hash8}" +
                if (after.candidateId != before.candidateId) " · the gates and suite moved the stamp from @${before.candidateId.hash8}: ${movedPathsHint(before, after)}" else "",
                required.flatMap { currency.getValue(it.id).rewrittenInputs }.distinct().sortedWith(Stamper.PATH_ORDER), currency.getValue(gap.id).receiptId)
            else -> null
        }
        if (check == null) {
            c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "full suite ($why): none declared by the repository — an explicit gap, acceptance runs stand" + (certification?.let { " · $it" } ?: ""), at = clock.instant()))
            return certification ?: FullSuite.Undeclared
        }
        val last = c.checks[Checks.FULL]?.last
        val result = certification ?: FullSuite.Green
        c.journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOfNotNull(last?.receiptId), text = "full suite ($why): ${result::class.simpleName!!.lowercase()}", at = clock.instant()))
        return result
    }

    /** One `verify` call the harness makes on its own authority (full suite, regression obligations), outside any cell. */
    private suspend fun harnessVerify(c: OpenedCampaign, ids: Identities, callId: String, args: String): io.astrolabe.tool.ToolOutcome {
        val config = c.attempt.config
        val redaction = Redaction(config.redaction)
        val logs = c.store.layout.root.resolve("logs")
        val runner = TrustedLocalRunner(c.os)
        val scheduler = Scheduler(c.checks, c.workspace, c.registry, c.stamper, SqliteReceipts(c.store, clock), SqliteAliases(c.store, clock), idGen, ids, clock, candidates = candidates(c), retryCandidates = c.store.layout.candidates)
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

    /** Records the main line as the next shadow snapshot, when it moved since the last one. */
    private fun snapshot(c: OpenedCampaign) = snapshot(c, CellTree.main(c))

    /** Records [tree] as the next snapshot of its own shadow ref, when it moved since the last one; counted (§7.2). */
    private fun snapshot(c: OpenedCampaign, tree: CellTree, ids: Identities = c.ids) {
        val counting = PhaseMark.begin()
        try {
            counting.run {
                val last = tree.shadow.records().last()
                val now = tree.dirty.capture(last.turn + 1)
                if (now.manifestDigest != checkNotNull(tree.shadow.manifest(last.turn)).manifestDigest) tree.shadow.snapshot(now)
            }
        } finally {
            events?.emit(counting.counted(ids, CountedPhase.Snapshot, opens.get(), c.finishAttempts.get()))
        }
    }

    /**
     * Members whose bytes differ from the last snapshot, recorded as the next snapshot so the following open
     * compares against it. A crashed mutation after its snapshot shows here too: the tree, not the model, says
     * what moved (FX-23).
     */
    /**
     * Task-workflow §2.4 A–C, at a boundary with no running cell: the work each recorded message gets. A cancelled or
     * replaced requirement's open increment is cancelled (C); an amendment's requirements get `inc-n`, depending on every
     * verified increment (B); a `continuation` or `steering` message that finds every increment closed gets a response
     * increment `inc-U-n` (A), so it reaches a model (WF-13). Each step is journalled (§2.4 E). The stop state when an
     * amendment derives no valid increment.
     */
    private fun intake(c: OpenedCampaign): CampaignState? {
        val opened = c.state ?: return null
        if (opened.phase != CampaignPhase.Running || opened.running != null) return null
        val contract = c.contract
        fun line(refs: List<String>, text: String) = c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Reconcile, refs = refs, text = text, at = clock.instant()))
        for (increment in opened.graph.increments.filter { it.status != IncrementStatus.Verified && it.status != IncrementStatus.Cancelled }) {
            if (increment.requirementIds.any { contract.requirement(it)?.lapsed != true }) continue
            val reason = "${increment.requirementIds.joinToString(", ")} cancelled or replaced by the user (contract v${contract.version})"
            c.advance(Transition.IncrementCancelled(increment.id, reason))
            line(listOf(increment.id), "cancelled ${increment.id}: $reason")
        }
        val graph = checkNotNull(c.state).graph
        val covered = graph.increments.filter { it.status != IncrementStatus.Cancelled }.flatMap { it.requirementIds }.toSet()
        val amended = contract.requirements.filter { r ->
            !r.lapsed && r.id !in covered && contract.requests.any { it.id == r.authorityRef && contract.kindOf(it) == MessageKind.Amendment }
        }
        for ((requestId, requirements) in amended.groupBy { it.authorityRef }) {
            val state = checkNotNull(c.state)
            val verified = state.graph.increments.filter { it.status == IncrementStatus.Verified }.map { it.id }
            val known = state.graph.increments.map { it.id }.toMutableSet()
            val added = requirements.map { r ->
                val id = "inc-${r.id.removePrefix("R")}".let { if (it in known) "$it-$requestId" else it }.also { known += it }
                Increment(id, listOf(r.id), r.acceptance, contract.scope.writePaths, 0, title = r.text.lineSequence().first().take(TITLE_CHARS),
                    dependsOn = verified, produces = io.astrolabe.graph.Production.Artifact)
            }
            val issues = state.graph.copy(increments = state.graph.increments + added).validate(contract)
            if (issues.isNotEmpty()) {
                return c.advance(Transition.Stopped(CampaignOutcome.WaitingForInput,
                    "amendment $requestId (contract v${contract.version}) derives no valid increment: ${issues.joinToString("; ") { it.detail }} — state a run: acceptance for it"))
            }
            c.advance(Transition.Amended(contract.version, requestId, added))
            line(listOf(requestId) + added.map { it.id }, "amended v${contract.version} by $requestId: ${added.joinToString(", ") { it.id }} added" +
                (if (verified.isEmpty()) "" else " (depends on ${verified.joinToString(", ")})"))
        }
        val state = checkNotNull(c.state)
        if (state.graph.increments.any { it.status != IncrementStatus.Verified && it.status != IncrementStatus.Cancelled }) return null
        val message = contract.requests.drop(maxOf(1, state.messagesSeen)).lastOrNull { r ->
            contract.kindOf(r).let { it == MessageKind.Continuation || it == MessageKind.Steering || it == MessageKind.Answer } &&
                state.graph.increments.none { it.id == "inc-${r.id}" }
        } ?: return null
        val verified = state.graph.increments.filter { it.status == IncrementStatus.Verified }
        // WR2 (P1 #7, task-workflow §2.4 A): a response shows nothing regressed — regression obligations only, never the goal
        // checks of the increments before it; with none, its packet resolves it (the campaign gate still runs every item).
        val accept = contract.acceptance.filterIsInstance<Acceptance.Run>().filter { it.evidencePurpose == EvidencePurpose.Regression }.map { it.id }
        val response = Increment(
            id = "inc-${message.id}",
            requirementIds = contract.requirements.filterNot { it.lapsed }.map { it.id },
            accept = accept,
            writeScope = contract.scope.writePaths,
            expectedFiles = 0,
            title = "${message.id}: ${message.text.lineSequence().first().take(TITLE_CHARS)}",
            dependsOn = verified.map { it.id },
            produces = io.astrolabe.graph.Production.Resolves(message.id),
            evidenceKinds = verified.flatMap { it.evidenceKinds.entries }.filter { it.key in accept }.associate { it.key to it.value },
        )
        val issues = state.graph.copy(increments = state.graph.increments + response).validate(contract)
        if (issues.isNotEmpty()) {
            line(listOf(message.id), "no response increment for ${message.id}: ${issues.joinToString("; ") { it.detail }}")
            return null
        }
        c.advance(Transition.ResponseOpened(message.id, response))
        line(listOf(message.id, response.id), "response ${response.id} opened for ${message.id} (${contract.kindOf(message).wire})")
        return null
    }

    /**
     * №31 (task-workflow §1.3, §1.4): why [parent] cannot be followed up — it is not in this project's store, or it can
     * still be continued and is resumed instead — or `null` when it ended with a final outcome.
     */
    private fun followable(contracts: Contracts, campaigns: Campaigns, parent: WorkId): String? {
        val contract = contracts.current(parent) ?: return "work ${parent.value} is not in this project: a follow-up names a work of the same project"
        val state = campaigns.load(parent, contract.attemptId)
        val continued = state == null || state.phase != CampaignPhase.Ended || state.resumable ||
            state.outcome == CampaignOutcome.BudgetExhausted && (state.budgetStop?.resumable == true || state.contractStop?.cause?.resumable == true)
        return if (continued) "work ${parent.value} can be continued: resume it (${state?.outcome?.wire ?: state?.phase?.name?.lowercase() ?: "never opened"})" else null
    }

    /** WF-1 (task-workflow §3.6): [contract] with the host's [items] added — ids after the derived ones, every requirement bound to them. */
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

        /** How many moved paths a void of the final review names before it counts the rest. */
        private const val MAX_MOVED: Int = 10

        /** Campaign-gate obligation ids (D-343). */
        private const val FULL_SUITE: String = "campaign:full-suite"
        private const val EQUIVALENCE: String = "campaign:equivalence"

        /** How many `rework` continuations one S0 run applies before it waits for the next resume (D-340). */
        private const val MAX_REWORKS_PER_RUN: Int = 2

        /** The cause of a test-integrity flag derived from the tree rather than from a cell's edit (C11). */
        private const val SINCE_S0: String = "a change since s0"

        /** The agent's final text kept with a pending completion, for the decider. */
        private const val MAX_SUMMARY_CHARS: Int = 4_000
        private const val HOST_ANSWER: String = "host answer for "

        /** How much of a message titles the increment it derives (task-workflow §2.4); the message itself is pinned whole. */
        private const val TITLE_CHARS: Int = 80
        /** The ripgrep executable looked up on the host's `PATH` (C18). */
        private const val RIPGREP: String = "rg"
        private const val PRESCAN_REFRESHED: String = "impact pre-scan refreshed: "

        /** The heading of the host's notes in a cell's pinned context (D-345): the user did not write them. */
        public const val HOST_NOTES: String = "Notes from the host application (not from the user):\n"

        /** D-170: review and probe cells exist (P4.4), so S2 is selectable; S3 comes from a plan at intake (D-183). */
        private val CAPABILITIES: ShapeCapabilities = ShapeCapabilities(reviewCells = true, probes = true)

        /** Default cap on an S1 campaign's cells, plan cell excluded (D-70). */
        public const val DEFAULT_MAX_CELLS: Int = 12

        /** A-D.6 (owner №4): the default handoff grant — epochs of direct cells, counted apart from [DEFAULT_MAX_CELLS]. */
        public const val DEFAULT_MAX_HANDOFFS: Int = 8

        /** A-D.6: the exit is a direct cell's handoff. */
        private val CellExit?.handoff: Boolean get() = this is CellExit.Partial && reason == PartialReason.Handoff

        /**
         * §8.8 (P4.2.2): [findings] at or above major not yet in [register] become its `Open` items, numbered after its
         * last. A-D.4: a direct register's archived open items count as its own — no number is reused, no closed one returns.
         */
        internal fun withOpenItems(register: Register, findings: List<Finding>): Register {
            if (findings.isEmpty()) return register
            val known = register.open + register.archive.open
            val fresh = Derived.openItems(findings, (known.maxOfOrNull { it.n } ?: 0) + 1).filter { item -> known.none { it.text == item.text } }
            return if (fresh.isEmpty()) register else register.copy(open = register.open + fresh)
        }

        /** The pseudo-increment the plan cell runs under; never part of the graph. */
        public const val PLAN: String = "plan"
    }
}
