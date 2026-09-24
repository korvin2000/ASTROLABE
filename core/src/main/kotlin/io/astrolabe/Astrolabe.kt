package io.astrolabe

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.CellModel
import io.astrolabe.contract.Contract
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
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Spans
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.nio.file.Path
import java.time.Clock

/**
 * The SDK entry point for Kotlin hosts (D-07); nothing sits above the controller. Java hosts use
 * [io.astrolabe.java.AstrolabeJava], which wraps this class.
 *
 * **Resources.** [open] acquires the project lock and the store; [Project.close] releases them. [close] cancels
 * every running campaign (each settles its checkpoint before it ends) and closes the event bus; it does not
 * close projects, which the host opened and closes.
 */
public class Astrolabe @JvmOverloads public constructor(
    public val config: Config,
    private val adapter: ProviderAdapter,
    private val authority: Authority,
    private val clock: Clock = Clock.systemUTC(),
    private val idGen: IdGen = RandomIdGen(),
) : AutoCloseable {
    /** Every campaign's events, in emission order per bus; filter by work id or use [CampaignHandle.events]. */
    public val events: Events = Events(clock)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = Controller(config, clock, idGen, events, spans = Spans(idGen, events))

    init {
        val violations = config.violations()
        if (violations.isNotEmpty()) throw InvalidConfig(violations)
    }

    /** Opens [repo]: the state root, the project lock, the store and the (read-only, empty until P2.6) KB. */
    public fun open(repo: Path): Project {
        val git = Git(repo)
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
     * full window of the main profile per allowed cell (D-67).
     */
    @JvmOverloads
    public suspend fun campaign(project: Project, request: String, policy: CampaignPolicy? = null): CampaignHandle {
        val profile = config.profiles[config.profileRoles.main]
            ?: throw IllegalStateException("no profile '${config.profileRoles.main}' is configured for the main routing function")
        val chosen = policy ?: CampaignPolicy(Tokens(profile.capabilities.contextLimitTokens.toLong() * config.defaults.campaignCells))
        synchronized(project) {
            check(project.active?.done != false) { "project already runs campaign ${project.active?.workId?.value}" }
            val opened = controller.open(project, CampaignRequest(WorkId(idGen.next("W")), AttemptId(FIRST_ATTEMPT), request), chosen)
            val model = CellModel(adapter, profile, HeuristicEstimator())
            val job = scope.async {
                opened.use { c ->
                    val run = controller.run(c, model, authority)
                    run.outcome ?: c.stop?.outcome ?: CampaignOutcome.Failed
                }
            }
            return CampaignHandle(opened.ids.work, job, opened, events, project.views).also { project.active = it }
        }
    }

    override fun close() {
        scope.cancel("Astrolabe closed")
        events.close()
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
) {
    public val done: Boolean get() = job.isCompleted

    public val events: Flow<AgentEvent> get() = bus.records().filter { it.event.ids.work == workId }.map { it.event }

    public suspend fun await(): CampaignOutcome {
        job.join()
        return if (job.isCancelled) CampaignOutcome.Cancelled else job.await()
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
