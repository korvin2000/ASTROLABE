package io.astrolabe.eval

import io.astrolabe.AttemptConfig
import io.astrolabe.id.Digest
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.telemetry.CallAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal

/** One complete task's trial row from the accounting of every call it made (P1.11.2 `Accounting`, §19.4). */
public object Trials {
    /**
     * [calls] are all of the task's calls — helpers, retries, review and integration included. A call without complete
     * usage, an unknown price or another currency leaves the cost unknown (FX-59), never zero; token volume counts
     * cached input and is null unless every call reported its input and output dimensions.
     */
    @JvmStatic
    public fun fromCalls(
        key: TrialKey,
        configuration: Digest,
        matchedInputs: Digest,
        accepted: Boolean,
        calls: List<CallAccount>,
        currency: String,
        latencyMillis: Long?,
        invariantViolations: Long,
        seriousRegressions: Long,
        provenance: String,
    ): EvaluationTrial {
        val sameCurrency = calls.filter { it.money.currency == currency }
        val cost = Money(currency, sameCurrency.sumOf { it.money.amount },
            calls.any { it.money.unknown || it.money.currency != currency })
        val complete = calls.all { it.usage?.isComplete == true } && !cost.unknown
        val input = if (calls.all { c -> c.usage?.unknown?.none { it.isInput } == true }) calls.sumOf { it.usage!!.totalInput } else null
        val output = if (calls.all { c -> c.usage?.quantities?.containsKey(BillingDimension.OUTPUT) == true })
            calls.sumOf { it.usage!!.quantities.getValue(BillingDimension.OUTPUT) } else null
        return EvaluationTrial(key, configuration, matchedInputs, accepted, cost, complete, input, output, latencyMillis,
            invariantViolations, seriousRegressions, provenance)
    }
}

/** One-off costs reported apart from online solver cost (§19.4), with the volume at which they repay. */
public enum class InvestmentKind { Index, Memory, Calibration, HarnessSearch }

public data class Investment(val kind: InvestmentKind, val money: Money)

public object Investments {
    /** The configuration's one-off total; unknown as soon as one part is unknown or in another currency. */
    @JvmStatic
    public fun total(investments: List<Investment>, currency: String): Money = Money(
        currency, investments.filter { it.money.currency == currency }.sumOf { it.money.amount },
        investments.any { it.money.unknown || it.money.currency != currency },
    )
}

/** Campaign facts in the form `PromotionReport` takes (P6.1.2 integrity, P6.1.5 partition, D-48 controls). */
public object PromotionEvidence {
    /**
     * Independence passes only when the manifest's partition was validated with repository or task-family grouping
     * (§19.2), and is unknown for task-only isolation; mandatory controls fail for a research attempt (D-48).
     */
    @JvmStatic
    public fun of(
        manifest: CampaignManifest,
        candidate: AttemptConfig,
        integrity: IntegrityVerdict,
        origin: EvaluationOrigin,
        provenance: String,
    ): EvaluationEvidence = EvaluationEvidence(
        configuration = candidate.fingerprint,
        manifest = manifest.fingerprint,
        origin = origin,
        integrity = integrity.evidence,
        independence = if (manifest.workload.policy.grouping.isEmpty()) EvidenceCheck.Unknown else EvidenceCheck.Pass,
        mandatoryControls = if (candidate.production && candidate.controls.allEnabled) EvidenceCheck.Pass else EvidenceCheck.Fail,
        provenance = provenance,
    )
}

/**
 * The §19.6 decision over a `PromotionReport` (P6.1.4) plus what it cannot see: the fixture run (no invariant
 * violation in fixtures; unmeasured is not zero) and whether every frozen candidate of the manifest is declared in
 * the design, so multiple selection is accounted for. Diagnostic only: it changes no configuration (D-223).
 */
public class PromotionDecision private constructor(
    public val report: PromotionReport,
    public val fixtures: FixtureReport?,
    issues: List<EvaluationIssue>,
    public val verdict: PromotionVerdict,
) {
    public val issues: List<EvaluationIssue> = immutable(issues)

    public companion object {
        @JvmStatic
        public fun decide(report: PromotionReport, fixtures: FixtureReport?, manifest: CampaignManifest): PromotionDecision {
            val design = report.baseline.design
            val issues = mutableListOf<EvaluationIssue>()
            if (design.manifest != manifest.fingerprint)
                issues += EvaluationIssue(EvaluationIssueCode.EvidenceMismatch, "design is not bound to manifest ${manifest.id}")
            val frozen = (manifest.variants.mapNotNull { it.attempt } + manifest.arms.mapNotNull { it.attempt }).map { it.fingerprint }.toSet()
            (frozen - design.candidates - design.baseline).sortedBy { it.hex }.forEach {
                issues += EvaluationIssue(EvaluationIssueCode.MultipleSelection, "frozen candidate ${it.hex} is not declared in the design")
            }
            val violated = fixtures != null && (fixtures.failed > 0 || fixtures.metrics.any { (it.violations ?: 0) > 0 })
            when {
                fixtures == null -> issues += EvaluationIssue(EvaluationIssueCode.UnmeasuredInvariant, "no fixture report")
                violated -> issues += EvaluationIssue(EvaluationIssueCode.InvariantViolation,
                    "fixtures: ${fixtures.failed} failed; " + fixtures.metrics.filter { (it.violations ?: 0) > 0 }.joinToString { "${it.invariant.wire} ${it.violations}" })
                else -> fixtures.metrics.filter { it.violations == null }.forEach {
                    issues += EvaluationIssue(EvaluationIssueCode.UnmeasuredInvariant, it.invariant.wire)
                }
            }
            val verdict = when {
                report.verdict == PromotionVerdict.InvalidInput -> PromotionVerdict.InvalidInput
                violated || report.verdict == PromotionVerdict.KeepBaseline -> PromotionVerdict.KeepBaseline
                issues.isNotEmpty() -> PromotionVerdict.Inconclusive
                else -> report.verdict
            }
            return PromotionDecision(report, fixtures, issues, verdict)
        }
    }

    /** The machine-readable scorecard and decision (derived view). */
    public fun json(): String = JSON.encodeToString(buildJsonObject {
        put("kind", "astrolabe.promotion-decision/1")
        put("verdict", verdict.name); put("reportVerdict", report.verdict.name); put("numericalPass", report.numericalPass)
        put("design", report.baseline.design.fingerprint.hex); put("manifest", report.baseline.design.manifest.hex)
        put("input", report.inputDigest.hex)
        put("baseline", score(report.baseline)); put("candidate", score(report.candidate))
        for ((name, bound) in listOf("overall" to report.overall, "complex" to report.complex)) putJsonObject(name) {
            put("clusters", bound.clusters); put("estimate", decimal(bound.estimate))
            put("radius", bound.radius?.let(::JsonPrimitive) ?: JsonNull); put("lower", bound.lower?.let(::JsonPrimitive) ?: JsonNull)
            put("passesMargin", bound.passesMargin)
        }
        put("baselineWeightedCost", money(report.baselineWeightedCost)); put("candidateWeightedCost", money(report.candidateWeightedCost))
        put("baselineInvestment", money(report.baselineInvestment)); put("candidateInvestment", money(report.candidateInvestment))
        put("repaymentAcceptedTasks", decimal(report.repaymentAcceptedTasks))
        putJsonObject("fixtures") {
            put("present", fixtures != null)
            fixtures?.let { f -> put("tests", f.results.size); put("failed", f.failed); put("invariantsZero", f.invariantsZero) }
        }
        putJsonArray("issues") { (report.issues + report.investmentIssues + issues).forEach { add(issue(it)) } }
    })
}

private val JSON = Json { prettyPrint = true }

private fun decimal(value: BigDecimal?): JsonElement = value?.let { JsonPrimitive(it.toPlainString()) } ?: JsonNull

private fun money(value: Money?): JsonElement = value?.let { m -> buildJsonObject {
    put("currency", m.currency); put("amount", m.amount.toPlainString()); put("unknown", m.unknown)
} } ?: JsonNull

private fun issue(i: EvaluationIssue): JsonElement = buildJsonObject { put("code", i.code.name); put("detail", i.detail) }

private fun score(s: Scorecard): JsonElement = buildJsonObject {
    put("configuration", s.configuration.hex)
    put("quality", decimal(s.quality)); put("economy", decimal(s.economy)); put("eligibleScore", decimal(s.eligibleScore))
    put("totalOnlineCost", money(s.totalOnlineCost))
    putJsonObject("strata") { s.strata.forEach { (id, st) -> put(id, buildJsonObject {
        put("trials", st.trials); put("accepted", st.accepted); put("acceptance", decimal(st.acceptance))
        put("costPerAccepted", money(st.costPerAccepted)); put("economy", decimal(st.economy))
        put("tokensPerAccepted", decimal(st.tokensPerAccepted))
    }) } }
}
