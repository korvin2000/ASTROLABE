package io.astrolabe.evallive

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.event.Events
import io.astrolabe.event.Subscription
import io.astrolabe.budget.LimitSpend
import io.astrolabe.id.Identities
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Charge
import io.astrolabe.provider.Money
import io.astrolabe.provider.PriceTable
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.telemetry.Quantities
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedWriter
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Writes every event of one run's bus to `events.jsonl` as it arrives — the whole [EventRecord], so fields added to
 * the events later reach the log without a change here — and keeps them for [Totals].
 */
internal class Recorder(private val events: Events, file: Path) : AutoCloseable {
    private val writer: BufferedWriter = Files.newBufferedWriter(file, StandardCharsets.UTF_8)
    private val kept = CopyOnWriteArrayList<EventRecord>()

    @Volatile
    private var written = 0L
    private val subscription: Subscription = events.subscribe({ record -> write(record) }, BUFFER)

    private fun write(record: EventRecord) {
        synchronized(writer) {
            writer.write(json.encodeToString(EventRecord.serializer(), record))
            writer.newLine()
        }
        kept += record
        written = record.seq
    }

    /** Waits until every event emitted so far is written, up to [waitMillis]. */
    fun drain(waitMillis: Long = 10_000) {
        val until = System.nanoTime() + waitMillis * 1_000_000
        while (written < events.lastSeq && System.nanoTime() < until) Thread.sleep(20)
    }

    val dropped: Long get() = subscription.dropped

    fun events(): List<AgentEvent> = kept.map { it.event }

    /** The events with a sequence number in [from] exclusive to [to] inclusive: one segment of the run. */
    fun events(from: Long, to: Long): List<AgentEvent> = kept.filter { it.seq > from && it.seq <= to }.map { it.event }

    override fun close() {
        subscription.close()
        synchronized(writer) { writer.close() }
    }

    companion object {
        /** Larger than one run's events, so the log is complete; a loss still shows as `eventsDropped`. */
        const val BUFFER: Int = 1_000_000
        val json: Json = Json { encodeDefaults = true }
    }
}

/** A numeric response field: [sum] covers the [known] of [calls] responses that reported it; partial when they differ. */
@Serializable
internal data class Quantity(val sum: String, val known: Int, val calls: Int)

/**
 * What one run's events add up to. Every dispatched model call ends in one `ModelResponded` — answered, failed or
 * cancelled — so its usage counts here; [modelFailures] counts the failed ones. A quantity no response reported is
 * `null`, never 0: a dimension counts only when every response reported it. [modelRequests] is the calls made — one
 * `ModelResponded` each — and [requestEvents] the `ModelRequested` seen, which the core emits before its funding check.
 * Money (B5, by [CallPrice] — the one choice both arms and their limits use): a call counts the bill the provider
 * reported, else its usage at the table of the profile its `ModelRequested` named, paid or nominal by the profile's
 * charge (C16); a call of an unpriced profile counts in [unpricedCalls] and no money. [cost] is that sum (`null` when a
 * part is unknown), [costPricedPart] its known part, [costNominal] the nominal part, [costByTable] every priced call at
 * its table whatever was billed; [costBasis] is the core's `CostBasis` of the choices (`billed`, `estimated`,
 * `nominal`, `mixed`, `none`); [profiles] counts the responses per profile.
 * [responded] aggregates every other numeric field of `ModelResponded` as a [Quantity], so fields the events gain later
 * are aggregated without a change and a partial sum is never mistaken for a whole one. A price tier is a threshold, not
 * a quantity: [priceTiers] counts the responses per tier threshold (`none` when a response named none).
 */
@Serializable
internal data class Totals(
    val modelRequests: Int,
    val modelResponses: Int,
    val modelFailures: Int,
    val cellsStarted: Int,
    val turns: Int,
    val toolCalls: Int,
    val uncachedInputTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val outputTokens: Long?,
    val currency: String,
    val cost: String?,
    val costPricedPart: String?,
    val spanCost: String?,
    val providerModels: List<String>,
    val stops: Map<String, Int>,
    val responded: Map<String, Quantity>,
    val priceTiers: Map<String, Int>,
    val costBasis: String? = null,
    val profiles: Map<String, Int> = emptyMap(),
    val costNominal: String? = null,
    val costByTable: String? = null,
    val unpricedCalls: Int = 0,
    val requestEvents: Int? = null,
) {
    companion object {
        /** Fields of `ModelResponded` read above or that are identities, not quantities. */
        private val KNOWN = setOf("type", "ids", "invocationId", "stop", "usage", "phase", "span", "parent", "failure")

        /** Prices every call with [prices], whatever its profile: the totals of a run on one table. */
        fun of(events: List<AgentEvent>, prices: PriceTable): Totals = of(events, prices.currency) { prices }

        /**
         * Prices every call with the table of the profile its `ModelRequested` named, from [tables] by profile id, in
         * [currency] (B5). A call whose request, profile or currency is not known is priced unknown, never at another
         * profile's prices.
         */
        fun of(events: List<AgentEvent>, tables: Map<String, PriceTable>, currency: String): Totals = of(events, currency) { id -> id?.let(tables::get) }

        private fun of(events: List<AgentEvent>, currency: String, table: (String?) -> PriceTable?): Totals {
            val requested = events.filterIsInstance<AgentEvent.Cell.ModelRequested>().associate { it.invocationId to it.profileId }
            val responded = events.filterIsInstance<AgentEvent.Cell.ModelResponded>()
            val usages = responded.map { it.usage }
            val facts = facts(responded)
            fun dimension(match: (BillingDimension) -> Boolean): Long? {
                if (usages.isEmpty() || usages.any { u -> u == null || u.quantities.keys.none(match) || u.unknown.any(match) }) return null
                return usages.sumOf { u -> u!!.quantities.filterKeys(match).values.sum() }
            }
            val accounts = responded.map { event ->
                val profile = requested[event.invocationId]
                CallPrice.account(event.invocationId, event.ids, profile ?: UNKNOWN, event.usage, table(profile), currency)
            }
            val spend = LimitSpend.of(accounts, 0, currency)
            val priced = spend.cost
            var byTable: Money? = null
            for ((event, account) in responded.zip(accounts)) {
                if (account.charge == Charge.Unpriced) continue
                val prices = table(requested[event.invocationId])?.takeIf { it.currency == currency }
                val money = prices?.let { event.usage?.price(it) } ?: Money.unknown(currency)
                byTable = byTable?.plus(money) ?: money
            }
            val spanCosts = events.filterIsInstance<AgentEvent.Telemetry.SpanEnded>().mapNotNull { it.cost }
            val spanTotal = spanCosts.map { BigDecimal(it.substringAfter(' ')) }.takeIf { it.isNotEmpty() }?.reduce(BigDecimal::add)
            return Totals(
                modelRequests = responded.size,
                modelResponses = responded.size,
                modelFailures = responded.count { it.failure != null },
                cellsStarted = events.count { it is AgentEvent.Cell.Started },
                turns = events.count { it is AgentEvent.Cell.TurnStarted },
                toolCalls = events.count { it is AgentEvent.Cell.ToolCalled },
                uncachedInputTokens = dimension { it == BillingDimension.UNCACHED_INPUT },
                cacheReadTokens = dimension { it == BillingDimension.CACHE_READ },
                cacheWriteTokens = dimension { it.isCacheWrite },
                outputTokens = dimension { it == BillingDimension.OUTPUT },
                currency = currency,
                cost = priced?.takeIf { !it.unknown }?.amount?.toPlainString(),
                // The known part keeps a partially priced call's priced dimensions, which `LimitSpend` folds into "unknown".
                costPricedPart = priced?.let {
                    accounts.fold(BigDecimal.ZERO) { sum, a -> sum + (a.usage?.billed?.takeIf { b -> a.charge == Charge.Paid || b.amount.signum() > 0 }?.amount ?: a.money.amount) }.toPlainString()
                },
                spanCost = spanTotal?.toPlainString(),
                providerModels = usages.mapNotNull { it?.provenance?.model }.distinct(),
                stops = responded.groupingBy { it.stop.name }.eachCount(),
                responded = extra(facts),
                priceTiers = tiers(facts),
                costBasis = spend.costBasis.wire,
                profiles = responded.groupingBy { requested[it.invocationId] ?: UNKNOWN }.eachCount().toSortedMap(),
                costNominal = spend.nominalCost?.takeIf { !it.unknown }?.amount?.toPlainString(),
                costByTable = byTable?.takeIf { !it.unknown }?.amount?.toPlainString(),
                unpricedCalls = spend.unpricedRequests,
                requestEvents = events.count { it is AgentEvent.Cell.ModelRequested },
            )
        }

        /** The numeric fields of every response, by path; a response that did not report a path has no entry for it. */
        private fun facts(responded: List<AgentEvent.Cell.ModelResponded>): List<Map<String, BigDecimal>> = responded.map { event ->
            val tree = Recorder.json.encodeToJsonElement(AgentEvent.serializer(), event) as JsonObject
            sortedMapOf<String, BigDecimal>().also { found -> for ((key, value) in tree) if (key !in KNOWN) collect(key, value, found) }
        }

        private fun extra(facts: List<Map<String, BigDecimal>>): Map<String, Quantity> {
            val paths = facts.flatMap { it.keys }.filterNot(::isTier).toSortedSet()
            return paths.associateWith { path ->
                val known = facts.mapNotNull { it[path] }
                Quantity(known.reduce(BigDecimal::add).toPlainString(), known.size, facts.size)
            }
        }

        private fun tiers(facts: List<Map<String, BigDecimal>>): Map<String, Int> =
            facts.groupingBy { found -> found.entries.firstOrNull { isTier(it.key) }?.value?.toPlainString() ?: NO_TIER }.eachCount().toSortedMap()

        private fun isTier(path: String): Boolean = path.substringAfterLast('.').startsWith("priceTier")

        private fun collect(path: String, value: JsonElement, found: MutableMap<String, BigDecimal>) {
            when (value) {
                is JsonObject -> for ((key, child) in value) collect("$path.$key", child, found)
                is JsonPrimitive -> {
                    val number = if (value.isString) value.content.takeIf { DECIMAL.matches(it) }?.let(::BigDecimal)
                    else value.longOrNull?.let(BigDecimal::valueOf) ?: value.content.toBigDecimalOrNull()
                    if (number != null) found[path] = number
                }
                else -> Unit
            }
        }

        /** The [Totals.priceTiers] bucket of a response that named no price tier: base prices, no prices, or no facts. */
        const val NO_TIER: String = "none"

        /** The profile of a call whose `ModelRequested` is not known. */
        const val UNKNOWN: String = "unknown"

        private val DECIMAL = Regex("""-?\d+(\.\d+)?""")
    }
}

/**
 * The money of one call as the core accounts it (`Accounting.record`, C16), for `Totals` and for the loop's limits
 * alike, so the two never choose different amounts: a positive bill is paid money on any profile; otherwise the usage
 * at [table] — paid or nominal by the table's charge — and an unpriced profile counts no money. Summed by the core's
 * `LimitSpend.of`, which takes the bill of a per-token call even at zero, else this amount, else [hold].
 */
internal object CallPrice {
    fun account(invocationId: String, ids: Identities, profileId: String, usage: BillableUsage?, table: PriceTable?, currency: String, hold: Money? = null): CallAccount {
        val bill = usage?.billed?.takeIf { it.amount.signum() > 0 }
        val prices = table?.takeIf { it.currency == currency }
        val charge = if (bill != null || prices == null) Charge.Paid else prices.charge
        val money = when {
            prices == null -> Money.unknown(currency)
            bill != null && prices.charge != Charge.Paid -> bill.takeIf { it.currency == currency } ?: Money.unknown(currency)
            charge == Charge.Unpriced -> Money.zero(currency)
            else -> usage?.price(prices) ?: Money.unknown(currency)
        }
        return CallAccount(invocationId, ids, profileId, usage, money, prices?.date?.toString() ?: "", Quantities(null, null, null, null), null, Instant.EPOCH, fundedMoney = hold, charge = charge)
    }
}
