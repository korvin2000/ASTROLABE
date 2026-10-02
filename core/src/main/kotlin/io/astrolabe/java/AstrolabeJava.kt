package io.astrolabe.java

import io.astrolabe.Astrolabe
import io.astrolabe.CampaignHandle
import io.astrolabe.Config
import io.astrolabe.Project
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.Deployer
import io.astrolabe.campaign.FinishReceipt
import io.astrolabe.campaign.OptionalLayers
import io.astrolabe.campaign.PublicationRequest
import io.astrolabe.campaign.PublicationRun
import io.astrolabe.contract.Contract
import io.astrolabe.event.Authorities
import io.astrolabe.event.EventSink
import io.astrolabe.event.Events
import io.astrolabe.event.Subscription
import io.astrolabe.event.Views
import io.astrolabe.id.WorkId
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.JavaProviderAdapter
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.ProviderAdapters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * The SDK entry point for Java hosts (D-07, I-14): [Astrolabe] with futures and blocking calls, a Java-authored
 * [JavaProviderAdapter] and [JavaAuthority] bridged through [ProviderAdapters] and [Authorities]. No `suspend`,
 * `Flow` or value class appears here.
 *
 * **Threading.** Futures complete on the SDK's worker threads; [EventSink] callbacks run on the event bus's
 * dispatcher, never under a lock. Host callbacks must not block those threads for long.
 *
 * **Exceptions.** A harness failure completes the future exceptionally with the original exception (blocking
 * variants rethrow it unwrapped); an invalid configuration throws `InvalidConfig` from the constructor; opening a
 * second campaign while one runs throws `IllegalStateException`.
 *
 * **Cancellation.** Cancelling the future of [JavaCampaignHandle.await] cancels the campaign, as does
 * [JavaCampaignHandle.cancel]; the outcome is then [CampaignOutcome.Cancelled].
 */
public class AstrolabeJava private constructor(private val core: Astrolabe) : AutoCloseable {
    @JvmOverloads
    public constructor(
        config: Config,
        adapter: JavaProviderAdapter,
        authority: JavaAuthority,
        clock: Clock = Clock.systemUTC(),
        /** Optional layers (D-251–D-253); a dense source comes from `Retrievers.fromJava(JavaRetriever)`. */
        layers: OptionalLayers = OptionalLayers(),
        /** The host's `deploy` stage (§14.2), a synchronous SPI; without one a requested deploy is refused. */
        deployer: Deployer? = null,
    ) : this(Astrolabe(config, ProviderAdapters.fromJava(adapter), Authorities.fromJava(authority), clock, layers = layers, deployer = deployer))

    /**
     * A provider module's adapter (for example `provider-ai-gate`'s `AiGateAdapter`), passed as the object the module
     * built, with the per-profile [estimators] it supplies (D-06); [ownsAdapter] lets [close] close it (D-328).
     */
    @JvmOverloads
    public constructor(
        config: Config,
        adapter: ProviderAdapter,
        authority: JavaAuthority,
        estimators: EstimatorFactory,
        clock: Clock = Clock.systemUTC(),
        layers: OptionalLayers = OptionalLayers(),
        deployer: Deployer? = null,
        ownsAdapter: Boolean = false,
    ) : this(Astrolabe(config, adapter, Authorities.fromJava(authority), clock, layers = layers, deployer = deployer, estimators = estimators, ownsAdapter = ownsAdapter))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Opens [repo] (state root, project lock, store); the host closes the returned project. */
    public fun open(repo: Path): Project = core.open(repo)

    /** Opens and starts a campaign; completes once it is open and reconciled. A `null` policy is D-67's default. */
    /** A [publication] asks for stages beyond `patch` after the campaign finishes (§14.2); none by default. */
    @JvmOverloads
    public fun campaign(project: Project, request: String, policy: CampaignPolicy? = null, publication: PublicationRequest? = null): CompletableFuture<JavaCampaignHandle> =
        scope.future { JavaCampaignHandle(core.campaign(project, request, policy, publication), scope, core.events) }

    /** [campaign], blocking the calling thread until the campaign is open. */
    @JvmOverloads
    public fun campaignBlocking(project: Project, request: String, policy: CampaignPolicy? = null, publication: PublicationRequest? = null): JavaCampaignHandle =
        join(campaign(project, request, policy, publication))

    /** Registers [sink] for every campaign's events; close the subscription to stop delivery. */
    public fun subscribe(sink: EventSink): Subscription = core.events.subscribe(sink)

    override fun close() {
        scope.cancel("AstrolabeJava closed")
        core.close()
    }

    internal companion object {
        /** Waits for [future], rethrowing the failure itself rather than its wrapper. */
        fun <T> join(future: CompletableFuture<T>): T = try {
            future.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        } catch (failure: CompletionException) {
            throw failure.cause ?: failure
        }
    }
}

/** A running campaign for Java hosts. */
public class JavaCampaignHandle internal constructor(
    private val handle: CampaignHandle,
    private val scope: CoroutineScope,
    private val bus: Events,
) {
    public fun workId(): WorkId = handle.workId

    public fun views(): Views = handle.views

    public fun isDone(): Boolean = handle.done

    /** The publication run after the campaign finished, when one was requested; `null` before or without one. */
    public fun publication(): PublicationRun? = handle.publication

    /** The finish receipt with its provenance class (§4.4 C2); `null` until [await] completes, or when the campaign ended without one. */
    public fun finish(): FinishReceipt? = handle.finish

    /** The outcome; cancelling this future cancels the campaign. */
    public fun await(): CompletableFuture<CampaignOutcome> {
        val future = scope.future { handle.await() }
        future.whenComplete { _, failure -> if (failure is CancellationException) handle.cancel() }
        return future
    }

    /** [await], blocking the calling thread. */
    public fun awaitBlocking(): CampaignOutcome = AstrolabeJava.join(await())

    public fun cancel() {
        handle.cancel()
    }

    public fun amend(text: String): Contract = handle.amend(text)

    /** Registers [sink] for this campaign's events only. */
    public fun subscribe(sink: EventSink): Subscription =
        bus.subscribe { record -> if (record.event.ids.work == handle.workId) sink.onEvent(record) }
}
