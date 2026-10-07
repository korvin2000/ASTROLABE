package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext

/**
 * A run's flows (D-421, plan §9.1): the harness decides on these first, the money axis only re-prices them. [reasoning]
 * is part of [output].
 */
@Serializable
public data class Flows(
    val requests: Int,
    val uncachedInput: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
    val reasoning: Long,
) {
    public operator fun plus(other: Flows): Flows = Flows(
        requests + other.requests, uncachedInput + other.uncachedInput, cacheRead + other.cacheRead,
        cacheWrite + other.cacheWrite, output + other.output, reasoning + other.reasoning,
    )

    public companion object {
        /** The flows of [anatomy]; `null` when a class the provider reports is unknown. A class no provider has counts 0. */
        @JvmStatic
        public fun of(anatomy: Anatomy): Flows? {
            fun tokens(c: Category): Long? = c.tokens ?: if (c.reported) null else 0L
            return Flows(
                anatomy.calls,
                tokens(anatomy.uncachedInput) ?: return null,
                tokens(anatomy.cacheRead) ?: return null,
                tokens(anatomy.cacheWrite) ?: return null,
                tokens(anatomy.output) ?: return null,
                tokens(anatomy.reasoning) ?: 0L,
            )
        }
    }
}

/**
 * List prices per million tokens of one model (D-421), with where they come from. A `null` [cacheRead] or [cacheWrite]
 * is no discount and no markup: that class is priced as uncached input.
 */
@Serializable
public data class PriceProfile(
    val name: String,
    /** The output / input price ratio the profile stands for: `2`, `3`, `5` or `tens`. */
    val ratio: String,
    val source: String,
    val input: SerializableBigDecimal,
    val cacheRead: SerializableBigDecimal?,
    val cacheWrite: SerializableBigDecimal?,
    val output: SerializableBigDecimal,
)

/** [flows] priced at [profile]: the money and each item's share of it; the shares are `null` when the money is zero. */
@Serializable
public data class Repriced(
    val profile: String,
    val money: SerializableBigDecimal,
    val uncachedInputShare: Double?,
    val cacheReadShare: Double?,
    val cacheWriteShare: Double?,
    val outputShare: Double?,
)

/**
 * The re-pricing what-if (D-421): the same flows at the list prices of other models. A what-if, not a forecast — another
 * model's flows would differ. The profiles are data: extend [PROFILES] with a name and the source of its prices.
 */
public object Repricing {
    private fun usd(text: String) = BigDecimal(text)

    /** The profiles of the first re-pricing (`session_4_results.md` §2.3), by output / input price ratio. */
    @JvmField
    public val PROFILES: List<PriceProfile> = listOf(
        PriceProfile("MiMo-V2.6-Pro, cache at input price", "2", "AI Gate catalog; no cache-read price listed", usd("0.43"), null, null, usd("0.87")),
        PriceProfile("MiMo-V2.6-Pro, cache read at 10 % of input", "2", "AI Gate catalog; cache read assumed at 10 % of input", usd("0.43"), usd("0.043"), null, usd("0.87")),
        PriceProfile("Qwen3.8 Max", "3", "AI Gate catalog", usd("2"), usd("0.25"), null, usd("6")),
        PriceProfile("Grok 4.7", "3", "AI Gate catalog", usd("2"), usd("0.5"), null, usd("6")),
        PriceProfile("Fable 5", "5", "AI Gate catalog", usd("10"), usd("1"), null, usd("50")),
        PriceProfile("deepseek-v4.1-flash", "tens", "AI Gate catalog snapshot, openrouter/deepseek/deepseek-v4.1-flash", usd("0.003"), usd("0.003"), null, usd("2.4")),
    )

    /** [flows] at [profile]'s prices: a pure function. */
    @JvmStatic
    public fun price(flows: Flows, profile: PriceProfile): Repriced {
        fun cost(tokens: Long, perMillion: BigDecimal) = perMillion.multiply(BigDecimal.valueOf(tokens)).divide(MILLION)
        val items = listOf(
            cost(flows.uncachedInput, profile.input),
            cost(flows.cacheRead, profile.cacheRead ?: profile.input),
            cost(flows.cacheWrite, profile.cacheWrite ?: profile.input),
            cost(flows.output, profile.output),
        )
        val money = items.fold(BigDecimal.ZERO, BigDecimal::add)
        val shares = items.map { if (money.signum() == 0) null else it.divide(money, MathContext.DECIMAL64).toDouble() }
        return Repriced(profile.name, money.stripTrailingZeros(), shares[0], shares[1], shares[2], shares[3])
    }

    /** [flows] at every one of [profiles]; empty when the flows are unknown. */
    @JvmStatic
    @JvmOverloads
    public fun of(flows: Flows?, profiles: List<PriceProfile> = PROFILES): List<Repriced> = flows?.let { f -> profiles.map { price(f, it) } }.orEmpty()

    private val MILLION = BigDecimal(1_000_000)
}
