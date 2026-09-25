package io.astrolabe.eval

import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import java.time.Clock
import java.time.Instant
import java.util.Collections

/** §12.3 cycle stages after a proposal is planned; [Promoted] only ever affects subsequent attempts. */
public enum class ExperimentStage { Planned, Checked, Compared, Integrated, TransferAssessed, Promoted, RolledBack, Rejected }

/** Evidence that moves an experiment record; every piece is supplied by the evaluator, never by the candidate. */
public sealed interface ExperimentEvidence {
    /** Cheap structural checks and a smoke run. */
    public data class Checks(val structural: Boolean, val smoke: Boolean, val provenance: String) : ExperimentEvidence {
        init { label(provenance) }
    }

    /** Paired comparisons on the tuning set: candidate vs the frozen baseline and vs each alternative arm. */
    public class Comparison(
        public val versusBaseline: PromotionReport,
        versusAlternatives: Map<ExperimentArmKind, PromotionReport>,
    ) : ExperimentEvidence {
        public val versusAlternatives: Map<ExperimentArmKind, PromotionReport> =
            Collections.unmodifiableMap(LinkedHashMap(versusAlternatives.toSortedMap()))
    }

    /** The candidate evaluated together with the other changes pending at the same time (they can interfere). */
    public class Integration(
        public val configuration: Digest,
        combinedWith: Set<Digest>,
        public val report: PromotionReport,
    ) : ExperimentEvidence {
        public val combinedWith: Set<Digest> = Collections.unmodifiableSet(LinkedHashSet(combinedWith.sortedBy { it.hex }))
    }

    /** The frozen candidate assessed on the separate final set. */
    public data class Transfer(val report: PromotionReport) : ExperimentEvidence

    /** An adoption decision made outside the runner (adoption rules are never candidate-editable). */
    public data class Adoption(val approver: String, val effectiveFromAttempt: String) : ExperimentEvidence {
        init { label(approver); label(effectiveFromAttempt) }
    }

    public data class Rollback(val reason: String) : ExperimentEvidence { init { label(reason) } }

    public data class Rejection(val reason: String) : ExperimentEvidence { init { label(reason) } }
}

/** [at] is capture metadata only; it is not part of any identity (I-05). */
public data class ExperimentEntry(val stage: ExperimentStage, val evidence: ExperimentEvidence?, val reason: String?, val at: Instant)

public class ExperimentRecord internal constructor(
    public val id: String,
    public val plan: ExperimentPlan,
    history: List<ExperimentEntry>,
) {
    public val history: List<ExperimentEntry> = immutable(history)
    public val stage: ExperimentStage get() = history.last().stage

    /** The harness subsequent attempts run; a live attempt keeps the frozen harness it started with. */
    public val harnessForSubsequentAttempts: Digest
        get() = if (stage == ExperimentStage.Promoted) plan.proposal.candidate else plan.proposal.baseline

    internal fun with(entry: ExperimentEntry): ExperimentRecord = ExperimentRecord(id, plan, history + entry)
}

public sealed interface ExperimentTransition {
    public data class Advanced(val record: ExperimentRecord) : ExperimentTransition
    /** The evidence does not fit the record's stage or plan; the record is unchanged. */
    public data class Refused(val record: ExperimentRecord, val reason: String) : ExperimentTransition
}

/** Experiment bookkeeping as an explicit state machine over immutable records; it never mutates a harness. */
public class Experiments(private val clock: Clock, private val ids: IdGen) {
    public fun open(plan: ExperimentPlan): ExperimentRecord =
        ExperimentRecord(ids.next("experiment"), plan, listOf(ExperimentEntry(ExperimentStage.Planned, null, null, clock.instant())))

    public fun advance(record: ExperimentRecord, evidence: ExperimentEvidence): ExperimentTransition {
        val plan = record.plan
        val stage = record.stage
        fun refuse(reason: String) = ExperimentTransition.Refused(record, reason)
        fun move(next: ExperimentStage, reason: String? = null) =
            ExperimentTransition.Advanced(record.with(ExperimentEntry(next, evidence, reason, clock.instant())))
        fun gate(next: ExperimentStage, failures: List<String>) =
            if (failures.isEmpty()) move(next) else move(ExperimentStage.Rejected, failures.joinToString("; "))

        if (stage == ExperimentStage.Rejected || stage == ExperimentStage.RolledBack) return refuse("terminal stage $stage")
        return when (evidence) {
            is ExperimentEvidence.Rejection ->
                if (stage == ExperimentStage.Promoted) refuse("a promoted change is rolled back, not rejected")
                else move(ExperimentStage.Rejected, evidence.reason)
            is ExperimentEvidence.Checks -> when {
                stage != ExperimentStage.Planned -> refuse("checks expected at Planned, stage is $stage")
                else -> gate(ExperimentStage.Checked, buildList {
                    if (!evidence.structural) add("structural checks failed")
                    if (!evidence.smoke) add("smoke run failed")
                })
            }
            is ExperimentEvidence.Comparison -> {
                if (stage != ExperimentStage.Checked) return refuse("comparison expected at Checked, stage is $stage")
                val candidate = plan.proposal.candidate
                mismatch(evidence.versusBaseline, plan.proposal.baseline, candidate, plan.tuningSet)?.let { return refuse(it) }
                val alternatives = listOf(ExperimentArmKind.StrongerReasoning, ExperimentArmKind.BetterContext,
                    ExperimentArmKind.AnotherAttempt)
                if (evidence.versusAlternatives.keys != alternatives.toSet())
                    return refuse("comparison needs exactly the three alternative arms")
                for (kind in alternatives) mismatch(evidence.versusAlternatives.getValue(kind),
                    plan.arm(kind).configuration, candidate, plan.tuningSet)?.let { return refuse("$kind: $it") }
                gate(ExperimentStage.Compared, buildList {
                    verdict(evidence.versusBaseline)?.let { add("vs baseline: $it") }
                    for (kind in alternatives) verdict(evidence.versusAlternatives.getValue(kind))?.let { add("vs $kind: $it") }
                })
            }
            is ExperimentEvidence.Integration -> {
                if (stage != ExperimentStage.Compared) return refuse("integration expected at Compared, stage is $stage")
                if (record.invalidIntegration(evidence)) return refuse("integrated configuration must be new and exclude the baseline")
                mismatch(evidence.report, plan.proposal.baseline, evidence.configuration, plan.tuningSet)?.let { return refuse(it) }
                gate(ExperimentStage.Integrated, listOfNotNull(verdict(evidence.report)?.let { "integrated: $it" }))
            }
            is ExperimentEvidence.Transfer -> {
                if (stage != ExperimentStage.Integrated) return refuse("transfer expected at Integrated, stage is $stage")
                mismatch(evidence.report, plan.proposal.baseline, plan.proposal.candidate, plan.transferSet)?.let { return refuse(it) }
                gate(ExperimentStage.TransferAssessed, listOfNotNull(verdict(evidence.report)?.let { "transfer: $it" }))
            }
            is ExperimentEvidence.Adoption ->
                if (stage != ExperimentStage.TransferAssessed) refuse("adoption expected at TransferAssessed, stage is $stage")
                else move(ExperimentStage.Promoted)
            is ExperimentEvidence.Rollback ->
                if (stage != ExperimentStage.Promoted) refuse("rollback expected at Promoted, stage is $stage")
                else move(ExperimentStage.RolledBack, evidence.reason)
        }
    }

    private fun ExperimentRecord.invalidIntegration(e: ExperimentEvidence.Integration): Boolean =
        e.configuration == plan.proposal.baseline || plan.proposal.baseline in e.combinedWith ||
            plan.proposal.candidate in e.combinedWith

    private fun mismatch(report: PromotionReport, baseline: Digest, candidate: Digest, set: Digest): String? = when {
        report.baseline.configuration != baseline -> "report baseline is not the planned configuration"
        report.candidate.configuration != candidate -> "report candidate is not the planned configuration"
        report.candidate.design.manifest != set || report.baseline.design.manifest != set -> "report is not on the planned workload set"
        else -> null
    }

    private fun verdict(report: PromotionReport): String? =
        if (report.verdict == PromotionVerdict.EligibleForReview) null
        else "${report.verdict} (${report.issues.joinToString(",") { it.code.name }})"
}
