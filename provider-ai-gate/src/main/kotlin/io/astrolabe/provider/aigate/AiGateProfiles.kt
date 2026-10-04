package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CacheCapability
import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.PriceTable
import io.astrolabe.provider.PriceTier
import io.astrolabe.provider.Profile
import io.astrolabe.provider.SchemaDialect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.cache.CacheMode
import net.ai.gate.cache.CacheRetention
import net.ai.gate.chat.Conversation
import net.ai.gate.chat.options.ChatOptions
import net.ai.gate.diagnostics.ConnectionReport
import net.ai.gate.metadata.Usage
import net.ai.gate.model.Capability
import net.ai.gate.model.Prices
import net.ai.gate.model.SupportLevel
import net.ai.gate.spi.protocol.ApiFeatures
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.Optional

/**
 * Drafts ASTROLABE profiles from what the SDK knows (§9 of the integration design): limits and prices from the
 * catalog, cache, output-cap and usage facts from the wire API's `ApiFeatures` (S-06). A draft is for a person to
 * review and freeze — `Profile` stays ASTROLABE's frozen truth, and a gateway's facts are only as good as its probe
 * (`llm.test(model) { it.usageFields().toolRoundTrip().cacheRoundTrip() }`).
 */
public object AiGateProfiles {
    /**
     * A profile [id] for [modelId] of [providerId], its prices dated [priceDate]. A model the catalog has no token
     * prices for (a subscription plan such as Codex) gets an empty table: every charge is unknown, never zero. A host
     * that knows the account is billed by a plan says so with `priceTable.copy(billing = Billing.Plan)` (D-409).
     * @throws IllegalArgumentException when the catalog lacks the context window or output limit
     */
    @JvmStatic
    public fun draft(llm: Llm, providerId: String, modelId: String, id: String, priceDate: LocalDate): Profile {
        val model = llm.model(providerId, modelId)
        val features = llm.features(model)
        val context = model.contextWindow()
        val output = model.maxOutputTokens()
        require(context.isPresent && output.isPresent) { "the catalog has no limits for $providerId/$modelId; describe the model in the provider's models" }
        val prices = model.prices().orElse(null)
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
        val gate = buildMap {
            put("v", JsonPrimitive(1))
            put("api", JsonPrimitive(features.api()))
            if (features.outputCap() == ApiFeatures.OutputCap.UNSUPPORTED) put("outputCap", JsonPrimitive("unsupported"))
        }
        return Profile(id, providerId, modelId, capabilities, priceTable(prices, priceDate), config = JsonObject(mapOf("gate" to JsonObject(gate))))
    }

    /**
     * The SDK's [prices] as a dated table, tiers kept (§4.5). Each tier states every dimension at the price the SDK
     * charges in it — the tier's own component, else the base one — so [PriceTable.at] reproduces `Prices.cost`.
     * One-hour writes take the long-write price, else the write price, as the SDK does.
     */
    internal fun priceTable(prices: Prices?, date: LocalDate): PriceTable {
        if (prices == null) return PriceTable(date, "USD", emptyMap())
        val tiers = prices.tiers().map { PriceTier(it.inputTokensAbove(), perMillion(prices, it.prices())) }
        return PriceTable(date, prices.currency().currencyCode, perMillion(prices, null), tiers)
    }

    private fun perMillion(base: Prices, tier: Prices?): Map<BillingDimension, BigDecimal> {
        fun price(component: (Prices) -> Optional<BigDecimal>): BigDecimal? =
            tier?.let(component)?.orElse(null) ?: component(base).orElse(null)
        val write = price(Prices::cacheWritePerMillion)
        return buildMap {
            price(Prices::inputPerMillion)?.let { put(BillingDimension.UNCACHED_INPUT, it) }
            price(Prices::cacheReadPerMillion)?.let { put(BillingDimension.CACHE_READ, it) }
            write?.let { put(BillingDimension.CACHE_WRITE_5M, it) }
            (price(Prices::cacheWriteLongPerMillion) ?: write)?.let { put(BillingDimension.CACHE_WRITE_1H, it) }
            price(Prices::outputPerMillion)?.let { put(BillingDimension.OUTPUT, it) }
        }
    }

    /**
     * Probes [profile]'s endpoint and narrows the profile to what the probe proved (doc phase 8: "probe before
     * enabling `cache_read`"): the SDK's staged check with a tool round trip (and, with [cache], a prompt-cache round
     * trip), then one small call whose usage shows which counters the endpoint reports. **Billable**: three to five
     * short calls within [timeout] for the staged check. Usage fields the endpoint does not report on an uncached call are
     * removed (a read the cache probe observed counts as reported); a failed cache probe is not a disqualification: it
     * withdraws explicit cache markers. The result is a proposal to review, never applied to a running campaign.
     */
    @JvmStatic
    @JvmOverloads
    public fun qualify(llm: Llm, profile: Profile, cache: Boolean = false, timeout: Duration = Duration.ofMinutes(2)): Qualification {
        val model = llm.model(profile.provider, profile.model)
        val report = llm.test(model) { t -> t.timeout(timeout).toolRoundTrip(); if (cache) t.cacheRoundTrip() }
        val failed = report.steps().filter { it.status() == ConnectionReport.Status.FAILED }
        val cacheStep = report.steps().firstOrNull { it.kind() == ConnectionReport.Kind.CACHE }
        val problems = failed.filter { it !== cacheStep }.map { "${it.kind()}: ${it.message()}" }.toMutableList()
        if (problems.isNotEmpty()) return Qualification(profile, report, problems, emptyList())
        val notes = ArrayList<String>()
        val usage = llm.complete(model, Conversation.of("Reply with OK."), ChatOptions.builder().responseCache(CacheMode.BYPASS).build()).usage()
        val reported = reported(usage) + if (cacheStep?.status() == ConnectionReport.Status.PASSED) setOf(BillingDimension.CACHE_READ) else emptySet()
        if (BillingDimension.UNCACHED_INPUT !in reported || BillingDimension.OUTPUT !in reported) {
            problems += "USAGE: the endpoint reports $reported; input and output are needed to account a call"
        }
        val declared = profile.capabilities.usageFields
        val fields = declared.filter { it in reported }.toSet()
        (declared - fields).forEach { notes += "usage field $it is not reported by the endpoint on an uncached call: removed" }
        var caching = profile.capabilities.caching
        if (cache && caching.breakpoints && cacheStep?.status() != ConnectionReport.Status.PASSED) {
            notes += "no cache read observed (${cacheStep?.message() ?: "no cache step"}): explicit breakpoints withdrawn"
            caching = CacheCapability(breakpoints = false)
        }
        val narrowed = profile.copy(capabilities = profile.capabilities.copy(usageFields = fields, caching = caching))
        return Qualification(narrowed, report, problems, notes)
    }

    private fun reported(usage: Usage): Set<BillingDimension> = buildSet {
        if (usage.input().isPresent) add(BillingDimension.UNCACHED_INPUT)
        if (usage.cacheRead().isPresent) add(BillingDimension.CACHE_READ)
        if (CacheRetention.SHORT in usage.cacheWrites()) add(BillingDimension.CACHE_WRITE_5M)
        if (CacheRetention.LONG in usage.cacheWrites()) add(BillingDimension.CACHE_WRITE_1H)
        if (usage.cacheWrites().isEmpty() && usage.cacheWrite().isPresent) add(BillingDimension.CACHE_WRITE_5M)
        if (usage.output().isPresent) add(BillingDimension.OUTPUT)
    }
}

/**
 * The outcome of [AiGateProfiles.qualify]: the probed [profile] (narrowed), the SDK's staged [report], [problems] that keep
 * the endpoint from running cells, and [notes] on what was narrowed.
 */
public data class Qualification(val profile: Profile, val report: ConnectionReport, val problems: List<String>, val notes: List<String>) {
    public val qualified: Boolean get() = problems.isEmpty()
}
