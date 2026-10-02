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
    private val kept = CopyOnWriteArrayList<AgentEvent>()

    @Volatile
    private var written = 0L
    private val subscription: Subscription = events.subscribe({ record -> write(record) }, BUFFER)

    private fun write(record: EventRecord) {
        synchronized(writer) {
            writer.write(json.encodeToString(EventRecord.serializer(), record))
            writer.newLine()
        }
        kept += record.event
        written = record.seq
    }

    /** Waits until every event emitted so far is written, up to [waitMillis]. */
    fun drain(waitMillis: Long = 10_000) {
        val until = System.nanoTime() + waitMillis * 1_000_000
        while (written < events.lastSeq && System.nanoTime() < until) Thread.sleep(20)
    }

    val dropped: Long get() = subscription.dropped

    fun events(): List<AgentEvent> = kept.toList()

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

/**
 * What one run's events add up to. A quantity no response reported is `null`, never 0: a dimension counts only when
 * every response reported it. [cost] prices the usage with the profile's table and is `null` when any part is
 * unknown or unpriced; [costPricedPart] is what could be priced. [responded] sums every other numeric field of
 * `ModelResponded`, so fields the events gain later (A2a: billed cost, reasoning, timings) are summed without a change.
 */
@Serializable
internal data class Totals(
    val modelRequests: Int,
    val modelResponses: Int,
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
    val responded: Map<String, String>,
) {
    companion object {
        /** Fields of `ModelResponded` read above or that are identities, not quantities. */
        private val KNOWN = setOf("type", "ids", "invocationId", "stop", "usage", "phase", "span", "parent")

        fun of(events: List<AgentEvent>, prices: PriceTable): Totals {
            val responded = events.filterIsInstance<AgentEvent.Cell.ModelResponded>()
            val usages = responded.map { it.usage }
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
                responded = extra(responded),
            )
        }

        private fun extra(responded: List<AgentEvent.Cell.ModelResponded>): Map<String, String> {
            val sums = sortedMapOf<String, BigDecimal>()
            for (event in responded) {
                val tree = Recorder.json.encodeToJsonElement(AgentEvent.serializer(), event) as JsonObject
                for ((key, value) in tree) if (key !in KNOWN) collect(key, value, sums)
            }
            return sums.mapValues { it.value.toPlainString() }
        }

        private fun collect(path: String, value: JsonElement, sums: MutableMap<String, BigDecimal>) {
            when (value) {
                is JsonObject -> for ((key, child) in value) collect("$path.$key", child, sums)
                is JsonPrimitive -> {
                    val number = if (value.isString) value.content.takeIf { DECIMAL.matches(it) }?.let(::BigDecimal)
                    else value.longOrNull?.let(BigDecimal::valueOf) ?: value.content.toBigDecimalOrNull()
                    if (number != null) sums.merge(path, number, BigDecimal::add)
                }
                else -> Unit
            }
        }

        private val DECIMAL = Regex("""-?\d+(\.\d+)?""")
    }
}
