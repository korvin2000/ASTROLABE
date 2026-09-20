package io.astrolabe.eval

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** D-57: distribution-free paired repository blocks; both endpoints and every candidate share family alpha. */
public class PairedBound private constructor(
    public val complexOnly: Boolean,
    public val clusters: Int,
    public val estimate: BigDecimal?,
    public val radius: Double?,
    public val lower: Double?,
    public val constantDifferences: Boolean,
    issues: List<EvaluationIssue>,
) {
    public val issues: List<EvaluationIssue> = immutable(issues)
    public val passesMargin: Boolean get() = lower != null && lower > -0.02

    public companion object {
        @JvmStatic
        public fun calculate(baseline: Scorecard, candidate: Scorecard, complexOnly: Boolean): PairedBound {
            val design = baseline.design
            require(design.fingerprint == candidate.design.fingerprint)
            require(baseline.configuration == design.baseline && candidate.configuration in design.candidates)
            val issues = (validateRows(design, baseline.configuration, baseline.rows) +
                validateRows(design, candidate.configuration, candidate.rows)).toMutableList()
            val strata = design.policy.strata.filter { it.weight.signum() > 0 && (!complexOnly || it.complex) }
            val counts = design.trials.groupingBy { it.stratum }.eachCount()
            strata.filter { counts.getOrDefault(it.id, 0) == 0 }.forEach {
                issues += EvaluationIssue(EvaluationIssueCode.MissingStratum, it.id)
            }
            if (issues.isNotEmpty()) return PairedBound(complexOnly, 0, null, null, null, false, issues)
            val selected = strata.map { it.id }.toSet()
            val groups = design.trials.filter { it.stratum in selected }.groupBy { it.key.repository }
            val baseRows = baseline.rows.associateBy { it.key }
            val candRows = candidate.rows.associateBy { it.key }
            val differences = groups.values.flatten().map {
                candRows.getValue(it.key).accepted.toInt() - baseRows.getValue(it.key).accepted.toInt()
            }
            val constant = differences.distinct().size == 1
            val delta = acceptanceDifference(baseline, candidate, complexOnly)
            val estimate = delta.numerator.divide(delta.denominator, arithmetic)
            if (groups.size < design.policy.minClusters) {
                issues += EvaluationIssue(EvaluationIssueCode.InsufficientClusters, "${groups.size} repositories")
                return PairedBound(complexOnly, groups.size, estimate, null, null, constant, issues)
            }
            val mass = strata.sumOf { it.weight }
            // a_g bounds the ENTIRE block, preserving dependence between strata and repeated runs.
            val squares = groups.values.sumOf { group ->
                val groupCounts = group.groupingBy { it.stratum }.eachCount()
                val coefficient = strata.sumOf { stratum ->
                    stratum.weight.multiply(groupCounts.getOrDefault(stratum.id, 0).toBigDecimal())
                        .divide(counts.getValue(stratum.id).toBigDecimal(), upward)
                }.divide(mass, upward)
                coefficient.multiply(coefficient)
            }
            // ln(2/alpha), alpha=(1-confidence)/(2*M). Upward rounding at every positive operation.
            val logArgument = BigDecimal(4).multiply(design.candidates.size.toBigDecimal())
                .divide(BigDecimal.ONE.subtract(design.policy.confidence), upward)
            val log = Math.nextUp(Math.nextUp(StrictMath.log(Math.nextUp(logArgument.toDouble()))))
            val variance = Math.nextUp(squares.toDouble())
            val radius = Math.nextUp(StrictMath.sqrt(Math.nextUp(Math.nextUp(2.0 * variance) * log)))
            val deltaDown = Math.nextDown(delta.numerator.divide(delta.denominator, downward).toDouble())
            val lower = Math.nextDown(deltaDown - radius).coerceAtLeast(-1.0)
            return PairedBound(complexOnly, groups.size, estimate, radius, lower, constant, issues)
        }
    }
}

private val upward: MathContext = MathContext(34, RoundingMode.CEILING)
private val downward: MathContext = MathContext(34, RoundingMode.FLOOR)
private fun Boolean.toInt(): Int = if (this) 1 else 0

/** Exact common-denominator arithmetic also decides observed loss without rounded rate comparisons. */
internal data class Fraction(val numerator: BigDecimal, val denominator: BigDecimal)

internal fun acceptanceDifference(baseline: Scorecard, candidate: Scorecard, complexOnly: Boolean): Fraction {
    val strata = baseline.design.policy.strata.filter { it.weight.signum() > 0 && (!complexOnly || it.complex) }
    var numerator = BigDecimal.ZERO
    var denominator = BigDecimal.ONE
    for (stratum in strata) {
        val base = baseline.strata.getValue(stratum.id)
        val cand = candidate.strata.getValue(stratum.id)
        val count = base.trials.toBigDecimal()
        numerator = numerator.multiply(count).add(stratum.weight.multiply((cand.accepted - base.accepted).toBigDecimal())
            .multiply(denominator))
        denominator = denominator.multiply(count)
    }
    return Fraction(numerator, denominator.multiply(strata.sumOf { it.weight }))
}
