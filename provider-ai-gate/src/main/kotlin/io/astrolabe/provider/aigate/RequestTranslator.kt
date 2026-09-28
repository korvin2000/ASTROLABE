package io.astrolabe.provider.aigate

import io.astrolabe.provider.ContentPart
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Opaque
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.ToolSchema
import io.astrolabe.provider.UsageItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.chat.Conversation
import net.ai.gate.chat.StopReason
import net.ai.gate.chat.ToolResultMessage
import net.ai.gate.chat.UserMessage
import net.ai.gate.chat.content.Content
import net.ai.gate.chat.tool.Tool
import net.ai.gate.json.JsonNull as GateNull
import net.ai.gate.json.JsonSchema
import net.ai.gate.model.ModelRef
import net.ai.gate.chat.content.ToolCall as GateToolCall
import net.ai.gate.chat.content.ToolResult as GateToolResult

/** A request the SDK conversation model cannot express; reported as `InvalidRequest`, never sent. */
internal class TranslationException(message: String) : RuntimeException(message)

/**
 * `Request` → AI Gate `Conversation` (G-03). `[S]` becomes the system prompt, `[R]` `[K]` `[A]` and pinned `[T]`
 * messages user turns, and `[T]` items turns in order: a run of assistant-side items (text, reasoning, calls) is one
 * assistant turn, a run of results one tool-result turn. A segment's breakpoint marks the end of what it emitted.
 *
 * Provenance (A-04, D-326): an assistant turn takes the origin of its reasoning, so the SDK replays same-origin
 * reasoning natively and applies its hand-off rules to any other; a turn without reasoning has the neutral origin
 * [HISTORY], whose text and calls every API accepts losslessly. The mask is not sent: `[S]` states it and the executor
 * enforces it.
 */
internal object RequestTranslator {
    /** Origin of assistant turns whose producing model is not recorded. */
    val HISTORY: ModelRef = ModelRef("astrolabe", "history")
    const val HISTORY_API: String = "astrolabe"

    private val UNKNOWN_ORIGIN = ModelRef("astrolabe", "unknown-origin")

    fun translate(request: Request, binding: ProfileBinding): Conversation {
        val b = Conversation.builder()
        b.tools(request.tools.map(::tool))
        val names = request.items.filterIsInstance<ToolCall>().associate { it.id to it.name }
        for (segment in request.segments) {
            if (segment.kind == SegmentKind.S) {
                b.system(segment.items.joinToString("\n") { item ->
                    if (item !is Message || item.role != Role.System) throw TranslationException("[S] holds only system messages, found ${item::class.simpleName}")
                    text(item.parts, "system message")
                })
            } else {
                Turns(b, names).addAll(segment.items)
            }
            if (segment.breakpoint) {
                // Longer retentions precede shorter ones: [S][R][K] end before [T] (the Anthropic TTL order rule).
                val retention = if (segment.kind == SegmentKind.T) null else binding.prefixRetention
                if (retention == null) b.cacheBreakpoint() else b.cacheBreakpoint(retention)
            }
        }
        return b.build()
    }

    private fun tool(schema: ToolSchema): Tool = try {
        // ASTROLABE schemas have optional members and open objects: non-strict is the only truthful mapping.
        Tool.function(schema.name).description(schema.description).parameters(JsonSchema.of(JsonBridge.toGateObject(schema.jsonSchema))).build()
    } catch (e: IllegalArgumentException) {
        throw TranslationException("tool ${schema.name}: ${e.message}")
    }

    private fun text(parts: List<ContentPart>, what: String): String = parts.joinToString("") { part ->
        when (part) {
            is Text -> part.text
            is Opaque -> throw TranslationException("$what: opaque '${part.kind}' parts have no portable form")
        }
    }

    /** Groups items into SDK turns, preserving order. */
    private class Turns(private val b: Conversation.Builder, private val names: Map<String, String>) {
        private val run = ArrayList<Content>()
        private var runOrigin: String? = null
        private val results = ArrayList<GateToolResult>()

        fun addAll(items: List<Item>) {
            for (item in items) add(item)
            flushRun()
            flushResults()
        }

        private fun add(item: Item) {
            when (item) {
                is Message -> when (item.role) {
                    Role.User -> {
                        flushRun()
                        flushResults()
                        val text = text(item.parts, "user message")
                        if (text.isNotEmpty()) b.message(UserMessage.of(text))
                    }
                    Role.Assistant -> {
                        flushResults()
                        val text = text(item.parts, "assistant message")
                        if (text.isNotEmpty()) run += Content.text(text)
                    }
                    Role.System -> throw TranslationException("a system message outside [S]")
                }
                is ReasoningRef -> {
                    flushResults()
                    if (runOrigin != null && runOrigin != item.providerTag) flushRun()
                    runOrigin = item.providerTag
                    run += reasoning(item)
                }
                is ToolCall -> {
                    flushResults()
                    run += GateToolCall.of(item.id, item.name, item.argsJson)
                }
                is ToolResult -> {
                    flushRun()
                    val name = names[item.callId] ?: throw TranslationException("result ${item.callId} answers no call in the request")
                    val parts = item.content.map { part ->
                        when (part) {
                            is Text -> Content.text(part.text)
                            is Opaque -> throw TranslationException("result ${item.callId}: opaque '${part.kind}' parts have no portable form")
                        }
                    }
                    results += GateToolResult.of(item.callId, name, parts, item.isError)
                }
                is UsageItem, is OpaqueContinuation -> Unit // accounting and provider state: never replayed as turns
            }
        }

        private fun flushRun() {
            if (run.isNotEmpty()) {
                val origin = runOrigin?.let { ReasoningRef(it).origin() }
                val builder = when {
                    runOrigin == null -> AssistantMessage.builder(HISTORY, HISTORY_API)
                    origin == null -> AssistantMessage.builder(UNKNOWN_ORIGIN, "unknown")
                    else -> AssistantMessage.builder(ModelRef(origin.provider, origin.model), origin.api)
                }
                run.forEach(builder::add)
                b.message(builder.stopReason(if (run.any { it is GateToolCall }) StopReason.TOOL_USE else StopReason.STOP).build())
            }
            run.clear()
            runOrigin = null
        }

        private fun flushResults() {
            if (results.isNotEmpty()) b.message(ToolResultMessage.of(results.toList()))
            results.clear()
        }
    }

    /** `opaque = {text, signature, redacted, providerData}` as `ResponseTranslator` stored it; anything else replays as unsigned. */
    private fun reasoning(item: ReasoningRef): Content.Reasoning {
        val opaque = item.opaque as? JsonObject
        fun string(name: String): String? = (opaque?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val redacted = (opaque?.get("redacted") as? JsonPrimitive)?.booleanOrNull ?: false
        val providerData: JsonElement? = opaque?.get("providerData")
        return Content.Reasoning.of(string("text"), string("signature"), redacted, providerData?.let(JsonBridge::toGate) ?: GateNull.INSTANCE)
    }
}
