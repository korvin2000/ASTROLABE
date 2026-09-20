package io.astrolabe.kb

import io.astrolabe.graph.Sizing
import io.astrolabe.contract.Increment
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationStatsTest {
    private val policy = CalibrationPolicy("bands-v1", listOf(0, 3, 10, Int.MAX_VALUE))
    private val series = CalibrationSeries("repo", "harness-v1", "sizing-v1")

    @Test fun `mean of individual ratios and even median retain the right denominator`() {
        val report = CalibrationStats.aggregate(listOf(row("a", 1, Sizing(turns = 2, filesTouched = 1)),
            row("b", 3, Sizing(turns = 8, continuations = 1, filesTouched = 9))), policy)
        val summary = report.groups.getValue(series).overall
        assertEquals(5.0, summary.turnsMedian)
        assertEquals(0.5, summary.overrunRate)
        assertEquals(BigDecimal("2"), summary.meanTouchedExpectedRatio)
        assertEquals(2, summary.definedRatios)
        assertNull(report.warning(series, 3))
    }

    @Test fun `DECIMAL128 rounds each ratio before averaging`() {
        val report = CalibrationStats.aggregate(listOf(row("half", 2, Sizing(filesTouched = 1)),
            row("third", 3, Sizing(filesTouched = 1))), policy)
        assertEquals(BigDecimal("0.4166666666666666666666666666666666"),
            report.groups.getValue(series).overall.meanTouchedExpectedRatio)
    }

    @Test fun `strict warning starts above one half`() {
        val report = CalibrationStats.aggregate(listOf(row("a", 2, Sizing(continuations = 1)),
            row("b", 2, Sizing(rebuilds = 1)), row("c", 3, Sizing())), policy)
        val warning = assertNotNull(report.warning(series, 1))
        assertEquals(2, warning.overruns)
        assertEquals(3, warning.eligible)
        assertEquals(CalibrationBand(1, 3), warning.band)
    }

    @Test fun `empty history has no measured rate or warning`() {
        val report = CalibrationStats.aggregate(emptyList(), policy)
        assertEquals(emptyMap(), report.groups)
        assertNull(report.warning(series, 1))
    }

    @Test fun `zero expected files excludes only ratios and empty bands are unknown`() {
        val report = CalibrationStats.aggregate(listOf(row("zero", 0, Sizing(turns = 7, rebuilds = 1, filesTouched = 9)),
            row("one", 1, Sizing(turns = 3, filesTouched = 2))), policy)
        val group = report.groups.getValue(series)
        assertEquals(2, group.overall.eligible)
        assertEquals(5.0, group.overall.turnsMedian)
        assertEquals(0.5, group.overall.overrunRate)
        assertEquals(1, group.overall.undefinedRatios)
        assertEquals(1, group.overall.definedRatios)
        assertEquals(BigDecimal("2"), group.overall.meanTouchedExpectedRatio)
        val zero = group.byBand.getValue(CalibrationBand(0, 0))
        assertEquals(1.0, zero.overrunRate)
        assertNull(zero.meanTouchedExpectedRatio)
        assertNotNull(report.warning(series, 0))
        val absent = group.byBand.getValue(CalibrationBand(4, 10))
        assertNull(absent.overrunRate)
        assertNull(absent.turnsMedian)
        assertNull(report.warning(series, 4))
    }

    @Test fun `odd and even medians and ratios handle maximum counters`() {
        val rows = listOf(row("a", 1, Sizing(turns = Int.MAX_VALUE, filesTouched = Int.MAX_VALUE)),
            row("b", Int.MAX_VALUE, Sizing(turns = Int.MAX_VALUE - 1, filesTouched = Int.MAX_VALUE)))
        val even = CalibrationStats.aggregate(rows, policy).groups.getValue(series).overall
        assertEquals(Int.MAX_VALUE - 0.5, even.turnsMedian)
        assertEquals(BigDecimal("1073741824"), even.meanTouchedExpectedRatio)
        val odd = CalibrationStats.aggregate(rows + row("c", 2, Sizing(turns = 0)), policy).groups.getValue(series).overall
        assertEquals((Int.MAX_VALUE - 1).toDouble(), odd.turnsMedian)
    }

    @Test fun `failed terminal work is eligible while cancelled and unfinished counts remain visible`() {
        val observations = listOf(row("success", 2, Sizing(turns = 10)),
            row("failure", 2, Sizing(turns = 20, continuations = 8)).copy(outcome = CalibrationOutcome.Failed),
            row("cancelled", 2, Sizing(turns = 200, rebuilds = 6)).copy(outcome = CalibrationOutcome.Cancelled),
            row("open", 2, Sizing(turns = 300)).copy(outcome = CalibrationOutcome.Unfinished))
        val report = CalibrationStats.aggregate(observations, policy)
        val s = report.groups.getValue(series).overall
        assertEquals(2, s.eligible)
        assertEquals(1, s.completed)
        assertEquals(1, s.failed)
        assertEquals(2, s.censored)
        assertEquals(1, s.cancelled)
        assertEquals(1, s.unfinished)
        assertEquals(0.5, s.overrunRate)
        assertEquals(15.0, s.turnsMedian)
        assertEquals(4, report.observations.size)
        val censored = CalibrationStats.aggregate(observations.takeLast(2), policy).groups.getValue(series).overall
        assertEquals(0, censored.eligible)
        assertNull(censored.turnsMedian)
        assertNull(censored.meanTouchedExpectedRatio)
        assertNull(censored.overrunRate)
    }

    @Test fun `repositories versions and unequal subsystems never pool observations`() {
        val observations = (0 until 5).map { row("core$it", 2, Sizing(continuations = 1)) } +
            row("ui", 2, Sizing()).copy(subsystem = "ui") + row("unknown", 4, Sizing()).copy(subsystem = null) +
            row("other", 2, Sizing()).copy(series = series.copy(repository = "other")) +
            row("harness", 2, Sizing()).copy(series = series.copy(harnessVersion = "v2")) +
            row("policy", 2, Sizing()).copy(series = series.copy(sizingPolicyVersion = "v2"))
        val report = CalibrationStats.aggregate(observations, policy)
        assertEquals(4, report.groups.size)
        val main = report.groups.getValue(series)
        assertEquals(7, main.overall.eligible)
        assertEquals(1.0, main.bySubsystem.getValue("core").overrunRate)
        assertEquals(0.0, main.bySubsystem.getValue("ui").overrunRate)
        assertEquals(1, main.bySubsystem.getValue(null).eligible)
        assertEquals(5.0 / 6, main.byBand.getValue(CalibrationBand(1, 3)).overrunRate)
        assertNull(report.warning(series.copy(harnessVersion = "v2"), 1))
        assertNotNull(report.warning(series, 1))
    }

    @Test fun `all inclusive band boundaries and absent-series warnings are explicit`() {
        val cases = mapOf(0 to CalibrationBand(0, 0), 1 to CalibrationBand(1, 3), 3 to CalibrationBand(1, 3),
            4 to CalibrationBand(4, 10), 10 to CalibrationBand(4, 10), 11 to CalibrationBand(11, Int.MAX_VALUE),
            Int.MAX_VALUE to CalibrationBand(11, Int.MAX_VALUE))
        cases.forEach { (files, band) -> assertEquals(band, policy.band(files)) }
        val report = CalibrationStats.aggregate(listOf(row("a", 2, Sizing(rebuilds = 1))), policy)
        assertNull(report.warning(series.copy(repository = "unknown"), 2))
        assertFailsWith<IllegalArgumentException> { report.warning(series, -1) }
    }

    @Test fun `identical checkpoint deliveries deduplicate and conflicts never choose by input order`() {
        val a = row("a", 2, Sizing(continuations = 50))
        val report = CalibrationStats.aggregate(listOf(a, a, a), policy)
        assertEquals(3, report.inputRows)
        assertEquals(2, report.duplicateRows)
        assertEquals(1, report.groups.getValue(series).overall.eligible)
        assertEquals(1, report.groups.getValue(series).overall.overruns)
        for (changed in listOf(a.copy(sizing = Sizing()), a.copy(provenance = "new-checkpoint"),
            a.copy(outcome = CalibrationOutcome.Unfinished), a.copy(series = series.copy(harnessVersion = "v2")),
            a.copy(series = series.copy(sizingPolicyVersion = "v2")))) {
            val forward = assertFailsWith<IllegalArgumentException> { CalibrationStats.aggregate(listOf(a, changed), policy) }
            val reverse = assertFailsWith<IllegalArgumentException> { CalibrationStats.aggregate(listOf(changed, a), policy) }
            assertEquals(forward.message, reverse.message)
            assertTrue(forward.message!!.contains("reconcile"))
        }
        val independent = listOf(a, a.copy(series = series.copy(repository = "other")),
            a.copy(id = a.id.copy(work = WorkId("w2"))), a.copy(id = a.id.copy(attempt = AttemptId("a2"))))
        assertEquals(4, CalibrationStats.aggregate(independent, policy).observations.size)
    }

    @Test fun `increment projection uses canonical expected files and sizing and checks identity`() {
        val a = row("increment", 1, Sizing())
        val increment = Increment("increment", listOf("requirement"), emptyList(), emptyList(), expectedFiles = 7,
            sizing = Sizing(turns = 9, continuations = 2, filesTouched = 13))
        val projection = CalibrationObservation.fromIncrement(a.id, series, null, CalibrationOutcome.Failed, "receipt", increment)
        assertEquals(7, projection.expectedFiles)
        assertEquals(increment.sizing, projection.sizing)
        assertEquals(CalibrationOutcome.Failed, projection.outcome)
        assertFailsWith<IllegalArgumentException> {
            CalibrationObservation.fromIncrement(a.id.copy(increment = "wrong"), series, null,
                CalibrationOutcome.Completed, "receipt", increment)
        }
    }

    @Test fun `collections are owned and invalid observations or band partitions are rejected`() {
        val bounds = mutableListOf(0, 3, Int.MAX_VALUE)
        val ownedPolicy = CalibrationPolicy("owned", bounds)
        bounds.clear()
        val rows = mutableListOf(row("a", 1, Sizing()))
        val report = CalibrationStats.aggregate(rows, ownedPolicy)
        rows.clear()
        assertEquals(1, report.observations.size)
        assertEquals(3, ownedPolicy.bands.size)
        assertFailsWith<UnsupportedOperationException> { (ownedPolicy.upperBounds as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (ownedPolicy.bands as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.observations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.groups as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.groups.getValue(series).byBand as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (report.groups.getValue(series).bySubsystem as MutableMap).clear() }
        for (invalid in listOf(emptyList(), listOf(-1, Int.MAX_VALUE), listOf(3, 2, Int.MAX_VALUE),
            listOf(3, 3, Int.MAX_VALUE), listOf(3))) {
            assertFailsWith<IllegalArgumentException> { CalibrationPolicy("bad", invalid) }
        }
        assertFailsWith<IllegalArgumentException> { row("a", -1, Sizing()) }
        assertFailsWith<IllegalArgumentException> { row("a", 1, Sizing()).copy(provenance = " ") }
        assertFailsWith<IllegalArgumentException> { row("a", 1, Sizing()).copy(subsystem = " core ") }
        assertFailsWith<IllegalArgumentException> { series.copy(harnessVersion = " ") }
        assertFailsWith<IllegalArgumentException> { CalibrationId(WorkId("work"), AttemptId("attempt"), " ") }
    }

    @Test fun `seeded histories match exact rational means and remain identical after permutation`() {
        val random = Random(265)
        var maximumError = BigDecimal.ZERO
        repeat(120) { sample ->
            val observations = (0 until 24).map { i -> row("n$i", random.nextInt(0, 13),
                Sizing(random.nextInt(100), random.nextInt(3), random.nextInt(2), random.nextInt(1000)))
                .copy(outcome = CalibrationOutcome.entries[random.nextInt(4)], subsystem = listOf(null, "a", "b")[random.nextInt(3)]) }
            val report = CalibrationStats.aggregate(observations, policy)
            val shuffled = CalibrationStats.aggregate(observations.shuffled(random), policy)
            assertEquals(report.observations, shuffled.observations)
            val group = report.groups.getValue(series)
            assertEquals(group.overall, shuffled.groups.getValue(series).overall)
            assertEquals(group.byBand, shuffled.groups.getValue(series).byBand)
            assertEquals(group.bySubsystem, shuffled.groups.getValue(series).bySubsystem)
            val slices = listOf(observations to group.overall) + policy.bands.map { band ->
                observations.filter { it.expectedFiles in band.minFiles..band.maxFiles } to group.byBand.getValue(band)
            } + group.bySubsystem.map { (subsystem, stats) -> observations.filter { it.subsystem == subsystem } to stats }
            for ((slice, actual) in slices) {
                val eligible = slice.filter { it.outcome in setOf(CalibrationOutcome.Completed, CalibrationOutcome.Failed) }
                assertEquals(eligible.size, actual.eligible)
                assertEquals(slice.size - eligible.size, actual.censored)
                assertEquals(eligible.count { it.sizing.continuations + it.sizing.rebuilds > 0 }, actual.overruns)
                val defined = eligible.filter { it.expectedFiles > 0 }
                assertEquals(defined.size, actual.definedRatios)
                var numerator = BigInteger.ZERO
                var denominator = BigInteger.ONE
                for (observation in defined) {
                    val divisor = observation.expectedFiles.toBigInteger()
                    numerator = numerator * divisor + observation.sizing.filesTouched.toBigInteger() * denominator
                    denominator *= divisor
                    val gcd = numerator.gcd(denominator)
                    numerator /= gcd
                    denominator /= gcd
                }
                if (defined.isEmpty()) assertNull(actual.meanTouchedExpectedRatio) else {
                    val expected = numerator.toBigDecimal().divide((denominator * defined.size.toBigInteger()).toBigDecimal(),
                        MathContext.DECIMAL128)
                    val error = (assertNotNull(actual.meanTouchedExpectedRatio) - expected).abs()
                    maximumError = maximumError.max(error)
                    assertTrue(error <= BigDecimal("1e-30"), "seed=265 sample=$sample error=$error")
                }
            }
        }
        println("calibration rational oracle seed=265 histories=120 rowsPerHistory=24 maxAbsoluteError=$maximumError")
    }

    private fun row(id: String, expected: Int, sizing: Sizing) = CalibrationObservation(
        CalibrationId(WorkId("work"), AttemptId("attempt"), id), series, "core", expected, sizing,
        CalibrationOutcome.Completed, "checkpoint-$id")
}
