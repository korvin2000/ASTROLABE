package io.astrolabe

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.Deployer
import io.astrolabe.campaign.FinishReceipt
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.campaign.OptionalLayers
import io.astrolabe.campaign.PublicationRequest
import io.astrolabe.campaign.PublicationRun
import io.astrolabe.campaign.LimitHold
import io.astrolabe.cell.CellModel
import io.astrolabe.contract.Contract
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Authority
import io.astrolabe.event.Events
import io.astrolabe.event.Views
import io.astrolabe.id.AttemptId
import io.astrolabe.id.IdGen
import io.astrolabe.id.RandomIdGen
import io.astrolabe.id.WorkId
import io.astrolabe.kb.EmptyKb
import io.astrolabe.kb.Kb
import io.astrolabe.os.Git
import io.astrolabe.os.LocalOs
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Spans
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * The SDK entry point for Kotlin hosts (D-07); nothing sits above the controller. Java hosts use
 * [io.astrolabe.java.AstrolabeJava], which wraps this class.
 *
 * **Resources.** [open] acquires the project lock and the store; [Project.close] releases them. [close] cancels
 * every running campaign (each settles its checkpoint before it ends) and closes the event bus; it does not
 * close projects, which the host opened and closes.
 *
 * **Shutdown order** (D-328): stop campaigns → [close] (cells settle terminal accounting) → close the adapter → close
 * its transport. With [ownsAdapter], [close] waits for the campaigns to settle, up to
 * `Defaults.providerTerminalWaitSeconds`, and then closes an `AutoCloseable` adapter itself; it blocks the caller,
 * so a host never calls it from a campaign callback (authority, deployer), which would wait on its own campaign.
 */
public class Astrolabe @JvmOverloads public constructor(
    public val config: Config,
    private val adapter: ProviderAdapter,
    private val authority: Authority,
    private val clock: Clock = Clock.systemUTC(),
    private val idGen: IdGen = RandomIdGen(),
    /** The host's optional layers, each read only while its `Flags` entry is on (D-251–D-253). */
    layers: OptionalLayers = OptionalLayers(),
    /** The host's `deploy` stage (§14.2); without one a requested deploy is refused. */
    private val deployer: Deployer? = null,
    /** The estimator admission is decided with, per profile (D-06, I-17); the default is the planning heuristic. */
    private val estimators: EstimatorFactory = EstimatorFactory { HeuristicEstimator() },
    /** Whether [close] also closes [adapter] (D-328); a borrowed adapter is the host's to close. */
    private val ownsAdapter: Boolean = false,
) : AutoCloseable {
    /** Every campaign's events, in emission order per bus; filter by work id or use [CampaignHandle.events]. */
    public val events: Events = Events(clock)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = Controller(config, clock, idGen, events, spans = Spans(idGen, events), layers = layers, estimators = estimators)

    init {
        val violations = config.violations()
        if (violations.isNotEmpty()) throw InvalidConfig(violations)
    }

    /** Opens [repo]: the state root, the project lock, the store and the (read-only, empty until P2.6) KB. */
    public fun open(repo: Path): Project {
        val git = Git(repo, timeoutMillis = config.defaults.gitDeadlineSeconds * 1000L)
        val store = Store.open(config, git, clock)
        val os = try {
            LocalOs(clock)
        } catch (failure: Throwable) {
            store.close()
            throw failure
        }
        return Project(repo, git, store, os)
    }

    /**
     * Opens a campaign for [request] in [project] and starts it; returns once the campaign is open and
     * reconciled. One campaign runs per project at a time (S0 single writer). A `null` [policy] budgets one
     * full window of the main profile per allowed cell (D-67). A [publication] asks for stages beyond `patch` once the
     * campaign has finished (§14.2): each is a separate grant through the authority; without one nothing is published.
     */
    @JvmOverloads
    public suspend fun campaign(project: Project, request: String, policy: CampaignPolicy? = null, publication: PublicationRequest? = null): CampaignHandle {
        val profile = mainProfile()
        val chosen = policy ?: CampaignPolicy(Tokens(profile.capabilities.contextLimitTokens.toLong() * config.defaults.campaignCells))
        return start(project, { CampaignRequest(WorkId(idGen.next("W")), AttemptId(FIRST_ATTEMPT), request) }, chosen, publication)
    }

    /**
     * Reopens [work]'s campaign in [project] and starts it again (C14, "raise the limit and continue"): the same work and
     * attempt, its contract and verified ledger kept. A [policy] with raised limits or contract tokens lifts the budget stop
     * that held it; `null` keeps everything stored with the campaign — its limits and the contract's tokens and money.
     * Returns once the campaign is open and reconciled; when the raise was not enough, [CampaignHandle.limitHold] names the
     * limit that still holds it and [CampaignHandle.await] returns that stop's outcome without a model call. A work this
     * project has no campaign for is an [IllegalArgumentException].
     */
    @JvmOverloads
    public suspend fun resume(project: Project, work: WorkId, policy: CampaignPolicy? = null, publication: PublicationRequest? = null): CampaignHandle {
        val contract = SqliteContractRepository(project.store, clock).latest(work)
            ?: throw IllegalArgumentException("project ${project.root} has no campaign for work ${work.value}")
        // The first request is what the campaign was opened for; amendments live in the stored contract.
        return start(project, { CampaignRequest(work, contract.attemptId, contract.requests.first().text) }, policy ?: CampaignPolicy(contract.budget.tokens, contract.budget.cost), publication)
    }

    private fun mainProfile() = config.profiles[config.profileRoles.main]
        ?: throw IllegalStateException("no profile '${config.profileRoles.main}' is configured for the main routing function")

    private fun start(project: Project, request: () -> CampaignRequest, chosen: CampaignPolicy, publication: PublicationRequest?): CampaignHandle {
        val profile = mainProfile()
        synchronized(project) {
            check(project.active?.done != false) { "project already runs campaign ${project.active?.workId?.value}" }
            val opened = controller.open(project, request(), chosen)
            val model = CellModel(adapter, profile, estimators.estimatorFor(profile))
            val published = AtomicReference<PublicationRun?>(null)
            val finished = AtomicReference<FinishReceipt?>(null)
            val job = scope.async {
                opened.use { c ->
                    val run = controller.run(c, model, authority)
                    finished.set(run.finish)
                    if (publication != null && run.finish != null) published.set(controller.publish(c, run, publication, authority, deployer))
                    run.outcome ?: c.stop?.outcome ?: CampaignOutcome.Failed
                }
            }
            return CampaignHandle(opened.ids.work, job, opened, events, project.views, published, finished).also { project.active = it }
        }
    }

    override fun close() {
        scope.cancel("Astrolabe closed")
        try {
            if (ownsAdapter && adapter is AutoCloseable) {
                // D-328: cells settle their terminal accounting under NonCancellable before the transport goes away.
                runBlocking { withTimeoutOrNull(config.defaults.providerTerminalWaitSeconds * 1_000L) { scope.coroutineContext.job.join() } }
                adapter.close()
            }
        } finally {
            events.close()
        }
    }

    public companion object {
        public const val MODULE: String = "astrolabe-core"

        /** Harness version recorded in every [AttemptConfig]; bumped at deliberate version boundaries. */
        public const val VERSION: String = "0.1.0"

        /** The attempt a new campaign starts with; further attempts of one work are P2. */
        public const val FIRST_ATTEMPT: String = "a1"
    }
}

/**
 * An opened repository: the store and the project lock held until [close], the OS handle and the KB. Campaigns
 * opened in it share the store; closing the project while a campaign runs is the host's error.
 */
public class Project internal constructor(
    public val root: Path,
    public val git: Git,
    public val store: Store,
    public val os: LocalOs,
) : AutoCloseable {
    /** Read-only views over the store: contract, ledger, receipts. */
    public val views: Views = Views(store)

    public val kb: Kb = EmptyKb

    @Volatile
    internal var active: CampaignHandle? = null

    override fun close() {
        try {
            os.close()
        } finally {
            store.close()
        }
    }
}

/**
 * A running campaign. [await] returns its outcome — [CampaignOutcome.Cancelled] after [cancel] — and rethrows a
 * harness failure. [events] is a cold flow of this campaign's events only.
 */
public class CampaignHandle internal constructor(
    public val workId: WorkId,
    private val job: Deferred<CampaignOutcome>,
    private val opened: OpenedCampaign,
    private val bus: Events,
    public val views: Views,
    private val published: AtomicReference<PublicationRun?> = AtomicReference(null),
    private val finished: AtomicReference<FinishReceipt?> = AtomicReference(null),
) {
    public val done: Boolean get() = job.isCompleted

    /** The stages published after finish when the campaign was started with a publication request; `null` until then or without one. */
    public val publication: PublicationRun? get() = published.get()

    /**
     * The finish receipt the campaign ended with (§5.9), its provenance class included (§4.4 C2); `null` until [await]
     * returns, or for a campaign that ended without one.
     */
    public val finish: FinishReceipt? get() = finished.get()

    /**
     * What still holds a budget stop this open could not continue (C14): the task limit or the contract budget to raise
     * next; `null` when the campaign runs, or was not stopped on a budget.
     */
    public val limitHold: LimitHold? get() = opened.limitHold

    public val events: Flow<AgentEvent> get() = bus.records().filter { it.event.ids.work == workId }.map { it.event }

    public suspend fun await(): CampaignOutcome = try {
        job.await()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // The caller's own cancellation propagates; a campaign job cancelled by [Astrolabe.close] is an outcome.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (job.isCancelled) CampaignOutcome.Cancelled else throw cancelled
    }

    /**
     * Cancels the campaign through its token (§3.7, D-26): an in-flight model call is interrupted, the cell settles
     * its checkpoint, nothing is dispatched or published afterwards, and effects already made are archived.
     */
    public fun cancel() {
        opened.cancellation.cancel("cancelled by the host")
    }

    /** A user amendment (§4.1): recorded against the contract at once; the running cell sees it on its next turn. */
    public fun amend(text: String): Contract = opened.contracts.amendByUser(workId, text)
}
