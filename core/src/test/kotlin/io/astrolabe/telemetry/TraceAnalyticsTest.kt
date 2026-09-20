package io.astrolabe.telemetry

import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.random.Random

class TraceAnalyticsTest {
    private val identities = Identities(WorkId("work"), AttemptId("attempt"))
    private val limits = TraceLimits(10_000)
    private fun usd(amount: String) = Money("USD", BigDecimal(amount))
    private fun span(id: String, parent: String? = null, start: Long = 0, end: Long? = 10, cost: String = "0") =
        TraceSpan(SpanId(id), parent?.let(::SpanId), Phase.Edit, identities, "clock", start, end, usd(cost),
            if (end == null) TraceSpanStatus.Open else TraceSpanStatus.Completed)
    private fun unit(id: String, span: String, start: Long, end: Long?, kind: TraceUnitKind = TraceUnitKind.Work) =
        TraceUnit(id, SpanId(span), kind, start, end)
    private fun snapshot(spans: List<TraceSpan>, units: List<TraceUnit>, edges: Set<TraceEdge> = emptySet(),
        unitsComplete: Boolean = true, causalityComplete: Boolean = false) =
        TraceSnapshot("v1", "synthetic trace", identities.work, "USD", spans, units, edges, unitsComplete, causalityComplete)

    @Test fun `overlapping workers have exact busy time and cost ancestry does not imply causality`() {
        val result = TraceAnalytics.analyze(snapshot(listOf(span("root", cost = "1"),
            span("a", "root", cost = "2"), span("b", "root", cost = "3")),
            listOf(unit("a", "a", 0, 6), unit("b", "b", 4, 10))), limits)
        assertEquals(TraceStatus.Partial, result.status)
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal("6")))
        assertEquals(0, result.inclusiveCosts.getValue(SpanId("root")).amount.compareTo(BigDecimal("6")))
        assertEquals(BigInteger("12"), result.timing!!.workerNanos)
        assertEquals(BigInteger.TEN, result.timing.busyNanos)
        assertEquals(2, result.timing.maxConcurrency)
        assertEquals(BigInteger("6"), result.criticalPath!!.knownLengthNanos)
        assertEquals(false, result.criticalPath.complete)
    }

    @Test fun `wait units count in causal path but not worker time or implicit ancestry`() {
        val result = TraceAnalytics.analyze(snapshot(listOf(span("root", end = 20), span("a", "root")),
            listOf(unit("work", "a", 0, 2), unit("wait", "a", 2, 8, TraceUnitKind.Wait), unit("finish", "a", 8, 10)),
            setOf(TraceEdge("work", "wait"), TraceEdge("wait", "finish")), causalityComplete = true), limits)
        assertEquals(TraceStatus.Complete, result.status)
        assertEquals(BigInteger("4"), result.timing!!.workerNanos)
        assertEquals(BigInteger("4"), result.timing.busyNanos)
        assertEquals(BigInteger("20"), result.timing.elapsedNanos)
        assertEquals(BigInteger.TEN, result.criticalPath!!.knownLengthNanos)
        assertEquals(listOf("work", "wait", "finish"), result.criticalPath.units)
        assertTrue(result.criticalPath.complete)
    }

    @Test fun `adjacent endpoints zero intervals and idle gaps have exact half-open semantics`() {
        val result = TraceAnalytics.analyze(snapshot(listOf(span("a"), span("b"), span("z")), listOf(
            unit("a", "a", 0, 2), unit("b", "b", 2, 4), unit("z", "z", 2, 2), unit("later", "a", 8, 10),
        ), causalityComplete = true), limits)
        assertEquals(BigInteger("6"), result.timing!!.busyNanos)
        assertEquals(BigInteger("6"), result.timing.workerNanos)
        assertEquals(1, result.timing.maxConcurrency)
        assertEquals(listOf(TraceBand(0, 2, 1), TraceBand(2, 4, 1), TraceBand(4, 8, 0), TraceBand(8, 10, 1)), result.timing.bands)
        val empty = TraceAnalytics.analyze(snapshot(emptyList(), emptyList(), causalityComplete = true), limits)
        assertEquals(TraceStatus.Complete, empty.status)
        assertEquals(BigInteger.ZERO, empty.timing!!.workerNanos)
        assertEquals(BigInteger.ZERO, empty.timing.elapsedNanos)
        assertEquals(0, empty.timing.maxConcurrency)
        assertEquals(emptyList(), empty.criticalPath!!.units)
    }

    @Test fun `identical deliveries deduplicate with numerical monetary equality and immutable provenance`() {
        val original = span("a", cost = "1.0")
        val activity = unit("a", "a", 0, 10)
        val spans = mutableListOf(original, original.copy(exclusiveCost = usd("1.00")))
        val units = mutableListOf(activity, activity)
        val edges = mutableSetOf<TraceEdge>()
        val input = snapshot(spans, units, edges, causalityComplete = true)
        spans.clear(); units.clear(); edges += TraceEdge("missing", "missing")
        val result = TraceAnalytics.analyze(input, limits)
        assertEquals(1, result.duplicateSpans); assertEquals(1, result.duplicateUnits)
        assertEquals(BigInteger.TEN, result.timing!!.workerNanos)
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal.ONE))
        assertSame(input, result.snapshot)
        assertFailsWith<UnsupportedOperationException> { (input.spans as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.units as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (input.edges as MutableSet).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.inclusiveCosts as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.criticalPath!!.units as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (result.timing.bands as MutableList).clear() }
    }

    @Test fun `conflicting payloads orphan ownership ancestry cycles and mixed identities are visible`() {
        val invalid = listOf(
            snapshot(listOf(span("a"), span("a", cost = "1")), emptyList()),
            snapshot(listOf(span("a")), listOf(unit("u", "a", 0, 1), unit("u", "a", 0, 2))),
            snapshot(listOf(span("a", "missing")), emptyList()),
            snapshot(listOf(span("a", "b"), span("b", "a")), emptyList()),
            snapshot(listOf(span("a", "a")), emptyList()),
            snapshot(listOf(span("a")), listOf(unit("u", "missing", 0, 1))),
            snapshot(listOf(span("a").copy(identities = identities.copy(work = WorkId("other")))), emptyList()),
            snapshot(listOf(span("a").copy(exclusiveCost = Money("EUR", BigDecimal.ZERO))), emptyList()),
        )
        invalid.forEach { input ->
            val result = TraceAnalytics.analyze(input, limits)
            assertEquals(TraceStatus.InvalidInput, result.status)
            assertTrue(result.issues.isNotEmpty()); assertNull(result.totalCost)
        }
    }

    @Test fun `conflicting redeliveries have order-independent duplicate counts and diagnostics`() {
        val a = span("a")
        val conflicting = span("a", "missing", cost = "1")
        val u = unit("u", "a", 0, 1)
        val other = unit("u", "a", 0, 2)
        val forward = TraceAnalytics.analyze(snapshot(listOf(a, a, conflicting), listOf(u, u, other)), limits)
        val reversed = TraceAnalytics.analyze(snapshot(listOf(conflicting, a, a), listOf(other, u, u)), limits)
        assertEquals(TraceStatus.InvalidInput, forward.status)
        assertEquals(1, forward.duplicateSpans); assertEquals(1, forward.duplicateUnits)
        assertEquals(forward.duplicateSpans, reversed.duplicateSpans)
        assertEquals(forward.duplicateUnits, reversed.duplicateUnits)
        assertEquals(forward.issues, reversed.issues)
    }

    @Test fun `unknown money propagates to ancestors without hiding known amounts or timing`() {
        val spans = listOf(span("root", cost = "1"), span("a", "root", cost = "2").copy(
            exclusiveCost = usd("2").copy(unknown = true)), span("b", "root", cost = "3"), span("other", cost = "4"))
        val result = TraceAnalytics.analyze(snapshot(spans, emptyList(), causalityComplete = true), limits)
        assertEquals(TraceStatus.Partial, result.status)
        assertTrue(result.totalCost!!.unknown)
        assertEquals(0, result.totalCost.amount.compareTo(BigDecimal.TEN))
        assertTrue(result.inclusiveCosts.getValue(SpanId("root")).unknown)
        assertEquals(false, result.inclusiveCosts.getValue(SpanId("b")).unknown)
        assertEquals(BigInteger.TEN, result.timing!!.elapsedNanos)
    }

    @Test fun `mixed clocks and missing ends never become zero complete durations`() {
        val mixed = snapshot(listOf(span("a"), span("b").copy(clockDomain = "other")),
            listOf(unit("a", "a", 0, 10), unit("b", "b", 0, 10)), causalityComplete = true)
        val result = TraceAnalytics.analyze(mixed, limits)
        assertEquals(TraceStatus.Partial, result.status)
        assertNull(result.timing!!.workerNanos); assertNull(result.timing.elapsedNanos); assertNull(result.criticalPath)
        for (status in TraceSpanStatus.entries) {
            val open = TraceAnalytics.analyze(snapshot(listOf(span("a", end = null).copy(status = status)),
                listOf(unit("u", "a", 0, null)), causalityComplete = true), limits)
            assertEquals(TraceStatus.Partial, open.status)
            assertNull(open.timing!!.workerNanos); assertNull(open.criticalPath)
        }
        val cancelled = TraceAnalytics.analyze(snapshot(listOf(span("a").copy(status = TraceSpanStatus.Cancelled)),
            listOf(unit("u", "a", 0, 10)), causalityComplete = true), limits)
        assertEquals(BigInteger.TEN, cancelled.timing!!.workerNanos)
        assertEquals(TraceStatus.Complete, cancelled.status)
    }

    @Test fun `incomplete unit inventory preserves elapsed and labels path as lower bound`() {
        val result = TraceAnalytics.analyze(snapshot(listOf(span("a")), listOf(unit("u", "a", 0, 5)),
            unitsComplete = false, causalityComplete = true), limits)
        assertNull(result.timing!!.workerNanos)
        assertEquals(BigInteger.TEN, result.timing.elapsedNanos)
        assertEquals(BigInteger("5"), result.criticalPath!!.knownLengthNanos)
        assertEquals(false, result.criticalPath.complete)
        val openSpan = TraceAnalytics.analyze(snapshot(listOf(span("a", end = null)), listOf(unit("u", "a", 0, 5)),
            causalityComplete = true), limits)
        assertEquals(false, openSpan.criticalPath!!.complete)
    }

    @Test fun `causal cycles missing endpoints skew and overlapping exclusive ownership are invalid`() {
        val spans = listOf(span("a"), span("b"))
        val inputs = listOf(
            snapshot(spans, listOf(unit("a", "a", 0, 0), unit("b", "b", 0, 0)), setOf(TraceEdge("a", "b"), TraceEdge("b", "a"))),
            snapshot(spans, listOf(unit("a", "a", 0, 0)), setOf(TraceEdge("a", "a"))),
            snapshot(spans, emptyList(), setOf(TraceEdge("missing", "missing"))),
            snapshot(spans, listOf(unit("a", "a", 0, 6), unit("b", "b", 4, 10)), setOf(TraceEdge("a", "b"))),
            snapshot(spans, listOf(unit("a", "a", 0, 6), unit("b", "a", 4, 10))),
            snapshot(spans, listOf(unit("a", "a", -1, 1))),
            snapshot(spans, listOf(unit("a", "a", 9, 11))),
        )
        inputs.forEach { assertEquals(TraceStatus.InvalidInput, TraceAnalytics.analyze(it, limits).status) }
        assertFailsWith<IllegalArgumentException> { span("negative", start = 3, end = 2) }
        assertFailsWith<IllegalArgumentException> { unit("negative", "a", 3, 2) }
    }

    @Test fun `asynchronous child may outlive parent and retains recorded attempt identity`() {
        val child = span("child", "parent", start = 2, end = 10, cost = "3").copy(
            identities = identities.copy(attempt = AttemptId("retry")))
        val result = TraceAnalytics.analyze(snapshot(listOf(span("parent", end = 2, cost = "1"), child),
            listOf(unit("child", "child", 2, 10)), causalityComplete = true), limits)
        assertEquals(TraceStatus.Complete, result.status)
        assertEquals(BigInteger.TEN, result.timing!!.elapsedNanos)
        assertEquals(0, result.totalCost!!.amount.compareTo(BigDecimal("4")))
    }

    @Test fun `nanosecond sums cross Long limits without wrapping and money limits refuse rounding`() {
        val spans = listOf(span("a", start = Long.MIN_VALUE, end = Long.MAX_VALUE), span("b", start = Long.MIN_VALUE, end = Long.MAX_VALUE))
        val result = TraceAnalytics.analyze(snapshot(spans, spans.map { unit(it.id.value, it.id.value, Long.MIN_VALUE, Long.MAX_VALUE) },
            causalityComplete = true), limits)
        val duration = BigInteger("18446744073709551615")
        assertEquals(duration, result.timing!!.busyNanos)
        assertEquals(duration * BigInteger.TWO, result.timing.workerNanos)
        assertEquals(duration, result.timing.elapsedNanos)
        assertEquals(duration, result.criticalPath!!.knownLengthNanos)
        assertEquals(TraceStatus.ResourceLimit, TraceAnalytics.analyze(snapshot(listOf(span("a", cost = "1E+10001")), emptyList()), limits).status)
        assertEquals(TraceStatus.ResourceLimit, TraceAnalytics.analyze(snapshot(listOf(span("a", cost = "9"), span("b", "a", cost = "9")),
            emptyList()), TraceLimits(1)).status)
        assertEquals(TraceStatus.ResourceLimit, TraceAnalytics.analyze(snapshot(listOf(span("a", cost = "9"), span("b", cost = "9")),
            emptyList()), TraceLimits(1)).status)
    }

    @Test fun `deep ancestry accumulates iteratively without stack overflow`() {
        val spans = (0 until 20_000).map { i -> span("s$i", if (i == 0) null else "s${i-1}", cost = "1") }
        val result = TraceAnalytics.analyze(snapshot(spans, emptyList(), causalityComplete = true), limits)
        assertEquals(TraceStatus.Complete, result.status)
        assertEquals(0, result.inclusiveCosts.getValue(SpanId("s0")).amount.compareTo(BigDecimal("20000")))
    }

    @Test fun `deep causal path reconstructs iteratively`() {
        val count = 5000
        val units = (0 until count).map { unit("u$it", "a", it.toLong(), it.toLong()+1) }
        val edges = (1 until count).map { TraceEdge("u${it-1}", "u$it") }.toSet()
        val result = TraceAnalytics.analyze(snapshot(listOf(span("a", end = count.toLong())), units, edges,
            causalityComplete = true), limits)
        assertEquals(BigInteger.valueOf(count.toLong()), result.criticalPath!!.knownLengthNanos)
        assertEquals(units.map { it.id }, result.criticalPath.units)
    }

    @Test fun `sweep tree costs and causal path match independent oracles for 300 random traces`() {
        val random = Random(1113)
        repeat(300) {
            val count = random.nextInt(1, 8)
            val spans = (0 until count).map { i -> span("s$i", if (i == 0 || random.nextBoolean()) null else "s${random.nextInt(i)}",
                end = 20, cost = random.nextInt(10).toString()) }
            val units = spans.mapIndexed { i, s ->
                val start = random.nextLong(0, 15)
                unit("u$i", s.id.value, start, random.nextLong(start, 20),
                    if (random.nextInt(4) == 0) TraceUnitKind.Wait else TraceUnitKind.Work)
            }
            val sorted = units.sortedWith(compareBy(TraceUnit::startNanos, TraceUnit::id))
            val edges = buildSet {
                for (i in sorted.indices) for (j in i+1 until sorted.size)
                    if (sorted[i].endNanos!! <= sorted[j].startNanos && random.nextBoolean()) add(TraceEdge(sorted[i].id, sorted[j].id))
            }
            val occupancy = (0L until 20).map { time -> units.count { it.kind == TraceUnitKind.Work && it.startNanos <= time && time < it.endNanos!! } }
            fun longest(id: String): Long {
                val u = units.single { it.id == id }
                return u.endNanos!! - u.startNanos + (edges.filter { it.from == id }.maxOfOrNull { longest(it.to) } ?: 0)
            }
            val expectedPath = units.maxOf { longest(it.id) }
            for ((ss, us, es) in listOf(Triple(spans, units, edges), Triple(spans.reversed(), units.reversed(), edges.reversed().toSet()))) {
                val result = TraceAnalytics.analyze(snapshot(ss, us, es, causalityComplete = true), limits)
                assertEquals(TraceStatus.Complete, result.status)
                assertEquals(BigInteger.valueOf(occupancy.sum().toLong()), result.timing!!.workerNanos)
                assertEquals(BigInteger.valueOf(occupancy.count { it > 0 }.toLong()), result.timing.busyNanos)
                assertEquals(occupancy.max(), result.timing.maxConcurrency)
                assertEquals(BigInteger.valueOf(expectedPath), result.criticalPath!!.knownLengthNanos)
                val path = result.criticalPath.units
                path.zipWithNext().forEach { (a, b) -> assertTrue(TraceEdge(a, b) in edges) }
                assertEquals(expectedPath, path.sumOf { id -> units.single { it.id == id }.let { it.endNanos!! - it.startNanos } })
                for (span in spans) {
                    val expected = spans.filter { possible ->
                        var at: TraceSpan? = possible
                        while (at != null && at.id != span.id) at = at.parent?.let { parent -> spans.single { it.id == parent } }
                        at != null
                    }.map { it.exclusiveCost.amount }.reduce(BigDecimal::add)
                    assertEquals(0, expected.compareTo(result.inclusiveCosts.getValue(span.id).amount))
                }
                assertEquals(0, spans.map { it.exclusiveCost.amount }.reduce(BigDecimal::add).compareTo(result.totalCost!!.amount))
                for (time in 0L until 20) {
                    val band = result.timing.bands!!.singleOrNull { it.startNanos <= time && time < it.endNanos }
                    assertEquals(occupancy[time.toInt()], band?.workers ?: 0)
                }
            }
        }
    }
}
