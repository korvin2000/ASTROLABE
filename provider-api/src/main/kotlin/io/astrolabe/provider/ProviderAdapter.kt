package io.astrolabe.provider

/**
 * Provider adapter contract (§15.1). Rules every implementation upholds:
 * - capabilities are probed and declared, never inferred from an API-shaped URL;
 * - a half-generated tool call is never exposed: an interrupted stream ends as [StopReason.Truncated] with no
 *   [ToolCall] (AX-01); complete native output is persisted by the caller before results are appended;
 * - native tool-call/result pairs are preserved; a history that cannot be reduced safely is rejected by
 *   [validate] as a capacity condition rather than sent malformed (FX-21, AX-02);
 * - provider errors, tool failures and verification failures have three separate retry semantics and are
 *   recorded separately; retry policy itself lives in the transport modules (P7);
 * - hosted tool output enters the same evidence and accounting pipeline without local executor capabilities;
 * - usage is reported per call with unknown dimensions explicit, never zero (AX-09);
 * - cancellation follows [Invocation]: late output and usage are observed exactly once and never executed (D-51).
 */
public interface ProviderAdapter {
    public val id: String

    public fun capabilities(profile: Profile): Capabilities

    /**
     * Checks tool pairing, breakpoint support, schema dialects, context admission (exact-or-margin counts plus
     * output headroom against the context limit) and continuation validity. [Validations.standard] implements
     * the shared rules; adapters add provider-specific ones.
     */
    public fun validate(request: Request, estimate: Estimate): Validation

    /** Registers [id] and dispatches; returns immediately with a handle (D-51). */
    public fun start(request: Request, id: InvocationId): Invocation

    public val normalizer: UsageNormalizer
}

public sealed interface Validation {
    public object Ok : Validation {
        override fun toString(): String = "Ok"
    }

    public data class Rejected(val problems: List<Problem>) : Validation {
        init {
            require(problems.isNotEmpty()) { "a rejection needs at least one problem" }
        }
    }
}

public enum class ProblemKind {
    BrokenToolPairing,
    UnsupportedBreakpoints,
    UnsupportedSchemaDialect,
    ContextOverflow,
    UnknownHistorySize,
    UnsupportedContinuation,
    InvalidRequest,
}

public data class Problem(val kind: ProblemKind, val detail: String)

/** Shared validation rules (§15.1, I-17); adapters compose these with their own checks. */
public object Validations {
    @JvmStatic
    public fun standard(request: Request, estimate: Estimate, capabilities: Capabilities): Validation {
        val problems = ArrayList<Problem>()
        val pairing = Items.pairs(request.items)
        if (pairing.broken) {
            problems += Problem(
                ProblemKind.BrokenToolPairing,
                "unmatched calls=${pairing.unmatchedCalls.map { it.id }} orphan results=${pairing.orphanResults.map { it.callId }} " +
                    "duplicates=${pairing.duplicateResults.map { it.callId }}",
            )
        }
        val breakpoints = request.segments.count { it.breakpoint }
        if (breakpoints > 0 && !capabilities.caching.breakpoints) {
            problems += Problem(ProblemKind.UnsupportedBreakpoints, "$breakpoints breakpoints requested; provider has none")
        }
        capabilities.caching.maxBreakpoints?.let { max ->
            if (breakpoints > max) problems += Problem(ProblemKind.UnsupportedBreakpoints, "$breakpoints breakpoints > max $max")
        }
        request.tools.filter { it.dialect !in capabilities.schemaDialects }.forEach {
            problems += Problem(ProblemKind.UnsupportedSchemaDialect, "tool ${it.name}: dialect ${it.dialect}")
        }
        if (request.continuation != null && !capabilities.continuation) {
            problems += Problem(ProblemKind.UnsupportedContinuation, "provider does not support continuation")
        }
        if (estimate.unknownHistory) {
            problems += Problem(ProblemKind.UnknownHistorySize, "effective history size unknown; use a fresh lineage")
        } else {
            val needed = estimate.upperBoundTokens + request.maxOutputTokens
            if (needed > capabilities.contextLimitTokens) {
                problems += Problem(
                    ProblemKind.ContextOverflow,
                    "input ${estimate.tokens}+margin ${estimate.marginTokens}+output ${request.maxOutputTokens} = $needed > " +
                        "context ${capabilities.contextLimitTokens} (estimator ${estimate.estimatorId}/${estimate.version}, exact=${estimate.exact})",
                )
            }
        }
        if (request.maxOutputTokens > capabilities.outputLimitTokens) {
            problems += Problem(ProblemKind.InvalidRequest, "maxOutputTokens ${request.maxOutputTokens} > output limit ${capabilities.outputLimitTokens}")
        }
        return if (problems.isEmpty()) Validation.Ok else Validation.Rejected(problems)
    }
}

/** Lifecycle of one provider call (D-51): requested → cancel-requested → provider-acknowledged → terminal-reconciled. */
public enum class InvocationState { Requested, CancelRequested, ProviderAcknowledged, TerminalReconciled }

/**
 * Handle of one in-flight provider call.
 * - [await] returns the response, or throws [ProviderError]; after [cancel] it returns a response with
 *   [StopReason.Cancelled] and no tool calls. Cancelling the awaiting coroutine requests provider cancellation
 *   but never ends the accounting: [terminal] still completes.
 * - [terminal] suspends until the call is reconciled and delivers late output and usage exactly once to the
 *   caller that archives them; nothing in it is executed (AX-08).
 * - Implementations complete [state] transitions in order and never complete [await] with a half-generated call.
 */
public interface Invocation {
    public val id: InvocationId
    public val state: InvocationState

    public suspend fun await(): Response

    public fun cancel()

    public suspend fun terminal(): Terminal
}

/** Reconciled end state of an invocation; [usage] is `null` only when the provider reported nothing. */
public data class Terminal(
    val id: InvocationId,
    val response: Response?,
    val error: ProviderError?,
    val lateItems: List<Item>,
    val usage: BillableUsage?,
    val cancelled: Boolean,
)

public sealed class ProviderError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    public class Transport(message: String, cause: Throwable? = null) : ProviderError(message, cause)

    public class RateLimit(message: String, public val retryAfterSeconds: Long? = null) : ProviderError(message)

    public class OutputLimit(message: String) : ProviderError(message)

    public class Refusal(message: String) : ProviderError(message)

    public class ExpiredContinuation(message: String) : ProviderError(message)

    public class InvalidRequest(message: String) : ProviderError(message)

    public class UnsupportedSchema(message: String) : ProviderError(message)

    public class MissingUsage(message: String) : ProviderError(message)
}
