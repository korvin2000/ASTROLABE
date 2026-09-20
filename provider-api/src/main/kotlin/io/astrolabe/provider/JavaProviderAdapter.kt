package io.astrolabe.provider

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Java-implementable form of [ProviderAdapter] (D-07, I-14). Contract for implementers:
 * - futures may complete on any thread; the SDK never blocks a provider thread and never assumes an executor;
 * - complete [JavaInvocation.response] exceptionally with a [ProviderError] for provider failures; any other
 *   exception is mapped to [ProviderError.Transport];
 * - after [JavaInvocation.cancel], complete `response()` with a [StopReason.Cancelled] response (no tool calls)
 *   or exceptionally, and still complete `terminal()` exactly once with late output and usage;
 * - the SDK may stop waiting on `response()` (coroutine cancellation); it then calls `cancel()` but never
 *   cancels the futures themselves, so accounting completes.
 */
public interface JavaProviderAdapter {
    public fun id(): String

    public fun capabilities(profile: Profile): Capabilities

    public fun validate(request: Request, estimate: Estimate): Validation

    public fun start(request: Request, id: InvocationId): JavaInvocation

    public fun normalizer(): UsageNormalizer
}

public interface JavaInvocation {
    public fun id(): InvocationId

    public fun state(): InvocationState

    public fun response(): CompletableFuture<Response>

    public fun cancel()

    public fun terminal(): CompletableFuture<Terminal>
}

public object ProviderAdapters {
    /** Bridges a Java SPI implementation to the Kotlin contract without exposing coroutines to Java. */
    @JvmStatic
    public fun fromJava(adapter: JavaProviderAdapter): ProviderAdapter = object : ProviderAdapter {
        override val id: String get() = adapter.id()
        override fun capabilities(profile: Profile): Capabilities = adapter.capabilities(profile)
        override fun validate(request: Request, estimate: Estimate): Validation = adapter.validate(request, estimate)
        override fun start(request: Request, id: InvocationId): Invocation = BridgedInvocation(adapter.start(request, id))
        override val normalizer: UsageNormalizer get() = adapter.normalizer()
    }

    private class BridgedInvocation(private val java: JavaInvocation) : Invocation {
        override val id: InvocationId get() = java.id()
        override val state: InvocationState get() = java.state()

        override suspend fun await(): Response = java.response().awaitWithoutCancelling(onCancel = java::cancel)

        override fun cancel(): Unit = java.cancel()

        override suspend fun terminal(): Terminal = java.terminal().awaitWithoutCancelling(onCancel = {})
    }

    /**
     * Awaits [this] without ever cancelling the future: cancelling the coroutine runs [onCancel] and rethrows,
     * leaving the provider's own completion (and accounting) intact.
     */
    private suspend fun <T> CompletableFuture<T>.awaitWithoutCancelling(onCancel: () -> Unit): T =
        suspendCancellableCoroutine { cont ->
            whenComplete { value, error ->
                when {
                    error == null -> cont.resume(value)
                    else -> cont.resumeWithException(mapError(error))
                }
            }
            cont.invokeOnCancellation { onCancel() }
        }

    @JvmStatic
    public fun mapError(error: Throwable): Throwable = when (val cause = if (error is CompletionException) error.cause ?: error else error) {
        is ProviderError -> cause
        is CancellationException -> cause
        else -> ProviderError.Transport(cause.message ?: cause.javaClass.name, cause)
    }
}
