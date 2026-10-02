package io.astrolabe.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Tool-schema dialect identifier; a schema is frozen only after its adapter lineage validated the dialect (D-20). */
@Serializable(with = SchemaDialect.Serializer::class)
public data class SchemaDialect(val id: String) {
    init {
        requireToken("SchemaDialect", id)
    }

    override fun toString(): String = id

    public object Serializer : StringWrapperSerializer<SchemaDialect>("SchemaDialect", ::SchemaDialect, SchemaDialect::id)

    public companion object {
        @JvmField public val JSON_SCHEMA_2020_12: SchemaDialect = SchemaDialect("json-schema-2020-12")
        @JvmField public val OPENAI_STRICT: SchemaDialect = SchemaDialect("openai-strict")
        @JvmField public val ANTHROPIC_INPUT_SCHEMA: SchemaDialect = SchemaDialect("anthropic-input-schema")
    }
}

@Serializable
public data class ToolSchema(
    val name: String,
    val description: String,
    val jsonSchema: JsonObject,
    val dialect: SchemaDialect,
) {
    init {
        require(name.isNotBlank()) { "ToolSchema.name must not be blank" }
    }
}

/** Operations the model may call this turn; enforcement happens locally in the executor regardless (D-20, L10). */
@Serializable
public data class ToolMask(val allowed: Set<String>) {
    public fun allows(op: String): Boolean = op in allowed

    public companion object {
        @JvmStatic
        public fun of(vararg ops: String): ToolMask = ToolMask(ops.toSet())
    }
}

/** Context layout regions (§5.1): `[S]` kernel · `[R]` repository prime · `[K]` compiled · `[T]` transcript · `[A]` anchor. */
@Serializable
public enum class SegmentKind { S, R, K, T, A }

/** One layout region; [breakpoint] is a logical cache hint the adapter maps or ignores by policy (D-29). */
@Serializable
public data class Segment(
    val kind: SegmentKind,
    val items: List<Item>,
    val breakpoint: Boolean = false,
)

@Serializable
public enum class Effort { Minimal, Low, Medium, High }

/** Caller-assigned correlation id of one provider call, registered before dispatch (D-51). */
@Serializable(with = InvocationId.Serializer::class)
public data class InvocationId(val value: String) {
    init {
        requireToken("InvocationId", value)
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<InvocationId>("InvocationId", ::InvocationId, InvocationId::value)
}

/**
 * One model request. Segments appear in layout order (each kind at most once, in `S R K T A` order); tools
 * carry their schemas; [continuation] replays provider-held history and is admitted by its effective size,
 * not its payload size (§6.1, I-17).
 */
@Serializable
public data class Request(
    val segments: List<Segment>,
    val tools: List<ToolSchema>,
    val profile: Profile,
    val effort: Effort,
    val maxOutputTokens: Int,
    val mask: ToolMask? = null,
    val continuation: OpaqueContinuation? = null,
) {
    init {
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(segments.zipWithNext().all { (a, b) -> a.kind.ordinal < b.kind.ordinal }) {
            "segments must follow the S R K T A order, each kind at most once: ${segments.map { it.kind }}"
        }
        require(tools.map { it.name }.toSet().size == tools.size) { "tool names must be unique" }
    }

    val items: List<Item> get() = segments.flatMap { it.items }

    public fun segment(kind: SegmentKind): Segment? = segments.firstOrNull { it.kind == kind }
}

@Serializable
public enum class StopReason { EndTurn, ToolUse, OutputLimit, Refusal, Cancelled, Truncated }

/**
 * One model response. A truncated, cancelled or output-limited response never carries an executable [ToolCall]
 * (§15.1 "never execute a half-generated tool call"; AX-01, AX-03, AX-08) — enforced at construction: the call a
 * reply was writing when it hit the output limit may look complete and still be cut.
 */
@Serializable
public data class Response @JvmOverloads constructor(
    val items: List<Item>,
    val stop: StopReason,
    val usage: BillableUsage? = null,
    val continuation: OpaqueContinuation? = null,
    val facts: CallFacts? = null,
) {
    init {
        if (stop == StopReason.Truncated || stop == StopReason.Cancelled || stop == StopReason.OutputLimit) {
            require(items.none { it is ToolCall }) { "a $stop response cannot expose tool calls" }
        }
    }

    val toolCalls: List<ToolCall> get() = Items.toolCalls(items)

    val text: String get() = items.filterIsInstance<Message>().joinToString("") { it.text }
}

/**
 * How one provider call went, as the transport observed it: telemetry for comparing runs, never priced, compared or
 * part of an identity or a cached prompt region (I-05). Every member is `null` when unknown.
 * - [latencyMillis]: from the call's start to its reply — credentials, retries, backoff and the whole stream included;
 * - [firstOutputMillis]: from the call's start to the model's first output (text, reasoning or a tool call), not the
 *   first byte — a time to first output, not a time to first token;
 * - [upstream]: the provider a gateway routed the call to (OpenRouter `provider`);
 * - [responseModel]: the model that answered, as the reply names it (it may be dated or differ from the profile's);
 * - [priceTierInputTokensAbove]: the input threshold of the provider price tier that applies to this call; `null` at
 *   base prices or when the profile has no prices.
 */
@Serializable
public data class CallFacts(
    val latencyMillis: Long? = null,
    val firstOutputMillis: Long? = null,
    val upstream: String? = null,
    val responseModel: String? = null,
    val priceTierInputTokensAbove: Long? = null,
) {
    init {
        require(latencyMillis == null || latencyMillis >= 0) { "latency must be ≥ 0" }
        require(firstOutputMillis == null || firstOutputMillis >= 0) { "first output must be ≥ 0" }
        require(priceTierInputTokensAbove == null || priceTierInputTokensAbove >= 0) { "a price tier threshold must be ≥ 0" }
    }
}
