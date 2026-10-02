package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.RecallPointer
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

/** An eviction batch the journal logged (Studio): its turn, trigger, and how many results it stubbed and lost. */
@Serializable
public data class LoggedBatch(val turn: Int, val trigger: String, val stubbed: Int, val losses: Int)

/** An eviction batch a replay ran: its turn and trigger, the results it stubbed, those with no captured copy (losses), and the tokens it freed. */
@Serializable
public data class ReplayedBatch(val turn: Int, val trigger: String, val stubbed: Int, val losses: Int, val freedTokens: Long)

/**
 * One eviction schedule replayed on the run's requests: its conditional cost, its batches, and the prefix tokens its
 * rewrites — batches, and the immediate stubs and unexplained shrinks every schedule replays alike — made the next
 * request re-pay.
 */
@Serializable
public data class ResidencyScenario(
    val label: String,
    val k: Int,
    val cost: SerializableBigDecimal?,
    val batches: List<ReplayedBatch>,
    val repaidTokens: Long,
    val inputTokens: Long,
)

/**
 * The `Residency` what-if (F §6.1): the run's eviction decisions replayed by the real [Residency] under other cadences,
 * priced on the run's own requests. A scenario's cost is a **conditional estimate** — on this log, with the model's
 * trajectory held fixed (same turns, outputs and results) — not a bound and not another trajectory: re-reads a
 * schedule would add or save are not in it. [calibrationError] is the replay of the observed cadence minus
 * [observedCost], both priced alike, and is reported apart; [loggedBatches] (Studio) are the journal's batches, to
 * hold against the observed cadence's replayed stub and loss counts.
 *
 * Reconstruction, per cell lineage (request `j` after turn `t`): growth `G_t = (I_j − A_j) − (I_t − A_t)`; the message
 * takes the visible output `M_t = o_t − ρ_t` (reasoning is not replayed on these routes) and the turn's results the
 * rest, `R_t = G_t − M_t + F_t`, where `F_t` is what the observed cadence's replayed batch and the turn's immediate stub
 * freed. Each result is its own resident: recoverable (a recall pointer) when it has an alias and is not a command
 * still running, its class from its tool and op, and an even share of `R_t` — a result without an alias (a refusal,
 * an error, a state line) at most [SMALL_RESULT_TOKENS]. An immediate stub replays the Workset's drop on the live
 * recoverable results that read a dropped path, on the turns the log shows one. A scenario serves from cache at most the
 * prefix before the first item it rewrote; otherwise the old prefix shifted by the provider's own deviation on that step
 * (`c − b`). A full miss after a mask gate and the observed provider and upstream misses stay as observed.
 */
@Serializable
public data class ResidencyWhatIf(
    val observedK: Int,
    val observedCost: SerializableBigDecimal?,
    val calibrationError: SerializableBigDecimal?,
    val loggedBatches: List<LoggedBatch>?,
    val scenarios: List<ResidencyScenario>,
    val unmeasured: String?,
)

public object ResidencyReplay {
    /** A cadence no run reaches: `turn % k` never fires, so only the `R_max` bound evicts. */
    public const val OFF: Int = 1_000_000

    /** The tokens a result without an alias carries at most: a refusal, an error or a state line. */
    public const val SMALL_RESULT_TOKENS: Long = 40

    @JvmStatic
    @JvmOverloads
    public fun of(trace: RunTrace, losses: Losses, book: PriceBook, format: JournalFormat, defaults: Defaults = Defaults(), cadences: List<Int> = listOf(defaults.k, 17, OFF)): ResidencyWhatIf {
        val logged = if (format == JournalFormat.Studio) {
            trace.activity.mapNotNull { a -> a.eviction?.let { LoggedBatch(a.turn, it.trigger, it.stubbed, it.losses) } }.sortedBy { it.turn }
        } else null
        fun none(why: String) = ResidencyWhatIf(defaults.k, null, null, logged, emptyList(), why)
        val lineages = losses.steps().groupBy { it.first.cell }.values.flatMap(::segments)
        if (lineages.isEmpty()) return none("no lineage with two consecutive calls of known usage")
        val prices = trace.calls.associate { it.index to book.prices(it)?.perMillion }
        val calls = lineages.flatMap { it.calls() }
        // A scenario moves tokens between uncached and cached input, so both need a price, as does every class with tokens.
        if (calls.any { c -> val p = prices[c.index]; p == null || NEEDED.any { p[it] == null } || PriceClass.entries.any { (tokensOf(c.usage!!, it) ?: 0) > 0 && p[it] == null } }) {
            return none("unpriced calls: a class they use has no fitted or catalog price")
        }
        val breaks = losses.cache().breaks.associateBy { it.call }
        val observedSteps = losses.observedSteps()
        lineages.forEach { it.derive(defaults, breaks) }
        val observed = calls.fold(BigDecimal.ZERO) { s, c -> c.usage!!.let { u -> s + cost(c, u.uncachedInput!!, u.cacheRead!!, prices) } }
        val runs = cadences.map { k -> k to lineages.map { it.replay(Residency(k, defaults.rMaxTokens, ESTIMATOR), breaks, observedSteps, prices) } }
        val error = runs.first { it.first == defaults.k }.second.fold(BigDecimal.ZERO) { s, r -> s + r.cost } - observed
        return ResidencyWhatIf(defaults.k, observed.stripTrailingZeros(), error.stripTrailingZeros(), logged, runs.map { (k, replays) ->
            ResidencyScenario(
                if (k == OFF) "off" else "k=$k", k, replays.fold(BigDecimal.ZERO) { s, r -> s + r.cost }.stripTrailingZeros(),
                replays.flatMap { it.batches }.sortedBy { it.turn }, replays.sumOf { it.repaid }, replays.sumOf { it.input },
            )
        }, null)
    }

    private val ESTIMATOR = HeuristicEstimator()
    private val NEEDED = listOf(PriceClass.UncachedInput, PriceClass.CacheRead)

    /** Misses no eviction schedule causes or prevents: they stay as observed in every scenario. */
    private val KEPT = setOf(BreakCause.Provider, BreakCause.Upstream)

    private fun complete(c: ModelCall): Boolean = c.usage?.let { it.input != null && it.output != null } == true

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

    /** One call's cost at [prices] with the given split of its input; every class it uses is priced (checked in [of]). */
    private fun cost(call: ModelCall, uncached: Long, cached: Long, prices: Map<Int, Map<PriceClass, BigDecimal?>?>): BigDecimal {
        val usage = call.usage!!
        val p = prices.getValue(call.index)!!
        fun m(tokens: Long, c: PriceClass): BigDecimal = if (tokens == 0L) BigDecimal.ZERO else AuditMath.money(tokens, p[c])!!
        return m(uncached, PriceClass.UncachedInput) + m(cached, PriceClass.CacheRead) + m(usage.cacheWrite!!, PriceClass.CacheWrite) + m(usage.output!!, PriceClass.Output)
    }

    /** A result as the replay keeps it: recoverable or not, its class, the paths it read, and whether it is a small aliasless line. */
    private class Spec(val pointer: Boolean, val resultClass: ResultClass, val paths: List<String>, val small: Boolean)

    private class Step(val previous: ModelCall, val call: ModelCall, between: List<TurnActivity>) {
        val turn: Int = previous.turn
        val dropped: Set<String> = between.flatMap { it.worksetDropped }.toSet()
        val specs: List<Spec> = run {
            val queues = between.flatMap { it.ops }.groupBy { it.substringBefore('.') }.mapValues { ArrayDeque(it.value) }
            between.flatMap { it.outcomes }.map { o ->
                val op = queues[o.tool]?.removeFirstOrNull() ?: o.tool
                Spec(o.alias != null && o.status != "running", ResultClass.of(op), o.paths, o.alias == null)
            }
        }
        var message = 0L
        var results = 0L
        var unexplained = 0L
        var staleStub = false

        /** [results] split over [specs]: aliasless lines take at most [SMALL_RESULT_TOKENS] each, the rest is shared evenly. */
        fun sizes(): List<Long> {
            if (specs.isEmpty()) return emptyList()
            val small = specs.count { it.small }
            val large = specs.size - small
            val each = if (large == 0) results / specs.size else minOf(SMALL_RESULT_TOKENS, results / specs.size)
            val share = if (large == 0) 0 else (results - each * small) / large
            val last = specs.indexOfLast { large == 0 || !it.small }
            val sizes = specs.map { if (it.small || large == 0) each else share }.toMutableList()
            sizes[last] += results - sizes.sum()
            return sizes
        }
    }

    private class Replay(val cost: BigDecimal, val batches: List<ReplayedBatch>, val repaid: Long, val input: Long)

    private class Lineage(val steps: List<Step>) {
        val first: ModelCall get() = steps.first().previous
        private val paths = HashMap<String, List<String>>()

        fun calls(): List<ModelCall> = listOf(first) + steps.map { it.call }

        private fun content(c: ModelCall): Long = c.usage!!.input!! - (c.anchorTokens ?: 0)

        /** Phase A: the per-turn results `R_t`, replaying the observed cadence and stubs to learn what each freed. */
        fun derive(defaults: Defaults, breaks: Map<Int, CacheBreak>) {
            val residency = Residency(defaults.k, defaults.rMaxTokens, ESTIMATOR)
            var residents: List<Resident> = emptyList()
            for (s in steps) {
                val usage = s.previous.usage!!
                val visible = (usage.output!! - (usage.reasoning ?: 0)).coerceAtLeast(0)
                s.message = visible
                residents = residents + residentsOf(s)
                val before = total(residents)
                s.staleStub = breaks[s.call.index]?.cause == BreakCause.ImmediateStub
                if (s.staleStub) residents = stale(residency, residents, s)?.residents ?: residents.also { s.staleStub = false }
                residency.due(residents, s.turn)?.let { residents = residency.batch(residents, s.turn, it).residents }
                // What the turn's items add once the stub and the batch freed their share: the message takes the visible
                // output, the results the rest; a turn that shrank further is freed from the oldest live results.
                val available = content(s.call) - content(s.previous) + before - total(residents)
                s.message = visible.coerceIn(0, available.coerceAtLeast(0))
                s.results = (available - s.message).coerceAtLeast(0)
                s.unexplained = (-available).coerceAtLeast(0)
                val sizes = s.sizes()
                residents = shrink(residents.map { r -> if (r.turn != s.turn) r else r.copy(tokens = if (r.isResult) sizes[index(r)] else tokensOf(s)) }, s.unexplained).first
            }
        }

        /** Phase B: one cadence on the derived turns; each request priced with the prefix that cadence leaves cached. */
        fun replay(residency: Residency, breaks: Map<Int, CacheBreak>, observedSteps: Map<Int, CacheStep>, prices: Map<Int, Map<PriceClass, BigDecimal?>?>): Replay {
            val base = content(first)
            val firstUsage = first.usage!!
            var cost = cost(first, firstUsage.uncachedInput!!, firstUsage.cacheRead!!, prices)
            var input = firstUsage.input!!
            var residents: List<Resident> = emptyList()
            val batches = ArrayList<ReplayedBatch>()
            var repaid = 0L
            for (s in steps) {
                val sizes = s.sizes()
                residents = residents + residentsOf(s).map { r -> r.copy(tokens = if (r.isResult) sizes[index(r)] else tokensOf(s)) }
                var rewrittenAt = Int.MAX_VALUE
                if (s.staleStub) stale(residency, residents, s)?.let { stub ->
                    rewrittenAt = minOf(rewrittenAt, changedAt(residents, stub.residents))
                    residents = stub.residents
                }
                shrink(residents, s.unexplained).let { (shrunk, at) -> if (at >= 0) rewrittenAt = minOf(rewrittenAt, at); residents = shrunk }
                residency.due(residents, s.turn)?.let { due ->
                    val batch = residency.batch(residents, s.turn, due)
                    if (batch.rewritten) {
                        rewrittenAt = minOf(rewrittenAt, changedAt(residents, batch.residents))
                        batches += ReplayedBatch(s.turn, due.name.lowercase(), batch.stubbed.size, batch.losses.size, total(residents) - total(batch.residents))
                    }
                    residents = batch.residents
                }
                val usage = s.call.usage!!
                val content = base + total(residents)
                val write = usage.cacheWrite!!
                val all = content + (s.call.anchorTokens ?: 0)
                val old = (content - s.message - s.results).coerceAtLeast(0)
                val observed = breaks[s.call.index]
                val estimate = when {
                    observed?.cause == BreakCause.Mask && observed.step.fullMiss -> 0L
                    observed?.cause in KEPT -> minOf(usage.cacheRead!!, old)
                    observed?.cause == BreakCause.ImmediateStub && !s.staleStub -> minOf(usage.cacheRead!!, old)
                    observed != null -> old
                    // The provider's own deviation from the cacheable prefix (block rounding, anchor estimate) on this step.
                    else -> observedSteps[s.call.index]?.let { o -> old + o.cached - o.cacheable } ?: old
                }
                val kept = if (rewrittenAt == Int.MAX_VALUE) null else base + residents.take(rewrittenAt).sumOf { it.tokens }
                val cached = minOf(estimate, kept ?: Long.MAX_VALUE).coerceIn(0, all - write)
                if (kept != null) repaid += (minOf(estimate, old) - cached).coerceAtLeast(0)
                cost += cost(s.call, all - cached - write, cached, prices)
                input += all
            }
            return Replay(cost, batches, repaid, input)
        }

        private fun residentsOf(s: Step): List<Resident> {
            val message = Resident.message(Message.text(Role.Assistant, "turn ${s.turn}"), s.turn, tokensOf(s))
            return listOf(message) + s.specs.mapIndexed { i, spec ->
                val label = "turn ${s.turn} #$i"
                paths[label] = spec.paths
                val pointer = if (spec.pointer) RecallPointer("#${s.turn}.$i", "obs-${s.turn}-$i", Digest.ofUtf8("${s.call.cell}/${s.turn}/$i")) else null
                Resident.result(ToolResult.text("call-${s.turn}-$i", label), s.turn, 0, spec.resultClass, pointer, label)
            }
        }

        /** The immediate stub of the Workset's drop (§5.3): the live recoverable results of earlier turns that read a dropped path; `null` when none is live. */
        private fun stale(residency: Residency, residents: List<Resident>, s: Step) = residents
            .filter { r -> r.isLiveResult && r.turn < s.turn && r.alias != null && paths[r.label].orEmpty().any { it in s.dropped } }
            .mapNotNull { it.alias }.toSet().takeIf { it.isNotEmpty() }?.let { residency.stubNow(residents, it, s.turn) }

        private fun index(r: Resident): Int = r.label.substringAfterLast('#').toInt()

        /** A turn's message carries its growth when the turn had no results (gauge and state lines). */
        private fun tokensOf(s: Step): Long = if (s.specs.isEmpty()) s.message + s.results else s.message

        private fun total(residents: List<Resident>): Long = residents.sumOf { it.tokens }

        private fun changedAt(before: List<Resident>, after: List<Resident>): Int =
            before.indices.firstOrNull { before[it].residence != after[it].residence } ?: Int.MAX_VALUE

        /** A shrink no replayed batch explains, taken from the oldest live results; the index of the first one it rewrote, or -1. */
        private fun shrink(residents: List<Resident>, tokens: Long): Pair<List<Resident>, Int> {
            if (tokens <= 0) return residents to -1
            var left = tokens
            var first = -1
            val out = residents.mapIndexed { i, r ->
                if (left <= 0 || !r.isLiveResult || r.tokens == 0L) r else {
                    val taken = minOf(left, r.tokens)
                    left -= taken
                    if (first < 0) first = i
                    r.copy(tokens = r.tokens - taken)
                }
            }
            return out to first
        }
    }
}
