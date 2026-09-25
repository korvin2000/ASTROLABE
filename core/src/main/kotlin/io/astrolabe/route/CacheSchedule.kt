package io.astrolabe.route

import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.math.MathContext
import java.util.Collections

/** The prompt prefix a cell would reuse: consecutive cells with the same role and profile keep `[S][R]` hot (§11.4). */
public data class CacheKey(val role: String, val profile: String) {
    init {
        require(role.isNotBlank() && profile.isNotBlank()) { "a cache key names a role and a profile" }
    }
}

/**
 * One ready cell as the ordering hint sees it: its [key] and how many positions past its ready-order position the hint
 * may defer it ([maxDelay], the latency it tolerates; 0 for a continuation, which never waits). Nothing about its
 * context is here: the hint reorders cells, it never keeps or drops context (FX-45).
 */
public data class CellSlot @JvmOverloads constructor(val id: String, val key: CacheKey, val maxDelay: Int = CacheSchedule.DEFAULT_MAX_DELAY) {
    init {
        require(id.isNotBlank()) { "a slot names its cell or increment" }
        require(maxDelay >= 0)
    }
}

/** A schedule's accepted-task economics (§11.4, FX-45): money over accepted tasks; the cache share is reported, never judged. */
public data class ScheduleEconomics @JvmOverloads constructor(
    val acceptedTasks: Int,
    val money: Money,
    val cachedInputTokens: Long? = null,
    val inputTokens: Long? = null,
) {
    init {
        require(acceptedTasks >= 0)
        require(cachedInputTokens == null || inputTokens == null || cachedInputTokens in 0..inputTokens)
    }

    /** Money per accepted task; `null` when nothing was accepted or the money is unknown. */
    public val costPerAcceptedTask: BigDecimal?
        get() = if (acceptedTasks == 0 || money.unknown) null else money.amount.divide(BigDecimal.valueOf(acceptedTasks.toLong()), MathContext.DECIMAL64)

    /** Cached over total input tokens, a diagnostic only; `null` when either is unmeasured. */
    public val cacheHitRate: Double?
        get() = if (cachedInputTokens == null || inputTokens == null || inputTokens == 0L) null else cachedInputTokens.toDouble() / inputTokens
}

/** How one schedule compares with a baseline on accepted-task economics. */
public enum class ScheduleVerdict { Better, Same, Worse, Unmeasured }

/**
 * Cache-aware scheduling (§11.4): an ordering hint for the controller over ready cells, and the judgement a schedule is
 * held to. The hint groups cells that share a [CacheKey] next to each other, but only where latency allows — a slot is
 * never deferred more than its [CellSlot.maxDelay] positions — and it is a pure function of the slots (D-153).
 */
public object CacheSchedule {
    public const val DEFAULT_MAX_DELAY: Int = 2

    /**
     * The ready slots in hint order, starting after [previous] (the key of the cell that just ran): at each position the
     * slot whose deadline (ready position + max delay) is due goes first, else the first slot sharing the current key,
     * else the first slot in ready order. Earliest-deadline-first over unit slots meets every deadline the ready order meets.
     */
    @JvmStatic
    @JvmOverloads
    public fun order(ready: List<CellSlot>, previous: CacheKey? = null): List<CellSlot> {
        require(ready.map { it.id }.toSet().size == ready.size) { "slot ids are distinct" }
        val remaining = ready.withIndex().toMutableList()
        val ordered = ArrayList<CellSlot>(ready.size)
        var current = previous
        while (remaining.isNotEmpty()) {
            val position = ordered.size
            val due = remaining.filter { it.index + it.value.maxDelay <= position }.minByOrNull { it.index + it.value.maxDelay }
            val chosen = due ?: remaining.firstOrNull { it.value.key == current } ?: remaining.first()
            remaining.remove(chosen)
            ordered += chosen.value
            current = chosen.value.key
        }
        return Collections.unmodifiableList(ordered)
    }

    /**
     * FX-45: a schedule is judged by accepted-task economics against [baseline]; a higher cache hit rate bought by
     * retaining irrelevant context is not an improvement unless money per accepted task falls.
     */
    @JvmStatic
    public fun judge(candidate: ScheduleEconomics, baseline: ScheduleEconomics): ScheduleVerdict {
        if (candidate.money.currency != baseline.money.currency) return ScheduleVerdict.Unmeasured
        val a = candidate.costPerAcceptedTask ?: return ScheduleVerdict.Unmeasured
        val b = baseline.costPerAcceptedTask ?: return ScheduleVerdict.Unmeasured
        return when (a.compareTo(b).coerceIn(-1, 1)) {
            -1 -> ScheduleVerdict.Better
            0 -> ScheduleVerdict.Same
            else -> ScheduleVerdict.Worse
        }
    }
}
