package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CacheCapability
import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.PriceTable
import io.astrolabe.provider.Profile
import io.astrolabe.provider.SchemaDialect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.cache.CacheRetention
import net.ai.gate.model.Capability
import net.ai.gate.model.SupportLevel
import net.ai.gate.spi.protocol.ApiFeatures
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Drafts ASTROLABE profiles from what the SDK knows (§9 of the integration design): limits and prices from the
 * catalog, cache, output-cap and usage facts from the wire API's `ApiFeatures` (S-06). A draft is for a person to
 * review and freeze — `Profile` stays ASTROLABE's frozen truth, and a gateway's facts are only as good as its probe
 * (`llm.test(model) { it.usageFields().toolRoundTrip().cacheRoundTrip() }`).
 */
public object AiGateProfiles {
    /**
     * A profile [id] for [modelId] of [providerId], its prices dated [priceDate].
     * @throws IllegalArgumentException when the catalog lacks the context window or output limit, or prices
     */
    @JvmStatic
    public fun draft(llm: Llm, providerId: String, modelId: String, id: String, priceDate: LocalDate): Profile {
        val model = llm.model(providerId, modelId)
        val features = llm.features(model)
        val context = model.contextWindow()
        val output = model.maxOutputTokens()
        require(context.isPresent && output.isPresent) { "the catalog has no limits for $providerId/$modelId; describe the model in the provider's models" }
        val prices = model.prices().orElseThrow { IllegalArgumentException("the catalog has no prices for $providerId/$modelId") }
        val reported = features.reportedUsageFields()
        val usage = LinkedHashSet<BillingDimension>()
        if ("input" in reported) usage += BillingDimension.UNCACHED_INPUT
        if ("cache_read" in reported) usage += BillingDimension.CACHE_READ
        if ("cache_write_5m" in reported) usage += BillingDimension.CACHE_WRITE_5M
        if ("cache_write_1h" in reported) usage += BillingDimension.CACHE_WRITE_1H
        if ("output" in reported) usage += BillingDimension.OUTPUT
        val explicit = features.promptCache() == ApiFeatures.PromptCache.EXPLICIT_MARKERS
        val writeClasses = buildSet {
            if (explicit && CacheRetention.SHORT in features.retentions()) add(BillingDimension.CACHE_WRITE_5M)
            if (explicit && CacheRetention.LONG in features.retentions()) add(BillingDimension.CACHE_WRITE_1H)
        }
        val capabilities = Capabilities(
            toolSchemaValidation = model.capabilities().support(Capability.TOOLS) == SupportLevel.SUPPORTED,
            parallelToolCalls = model.capabilities().support(Capability.PARALLEL_TOOLS) == SupportLevel.SUPPORTED,
            streaming = features.streamingSupported(),
            outputLimitTokens = output.asLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            contextLimitTokens = context.asLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            nativeCompaction = false,
            continuation = false,
            cancellation = true,
            hostedExecution = false,
            caching = CacheCapability(
                breakpoints = explicit,
                maxBreakpoints = if (explicit) features.maxCacheMarkers() else null,
                writeClasses = writeClasses,
            ),
            usageFields = usage,
            schemaDialects = setOf(SchemaDialect.JSON_SCHEMA_2020_12),
        )
        val perMillion = LinkedHashMap<BillingDimension, BigDecimal>()
        prices.inputPerMillion().ifPresent { perMillion[BillingDimension.UNCACHED_INPUT] = it }
        prices.cacheReadPerMillion().ifPresent { perMillion[BillingDimension.CACHE_READ] = it }
        prices.cacheWritePerMillion().ifPresent { perMillion[BillingDimension.CACHE_WRITE_5M] = it }
        prices.cacheWriteLongPerMillion().or { prices.cacheWritePerMillion() }.ifPresent { perMillion[BillingDimension.CACHE_WRITE_1H] = it }
        prices.outputPerMillion().ifPresent { perMillion[BillingDimension.OUTPUT] = it }
        val gate = buildMap {
            put("v", JsonPrimitive(1))
            put("api", JsonPrimitive(features.api()))
            if (features.outputCap() == ApiFeatures.OutputCap.UNSUPPORTED) put("outputCap", JsonPrimitive("unsupported"))
        }
        return Profile(
            id, providerId, modelId, capabilities, PriceTable(priceDate, prices.currency().currencyCode, perMillion),
            config = JsonObject(mapOf("gate" to JsonObject(gate))),
        )
    }
}
