package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.provider.Money
import java.math.BigDecimal
import java.util.Collections

/**
 * The harness surfaces of §12.3: the six mutation scopes a candidate may change, and the four that are never
 * editable by a candidate ([editable] false).
 */
public enum class HarnessSurface(public val editable: Boolean) {
    ToolBehaviour(true), ObservationRendering(true), ContextPolicy(true), StateValidation(true),
    CompletionDetection(true), Recovery(true),
    EvaluatorAccess(false), AcceptanceCriteria(false), BudgetAccounting(false), AdoptionRules(false),
}

/**
 * A mechanism-level hypothesis about one module. [predictedQuality] is the predicted change in complete
 * acceptance rate (a fraction in [-1, 1]); [predictedEconomy] the predicted relative change in billed cost per
 * accepted task (greater than -1; negative is cheaper). D-231.
 */
public data class Hypothesis(
    val mechanism: String,
    val module: String,
    val predictedQuality: BigDecimal,
    val predictedEconomy: BigDecimal,
    val evidence: String,
) {
    init {
        label(mechanism); label(module); label(evidence)
        require(predictedQuality >= BigDecimal.ONE.negate() && predictedQuality <= BigDecimal.ONE)
        require(predictedEconomy > BigDecimal.ONE.negate())
    }
}

/** One bounded change: exactly one editable surface, a frozen baseline and a distinct candidate configuration. */
@ConsistentCopyVisibility
public data class ChangeProposal internal constructor(
    val id: String,
    val hypothesis: Hypothesis,
    val surface: HarnessSurface,
    val baseline: Digest,
    val candidate: Digest,
    val description: String,
)

public sealed interface ProposalOutcome {
    public data class Admitted(val proposal: ChangeProposal) : ProposalOutcome
    public data class Refused(val reasons: List<String>) : ProposalOutcome
}

public enum class ExperimentArmKind { Baseline, Candidate, StrongerReasoning, BetterContext, AnotherAttempt }

public data class ExperimentArm(val kind: ExperimentArmKind, val configuration: Digest, val budget: Money) {
    init { validMoney(budget) }
}

/**
 * The paired experiment under matched total budget: the frozen baseline, the candidate and the three §12.3
 * alternatives (the same resources spent on stronger reasoning, better context or another ordinary attempt),
 * each with the same known total budget. [tuningSet] and [transferSet] are distinct workload manifests.
 */
public class ExperimentPlan(
    public val proposal: ChangeProposal,
    public val totalBudget: Money,
    arms: List<ExperimentArm>,
    public val tuningSet: Digest,
    public val transferSet: Digest,
) {
    public val arms: List<ExperimentArm> = immutable(arms.sortedBy { it.kind.ordinal })
    init {
        validMoney(totalBudget); require(!totalBudget.unknown && totalBudget.amount.signum() > 0)
        require(this.arms.map { it.kind } == ExperimentArmKind.entries) { "one arm of each kind" }
        require(this.arms.all { it.budget == totalBudget }) { "matched total budget" }
        require(this.arms.map { it.configuration }.distinct().size == this.arms.size) { "distinct arm configurations" }
        require(arm(ExperimentArmKind.Baseline).configuration == proposal.baseline)
        require(arm(ExperimentArmKind.Candidate).configuration == proposal.candidate)
        require(tuningSet != transferSet) { "transfer is assessed on a separate final set" }
    }

    public fun arm(kind: ExperimentArmKind): ExperimentArm = arms.first { it.kind == kind }

    public val fingerprint: Digest = fingerprint("experiment-plan", buildList {
        addAll(listOf(proposal.id, proposal.surface, proposal.baseline, proposal.candidate, totalBudget.currency,
            totalBudget.amount, tuningSet, transferSet))
        this@ExperimentPlan.arms.forEach { addAll(listOf(it.kind, it.configuration)) }
    })
}

/** Records bounded change proposals; never applies one. */
public class ChangeProposals(private val ids: IdGen) {
    public fun propose(
        hypothesis: Hypothesis,
        surfaces: Set<HarnessSurface>,
        baseline: Digest,
        candidate: Digest,
        description: String,
    ): ProposalOutcome {
        val reasons = buildList {
            // §12.3: evaluator access, acceptance criteria, budget accounting and adoption rules are never editable.
            surfaces.filterNot { it.editable }.sortedBy { it.ordinal }.forEach { add("protected surface: $it") }
            val editable = surfaces.filter { it.editable }
            if (editable.size != 1) add("a bounded change names exactly one mutation scope, got ${editable.size}")
            if (baseline == candidate) add("candidate equals the frozen baseline")
            if (description.isBlank()) add("empty description")
        }
        if (reasons.isNotEmpty()) return ProposalOutcome.Refused(Collections.unmodifiableList(reasons))
        return ProposalOutcome.Admitted(ChangeProposal(ids.next("proposal"), hypothesis, surfaces.single(),
            baseline, candidate, description.trim()))
    }
}
