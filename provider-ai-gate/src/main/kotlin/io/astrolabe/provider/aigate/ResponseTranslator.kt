package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CallFacts
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Response
import io.astrolabe.provider.Role
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.UsageProvenance
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.cache.CacheRetention
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.chat.content.Content
import net.ai.gate.metadata.Usage
import java.util.OptionalLong
import net.ai.gate.chat.StopReason as GateStop
import net.ai.gate.chat.content.ToolCall as GateToolCall

/**
 * AI Gate reply → `Response` (G-04). Parts the reply marks incomplete are never exposed; tool calls survive only a
 * tool-use or plain stop (AX-01, AX-03, AX-04, AX-08). Reasoning is kept as a `ReasoningRef` tagged with the reply's
 * requested model and API — the identity the SDK compares for native replay, so a dated `responseModel` never turns
 * the model's own reasoning foreign. Parts the item model cannot hold (unknown blocks, generated media, compaction
 * summaries) are not replayed (D-334).
 */
internal object ResponseTranslator {
    fun response(reply: AssistantMessage, binding: ProfileBinding, cancelRequested: Boolean): Response {
        val stop = stop(reply, cancelRequested)
        val calls = stop == StopReason.ToolUse
        return Response(items(reply, calls), stop, UsageMapper.billable(reply.usage(), reply.responseModel().orElse(null), binding), facts = facts(reply, binding))
    }

    /**
     * The SDK's call facts of [reply]: timings from its `ResponseInfo` (none for a reply not obtained by a call), the
     * gateway's route, the answering model and the tier of the profile's `PriceTable` its usage is priced at — the same
     * total input `BillableUsage.price` selects the tier by.
     */
    fun facts(reply: AssistantMessage, binding: ProfileBinding): CallFacts {
        val info = reply.info()
        val called = info.requestId().isNotEmpty()
        val totalInput = UsageMapper.billable(reply.usage(), null, binding).totalInput
        return CallFacts(
            latencyMillis = if (called) info.latency().toMillis() else null,
            firstOutputMillis = if (called) info.timeToFirstOutput().orElse(null)?.toMillis() else null,
            upstream = info.route().orElse(null),
            responseModel = reply.responseModel().orElse(null),
            priceTierInputTokensAbove = binding.profile.priceTable.tier(totalInput)?.inputTokensAbove,
        )
    }

    fun stop(reply: AssistantMessage, cancelRequested: Boolean): StopReason = when (reply.stopReason()) {
        GateStop.TOOL_USE -> if (reply.hasToolCalls()) StopReason.ToolUse else StopReason.EndTurn
        GateStop.STOP -> if (reply.hasToolCalls()) StopReason.ToolUse else StopReason.EndTurn
        GateStop.LENGTH -> StopReason.OutputLimit
        GateStop.REFUSAL, GateStop.CONTENT_FILTER -> StopReason.Refusal
        GateStop.ABORTED -> if (cancelRequested) StopReason.Cancelled else StopReason.Truncated
        else -> StopReason.Truncated // ERROR, OTHER, COMPACTION and raw reasons: nothing in it is executable
    }

    /** The reply's complete parts as items; tool calls only when [calls]. Adjacent text and refusal parts merge. */
    fun items(reply: AssistantMessage, calls: Boolean): List<Item> {
        val out = ArrayList<Item>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) out += Message(Role.Assistant, listOf(Text(text.toString())))
            text.setLength(0)
        }
        val incomplete = reply.incompleteParts().toSet()
        val tag = "${reply.model().providerId()}/${reply.model().modelId()}@${reply.api()}"
        reply.content().forEachIndexed { index, part ->
            if (index in incomplete) return@forEachIndexed
            when (part) {
                is Content.Text -> text.append(part.text())
                is Content.Refusal -> text.append(part.text())
                is Content.Reasoning -> {
                    flush()
                    out += ReasoningRef(tag, JsonObject(buildMap {
                        put("text", part.text().map { JsonPrimitive(it) as kotlinx.serialization.json.JsonElement }.orElse(JsonNull))
                        put("signature", part.signature().map { JsonPrimitive(it) as kotlinx.serialization.json.JsonElement }.orElse(JsonNull))
                        put("redacted", JsonPrimitive(part.redacted()))
                        put("providerData", JsonBridge.toKotlin(part.providerData()))
                    }))
                }
                is GateToolCall -> if (calls) {
                    flush()
                    out += ToolCall(part.id(), part.name(), part.argumentsJson())
                }
                else -> Unit
            }
        }
        flush()
        return out
    }
}

/**
 * AI Gate `Usage` → `BillableUsage` (G-07, §15.2). The SDK's buckets are already disjoint (input excludes cache reads and
 * writes) and absent counters stay absent (S-04), so no wire format is parsed here. A dimension the profile expects
 * but the call did not report is unknown, never zero (AX-09); output observed before a call ended may still grow and
 * is unknown too, as are its reasoning tokens and the charge. An undifferentiated cache-write count belongs to the only
 * retention class the request could write. The provider's reported charge (`Usage.charge()`) is kept as billed, apart
 * from the estimate.
 */
internal object UsageMapper {
    fun billable(usage: Usage, responseModel: String?, binding: ProfileBinding): BillableUsage {
        val expected = binding.profile.capabilities.usageFields
        val quantities = LinkedHashMap<BillingDimension, Long>()
        val unknown = LinkedHashSet<BillingDimension>()
        fun put(dimension: BillingDimension, value: Long?) {
            when {
                value == null -> if (dimension in expected) unknown += dimension
                // An unexpected zero would only make the bill unpriced; an unexpected count is kept, truthfully.
                value > 0 || dimension in expected -> quantities[dimension] = value
            }
        }
        put(BillingDimension.UNCACHED_INPUT, usage.input().orNull())
        put(BillingDimension.CACHE_READ, usage.cacheRead().orNull())
        val classes = usage.cacheWrites()
        val undifferentiated = usage.cacheWrite().orNull()
        when {
            classes.isNotEmpty() -> {
                put(BillingDimension.CACHE_WRITE_5M, classes[CacheRetention.SHORT])
                put(BillingDimension.CACHE_WRITE_1H, classes[CacheRetention.LONG])
            }
            undifferentiated == null -> {
                put(BillingDimension.CACHE_WRITE_5M, null)
                put(BillingDimension.CACHE_WRITE_1H, null)
            }
            undifferentiated == 0L -> {
                put(BillingDimension.CACHE_WRITE_5M, 0)
                put(BillingDimension.CACHE_WRITE_1H, 0)
            }
            else -> {
                val written = binding.writeRetentions
                put(BillingDimension.CACHE_WRITE_5M, if (written == setOf(CacheRetention.SHORT)) undifferentiated else if (CacheRetention.SHORT !in written) 0 else null)
                put(BillingDimension.CACHE_WRITE_1H, if (written == setOf(CacheRetention.LONG)) undifferentiated else if (CacheRetention.LONG !in written) 0 else null)
            }
        }
        put(BillingDimension.OUTPUT, if (usage.finalForCall()) usage.output().orNull() else null)
        val raw = JsonBridge.toKotlin(usage.raw()).takeIf { it != JsonNull }
        val charge = if (usage.finalForCall()) usage.charge().orElse(null) else null
        return BillableUsage(
            quantities, UsageProvenance(binding.profile.provider, responseModel ?: binding.profile.model, binding.api),
            unknown, raw, reasoningIncludedInOutput = true,
            billed = charge?.let { Money(it.currency().currencyCode, it.amount()) },
            billedUpstream = charge?.upstream()?.let { Money(charge.currency().currencyCode, it) },
            reasoningTokens = if (usage.finalForCall()) usage.reasoning().orNull() else null,
        )
    }

    /** A call the provider documented as not processed: nothing was billed, so every expected dimension is a known zero. */
    fun none(binding: ProfileBinding): BillableUsage = BillableUsage(
        binding.profile.capabilities.usageFields.associateWith { 0L },
        UsageProvenance(binding.profile.provider, binding.profile.model, binding.api),
        reasoningIncludedInOutput = true,
    )

    /** A call whose usage never arrived although it may have been billed (AX-09). */
    fun missing(binding: ProfileBinding): BillableUsage =
        BillableUsage.missing(UsageProvenance(binding.profile.provider, binding.profile.model, binding.api), binding.profile.capabilities.usageFields)

    private fun OptionalLong.orNull(): Long? = if (isPresent) asLong else null
}
