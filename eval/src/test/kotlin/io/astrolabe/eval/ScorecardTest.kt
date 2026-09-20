package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

internal fun decimal(value: String): BigDecimal = BigDecimal(value)
internal fun money(value: String): Money = Money("USD", decimal(value))
internal val baseline: Digest = Digest.ofUtf8("baseline-v1")
internal val candidate: Digest = Digest.ofUtf8("candidate-v1")
internal val matched: Digest = Digest.ofUtf8("start-acceptance-budget-environment-v1")
internal val manifest: Digest = Digest.ofUtf8("frozen-workload-v1")

internal fun policy(): ScorePolicy = ScorePolicy(
    "test-policy-v1", listOf(
        StratumPolicy("simple", decimal("0.5"), false, money("1"), money("5"), decimal("0.4")),
        StratumPolicy("complex", decimal("0.5"), true, money("2"), money("6"), decimal("0.4")),
    ), decimal("0.95"), 2, decimal("0.60"), money("10"), 1_000,
)

internal fun design(keys: List<PlannedTrial>, scorePolicy: ScorePolicy = policy()): EvaluationDesign =
    EvaluationDesign(scorePolicy, manifest, baseline, setOf(candidate), keys)

internal fun planned(repo: String, task: String, stratum: String, repeat: Int = 0): PlannedTrial =
    PlannedTrial(TrialKey(repo, task, repeat), stratum, matched)

internal fun trial(
    plan: PlannedTrial, accepted: Boolean, cost: String, config: Digest = candidate,
): EvaluationTrial = EvaluationTrial(
    plan.key, config, plan.matchedInputs, accepted, money(cost), true, 100, 20, 100, 0, 0, "fixture-v1",
)

class ScorecardTest {
    @Test fun `repetitions of one task keep the predeclared stratum`() {
        assertFailsWith<IllegalArgumentException> {
            design(listOf(planned("a", "task", "simple"), planned("a", "task", "complex", 1)))
        }
    }

    @Test fun `hand table prices failed attempts and uses per stratum costs`() {
        val keys = listOf(planned("a", "a", "simple"), planned("b", "b", "simple"), planned("a", "c", "complex"))
        val score = Scorecard.calculate(design(keys), candidate, listOf(
            trial(keys[0], true, "1"), trial(keys[1], false, "2"), trial(keys[2], true, "2"),
        ))
        assertEquals(75.0, score.quality!!.toDouble())
        assertEquals(75.0, score.economy!!.toDouble())
        assertEquals(75.0, score.eligibleScore!!.toDouble())
        assertEquals(3.0, score.strata.getValue("simple").costPerAccepted!!.amount.toDouble())
        assertEquals(240.0, score.strata.getValue("simple").tokensPerAccepted!!.toDouble())
        assertEquals(5.0, score.totalOnlineCost.amount.toDouble())
    }

    @Test fun `no accepted task and missing billing stay explicit`() {
        val keys = listOf(planned("a", "a", "simple"), planned("b", "b", "complex"))
        val rows = listOf(trial(keys[0], false, "1"), trial(keys[1], true, "2").copy(billingComplete = false))
        val score = Scorecard.calculate(design(keys), candidate, rows)
        assertNull(score.strata.getValue("simple").costPerAccepted)
        assertEquals(BigDecimal.ZERO, score.strata.getValue("simple").economy)
        assertTrue(score.totalOnlineCost.unknown)
        assertNull(score.economy)
        assertNull(score.eligibleScore)
        assertTrue(score.issues.any { it.code == EvaluationIssueCode.ZeroAccepted })
        assertTrue(score.issues.any { it.code == EvaluationIssueCode.UnknownBilling })
    }

    @Test fun `missing duplicate mismatched and foreign rows cannot improve eligibility`() {
        val keys = listOf(planned("a", "a", "simple"), planned("b", "b", "complex"))
        val good = keys.map { trial(it, true, "1") }
        for ((rows, issue) in listOf(
            good.take(1) to EvaluationIssueCode.MissingTrial,
            good + good.first() to EvaluationIssueCode.DuplicateTrial,
            listOf(good.first().copy(matchedInputs = manifest), good.last()) to EvaluationIssueCode.MismatchedInputs,
            listOf(good.first().copy(onlineCost = Money("EUR", BigDecimal.ONE)), good.last()) to EvaluationIssueCode.CurrencyMismatch,
            listOf(good.first().copy(configuration = baseline), good.last()) to EvaluationIssueCode.UnexpectedTrial,
        )) {
            val score = Scorecard.calculate(design(keys), candidate, rows)
            assertNull(score.eligibleScore)
            assertNull(score.quality)
            assertTrue(score.issues.any { it.code == issue }, issue.toString())
        }
    }

    @Test fun `floors safety and unknown money are independent vetoes`() {
        val keys = listOf(planned("a", "a", "simple"), planned("b", "b", "complex"))
        for ((change, issue) in listOf<Pair<(EvaluationTrial) -> EvaluationTrial, EvaluationIssueCode>>(
            { it: EvaluationTrial -> it.copy(invariantViolations = 1) } to EvaluationIssueCode.InvariantViolation,
            { it: EvaluationTrial -> it.copy(seriousRegressions = 1) } to EvaluationIssueCode.SeriousRegression,
            { it: EvaluationTrial -> it.copy(onlineCost = Money.unknown("USD")) } to EvaluationIssueCode.UnknownBilling,
        )) {
            val score = Scorecard.calculate(design(keys), candidate,
                listOf(change(trial(keys[0], true, "0")), trial(keys[1], true, "0")))
            assertNull(score.eligibleScore)
            assertTrue(score.issues.any { it.code == issue })
        }
    }

    @Test fun `weights policy ranges and trial numbers are validated`() {
        val p = policy()
        fun withStrata(strata: List<StratumPolicy>) = ScorePolicy("v1", strata, p.confidence,
            p.minClusters, p.costRatio, p.costCeiling, p.latencyCeilingMillis)
        assertFailsWith<IllegalArgumentException> { withStrata(p.strata.map { it.copy(weight = decimal("0.6")) }) }
        assertFailsWith<IllegalArgumentException> { withStrata(p.strata.map { it.copy(complex = false) }) }
        assertFailsWith<IllegalArgumentException> { p.strata.first().copy(costLow = money("6")) }
        assertFailsWith<IllegalArgumentException> { p.strata.first().copy(acceptanceFloor = decimal("1.01")) }
        assertFailsWith<IllegalArgumentException> { trial(planned("a", "a", "simple"), true, "-1") }
        assertFailsWith<IllegalArgumentException> { trial(planned("a", "a", "simple"), true, "1").copy(inputTokens = -1) }
    }

    @Test fun `snapshots permutations missing strata and large token sums`() {
        val keys = mutableListOf(planned("a", "a", "simple"), planned("b", "b", "complex"))
        val d = design(keys)
        val rows = keys.map { trial(it, true, "1").copy(inputTokens = Long.MAX_VALUE, outputTokens = Long.MAX_VALUE) }
        val score = Scorecard.calculate(d, candidate, rows)
        keys.clear()
        assertEquals(2, d.trials.size)
        assertEquals(Long.MAX_VALUE.toBigDecimal().multiply(BigDecimal(2)), score.strata.getValue("simple").tokensPerAccepted)
        assertEquals(score.inputDigest, Scorecard.calculate(d, candidate, rows.reversed()).inputDigest)
        assertFailsWith<UnsupportedOperationException> { (score.rows as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (d.candidates as MutableSet).clear() }
        val onlySimple = listOf(planned("a", "a", "simple"))
        val missing = Scorecard.calculate(design(onlySimple), candidate, onlySimple.map { trial(it, true, "1") })
        assertNull(missing.quality)
        assertNull(missing.eligibleScore)
        assertTrue(missing.issues.any { it.code == EvaluationIssueCode.MissingStratum })
    }

    @Test fun `seeded tables agree with independent double arithmetic oracle`() {
        val rng = java.util.Random(614)
        repeat(100) {
            val keys = (0 until 20).map { planned("r$it", "t", if (it < 10) "simple" else "complex") }
            val rows = keys.map { trial(it, rng.nextBoolean(), (rng.nextInt(6) + 1).toString()) }
            val score = Scorecard.calculate(design(keys), candidate, rows)
            val quality = (rows.take(10).count { it.accepted } + rows.drop(10).count { it.accepted }) * 5.0
            val economy = listOf(rows.take(10), rows.drop(10)).mapIndexed { index, group ->
                val passed = group.count { it.accepted }
                if (passed == 0) 0.0 else {
                    val cost = group.sumOf { it.onlineCost.amount.toDouble() } / passed
                    ((5.0 + index - cost) / 4.0).coerceIn(0.0, 1.0) * 100.0
                }
            }.average()
            assertEquals(quality, score.quality!!.toDouble(), 1e-12)
            assertEquals(economy, score.economy!!.toDouble(), 1e-12)
        }
    }
}
