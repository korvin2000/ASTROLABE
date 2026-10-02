package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.RecallPointer
import io.astrolabe.cell.Residence
import io.astrolabe.cell.Resident
import io.astrolabe.cell.Residency
import io.astrolabe.cell.ResultClass
import io.astrolabe.id.Digest
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.provider.SerializableBigDecimal
import io.astrolabe.provider.ToolResult
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** One eviction schedule replayed on the run's requests: its cost, the turns its batches rewrote `[T]`, and what they made the next request re-pay. */
@Serializable
public data class ResidencyScenario(
    val label: String,
    val k: Int,
    val cost: SerializableBigDecimal?,
    val low: SerializableBigDecimal?,
    val high: SerializableBigDecimal?,
    val batches: List<Int>,
    val repaidTokens: Long,
    val inputTokens: Long,
)

/**
 * The `Residency` what-if (F §6.1): the run's eviction decisions replayed by the real [Residency] under other cadences,
 * priced on the run's own requests. The model's trajectory is held fixed — same turns, outputs and results — so a
 * scenario is a bound on that schedule's cost, not another trajectory; re-reads a schedule would add or save are not in
 * it. [observedCost] prices the observed requests the same way; [modelError] is the replay of the observed cadence
 * minus [observedCost], and every scenario's `low`/`high` widen its cost by it. [loggedBatches] are the journal's batches.
 *
 * Reconstruction (per cell lineage, request `j` after turn `t`): growth `G_t = (I_j − A_j) − (I_t − A_t)`; the model's
 * message `M_t = o_t − ρ_t` (reasoning is not replayed on these routes); the turn's results
 * `R_t = G_t − M_t + F_t + F'_t`, where `F_t` is what the observed cadence's batch freed (replayed) and `F'_t` an
 * immediate stub (a Workset drop the next request paid for), whose results are taken as the median result size of
 * clean turns. A scenario then serves from cache the prefix before the first item its batch rewrote, else the old
 * prefix shifted by the provider's own deviation on that step (`c − b`); a full miss after a mask gate and the observed
 * provider and immediate-stub misses stay as observed.
 */
@Serializable
public data class ResidencyWhatIf(
    val observedK: Int,
    val observedCost: SerializableBigDecimal?,
    val modelError: SerializableBigDecimal?,
    val loggedBatches: List<Int>?,
    val scenarios: List<ResidencyScenario>,
    val unmeasured: String?,
)

public object ResidencyReplay {
    /** A cadence no run reaches: `turn % k` never fires, so only the `R_max` bound evicts. */
    public const val OFF: Int = 1_000_000

    @JvmStatic
    @JvmOverloads
    public fun of(trace: RunTrace, losses: Losses, book: PriceBook, format: JournalFormat, defaults: Defaults = Defaults(), cadences: List<Int> = listOf(defaults.k, 17, OFF)): ResidencyWhatIf {
        val lineages = losses.steps().groupBy { it.first.cell }.values.flatMap(::segments)
        val logged = if (format == JournalFormat.Studio) trace.activity.filter { it.eviction != null }.map { it.turn }.sorted() else null
        if (lineages.isEmpty()) return ResidencyWhatIf(defaults.k, null, null, logged, emptyList(), "no lineage with two consecutive calls of known usage")
        val prices = trace.calls.associate { it.index to book.prices(it) }
        if (lineages.any { l -> l.calls().any { prices[it.index] == null } }) {
            return ResidencyWhatIf(defaults.k, null, null, logged, emptyList(), "unpriced calls: no billed fit or catalog price for their route")
        }
        val breaks = losses.cache().breaks.associateBy { it.call }
        val observedSteps = losses.observedSteps()
        lineages.forEach { it.derive(defaults, breaks) }
        val observed = lineages.fold(BigDecimal.ZERO) { s, l ->
            s + l.calls().fold(BigDecimal.ZERO) { a, c -> c.usage!!.let { u -> a + cost(c, u.uncachedInput!!, u.cacheRead!!, prices) } }
        }
        val runs = cadences.map { k -> k to lineages.map { it.replay(Residency(k, defaults.rMaxTokens, ESTIMATOR), breaks, observedSteps, prices) } }
        val error = runs.first { it.first == defaults.k }.second.fold(BigDecimal.ZERO) { s, r -> s + r.cost } - observed
        return ResidencyWhatIf(defaults.k, observed.stripTrailingZeros(), error.stripTrailingZeros(), logged, runs.map { (k, replays) ->
            val cost = replays.fold(BigDecimal.ZERO) { s, r -> s + r.cost }
            ResidencyScenario(
                if (k == OFF) "off" else "k=$k", k, cost.stripTrailingZeros(), (cost - error.abs()).stripTrailingZeros(), (cost + error.abs()).stripTrailingZeros(),
                replays.flatMap { it.batches }.sorted(), replays.sumOf { it.repaid }, replays.sumOf { it.input },
            )
        }, null)
    }

    private val ESTIMATOR = HeuristicEstimator()

    private fun complete(c: ModelCall): Boolean = c.usage?.let { it.input != null && it.cacheRead != null && it.output != null } == true

    /** Maximal chains of steps whose calls all have usage: a call without usage ends a lineage, the next complete pair starts one. */
    private fun segments(steps: List<Triple<ModelCall, ModelCall, List<TurnActivity>>>): List<Lineage> {
        val out = ArrayList<Lineage>()
        var current = ArrayList<Step>()
        for ((previous, call, between) in steps) {
            val usable = complete(previous) && complete(call)
            if (current.isNotEmpty() && (!usable || current.last().call.index != previous.index)) {
                out += Lineage(current)
                current = ArrayList()
            }
            if (usable) current += Step(previous, call, between)
        }
        if (current.isNotEmpty()) out += Lineage(current)
        return out
    }

    private fun cost(call: ModelCall, uncached: Long, cached: Long, prices: Map<Int, CallPrices?>): BigDecimal {
        val usage = call.usage!!
        val p = prices.getValue(call.index)!!.perMillion
        fun m(tokens: Long, c: PriceClass) = if (tokens == 0L) BigDecimal.ZERO else AuditMath.money(tokens, p[c]) ?: BigDecimal.ZERO
        return m(uncached, PriceClass.UncachedInput) + m(cached, PriceClass.CacheRead) + m(usage.cacheWrite ?: 0, PriceClass.CacheWrite) + m(usage.output!!, PriceClass.Output)
    }

    private class Step(val previous: ModelCall, val call: ModelCall, val between: List<TurnActivity>) {
        var message = 0L
        var results = 0L
        var immediate = 0L
        var resultCount = 0
        var resultClass = ResultClass.Verdict
    }

    private class Replay(val cost: BigDecimal, val batches: List<Int>, val repaid: Long, val input: Long)

    private class Lineage(val steps: List<Step>) {
        val first: ModelCall get() = steps.first().previous

        fun calls(): List<ModelCall> = listOf(first) + steps.map { it.call }

        private fun content(c: ModelCall): Long = c.usage!!.input!! - (c.anchorTokens ?: 0)

        /** Phase A: the per-turn results `R_t`, replaying the observed cadence to learn what each of its batches freed. */
        fun derive(defaults: Defaults, breaks: Map<Int, CacheBreak>) {
            val residency = Residency(defaults.k, defaults.rMaxTokens, ESTIMATOR)
            val clean = ArrayList<Double>()
            for (s in steps) {
                val usage = s.previous.usage!!
                s.message = (usage.output!! - (usage.reasoning ?: 0)).coerceAtLeast(0)
                s.resultCount = s.between.sumOf { it.results }
                s.resultClass = classOf(s.between.flatMap { it.ops })
                val growth = content(s.call) - content(s.previous)
                if (s.resultCount > 0 && breaks[s.call.index] == null && s.between.none { it.worksetDropped.isNotEmpty() || it.eviction != null }) {
                    clean += (growth - s.message).coerceAtLeast(0).toDouble() / s.resultCount
                }
            }
            val median = clean.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
            var residents: List<Resident> = emptyList()
            for (s in steps) {
                val growth = content(s.call) - content(s.previous)
                val turn = s.previous.turn
                residents = residents + listOfNotNull(message(s, turn), result(s, turn, 0))
                val batch = residency.due(residents, turn)?.let { residency.batch(residents, turn, it) }
                val freed = if (batch == null) 0 else total(residents) - total(batch.residents)
                if (batch != null) residents = batch.residents
                // What the turn's items add once the batch freed its share; the message takes the visible output, the results the rest.
                val visible = s.message
                val available = growth + freed
                if (breaks[s.call.index]?.cause == BreakCause.ImmediateStub) {
                    s.results = maxOf((median * s.resultCount).toLong(), available - visible)
                    s.immediate = (visible + s.results - available).coerceAtLeast(0)
                } else {
                    // A turn that added less than its visible output (tokenized differently as input) keeps the observed growth;
                    // one that shrank more than the replayed batch freed is freed as an immediate stub would be.
                    s.message = visible.coerceIn(0, available.coerceAtLeast(0))
                    s.results = (available - s.message).coerceAtLeast(0)
                    s.immediate = (-available).coerceAtLeast(0)
                }
                residents = shrink(residents.map { r -> if (r.turn == turn) r.copy(tokens = tokensOf(s, r.isResult)) else r }, s.immediate)
            }
        }

        /** Phase B: one cadence on the derived turns; each request priced with the prefix that cadence leaves cached. */
        fun replay(residency: Residency, breaks: Map<Int, CacheBreak>, observedSteps: Map<Int, CacheStep>, prices: Map<Int, CallPrices?>): Replay {
            val base = content(first)
            val firstUsage = first.usage!!
            var cost = cost(first, firstUsage.uncachedInput!!, firstUsage.cacheRead!!, prices)
            var input = firstUsage.input!!
            var residents: List<Resident> = emptyList()
            val batches = ArrayList<Int>()
            var repaid = 0L
            for (s in steps) {
                val turn = s.previous.turn
                residents = shrink(residents + listOfNotNull(message(s, turn), result(s, turn, s.results)), s.immediate)
                var rewrittenAt = -1
                residency.due(residents, turn)?.let { due ->
                    val batch = residency.batch(residents, turn, due)
                    rewrittenAt = residents.indices.firstOrNull { residents[it].residence != batch.residents[it].residence } ?: -1
                    residents = batch.residents
                    if (rewrittenAt >= 0) batches += turn
                }
                val usage = s.call.usage!!
                val content = base + total(residents)
                val write = usage.cacheWrite ?: 0
                val all = content + (s.call.anchorTokens ?: 0) + write
                val old = (content - s.message - s.results).coerceAtLeast(0)
                val observed = breaks[s.call.index]
                val kept = if (rewrittenAt < 0) null else minOf(old, base + residents.take(rewrittenAt).sumOf { it.tokens })
                val cached = when {
                    observed?.cause == BreakCause.Mask && observed.step.fullMiss -> 0L
                    observed?.cause == BreakCause.Provider || observed?.cause == BreakCause.ImmediateStub -> minOf(usage.cacheRead!!, old)
                    kept != null -> kept
                    observed?.cause == BreakCause.Eviction -> old
                    // The provider's own deviation from the cacheable prefix (block rounding, anchor estimate) on this step.
                    else -> observedSteps[s.call.index]?.let { o -> (old + o.cached - o.cacheable).coerceIn(0, all - write) } ?: old
                }
                if (kept != null && cached == kept) repaid += old - kept
                cost += cost(s.call, all - cached - write, cached, prices)
                input += all
            }
            return Replay(cost, batches, repaid, input)
        }

        /** A turn's message carries its growth when the turn had no results (gauge and state lines). */
        private fun tokensOf(s: Step, result: Boolean): Long = when {
            result -> s.results
            s.resultCount == 0 -> s.message + s.results
            else -> s.message
        }

        private fun total(residents: List<Resident>): Long = residents.sumOf { it.tokens }

        private fun message(s: Step, turn: Int): Resident = Resident.message(Message.text(Role.Assistant, "turn $turn"), turn, tokensOf(s, false))

        private fun result(s: Step, turn: Int, tokens: Long): Resident? = if (s.resultCount == 0) null else Resident.result(
            ToolResult.text("call-$turn", "results of turn $turn"), turn, tokens, s.resultClass,
            RecallPointer("#$turn", "obs-$turn", Digest.ofUtf8("${s.call.cell}/$turn")), "turn $turn",
        )

        /** An immediate stub frees [tokens] from the oldest live results (§5.3: stale bodies, mostly early reads). */
        private fun shrink(residents: List<Resident>, tokens: Long): List<Resident> {
            if (tokens <= 0) return residents
            var left = tokens
            return residents.map { r ->
                if (left <= 0 || !r.isLiveResult) r else {
                    val taken = minOf(left, r.tokens)
                    left -= taken
                    r.copy(tokens = r.tokens - taken)
                }
            }
        }

        private fun classOf(ops: List<String>): ResultClass =
            ops.map { ResultClass.of(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ResultClass.Verdict
    }
}
