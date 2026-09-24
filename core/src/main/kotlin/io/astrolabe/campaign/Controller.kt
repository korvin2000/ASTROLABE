package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.Prime
import io.astrolabe.atlas.RulesSnapshot
import io.astrolabe.atlas.Sniffed
import io.astrolabe.auth.RulesTrust
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.TouchKind
import io.astrolabe.cell.Touched
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.IntentJournal
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.SqliteIntentJournal
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
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
import io.astrolabe.provider.Money
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import io.astrolabe.verify.Checks
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.ProtectedPaths
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Snapshot
import io.astrolabe.workspace.SnapshotEntryKind
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import java.nio.file.Path
import java.time.Clock

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
) : AutoCloseable {
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
        try {
            os.close()
        } finally {
            store.close()
        }
    }
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
            return open(repo, git, store, os, request, policy)
        } catch (failure: Throwable) {
            runCatching { os.close() }
            runCatching { store.close() }
            throw failure
        }
    }

    private fun open(repo: Path, git: Git, store: Store, os: LocalOs, request: CampaignRequest, policy: CampaignPolicy): OpenedCampaign {
        val ids = Identities(request.work, request.attempt)
        val protected = ProtectedPaths()
        val workspace = Workspace(WORKSPACE, repo, git, protected)
        val registry = VersionRegistry(workspace)
        val stamper = Stamper(workspace, EnvFingerprint.compute(env))
        val journal = Journal(store, clock)
        val intents = SqliteIntentJournal(store, clock)
        val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock, events)
        val campaigns = SqliteCampaigns(store, clock)
        val dirty = DirtyState(workspace, store.blobs, stamper, ids, clock)
        val shadow = ShadowRef(request.work, request.attempt, workspace, store, dirty, os, clock)

        // Capture before anything else looks at the tree: snapshot 0 is the user's pre-existing state.
        val first = shadow.record(0) == null
        val s0 = if (first) dirty.capture(0).also { shadow.open(it) } else checkNotNull(shadow.manifest(0))
        val external = if (first) emptyList() else drift(shadow, dirty)

        val atlas = Atlas.build(workspace.root)
        val derived = contracts.deriveS0(request.work, request.attempt, request.text, atlas, config, policy.tokens, protected, policy.cost)
        val stored = contracts.current(request.work)
        check(stored == null || stored.attemptId == request.attempt) { "work ${request.work.value} is attempt ${stored?.attemptId?.value}; a new attempt is P2" }
        val contract = stored ?: contracts.open(derived.contract)
        val commands = derived.primary?.let(RunnerCommands::of) ?: RunnerCommands()
        val checks = Checks.seed(contract, commands)
        val rules = RulesTrust(workspace.root).approved(config.rulesFile)?.let { RulesSnapshot(it.binding.path, it.digest, it.text) }
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
        if (state?.phase == CampaignPhase.Opened) {
            state = Lifecycle.apply(state, contract, Transition.Reconciled(reconciliation.unknownOutcomes)).also(campaigns::save)
        }

        val prescan = Prescan.UNKNOWN
        val shape = ShapeSelector.select(contract, prescan, config.defaults.shapePolicy, policy.resumeExpected)
        events?.emit(AgentEvent.Campaign.Opened(ids, contract.requests.last().id))
        events?.emit(AgentEvent.Campaign.ShapeSelected(ids, (shape as? ShapeDecision.Selected)?.shape?.name ?: "blocked", "contract:v${contract.version}"))
        if (shape is ShapeDecision.Unavailable && state?.phase == CampaignPhase.Running && state.running == null) {
            state = Lifecycle.apply(state, contract, Transition.Stopped(CampaignOutcome.BlockedExternal, shape.reason)).also(campaigns::save)
        }

        return OpenedCampaign(
            request, ids, store, os, workspace, registry, stamper, dirty, shadow, s0, atlas, derived.sniffed, commands,
            contracts, checks, rules, prime, EmptyKb, journal, intents, campaigns, reconciliation, prescan, shape, state, refusal,
        )
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
