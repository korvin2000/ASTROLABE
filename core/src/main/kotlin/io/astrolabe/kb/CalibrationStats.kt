package io.astrolabe.kb

import io.astrolabe.contract.Increment
import io.astrolabe.graph.Sizing
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import java.math.BigDecimal
import java.math.MathContext
import java.util.Collections

/** One increment execution, including all continuation cells. Repository qualifies this ID in a series. */
public data class CalibrationId(val work: WorkId, val attempt: AttemptId, val increment: String) {
    init { require(increment.isNotBlank() && increment == increment.trim()) }
}

public data class CalibrationSeries(val repository: String, val harnessVersion: String, val sizingPolicyVersion: String) {
    init { require(listOf(repository, harnessVersion, sizingPolicyVersion).all { it.isNotBlank() && it == it.trim() }) }
}

/** Failed executions are eligible. Cancelled/Unfinished are explicit censoring reasons, not zero-sized successes. */
public enum class CalibrationOutcome { Completed, Failed, Cancelled, Unfinished }

/** Trusted harness projection, never parsed from model-written CAL text. No mutable Increment is retained. */
public data class CalibrationObservation(
    val id: CalibrationId,
    val series: CalibrationSeries,
    val subsystem: String?,
    val expectedFiles: Int,
    val sizing: Sizing,
    val outcome: CalibrationOutcome,
    val provenance: String,
) {
    init {
        require(expectedFiles >= 0)
        require(subsystem == null || (subsystem.isNotBlank() && subsystem == subsystem.trim()))
        require(provenance.isNotBlank())
    }

    public companion object {
        /** Outcome describes terminal execution, independently of Increment's verification/plan status. */
        @JvmStatic
        public fun fromIncrement(
            id: CalibrationId,
            series: CalibrationSeries,
            subsystem: String?,
            outcome: CalibrationOutcome,
            provenance: String,
            increment: Increment,
        ): CalibrationObservation {
            require(id.increment == increment.id) { "calibration increment identity mismatch" }
            return CalibrationObservation(id, series, subsystem, increment.expectedFiles, increment.sizing, outcome, provenance)
        }
    }
}

public data class CalibrationBand(val minFiles: Int, val maxFiles: Int) {
    init { require(minFiles >= 0 && maxFiles >= minFiles) }
}

/** Explicit inclusive bands partition all nonnegative Int counts; no empirical/default bands are inferred. */
public class CalibrationPolicy(public val version: String, upperBounds: List<Int>) {
    public val upperBounds: List<Int> = Collections.unmodifiableList(upperBounds.toList())
    init {
        require(version.isNotBlank() && version == version.trim())
        require(this.upperBounds.isNotEmpty() && this.upperBounds.first() >= 0 &&
            this.upperBounds.last() == Int.MAX_VALUE && this.upperBounds.zipWithNext().all { (a, b) -> a < b })
    }
    public val bands: List<CalibrationBand> = Collections.unmodifiableList(this.upperBounds.mapIndexed { i, upper ->
        CalibrationBand(if (i == 0) 0 else this.upperBounds[i - 1] + 1, upper)
    })

    public fun band(expectedFiles: Int): CalibrationBand {
        require(expectedFiles >= 0)
        return bands.first { expectedFiles <= it.maxFiles }
    }
}

/** Statistics conditional on non-cancelled terminal observations; zero expected files only excludes a ratio. */
@ConsistentCopyVisibility
public data class CalibrationSummary internal constructor(
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val unfinished: Int,
    val overruns: Int,
    val turnsMedian: Double?,
    val definedRatios: Int,
    val undefinedRatios: Int,
    val meanTouchedExpectedRatio: BigDecimal?,
) {
    public val eligible: Int get() = completed + failed
    public val censored: Int get() = cancelled + unfinished
    public val overrunRate: Double? get() = if (eligible == 0) null else overruns.toDouble() / eligible
}

public class CalibrationGroup internal constructor(
    public val overall: CalibrationSummary,
    byBand: Map<CalibrationBand, CalibrationSummary>,
    bySubsystem: Map<String?, CalibrationSummary>,
) {
    public val byBand: Map<CalibrationBand, CalibrationSummary> = Collections.unmodifiableMap(LinkedHashMap(byBand))
    public val bySubsystem: Map<String?, CalibrationSummary> = Collections.unmodifiableMap(LinkedHashMap(bySubsystem))
}

/** Pure diagnostic data, not a controller Warning event. */
public data class CalibrationWarning(
    val series: CalibrationSeries,
    val band: CalibrationBand,
    val eligible: Int,
    val overruns: Int,
)

public class CalibrationStats private constructor(
    public val policy: CalibrationPolicy,
    public val inputRows: Int,
    observations: List<CalibrationObservation>,
    groups: Map<CalibrationSeries, CalibrationGroup>,
) {
    public val algorithmVersion: String = "calibration-stats-v1"
    public val observations: List<CalibrationObservation> = Collections.unmodifiableList(observations.toList())
    public val duplicateRows: Int = inputRows - observations.size
    public val groups: Map<CalibrationSeries, CalibrationGroup> = Collections.unmodifiableMap(LinkedHashMap(groups))

    /** Null means no triggered diagnostic; missing history remains explicit in groups and nullable rates. */
    public fun warning(series: CalibrationSeries, expectedFiles: Int): CalibrationWarning? {
        val band = policy.band(expectedFiles)
        val summary = groups[series]?.byBand?.get(band) ?: return null
        return if (2L * summary.overruns > summary.eligible)
            CalibrationWarning(series, band, summary.eligible, summary.overruns) else null
    }

    public companion object {
        /** Conflicting deliveries reject the whole calculation; callers must reconcile checkpoint versions first. */
        @JvmStatic
        public fun aggregate(observations: Collection<CalibrationObservation>, policy: CalibrationPolicy): CalibrationStats {
            val rows = observations.toList()
            val deliveries = rows.groupBy { it.series.repository to it.id }
            val conflicts = deliveries.filterValues { it.distinct().size != 1 }.keys.map { (repo, id) ->
                "$repo/${id.work.value}/${id.attempt.value}/${id.increment}"
            }.sorted()
            require(conflicts.isEmpty()) { "conflicting calibration checkpoints; reconcile before aggregation: $conflicts" }
            val unique = deliveries.values.map { it.first() }.sortedWith(compareBy(
                { it.series.repository }, { it.series.harnessVersion }, { it.series.sizingPolicyVersion },
                { it.id.work.value }, { it.id.attempt.value }, { it.id.increment }))
            val groups = unique.groupBy { it.series }.mapValues { (_, group) ->
                val bands = group.groupBy { policy.band(it.expectedFiles) }
                val subsystems = group.groupBy { it.subsystem }.toSortedMap(nullsFirst(naturalOrder()))
                CalibrationGroup(summarize(group), policy.bands.associateWith { summarize(bands[it].orEmpty()) },
                    subsystems.mapValues { summarize(it.value) })
            }
            return CalibrationStats(policy, rows.size, unique, groups)
        }

        private fun summarize(rows: List<CalibrationObservation>): CalibrationSummary {
            val eligible = rows.filter { it.outcome == CalibrationOutcome.Completed || it.outcome == CalibrationOutcome.Failed }
            val turns = eligible.map { it.sizing.turns }.sorted()
            val median = when {
                turns.isEmpty() -> null
                turns.size % 2 == 1 -> turns[turns.size / 2].toDouble()
                else -> (turns[turns.size / 2 - 1].toLong() + turns[turns.size / 2].toLong()) / 2.0
            }
            val ratios = eligible.filter { it.expectedFiles > 0 }.map {
                BigDecimal.valueOf(it.sizing.filesTouched.toLong())
                    .divide(BigDecimal.valueOf(it.expectedFiles.toLong()), MathContext.DECIMAL128)
            }
            val mean = if (ratios.isEmpty()) null else ratios.fold(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(ratios.size.toLong()), MathContext.DECIMAL128)
            return CalibrationSummary(
                rows.count { it.outcome == CalibrationOutcome.Completed }, rows.count { it.outcome == CalibrationOutcome.Failed },
                rows.count { it.outcome == CalibrationOutcome.Cancelled }, rows.count { it.outcome == CalibrationOutcome.Unfinished },
                eligible.count { it.sizing.continuations > 0 || it.sizing.rebuilds > 0 }, median,
                ratios.size, eligible.size - ratios.size, mean,
            )
        }
    }
}
