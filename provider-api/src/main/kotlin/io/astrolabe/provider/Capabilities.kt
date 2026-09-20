package io.astrolabe.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Caching support as probed from the provider, never inferred from an API-shaped URL (§15.1, D-29). */
@Serializable
public data class CacheCapability(
    val breakpoints: Boolean,
    val maxBreakpoints: Int? = null,
    val minimumTokens: Int? = null,
    /** Billable cache-write classes this provider distinguishes (I-16). */
    val writeClasses: Set<BillingDimension> = emptySet(),
)

/** Capability description of one provider/model, separated from request construction (§15.1). */
@Serializable
public data class Capabilities(
    val toolSchemaValidation: Boolean,
    val parallelToolCalls: Boolean,
    val streaming: Boolean,
    val outputLimitTokens: Int,
    val contextLimitTokens: Int,
    val nativeCompaction: Boolean,
    val continuation: Boolean,
    val cancellation: Boolean,
    val hostedExecution: Boolean,
    val caching: CacheCapability,
    /** Usage dimensions the provider reports; any of them absent from a response is recorded as unknown. */
    val usageFields: Set<BillingDimension>,
    val schemaDialects: Set<SchemaDialect>,
) {
    init {
        require(outputLimitTokens > 0 && contextLimitTokens > 0) { "limits must be positive" }
        require(outputLimitTokens <= contextLimitTokens) { "output limit cannot exceed the context limit" }
    }
}

@Serializable
public enum class LatencyClass { Fast, Standard, Slow }

/** Calibration evidence per evaluation stratum (§11): accepted trials out of all trials. */
@Serializable
public data class StratumOutcome(val stratum: String, val trials: Int, val accepted: Int) {
    init {
        require(trials >= 0 && accepted in 0..trials) { "accepted must be within 0..trials" }
    }
}

/**
 * A routable model configuration (§11, §15.1). Context and output limits live in [capabilities]; [config]
 * carries provider-specific request settings as opaque JSON; [priceTable] is dated so accounting can reproduce
 * a bill.
 */
@Serializable
public data class Profile(
    val id: String,
    val provider: String,
    val model: String,
    val capabilities: Capabilities,
    val priceTable: PriceTable,
    val config: JsonObject = JsonObject(emptyMap()),
    val latency: LatencyClass = LatencyClass.Standard,
    val stratumOutcomes: List<StratumOutcome> = emptyList(),
) {
    init {
        requireToken("Profile.id", id)
        require(provider.isNotBlank() && model.isNotBlank()) { "provider and model must not be blank" }
    }
}
