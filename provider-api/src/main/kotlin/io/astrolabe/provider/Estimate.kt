package io.astrolabe.provider

import kotlinx.serialization.Serializable

/**
 * A token count with its provenance (D-06, I-17). [exact] is true only when every contribution was counted by
 * the profile's own tokenizer; otherwise [marginTokens] is the estimator's declared uncertainty and
 * [unknownHistory] says that some replayed history has no known size at all, so no fit can be claimed.
 */
@Serializable
public data class Estimate(
    val tokens: Long,
    val exact: Boolean,
    val estimatorId: String,
    val version: String,
    val marginTokens: Long = 0,
    val unknownHistory: Boolean = false,
) {
    init {
        require(tokens >= 0 && marginTokens >= 0) { "token counts must be ≥ 0" }
    }

    /** Conservative bound used for admission; meaningless while [unknownHistory]. */
    val upperBoundTokens: Long get() = tokens + marginTokens

    public operator fun plus(other: Estimate): Estimate {
        require(estimatorId == other.estimatorId) { "cannot add estimates from $estimatorId and ${other.estimatorId}" }
        return Estimate(
            tokens = tokens + other.tokens,
            exact = exact && other.exact,
            estimatorId = estimatorId,
            version = version,
            marginTokens = marginTokens + other.marginTokens,
            unknownHistory = unknownHistory || other.unknownHistory,
        )
    }

    public companion object {
        @JvmStatic
        public fun zero(estimatorId: String, version: String, exact: Boolean = true): Estimate =
            Estimate(0, exact, estimatorId, version)

        /** A contribution of provider-reported size (e.g. effective continuation history): exact, no margin. */
        @JvmStatic
        public fun reported(tokens: Long, estimatorId: String, version: String): Estimate =
            Estimate(tokens, exact = true, estimatorId = estimatorId, version = version)

        @JvmStatic
        public fun unknownHistory(estimatorId: String, version: String): Estimate =
            Estimate(0, exact = false, estimatorId = estimatorId, version = version, unknownHistory = true)
    }
}

/** Counts tokens for planning or admission; [id]/[version] are recorded on every admission decision (D-06). */
public interface TokenEstimator {
    public val id: String
    public val version: String

    public fun estimate(text: String): Estimate

    /** Charges every serialized contribution of [request] once; see [Request.estimate]. */
    public fun estimate(request: Request): Estimate = request.estimate(this)
}

/**
 * Charges every serialized contribution once: tool schemas, every item of every segment (text, opaque
 * payloads, call arguments, result content, reasoning references) and the continuation. A continuation
 * without a reported effective history size yields [Estimate.unknownHistory] (never a silent fit, I-17).
 */
public fun Request.estimate(estimator: TokenEstimator): Estimate {
    var total = Estimate.zero(estimator.id, estimator.version)
    for (tool in tools) {
        total += estimator.estimate(tool.name)
        total += estimator.estimate(tool.description)
        total += estimator.estimate(tool.jsonSchema.toString())
    }
    for (segment in segments) {
        for (item in segment.items) total += item.estimate(estimator)
    }
    continuation?.let { total += it.estimate(estimator) }
    return total
}

public fun Item.estimate(estimator: TokenEstimator): Estimate = when (this) {
    is Message -> parts.fold(Estimate.zero(estimator.id, estimator.version)) { acc, part -> acc + part.estimate(estimator) }
    is ToolCall -> estimator.estimate(name) + estimator.estimate(argsJson)
    is ToolResult -> content.fold(Estimate.zero(estimator.id, estimator.version)) { acc, part -> acc + part.estimate(estimator) }
    is ReasoningRef -> opaque?.let { estimator.estimate(it.toString()) } ?: Estimate.zero(estimator.id, estimator.version)
    is UsageItem -> Estimate.zero(estimator.id, estimator.version)
    is OpaqueContinuation -> effectiveHistoryTokens?.let { Estimate.reported(it, estimator.id, estimator.version) }
        ?: Estimate.unknownHistory(estimator.id, estimator.version)
}

public fun ContentPart.estimate(estimator: TokenEstimator): Estimate = when (this) {
    is Text -> estimator.estimate(text)
    is Opaque -> estimator.estimate(payload.toString())
}
