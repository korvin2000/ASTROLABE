package io.astrolabe.budget

import io.astrolabe.provider.Money
import io.astrolabe.telemetry.CallAccount
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** What a task limit counts (plan §4.6, C3). */
@Serializable
public enum class LimitKind(public val wire: String) {
    /** Money for model calls: the provider's billed amount, else the price-table estimate (D-378). */
    Cost("money"),

    /** Minutes of active work on the injected clock: the time runs of the campaign held the controller. */
    Minutes("minutes"),

    /** Model requests: every dispatched model call of the task, child, reviewer and extractor calls included. */
    Requests("requests"),
}

/** Which amounts a money spend was summed from (C3, D-378). */
@Serializable
public enum class CostBasis(public val wire: String) {
    /** Every call reported the amount the provider billed. */
    Billed("billed"),

    /** No call reported one: price-table estimates, or the conservative hold of a call not yet settled. */
    Estimated("estimated"),

    /** Some calls billed, the others estimated. */
    Mixed("mixed"),

    /** No call yet. */
    None("none"),
}

/**
 * The user's hard limits on one task (plan §4.6, C3): money for model calls, minutes of active work and model
 * requests; `null` is no limit. Only the host sets them, at the start and at each resume (`CampaignPolicy.limits`):
 * neither the model nor a regulator raises them. The harness stops before a limit is crossed, keeping a reserve
 * for verification and the report ([LimitRule]).
 */
@Serializable
public data class TaskLimits @JvmOverloads constructor(
    val maxCost: Money? = null,
    val maxMinutes: Int? = null,
    val maxRequests: Int? = null,
) {
    init {
        require(maxCost == null || (!maxCost.unknown && maxCost.amount.signum() > 0)) { "maxCost must be a known positive amount" }
        require(maxMinutes == null || maxMinutes > 0) { "maxMinutes must be positive, got $maxMinutes" }
        require(maxRequests == null || maxRequests > 0) { "maxRequests must be positive, got $maxRequests" }
    }

    /** True when at least one limit is set. */
    val any: Boolean get() = maxCost != null || maxMinutes != null || maxRequests != null

    /** [maxMinutes] in milliseconds. */
    val maxMillis: Long? get() = maxMinutes?.let { it * MILLIS_PER_MINUTE }

    override fun toString(): String = listOfNotNull(
        maxCost?.let { "money ${it.amount.toPlainString()} ${it.currency}" },
        maxMinutes?.let { "$it min" },
        maxRequests?.let { "$it requests" },
    ).joinToString(", ").ifEmpty { "none" }

    public companion object {
        /** No limit of any kind. */
        @JvmField
        public val NONE: TaskLimits = TaskLimits()

        internal const val MILLIS_PER_MINUTE: Long = 60_000L
    }
}

/**
 * What a task has spent against its limits, derived from durable records only — the `usage` rows (one per
 * invocation id) and the journal's run sessions — so a reopen never counts a call or a minute twice (D-392).
 */
@Serializable
public data class LimitSpend(
    val requests: Int,
    /** `null` before the first call; [Money.unknown] once a call's amount is unknown or in another currency. */
    val cost: Money?,
    val costBasis: CostBasis,
    val elapsedMillis: Long,
    /** The dearest single call so far — billed, estimated or held: the per-call unit of the money reserve. */
    val largestCallCost: Money?,
) {
    init {
        require(requests >= 0 && elapsedMillis >= 0) { "a spend is never negative" }
    }

    public companion object {
        /**
         * The spend of [calls] in [currency] after [elapsedMillis] of active work. A call counts what the provider billed
         * (D-378), else its price-table estimate, else the conservative hold a call keeps until it settles; a call in
         * another currency, or with none of these known, makes the cost unknown — never zero (FX-59).
         */
        @JvmStatic
        public fun of(calls: List<CallAccount>, elapsedMillis: Long, currency: String): LimitSpend {
            if (calls.isEmpty()) return LimitSpend(0, null, CostBasis.None, elapsedMillis, null)
            var total = Money.zero(currency)
            var largest = Money.zero(currency)
            var billed = 0
            for (call in calls) {
                val reported = call.usage?.billed
                val amount = when {
                    reported != null -> reported.also { billed++ }
                    !call.money.unknown -> call.money
                    call.fundedMoney != null && !call.fundedMoney.unknown -> call.fundedMoney
                    else -> Money.unknown(currency)
                }
                val counted = if (amount.currency == currency) amount else Money.unknown(currency)
                total += counted
                if (counted.unknown) largest = largest.copy(unknown = true)
                else if (counted.amount > largest.amount) largest = counted.copy(unknown = largest.unknown)
            }
            val basis = when (billed) {
                calls.size -> CostBasis.Billed
                0 -> CostBasis.Estimated
                else -> CostBasis.Mixed
            }
            return LimitSpend(calls.size, total, basis, elapsedMillis, largest)
        }
    }
}

/** The task limits beside their spend and their reserves (C3): what `budget.spent` reports and the receipt keeps. */
@Serializable
public data class LimitStatus(
    val requests: Int,
    val maxRequests: Int?,
    val reserveRequests: Int?,
    val cost: Money?,
    val costBasis: CostBasis,
    val maxCost: Money?,
    val reserveCost: Money?,
    val elapsedMillis: Long,
    val maxMillis: Long?,
    val reserveMillis: Long?,
)

/** The task limits' answer before a model call (C3). */
public sealed interface LimitDecision {
    /** Within every limit and its working part: any spend may proceed. */
    public data object Within : LimitDecision

    /**
     * The working part of [kind] is spent: generation and edits stop, and only verification and the report may still
     * spend — from the reserve held for them (§8.1 reserve gate, applied to the task's limits).
     */
    public data class Reserve(val kind: LimitKind, val reason: String) : LimitDecision

    /** [kind] would be crossed by one more call: nothing more is dispatched. */
    public data class Exhausted(val kind: LimitKind, val reason: String) : LimitDecision
}

/**
 * The reserve rule and the decision of the task limits (C3, plan §4.6) — pure functions of the limits, the spend and
 * the attempt's frozen [Reserves] (the cell's own fractions: verification `v`, recovery/persist `r`).
 *
 * **Reserve** of a limit `L` with per-call unit `u` — small and proportional to what verifying and reporting cost, never
 * a fixed share of a large limit (owner, 2026-10-03):
 * `R = max(0, min(N·u, s·L, L − u))`, with `N` = [RESERVE_CALLS] calls (verify, check the result, report), the share
 * cap `s = v + r` (the cell's reserve share, §8.1) binding only on small limits, and `L − u` leaving one working call.
 * `u` is `1` for requests (and `⌈s·L⌉` is rounded up to whole calls), the dearest call so far for money, and the mean
 * active time per call so far for minutes — `0` before the first call, when there is nothing to verify yet. A $50 limit
 * with $0.10 calls holds $0.30; 3,000 requests hold 3.
 *
 * **Decision** for the next call of cost `c` (requests `1`; minutes `0`, which only the reserve covers; money: the
 * dearest call so far at a turn's start, and at admission the request's conservative estimate or the dearest call,
 * whichever is dearer): `Exhausted` when `S + c > L` for any kind (`S ≥ L` for minutes), else `Reserve` when
 * `S + c > L − R` (`S ≥ L − R` for minutes), else `Within`. The working part is decided at a turn's start; admission
 * enforces only `Exhausted` (the controller's gate, `TaskLimitControl.gate`). A money limit with an unknown spend, an
 * unknown next call or another currency is `Exhausted`: a spend that cannot be shown to fit does not proceed.
 */
public object LimitRule {
    /** `N`: the calls the reserve holds — a verification turn, a look at its result, the report (completion) turn. */
    public const val RESERVE_CALLS: Int = 3

    /** The request reserve of a [limit] under [reserves]. */
    @JvmStatic
    public fun reserveRequests(limit: Int, reserves: Reserves): Int {
        require(limit > 0) { "a limit is positive" }
        val share = BigDecimal.valueOf(limit.toLong()).multiply(share(reserves)).setScale(0, java.math.RoundingMode.CEILING).toInt()
        return minOf(RESERVE_CALLS, share, limit - 1).coerceAtLeast(0)
    }

    /** The money or time reserve of a [limit] whose per-call [unit] is known so far, under [reserves]. */
    @JvmStatic
    public fun reserveAmount(limit: BigDecimal, reserves: Reserves, unit: BigDecimal): BigDecimal {
        require(limit.signum() > 0 && unit.signum() >= 0) { "a positive limit and a non-negative unit" }
        val calls = unit.multiply(BigDecimal.valueOf(RESERVE_CALLS.toLong()))
        return calls.min(limit.multiply(share(reserves))).min(limit.subtract(unit)).max(BigDecimal.ZERO)
    }

    /** The limits beside their spend and reserves. */
    @JvmStatic
    public fun status(limits: TaskLimits, spend: LimitSpend, reserves: Reserves): LimitStatus {
        val maxCost = limits.maxCost
        val unitCost = spend.largestCallCost?.takeIf { !it.unknown && maxCost != null && it.currency == maxCost.currency }?.amount ?: BigDecimal.ZERO
        val maxMillis = limits.maxMillis
        val unitMillis = if (spend.requests == 0) 0L else spend.elapsedMillis / spend.requests
        return LimitStatus(
            requests = spend.requests,
            maxRequests = limits.maxRequests,
            reserveRequests = limits.maxRequests?.let { reserveRequests(it, reserves) },
            cost = spend.cost,
            costBasis = spend.costBasis,
            maxCost = maxCost,
            reserveCost = maxCost?.let { Money(it.currency, reserveAmount(it.amount, reserves, unitCost)) },
            elapsedMillis = spend.elapsedMillis,
            maxMillis = maxMillis,
            reserveMillis = maxMillis?.let { reserveAmount(BigDecimal.valueOf(it), reserves, BigDecimal.valueOf(unitMillis)).toLong() },
        )
    }

    /**
     * The answer before the next call: [nextCost] is its conservative money (`null` before the first call is known);
     * a hard limit is checked before the working part, so `Exhausted` wins over `Reserve`.
     */
    @JvmStatic
    @JvmOverloads
    public fun decide(limits: TaskLimits, spend: LimitSpend, reserves: Reserves, nextCost: Money? = spend.largestCallCost): LimitDecision {
        if (!limits.any) return LimitDecision.Within
        val status = status(limits, spend, reserves)
        val requests = limits.maxRequests
        val maxCost = limits.maxCost
        val maxMillis = limits.maxMillis
        val next = nextCost?.takeIf { maxCost != null }
        val costKnown = maxCost == null || (spend.cost?.let { !it.unknown && it.currency == maxCost.currency } ?: true) &&
            (next == null || (!next.unknown && next.currency == maxCost.currency))
        val spentCost = spend.cost?.amount ?: BigDecimal.ZERO
        val nextAmount = next?.amount ?: BigDecimal.ZERO
        if (requests != null && spend.requests + 1 > requests) {
            return LimitDecision.Exhausted(LimitKind.Requests, "task limit: ${spend.requests} of $requests model requests spent")
        }
        if (maxCost != null) {
            if (!costKnown) return LimitDecision.Exhausted(LimitKind.Cost, "task limit: the money spend or the next call cannot be priced in ${maxCost.currency}; a spend that cannot be shown to fit ${maxCost.amount.toPlainString()} does not proceed")
            if (spentCost.add(nextAmount) > maxCost.amount) {
                return LimitDecision.Exhausted(LimitKind.Cost, "task limit: ${money(spentCost)} of ${money(maxCost.amount)} ${maxCost.currency} spent (${spend.costBasis.wire}); the next call (≤ ${money(nextAmount)}) would cross it")
            }
        }
        if (maxMillis != null && spend.elapsedMillis >= maxMillis) {
            return LimitDecision.Exhausted(LimitKind.Minutes, "task limit: ${minutes(spend.elapsedMillis)} of ${limits.maxMinutes} min spent")
        }
        if (requests != null && spend.requests + 1 > requests - checkNotNull(status.reserveRequests)) {
            return LimitDecision.Reserve(LimitKind.Requests, "task limit: ${spend.requests} of $requests model requests spent; the last ${status.reserveRequests} are held for verification and report")
        }
        if (maxCost != null && spentCost.add(nextAmount) > maxCost.amount.subtract(checkNotNull(status.reserveCost).amount)) {
            return LimitDecision.Reserve(LimitKind.Cost, "task limit: ${money(spentCost)} of ${money(maxCost.amount)} ${maxCost.currency} spent (${spend.costBasis.wire}); ${money(status.reserveCost!!.amount)} is held for verification and report")
        }
        if (maxMillis != null && spend.elapsedMillis >= maxMillis - checkNotNull(status.reserveMillis)) {
            return LimitDecision.Reserve(LimitKind.Minutes, "task limit: ${minutes(spend.elapsedMillis)} of ${limits.maxMinutes} min spent; ${minutes(status.reserveMillis!!)} is held for verification and report")
        }
        return LimitDecision.Within
    }

    /** `s = v + r` in decimal arithmetic, so `⌈s·L⌉` never gains a unit through a binary rounding (`100 · 0.2` is 20). */
    private fun share(reserves: Reserves): BigDecimal =
        BigDecimal.valueOf(reserves.verification).add(BigDecimal.valueOf(reserves.recoveryAndPersist))

    private fun money(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString()

    private fun minutes(millis: Long): String = BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(TaskLimits.MILLIS_PER_MINUTE), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " min"
}
