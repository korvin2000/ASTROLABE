package io.astrolabe.provider.aigate

import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationState
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.Response
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.Terminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import net.ai.gate.CallOutcome
import net.ai.gate.LlmCall
import net.ai.gate.error.ErrorCode
import net.ai.gate.error.LlmException
import net.ai.gate.error.RequestCancelledException
import java.util.Locale

/**
 * `LlmException` → `ProviderError` (G-06). The SDK's retry policy has already run: nothing here is retried again.
 * The message keeps the code, the SDK request id (it correlates with SDK events and logs) and whether the provider may
 * have processed the call.
 */
internal object ErrorMapper {
    private val AUTHENTICATION = setOf(
        ErrorCode.INVALID_CREDENTIALS, ErrorCode.LOGIN_REQUIRED, ErrorCode.REFRESH_FAILED, ErrorCode.LOGIN_CANCELLED,
        ErrorCode.PERMISSION_DENIED, ErrorCode.CREDENTIAL_STORE,
    )

    fun error(e: LlmException): ProviderError {
        val message = buildString {
            append(e.code().value()).append(": ").append(e.message)
            e.requestId().ifPresent { append(" [request ").append(it).append(']') }
            if (e.outcomeUnknown()) append(" (outcome unknown: the provider may have processed and billed it)")
        }
        return when (e.code()) {
            ErrorCode.RATE_LIMITED, ErrorCode.OVERLOADED ->
                ProviderError.RateLimit(message, e.details().retryAfter().map { it.toSeconds() }.orElse(null))
            // C16: a spent plan quota (402, `usage_limit_reached`, `insufficient_quota`); the SDK never retries it.
            ErrorCode.QUOTA_EXHAUSTED -> ProviderError.QuotaExhausted(message, e.details().retryAfter().map { it.toSeconds() }.orElse(null))
            ErrorCode.CONTEXT_OVERFLOW, ErrorCode.REQUEST_TOO_LARGE -> ProviderError.ContextOverflow(message)
            in AUTHENTICATION -> ProviderError.Authentication(message, e)
            ErrorCode.DEADLINE_EXCEEDED, ErrorCode.STREAM_IDLE_TIMEOUT -> ProviderError.Timeout(message, e.outcomeUnknown())
            ErrorCode.OUTPUT_REFUSED -> ProviderError.Refusal(message)
            ErrorCode.OUTPUT_TRUNCATED -> ProviderError.OutputLimit(message)
            ErrorCode.CONTINUATION_EXPIRED -> ProviderError.ExpiredContinuation(message)
            ErrorCode.INVALID_REQUEST, ErrorCode.MODEL_NOT_FOUND, ErrorCode.UNSUPPORTED_FEATURE ->
                if ("schema" in message.lowercase(Locale.ROOT)) ProviderError.UnsupportedSchema(message) else ProviderError.InvalidRequest(message)
            else -> ProviderError.Transport(message, e) // connect_failed, server_error, outcome_unknown, malformed_response, …
        }
    }
}

/**
 * One provider call (G-05, D-51) over an SDK [LlmCall], whose outcome settles exactly once and survives cancellation:
 * [terminal] completes on every path — a request the SDK refused to build, an executor rejection, a cancellation before
 * or after send — and [await] never completes with a half-generated call. `ProviderAcknowledged` means the SDK call
 * settled: local knowledge, not a provider receipt.
 */
internal class AiGateInvocation(
    override val id: InvocationId,
    private val binding: ProfileBinding,
    private val onSettled: (InvocationId) -> Unit,
) : Invocation {
    private val response = CompletableDeferred<Response>()
    private val settled = CompletableDeferred<Terminal>()
    private val lock = Any()

    @Volatile
    override var state: InvocationState = InvocationState.Requested
        private set

    @Volatile
    private var cancelRequested = false
    private var call: LlmCall? = null
    private var done = false

    /** Starts the SDK call from [start]; a construction failure settles at once as `InvalidRequest` (nothing was sent). */
    fun begin(start: () -> LlmCall): AiGateInvocation {
        val started = try {
            start()
        } catch (e: TranslationException) {
            settle(failure(ProviderError.InvalidRequest(e.message ?: "untranslatable request"), UsageMapper.none(binding)))
            return this
        } catch (e: LlmException) {
            settle(failure(ErrorMapper.error(e), UsageMapper.none(binding)))
            return this
        } catch (e: RuntimeException) {
            settle(failure(ProviderError.InvalidRequest("${e::class.simpleName}: ${e.message}"), UsageMapper.none(binding)))
            return this
        }
        val cancelNow = synchronized(lock) {
            call = started
            cancelRequested
        }
        if (cancelNow) started.cancel()
        started.outcome().whenComplete { outcome, error ->
            val terminal = try {
                if (outcome != null) terminal(outcome)
                // A rejected execution never ran, so nothing was billed; any other SDK failure may have followed a send.
                else failure(ProviderError.Transport("the SDK call failed outside its outcome: ${error?.message}", error), if (rejected(error)) UsageMapper.none(binding) else UsageMapper.missing(binding))
            } catch (e: RuntimeException) {
                // A reply this adapter cannot translate still settles: the bill stays unknown, nothing is exposed.
                failure(ProviderError.Transport("untranslatable provider outcome: ${e::class.simpleName}: ${e.message}", e), UsageMapper.missing(binding))
            }
            settle(terminal)
        }
        return this
    }

    override suspend fun await(): Response = try {
        response.await()
    } catch (cancelled: CancellationException) {
        // The awaiting coroutine went away: request provider cancellation; terminal() still settles the bill.
        cancel()
        throw cancelled
    }

    override fun cancel() {
        val target = synchronized(lock) {
            if (done) return
            cancelRequested = true
            if (state == InvocationState.Requested) state = InvocationState.CancelRequested
            call
        }
        target?.cancel()
    }

    override suspend fun terminal(): Terminal = settled.await()

    private fun terminal(outcome: CallOutcome): Terminal {
        outcome.reply()?.let { reply ->
            val r = ResponseTranslator.response(reply, binding, cancelRequested)
            return Terminal(id, r, null, emptyList(), r.usage, cancelled = r.stop == StopReason.Cancelled)
        }
        val error = checkNotNull(outcome.error())
        val partial = outcome.partial().orElse(null)
        val usage = partial?.let { UsageMapper.billable(it.usage(), it.responseModel().orElse(null), binding) } ?: when {
            // Documented as not processed: never sent, or refused with a client error before any work (400, 401, 429 …),
            // and no attempt received a successful response that could have streamed billable output.
            !error.outcomeUnknown() && outcome.attempts().none { it.httpStatus()?.let { s -> s in 200..299 } == true } &&
                (outcome.attempts().none { it.sent() } || outcome.attempts().last().httpStatus()?.let { it in 400..499 } == true) -> UsageMapper.none(binding)
            else -> UsageMapper.missing(binding)
        }
        val late = partial?.let { ResponseTranslator.items(it, calls = false) }.orEmpty()
        val facts = partial?.let { ResponseTranslator.facts(it, binding) }
        return when {
            error is RequestCancelledException || error.code() == ErrorCode.CANCELLED ->
                Terminal(id, Response(emptyList(), StopReason.Cancelled, usage, facts = facts), null, late, usage, cancelled = true)
            // AX-01: an interrupted stream is a truncated response: its text stays, a half-generated call never does.
            error.code() == ErrorCode.STREAM_INTERRUPTED ->
                Response(late, StopReason.Truncated, usage, facts = facts).let { Terminal(id, it, null, emptyList(), usage, cancelled = false) }
            else -> Terminal(id, null, ErrorMapper.error(error), late, usage, cancelled = false)
        }
    }

    private fun rejected(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is java.util.concurrent.RejectedExecutionException }

    private fun failure(error: ProviderError, usage: io.astrolabe.provider.BillableUsage) = Terminal(id, null, error, emptyList(), usage, cancelled = false)

    private fun settle(terminal: Terminal) {
        synchronized(lock) {
            if (done) return
            done = true
            state = InvocationState.ProviderAcknowledged
        }
        terminal.response?.let(response::complete) ?: response.completeExceptionally(checkNotNull(terminal.error))
        state = InvocationState.TerminalReconciled
        settled.complete(terminal)
        onSettled(id)
    }
}
