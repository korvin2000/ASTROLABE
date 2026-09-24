package io.astrolabe.telemetry

import io.astrolabe.id.ContextId
import java.util.function.LongSupplier

/** How a boundary found the pre-compiled `[K]` (§6.6): served, discarded for a moved input, or never pending. */
public enum class PrecompileOutcome { Hit, Miss, None }

/**
 * One boundary under pre-compilation: the closing [cell], the increment compiled next, the outcome and the boundary
 * latency — from the cell's close to the next `[K]` in hand, in the metrics' monotonic clock domain.
 */
public data class PrecompileSample(
    val cell: ContextId?,
    val incrementId: String,
    val outcome: PrecompileOutcome,
    val reason: String?,
    val boundaryNanos: Long,
)

/** The §19.5 ablation numbers for pre-compilation: hits, misses and p50/p95 boundary latency; `null` until measured. */
public data class PrecompileReport(
    val hits: Int,
    val misses: Int,
    val boundaries: Int,
    val p50BoundaryNanos: Long?,
    val p95BoundaryNanos: Long?,
)

/** Records every boundary the controller runs with the `precompile` flag on; nothing is recorded with it off. */
public class PrecompileMetrics @JvmOverloads public constructor(
    private val nanos: LongSupplier = LongSupplier { System.nanoTime() },
) {
    private val samples = ArrayList<PrecompileSample>()

    /** The current instant of the metrics' clock domain, for measuring a boundary. */
    public fun now(): Long = nanos.asLong

    @Synchronized
    public fun record(sample: PrecompileSample) {
        require(sample.boundaryNanos >= 0) { "a boundary latency is non-negative" }
        samples += sample
    }

    @Synchronized
    public fun samples(): List<PrecompileSample> = samples.toList()

    @Synchronized
    public fun report(): PrecompileReport {
        val sorted = samples.map { it.boundaryNanos }.sorted()
        return PrecompileReport(
            hits = samples.count { it.outcome == PrecompileOutcome.Hit },
            misses = samples.count { it.outcome == PrecompileOutcome.Miss },
            boundaries = samples.size,
            p50BoundaryNanos = percentile(sorted, 50),
            p95BoundaryNanos = percentile(sorted, 95),
        )
    }

    public companion object {
        /** Nearest-rank percentile of [sorted] ascending values; `null` on an empty list. */
        @JvmStatic
        public fun percentile(sorted: List<Long>, p: Int): Long? {
            require(p in 1..100) { "a percentile is 1..100" }
            if (sorted.isEmpty()) return null
            val rank = Math.ceilDiv(p * sorted.size, 100).coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
