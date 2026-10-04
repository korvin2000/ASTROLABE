package io.astrolabe.telemetry

import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.Charge
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * The four quantities of §11.5, kept apart. `null` is unknown, never zero: bytes on the wire are the transport's
 * (P7), so [bytesTransmitted] here is the serialized request handed to the adapter; [modelVisibleInput] and
 * [billedUsage] come from the provider's usage; [durableState] is measured over the store when exported.
 */
@Serializable
public data class Quantities(
    val bytesTransmitted: Long?,
    val modelVisibleInput: Long?,
    val billedUsage: Long?,
    val durableState: Long?,
)

/**
 * One priced model call (§15.2): the native usage retained, the normalized categories, and money from the
 * profile's dated price table. [money] is [Money.unknown] when usage is missing, incomplete or unpriced — a
 * missing usage is recorded as missing, never as zero spend (FX-59). [warm] says whether the call read the
 * provider cache; cold and warm calls are reported separately. [charge] says whether [money] was paid, is a plan-billed
 * model's nominal price or is no money accounting at all (C16); a row written before C16 reads as [Charge.Paid].
 */
@Serializable
public data class CallAccount(
    val invocationId: String,
    val ids: Identities,
    val profileId: String,
    val usage: BillableUsage?,
    val money: Money,
    val priceTableDate: String,
    val quantities: Quantities,
    val warm: Boolean?,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val fundedTokens: Long? = null,
    val fundedMoney: Money? = null,
    val charge: Charge = Charge.Paid,
)

/**
 * Totals over calls, cold and warm apart; each money sum is unknown as soon as one call's is. [money] is paid plus nominal
 * (C16: a plan-billed model's nominal spend counts as real); [paidMoney] and [nominalMoney] keep them apart and
 * [unpricedCalls] counts calls without money accounting. The three are `null` in totals stored before C16.
 */
@Serializable
public data class AccountTotals(
    val calls: Int,
    val callsWithoutUsage: Int,
    val money: Money,
    val coldMoney: Money,
    val warmMoney: Money,
    val quantities: Map<BillingDimension, Long?>,
    /** Undefined (`null`) at zero accepted tasks and when [money] is unknown. */
    val costPerAcceptedTask: Money?,
    val paidMoney: Money? = null,
    val nominalMoney: Money? = null,
    val unpricedCalls: Int = 0,
)

/**
 * Per-call accounting (§15.2, §11.5): the cell reports each response here, and the call is priced and stored in
 * the `usage` table (this component is its one writer). Reasoning is priced only as the provider's own
 * dimensions say — no field is added to another with a similar name.
 */
public class Accounting internal constructor(
    private val store: Store,
    private val clock: Clock,
    /**
     * C3: the task limits' admission of one more call priced [Money] at most over the calls on record, asked inside the
     * transaction that writes the call's durable hold — so concurrent cells can never pass the limit together.
     */
    private val admission: ((calls: List<CallAccount>, money: Money) -> Boolean)?,
) {
    public constructor(store: Store, clock: Clock) : this(store, clock, null)

    /** Records one call; a `null` [usage] is a call whose usage never arrived. */
    public fun record(ids: Identities, invocationId: String, profile: Profile, request: Request?, usage: BillableUsage?, fundedTokens: Long? = null): CallAccount {
        val table = profile.priceTable
        // C16: a positive bill is real money whatever the plan says — the call is paid, at the bill, counted once.
        val bill = usage?.billed?.takeIf { it.amount.signum() > 0 }
        val charge = if (bill != null) Charge.Paid else table.charge
        val money = when {
            bill != null && table.charge != Charge.Paid -> bill.takeIf { it.currency == table.currency } ?: Money.unknown(table.currency)
            charge == Charge.Unpriced -> Money.zero(table.currency)
            else -> usage?.price(table) ?: Money.unknown(table.currency)
        }
        val prior = calls(ids.work).firstOrNull { it.invocationId == invocationId }
        val account = CallAccount(
            invocationId = invocationId,
            ids = ids,
            profileId = profile.id,
            usage = usage,
            money = money,
            priceTableDate = table.date.toString(),
            quantities = Quantities(
                bytesTransmitted = request?.let { JSON.encodeToString(Request.serializer(), it).toByteArray(Charsets.UTF_8).size.toLong() },
                modelVisibleInput = usage?.takeIf { it.unknown.none(BillingDimension::isInput) }?.totalInput,
                billedUsage = usage?.takeIf { it.isComplete }?.quantities?.values?.fold(0L, ::add),
                durableState = null,
            ),
            warm = usage?.let { u -> u.quantities[BillingDimension.CACHE_READ]?.let { it > 0 } ?: if (BillingDimension.CACHE_READ in u.unknown) null else false },
            at = prior?.at ?: clock.instant(),
            fundedTokens = fundedTokens ?: usage?.takeIf { it.isComplete }?.quantities?.values?.fold(0L, ::add) ?: prior?.fundedTokens,
            // An unsettled call keeps its conservative reservation as known funding, so later calls stay affordable.
            fundedMoney = if (!money.unknown) money else prior?.fundedMoney?.let { it.copy(amount = maxOf(it.amount, money.amount)) },
            charge = charge,
        )
        store.db.tx { tx -> save(tx, account) }
        return account
    }

    /** A durable reservation precedes dispatch. Concurrent children share the same transaction. */
    internal fun reserve(ids: Identities, invocationId: String, profile: Profile, tokens: Long, money: Money,
                         tokenLimit: Long, costLimit: Money?): Boolean = store.db.tx { tx ->
        if (!affordable(ids.work, tokens, money, tokenLimit, costLimit)) return@tx false
        if (admission != null && !admission.invoke(calls(ids.work), money)) return@tx false
        val account = CallAccount(invocationId, ids, profile.id, null, Money.unknown(profile.priceTable.currency),
            profile.priceTable.date.toString(), Quantities(null, null, null, null), null, clock.instant(), tokens, money, profile.priceTable.charge)
        save(tx, account)
        true
    }

    /**
     * Releases the durable hold [reserve] wrote for [invocationId] when its request is known never to have reached the
     * provider (C3r): nothing was sent, so nothing is spent and no request is counted. A settled call is never removed;
     * an unknown outcome keeps its hold until it is reconciled.
     */
    internal fun release(ids: Identities, invocationId: String) {
        store.db.tx { tx -> tx.execute("DELETE FROM usage WHERE invocation_id = ? AND work_id = ? AND normalized = 'null'", invocationId, ids.work) }
    }

    private fun affordable(work: WorkId, tokens: Long, money: Money, tokenLimit: Long, costLimit: Money?): Boolean {
        require(tokens >= 0 && money.amount.signum() >= 0)
        val calls = calls(work)
        val spent = calls.fold(0L) { total, call -> add(total, call.fundedTokens ?: call.quantities.billedUsage ?: tokenLimit) }
        if (tokens > (tokenLimit - spent).coerceAtLeast(0)) return false
        if (costLimit != null) {
            val costs = calls.map { it.fundedMoney ?: it.money }
            if (money.unknown || money.currency != costLimit.currency || costs.any { it.unknown || it.currency != costLimit.currency }) return false
            if (costs.fold(money.amount) { sum, cost -> sum + cost.amount } > costLimit.amount) return false
        }
        return true
    }

    internal fun reserveExtraction(ids: Identities, invocationId: String, tokens: Long, money: Money?, tokenLimit: Long,
                                   costLimit: Money?, currency: String): Boolean = store.db.tx { tx ->
        if (calls(ids.work).any { it.invocationId == invocationId } || !affordable(ids.work, tokens, money ?: Money.unknown(currency), tokenLimit, costLimit)) return@tx false
        // C3: a call without a known price bound is unknown to the limits, which refuse it under a money limit.
        if (admission != null && !admission.invoke(calls(ids.work), money ?: Money.unknown(currency))) return@tx false
        save(tx, CallAccount(invocationId, ids, "extractor", null, Money.unknown(currency), "host",
            Quantities(null, null, null, null), null, clock.instant(), tokens, money))
        true
    }

    internal fun extraction(ids: Identities, invocationId: String, tokens: Long?, funded: Long, cost: Money?, currency: String = "USD",
                            fundedCost: Money? = cost) {
        val usage = tokens?.let { BillableUsage(mapOf(BillingDimension("extractor_tokens") to it),
            io.astrolabe.provider.UsageProvenance("host", "extractor", "reported-total")) }
        val account = CallAccount(invocationId, ids, "extractor", usage, cost ?: Money.unknown(currency), "host",
            Quantities(null, null, tokens, null), null, clock.instant(), funded, fundedCost)
        store.db.tx { save(it, account) }
    }

    private fun save(tx: io.astrolabe.store.Tx, account: CallAccount) {
        val usage = account.usage
        val existing = store.db.query("SELECT body FROM usage WHERE invocation_id = ?", account.invocationId) {
            JSON.decodeFromString(CallAccount.serializer(), it.string("body"))
        }.firstOrNull()
        require(existing == null || (existing.ids == account.ids && existing.profileId == account.profileId)) { "invocation belongs to another call" }
        tx.execute("INSERT INTO usage (invocation_id, work_id, attempt_id, candidate_id, context_id, profile_id, native, normalized, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(invocation_id) DO UPDATE SET native=excluded.native, normalized=excluded.normalized, body=excluded.body",
            account.invocationId, account.ids.work, account.ids.attempt, account.ids.candidate, account.ids.context,
            account.profileId, (usage?.native ?: JsonNull).toString(), usage?.let { JSON.encodeToString(BillableUsage.serializer(), it) } ?: "null",
            Migrations.SCHEMA_VERSION, account.at, JSON.encodeToString(CallAccount.serializer(), account))
    }

    internal fun remainingTokens(work: WorkId, limit: Long): Long =
        (limit - calls(work).fold(0L) { total, call -> add(total, call.fundedTokens ?: call.quantities.billedUsage ?: limit) }).coerceAtLeast(0)

    internal fun remainingCost(work: WorkId, limit: Money?): Money? = limit?.let {
        val spent = calls(work).fold(Money.zero(it.currency)) { total, call -> total + (call.fundedMoney ?: call.money) }
        Money(it.currency, (it.amount - spent.amount).max(BigDecimal.ZERO), it.unknown || spent.unknown)
    }

    /** Every call of [work], in recording order. */
    public fun calls(work: WorkId): List<CallAccount> = store.db.query(
        "SELECT body FROM usage WHERE work_id = ? ORDER BY created_at, rowid", work,
    ) { JSON.decodeFromString(CallAccount.serializer(), it.string("body")) }

    /** Totals of [work] with `cost_per_accepted_task` over [accepted] verified increments. */
    public fun totals(work: WorkId, accepted: Int, currency: String): AccountTotals = totals(calls(work), accepted, currency)

    public companion object {
        internal fun add(a: Long, b: Long): Long = if (b > Long.MAX_VALUE - a) Long.MAX_VALUE else a + b

        /**
         * The conservative charge of a call of at most [input] input and [output] output tokens: every input token at the
         * dearest input rate, under every price table the call can be billed at — the base and each tier whose threshold
         * is below [input] (tiers do not accumulate, so effective rates can fall as input grows). Unknown, never zero,
         * when a table lacks the output price or a price for an input dimension the route can bill; zero for an
         * [unpriced] profile. A plan-billed profile with a nominal price is estimated at it like a per-token one (C16), so
         * routing and admission never take a subscription model for a free one.
         */
        internal fun estimateCost(profile: Profile, input: Long, output: Long): Money {
            val prices = profile.priceTable
            if (unpriced(profile)) return Money.zero(prices.currency)
            val billable = billableInput(profile)
            val reachable = listOf(prices.at(0)) + prices.tiers.filter { it.inputTokensAbove < input }.map { prices.at(it.inputTokensAbove + 1) }
            var worst = Money.zero(prices.currency)
            for (table in reachable) {
                if (billable.any { it !in table.perMillion }) return Money.unknown(prices.currency)
                val rate = table.perMillion.filterKeys { it.isInput }.values.max()
                val out = table.price(BillingDimension.OUTPUT, output) ?: return Money.unknown(prices.currency)
                val cost = Money(prices.currency, rate.multiply(BigDecimal.valueOf(input)).divide(BigDecimal.valueOf(1_000_000))) + out
                if (cost.amount > worst.amount) worst = cost
            }
            return worst
        }

        /**
         * D-409, C16: a profile the host declared plan-billed with no price stated ([Charge.Unpriced]) charges the task's
         * money limit nothing; the request and minute limits bound it. With the model's official price it is
         * [Charge.Nominal] and charged at that price. Missing prices alone never mean either: a per-token table without
         * a price stays an unknown charge and fails closed.
         */
        internal fun unpriced(profile: Profile): Boolean = profile.priceTable.charge == Charge.Unpriced

        /** Input dimensions a call on [profile] can be billed in: uncached input, the declared input usage fields and the cache-write classes. */
        private fun billableInput(profile: Profile): Set<BillingDimension> =
            setOf(BillingDimension.UNCACHED_INPUT) + profile.capabilities.usageFields.filter { it.isInput } + profile.capabilities.caching.writeClasses

        internal val JSON: Json = Json { encodeDefaults = true }

        @JvmStatic
        public fun totals(calls: List<CallAccount>, accepted: Int, currency: String): AccountTotals {
            fun sum(selected: List<CallAccount>) = selected.fold(Money.zero(currency)) { total, call -> total + call.money }
            val paid = sum(calls.filter { it.charge == Charge.Paid })
            val nominal = sum(calls.filter { it.charge == Charge.Nominal })
            val money = paid + nominal
            val dimensions = calls.mapNotNull { it.usage }.flatMap { it.quantities.keys + it.unknown }.distinct()
            return AccountTotals(
                calls = calls.size,
                callsWithoutUsage = calls.count { it.usage == null },
                money = money,
                coldMoney = sum(calls.filter { it.warm == false }),
                warmMoney = sum(calls.filter { it.warm == true }),
                quantities = dimensions.associateWith { d ->
                    // Host extraction reports an aggregate, separate from provider cache/input/output dimensions.
                    val selected = calls.filter { (it.profileId == "extractor") == (d.id == "extractor_tokens") }
                    if (selected.any { it.usage == null || d !in it.usage.quantities }) null
                    else selected.fold(0L) { total, call -> add(total, call.usage!!.quantities.getValue(d)) }
                },
                costPerAcceptedTask = if (accepted == 0 || money.unknown) null else Money(currency, money.amount.divide(BigDecimal.valueOf(accepted.toLong()), 10, RoundingMode.HALF_EVEN)),
                paidMoney = paid,
                nominalMoney = nominal,
                unpricedCalls = calls.count { it.charge == Charge.Unpriced },
            )
        }

        /** Bytes the store holds on disk: the database and every blob (§11.5 "durable state"). */
        @JvmStatic
        public fun durableState(root: Path): Long = if (!Files.exists(root)) 0 else Files.walk(root).use { paths ->
            paths.asSequence().filter { it.isRegularFile() }.sumOf { it.fileSize() }
        }
    }
}
