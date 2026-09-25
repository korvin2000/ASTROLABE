package io.astrolabe.eval

import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.telemetry.Quantities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PromotionDecisionTest {
    private val base = Config(profiles = FakeProfiles.all)
    private val baselineArm = EvalArms.configure(EvalArm.Precompile, "off", base)
    private val candidateArm = EvalArms.configure(EvalArm.Precompile, "on", base)
    private val baseline = baselineArm.attempt!!
    private val candidate = candidateArm.attempt!!
    private val policy = ScorePolicy("score-1", listOf(
        StratumPolicy("hard", BigDecimal("0.6"), true, usd("1"), usd("10"), BigDecimal("0.5")),
        StratumPolicy("easy", BigDecimal("0.4"), false, usd("0.5"), usd("5"), BigDecimal("0.5")),
    ), BigDecimal("0.95"), 2, BigDecimal("0.6"), usd("100"), 1_000_000)

    @Test
    fun `trial rows from accounting include every call and keep missing usage unknown`() {
        val key = TrialKey("r1", "t1", 0)
        val known = Trials.fromCalls(key, candidate.fingerprint, inputs(key), true, listOf(call("1.5", 100, 20), call("0.5", 40, 5)),
            "USD", 900, 0, 0, "synthetic")
        assertEquals(0, BigDecimal("2.0").compareTo(known.onlineCost.amount)); assertFalse(known.onlineCost.unknown)
        assertTrue(known.billingComplete); assertEquals(140L, known.inputTokens); assertEquals(25L, known.outputTokens)

        val missing = CallAccount("inv-3", ids, "main", null, Money.unknown("USD"), "2026-01-01", Quantities(null, null, null, null), null, Instant.EPOCH)
        val partial = Trials.fromCalls(key, candidate.fingerprint, inputs(key), true, listOf(call("1.5", 100, 20), missing), "USD", 900, 0, 0, "synthetic")
        assertTrue(partial.onlineCost.unknown); assertFalse(partial.billingComplete)
        assertNull(partial.inputTokens); assertNull(partial.outputTokens)
        val euro = Trials.fromCalls(key, candidate.fingerprint, inputs(key), true, listOf(call("1", 1, 1, "EUR")), "USD", 900, 0, 0, "synthetic")
        assertTrue(euro.onlineCost.unknown)
    }

    @Test
    fun `synthetic trial tables score Q, E and the eligible score and the decision keeps what the report cannot see`() {
        val repos = 2
        val design = design(repos)
        val before = Scorecard.calculate(design, baseline.fingerprint, table(design, baseline, cost = "5"))
        val cand = Scorecard.calculate(design, candidate.fingerprint, table(design, candidate, cost = "2"))
        // Q = 100; E_hard = 100·(10−2)/9, E_easy = 100·(5−2)/4.5, E = 80; eligible = 0.6·100 + 0.4·80 = 92.
        assertEquals(0, BigDecimal(100).compareTo(cand.quality))
        assertEquals(0, BigDecimal(80).compareTo(cand.economy!!.setScale(20, RoundingMode.HALF_EVEN)))
        assertEquals(0, BigDecimal(92).compareTo(cand.eligibleScore!!.setScale(20, RoundingMode.HALF_EVEN)))

        val manifest = manifest(repos, listOf(baselineArm, candidateArm))
        val report = PromotionReport.evaluate(before, cand, evidence(manifest, EvaluationOrigin.Synthetic), usd("0"), usd("0"))
        assertEquals(PromotionVerdict.Inconclusive, report.verdict, "synthetic results never support adoption")
        assertEquals(PromotionVerdict.Inconclusive, PromotionDecision.decide(report, fixtures(), manifest).verdict)

        val red = FixtureReport("B1", listOf(FixtureResult("C", "FX-50 unseen body()", listOf("FX-50"), FixtureStatus.Failed, "x")) + measured())
        val kept = PromotionDecision.decide(report, red, manifest)
        assertEquals(PromotionVerdict.KeepBaseline, kept.verdict)
        assertTrue(kept.issues.any { it.code == EvaluationIssueCode.InvariantViolation && "unseen content 1" in it.detail })
        val unmeasured = PromotionDecision.decide(report, null, manifest)
        assertEquals(PromotionVerdict.Inconclusive, unmeasured.verdict)
        assertTrue(unmeasured.issues.any { it.code == EvaluationIssueCode.UnmeasuredInvariant })

        val wider = manifest(repos, listOf(baselineArm, candidateArm, EvalArms.configure(EvalArm.LanguageService, "on", base)))
        val undeclared = PromotionDecision.decide(PromotionReport.evaluate(before, cand, evidence(wider, EvaluationOrigin.Synthetic), usd("0"), usd("0")), fixtures(), wider)
        assertTrue(undeclared.issues.any { it.code == EvaluationIssueCode.MultipleSelection })

        // A stratum with no accepted trial: E_s = 0 and a floor failure, so the baseline is kept.
        val lost = Scorecard.calculate(design, candidate.fingerprint, table(design, candidate, cost = "2", acceptHard = false))
        assertEquals(0, BigDecimal.ZERO.compareTo(lost.strata.getValue("hard").economy))
        assertTrue(lost.issues.map { it.code }.containsAll(listOf(EvaluationIssueCode.ZeroAccepted, EvaluationIssueCode.QualityFloor)))
        assertEquals(PromotionVerdict.KeepBaseline,
            PromotionReport.evaluate(before, lost, evidence(manifest, EvaluationOrigin.Measured), usd("0"), usd("0")).verdict)

        // Missing billing is unknown: no weighted cost, no economic claim.
        val unbilled = Scorecard.calculate(design, candidate.fingerprint, table(design, candidate, cost = "2", billed = false))
        val unknown = PromotionReport.evaluate(before, unbilled, evidence(manifest, EvaluationOrigin.Measured), usd("0"), usd("0"))
        assertNull(unknown.candidateWeightedCost)
        assertTrue(unknown.issues.any { it.code == EvaluationIssueCode.UnknownBilling })
        assertEquals(PromotionVerdict.Inconclusive, unknown.verdict)
    }

    @Test
    fun `paired bound helper clusters by repository and clears the margin only with enough clusters`() {
        fun bound(repos: Int, complexOnly: Boolean = false): PairedBound {
            val design = design(repos)
            return PairedBound.calculate(
                Scorecard.calculate(design, baseline.fingerprint, table(design, baseline, "5", acceptHard = false, acceptEasy = false)),
                Scorecard.calculate(design, candidate.fingerprint, table(design, candidate, "5")), complexOnly)
        }
        // Every pair improves by one: estimate 1, radius sqrt(2·Σ(1/N)²·ln 80).
        val ten = bound(10)
        assertEquals(10, ten.clusters); assertEquals(0, BigDecimal.ONE.compareTo(ten.estimate))
        assertTrue(ten.radius!! >= Math.sqrt(2.0 / 10 * Math.log(80.0)) && ten.lower!! <= 1.0 - ten.radius!!)
        assertTrue(ten.passesMargin && ten.constantDifferences)
        assertFalse(bound(8).passesMargin, "eight repositories leave the lower bound below -0.02")
        assertTrue(bound(10, complexOnly = true).passesMargin)
        val one = bound(1)
        assertNull(one.lower); assertTrue(one.issues.any { it.code == EvaluationIssueCode.InsufficientClusters })
    }

    @Test
    fun `campaign evidence, one-off investment repayment and the exported decision`() {
        val repos = 2
        val manifest = manifest(repos, listOf(baselineArm, candidateArm))
        val clean = CampaignIntegrity.check(IntegrityInput(emptyList(), emptyList(), emptyList(), emptyList(), null, emptyList(), 0))
        val evidence = PromotionEvidence.of(manifest, candidate, clean, EvaluationOrigin.Measured, "pilot")
        assertEquals(listOf(EvidenceCheck.Pass, EvidenceCheck.Pass, EvidenceCheck.Pass),
            listOf(evidence.integrity, evidence.independence, evidence.mandatoryControls))
        val reserveOff = EvalArms.configure(EvalArm.Reserve, "off", base).attempt!!
        assertEquals(EvidenceCheck.Fail, PromotionEvidence.of(manifest, reserveOff, clean, EvaluationOrigin.Measured, "pilot").mandatoryControls)

        val investment = Investments.total(listOf(Investment(InvestmentKind.Index, usd("20")), Investment(InvestmentKind.Calibration, usd("10"))), "USD")
        assertEquals(0, BigDecimal(30).compareTo(investment.amount)); assertFalse(investment.unknown)
        assertTrue(Investments.total(listOf(Investment(InvestmentKind.Memory, Money.unknown("USD"))), "USD").unknown)

        val design = design(repos)
        val report = PromotionReport.evaluate(Scorecard.calculate(design, baseline.fingerprint, table(design, baseline, "5")),
            Scorecard.calculate(design, candidate.fingerprint, table(design, candidate, "2")), evidence, usd("0"), investment)
        // Weighted saving 3 per accepted task repays 30 after 10 accepted tasks.
        assertEquals(0, BigDecimal(10).compareTo(report.repaymentAcceptedTasks))
        val json = Json.parseToJsonElement(PromotionDecision.decide(report, fixtures(), manifest).json()).jsonObject
        assertEquals(0, BigDecimal(10).compareTo(BigDecimal(json.getValue("repaymentAcceptedTasks").jsonPrimitive.content)))
        assertEquals(report.verdict.name, json.getValue("reportVerdict").jsonPrimitive.content)
        assertEquals(0, BigDecimal(92).compareTo(BigDecimal(json.getValue("candidate").jsonObject.getValue("eligibleScore").jsonPrimitive.content)
            .setScale(20, RoundingMode.HALF_EVEN)))
    }

    private val ids = Identities(WorkId("w1"), AttemptId("a1"))

    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    private fun inputs(key: TrialKey) = Digest.ofUtf8("inputs/${key.repository}/${key.task}/${key.repetition}")

    private fun call(amount: String, input: Long, output: Long, currency: String = "USD") = CallAccount(
        "inv-$amount-$input", ids, "main",
        BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to input, BillingDimension.OUTPUT to output), UsageProvenance("fake", "m", "p")),
        Money(currency, BigDecimal(amount)), "2026-01-01", Quantities(null, input, input + output, null), false, Instant.EPOCH,
    )

    private fun design(repos: Int) = EvaluationDesign(policy, manifest(repos, listOf(baselineArm, candidateArm)).fingerprint,
        baseline.fingerprint, setOf(candidate.fingerprint), (1..repos).flatMap { r ->
            listOf("hard", "easy").map { s -> TrialKey("r$r", "$s-$r", 0).let { PlannedTrial(it, s, inputs(it)) } }
        })

    private fun table(design: EvaluationDesign, attempt: AttemptConfig, cost: String, acceptHard: Boolean = true,
                      acceptEasy: Boolean = true, billed: Boolean = true) = design.trials.map { planned ->
        val accepted = if (planned.stratum == "hard") acceptHard else acceptEasy
        val calls = if (billed) listOf(call(cost, 1_000, 100)) else listOf(call(cost, 1_000, 100).copy(usage = null, money = Money.unknown("USD")))
        Trials.fromCalls(planned.key, attempt.fingerprint, planned.matchedInputs, accepted, calls, "USD", 1_000, 0, 0, "synthetic table")
    }

    private fun manifest(repos: Int, arms: List<ArmConfig>): CampaignManifest {
        val policy = WorkloadPolicy("w1", mapOf("hard" to true, "easy" to false), setOf(WorkloadGrouping.Repository), emptyList(),
            emptyMap(), emptyMap(), WorkloadPartition.entries.map { WorkloadQuota(it, null, BigDecimal.ZERO, BigDecimal(100), BigDecimal.ZERO, BigDecimal.ONE) })
        val tasks = (1..repos).flatMap { r -> listOf("hard", "easy").map { s -> WorkloadTask("$s-$r", setOf(0), "r$r", null, s, BigDecimal.ONE, null) } }
        val design = WorkloadDesign(policy, tasks)
        return CampaignManifest("decision", "0.1.0", emptyList(), arms, design,
            tasks.associate { WorkloadTrialKey(it.id, 0) to WorkloadPartition.Final }, MemoryMode.Cold)
    }

    private fun evidence(manifest: CampaignManifest, origin: EvaluationOrigin) = EvaluationEvidence(candidate.fingerprint, manifest.fingerprint,
        origin, EvidenceCheck.Pass, EvidenceCheck.Pass, EvidenceCheck.Pass, "synthetic")

    /** One passing fixture per invariant: every invariant measured and zero. */
    private fun measured() = Invariant.entries.map { FixtureResult("C", "${it.fixtures.first()} ok()", listOf(it.fixtures.first()), FixtureStatus.Passed, null) }

    private fun fixtures() = FixtureReport("B1", measured())
}
