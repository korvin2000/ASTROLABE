package io.astrolabe.eval

import org.junit.jupiter.api.Test
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.*

class PairedBoundTest {
    @Test fun `margin uses fractions and excludes equality even for constant samples`() {
        val keys = (0 until 50).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        val d = design(keys)
        val base = Scorecard.calculate(d, baseline, keys.map { trial(it, true, "1", baseline) })
        val cand = Scorecard.calculate(d, candidate, keys.map { trial(it, it.key.repository != "r0", "1") })
        for (onlyComplex in listOf(false, true)) {
            val bound = PairedBound.calculate(base, cand, onlyComplex)
            assertEquals(-0.02, bound.estimate!!.toDouble())
            assertTrue(bound.lower!! < -0.02)
            assertFalse(bound.passesMargin)
        }
    }

    @Test fun `repository blocks retain repeats and cross stratum covariance`() {
        val keys = listOf(planned("a", "a", "simple"), planned("a", "a", "simple", 1),
            planned("b", "b", "simple"), planned("b", "b", "simple", 1), planned("a", "c", "complex"))
        val d = design(keys)
        val base = Scorecard.calculate(d, baseline, keys.map { trial(it, false, "1", baseline) })
        val cand = Scorecard.calculate(d, candidate, keys.map { trial(it, true, "1") })
        val bound = PairedBound.calculate(base, cand, false)
        // Repository a carries 0.5*2/4 + 0.5*1/1 = 0.75, b carries 0.25.
        assertEquals(2, bound.clusters)
        assertEquals(1.0, bound.estimate!!.toDouble())
        assertEquals(sqrt(2.0 * (0.75 * 0.75 + 0.25 * 0.25) * ln(80.0)), bound.radius!!, 1e-12)
        assertEquals(-1.0, bound.lower)
        assertNull(PairedBound.calculate(base, cand, true).lower)
    }

    @Test fun `missing pairs produce no bound`() {
        val keys = listOf(planned("a", "a", "simple"), planned("b", "b", "complex"))
        val d = design(keys)
        val base = Scorecard.calculate(d, baseline, keys.map { trial(it, true, "1", baseline) })
        val cand = Scorecard.calculate(d, candidate, listOf(trial(keys[0], true, "1")))
        val bound = PairedBound.calculate(base, cand, false)
        assertNull(bound.lower)
        assertTrue(bound.issues.any { it.code == EvaluationIssueCode.MissingTrial })
    }

    @Test fun `constant differences repeated delivery order and multiple selection remain conservative`() {
        val keys = (0 until 40).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        fun calculate(keys: List<PlannedTrial>, candidates: Set<io.astrolabe.id.Digest>): PairedBound {
            val d = EvaluationDesign(policy(), manifest, baseline, candidates, keys.reversed())
            val base = Scorecard.calculate(d, baseline, keys.map { trial(it, true, "2", baseline) }.reversed())
            val cand = Scorecard.calculate(d, candidate, keys.map { trial(it, true, "1") })
            return PairedBound.calculate(base, cand, false)
        }
        val bound = calculate(keys, setOf(candidate))
        val repeated = calculate(keys + keys.map { it.copy(key = it.key.copy(repetition = 1)) }, setOf(candidate))
        assertTrue(bound.constantDifferences)
        assertTrue(bound.radius!! > 0)
        assertEquals(bound.radius, repeated.radius)
        assertEquals(bound.lower, repeated.lower)
        assertFalse(bound.passesMargin)
        val family = calculate(keys, setOf(candidate, io.astrolabe.id.Digest.ofUtf8("candidate-2")))
        assertTrue(family.lower!! < bound.lower!!)
    }
}
