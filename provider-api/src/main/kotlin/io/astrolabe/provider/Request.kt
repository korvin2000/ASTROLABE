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
 * One model response. A truncated or cancelled response never carries an executable [ToolCall]
 * (§15.1 "never execute a half-generated tool call"; AX-01, AX-08) — enforced at construction.
 */
@Serializable
public data class Response(
    val items: List<Item>,
    val stop: StopReason,
    val usage: BillableUsage? = null,
    val continuation: OpaqueContinuation? = null,
) {
    init {
        if (stop == StopReason.Truncated || stop == StopReason.Cancelled) {
            require(items.none { it is ToolCall }) { "a $stop response cannot expose tool calls" }
        }
    }

    val toolCalls: List<ToolCall> get() = Items.toolCalls(items)

    val text: String get() = items.filterIsInstance<Message>().joinToString("") { it.text }
}
