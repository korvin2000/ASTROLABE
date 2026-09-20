package io.astrolabe.telemetry

import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import java.math.BigInteger
import java.util.Collections

public enum class TraceSpanStatus { Completed, Cancelled, Open }

/** Supplied capture record, not the runtime Span. Parentage owns costs, never implicit causality. */
public data class TraceSpan(
    val id: SpanId,
    val parent: SpanId?,
    val phase: Phase,
    val identities: Identities,
    val clockDomain: String,
    val startNanos: Long,
    val endNanos: Long?,
    val exclusiveCost: Money,
    val status: TraceSpanStatus,
) {
    init {
        traceLabel(clockDomain)
        require(endNanos == null || endNanos >= startNanos) { "negative span duration or clock wrap/skew" }
        require(status != TraceSpanStatus.Open || endNanos == null)
        require(exclusiveCost.amount.signum() >= 0)
    }
}

public enum class TraceUnitKind { Work, Wait }

/** Exclusive execution/wait interval in its owning span's clock; not the inclusive span duration. */
public data class TraceUnit(val id: String, val span: SpanId, val kind: TraceUnitKind, val startNanos: Long, val endNanos: Long?) {
    init {
        traceLabel(id)
        require(endNanos == null || endNanos >= startNanos) { "negative unit duration or clock wrap/skew" }
    }
}

public data class TraceEdge(val from: String, val to: String) {
    init { traceLabel(from); traceLabel(to) }
}

/** Completeness is an explicit caller attestation, never inferred from an ancestry tree. */
public class TraceSnapshot(
    public val version: String,
    public val provenance: String,
    public val work: WorkId,
    public val currency: String,
    spans: List<TraceSpan>,
    units: List<TraceUnit>,
    edges: Set<TraceEdge>,
    public val unitsComplete: Boolean,
    public val causalityComplete: Boolean,
) {
    public val spans: List<TraceSpan> = traceList(spans.sortedBy { it.id.value })
    public val units: List<TraceUnit> = traceList(units.sortedBy { it.id })
    public val edges: Set<TraceEdge> = Collections.unmodifiableSet(LinkedHashSet(edges.sortedWith(compareBy(TraceEdge::from, TraceEdge::to))))
    init {
        traceLabel(version); require(provenance.isNotBlank())
        require(currency.length == 3 && currency.all { it in 'A'..'Z' })
    }
}

public data class TraceLimits(val maxDecimalDigits: Int) { init { require(maxDecimalDigits > 0) } }
public enum class TraceStatus { Complete, Partial, InvalidInput, ResourceLimit }
public data class TraceBand(val startNanos: Long, val endNanos: Long, val workers: Int)

public class TraceTiming internal constructor(
    public val workerNanos: BigInteger?,
    public val busyNanos: BigInteger?,
    public val maxConcurrency: Int?,
    bands: List<TraceBand>?,
    public val elapsedNanos: BigInteger?,
) {
    public val bands: List<TraceBand>? = bands?.let(::traceList)
}

/** Known causal-path lower bound; exact only for a caller-certified complete, closed model. */
public class TraceCriticalPath internal constructor(
    public val knownLengthNanos: BigInteger,
    units: List<String>,
    public val complete: Boolean,
) {
    public val units: List<String> = traceList(units)
}

public class TraceAnalysis internal constructor(
    public val snapshot: TraceSnapshot,
    public val status: TraceStatus,
    inclusiveCosts: Map<SpanId, Money>,
    public val totalCost: Money?,
    public val timing: TraceTiming?,
    public val criticalPath: TraceCriticalPath?,
    public val duplicateSpans: Int,
    public val duplicateUnits: Int,
    issues: List<String>,
) {
    public val inclusiveCosts: Map<SpanId, Money> = Collections.unmodifiableMap(LinkedHashMap(inclusiveCosts))
    public val issues: List<String> = traceList(issues.sorted())
}

private fun traceLabel(value: String) { require(value.isNotBlank() && value == value.trim()) }
private fun <T> traceList(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
