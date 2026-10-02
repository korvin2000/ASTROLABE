package io.astrolabe.provider.aigate

import io.astrolabe.provider.Estimate
import io.astrolabe.provider.Item
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.estimate
import net.ai.gate.Llm
import net.ai.gate.json.JsonArray
import net.ai.gate.json.JsonBoolean
import net.ai.gate.json.JsonNull
import net.ai.gate.json.JsonNumber
import net.ai.gate.json.JsonObject
import net.ai.gate.json.JsonString
import net.ai.gate.json.JsonValue
import net.ai.gate.metadata.TokenCount

/**
 * The admission estimator of one profile (G-08, D-06, I-17): it counts the request as the SDK will encode it — system,
 * tools, adapted history and replay data in their wire form — instead of ASTROLABE's normalized view. Every string of
 * the prepared body (member names included) is measured with the host's [text] estimator, plus a framing allowance
 * per object; the margin is the text estimator's plus the request framing. With `gate.tokenCount = "endpoint"` the
 * provider's counting endpoint is asked first (a network call per estimate, not billed as inference) and an exact
 * count replaces the estimate. Text-only measurements (residency, digests) delegate to [text].
 */
internal class AiGateEstimator(
    private val llm: Llm,
    private val adapter: AiGateAdapter,
    private val binding: ProfileBinding,
    private val text: TokenEstimator,
) : TokenEstimator {
    override val id: String = "ai-gate-${binding.api}+${text.id}"
    override val version: String = "1/${text.version}"

    override fun estimate(text: String): Estimate = this.text.estimate(text).copy(estimatorId = id, version = version)

    /**
     * Reasoning fills the transcript only where the route's codec replays it (`ApiFeatures.nativeReasoningReplay`);
     * a codec that leaves it out of the next request (OpenRouter chat completions without `reasoning_content`
     * replay) sends nothing for it, so it counts zero. Every other item is the planning estimate.
     */
    override fun estimate(item: Item): Estimate =
        if (item is ReasoningRef && !binding.features.nativeReasoningReplay()) Estimate.zero(id, version) else super.estimate(item)

    override fun estimate(request: Request): Estimate {
        val prepared = when (val p = adapter.prepared(request, binding)) {
            is Prepared.Ready -> p.call
            // Not encodable: validate() rejects it; the planning estimate keeps admission's arithmetic defined meanwhile.
            is Prepared.Refused -> return request.estimate(this)
        }
        if (binding.settings.endpointCounts) {
            val count = runCatching { llm.countTokens(prepared) }.getOrNull()
            if (count != null && count.method() != TokenCount.ESTIMATE) {
                return Estimate(count.inputTokens(), count.exact(), id, version, marginTokens = if (count.exact()) 0 else count.marginTokens())
            }
        }
        val tally = Tally()
        tally.add(prepared.request().body())
        return Estimate(tally.tokens, exact = false, estimatorId = id, version = version, marginTokens = tally.margin + REQUEST_FRAMING)
    }

    private inner class Tally {
        var tokens = 0L
        var margin = 0L

        fun add(value: JsonValue) {
            when (value) {
                is JsonString -> measure(value.value())
                is JsonObject -> {
                    tokens += OBJECT_FRAMING
                    for ((name, member) in value.members()) {
                        measure(name)
                        add(member)
                    }
                }
                is JsonArray -> value.values().forEach(::add)
                is JsonNumber, is JsonBoolean -> tokens += 1
                is JsonNull -> Unit
            }
        }

        private fun measure(s: String) {
            val e = text.estimate(s)
            tokens += e.tokens
            margin += e.marginTokens
        }
    }

    private companion object {
        const val OBJECT_FRAMING = 2L
        const val REQUEST_FRAMING = 32L
    }
}
