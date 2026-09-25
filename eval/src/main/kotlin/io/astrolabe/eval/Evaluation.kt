package io.astrolabe.eval

import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.math.MathContext
import java.util.Collections

/** All related tasks/repetitions use the same repository cluster, including related repository forks. */
public data class TrialKey(val repository: String, val task: String, val repetition: Int) {
    init { label(repository); label(task); require(repetition >= 0) }
}

/** matchedInputs binds starting artifacts, acceptance, environment and equal total budget. */
public data class PlannedTrial(val key: TrialKey, val stratum: String, val matchedInputs: Digest) {
    init { label(stratum) }
}

/** One terminal complete task, with every internal attempt/helper/review/integration cost included. */
public data class EvaluationTrial(
    val key: TrialKey,
    val configuration: Digest,
    val matchedInputs: Digest,
    val accepted: Boolean,
    val onlineCost: Money,
    val billingComplete: Boolean,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val latencyMillis: Long?,
    val invariantViolations: Long,
    val seriousRegressions: Long,
    val provenance: String,
) {
    init {
        validMoney(onlineCost)
        require(listOf(inputTokens, outputTokens, latencyMillis).all { it == null || it >= 0 })
        require(invariantViolations >= 0 && seriousRegressions >= 0)
        require(provenance.isNotBlank())
    }
}

public data class StratumPolicy(
    val id: String,
    val weight: BigDecimal,
    val complex: Boolean,
    val costLow: Money,
    val costHigh: Money,
    val acceptanceFloor: BigDecimal,
) {
    init {
        label(id); fraction(weight); fraction(acceptanceFloor)
        validMoney(costLow); validMoney(costHigh)
        require(!costLow.unknown && !costHigh.unknown && costLow.currency == costHigh.currency)
        require(costLow.amount < costHigh.amount)
    }
}

/** Parameters must be fixed from pilot/design, before inspecting confirmatory outcomes. D-57. */
public class ScorePolicy(
    public val version: String,
    strata: List<StratumPolicy>,
    public val confidence: BigDecimal,
    public val minClusters: Int,
    public val costRatio: BigDecimal,
    public val costCeiling: Money,
    public val latencyCeilingMillis: Long,
) {
    public val strata: List<StratumPolicy> = immutable(strata.sortedBy { it.id })
    public val currency: String get() = costCeiling.currency
    init {
        label(version)
        require(this.strata.isNotEmpty() && this.strata.map { it.id }.distinct().size == this.strata.size)
        require(this.strata.sumOf { it.weight }.compareTo(BigDecimal.ONE) == 0)
        require(this.strata.filter { it.complex }.sumOf { it.weight } >= BigDecimal("0.5"))
        require(this.strata.all { it.costLow.currency == currency })
        require(confidence > BigDecimal.ZERO && confidence < BigDecimal.ONE)
        require(minClusters >= 2)
        require(costRatio > BigDecimal.ZERO && costRatio < BigDecimal.ONE)
        validMoney(costCeiling); require(!costCeiling.unknown && latencyCeilingMillis >= 0)
    }
    public val fingerprint: Digest = fingerprint("score-policy", buildList {
        addAll(listOf(version, confidence, minClusters, costRatio, currency, costCeiling.amount, latencyCeilingMillis))
        this@ScorePolicy.strata.forEach { addAll(listOf(it.id, it.weight, it.complex, it.costLow.amount,
            it.costHigh.amount, it.acceptanceFloor)) }
    })
}

/** An input projection, not a producer or verifier of frozen campaign manifests. */
public class EvaluationDesign(
    public val policy: ScorePolicy,
    public val manifest: Digest,
    public val baseline: Digest,
    candidates: Set<Digest>,
    trials: List<PlannedTrial>,
) {
    public val candidates: Set<Digest> = Collections.unmodifiableSet(LinkedHashSet(candidates.sortedBy { it.hex }))
    public val trials: List<PlannedTrial> = immutable(trials.sortedWith(compareBy(trialOrder) { it.key }))
    init {
        require(this.candidates.isNotEmpty() && baseline !in this.candidates)
        require(this.trials.map { it.key }.distinct().size == this.trials.size) { "duplicate planned pair" }
        require(this.trials.all { trial -> policy.strata.any { it.id == trial.stratum } })
        require(this.trials.groupBy { it.key.repository to it.key.task }.values.all { group ->
            group.map { it.stratum }.distinct().size == 1
        }) { "repetitions of a task must keep its stratum" }
    }
    public val fingerprint: Digest = fingerprint("evaluation-design", buildList {
        addAll(listOf(policy.fingerprint, manifest, baseline, this@EvaluationDesign.candidates.size))
        addAll(this@EvaluationDesign.candidates)
        this@EvaluationDesign.trials.forEach { addAll(listOf(it.key.repository, it.key.task, it.key.repetition,
            it.stratum, it.matchedInputs)) }
    })
}

public enum class EvaluationIssueCode {
    DuplicateTrial, UnexpectedTrial, MissingTrial, MismatchedInputs, CurrencyMismatch,
    MissingStratum, ZeroAccepted, QualityFloor, UnknownBilling, InvariantViolation, SeriousRegression,
    InsufficientClusters, BoundNotMet, ObservedComplexLoss, EconomicPolicy, CostCeiling,
    UnknownLatency, LatencyCeiling, UnknownInvestment, NoPositiveSaving,
    Integrity, Independence, MandatoryControls, EvidenceMismatch, SyntheticEvidence,
    // P6.1.3 decision layer: fixture invariants and undeclared frozen candidates.
    UnmeasuredInvariant, MultipleSelection,
}

public data class EvaluationIssue(val code: EvaluationIssueCode, val detail: String)

internal val arithmetic: MathContext = MathContext.DECIMAL128
internal val trialOrder: Comparator<TrialKey> = compareBy(TrialKey::repository, TrialKey::task, TrialKey::repetition)
internal val rowOrder: Comparator<EvaluationTrial> = compareBy<EvaluationTrial> { it.configuration.hex }
    .thenBy(trialOrder) { it.key }.thenBy { it.toString() }
internal fun <T> immutable(values: Collection<T>): List<T> = Collections.unmodifiableList(values.toList())
internal fun label(value: String) { require(value.isNotBlank() && value == value.trim()) }
internal fun fraction(value: BigDecimal) { require(value >= BigDecimal.ZERO && value <= BigDecimal.ONE) }
internal fun validMoney(value: Money) {
    require(value.currency.length == 3 && value.currency.all { it in 'A'..'Z' } && value.amount.signum() >= 0)
}
internal fun fingerprint(kind: String, values: List<Any?>): Digest = Digest.ofUtf8(
    CanonicalEncoding.encode(kind, 1, values.mapIndexed { i, value -> i.toString() to (value?.toString() ?: "<null>") }),
)
internal fun trialFingerprint(rows: List<EvaluationTrial>): Digest = fingerprint("evaluation-trials", buildList {
    rows.forEach { addAll(listOf(it.key.repository, it.key.task, it.key.repetition, it.configuration, it.matchedInputs,
        it.accepted, it.onlineCost.currency, it.onlineCost.amount, it.onlineCost.unknown, it.billingComplete,
        it.inputTokens, it.outputTokens, it.latencyMillis, it.invariantViolations, it.seriousRegressions, it.provenance)) }
})

internal fun validateRows(design: EvaluationDesign, config: Digest, rows: List<EvaluationTrial>): List<EvaluationIssue> {
    val expected = design.trials.associateBy { it.key }
    val issues = mutableListOf<EvaluationIssue>()
    val seen = HashSet<TrialKey>()
    for (row in rows) {
        if (!seen.add(row.key)) issues += EvaluationIssue(EvaluationIssueCode.DuplicateTrial, row.key.toString())
        val planned = expected[row.key]
        if (planned == null || row.configuration != config)
            issues += EvaluationIssue(EvaluationIssueCode.UnexpectedTrial, row.key.toString())
        else if (row.matchedInputs != planned.matchedInputs)
            issues += EvaluationIssue(EvaluationIssueCode.MismatchedInputs, row.key.toString())
        if (row.onlineCost.currency != design.policy.currency)
            issues += EvaluationIssue(EvaluationIssueCode.CurrencyMismatch, row.key.toString())
    }
    for (key in expected.keys - seen) issues += EvaluationIssue(EvaluationIssueCode.MissingTrial, key.toString())
    return issues
}
