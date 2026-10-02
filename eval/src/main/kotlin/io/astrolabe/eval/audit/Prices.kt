package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Where a call's prices come from: fitted from billed calls of its route, or the catalog's list prices (an estimate). */
public enum class PriceSource { Billed, Catalog }

/** The prices a call is priced with; [from] names the fitted route or the catalog entry. */
@Serializable
public data class CallPrices(
    val source: PriceSource,
    val from: String,
    val currency: String,
    val perMillion: Map<PriceClass, SerializableBigDecimal?>,
)

/** List prices per million tokens from an ai-gate catalog snapshot (`catalog-snapshot.json`), by `provider` and model id. */
public class Catalog(entries: Map<Pair<String, String>, CallPrices>) {
    private val entries = entries.toMap()

    public fun prices(provider: String, model: String): CallPrices? = entries[provider to model]

    public companion object {
        @JvmField public val EMPTY: Catalog = Catalog(emptyMap())

        @JvmStatic
        public fun read(file: Path): Catalog = parse(Files.readString(file, StandardCharsets.UTF_8))

        @JvmStatic
        public fun parse(text: String): Catalog {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return EMPTY
            val entries = HashMap<Pair<String, String>, CallPrices>()
            for (model in root.array("models").orEmpty()) {
                val m = model as? JsonObject ?: continue
                val provider = m.str("provider") ?: continue
                val id = m.str("id") ?: continue
                val prices = m.obj("prices") ?: continue
                val perMillion = mapOf(
                    PriceClass.UncachedInput to prices.decimal("input"),
                    PriceClass.CacheRead to prices.decimal("cacheRead"),
                    PriceClass.CacheWrite to prices.decimal("cacheWrite"),
                    PriceClass.Output to prices.decimal("output"),
                )
                entries[provider to id] = CallPrices(PriceSource.Catalog, "catalog $provider/$id", prices.str("currency") ?: "USD", perMillion)
            }
            return Catalog(entries)
        }
    }
}

/**
 * The prices of every call of an audit: a route's own billed fit when it identifies prices, else the fit of all the
 * model's routes on that provider (an upstream with too few calls), else the catalog's list prices, else none.
 */
public class PriceBook(fits: List<PriceFit>, private val catalog: Catalog, private val currencies: Map<Binding, String>) {
    public val fits: List<PriceFit> = fits.toList()
    private val byBinding = fits.filter { it.agreement != Agreement.Unidentified }.associateBy { it.binding }

    public fun prices(call: ModelCall): CallPrices? {
        val binding = call.binding ?: return null
        val pooled = binding.copy(upstream = ANY)
        (byBinding[binding] ?: byBinding[pooled])?.let { fit ->
            return CallPrices(PriceSource.Billed, "billed fit ${fit.binding} (${fit.agreement}, ${fit.calls} calls)", currencies[fit.binding] ?: "USD", fit.perMillion)
        }
        return catalog.prices(binding.provider, binding.model)
    }

    public companion object {
        /** The upstream label of a fit pooled over all of a model's upstreams. */
        public const val ANY: String = "*"

        /** Fits every route of [calls] and, where several upstreams served a model, the model pooled over them. */
        @JvmStatic
        public fun of(calls: List<ModelCall>, catalog: Catalog): PriceBook {
            val billed = calls.filter { it.binding != null && it.billed != null && row(it) != null }
            val currencies = HashMap<Binding, String>()
            val fits = ArrayList<PriceFit>()
            fun fit(binding: Binding, group: List<ModelCall>) {
                group.firstNotNullOfOrNull { it.billedCurrency }?.let { currencies[binding] = it }
                fits += AuditMath.fitPrices(binding, group.map { row(it)!! })
            }
            for ((binding, group) in billed.groupBy { it.binding!! }) fit(binding, group)
            for ((model, group) in billed.groupBy { it.binding!!.copy(upstream = ANY) }) {
                if (group.mapTo(HashSet()) { it.binding!!.upstream }.size > 1) fit(model, group)
            }
            return PriceBook(fits, catalog, currencies)
        }

        private fun row(call: ModelCall): FitRow? {
            val u = call.usage ?: return null
            val tokens = mapOf(
                PriceClass.UncachedInput to (u.uncachedInput ?: return null),
                PriceClass.CacheRead to (u.cacheRead ?: return null),
                PriceClass.CacheWrite to (u.cacheWrite ?: 0),
                PriceClass.Output to (u.output ?: return null),
            )
            return FitRow(tokens, call.billed ?: return null)
        }
    }
}

/** The tokens of [usage] in [c], `null` when unknown. */
internal fun tokensOf(usage: CallUsage, c: PriceClass): Long? = when (c) {
    PriceClass.UncachedInput -> usage.uncachedInput
    PriceClass.CacheRead -> usage.cacheRead
    PriceClass.CacheWrite -> usage.cacheWrite ?: 0
    PriceClass.Output -> usage.output
}

/** The priced cost of one call: each class's tokens at its price; `null` when a class with tokens is unknown or unpriced. */
internal fun pricedCost(usage: CallUsage, prices: CallPrices?): BigDecimal? {
    if (prices == null) return null
    var total = BigDecimal.ZERO
    for (c in PriceClass.entries) {
        val tokens = tokensOf(usage, c) ?: return null
        if (tokens == 0L) continue
        total += AuditMath.money(tokens, prices.perMillion[c]) ?: return null
    }
    return total
}
