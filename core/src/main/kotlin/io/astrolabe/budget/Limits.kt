package io.astrolabe.budget

import io.astrolabe.provider.Money
import io.astrolabe.telemetry.CallAccount
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** The refusal of a run or a check a task's minutes limit leaves no active time for (C3r): nothing is dispatched. */
internal const val NO_ACTIVE_TIME: String = "task limit (minutes): no active time is left for this operation; nothing was dispatched"

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
 * The user's limits on one task (plan §4.6, C3); `null` is no limit, and none is set by default. Only the host sets them
 * (`CampaignPolicy.limits`, kept with the campaign across reopens); neither the model nor a regulator raises them.
 * What each guarantees ([LimitRule]):
 * - [maxRequests]: hard — no model call is dispatched past it; the check and the call's durable hold are one transaction.
 * - [maxCost]: bounds the **accounted** spend — billed amounts, else estimates, else holds — under conservative
 *   admission (a call is dispatched only when its conservative price fits). A provider that bills above the hold can
 *   exceed the limit by (billed − held); that overrun is recorded, never hidden. [CostBasis] says where a sum came from,
 *   not that it is an upper bound.
 * - [maxMinutes]: an admission threshold on active time — no model call starts once a mean call would cross the
 *   working part; a model call already running can overrun by its own duration; every `run` and check deadline is cut
 *   at its dispatch to the active time left, and nothing is dispatched once no whole second is left.
 * A limit stop keeps a reserve for verification and the report and names the best verified candidate.
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

/** The task limits beside their spend and the reserves held for the next call's price (C3): what `budget.spent` reports. */
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
    /** `C_next`: the price the decision charged the next call, `null` while none is known. */
    val nextCallCost: Money? = null,
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

    /** One more call of the next call's price would cross [kind]: nothing more is dispatched. */
    public data class Exhausted(val kind: LimitKind, val reason: String) : LimitDecision
}

/**
 * The reserve rule and the decision of the task limits (C3, plan §4.6): pure functions of the limits, the spend and the
 * next call's price.
 *
 * **Next call's price** `C` — one price at every point (a cell boundary, a turn's start, the admission): requests `1`;
 * money `C = max(E, u)`, where `E` is the conservative estimate of the request (every input token at the dearest input
 * rate plus the full output headroom; at a boundary or a turn's start, the last admitted request's, the request being
 * rendered only later) and `u` the dearest accounted call so far; minutes the mean active time per call so far.
 *
 * **Reserve** `R = max(0, min(N·C, L − C))` with `N` = [RESERVE_CALLS]: room for three more calls of the next call's price —
 * a verification turn, a look at its result and the report turn — small and proportional to a call, never a share of a
 * large limit; `L − C` leaves one working call. Requests: `R = max(0, min(3, L − 1))`.
 *
 * **Decision**: `Exhausted` when `S + C > L` (requests `S ≥ L`); else `Reserve` when `S + C + R > L` (requests
 * `S ≥ L − R`); else `Within`. Generation needs `Within`; a verify-and-report spend needs anything but `Exhausted`.
 * Money fails closed: an unknown spend, an unknown or unpriceable next call, or another currency is `Exhausted`.
 * Comparisons never add to a count, so no limit overflows.
 */
public object LimitRule {
    /** `N`: the calls the reserve holds — a verification turn, a look at its result, the report (completion) turn. */
    public const val RESERVE_CALLS: Int = 3

    /** The request reserve of a [limit]: `max(0, min(3, L − 1))`. */
    @JvmStatic
    public fun reserveRequests(limit: Int): Int {
        require(limit > 0) { "a limit is positive" }
        return minOf(RESERVE_CALLS, limit - 1).coerceAtLeast(0)
    }

    /** The money or time reserve of a [limit] for calls of [price]: `max(0, min(N·C, L − C))`. */
    @JvmStatic
    public fun reserveAmount(limit: BigDecimal, price: BigDecimal): BigDecimal {
        require(limit.signum() > 0 && price.signum() >= 0) { "a positive limit and a non-negative price" }
        return price.multiply(BigDecimal.valueOf(RESERVE_CALLS.toLong())).min(limit.subtract(price)).max(BigDecimal.ZERO)
    }

    /** The mean active time per call so far: the minutes limit's per-call price. */
    @JvmStatic
    public fun meanCallMillis(spend: LimitSpend): Long = if (spend.requests == 0) 0L else spend.elapsedMillis / spend.requests

    /** The money price `C = max(E, u)` of the next call; [estimate] is `E` (`null` when no request was estimated yet). */
    @JvmStatic
    public fun nextCost(spend: LimitSpend, estimate: Money?): Money? = when {
        estimate == null -> spend.largestCallCost
        estimate.unknown || spend.largestCallCost == null -> estimate
        spend.largestCallCost.unknown -> spend.largestCallCost
        estimate.currency != spend.largestCallCost.currency -> estimate.copy(unknown = true)
        else -> if (spend.largestCallCost.amount > estimate.amount) spend.largestCallCost else estimate
    }

    /** The limits beside their spend and reserves, for a next call of money price [nextCost]. */
    @JvmStatic
    @JvmOverloads
    public fun status(limits: TaskLimits, spend: LimitSpend, nextCost: Money? = spend.largestCallCost): LimitStatus {
        val maxCost = limits.maxCost
        val price = nextCost?.takeIf { maxCost != null && !it.unknown && it.currency == maxCost.currency }?.amount ?: BigDecimal.ZERO
        val maxMillis = limits.maxMillis
        return LimitStatus(
            requests = spend.requests,
            maxRequests = limits.maxRequests,
            reserveRequests = limits.maxRequests?.let(::reserveRequests),
            cost = spend.cost,
            costBasis = spend.costBasis,
            maxCost = maxCost,
            reserveCost = maxCost?.let { Money(it.currency, reserveAmount(it.amount, price)) },
            elapsedMillis = spend.elapsedMillis,
            maxMillis = maxMillis,
            reserveMillis = maxMillis?.let { reserveAmount(BigDecimal.valueOf(it), BigDecimal.valueOf(meanCallMillis(spend))).toLong() },
            nextCallCost = nextCost,
        )
    }

    /** The answer before the next call of money price [nextCost] (see the object's KDoc); `Exhausted` wins over `Reserve`. */
    @JvmStatic
    @JvmOverloads
    public fun decide(limits: TaskLimits, spend: LimitSpend, nextCost: Money? = spend.largestCallCost): LimitDecision {
        if (!limits.any) return LimitDecision.Within
        val status = status(limits, spend, nextCost)
        val requests = limits.maxRequests
        val maxCost = limits.maxCost
        val maxMillis = limits.maxMillis
        if (requests != null && spend.requests >= requests) {
            return LimitDecision.Exhausted(LimitKind.Requests, "task limit: ${spend.requests} of $requests model requests spent")
        }
        val spentCost = spend.cost?.amount ?: BigDecimal.ZERO
        val price = nextCost?.amount ?: BigDecimal.ZERO
        if (maxCost != null) {
            val known = (spend.cost == null || (!spend.cost.unknown && spend.cost.currency == maxCost.currency)) &&
                (nextCost == null || (!nextCost.unknown && nextCost.currency == maxCost.currency))
            if (!known) return LimitDecision.Exhausted(LimitKind.Cost, "task limit: the spend or the next call cannot be priced in ${maxCost.currency}; a call that cannot be shown to fit ${money(maxCost.amount)} is not dispatched")
            if (spentCost.add(price) > maxCost.amount) {
                return LimitDecision.Exhausted(LimitKind.Cost, "task limit: ${money(spentCost)} of ${money(maxCost.amount)} ${maxCost.currency} accounted (${spend.costBasis.wire}); the next call needs up to ${money(price)}")
            }
        }
        val mean = meanCallMillis(spend)
        if (maxMillis != null && (spend.elapsedMillis >= maxMillis || spend.elapsedMillis > maxMillis - mean)) {
            return LimitDecision.Exhausted(LimitKind.Minutes, "task limit: ${minutes(spend.elapsedMillis)} of ${limits.maxMinutes} min active; a mean call (${minutes(mean)}) would cross it")
        }
        if (requests != null && spend.requests >= requests - checkNotNull(status.reserveRequests)) {
            return LimitDecision.Reserve(LimitKind.Requests, "task limit: ${spend.requests} of $requests model requests spent; the last ${status.reserveRequests} are held for verification and report")
        }
        if (maxCost != null && spentCost.add(price).add(checkNotNull(status.reserveCost).amount) > maxCost.amount) {
            return LimitDecision.Reserve(LimitKind.Cost, "task limit: ${money(spentCost)} of ${money(maxCost.amount)} ${maxCost.currency} accounted (${spend.costBasis.wire}); the next call needs up to ${money(price)} and ${money(status.reserveCost!!.amount)} is held for verification and report")
        }
        if (maxMillis != null && spend.elapsedMillis > maxMillis - mean - checkNotNull(status.reserveMillis)) {
            return LimitDecision.Reserve(LimitKind.Minutes, "task limit: ${minutes(spend.elapsedMillis)} of ${limits.maxMinutes} min active; ${minutes(status.reserveMillis!!)} is held for verification and report")
        }
        return LimitDecision.Within
    }

    private fun money(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString()

    private fun minutes(millis: Long): String = BigDecimal.valueOf(millis).divide(BigDecimal.valueOf(TaskLimits.MILLIS_PER_MINUTE), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " min"
}
