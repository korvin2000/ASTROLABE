package io.astrolabe.telemetry

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * Per-cell metrics of §15.5, derived from the cell's events. A quantity no producer reports yet is `null`
 * (unmeasured), never zero: STATE upkeep (P2.3 manifest), manifest ref and pre-compilation hit
 * (P2.3.4). A billed dimension missing from any call's usage is `null` for the whole cell (§15.2).
 */
public data class CellMetrics(
    val cell: ContextId,
    val tokensByCacheClass: Map<BillingDimension, Long?>,
    val modelCalls: Int,
    val toolCalls: Int,
    /** Summed over calls from `tool.called` to `tool.resulted`; parallel calls add, so this is worker time, not latency. */
    val toolSeconds: Double,
    /** Checks finished, by layer (the check's kind when the caller maps ids to kinds, else the check id). */
    val checksByLayer: Map<String, Int>,
    val gatesFired: List<String>,
    val rebuilds: Int,
    val turns: Int,
    val boundaryReason: String?,
    val anchorTokens: Long? = null,
    val stateUpkeepTokens: Long? = null,
    val manifestRef: String? = null,
    val precompilationHit: Boolean? = null,
) {
    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun of(cell: ContextId, records: List<EventRecord>, layerOf: Map<String, String> = emptyMap()): CellMetrics {
            val own = records.filter { it.event.ids.context == cell }
            val usages = own.map { it.event }.filterIsInstance<AgentEvent.Cell.ModelResponded>().map { it.usage }
            val dimensions = usages.filterNotNull().flatMap { it.quantities.keys + it.unknown }.distinct()
            val tokens = dimensions.associateWith { dimension ->
                if (usages.any { it == null || dimension !in it.quantities }) null else usages.sumOf { it!!.quantities.getValue(dimension) }
            }
            val called = HashMap<Int, Instant>()
            var toolNanos = 0L
            for (record in own) when (val event = record.event) {
                is AgentEvent.Cell.ToolCalled -> called[event.opId] = record.at
                is AgentEvent.Cell.ToolResulted -> called.remove(event.opId)?.let { toolNanos += Duration.between(it, record.at).toNanos().coerceAtLeast(0) }
                else -> Unit
            }
            val events = own.map { it.event }
            return CellMetrics(
                cell = cell,
                tokensByCacheClass = tokens,
                modelCalls = events.count { it is AgentEvent.Cell.ModelRequested },
                toolCalls = events.count { it is AgentEvent.Cell.ToolCalled },
                toolSeconds = toolNanos / 1e9,
                checksByLayer = events.filterIsInstance<AgentEvent.Check.Finished>().groupingBy { layerOf[it.checkId] ?: it.checkId }.eachCount(),
                gatesFired = events.filterIsInstance<AgentEvent.Cell.GateFired>().map { it.gate },
                rebuilds = events.count { it is AgentEvent.Cell.Rebuilt },
                turns = events.filterIsInstance<AgentEvent.Cell.TurnStarted>().maxOfOrNull { it.turn } ?: 0,
                boundaryReason = events.filterIsInstance<AgentEvent.Cell.Ended>().lastOrNull()?.status,
                anchorTokens = events.filterIsInstance<AgentEvent.Cell.ModelRequested>().map { it.anchorTokens }.let { sizes -> if (sizes.isEmpty() || null in sizes) null else sizes.sumOf { it!! } },
            )
        }
    }
}

/** Why a human intervened (§15.5); `null` on an [Intervention] the harness could not classify. */
public enum class InterventionReason { MissingRequirement, ScopeDecision, Environment, ExternalEffectApproval, IncorrectImplementation }

public data class Intervention(val questionId: String, val reason: InterventionReason?)

/**
 * Per-campaign metrics of §15.5, derived from the campaign's events and its accepted spend. Mechanisms that do
 * not exist yet report `null`: probes and reviews (P2.4), escalations and alternative attempts (P4.6), boundary
 * cost share (P2.5).
 */
public data class CampaignMetrics(
    val work: WorkId,
    /** Cost over verified increments; undefined (`null`) at zero accepted and when the cost is unknown. */
    val costPerAcceptedTask: Money?,
    val firstAttemptPassRate: Double?,
    val verified: Int,
    val blocked: Int,
    val cancelled: Int,
    val continuationsPerIncrement: Map<String, Int>,
    val rebuildsPerCell: Double?,
    val interventions: List<Intervention>,
    val boundaryCostShare: Double? = null,
    val probes: Int? = null,
    val reviews: Int? = null,
    val escalations: Int? = null,
    val alternativeAttempts: Int? = null,
) {
    public companion object {
        @JvmStatic
        public fun of(work: WorkId, records: List<EventRecord>, cost: Money?): CampaignMetrics {
            val events = records.map { it.event }.filter { it.ids.work == work }
            val cellsByIncrement = LinkedHashMap<String, MutableList<ContextId>>()
            for (event in events.filterIsInstance<AgentEvent.Cell.Started>()) {
                val increment = event.incrementId ?: continue
                cellsByIncrement.getOrPut(increment) { ArrayList() } += checkNotNull(event.ids.context)
            }
            val ended = events.filterIsInstance<AgentEvent.Cell.Ended>().associate { it.ids.context to it.status }
            val verified = events.filterIsInstance<AgentEvent.Campaign.IncrementClosed>().filter { it.status == "verified" }.map { it.incrementId }.toSet()
            val last = cellsByIncrement.filterKeys { it !in verified }.mapValues { (_, cells) -> ended[cells.last()] }
            val cellCount = cellsByIncrement.values.sumOf { it.size }
            val accepted = verified.size
            return CampaignMetrics(
                work = work,
                costPerAcceptedTask = cost?.takeIf { accepted > 0 && !it.unknown }?.let {
                    Money(it.currency, it.amount.divide(BigDecimal.valueOf(accepted.toLong()), 10, RoundingMode.HALF_EVEN))
                },
                firstAttemptPassRate = if (accepted == 0) null else verified.count { cellsByIncrement[it]?.size == 1 }.toDouble() / accepted,
                verified = accepted,
                blocked = last.values.count { it == "blocked" },
                cancelled = last.values.count { it == "cancelled" },
                continuationsPerIncrement = cellsByIncrement.mapValues { (_, cells) -> cells.size - 1 },
                rebuildsPerCell = if (cellCount == 0) null else events.count { it is AgentEvent.Cell.Rebuilt }.toDouble() / cellCount,
                interventions = events.filterIsInstance<AgentEvent.Ask.Question>().map { Intervention(it.questionId, null) },
            )
        }
    }
}

/** A deployment outcome the host reports after the harness is done (§15.5 "post-merge reverts and churn"). */
public enum class DeploymentOutcomeKind { Merged, Reverted, Churned }

public data class DeploymentOutcome(val work: WorkId, val kind: DeploymentOutcomeKind, val detail: String?, val at: Instant)

/** The host's reporting seam for deployment outcomes: `Outcomes.report(workId, outcome)`. */
public interface Outcomes {
    public fun report(work: WorkId, outcome: DeploymentOutcome)

    public fun reported(): List<DeploymentOutcome>
}

public class InMemoryOutcomes : Outcomes {
    private val outcomes = ArrayList<DeploymentOutcome>()

    @Synchronized
    override fun report(work: WorkId, outcome: DeploymentOutcome) {
        require(outcome.work == work) { "the outcome belongs to ${outcome.work.value}, not ${work.value}" }
        outcomes += outcome
    }

    @Synchronized
    override fun reported(): List<DeploymentOutcome> = outcomes.toList()
}

/**
 * The per-project metric model of §15.5. Its sources arrive later and fill these fields there: KB usage and
 * retrieval misses (P2.6), routing calibration quadruples and prior drift (P4.1), MAST-tagged failures (P4.5);
 * deployment outcomes come from [Outcomes]. `null` means not measured, never zero.
 */
public data class ProjectMetrics(
    val kbUsageRate: Double? = null,
    val retrievalMisses: Int? = null,
    val routingCalibration: List<String>? = null,
    val failureDistribution: Map<String, Int>? = null,
    val calibrationPriorDrift: Double? = null,
    val deploymentOutcomes: List<DeploymentOutcome> = emptyList(),
)
