package io.astrolabe.eval

import org.junit.jupiter.api.Test
import kotlin.test.*

internal fun evidence(origin: EvaluationOrigin = EvaluationOrigin.Synthetic): EvaluationEvidence = EvaluationEvidence(
    candidate, manifest, origin, EvidenceCheck.Pass, EvidenceCheck.Pass, EvidenceCheck.Pass, "fixture-attestation-v1",
)

class PromotionTest {
    @Test fun `economic ratio and cost ceiling compare exact decimal amounts`() {
        val keys = (0 until 20).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        val d = design(keys)
        val base = Scorecard.calculate(d, baseline, keys.map { trial(it, true, "1", baseline) })
        fun check(cost: String) = PromotionReport.evaluate(base,
            Scorecard.calculate(d, candidate, keys.map { trial(it, true, cost) }), evidence(), money("0"), money("0"))
        assertFalse(check("0.60").issues.any { it.code == EvaluationIssueCode.EconomicPolicy })
        assertTrue(check("0.60000000000000000000000000000000001").issues.any { it.code == EvaluationIssueCode.EconomicPolicy })
        assertFalse(check("10").issues.any { it.code == EvaluationIssueCode.CostCeiling })
        assertTrue(check("10.00000000000000000000000000000000001").issues.any { it.code == EvaluationIssueCode.CostCeiling })
    }

    private fun improved(): Pair<Scorecard, Scorecard> {
        val keys = (0 until 200).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        val d = design(keys)
        return Scorecard.calculate(d, baseline, keys.mapIndexed { i, key -> trial(key, i % 4 < 2, "2", baseline) }) to
            Scorecard.calculate(d, candidate, keys.map { trial(it, true, "1") })
    }

    @Test fun `synthetic success stays inconclusive while numerical conditions are visible`() {
        val (base, cand) = improved()
        val result = PromotionReport.evaluate(base, cand, evidence(), money("0"), money("30"))
        assertTrue(result.numericalPass)
        assertEquals(PromotionVerdict.Inconclusive, result.verdict)
        assertEquals(10.0, result.repaymentAcceptedTasks!!.toDouble())
        assertTrue(result.issues.any { it.code == EvaluationIssueCode.SyntheticEvidence })
        assertEquals(PromotionVerdict.EligibleForReview,
            PromotionReport.evaluate(base, cand, evidence(EvaluationOrigin.Measured), money("0"), money("30")).verdict)
    }

    @Test fun `every external integrity requirement remains a separate gate`() {
        val (base, cand) = improved()
        for ((ev, code) in listOf(
            evidence(EvaluationOrigin.Measured).copy(integrity = EvidenceCheck.Unknown) to EvaluationIssueCode.Integrity,
            evidence(EvaluationOrigin.Measured).copy(independence = EvidenceCheck.Fail) to EvaluationIssueCode.Independence,
            evidence(EvaluationOrigin.Measured).copy(mandatoryControls = EvidenceCheck.Fail) to EvaluationIssueCode.MandatoryControls,
            evidence(EvaluationOrigin.Measured).copy(manifest = null) to EvaluationIssueCode.EvidenceMismatch,
            evidence(EvaluationOrigin.Measured).copy(configuration = baseline) to EvaluationIssueCode.EvidenceMismatch,
        )) {
            val result = PromotionReport.evaluate(base, cand, ev, money("0"), money("0"))
            assertNotEquals(PromotionVerdict.EligibleForReview, result.verdict)
            assertTrue(result.issues.any { it.code == code })
        }
    }

    @Test fun `aggregate gain cannot hide observed complex loss or a ceiling violation`() {
        val keys = (0 until 200).flatMap { listOf(planned("r$it", "s", "simple"), planned("r$it", "c", "complex")) }
        val d = design(keys)
        val base = Scorecard.calculate(d, baseline, keys.mapIndexed { i, key ->
            trial(key, key.stratum == "complex" || i % 4 == 0, "2", baseline)
        })
        val rows = keys.mapIndexed { i, key -> trial(key, key.stratum == "simple" || i % 10 != 1, "1") }
        val cand = Scorecard.calculate(d, candidate, rows)
        assertTrue(cand.quality!! > base.quality!!)
        val loss = PromotionReport.evaluate(base, cand, evidence(), money("0"), money("0"))
        assertTrue(loss.issues.any { it.code == EvaluationIssueCode.ObservedComplexLoss })
        for ((row, code) in listOf(
            rows[0].copy(latencyMillis = 1_001) to EvaluationIssueCode.LatencyCeiling,
            rows[0].copy(latencyMillis = null) to EvaluationIssueCode.UnknownLatency,
            rows[0].copy(onlineCost = money("100000")) to EvaluationIssueCode.CostCeiling,
        )) {
            val result = PromotionReport.evaluate(base, Scorecard.calculate(d, candidate, listOf(row) + rows.drop(1)),
                evidence(), money("0"), money("0"))
            assertTrue(result.issues.any { it.code == code }, code.toString())
        }
    }

    @Test fun `undefined repayment never becomes zero investment or savings`() {
        val (base, cand) = improved()
        val unknown = PromotionReport.evaluate(base, cand, evidence(), money("0"), io.astrolabe.provider.Money.unknown("USD"))
        assertNull(unknown.repaymentAcceptedTasks)
        assertTrue(unknown.investmentIssues.any { it.code == EvaluationIssueCode.UnknownInvestment })
        val sameCost = Scorecard.calculate(base.design, candidate, base.rows.map { it.copy(configuration = candidate) })
        val noSaving = PromotionReport.evaluate(base, sameCost, evidence(), money("0"), money("10"))
        assertNull(noSaving.repaymentAcceptedTasks)
        assertTrue(noSaving.investmentIssues.any { it.code == EvaluationIssueCode.NoPositiveSaving })
    }
}
