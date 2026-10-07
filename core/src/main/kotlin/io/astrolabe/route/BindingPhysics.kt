package io.astrolabe.route

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventSink
import io.astrolabe.event.Events
import io.astrolabe.event.Subscription
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.PriceTable
import io.astrolabe.provider.Profile
import io.astrolabe.provider.StopReason
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock

/**
 * One model call as binding physics reads it (plan §4.6, §10.4), built from `cell.model_responded` and the call's
 * profile by [of]. Every quantity is `null` when unknown, never zero. [sessionId] is the provider session — the work,
 * whose `WorkId.sessionKey` every request carries — and delimits the cache series and the latency fit; [transcriptId]
 * (the cell, else the attempt) is the request history whose previous request is this call's unchanged prefix.
 */
public data class BindingObservation @JvmOverloads constructor(
    val key: BindingKey,
    val ids: Identities,
    val invocationId: String,
    val inputTokens: Long? = null,
    val cachedTokens: Long? = null,
    val uncachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val latencyMillis: Long? = null,
    /** The provider's own charge for the call (OpenRouter `usage.cost`), never computed. */
    val billed: Money? = null,
    /** The profile's flat price table at this call's input: the documented prices, apart from the bill. */
    val documented: PriceTable? = null,
    /** The input threshold of the price tier the call was billed at; `null` at base prices. */
    val priceTierInputTokensAbove: Long? = null,
    /** The kind of error the call ended with (the provider error's class, or `Truncated`); `null` when it answered. */
    val failure: String? = null,
) {
    init {
        require(invocationId.isNotBlank()) { "an observation names its invocation" }
        listOf(inputTokens, cachedTokens, uncachedInputTokens, cacheWriteTokens, outputTokens, reasoningTokens, latencyMillis, priceTierInputTokensAbove)
            .forEach { require(it == null || it >= 0) { "token counts and times are ≥ 0" } }
        require(cachedTokens == null || inputTokens == null || cachedTokens <= inputTokens) { "cached input is part of the input" }
        require(billed == null || !billed.unknown) { "a bill is reported, never inexact" }
        require(failure == null || failure.isNotBlank())
    }

    val sessionId: String get() = ids.work.value

    val transcriptId: String get() = ids.context?.value ?: ids.attempt.value

    public companion object {
        /** The observation of [event], a call on [profile]: usage as reconciled, facts as the transport saw them. */
        @JvmStatic
        public fun of(profile: Profile, event: AgentEvent.Cell.ModelResponded): BindingObservation {
            val usage = event.usage
            val quantities = usage?.quantities.orEmpty()
            val inputKnown = usage != null && quantities.keys.any { it.isInput } && usage.unknown.none { it.isInput }
            val input = if (inputKnown) usage?.totalInput else null
            val table = profile.priceTable.takeIf { it.perMillion.isNotEmpty() }
            return BindingObservation(
                key = BindingKey.of(profile, event.facts?.upstream),
                ids = event.ids,
                invocationId = event.invocationId,
                inputTokens = input,
                cachedTokens = if (inputKnown) quantities[BillingDimension.CACHE_READ] ?: 0L else null,
                uncachedInputTokens = if (inputKnown) quantities[BillingDimension.UNCACHED_INPUT] ?: 0L else null,
                cacheWriteTokens = if (inputKnown) usage?.totalCacheWrite else null,
                outputTokens = quantities[BillingDimension.OUTPUT],
                reasoningTokens = usage?.reasoningTokens,
                latencyMillis = event.facts?.latencyMillis,
                billed = usage?.billed,
                documented = table?.let { if (input != null) it.at(input) else it.at(0) },
                priceTierInputTokensAbove = event.facts?.priceTierInputTokensAbove ?: input?.let { table?.tier(it)?.inputTokensAbove },
                failure = event.failure ?: event.stop.takeIf { it == StopReason.Truncated }?.name,
            )
        }
    }
}

/**
 * The cache-share statistics of a binding (§10.4, part A's [CacheShareState]): the current series of the provider session
 * and the lifetime totals, plus the last request's input per transcript of the session, which is the next request's
 * cacheable prefix.
 */
@Serializable
public data class BindingCacheStats(
    val priorAlpha: Double = 1.0,
    val priorBeta: Double = 1.0,
    val sessionId: String? = null,
    val cacheableTokens: Long = 0,
    val cachedTokens: Long = 0,
    val blockIds: List<String> = emptyList(),
    val totalInputTokens: Long = 0,
    val totalCachedTokens: Long = 0,
    val lastInputTokens: Map<String, Long> = emptyMap(),
) {
    public fun state(upstream: String?): CacheShareState = CacheShareState(
        BetaParameters(priorAlpha, priorBeta), sessionId?.let { CacheSeriesKey(upstream, it) },
        cacheableTokens, cachedTokens, blockIds.toSet(), totalInputTokens, totalCachedTokens,
    )
}

/** One latency sample of the session fit: output tokens and the call's seconds. */
@Serializable
public data class BindingLatencySample(val outputTokens: Long, val elapsedSeconds: Double)

/** The latency fit's state (§10.4, part A's [LatencyState]): the samples of the current provider session. */
@Serializable
public data class BindingLatencyStats(val sessionId: String? = null, val samples: List<BindingLatencySample> = emptyList()) {
    public fun state(): LatencyState =
        LatencyState(sessionId, sessionId?.let { s -> samples.map { LatencyObservation(s, it.outputTokens, it.elapsedSeconds) } }.orEmpty())
}

/** Reasoning tokens per request: the calls that reported them and their sum. */
@Serializable
public data class BindingReasoning(val calls: Long = 0, val tokens: Long = 0) {
    val meanTokens: Double? get() = if (calls == 0L) null else tokens.toDouble() / calls
}

/** The documented prices ([PriceProvenance.Documented]) of one price tier: per million tokens by dimension. */
@Serializable
public data class DocumentedPrices(val currency: String, val date: String, val perMillion: Map<String, String>)

/** The bills ([PriceProvenance.Billed]) of one currency: their sum and count. A bill determines only its own sum. */
@Serializable
public data class BilledTotals(val amount: String, val calls: Long)

/** One bill with the quantities it priced, kept for inference within its price tier and currency. */
@Serializable
public data class PriceBill(
    val seq: Long,
    val scope: String,
    val uncachedInputTokens: Long,
    val cacheReadTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long,
    val cost: String,
)

/**
 * The prices inferred ([PriceProvenance.Inferred]) from the bills of one scope (§10.4): per-token rates when three
 * independent bills determine them and every other bill agrees, else [insufficiency] and no rates.
 */
@Serializable
public data class InferredPrices(
    val bills: Int,
    val uncachedInputPerToken: Double? = null,
    val cacheReadPerToken: Double? = null,
    val outputPerToken: Double? = null,
    val cacheWritePerToken: Double? = null,
    val insufficiency: PriceInsufficiency? = null,
)

/**
 * The three price sources of a binding, kept apart (§10.4): [documented] by price tier, [billed] by currency, and
 * [inferred] by scope (`<price tier>/<currency>`) from the last [BindingPhysicsRow.BILL_WINDOW] [bills].
 */
@Serializable
public data class BindingPrices(
    val documented: Map<String, DocumentedPrices> = emptyMap(),
    val billed: Map<String, BilledTotals> = emptyMap(),
    val bills: List<PriceBill> = emptyList(),
    val inferred: Map<String, InferredPrices> = emptyMap(),
)

/**
 * One row of `binding_physics` (§4.6): the sufficient statistics of part A's estimators for one [key], the reasoning per
 * request, errors by kind and the three price sources. [updatedSeq] is the table-wide order of the last observation
 * applied, so replaying the same observations in `seq` order rebuilds the same row ([next] is a pure function).
 */
@Serializable
public data class BindingPhysicsRow @JvmOverloads constructor(
    val key: BindingKey,
    val updatedSeq: Long = 0,
    val calls: Long = 0,
    val cache: BindingCacheStats = BindingCacheStats(),
    val latency: BindingLatencyStats = BindingLatencyStats(),
    val reasoning: BindingReasoning = BindingReasoning(),
    val errors: Map<String, Long> = emptyMap(),
    val prices: BindingPrices = BindingPrices(),
) {
    public fun cacheShare(): CacheShareEstimate = estimateCacheShare(cache.state(key.upstream))

    public fun latencyEstimate(): LatencyEstimate = estimateLatency(latency.state())

    /** This row after [observation], applied as the table's [seq]-th update. */
    public fun next(observation: BindingObservation, seq: Long): BindingPhysicsRow {
        require(observation.key == key) { "an observation of ${observation.key} cannot update $key" }
        require(seq > updatedSeq) { "updates are applied in seq order" }
        return copy(
            updatedSeq = seq,
            calls = calls + 1,
            cache = nextCache(observation),
            latency = nextLatency(observation),
            reasoning = observation.reasoningTokens?.let { BindingReasoning(reasoning.calls + 1, Math.addExact(reasoning.tokens, it)) } ?: reasoning,
            errors = observation.failure?.let { errors + (it to (errors[it] ?: 0L) + 1) }?.toSortedMap()?.toMap() ?: errors,
            prices = nextPrices(observation, seq),
        )
    }

    private fun nextCache(o: BindingObservation): BindingCacheStats {
        val input = o.inputTokens ?: return cache
        val cached = o.cachedTokens ?: return cache
        val sameSession = cache.sessionId == o.sessionId
        val previous = if (sameSession) cache.lastInputTokens[o.transcriptId] else null
        // §10.4 b_i: the previous request of the same transcript is this request's unchanged prefix. A first request, or
        // one shorter than its predecessor (the harness rewrote the transcript), is no evidence of the cache.
        val rewrite = previous == null || input < previous
        val cacheable = maxOf(cached, if (previous == null) cached else minOf(input, previous))
        val state = updateCacheShare(cache.state(key.upstream), CacheObservation(CacheSeriesKey(key.upstream, o.sessionId), cacheable, cached, input, o.invocationId, rewrite))
        return BindingCacheStats(
            state.prior.alpha, state.prior.beta, o.sessionId, state.cacheableTokens, state.cachedTokens, state.blockIds.toList(),
            state.totalInputTokens, state.totalCachedTokens,
            (if (sameSession) cache.lastInputTokens else emptyMap()) + (o.transcriptId to input),
        )
    }

    private fun nextLatency(o: BindingObservation): BindingLatencyStats {
        // A failed call's time is not the model's; tool time never enters a call's latency.
        if (o.failure != null) return latency
        val millis = o.latencyMillis ?: return latency
        val output = o.outputTokens ?: return latency
        val state = updateLatency(latency.state(), LatencyObservation(o.sessionId, output, millis / 1000.0))
        return BindingLatencyStats(state.sessionId, state.observations.map { BindingLatencySample(it.outputTokens, it.elapsedSeconds) })
    }

    private fun nextPrices(o: BindingObservation, seq: Long): BindingPrices {
        val tier = o.priceTierInputTokensAbove?.let { "above-$it" } ?: "base"
        val documented = o.documented?.let { table ->
            prices.documented + (tier to DocumentedPrices(table.currency, table.date.toString(), table.perMillion.entries
                .associate { (dimension, rate) -> dimension.id to rate.stripTrailingZeros().toPlainString() }.toSortedMap()))
        } ?: prices.documented
        val bill = o.billed ?: return prices.copy(documented = documented)
        val total = prices.billed[bill.currency]
        val billed = prices.billed + (bill.currency to BilledTotals(
            ((total?.amount?.let(::BigDecimal) ?: BigDecimal.ZERO) + bill.amount).stripTrailingZeros().toPlainString(), (total?.calls ?: 0L) + 1,
        ))
        val uncached = o.uncachedInputTokens
        val cacheRead = o.cachedTokens
        val output = o.outputTokens
        if (uncached == null || cacheRead == null || output == null) return prices.copy(documented = documented, billed = billed)
        val scope = "$tier/${bill.currency}"
        val bills = (prices.bills + PriceBill(seq, scope, uncached, cacheRead, output, o.cacheWriteTokens ?: 0L, bill.amount.stripTrailingZeros().toPlainString()))
            .takeLast(BILL_WINDOW)
        return BindingPrices(documented, billed, bills, prices.inferred + (scope to infer(scope, bills.filter { it.scope == scope })))
    }

    private fun infer(scope: String, bills: List<PriceBill>): InferredPrices {
        // Part A's scope names a routing tier; the price tier and currency live in the route id instead.
        val priceScope = PriceScope("${key.canonical}#$scope", Tier.Low)
        val state = bills.fold(PriceState(priceScope, includeCacheWrite = bills.any { it.cacheWriteTokens > 0 })) { state, b ->
            updatePrices(state, PriceObservation(priceScope, b.uncachedInputTokens, b.cacheReadTokens, b.outputTokens, BigDecimal(b.cost).toDouble(), b.cacheWriteTokens))
        }
        return when (val estimate = estimatePrices(state)) {
            is PriceEstimate.Determined -> InferredPrices(bills.size, estimate.rates.uncachedInputPerToken, estimate.rates.cacheReadPerToken,
                estimate.rates.outputPerToken, estimate.rates.cacheWritePerToken)
            is PriceEstimate.InsufficientData -> InferredPrices(bills.size, insufficiency = estimate.reason)
        }
    }

    public companion object {
        /** Bills kept per binding for price inference: the most recent ones, in `seq` order. */
        public const val BILL_WINDOW: Int = 32
    }
}

/**
 * The binding physics an attempt reads (§4.6 invariant 12, tier "frozen per attempt"): the rows of the attempt's
 * profile [routes] on every upstream, as of the table's [asOfSeq]. Frozen once per attempt in `binding_snapshots`; a
 * reopen of the attempt reads the same snapshot, whatever the live table holds by then.
 */
@Serializable
public data class BindingSnapshot(
    val work: WorkId,
    val attempt: AttemptId,
    val asOfSeq: Long,
    val routes: List<BindingKey>,
    val rows: List<BindingPhysicsRow>,
) {
    /** The rows of [route]'s model, gateway and wire API, on every upstream. */
    public fun rows(route: BindingKey): List<BindingPhysicsRow> = rows.filter { it.key.sameRoute(route) }

    public fun rows(profile: Profile): List<BindingPhysicsRow> = rows(BindingKey.of(profile))
}

/**
 * The one writer of `binding_physics` and `binding_snapshots` (store v7, L9). [observe] applies one call to its key's
 * row in a transaction; the row's `updated_seq` is the table's next number, so the table's history is a total order.
 * Only [Clock] stamps a row's `created_at`; no time enters a row's statistics (I-05).
 */
public class BindingPhysics @JvmOverloads constructor(
    private val store: Store,
    private val clock: Clock,
    /** The cache-share prior of a new binding (Beta(1, 1) by default, §10.4). */
    private val prior: BetaParameters = BetaParameters(),
) {
    public fun observe(observation: BindingObservation): BindingPhysicsRow = store.db.tx { tx ->
        val key = observation.key
        val seq = tx.query("SELECT coalesce(max(updated_seq), 0) AS seq FROM binding_physics") { it.long("seq") }.first() + 1
        val current = tx.query("SELECT body FROM binding_physics WHERE binding_key = ?", key.canonical) {
            JSON.decodeFromString(BindingPhysicsRow.serializer(), it.string("body"))
        }.firstOrNull() ?: BindingPhysicsRow(key, cache = BindingCacheStats(prior.alpha, prior.beta))
        val row = current.next(observation, seq)
        val ids = observation.ids
        tx.execute(
            "INSERT INTO binding_physics (binding_key, work_id, attempt_id, candidate_id, context_id, updated_seq, schema_version, created_at, body) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(binding_key) DO UPDATE SET work_id = excluded.work_id, attempt_id = excluded.attempt_id, " +
                "candidate_id = excluded.candidate_id, context_id = excluded.context_id, updated_seq = excluded.updated_seq, " +
                "schema_version = excluded.schema_version, body = excluded.body",
            key.canonical, ids.work, ids.attempt, ids.candidate, ids.context, seq, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(BindingPhysicsRow.serializer(), row),
        )
        row
    }

    public fun row(key: BindingKey): BindingPhysicsRow? = store.db.query("SELECT body FROM binding_physics WHERE binding_key = ?", key.canonical) {
        JSON.decodeFromString(BindingPhysicsRow.serializer(), it.string("body"))
    }.firstOrNull()

    /** Every row, in key order. */
    public fun rows(): List<BindingPhysicsRow> = store.db.query("SELECT body FROM binding_physics ORDER BY binding_key") {
        JSON.decodeFromString(BindingPhysicsRow.serializer(), it.string("body"))
    }

    /**
     * The snapshot of the attempt of [ids], frozen by the first call: the rows of the [profiles]' routes as of now. Every
     * later call for the attempt — a reopen included — returns that snapshot unchanged, whatever [profiles] it names.
     */
    public fun freeze(ids: Identities, profiles: Collection<Profile>): BindingSnapshot = store.db.tx { tx ->
        load(tx, ids.work, ids.attempt) ?: run {
            val routes = profiles.map { BindingKey.of(it).route }.distinct().sortedBy { it.canonical }
            val all = tx.query("SELECT updated_seq, body FROM binding_physics ORDER BY binding_key") {
                it.long("updated_seq") to JSON.decodeFromString(BindingPhysicsRow.serializer(), it.string("body"))
            }
            val snapshot = BindingSnapshot(ids.work, ids.attempt, all.maxOfOrNull { it.first } ?: 0L, routes,
                all.map { it.second }.filter { row -> routes.any(row.key::sameRoute) })
            tx.execute(
                "INSERT INTO binding_snapshots (work_id, attempt_id, candidate_id, context_id, as_of_seq, schema_version, created_at, body) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)",
                ids.work, ids.attempt, snapshot.asOfSeq, Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(BindingSnapshot.serializer(), snapshot),
            )
            snapshot
        }
    }

    /** The snapshot frozen for [attempt], or `null` before its first freeze. */
    public fun frozen(work: WorkId, attempt: AttemptId): BindingSnapshot? = store.db.tx { load(it, work, attempt) }

    private fun load(tx: io.astrolabe.store.Tx, work: WorkId, attempt: AttemptId): BindingSnapshot? =
        tx.query("SELECT body FROM binding_snapshots WHERE work_id = ? AND attempt_id = ?", work, attempt) {
            JSON.decodeFromString(BindingSnapshot.serializer(), it.string("body"))
        }.firstOrNull()

    /**
     * Observes every `cell.model_responded` on [events] from now on: the call's profile is the one its `usage` row names
     * (accounting records the call before the event), resolved by [profiles]; a call without a usage row never reached
     * the provider and an unresolved profile is not a model binding, so neither is observed.
     */
    public fun subscribe(events: Events, profiles: (String) -> Profile?): Subscription {
        val accounting = Accounting(store, clock)
        // The bus replays its recent records to a new subscriber; they predate this subscription and are not observed.
        val from = events.lastSeq
        return events.subscribe(EventSink { record ->
            val responded = record.event as? AgentEvent.Cell.ModelResponded
            if (record.seq > from && responded != null) {
                accounting.profileId(responded.invocationId)?.let(profiles)?.let { observe(BindingObservation.of(it, responded)) }
            }
        })
    }

    /** As [subscribe], resolving profiles by id in [profiles]. */
    public fun subscribe(events: Events, profiles: Map<String, Profile>): Subscription = subscribe(events) { profiles[it] }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
