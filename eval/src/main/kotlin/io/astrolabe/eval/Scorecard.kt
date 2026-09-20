package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.util.Collections

@ConsistentCopyVisibility
public data class StratumScore internal constructor(
    val trials: Int,
    val accepted: Int,
    val acceptance: BigDecimal?,
    val onlineCost: Money,
    val costPerAccepted: Money?,
    val economy: BigDecimal?,
    val tokensPerAccepted: BigDecimal?,
)

/** Descriptive statistics; valid arithmetic alone is not a promotion decision. */
public class Scorecard private constructor(
    public val design: EvaluationDesign,
    public val configuration: Digest,
    rows: List<EvaluationTrial>,
    strata: Map<String, StratumScore>,
    public val quality: BigDecimal?,
    public val economy: BigDecimal?,
    public val eligibleScore: BigDecimal?,
    public val totalOnlineCost: Money,
    issues: List<EvaluationIssue>,
) {
    public val rows: List<EvaluationTrial> = immutable(rows)
    public val strata: Map<String, StratumScore> = Collections.unmodifiableMap(LinkedHashMap(strata))
    public val issues: List<EvaluationIssue> = immutable(issues)
    public val inputDigest: Digest = fingerprint("scorecard-input", listOf(design.fingerprint, configuration, trialFingerprint(rows)))

    public companion object {
        @JvmStatic
        public fun calculate(design: EvaluationDesign, configuration: Digest, trials: List<EvaluationTrial>): Scorecard {
            require(configuration == design.baseline || configuration in design.candidates)
            val rows = trials.sortedWith(rowOrder)
            val policy = design.policy
            val issues = validateRows(design, configuration, rows).toMutableList()
            val valid = issues.isEmpty()
            val plans = design.trials.associateBy { it.key }
            val strata = policy.strata.associate { stratum ->
                val group = rows.filter { plans[it.key]?.stratum == stratum.id }
                val accepted = group.count { it.accepted }
                val cost = totalCost(group, policy.currency)
                val rate = if (group.isEmpty()) null else accepted.toBigDecimal().divide(group.size.toBigDecimal(), arithmetic)
                val perAccepted = if (accepted == 0 || cost.unknown) null
                    else Money(policy.currency, cost.amount.divide(accepted.toBigDecimal(), arithmetic))
                val economy = when {
                    accepted == 0 -> BigDecimal.ZERO
                    perAccepted == null -> null
                    else -> stratum.costHigh.amount.subtract(perAccepted.amount)
                        .divide(stratum.costHigh.amount.subtract(stratum.costLow.amount), arithmetic)
                        .coerceIn(BigDecimal.ZERO, BigDecimal.ONE).multiply(BigDecimal(100))
                }
                if (stratum.weight.signum() > 0) {
                    if (group.isEmpty()) issues += EvaluationIssue(EvaluationIssueCode.MissingStratum, stratum.id)
                    else if (accepted == 0) issues += EvaluationIssue(EvaluationIssueCode.ZeroAccepted, stratum.id)
                    // Exact cross-multiplication, so rounding cannot turn a failed floor into a pass.
                    if (accepted.toBigDecimal() < stratum.acceptanceFloor.multiply(group.size.toBigDecimal()))
                        issues += EvaluationIssue(EvaluationIssueCode.QualityFloor, stratum.id)
                }
                val tokens = if (accepted == 0 || group.any { it.inputTokens == null || it.outputTokens == null }) null
                    else group.sumOf { it.inputTokens!!.toBigDecimal() + it.outputTokens!!.toBigDecimal() }
                        .divide(accepted.toBigDecimal(), arithmetic)
                stratum.id to StratumScore(group.size, accepted, rate, cost, perAccepted, economy, tokens)
            }
            if (rows.any { it.onlineCost.unknown || !it.billingComplete })
                issues += EvaluationIssue(EvaluationIssueCode.UnknownBilling, configuration.hex)
            if (rows.any { it.invariantViolations > 0 })
                issues += EvaluationIssue(EvaluationIssueCode.InvariantViolation, configuration.hex)
            if (rows.any { it.seriousRegressions > 0 })
                issues += EvaluationIssue(EvaluationIssueCode.SeriousRegression, configuration.hex)
            val weighted = policy.strata.filter { it.weight.signum() > 0 }
            val quality = if (!valid || weighted.any { strata.getValue(it.id).acceptance == null }) null
                else weighted.sumOf { it.weight.multiply(strata.getValue(it.id).acceptance!!) }.multiply(BigDecimal(100))
            val economy = if (!valid || weighted.any { strata.getValue(it.id).economy == null }) null
                else weighted.sumOf { it.weight.multiply(strata.getValue(it.id).economy!!) }
            val score = if (issues.isNotEmpty() || quality == null || economy == null) null
                else quality.multiply(BigDecimal("0.60")) + economy.multiply(BigDecimal("0.40"))
            return Scorecard(design, configuration, rows, strata, quality, economy, score,
                totalCost(rows, policy.currency), issues)
        }
    }
}

internal fun totalCost(rows: List<EvaluationTrial>, currency: String): Money = Money(
    currency, rows.filter { it.onlineCost.currency == currency }.sumOf { it.onlineCost.amount },
    rows.any { it.onlineCost.unknown || !it.billingComplete || it.onlineCost.currency != currency },
)
