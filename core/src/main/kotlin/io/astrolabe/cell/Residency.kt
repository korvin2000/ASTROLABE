package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.auth.Boundary
import io.astrolabe.id.Digest
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Text
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import java.util.Locale
import io.astrolabe.provider.Role as ItemRole

/**
 * Refetchability class of a tool result (§5.7, C3 §6.3): the dominant term of `c_refetch`, what it would
 * cost to get the bytes back once the result is a stub.
 */
public enum class ResultClass {
    /**
     * A raw observation of current repository state (read, tree, outline, def, recall, catalog): `recall #n`
     * re-serves the exact bytes and a re-read serves the current ones, each at about the body's size.
     */
    Observation,

    /** A search or index view (find, refs, importers, impact, bmap, kb): re-running the query reproduces it while its scope is unchanged. */
    Search,

    /**
     * A verdict (diff, exit code, receipt, check summary, edit outcome, register op): the world state it judged
     * is gone, so nothing can refetch it — only the captured bytes remain.
     */
    Verdict,
    ;

    public companion object {
        /** The class of a `family.op` result; an unknown op is a verdict, the class that is stubbed last. */
        @JvmStatic
        public fun of(op: String): ResultClass {
            val dot = op.indexOf('.')
            val family = if (dot < 0) op else op.substring(0, dot)
            val name = if (dot < 0) "" else op.substring(dot + 1)
            return when (family) {
                "look" -> if (name in SEARCH_OPS) Search else Observation
                "kb" -> Search
                else -> Verdict
            }
        }

        private val SEARCH_OPS: Set<String> = setOf("find", "refs", "importers", "impact", "bmap")
    }
}

/**
 * The recoverable pointer a stub keeps (§5.7; J1 §5.4): the campaign alias the model recalls with
 * `look(recall, id=#n)`, and the observation id and content digest the harness resolves it through.
 */
public data class RecallPointer(val alias: String, val observationId: String, val contentRef: Digest) {
    init {
        require(alias.length > 1 && alias.startsWith("#")) { "a recall pointer needs a #n alias, got '$alias'" }
        require(observationId.isNotBlank()) { "a recall pointer needs an observation id" }
    }
}

/** Where an item of `[T]` stands: live in full, replaced by its stub, or trimmed to its first line. */
public enum class Residence { Live, Stubbed, Trimmed }

/**
 * One item of `[T]` with what residency needs to know about it and the item model does not carry:
 * the turn it entered, its measured size, and — for a result — its refetchability and the pointer to
 * its captured bytes. [tokens] is the estimator's count at entry or at the last rewrite, recorded rather
 * than recomputed so the policy stays a function of records.
 */
public data class Resident(
    val item: Item,
    val turn: Int,
    val tokens: Long,
    val pinned: Boolean = false,
    val pointer: RecallPointer? = null,
    val resultClass: ResultClass? = null,
    /** Short harness-written description for the stub line (tool, op, paths, versions); never model text. */
    val label: String = "",
    /** Set when the Workset dropped the result's coverage: the bytes are historical and `p_reuse` is nil. */
    val stale: Boolean = false,
    val residence: Residence = Residence.Live,
) {
    init {
        require(turn >= 0) { "turn must be ≥ 0" }
        require(tokens >= 0) { "tokens must be ≥ 0" }
        if (item is ToolResult) require(resultClass != null) { "a result needs a refetchability class" }
    }

    val isResult: Boolean get() = item is ToolResult

    val isLiveResult: Boolean get() = isResult && residence == Residence.Live

    val alias: String? get() = pointer?.alias

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun result(
            item: ToolResult,
            turn: Int,
            tokens: Long,
            resultClass: ResultClass,
            pointer: RecallPointer? = null,
            label: String = "",
            pinned: Boolean = false,
        ): Resident = Resident(item, turn, tokens, pinned, pointer, resultClass, label)

        /** A user message is pinned by default (§5.7); a model message is not. */
        @JvmStatic
        @JvmOverloads
        public fun message(item: Message, turn: Int, tokens: Long, pinned: Boolean = item.role == ItemRole.User): Resident =
            Resident(item, turn, tokens, pinned)

        @JvmStatic
        public fun call(item: ToolCall, turn: Int, tokens: Long): Resident = Resident(item, turn, tokens)
    }
}

/** Why a batch ran: the `k`-turn cadence, the `R_max` bound, or a large stale body that could not wait. */
public enum class EvictionTrigger { Age, Budget, Immediate }

/** A stubbed result: the ~20-token line that replaced [tokens] tokens of body, and the pointer it keeps. */
public data class Stub(
    val alias: String?,
    val label: String,
    val tokens: Long,
    val pointer: RecallPointer?,
    val turn: Int,
) {
    val recoverable: Boolean get() = pointer != null

    /** Byte-stable: no clock or counter, so the stub does not perturb the cached prefix again later. */
    public fun render(): String {
        val sb = StringBuilder(Boundary.RESULT_OPEN).append("stub")
        alias?.let { sb.append(' ').append(it) }
        if (label.isNotBlank()) sb.append(' ').append(Boundary.escape(label))
        sb.append(" · ").append(size(tokens)).append(" tok")
        sb.append(" · ").append(if (pointer != null) "recall $alias" else "not recoverable: no captured copy")
        return sb.append(Boundary.RESULT_CLOSE).toString()
    }

    private fun size(n: Long): String = if (n >= 1_000) String.format(Locale.ROOT, "%.1fK", n / 1_000.0) else n.toString()
}

/** Invariant 9: a reduction that removed the only copy of a body is recorded, never silent. */
public data class Loss(val turn: Int, val label: String, val tokens: Long, val reason: String)

/**
 * One eviction batch, logged with its inputs (k, R_max, the live total before) and its outputs. Everything
 * it did was a replacement in place: [residents] has the same length and order as the input, so every
 * tool-call/result unit is still complete (FX-21).
 */
public data class Eviction(
    val turn: Int,
    val trigger: EvictionTrigger,
    val k: Int,
    val rMaxTokens: Int,
    val liveResultTokensBefore: Long,
    val liveResultTokensAfter: Long,
    val residents: List<Resident>,
    val stubbed: List<Stub>,
    val trimmed: Int,
    val losses: List<Loss>,
) {
    /** True when an item before the tail changed, which is what costs the prefix-cache miss (F26). */
    val rewritten: Boolean get() = stubbed.isNotEmpty() || trimmed > 0

    /** True when pinned or unrefetchable results alone exceed `R_max`: reported, never hidden. */
    val overBound: Boolean get() = liveResultTokensAfter > rMaxTokens

    val items: List<Item> get() = residents.map { it.item }
}

/** Token counts of the cached prefix `[S][R][K]` as rendered for this request. */
public data class PrefixTokens(val sTokens: Long, val rTokens: Long, val kTokens: Long) {
    init {
        require(sTokens >= 0 && rTokens >= 0 && kTokens >= 0) { "token counts must be ≥ 0" }
    }

    val total: Long get() = sTokens + rTokens + kTokens
}

/**
 * The `C(t)` accounting of §5.7:
 * `C(t) = |S| + |R| + |K| + |T_live(t)| + |A(t)|`, `T_live = Σ live results (≤ R_max) + Σ stubs + messages`.
 * The uncached part of a request is the anchor and the items that entered `[T]` this turn; the rest is
 * the prefix the provider can serve from cache.
 */
public data class Occupancy(
    val prefix: PrefixTokens,
    val liveResultTokens: Long,
    val stubTokens: Long,
    val stubCount: Int,
    /** Pinned user messages, model messages (trimmed or not), calls and any other non-result item. */
    val messageTokens: Long,
    val anchorTokens: Long,
    /** Tokens of the items that entered `[T]` this turn: the new model message and the new results. */
    val newTokens: Long,
) {
    val tLiveTokens: Long get() = liveResultTokens + stubTokens + messageTokens

    val totalTokens: Long get() = prefix.total + tLiveTokens + anchorTokens

    val uncachedTokens: Long get() = anchorTokens + newTokens

    val cachedTokens: Long get() = totalTokens - uncachedTokens

    /** Window occupancy for the gauge (`ctx %`), clamped so an overflow reads 100, not 140. */
    public fun percentOf(contextLimitTokens: Int): Int {
        require(contextLimitTokens > 0) { "context limit must be positive" }
        return (totalTokens * 100 / contextLimitTokens).coerceIn(0, 100).toInt()
    }
}

/**
 * Residency of `[T]` (§5.7; L1; F26): results live in full for [k] turns and then become stubs; the total
 * of live results is bounded by [rMaxTokens]; model messages older than `3k` turns are trimmed to their
 * first line, their calls kept; user messages and the packet are pinned.
 *
 * **Batching is the point.** Every rewrite of an item before the tail invalidates the provider's cached
 * prefix from that item on, so evicting one result per turn would pay a miss every turn (F26). Rewrites
 * happen in batches: on the `k`-turn cadence, when the bound is exceeded, or for a stale body too large
 * to wait (§5.3). Between batches `[T]` is append-only.
 *
 * **Nothing is removed.** A stub replaces its result in place with the same call id, and a trim replaces
 * a message in place, so every tool-call/result unit stays complete and the adapter's `validate()` keeps
 * accepting the history (FX-21). A stub is a pointer to captured bytes, not a summary; a body with no
 * captured copy is stubbed only with a recorded [Loss] (invariant 9).
 *
 * Every method is a pure function of the records it is given: no clock, no counter, and the estimator is
 * used only to measure the stub and trimmed text it writes.
 */
public class Residency(
    public val k: Int,
    public val rMaxTokens: Int,
    private val estimator: TokenEstimator,
) {
    init {
        require(k > 0) { "k must be positive" }
        require(rMaxTokens > 0) { "R_max must be positive" }
    }

    /** Model messages older than this many turns are trimmed (§5.7: `3k`). */
    public val modelTrimTurns: Int get() = 3 * k

    /** Σ tokens of live results. */
    public fun liveResultTokens(residents: List<Resident>): Long = residents.filter { it.isLiveResult }.sumOf { it.tokens }

    /**
     * Whether a batch is due before the request of [turn] is rendered: the cadence, or the bound. When both
     * apply the cadence is named, because that batch was going to happen anyway.
     */
    public fun due(residents: List<Resident>, turn: Int): EvictionTrigger? = when {
        turn > 0 && turn % k == 0 -> EvictionTrigger.Age
        liveResultTokens(residents) > rMaxTokens -> EvictionTrigger.Budget
        else -> null
    }

    /**
     * One batch at the end of [turn]: the age rule, the trim rule, then the bound.
     *
     * Bound order (§5.7): candidates are live, unpinned, refetchable results of earlier turns — the current
     * turn's results are exempt because a result the model has not read yet has no reuse to estimate — taken
     * in ascending `value = p_reuse · c_refetch`, raw observations first and verdicts last, until the live
     * total is within `R_max`. What remains over the bound is pinned or unrefetchable and is reported.
     */
    @JvmOverloads
    public fun batch(residents: List<Resident>, turn: Int, trigger: EvictionTrigger = due(residents, turn) ?: EvictionTrigger.Age): Eviction {
        require(turn >= 0) { "turn must be ≥ 0" }
        val out = residents.toMutableList()
        val stubbed = ArrayList<Stub>()
        val losses = ArrayList<Loss>()
        var trimmed = 0
        val before = liveResultTokens(residents)

        // Age rule: a result that has been live for k turns becomes a stub, recoverable or not (invariant 9 records the loss).
        for (i in out.indices) {
            val r = out[i]
            if (r.isLiveResult && !r.pinned && turn - r.turn >= k) out[i] = stub(r, turn, stubbed, losses)
        }
        // Trim rule: a model message older than 3k turns keeps its first line; its calls are separate items and stay.
        for (i in out.indices) {
            val r = out[i]
            val message = r.item as? Message ?: continue
            if (r.pinned || r.residence != Residence.Live || message.role != ItemRole.Assistant || turn - r.turn < modelTrimTurns) continue
            val head = message.text.lineSequence().first()
            if (head == message.text) continue
            val parts = listOf(Text(head)) + message.parts.filter { it !is Text }
            val item = Message(message.role, parts)
            out[i] = r.copy(item = item, tokens = estimator.estimate(item.text).tokens, residence = Residence.Trimmed)
            trimmed++
        }
        // Bound: stub the cheapest-to-lose refetchable results of earlier turns until the live total fits.
        var live = liveResultTokens(out)
        if (live > rMaxTokens) {
            val order = out.indices
                .filter { i -> val r = out[i]; r.isLiveResult && !r.pinned && r.pointer != null && r.turn < turn }
                .sortedWith(compareBy({ out[it].resultClass!!.ordinal }, { refetchability(out[it], turn) }, { out[it].turn }, { it }))
            for (i in order) {
                if (live <= rMaxTokens) break
                val r = out[i]
                out[i] = stub(r, turn, stubbed, losses)
                live -= r.tokens
            }
        }
        return Eviction(turn, trigger, k, rMaxTokens, before, liveResultTokens(out), out, stubbed, trimmed, losses)
    }

    /**
     * Stubs the named live results now, outside the cadence (§5.3: a stale body over `immediateStubTokens`
     * is stubbed at once). It costs a miss of its own, so it does nothing else; the cadence is untouched.
     */
    public fun stubNow(residents: List<Resident>, aliases: Set<String>, turn: Int): Eviction {
        require(turn >= 0) { "turn must be ≥ 0" }
        val out = residents.toMutableList()
        val stubbed = ArrayList<Stub>()
        val losses = ArrayList<Loss>()
        val before = liveResultTokens(residents)
        for (i in out.indices) {
            val r = out[i]
            if (r.isLiveResult && !r.pinned && r.alias != null && r.alias in aliases) out[i] = stub(r, turn, stubbed, losses)
        }
        return Eviction(turn, EvictionTrigger.Immediate, k, rMaxTokens, before, liveResultTokens(out), out, stubbed, 0, losses)
    }

    /** Marks the results the Workset dropped: their bytes are historical, so their `p_reuse` is nil. */
    public fun markStale(residents: List<Resident>, aliases: Set<String>): List<Resident> =
        residents.map { if (it.isLiveResult && it.alias != null && it.alias in aliases) it.copy(stale = true) else it }

    /**
     * `value = p_reuse · c_refetch` (§5.7) within a class. `p_reuse` decays with age and is nil for a stale
     * result, whose bytes the model must re-read anyway; `c_refetch` is the body's size, what a recall or
     * re-run serves again. The class itself carries the dominant term (a verdict cannot be refetched at
     * all) and orders first; this value orders within it. Lower is stubbed earlier.
     */
    public fun refetchability(resident: Resident, turn: Int): Double {
        require(resident.isResult) { "refetchability is defined for results" }
        val age = (turn - resident.turn).coerceAtLeast(0)
        val pReuse = if (resident.stale) 0.0 else 1.0 / (1 + age)
        return pReuse * resident.tokens.toDouble()
    }

    /** The pointer behind a stub, for a `recall` of [alias]; null when the alias is not a stubbed result. */
    public fun recall(residents: List<Resident>, alias: String): RecallPointer? =
        residents.firstOrNull { it.residence == Residence.Stubbed && it.alias == alias }?.pointer

    public fun stubs(residents: List<Resident>): List<Resident> = residents.filter { it.residence == Residence.Stubbed }

    /** The `[T]` items in order, for [Transcript]. */
    public fun items(residents: List<Resident>): List<Item> = residents.map { it.item }

    /** `C(t)` for the request of [turn]; [pinnedTokens] is the size of the transcript's pinned user messages. */
    @JvmOverloads
    public fun occupancy(
        prefix: PrefixTokens,
        residents: List<Resident>,
        turn: Int,
        anchorTokens: Long,
        pinnedTokens: Long = 0,
    ): Occupancy {
        require(anchorTokens >= 0 && pinnedTokens >= 0) { "token counts must be ≥ 0" }
        var live = 0L
        var stubs = 0L
        var stubCount = 0
        var messages = pinnedTokens
        var fresh = 0L
        for (r in residents) {
            when {
                r.isLiveResult -> live += r.tokens
                r.isResult -> {
                    stubs += r.tokens
                    stubCount++
                }
                else -> messages += r.tokens
            }
            if (r.turn == turn) fresh += r.tokens
        }
        return Occupancy(prefix, live, stubs, stubCount, messages, anchorTokens, fresh)
    }

    private fun stub(r: Resident, turn: Int, stubbed: MutableList<Stub>, losses: MutableList<Loss>): Resident {
        val original = r.item as ToolResult
        val stub = Stub(r.alias, r.label, r.tokens, r.pointer, turn)
        if (stub.pointer == null) losses += Loss(turn, r.label, r.tokens, "stubbed with no captured copy to recall")
        stubbed += stub
        val text = stub.render()
        // Same call id, same error flag: the pair the adapter validates is untouched (FX-21).
        val item = ToolResult(original.callId, listOf(Text(text)), original.isError)
        return r.copy(item = item, tokens = estimator.estimate(text).tokens, residence = Residence.Stubbed)
    }

    public companion object {
        @JvmStatic
        public fun of(defaults: Defaults, estimator: TokenEstimator): Residency = Residency(defaults.k, defaults.rMaxTokens, estimator)
    }
}
