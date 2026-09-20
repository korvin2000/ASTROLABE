package io.astrolabe.provider

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

/** Dated price per million tokens (or per unit for hosted tools) per billable dimension. */
@Serializable
public data class PriceTable(
    val date: SerializableLocalDate,
    val currency: String,
    val perMillion: Map<BillingDimension, SerializableBigDecimal>,
) {
    init {
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "currency must be an ISO code, got '$currency'" }
        require(perMillion.values.all { it.signum() >= 0 }) { "prices must be ≥ 0" }
    }

    /** Price of [quantity] units of [dimension], or `null` when the dimension is not in this table. */
    public fun price(dimension: BillingDimension, quantity: Long): Money? {
        val rate = perMillion[dimension] ?: return null
        return Money(currency, rate.multiply(BigDecimal.valueOf(quantity)).divide(MILLION))
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
 */
@Serializable
public data class BillableUsage(
    val quantities: Map<BillingDimension, Long>,
    val provenance: UsageProvenance,
    val unknown: Set<BillingDimension> = emptySet(),
    val native: JsonElement? = null,
    val reasoningIncludedInOutput: Boolean? = null,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(quantities.values.all { it >= 0 }) { "usage quantities must be ≥ 0" }
        require(unknown.none { it in quantities }) { "a dimension cannot be both known and unknown" }
    }

    /** Diagnostic: uncached + cache read + every cache-write class. Never priced. */
    val totalInput: Long get() = quantities.entries.filter { it.key.isInput }.sumOf { it.value }

    /** Diagnostic: every cache-write class summed. Never priced. */
    val totalCacheWrite: Long get() = quantities.entries.filter { it.key.isCacheWrite }.sumOf { it.value }

    val isComplete: Boolean get() = unknown.isEmpty()

    /**
     * Prices each known dimension once with [table]. The result is [Money.unknown] when any dimension is
     * unknown or unpriced; the amount then covers only the priced part and cannot support an exact economic claim.
     */
    public fun price(table: PriceTable): Money {
        var total = Money.zero(table.currency)
        var unknownCost = unknown.isNotEmpty()
        for ((dimension, quantity) in quantities) {
            val priced = table.price(dimension, quantity)
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
