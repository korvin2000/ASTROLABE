package io.astrolabe.eval

import io.astrolabe.id.Digest
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.*

/** Fixed before results in audit/OUT-OF-ORDER-P6.1.4.md; this is method validation, not live evaluation. */
class InferenceSimulationTest {
    private data class Model(val name: String, val delta: Double, val repositories: Int, val seed: Long,
        val unequal: Boolean = false, val rareComplex: Boolean = false, val family: Int = 1)

    @Test fun `predeclared clustered models cover truth and control false eligibility`() {
        val models = listOf(
            Model("null", 0.0, 80, 614), Model("margin", -0.02, 80, 615), Model("harm", -0.10, 80, 616),
            Model("improvement", 0.30, 400, 614), Model("unequal-margin", -0.02, 80, 615, unequal = true),
            Model("rare-complex-margin", -0.02, 80, 616, rareComplex = true),
            Model("family-margin", -0.02, 80, 614, family = 4),
        )
        for (model in models) simulate(model)
    }

    private fun simulate(model: Model) {
        val random = Random(model.seed)
        val keys = (0 until model.repositories).flatMap { repo ->
            (0 until if (model.unequal) 1 + repo % 5 else 1).flatMap { repetition -> buildList {
                add(planned("r$repo", "s", "simple", repetition))
                if (!model.rareComplex || repo % 10 == 0) add(planned("r$repo", "c", "complex", repetition))
            } }
        }
        val p = policy()
        val simPolicy = ScorePolicy("simulation-v1", p.strata.map { it.copy(acceptanceFloor = decimal("0.1")) },
            p.confidence, p.minClusters, p.costRatio, p.costCeiling, p.latencyCeilingMillis)
        val candidates = (0 until model.family).map { Digest.ofUtf8("simulation-candidate-$it") }
        val d = EvaluationDesign(simPolicy, manifest, baseline, candidates.toSet(), keys)
        val complexCount = keys.count { it.stratum == "complex" }
        val simpleCount = keys.size - complexCount
        val weights = listOf(
            keys.map { if (it.stratum == "complex") 0.5 / complexCount else 0.5 / simpleCount },
            keys.map { if (it.stratum == "complex") 1.0 / complexCount else 0.0 },
        )
        val oracleRadii = weights.map { w ->
            val mass = keys.indices.groupBy { keys[it].key.repository }.values.map { ids -> ids.sumOf { w[it] } }
            sqrt(2 * mass.sumOf { it * it } * ln(4.0 * model.family / 0.05))
        }
        val sums = DoubleArray(2 * model.family)
        val squares = DoubleArray(sums.size)
        var uncovered = 0
        var noninferiority = 0
        var numericalPass = 0
        val campaigns = 1_000
        repeat(campaigns) {
            val pairs = Array(model.repositories) {
                if (model.family == 1) {
                    val u = random.nextDouble()
                    when {
                        u < 0.4 + model.delta / 2 -> booleanArrayOf(false, true)
                        u < 0.8 -> booleanArrayOf(true, false)
                        u < 0.9 -> booleanArrayOf(true, true)
                        else -> booleanArrayOf(false, false)
                    }
                } else BooleanArray(model.family + 1) { arm -> random.nextDouble() < if (arm == 0) 0.5 else 0.48 }
            }
            val baseRows = keys.map { trial(it, pairs[it.key.repository.substring(1).toInt()][0], "1", baseline) }
            val base = Scorecard.calculate(d, baseline, baseRows)
            var campaignUncovered = false
            var campaignNoninferiority = false
            var campaignPass = false
            for ((arm, config) in candidates.withIndex()) {
                val rows = keys.map { trial(it, pairs[it.key.repository.substring(1).toInt()][arm + 1], "0.4", config) }
                val cand = Scorecard.calculate(d, config, rows)
                val ev = EvaluationEvidence(config, manifest, EvaluationOrigin.Synthetic, EvidenceCheck.Pass,
                    EvidenceCheck.Pass, EvidenceCheck.Pass, "simulation-${model.name}")
                val report = PromotionReport.evaluate(base, cand, ev, money("0"), money("0"))
                for ((endpoint, bound) in listOf(report.overall, report.complex).withIndex()) {
                    val oracle = keys.indices.sumOf { index ->
                        weights[endpoint][index] * ((if (rows[index].accepted) 1 else 0) - (if (baseRows[index].accepted) 1 else 0))
                    }
                    assertEquals(oracle, bound.estimate!!.toDouble(), 1e-12)
                    assertEquals(oracleRadii[endpoint], bound.radius!!, 1e-12)
                    assertTrue(bound.lower!! <= (oracle - oracleRadii[endpoint]).coerceAtLeast(-1.0) + 1e-12)
                    campaignUncovered = campaignUncovered || bound.lower > model.delta
                    sums[arm * 2 + endpoint] += bound.estimate.toDouble()
                    squares[arm * 2 + endpoint] += bound.estimate.toDouble().let { it * it }
                }
                campaignNoninferiority = campaignNoninferiority || (report.overall.passesMargin && report.complex.passesMargin)
                campaignPass = campaignPass || report.numericalPass
                assertNotEquals(PromotionVerdict.EligibleForReview, report.verdict)
            }
            if (campaignUncovered) uncovered++
            if (campaignNoninferiority) noninferiority++
            if (campaignPass) numericalPass++
        }
        var maximumBias = 0.0
        for (index in sums.indices) {
            val mean = sums[index] / campaigns
            val se = sqrt((squares[index] / campaigns - mean * mean).coerceAtLeast(0.0) / (campaigns - 1))
            maximumBias = maxOf(maximumBias, abs(mean - model.delta))
            assertTrue(abs(mean - model.delta) <= 4 * se + 0.005, "${model.name} bias=$mean truth=${model.delta} se=$se")
        }
        assertTrue(wilsonUpper(uncovered, campaigns) <= 0.05, "${model.name} uncovered=$uncovered")
        if (model.delta <= -0.02) {
            assertTrue(wilsonUpper(noninferiority, campaigns) <= 0.05, "${model.name} false noninferiority=$noninferiority")
            assertTrue(wilsonUpper(numericalPass, campaigns) <= 0.05, "${model.name} false eligibility=$numericalPass")
        }
        println("SIM ${model.name}: n=$campaigns seed=${model.seed} uncovered=$uncovered upper95=${wilsonUpper(uncovered, campaigns)} " +
            "bothBounds=$noninferiority numericalPass=$numericalPass maxAbsBias=$maximumBias")
    }

    private fun wilsonUpper(events: Int, trials: Int): Double {
        val z = 1.6448536269514722
        val p = events.toDouble() / trials
        return (p + z * z / (2 * trials) + z * sqrt(p * (1 - p) / trials + z * z / (4.0 * trials * trials))) /
            (1 + z * z / trials)
    }
}
