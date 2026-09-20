package io.astrolabe.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Item-based internal message model (§15.1): maps losslessly onto OpenAI Responses items and Anthropic
 * Messages content blocks. [native] carries the provider's own form, replayed unchanged only on a
 * compatible lineage (D-25); presentation views never rewrite it.
 */
@Serializable
public sealed interface Item {
    public val native: JsonElement?
}

@Serializable
public enum class Role {
    @SerialName("system")
    System,

    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,
}

@Serializable
public sealed interface ContentPart

@Serializable
@SerialName("text")
public data class Text(val text: String) : ContentPart

/** A non-text part kept opaque (image, document, provider block); [kind] is the provider's label. */
@Serializable
@SerialName("opaque")
public data class Opaque(val kind: String, val payload: JsonElement) : ContentPart

@Serializable
@SerialName("message")
public data class Message(
    val role: Role,
    val parts: List<ContentPart>,
    override val native: JsonElement? = null,
) : Item {
    /** Concatenated text parts; opaque parts contribute nothing. */
    val text: String get() = parts.filterIsInstance<Text>().joinToString("") { it.text }

    public companion object {
        @JvmStatic
        public fun text(role: Role, text: String): Message = Message(role, listOf(Text(text)))
    }
}

/** A complete, schema-valid tool call emitted by the model; [argsJson] is parsed once at the tool boundary. */
@Serializable
@SerialName("tool_call")
public data class ToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
    override val native: JsonElement? = null,
) : Item {
    init {
        require(id.isNotBlank()) { "ToolCall.id must not be blank" }
        require(name.isNotBlank()) { "ToolCall.name must not be blank" }
    }
}

@Serializable
@SerialName("tool_result")
public data class ToolResult(
    val callId: String,
    val content: List<ContentPart>,
    val isError: Boolean = false,
    override val native: JsonElement? = null,
) : Item {
    init {
        require(callId.isNotBlank()) { "ToolResult.callId must not be blank" }
    }

    public companion object {
        @JvmStatic
        public fun text(callId: String, text: String, isError: Boolean = false): ToolResult =
            ToolResult(callId, listOf(Text(text)), isError)
    }
}

/** Reference to provider-side reasoning; the content is opaque and never replayed across providers (AX-07). */
@Serializable
@SerialName("reasoning_ref")
public data class ReasoningRef(
    val providerTag: String,
    val opaque: JsonElement? = null,
    override val native: JsonElement? = null,
) : Item

/** Usage reported by the provider for one call; an accounting item, never sent back. */
@Serializable
@SerialName("usage")
public data class UsageItem(
    val usage: BillableUsage,
    override val native: JsonElement? = null,
) : Item

/**
 * Provider-held conversation state (native compaction, server-side history). [effectiveHistoryTokens] is the
 * provider-reported size of the history it will replay; when unknown, admission cannot be exact (I-17).
 */
@Serializable
@SerialName("opaque_continuation")
public data class OpaqueContinuation(
    val providerTag: String,
    val payload: JsonElement,
    val effectiveHistoryTokens: Long? = null,
    override val native: JsonElement? = null,
) : Item

public data class CallResultPair(val call: ToolCall, val result: ToolResult?)

/** Outcome of [Items.pairs]; [broken] means the history is not a valid native protocol sequence (FX-21, AX-02). */
public data class Pairing(
    val pairs: List<CallResultPair>,
    val orphanResults: List<ToolResult>,
    val duplicateResults: List<ToolResult>,
) {
    val unmatchedCalls: List<ToolCall> get() = pairs.filter { it.result == null }.map { it.call }
    val broken: Boolean get() = orphanResults.isNotEmpty() || duplicateResults.isNotEmpty() || pairs.any { it.result == null }
}

public object Items {
    /**
     * Pairs every [ToolCall] with the [ToolResult] that follows it. A result before its call or without a
     * call is an orphan; a second result for one call is a duplicate; a call without a result is unmatched.
     */
    @JvmStatic
    public fun pairs(items: List<Item>): Pairing {
        val callIndex = LinkedHashMap<String, Int>()
        val results = HashMap<String, ToolResult>()
        val calls = ArrayList<ToolCall>()
        val orphans = ArrayList<ToolResult>()
        val duplicates = ArrayList<ToolResult>()
        for (item in items) {
            when (item) {
                is ToolCall -> {
                    callIndex[item.id] = calls.size
                    calls += item
                }
                is ToolResult -> when {
                    item.callId !in callIndex -> orphans += item
                    item.callId in results -> duplicates += item
                    else -> results[item.callId] = item
                }
                else -> Unit
            }
        }
        return Pairing(calls.map { CallResultPair(it, results[it.id]) }, orphans, duplicates)
    }

    @JvmStatic
    public fun toolCalls(items: List<Item>): List<ToolCall> = items.filterIsInstance<ToolCall>()
}
