package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** What a run's money shares are shares of: the billed total when every call with usage was billed, else the priced estimate. */
public enum class MoneyBasis { Billed, Estimate, None }

/**
 * One cost category: tokens, money at the calls' prices, and the share of the run's basis total. [tokens] and [money]
 * are `null` when any call left the class unknown or it has tokens without a price; [reported] is false when no
 * call's provider has the class at all (none, not unknown).
 */
@Serializable
public data class Category(val tokens: Long?, val money: SerializableBigDecimal?, val share: Double?, val reported: Boolean = true)

/**
 * The cost anatomy of one run (F §2.1): tokens and money per billing class, with reasoning as part of output. Billed
 * and estimated money stay apart: [billed] sums the reported charges, [estimate] prices every call with usage.
 * [unexplained] is the basis total minus every category — zero when the prices reproduce the bill, `null` when a
 * category is unknown. [complete] is false when a call has no usage, an unknown class or (on a billed basis) no charge:
 * the totals then cover only part of the run. [pricesEstimated] is true when any call was priced by a pooled fit,
 * the catalog or a fit that does not reproduce its bills.
 */
@Serializable
public data class Anatomy(
    val calls: Int,
    val callsWithUsage: Int,
    val billedCalls: Int,
    val currency: String?,
    val billed: SerializableBigDecimal?,
    val billedUpstream: SerializableBigDecimal?,
    val estimate: SerializableBigDecimal?,
    val basis: MoneyBasis,
    val total: SerializableBigDecimal?,
    val complete: Boolean,
    val prices: List<String>,
    val pricesEstimated: Boolean,
    val uncachedInput: Category,
    val cacheRead: Category,
    val cacheWrite: Category,
    val output: Category,
    val reasoning: Category,
    val unexplained: SerializableBigDecimal?,
) {
    public companion object {
        @JvmStatic
        public fun of(calls: List<ModelCall>, book: PriceBook): Anatomy {
            val used = calls.filter { it.usage != null }
            val billedCalls = calls.filter { it.billed != null }
            val billed = billedCalls.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { s, c -> s + c.billed!! }
            val upstream = calls.mapNotNull { it.billedUpstream }.takeIf { it.isNotEmpty() && it.size == billedCalls.size }?.reduce(BigDecimal::add)
            val prices = used.associate { it.index to book.prices(it) }
            val estimate = if (used.isEmpty()) null else used.fold(BigDecimal.ZERO as BigDecimal?) { s, c -> pricedCost(c.usage!!, prices[c.index])?.let { s?.add(it) } }
            val basis = when {
                used.isNotEmpty() && used.all { it.billed != null } -> MoneyBasis.Billed
                estimate != null -> MoneyBasis.Estimate
                else -> MoneyBasis.None
            }
            val total = when (basis) {
                MoneyBasis.Billed -> billed
                MoneyBasis.Estimate -> estimate
                MoneyBasis.None -> null
            }
            fun category(tokens: (CallUsage) -> Long?, price: PriceClass, absent: PriceClass? = price): Category {
                val counts = used.map { tokens(it.usage!!) }
                val sum = if (used.isEmpty() || counts.any { it == null }) null else counts.sumOf { it!! }
                var money: BigDecimal? = if (sum == null) null else BigDecimal.ZERO
                for ((c, n) in used.zip(counts)) {
                    if (n == null || n == 0L) continue
                    money = money?.let { m -> AuditMath.money(n, prices[c.index]?.perMillion?.get(price))?.let(m::add) }
                }
                val reported = absent == null || used.isEmpty() || used.any { absent !in it.usage!!.absent }
                return Category(sum, money?.stripTrailingZeros(), AuditMath.share(money, total), reported)
            }
            val categories = listOf(
                category({ it.uncachedInput }, PriceClass.UncachedInput),
                category({ it.cacheRead }, PriceClass.CacheRead),
                category({ it.cacheWrite }, PriceClass.CacheWrite),
                category({ it.output }, PriceClass.Output),
            )
            val explained = categories.fold(BigDecimal.ZERO as BigDecimal?) { s, c -> c.money?.let { s?.add(it) } }
            val known = used.all { u -> PriceClass.entries.all { tokensOf(u.usage!!, it) != null } }
            return Anatomy(
                calls = calls.size,
                callsWithUsage = used.size,
                billedCalls = billedCalls.size,
                currency = billedCalls.firstNotNullOfOrNull { it.billedCurrency } ?: prices.values.firstNotNullOfOrNull { it?.currency },
                billed = billed?.stripTrailingZeros(),
                billedUpstream = upstream?.stripTrailingZeros(),
                estimate = estimate?.stripTrailingZeros(),
                basis = basis,
                total = total?.stripTrailingZeros(),
                complete = used.size == calls.size && known && (basis != MoneyBasis.Billed || billedCalls.size == calls.size),
                prices = prices.values.mapNotNull { it?.from }.distinct(),
                pricesEstimated = prices.values.any { it?.estimate == true },
                uncachedInput = categories[0],
                cacheRead = categories[1],
                cacheWrite = categories[2],
                output = categories[3],
                reasoning = category({ it.reasoning }, PriceClass.Output, absent = null),
                unexplained = if (total == null || explained == null) null else total.subtract(explained).stripTrailingZeros(),
            )
        }
    }
}
