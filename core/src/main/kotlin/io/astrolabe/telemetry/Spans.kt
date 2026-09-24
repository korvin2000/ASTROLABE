package io.astrolabe.telemetry

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.event.SpanId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import java.util.function.LongSupplier

/**
 * A runtime span (§15.5): a phase-tagged interval with its parent, the ids it ran under, and the cost it produced
 * itself — exclusive, recorded once at its producing span. Inclusive totals are derived, never stored. An
 * unknown cost is [Money.unknown], never zero (§15.2).
 */
public data class Span(
    val id: SpanId,
    val parent: SpanId?,
    val phase: Phase,
    val ids: Identities,
    val startNanos: Long,
    val endNanos: Long?,
    val exclusiveCost: Money,
    val status: TraceSpanStatus,
) {
    public val durationNanos: Long? get() = endNanos?.let { it - startNanos }
}

/**
 * The span recorder: [start] and [end] each emit one event, so every span is visible on the bus with its parent
 * and phase. Times come from one monotonic clock domain ([clockDomain]); wall-clock instants live on the event
 * records as metadata. [analyze] hands the recorded spans to [TraceAnalytics] for inclusive costs and timing.
 */
public class Spans @JvmOverloads public constructor(
    private val idGen: IdGen,
    private val events: Events? = null,
    public val currency: String = "USD",
    private val nanos: LongSupplier = LongSupplier { System.nanoTime() },
    public val clockDomain: String = "jvm-nanotime",
) {
    private val spans = LinkedHashMap<SpanId, Span>()

    /** Opens a span under [parent]; the parent must be a span of this recorder. */
    @JvmOverloads
    public fun start(phase: Phase, ids: Identities, parent: SpanId? = null): SpanId = synchronized(spans) {
        require(parent == null || parent in spans) { "unknown parent span $parent" }
        val id = SpanId(idGen.next("span"))
        spans[id] = Span(id, parent, phase, ids, nanos.asLong, null, Money.unknown(currency), TraceSpanStatus.Open)
        events?.emit(AgentEvent.Telemetry.SpanStarted(ids, phase, id, parent))
        id
    }

    /** Closes [id] with the cost it produced itself; a span ends once, so its cost is counted once. */
    @JvmOverloads
    public fun end(id: SpanId, exclusiveCost: Money = Money.unknown(currency), status: TraceSpanStatus = TraceSpanStatus.Completed): Span = synchronized(spans) {
        val open = requireNotNull(spans[id]) { "unknown span $id" }
        check(open.status == TraceSpanStatus.Open) { "span $id already ended ${open.status}" }
        require(status != TraceSpanStatus.Open) { "a span ends completed or cancelled" }
        require(exclusiveCost.currency == currency) { "span costs are in $currency, not ${exclusiveCost.currency}" }
        val closed = open.copy(endNanos = maxOf(open.startNanos, nanos.asLong), exclusiveCost = exclusiveCost, status = status)
        spans[id] = closed
        val cost = if (exclusiveCost.unknown) null else "${exclusiveCost.currency} ${exclusiveCost.amount.toPlainString()}"
        events?.emit(AgentEvent.Telemetry.SpanEnded(closed.ids, status.name.lowercase(), cost, closed.durationNanos, closed.phase, id, closed.parent))
        closed
    }

    /** Runs [block] inside a span, ending it cancelled when [block] throws. */
    public suspend fun <T> span(phase: Phase, ids: Identities, parent: SpanId? = null, block: suspend (SpanId) -> T): T {
        val id = start(phase, ids, parent)
        val result = try {
            block(id)
        } catch (failure: Throwable) {
            end(id, status = TraceSpanStatus.Cancelled)
            throw failure
        }
        end(id)
        return result
    }

    public fun all(): List<Span> = synchronized(spans) { spans.values.toList() }

    /** The spans of [work] as the analytics kernel's input; open spans stay open, so totals report them. */
    @JvmOverloads
    public fun snapshot(work: WorkId, provenance: String = "spans"): TraceSnapshot = TraceSnapshot(
        version = SNAPSHOT_VERSION,
        provenance = provenance,
        work = work,
        currency = currency,
        spans = all().filter { it.ids.work == work }.map {
            TraceSpan(it.id, it.parent, it.phase, it.ids, clockDomain, it.startNanos, it.endNanos, it.exclusiveCost, it.status)
        },
        units = emptyList(),
        edges = emptySet(),
        unitsComplete = false,
        causalityComplete = false,
    )

    /** Inclusive costs per span and the work's total, each exclusive cost counted once (§15.5). */
    @JvmOverloads
    public fun analyze(work: WorkId, limits: TraceLimits = TraceLimits(MAX_DIGITS)): TraceAnalysis = TraceAnalytics.analyze(snapshot(work), limits)

    public companion object {
        public const val SNAPSHOT_VERSION: String = "spans-v1"

        /** Decimal digits the money arithmetic accepts before it refuses as a resource limit. */
        public const val MAX_DIGITS: Int = 38
    }
}
