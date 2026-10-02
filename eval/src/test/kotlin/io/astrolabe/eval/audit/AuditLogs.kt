package io.astrolabe.eval.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A small synthetic run log in either layout; Studio-only entries (`journal.*`) are skipped on the bus. */
internal class AuditLog(private val format: JournalFormat, private val cell: String = "cell-1") {
    private val lines = ArrayList<String>()
    private var seq = 0L
    private var invocation = 0
    private var turn = 0

    fun lines(): List<String> = lines.toList()

    fun journal(): Journal = Journal.parse(lines)

    fun event(kind: String, fields: Map<String, Any?>, context: String? = cell): AuditLog {
        seq++
        val ids = buildJsonObject { put("work", "W-1"); put("attempt", "a1"); put("candidate", JsonNull); put("context", context?.let(::JsonPrimitive) ?: JsonNull) }
        val data = json(fields) as JsonObject
        lines += when (format) {
            JournalFormat.Bus -> buildJsonObject {
                put("seq", seq); put("at", "2026-10-01T00:00:00Z")
                put("event", buildJsonObject { put("type", kind); put("ids", ids); data.forEach { (k, v) -> put(k, v) } })
            }
            JournalFormat.Studio -> buildJsonObject {
                put("at", "2026-10-01T00:00:00Z"); put("source", "bus"); put("kind", kind); put("ids", ids)
                if (context != null) put("cell", context)
                put("data", data); put("seq", seq)
            }
        }.toString()
        return this
    }

    /** A Studio journal entry (`journal.<kind>`) at the current turn; the bus has none. */
    fun entry(kind: String, text: String, payload: JsonElement = JsonNull, at: Int = turn): AuditLog {
        if (format == JournalFormat.Bus) return this
        seq++
        lines += buildJsonObject {
            put("at", "2026-10-01T00:00:00Z"); put("source", "journal"); put("kind", "journal.$kind"); put("cell", cell); put("turn", at)
            put("data", buildJsonObject { put("kind", kind); put("turn", at); put("text", text); put("payload", payload) }); put("seq", seq)
        }.toString()
        return this
    }

    fun turn(n: Int): AuditLog { turn = n; return event("cell.turn_started", mapOf("turn" to n, "turnsMax" to 80)) }

    /** One model call: request and response; [billed] as A2a reports it, [nativeCost] as OpenRouter's raw usage did before. */
    fun call(
        uncached: Long?, cached: Long?, output: Long?, reasoning: Long? = null, billed: String? = null, nativeCost: String? = null,
        anchor: Long? = 100, provider: String = "openrouter", upstream: String? = null, respond: Boolean = true, write: Long? = null,
    ): AuditLog {
        val id = "inv-${++invocation}"
        event("cell.model_requested", mapOf("invocationId" to id, "estimatedTokens" to 1000, "profileId" to "p", "anchorTokens" to anchor))
        if (!respond) return this
        val quantities = LinkedHashMap<String, Any?>()
        val unknown = ArrayList<String>()
        uncached?.let { quantities["uncached_input"] = it } ?: unknown.add("uncached_input")
        cached?.let { quantities["cache_read"] = it } ?: unknown.add("cache_read")
        output?.let { quantities["output"] = it } ?: unknown.add("output")
        write?.let { quantities["cache_write_5m"] = it }
        val usage = linkedMapOf<String, Any?>(
            "quantities" to quantities,
            "provenance" to mapOf("provider" to provider, "model" to "m/one", "protocol" to "openai-completions"),
            "unknown" to unknown,
            "native" to mapOf("cost" to nativeCost?.toBigDecimal(), "completion_tokens_details" to mapOf("reasoning_tokens" to reasoning)),
            "reasoningIncludedInOutput" to true,
        )
        if (billed != null) usage["billed"] = mapOf("currency" to "USD", "amount" to billed, "unknown" to false)
        return event("cell.model_responded", mapOf("invocationId" to id, "stop" to "ToolUse", "usage" to usage, "facts" to mapOf("upstream" to upstream)))
    }

    fun tool(family: String, op: String, header: String): AuditLog =
        event("cell.tool_called", mapOf("opId" to 1, "family" to family, "op" to op))
            .event("cell.tool_resulted", mapOf("opId" to 1, "resultAlias" to "#1", "header" to header))

    fun gate(gate: String, text: String = gate): AuditLog = event("cell.gate_fired", mapOf("gate" to gate, "text" to text))

    fun dropped(vararg paths: String): AuditLog = event("cell.workset_changed", mapOf("known" to 1, "dropped" to paths.toList()))

    fun finished(outcome: String, extra: Map<String, Any?> = emptyMap()): AuditLog =
        event("campaign.finished", mapOf("outcome" to outcome, "finishReceiptRef" to "r") + extra, context = null)

    companion object {
        fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v) })
            is List<*> -> JsonArray(value.map(::json))
            else -> error("unsupported $value")
        }

        /** A `journal.call` payload of tool calls `(id, name, argsJson)`. */
        fun calls(vararg calls: Triple<String, String, String>): JsonArray = buildJsonArray {
            for ((id, name, args) in calls) add(buildJsonObject { put("type", "tool_call"); put("id", id); put("name", name); put("argsJson", args) })
        }
    }
}
