package io.astrolabe.eval.audit

import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** Why a cache step broke, in order of precedence. */
public enum class BreakCause {
    /** W4: the whole prefix missed after a gate that changes the turn mask (`reserve`), whose line sat in `[S]` before A3. */
    Mask,

    /** W3: an eviction batch rewrote `[T]` at the end of the previous turn (logged by the journal; on the bus, the `k` cadence). */
    Eviction,

    /** W7: the Workset dropped files in the previous turn, and their stale bodies are stubbed at once (§5.3). */
    ImmediateStub,

    /** No harness rewrite before the step: the provider did not serve an unchanged prefix (F §2.3). */
    Provider,
}

/** A broken cache step: the call that paid it, its cause, and what re-paying the prefix cost, `s · (p_u − p_c)`. */
@Serializable
public data class CacheBreak(val call: Int, val cell: String, val turn: Int, val cause: BreakCause, val step: CacheStep, val cost: SerializableBigDecimal?)

/**
 * A run's cache share three ways (§10.4): [hitShare] `Σ cache_read / Σ input` over every call with known input — the
 * "share of hits" of F §6.1 (86,7 % on `live1/W-letk4`); [qHat] `Σ c_i / Σ b_i` over [eligibleSteps], the steps no
 * harness rewrite preceded (no eviction, Workset drop, mask gate or rebuild); [reliability] the share of eligible steps
 * served any cache at all (F §2.3's "reliability at an unchanged prefix").
 */
@Serializable
public data class CacheReport(
    val hitShare: Double?,
    val cacheReadTokens: Long,
    val inputTokens: Long,
    val qHat: Double?,
    val eligibleSteps: Int,
    val reliability: Double?,
    val breaks: List<CacheBreak>,
)

/** The losses of F §2.2, each a share of the run's money. They overlap; they are not a partition. */
public enum class Waste(public val code: String) {
    /** Calls after the first working result: the first green check, after the workspace first changed, that stays green. */
    TailAfterResult("W1"),

    /** Calls that polled a background command (`run(op=poll)`). */
    BackgroundPolls("W2"),

    /** Prefix re-paid after cadence or bound eviction batches (gross; the Residency what-if nets the savings). */
    CadenceEviction("W3"),

    /** Prefix re-paid after a mask change rewrote `[S]`. */
    PrefixMiss("W4"),

    /** File bodies written through `edit`: generated as output, then carried as cached input by every later request of the cell. */
    WrittenBodies("W5"),

    /** The anchor `[A]`, uncached on every request. */
    AnchorTail("W6"),

    /** Prefix re-paid after immediate stubs of stale reads. */
    ImmediateStubs("W7"),
}

/** One loss: its money, share of the run's basis total, calls and tokens involved; or why the log cannot measure it. */
@Serializable
public data class WasteShare(
    val waste: Waste,
    val money: SerializableBigDecimal?,
    val share: Double?,
    val calls: Int?,
    val tokens: Long?,
    val detail: String?,
    val unmeasured: String?,
)

/** The cache steps, breaks and losses of one run; pure functions of its trace and prices. */
public class Losses(private val trace: RunTrace, private val format: JournalFormat, private val book: PriceBook, private val k: Int) {
    private val prices = trace.calls.associate { it.index to book.prices(it) }

    /** A call's cost on the run's basis: billed when the basis is billed, else priced; `null` when unknown. */
    private fun cost(call: ModelCall, basis: MoneyBasis): BigDecimal? = when (basis) {
        MoneyBasis.Billed -> call.billed
        MoneyBasis.Estimate -> call.usage?.let { pricedCost(it, prices[call.index]) }
        MoneyBasis.None -> null
    }

    public fun cache(): CacheReport {
        val known = trace.calls.mapNotNull { c -> c.usage?.let { u -> u.input?.let { input -> u.cacheRead?.let { it to input } } } }
        val breaks = ArrayList<CacheBreak>()
        val eligible = ArrayList<CacheStep>()
        for ((previous, call, between) in steps()) {
            val step = stepOf(previous, call) ?: continue
            val mask = between.any { a -> a.gates.any { it in MASK_GATES } }
            val eviction = between.any { a -> a.eviction != null || (format == JournalFormat.Bus && a.turn >= 2 * k && a.turn % k == 0) }
            val immediate = between.any { it.worksetDropped.isNotEmpty() }
            if (!mask && !eviction && !immediate) eligible += step
            if (!step.broken) continue
            val cause = when {
                mask && step.fullMiss -> BreakCause.Mask
                eviction -> BreakCause.Eviction
                immediate -> BreakCause.ImmediateStub
                else -> BreakCause.Provider
            }
            val cost = prices[call.index]?.let { AuditMath.missCost(step.shortfall, it.perMillion) }
            breaks += CacheBreak(call.index, call.cell, call.turn, cause, step, cost?.stripTrailingZeros())
        }
        return CacheReport(
            AuditMath.hitShare(known.sumOf { it.first }, known.sumOf { it.second }), known.sumOf { it.first }, known.sumOf { it.second },
            AuditMath.qHat(eligible), eligible.size,
            if (eligible.isEmpty()) null else eligible.count { it.cached > 0 }.toDouble() / eligible.size,
            breaks,
        )
    }

    /** Consecutive calls of one cell with the activity of the turns between their requests; a rebuild starts a new lineage. */
    internal fun steps(): List<Triple<ModelCall, ModelCall, List<TurnActivity>>> = trace.calls.groupBy { it.cell }.values.flatMap { cell ->
        cell.zipWithNext().mapNotNull { (previous, call) ->
            val between = (previous.turn until call.turn).mapNotNull { trace.at(call.cell, it) }
            if (between.any { it.rebuilt } || call.turn <= previous.turn) null else Triple(previous, call, between)
        }
    }

    /** The observed step into every call that has one, by call index. */
    internal fun observedSteps(): Map<Int, CacheStep> = steps().mapNotNull { (p, c, _) -> stepOf(p, c)?.let { c.index to it } }.toMap()

    private fun stepOf(previous: ModelCall, call: ModelCall): CacheStep? {
        val before = previous.usage?.input ?: return null
        val usage = call.usage ?: return null
        val input = usage.input ?: return null
        val cached = usage.cacheRead ?: return null
        return AuditMath.step(before, previous.anchorTokens ?: 0, input, call.anchorTokens ?: 0, cached)
    }

    public fun wastes(anatomy: Anatomy, cache: CacheReport): List<WasteShare> {
        val total = anatomy.total
        fun share(waste: Waste, money: BigDecimal?, calls: Int?, tokens: Long?, detail: String?, unmeasured: String? = null) =
            WasteShare(waste, money?.stripTrailingZeros(), AuditMath.share(money, total), calls, tokens, detail, unmeasured)
        fun none(waste: Waste, why: String) = WasteShare(waste, null, null, null, null, null, why)
        fun sum(calls: List<ModelCall>): BigDecimal? = calls.fold(BigDecimal.ZERO as BigDecimal?) { s, c -> cost(c, anatomy.basis)?.let { s?.add(it) } }
        fun spent(waste: Waste, calls: List<ModelCall>, detail: String?): WasteShare {
            val unknown = calls.count { cost(it, anatomy.basis) == null }
            return if (unknown == 0) share(waste, sum(calls), calls.size, null, detail)
            else WasteShare(waste, null, null, calls.size, null, detail, "$unknown of its ${calls.size} calls have no cost: no usage or charge reported")
        }
        fun breaks(waste: Waste, cause: BreakCause): WasteShare {
            val hit = cache.breaks.filter { it.cause == cause }
            val money = hit.fold(BigDecimal.ZERO as BigDecimal?) { s, b -> b.cost?.let { s?.add(it) } }
            val turns = hit.joinToString(", ") { "${it.turn}: ${it.step.shortfall}" }.ifEmpty { null }
            return share(waste, money, hit.size, hit.sumOf { it.step.shortfall }, turns?.let { "re-paid tokens by turn: $it" })
        }

        val result = ArrayList<WasteShare>()
        val working = firstWorkingResult()
        result += if (working == null) {
            none(Waste.TailAfterResult, "no check green after the workspace changed and green to the end (verify verdict, test verdict or repeated check command)")
        } else {
            spent(Waste.TailAfterResult, trace.calls.filter { it.index > working.first }, "after call ${working.first} (${working.second})")
        }
        val polls = trace.calls.filter { c -> trace.at(c.cell, c.turn)?.ops?.contains("run.poll") == true }
        result += spent(Waste.BackgroundPolls, polls, polls.joinToString(", ") { "${it.turn}" }.ifEmpty { null }?.let { "turns $it" })
        result += breaks(Waste.CadenceEviction, BreakCause.Eviction).let {
            if (format == JournalFormat.Bus) it.copy(detail = listOfNotNull(it.detail, "batches inferred from the k=$k cadence").joinToString("; ")) else it
        }
        result += breaks(Waste.PrefixMiss, BreakCause.Mask)
        result += bodies(anatomy)
        result += anchors(anatomy)
        result += breaks(Waste.ImmediateStubs, BreakCause.ImmediateStub)
        return result
    }

    /**
     * W5: a call's edit arguments are `a = (o − ρ) · args / visible` of its tokens. The loss is what they cost beyond
     * writing each file once: every later request of the cell carries them as cached input (`a · p_c` each), and a
     * file created again regenerates its whole body (`p_out` on every `create` of a path but the last).
     */
    private fun bodies(anatomy: Anatomy): WasteShare {
        if (format == JournalFormat.Bus || trace.activity.none { it.visibleChars != null }) {
            return WasteShare(Waste.WrittenBodies, null, null, null, null, null, "the log keeps no tool arguments (Studio's journal.call does)")
        }
        var carry: BigDecimal? = BigDecimal.ZERO
        var bodies = 0L
        var calls = 0
        var reasoningUnknown = false
        val creations = LinkedHashMap<String, MutableList<Pair<Long, BigDecimal?>>>()
        val byCell = trace.calls.groupBy { it.cell }
        for (call in trace.calls) {
            val a = trace.at(call.cell, call.turn) ?: continue
            val args = a.editArgsChars ?: continue
            val visible = a.visibleChars ?: continue
            if (args == 0L || visible == 0L) continue
            val usage = call.usage
            val output = usage?.output
            if (usage == null || output == null) { carry = null; continue }
            if (usage.reasoning == null) reasoningUnknown = true
            val tokens = ((output - (usage.reasoning ?: 0)).coerceAtLeast(0) * args.toDouble() / visible).toLong()
            val later = byCell.getValue(call.cell).count { it.index > call.index }.toLong()
            val p = prices[call.index]?.perMillion
            carry = AuditMath.money(tokens * later, p?.get(PriceClass.CacheRead))?.let { c -> carry?.add(c) }
            for ((path, chars) in a.created) {
                val body = (tokens * chars.toDouble() / args).toLong()
                creations.getOrPut(path) { ArrayList() } += body to AuditMath.money(body, p?.get(PriceClass.Output))
            }
            bodies += tokens
            calls++
        }
        val again = creations.filterValues { it.size > 1 }
        val regenerated = again.values.flatMap { it.dropLast(1) }
        val regeneration = regenerated.fold(BigDecimal.ZERO as BigDecimal?) { s, g -> g.second?.let { s?.add(it) } }
        val money = carry?.let { c -> regeneration?.let(c::add) }
        val detail = listOfNotNull(
            "edit arguments $bodies tok in $calls calls; carried ${text(carry)}, regenerated ${regenerated.sumOf { it.first }} tok ${text(regeneration)}",
            again.entries.sortedByDescending { it.value.size }.take(5).joinToString(", ") { "${it.key} ×${it.value.size}" }.ifEmpty { null }?.let { "created again: $it" },
            if (reasoningUnknown) "reasoning unknown on some calls: their output counts as visible" else null,
        ).joinToString("; ")
        return WasteShare(Waste.WrittenBodies, money?.stripTrailingZeros(), AuditMath.share(money, anatomy.total), calls, bodies, detail, null)
    }

    private fun text(value: BigDecimal?): String = value?.round(java.math.MathContext(5))?.stripTrailingZeros()?.toPlainString() ?: "—"

    /** W6: `Σ A_i · p_u`, the anchor at the uncached price on every request; anchors are the harness estimator's tokens. */
    private fun anchors(anatomy: Anatomy): WasteShare {
        val used = trace.calls.filter { it.usage != null }
        val known = used.filter { it.anchorTokens != null }
        if (known.isEmpty()) return WasteShare(Waste.AnchorTail, null, null, null, null, null, "no anchorTokens in cell.model_requested")
        val money = known.fold(BigDecimal.ZERO as BigDecimal?) { s, c ->
            AuditMath.money(c.anchorTokens, prices[c.index]?.perMillion?.get(PriceClass.UncachedInput))?.let { s?.add(it) }
        }
        val detail = "harness-estimated tokens" + if (known.size < used.size) "; anchor known on ${known.size} of ${used.size} calls" else ""
        return WasteShare(Waste.AnchorTail, money?.stripTrailingZeros(), AuditMath.share(money, anatomy.total), known.size, known.sumOf { it.anchorTokens!! }, detail, null)
    }

    /**
     * The first working result (W1): the earliest call whose check came out green and stayed green — no later red of the
     * same check ([RunTrace.verdict]) — once the run had changed the workspace; a check green on the untouched base
     * (existing tests) is no result of the work. `null` when no check qualifies.
     */
    internal fun firstWorkingResult(): Pair<Int, String>? {
        val callAt = trace.calls.associateBy { it.cell to it.turn }
        val outcomes = trace.activity.flatMap { a -> callAt[a.cell to a.turn]?.index?.let { call -> a.outcomes.map { call to it } }.orEmpty() }
        val changed = outcomes.filter { it.second.changed }.minOfOrNull { it.first } ?: return null
        val verdicts = outcomes.mapNotNull { (call, o) -> trace.verdict(o)?.let { (check, green) -> Triple(call, check, green) } }
        return verdicts.groupBy { it.second }.mapNotNull { (check, seen) ->
            val lastRed = seen.filter { !it.third }.maxOfOrNull { it.first } ?: -1
            seen.filter { it.third && it.first > lastRed && it.first >= changed }.minOfOrNull { it.first }?.let { it to check }
        }.minByOrNull { it.first }?.let { (call, check) -> call to "$check green at turn ${trace.calls[call].turn}" }
    }

    public companion object {
        /** Gates that change the turn mask; before A3 its line sat in `[S]`, so the next request missed the whole prefix. */
        @JvmField public val MASK_GATES: Set<String> = setOf("reserve")
    }
}
