package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import java.math.BigDecimal

public enum class EvidenceCheck { Pass, Fail, Unknown }
public enum class EvaluationOrigin { Synthetic, Measured }

/** Caller-supplied attestation. P6.1.2 verifies provenance, freeze timing, hidden acceptance and holdout integrity. */
public data class EvaluationEvidence(
    val configuration: Digest,
    val manifest: Digest?,
    val origin: EvaluationOrigin,
    val integrity: EvidenceCheck,
    val independence: EvidenceCheck,
    val mandatoryControls: EvidenceCheck,
    val provenance: String,
) {
    init { require(provenance.isNotBlank()) }
}

/** Even EligibleForReview is diagnostic, never an adoption permission or an action. */
public enum class PromotionVerdict { EligibleForReview, KeepBaseline, Inconclusive, InvalidInput }

public class PromotionReport private constructor(
    public val baseline: Scorecard,
    public val candidate: Scorecard,
    public val evidence: EvaluationEvidence,
    public val overall: PairedBound,
    public val complex: PairedBound,
    public val baselineWeightedCost: Money?,
    public val candidateWeightedCost: Money?,
    public val baselineInvestment: Money,
    public val candidateInvestment: Money,
    public val repaymentAcceptedTasks: BigDecimal?,
    investmentIssues: List<EvaluationIssue>,
    public val numericalPass: Boolean,
    public val verdict: PromotionVerdict,
    issues: List<EvaluationIssue>,
) {
    public val issues: List<EvaluationIssue> = immutable(issues.distinct())
    public val investmentIssues: List<EvaluationIssue> = immutable(investmentIssues)
    public val inputDigest: Digest = fingerprint("promotion-input", listOf(
        baseline.inputDigest, candidate.inputDigest, evidence.configuration, evidence.manifest, evidence.origin,
        evidence.integrity, evidence.independence, evidence.mandatoryControls, evidence.provenance,
        baselineInvestment.currency, baselineInvestment.amount, baselineInvestment.unknown,
        candidateInvestment.currency, candidateInvestment.amount, candidateInvestment.unknown,
    ))

    public companion object {
        @JvmStatic
        public fun evaluate(
            baseline: Scorecard,
            candidate: Scorecard,
            evidence: EvaluationEvidence,
            baselineInvestment: Money,
            candidateInvestment: Money,
        ): PromotionReport {
            val overall = PairedBound.calculate(baseline, candidate, false)
            val complex = PairedBound.calculate(baseline, candidate, true)
            val policy = baseline.design.policy
            val issues = (baseline.issues + candidate.issues + overall.issues + complex.issues).toMutableList()
            val valid = issues.none { it.code in invalidCodes }
            if (valid && overall.estimate != null) {
                for (stratum in policy.strata.filter { it.complex && it.weight.signum() > 0 }) {
                    if (candidate.strata.getValue(stratum.id).accepted < baseline.strata.getValue(stratum.id).accepted)
                        issues += EvaluationIssue(EvaluationIssueCode.ObservedComplexLoss, stratum.id)
                }
            }
            for (bound in listOf(overall, complex)) {
                if (!bound.passesMargin) issues += EvaluationIssue(EvaluationIssueCode.BoundNotMet,
                    if (bound.complexOnly) "complex lower bound must exceed -0.02" else "overall lower bound must exceed -0.02")
            }
            val baseCost = weightedCost(baseline)
            val candCost = weightedCost(candidate)
            if (valid && baseCost != null && candCost != null && overall.estimate != null) {
                val delta = acceptanceDifference(baseline, candidate, false).numerator.signum()
                val left = candCost.numerator.multiply(baseCost.denominator)
                val right = baseCost.numerator.multiply(candCost.denominator)
                if (!((delta >= 0 && left <= right.multiply(policy.costRatio)) || (delta > 0 && left <= right)))
                    issues += EvaluationIssue(EvaluationIssueCode.EconomicPolicy, "quality/cost tradeoff not met")
            }
            for (stratum in policy.strata.filter { it.weight.signum() > 0 }) {
                val score = candidate.strata.getValue(stratum.id)
                if (!score.onlineCost.unknown && score.accepted > 0 &&
                    score.onlineCost.amount > policy.costCeiling.amount.multiply(score.accepted.toBigDecimal()))
                    issues += EvaluationIssue(EvaluationIssueCode.CostCeiling, stratum.id)
            }
            if (candidate.rows.any { it.latencyMillis == null })
                issues += EvaluationIssue(EvaluationIssueCode.UnknownLatency, "complete-task latency missing")
            if (candidate.rows.any { it.latencyMillis != null && it.latencyMillis > policy.latencyCeilingMillis })
                issues += EvaluationIssue(EvaluationIssueCode.LatencyCeiling, "maximum complete-task latency exceeded")
            val numericalPass = issues.isEmpty()
            if (evidence.configuration != candidate.configuration || evidence.manifest != baseline.design.manifest)
                issues += EvaluationIssue(EvaluationIssueCode.EvidenceMismatch, "configuration or frozen manifest")
            for ((check, code) in listOf(evidence.integrity to EvaluationIssueCode.Integrity,
                evidence.independence to EvaluationIssueCode.Independence,
                evidence.mandatoryControls to EvaluationIssueCode.MandatoryControls)) {
                if (check != EvidenceCheck.Pass) issues += EvaluationIssue(code, check.name)
            }
            if (evidence.origin == EvaluationOrigin.Synthetic)
                issues += EvaluationIssue(EvaluationIssueCode.SyntheticEvidence, "synthetic results cannot support adoption")

            validMoney(baselineInvestment); validMoney(candidateInvestment)
            val investmentIssues = mutableListOf<EvaluationIssue>()
            val saving = if (baseCost == null || candCost == null) null else Fraction(
                baseCost.numerator.multiply(candCost.denominator) - candCost.numerator.multiply(baseCost.denominator),
                baseCost.denominator.multiply(candCost.denominator),
            )
            if (baselineInvestment.unknown || candidateInvestment.unknown)
                investmentIssues += EvaluationIssue(EvaluationIssueCode.UnknownInvestment, "one-off investment")
            if (baselineInvestment.currency != policy.currency || candidateInvestment.currency != policy.currency)
                investmentIssues += EvaluationIssue(EvaluationIssueCode.CurrencyMismatch, "one-off investment")
            if (saving == null || saving.numerator.signum() <= 0)
                investmentIssues += EvaluationIssue(EvaluationIssueCode.NoPositiveSaving, "weighted cost per accepted task")
            val repayment = if (investmentIssues.isNotEmpty()) null else
                (candidateInvestment.amount - baselineInvestment.amount).max(BigDecimal.ZERO)
                    .multiply(saving!!.denominator).divide(saving.numerator, arithmetic)
            val verdict = when {
                issues.any { it.code in invalidCodes } -> PromotionVerdict.InvalidInput
                issues.isEmpty() -> PromotionVerdict.EligibleForReview
                issues.any { it.code in failedCodes || (it.code in evidenceCodes && it.detail == EvidenceCheck.Fail.name) } ->
                    PromotionVerdict.KeepBaseline
                else -> PromotionVerdict.Inconclusive
            }
            fun cost(value: Fraction?): Money? = value?.let {
                Money(policy.currency, it.numerator.divide(it.denominator, arithmetic))
            }
            return PromotionReport(baseline, candidate, evidence, overall, complex, cost(baseCost), cost(candCost),
                baselineInvestment, candidateInvestment, repayment, investmentIssues, numericalPass, verdict, issues)
        }
    }
}

private val invalidCodes = setOf(EvaluationIssueCode.DuplicateTrial, EvaluationIssueCode.UnexpectedTrial,
    EvaluationIssueCode.MissingTrial, EvaluationIssueCode.MismatchedInputs, EvaluationIssueCode.CurrencyMismatch)
private val failedCodes = setOf(EvaluationIssueCode.ZeroAccepted, EvaluationIssueCode.QualityFloor,
    EvaluationIssueCode.InvariantViolation, EvaluationIssueCode.SeriousRegression, EvaluationIssueCode.ObservedComplexLoss,
    EvaluationIssueCode.EconomicPolicy, EvaluationIssueCode.CostCeiling, EvaluationIssueCode.LatencyCeiling)
private val evidenceCodes = setOf(EvaluationIssueCode.Integrity, EvaluationIssueCode.Independence,
    EvaluationIssueCode.MandatoryControls)

private fun weightedCost(score: Scorecard): Fraction? {
    if (score.quality == null || score.issues.any { it.code in invalidCodes } || score.totalOnlineCost.unknown) return null
    var numerator = BigDecimal.ZERO
    var denominator = BigDecimal.ONE
    for (stratum in score.design.policy.strata.filter { it.weight.signum() > 0 }) {
        val slice = score.strata.getValue(stratum.id)
        if (slice.accepted == 0 || slice.onlineCost.unknown) return null
        val accepted = slice.accepted.toBigDecimal()
        numerator = numerator.multiply(accepted) + stratum.weight.multiply(slice.onlineCost.amount).multiply(denominator)
        denominator = denominator.multiply(accepted)
    }
    return Fraction(numerator, denominator)
}
