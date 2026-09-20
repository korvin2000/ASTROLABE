package io.astrolabe.telemetry

import io.astrolabe.event.SpanId
import io.astrolabe.provider.Money
import java.math.BigInteger
import java.util.TreeMap

/** Offline arithmetic for supplied traces (§15.2/§15.5). No store, timing capture or invoice authority. */
public object TraceAnalytics {
    @JvmStatic
    public fun analyze(snapshot: TraceSnapshot, limits: TraceLimits): TraceAnalysis {
        val issues = mutableListOf<String>()
        val spans = linkedMapOf<SpanId, TraceSpan>()
        val units = linkedMapOf<String, TraceUnit>()
        var duplicateSpans = 0
        var duplicateUnits = 0
        fun refuse(status: TraceStatus, reasons: List<String>) = TraceAnalysis(snapshot, status, emptyMap(), null,
            null, null, duplicateSpans, duplicateUnits, reasons)
        if (snapshot.spans.any { span -> span.exclusiveCost.amount.let {
            it.precision() > limits.maxDecimalDigits || kotlin.math.abs(it.scale().toLong()) > limits.maxDecimalDigits
        } }) return refuse(TraceStatus.ResourceLimit, listOf("money precision/scale limit"))
        for ((id, deliveries) in snapshot.spans.groupBy { it.id }) {
            val payloads = deliveries.map { it.copy(exclusiveCost = it.exclusiveCost.copy(amount = it.exclusiveCost.amount.stripTrailingZeros())) }.distinct()
            duplicateSpans += deliveries.size - payloads.size
            if (payloads.size != 1) issues += "conflicting span $id"
            spans[id] = payloads.first()
            if (payloads.any { it.identities.work != snapshot.work }) issues += "wrong work identity $id"
            if (payloads.any { it.exclusiveCost.currency != snapshot.currency }) issues += "mixed currency $id"
        }
        for ((id, deliveries) in snapshot.units.groupBy { it.id }) {
            val payloads = deliveries.distinct()
            duplicateUnits += deliveries.size - payloads.size
            if (payloads.size != 1) issues += "conflicting unit $id"
            units[id] = payloads.first()
        }
        // Conflicting records cannot establish a parent or owner; reject before consulting either payload.
        if (issues.isNotEmpty()) return refuse(TraceStatus.InvalidInput, issues)
        spans.values.forEach { span -> if (span.parent != null && span.parent !in spans) issues += "orphan span ${span.id}" }
        units.values.forEach { unit ->
            val owner = spans[unit.span]
            if (owner == null) issues += "missing unit owner ${unit.id}"
            else if (unit.startNanos < owner.startNanos || owner.endNanos?.let { end ->
                unit.startNanos > end || unit.endNanos != null && unit.endNanos > end
            } == true) issues += "unit outside owner lifetime ${unit.id}"
        }
        for ((span, activity) in units.values.groupBy { it.span }) {
            var previous: TraceUnit? = null
            for (unit in activity.sortedWith(compareBy(TraceUnit::startNanos, TraceUnit::id))) {
                if (unit.endNanos == unit.startNanos) continue
                previous?.let { if (it.endNanos == null || it.endNanos > unit.startNanos) issues += "overlapping exclusive units for $span" }
                previous = unit
            }
        }
        snapshot.edges.forEach { edge -> if (edge.from !in units || edge.to !in units) issues += "missing causal endpoint $edge" }
        if (issues.isNotEmpty()) return refuse(TraceStatus.InvalidInput, issues)

        val comparable = spans.values.map { it.clockDomain }.distinct().size <= 1
        if (comparable) snapshot.edges.forEach { edge ->
            val from = units.getValue(edge.from)
            if (from.endNanos != null && from.endNanos > units.getValue(edge.to).startNanos)
                issues += "causality contradicts timestamps $edge"
        }
        if (issues.isNotEmpty()) return refuse(TraceStatus.InvalidInput, issues)

        val costs = spans.mapValues { it.value.exclusiveCost }.toMutableMap()
        val childCount = spans.keys.associateWith { 0 }.toMutableMap()
        spans.values.forEach { it.parent?.let { parent -> childCount[parent] = childCount.getValue(parent) + 1 } }
        val leaves = ArrayDeque(childCount.filterValues { it == 0 }.keys)
        var visited = 0
        while (leaves.isNotEmpty()) {
            val id = leaves.removeFirst()
            visited++
            spans.getValue(id).parent?.let { parent ->
                val sum = costs.getValue(parent) + costs.getValue(id)
                if (sum.amount.precision() > limits.maxDecimalDigits)
                    return refuse(TraceStatus.ResourceLimit, listOf("money sum precision limit"))
                costs[parent] = sum
                childCount[parent] = childCount.getValue(parent) - 1
                if (childCount.getValue(parent) == 0) leaves.addLast(parent)
            }
        }
        if (visited != spans.size) return refuse(TraceStatus.InvalidInput, listOf("ancestry cycle"))
        var total = Money.zero(snapshot.currency)
        spans.values.filter { it.parent == null }.forEach { total += costs.getValue(it.id) }
        if (total.amount.precision() > limits.maxDecimalDigits)
            return refuse(TraceStatus.ResourceLimit, listOf("money total precision limit"))

        val predecessors = units.keys.associateWith { 0 }.toMutableMap()
        val successors = snapshot.edges.groupBy { it.from }
        snapshot.edges.forEach { predecessors[it.to] = predecessors.getValue(it.to) + 1 }
        val ready = ArrayDeque(predecessors.filterValues { it == 0 }.keys)
        val order = ArrayList<String>()
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            order += id
            successors[id].orEmpty().forEach { edge ->
                predecessors[edge.to] = predecessors.getValue(edge.to) - 1
                if (predecessors.getValue(edge.to) == 0) ready.addLast(edge.to)
            }
        }
        if (order.size != units.size) return refuse(TraceStatus.InvalidInput, listOf("causal cycle"))

        val spansClosed = spans.values.all { it.endNanos != null }
        val unitsClosed = units.values.all { it.endNanos != null }
        val elapsed = if (comparable && spansClosed) {
            if (spans.isEmpty()) BigInteger.ZERO else nanos(spans.values.minOf { it.startNanos }, spans.values.maxOf { it.endNanos!! })
        } else null
        val completeActivity = comparable && spansClosed && unitsClosed && snapshot.unitsComplete
        val timing = if (completeActivity) sweep(units.values.filter { it.kind == TraceUnitKind.Work }, elapsed)
            else TraceTiming(null, null, null, null, elapsed)
        var critical: TraceCriticalPath? = null
        if (comparable && unitsClosed) {
            val length = units.mapValues { (_, unit) -> nanos(unit.startNanos, unit.endNanos!!) }.toMutableMap()
            val previous = hashMapOf<String, String>()
            for (id in order) successors[id].orEmpty().forEach { edge ->
                val next = units.getValue(edge.to)
                val proposed = length.getValue(id) + nanos(next.startNanos, next.endNanos!!)
                if (proposed > length.getValue(edge.to)) { length[edge.to] = proposed; previous[edge.to] = id }
            }
            val end = units.keys.minWithOrNull(compareByDescending<String> { length.getValue(it) }.thenBy { it })
            val path = mutableListOf<String>()
            var cursor = end
            while (cursor != null) { path += cursor; cursor = previous[cursor] }
            critical = TraceCriticalPath(end?.let { length.getValue(it) } ?: BigInteger.ZERO, path.reversed(),
                snapshot.causalityComplete && snapshot.unitsComplete && spansClosed)
        }
        if (total.unknown) issues += "recorded exclusive cost is incomplete"
        if (!comparable) issues += "clock domains are not comparable"
        if (!spansClosed) issues += "span end missing"
        if (!unitsClosed) issues += "unit end missing"
        if (!snapshot.unitsComplete) issues += "exclusive unit inventory incomplete"
        if (!snapshot.causalityComplete) issues += "causal model incomplete - known path is only a lower bound"
        return TraceAnalysis(snapshot, if (issues.isEmpty()) TraceStatus.Complete else TraceStatus.Partial,
            costs, total, timing, critical, duplicateSpans, duplicateUnits, issues)
    }
}

private fun nanos(start: Long, end: Long): BigInteger = BigInteger.valueOf(end).subtract(BigInteger.valueOf(start))

private fun sweep(units: List<TraceUnit>, elapsed: BigInteger?): TraceTiming {
    val events = TreeMap<Long, Int>()
    var worker = BigInteger.ZERO
    for (unit in units) {
        val end = unit.endNanos!!
        worker += nanos(unit.startNanos, end)
        if (unit.startNanos == end) continue
        events[unit.startNanos] = events.getOrDefault(unit.startNanos, 0) + 1
        events[end] = events.getOrDefault(end, 0) - 1
    }
    var active = 0
    var peak = 0
    var busy = BigInteger.ZERO
    var previous: Long? = null
    val bands = mutableListOf<TraceBand>()
    for ((time, delta) in events) {
        previous?.let { start ->
            bands += TraceBand(start, time, active)
            if (active > 0) busy += nanos(start, time)
        }
        active += delta
        peak = maxOf(peak, active)
        previous = time
    }
    return TraceTiming(worker, busy, peak, bands, elapsed)
}
