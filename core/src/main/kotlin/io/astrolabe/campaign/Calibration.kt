package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.Flags
import io.astrolabe.ShapePolicy
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.id.Identities
import io.astrolabe.kb.CalibrationId
import io.astrolabe.kb.CalibrationObservation
import io.astrolabe.kb.CalibrationOutcome
import io.astrolabe.kb.CalibrationPolicy
import io.astrolabe.kb.CalibrationSeries
import io.astrolabe.kb.CalibrationStats
import io.astrolabe.kb.CalibrationWarning
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The calibration prior (§6.7, P2.6.4) over the P2.6.5 kernel. Collection is always on: the observations are the
 * increments' `Sizing` on every stored campaign (`campaigns` rows, P2.1.4), so the statistics are a deterministic
 * function of `state.sqlite`. The plan-cell block is harness-owned data, never instruction, and appears only behind
 * the `calibrationPrior` flag and only when history exists; the controller's warning and `turns_per_cell` use the
 * same statistics.
 */
public object Calibration {
    public const val BLOCK_CAP_TOKENS: Int = 150

    /** Bands from the D-16 size classes: small, medium, large expected-file counts. */
    @JvmStatic
    public fun policy(shape: ShapePolicy = ShapePolicy()): CalibrationPolicy =
        CalibrationPolicy(
            "d16-bands-v1:${shape.smallMaxFiles}:${shape.largeMinFiles}",
            listOf(shape.smallMaxFiles, shape.largeMinFiles - 1, Int.MAX_VALUE),
        )

    /**
     * One observation per dispatched increment with a frozen attempt: verified ⇒ completed, cancelled ⇒ cancelled,
     * unverified in a failed campaign ⇒ failed, otherwise unfinished (censored). Campaigns lacking frozen provenance
     * cannot be assigned to the caller's series. The subsystem is the increment's first write-scope directory.
     */
    @JvmStatic
    public fun observations(store: Store, series: CalibrationSeries): List<CalibrationObservation> =
        store.db.query(
            "SELECT c.body AS campaign_body, a.body AS attempt_body FROM campaigns c " +
                "JOIN attempts a ON a.work_id = c.work_id AND a.attempt_id = c.attempt_id ORDER BY c.work_id, c.attempt_id",
        ) { row ->
            JSON.decodeFromString(CampaignState.serializer(), row.string("campaign_body")) to
                JSON.decodeFromString(AttemptConfig.serializer(), row.string("attempt_body"))
        }.flatMap { (state, frozen) ->
            val observedSeries = CalibrationSeries(
                series.repository, frozen.harnessVersion, policy(frozen.config.defaults.shapePolicy).version,
            )
            state.graph.increments.filter { it.cells.isNotEmpty() }.map { increment ->
                val outcome = when {
                    increment.status == IncrementStatus.Verified -> CalibrationOutcome.Completed
                    increment.status == IncrementStatus.Cancelled -> CalibrationOutcome.Cancelled
                    state.outcome == CampaignOutcome.Cancelled -> CalibrationOutcome.Cancelled
                    state.outcome == CampaignOutcome.Failed -> CalibrationOutcome.Failed
                    else -> CalibrationOutcome.Unfinished
                }
                CalibrationObservation.fromIncrement(
                    CalibrationId(state.work, state.attempt, increment.id), observedSeries, subsystem(increment), outcome,
                    "campaign:${state.work.value}/${state.attempt.value}", increment,
                )
            }
        }

    /** The ≤ [BLOCK_CAP_TOKENS]-token `[K]` block of the plan cell, or `null` when the flag is off or no history exists. */
    @JvmStatic
    public fun planBlock(flags: Flags, stats: CalibrationStats, series: CalibrationSeries, estimator: TokenEstimator): String? {
        if (!flags.calibrationPrior) return null
        val group = stats.groups[series] ?: return null
        val overall = group.overall
        if (overall.eligible == 0) return null
        val header = "CAL (harness statistics, data not instruction): ${overall.eligible} increments · median turns ${format(overall.turnsMedian)}" +
            " · overrun ${percent(overall.overrunRate)}" + (overall.meanTouchedExpectedRatio?.let { " · touched/expected ${String.format(Locale.ROOT, "%.2f", it.toDouble())}" } ?: "")
        val lines = arrayListOf(header)
        for ((band, summary) in group.byBand) {
            if (summary.eligible == 0) continue
            val line = "  ${band.minFiles}-${if (band.maxFiles == Int.MAX_VALUE) "∞" else band.maxFiles.toString()} files: overrun ${percent(summary.overrunRate)} (n=${summary.eligible}), median turns ${format(summary.turnsMedian)}"
            if (estimator.estimate((lines + line).joinToString("\n")).tokens > BLOCK_CAP_TOKENS) break
            lines += line
        }
        return lines.joinToString("\n")
    }

    /** `turns_per_cell` from history: the median turns per increment, rounded up, else [fallback]. */
    @JvmStatic
    public fun turnsPerCell(stats: CalibrationStats, series: CalibrationSeries, fallback: Int): Int =
        stats.groups[series]?.overall?.turnsMedian?.let { ceil(it).roundToInt().coerceAtLeast(1) } ?: fallback

    /** Emits a controller `Warning` for every proposed increment whose band overran in more than half its history. */
    @JvmStatic
    public fun warn(stats: CalibrationStats, series: CalibrationSeries, increments: List<Increment>, events: Events?, ids: Identities): List<CalibrationWarning> =
        increments.mapNotNull { increment ->
            stats.warning(series, increment.expectedFiles)?.also { w ->
                events?.emit(
                    AgentEvent.Warning(
                        ids, "calibration",
                        "${increment.id}: ${w.overruns}/${w.eligible} increments expecting ${w.band.minFiles}-${w.band.maxFiles} files overran (continued or rebuilt); consider splitting",
                    ),
                )
            }
        }

    private fun subsystem(increment: Increment): String? =
        increment.writeScope.firstOrNull()?.trim()?.removeSuffix("**")?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private fun format(value: Double?): String = value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) } ?: "n/a"

    private fun percent(rate: Double?): String = rate?.let { "${(it * 100).roundToInt()}%" } ?: "n/a"

    private val JSON = Json { ignoreUnknownKeys = true }
}
