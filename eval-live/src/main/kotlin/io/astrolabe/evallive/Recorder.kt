package io.astrolabe.evallive

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.event.Events
import io.astrolabe.event.Subscription
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.PriceTable
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
 * `null`, never 0: a dimension counts only when every response reported it. [cost] prices the usage with the
 * profile's table and is `null` when any part is unknown or unpriced; [costPricedPart] is what could be priced.
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
) {
    companion object {
        /** Fields of `ModelResponded` read above or that are identities, not quantities. */
        private val KNOWN = setOf("type", "ids", "invocationId", "stop", "usage", "phase", "span", "parent", "failure")

        fun of(events: List<AgentEvent>, prices: PriceTable): Totals {
            val responded = events.filterIsInstance<AgentEvent.Cell.ModelResponded>()
            val usages = responded.map { it.usage }
            val facts = facts(responded)
            fun dimension(match: (BillingDimension) -> Boolean): Long? {
                if (usages.isEmpty() || usages.any { u -> u == null || u.quantities.keys.none(match) || u.unknown.any(match) }) return null
                return usages.sumOf { u -> u!!.quantities.filterKeys(match).values.sum() }
            }
            var priced: Money? = null
            for (usage in usages) {
                val money = usage?.price(prices) ?: Money.unknown(prices.currency)
                priced = priced?.plus(money) ?: money
            }
            val spanCosts = events.filterIsInstance<AgentEvent.Telemetry.SpanEnded>().mapNotNull { it.cost }
            val spanTotal = spanCosts.map { BigDecimal(it.substringAfter(' ')) }.takeIf { it.isNotEmpty() }?.reduce(BigDecimal::add)
            return Totals(
                modelRequests = events.count { it is AgentEvent.Cell.ModelRequested },
                modelResponses = responded.size,
                modelFailures = responded.count { it.failure != null },
                cellsStarted = events.count { it is AgentEvent.Cell.Started },
                turns = events.count { it is AgentEvent.Cell.TurnStarted },
                toolCalls = events.count { it is AgentEvent.Cell.ToolCalled },
                uncachedInputTokens = dimension { it == BillingDimension.UNCACHED_INPUT },
                cacheReadTokens = dimension { it == BillingDimension.CACHE_READ },
                cacheWriteTokens = dimension { it.isCacheWrite },
                outputTokens = dimension { it == BillingDimension.OUTPUT },
                currency = prices.currency,
                cost = priced?.takeIf { !it.unknown }?.amount?.toPlainString(),
                costPricedPart = priced?.amount?.toPlainString(),
                spanCost = spanTotal?.toPlainString(),
                providerModels = usages.mapNotNull { it?.provenance?.model }.distinct(),
                stops = responded.groupingBy { it.stop.name }.eachCount(),
                responded = extra(facts),
                priceTiers = tiers(facts),
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

        private val DECIMAL = Regex("""-?\d+(\.\d+)?""")
    }
}
