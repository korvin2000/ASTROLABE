package io.astrolabe.provider

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A provider-defined billable dimension (`uncached_input`, `cache_read`, `cache_write_5m`, `cache_write_1h`,
 * `output`, `hosted_tool_web_search`, …). Each dimension is priced once (§15.2, I-16); aggregates such as
 * [BillableUsage.totalInput] are diagnostics, never priced.
 */
@Serializable(with = BillingDimension.Serializer::class)
public data class BillingDimension(val id: String) {
    init {
        require(id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) {
            "BillingDimension id must match [a-z0-9_]+, got '$id'"
        }
    }

    val isCacheWrite: Boolean get() = id.startsWith("cache_write")
    val isInput: Boolean get() = id == UNCACHED_INPUT.id || id == CACHE_READ.id || isCacheWrite

    override fun toString(): String = id

    public object Serializer : StringWrapperSerializer<BillingDimension>("BillingDimension", ::BillingDimension, BillingDimension::id)

    public companion object {
        @JvmField public val UNCACHED_INPUT: BillingDimension = BillingDimension("uncached_input")
        @JvmField public val CACHE_READ: BillingDimension = BillingDimension("cache_read")
        @JvmField public val CACHE_WRITE_5M: BillingDimension = BillingDimension("cache_write_5m")
        @JvmField public val CACHE_WRITE_1H: BillingDimension = BillingDimension("cache_write_1h")
        @JvmField public val OUTPUT: BillingDimension = BillingDimension("output")
    }
}

/** How a profile's calls are charged. The host that knows the account states it; nothing infers it from missing prices. */
@Serializable
public enum class Billing {
    /** Per token at the table's prices: a dimension without a price is an unknown charge, never zero. */
    @SerialName("per_token")
    PerToken,

    /**
     * By a plan the account already pays for — a subscription with its own hourly or weekly quotas — or by nobody (a
     * local server). The table states the model's official price, if any (the same model at a paying provider, or a price
     * the user entered): calls are then charged at it as [Charge.Nominal], counted as real and marked apart from paid
     * spend (plan §4.3a item 5, C16). Without a price a call is [Charge.Unpriced]: no money accounting.
     */
    @SerialName("plan")
    Plan,
}

/** How a profile's calls are charged in money, from its [PriceTable] (C16). JSON carries [wire]. */
@Serializable
public enum class Charge(public val wire: String) {
    /** Per token: the provider's bill, else the table's estimate. */
    @SerialName("paid")
    Paid("paid"),

    /** A plan-billed model at its official price: counted as real in limits, statistics and ranking, shown apart. */
    @SerialName("nominal")
    Nominal("nominal"),

    /** A plan-billed model without a price: no money accounting, explicitly marked; request and minute limits still bound it. */
    @SerialName("unpriced")
    Unpriced("unpriced"),
}

/**
 * Dated price per million tokens (or per unit for hosted tools) per billable dimension. [tiers] are the provider's
 * request-size prices (a long-context tier): the highest tier whose [PriceTier.inputTokensAbove] a request's total
 * input exceeds replaces, for that whole request, the base prices of the dimensions it states ([at]). An empty
 * [tiers] and the default [billing] are not serialized, so a table without them keeps its bytes and its attempt
 * fingerprint. A [Billing.Plan] table may state the model's official price (a nominal price, [charge]).
 */
@Serializable
public data class PriceTable @JvmOverloads constructor(
    val date: SerializableLocalDate,
    val currency: String,
    val perMillion: Map<BillingDimension, SerializableBigDecimal>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tiers: List<PriceTier> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val billing: Billing = Billing.PerToken,
) {
    init {
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "currency must be an ISO code, got '$currency'" }
        require(perMillion.values.all { it.signum() >= 0 }) { "prices must be ≥ 0" }
        require(tiers.map { it.inputTokensAbove }.toSet().size == tiers.size) { "price tier thresholds must be distinct" }
        require(billing == Billing.PerToken || perMillion.isNotEmpty() || tiers.isEmpty()) { "a plan-billed table without a base price has no tiers" }
    }

    /** How calls priced by this table are charged (C16): [Charge.Paid] per token, else nominal or unpriced by whether a price is stated. */
    val charge: Charge
        get() = when {
            billing == Billing.PerToken -> Charge.Paid
            perMillion.isEmpty() -> Charge.Unpriced
            else -> Charge.Nominal
        }

    /** Price of [quantity] units of [dimension] at base prices, or `null` when the dimension is not in this table. */
    public fun price(dimension: BillingDimension, quantity: Long): Money? {
        val rate = perMillion[dimension] ?: return null
        return Money(currency, rate.multiply(BigDecimal.valueOf(quantity)).divide(MILLION))
    }

    /** The tier a request of [inputTokens] total input is priced at: the highest threshold it exceeds; `null` at base prices. */
    public fun tier(inputTokens: Long): PriceTier? = tiers.filter { inputTokens > it.inputTokensAbove }.maxByOrNull { it.inputTokensAbove }

    /** The flat table (no tiers) a request of [inputTokens] total input is priced with: base prices overridden by its [tier]. */
    public fun at(inputTokens: Long): PriceTable {
        if (tiers.isEmpty()) return this
        return PriceTable(date, currency, tier(inputTokens)?.let { perMillion + it.perMillion } ?: perMillion, billing = billing)
    }
}

/** Prices for a whole request whose total input exceeds [inputTokensAbove]; a dimension it does not state keeps the base price. */
@Serializable
public data class PriceTier(
    val inputTokensAbove: Long,
    val perMillion: Map<BillingDimension, SerializableBigDecimal>,
) {
    init {
        require(inputTokensAbove >= 0) { "a price tier threshold must be ≥ 0" }
        require(perMillion.values.all { it.signum() >= 0 }) { "prices must be ≥ 0" }
    }
}

private val MILLION: BigDecimal = BigDecimal.valueOf(1_000_000)

/** Where a usage record came from; retained on every persisted usage (D-25). */
@Serializable
public data class UsageProvenance(val provider: String, val model: String, val protocol: String)

/**
 * Normalized billable usage of one provider call (§15.2). [quantities] holds each billable dimension once;
 * [unknown] names dimensions the provider should have reported but did not — missing usage is recorded as
 * missing, never as zero (AX-09, FX-59). [native] retains the raw usage object.
 *
 * [billed] is what the provider reports it charged for the call (OpenRouter `usage.cost`), as reported and never
 * computed — [price] gives the estimate; [billedUpstream] is the upstream provider's own charge where a gateway states
 * it. [reasoningTokens] are part of output when [reasoningIncludedInOutput] and are never priced again. Each is `null`
 * when not reported: unknown, never zero (AX-09).
 */
@Serializable
public data class BillableUsage @JvmOverloads constructor(
    val quantities: Map<BillingDimension, Long>,
    val provenance: UsageProvenance,
    val unknown: Set<BillingDimension> = emptySet(),
    val native: JsonElement? = null,
    val reasoningIncludedInOutput: Boolean? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
    val billed: Money? = null,
    val billedUpstream: Money? = null,
    val reasoningTokens: Long? = null,
) {
    init {
        require(quantities.values.all { it >= 0 }) { "usage quantities must be ≥ 0" }
        require(unknown.none { it in quantities }) { "a dimension cannot be both known and unknown" }
        require(reasoningTokens == null || reasoningTokens >= 0) { "reasoning tokens must be ≥ 0" }
        require(billed?.unknown != true && billedUpstream?.unknown != true) { "a billed amount is reported, never inexact" }
    }

    /** Diagnostic: uncached + cache read + every cache-write class. Never priced. */
    val totalInput: Long get() = quantities.entries.filter { it.key.isInput }.sumOf { it.value }

    /** Diagnostic: every cache-write class summed. Never priced. */
    val totalCacheWrite: Long get() = quantities.entries.filter { it.key.isCacheWrite }.sumOf { it.value }

    val isComplete: Boolean get() = unknown.isEmpty()

    /**
     * Prices each known dimension once with [table]. The result is [Money.unknown] when any dimension is
     * unknown or unpriced; the amount then covers only the priced part and cannot support an exact economic claim.
     * The call is priced at the tier of its [totalInput] ([PriceTable.at]): with an input dimension unknown the tier
     * is chosen from the known part, and the amount is unknown anyway.
     */
    public fun price(table: PriceTable): Money {
        val flat = table.at(totalInput)
        var total = Money.zero(flat.currency)
        var unknownCost = unknown.isNotEmpty()
        for ((dimension, quantity) in quantities) {
            val priced = flat.price(dimension, quantity)
            if (priced == null) unknownCost = true else total += priced
        }
        return if (unknownCost) total.copy(unknown = true) else total
    }

    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** Usage the provider failed to report: every expected dimension unknown (AX-09). */
        @JvmStatic
        public fun missing(provenance: UsageProvenance, expected: Set<BillingDimension>, native: JsonElement? = null): BillableUsage =
            BillableUsage(emptyMap(), provenance, expected, native)
    }
}

/** Decimal money; [unknown] marks a total that a missing or unpriced quantity made inexact. */
@Serializable
public data class Money(
    val currency: String,
    val amount: SerializableBigDecimal,
    val unknown: Boolean = false,
) {
    public operator fun plus(other: Money): Money {
        require(currency == other.currency) { "currency mismatch: $currency vs ${other.currency}" }
        return Money(currency, amount.add(other.amount), unknown || other.unknown)
    }

    public companion object {
        @JvmStatic
        public fun zero(currency: String): Money = Money(currency, BigDecimal.ZERO)

        @JvmStatic
        public fun unknown(currency: String): Money = Money(currency, BigDecimal.ZERO, unknown = true)
    }
}

/**
 * Per-provider mapping from a native usage object to [BillableUsage]. Rules (§15.2): OpenAI cached-input
 * counts are a subset of reported input, so `uncached_input = input − cached − cache_write` where the API
 * reports the latter; Anthropic reports input, cache-read and cache-creation as separate categories whose sum
 * is total input, with five-minute and one-hour writes as distinct dimensions; never add similarly named
 * fields indiscriminately; do not count reasoning twice when it is inside output usage; preserve unknown and
 * bounded estimates when usage is incomplete. Implementations for live providers are deferred (P7).
 */
public fun interface UsageNormalizer {
    public fun normalize(native: JsonElement, profile: Profile): BillableUsage
}
