package io.astrolabe.telemetry

import io.astrolabe.id.ContextId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** P3.7.1 telemetry: pre-compilation hits and misses with p50/p95 boundary latency (§6.6, §19.5 ablation). */
class PrecompileMetricsTest {
    @Test
    fun `an empty recorder reports nothing measured`() {
        val report = PrecompileMetrics().report()
        assertEquals(PrecompileReport(0, 0, 0, null, null), report)
    }

    @Test
    fun `hits and misses are counted and the boundary latency percentiles are nearest-rank`() {
        var tick = 0L
        val metrics = PrecompileMetrics { tick }
        val latencies = listOf(50L, 10L, 40L, 20L, 30L, 60L, 70L, 80L, 90L, 100L)
        latencies.forEachIndexed { i, nanos ->
            tick += 1
            val outcome = when {
                i % 3 == 0 -> PrecompileOutcome.Hit
                i % 3 == 1 -> PrecompileOutcome.Miss
                else -> PrecompileOutcome.None
            }
            metrics.record(PrecompileSample(ContextId("cell-$i"), "I${i + 1}", outcome, null, nanos))
        }
        val report = metrics.report()
        assertEquals(4, report.hits)
        assertEquals(3, report.misses)
        assertEquals(10, report.boundaries)
        assertEquals(50L, report.p50BoundaryNanos)
        assertEquals(100L, report.p95BoundaryNanos)
        assertEquals(10, metrics.samples().size)
        assertEquals(tick, metrics.now())
    }

    @Test
    fun `percentiles are nearest-rank over the sorted latencies`() {
        assertNull(PrecompileMetrics.percentile(emptyList(), 50))
        assertEquals(7L, PrecompileMetrics.percentile(listOf(7L), 95))
        assertEquals(1L, PrecompileMetrics.percentile(listOf(1L, 2L), 50))
        assertEquals(2L, PrecompileMetrics.percentile(listOf(1L, 2L), 95))
        assertEquals(3L, PrecompileMetrics.percentile(listOf(1L, 2L, 3L, 4L, 5L), 50))
        assertEquals(5L, PrecompileMetrics.percentile(listOf(1L, 2L, 3L, 4L, 5L), 95))
        assertFailsWith<IllegalArgumentException> { PrecompileMetrics.percentile(listOf(1L), 0) }
    }

    @Test
    fun `a negative boundary latency is refused`() {
        assertFailsWith<IllegalArgumentException> { PrecompileMetrics().record(PrecompileSample(null, "I1", PrecompileOutcome.Hit, null, -1)) }
    }
}
