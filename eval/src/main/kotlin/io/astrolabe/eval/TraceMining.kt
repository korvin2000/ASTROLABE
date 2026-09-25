package io.astrolabe.eval

import io.astrolabe.event.Phase
import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import io.astrolabe.recover.ErrorSignature
import io.astrolabe.recover.FailureClass
import io.astrolabe.telemetry.TraceSnapshot
import java.math.BigDecimal
import java.util.Collections

/** MAST top-level failure categories (D-230); a failure the annotator did not tag stays untagged, never guessed. */
public enum class MastCategory { SpecificationIssues, InterAgentMisalignment, TaskVerification }

/** One classified failure observed in a trace: the §13.2 class, its coarse P4.6.2 signature and an optional MAST tag. */
public data class TraceFailure(
    val failureClass: FailureClass,
    val signature: ErrorSignature,
    val mast: MastCategory?,
    val module: String?,
) {
    init { module?.let(::label) }
}

/** A versioned trace of one complete task: the frozen harness it ran, its span snapshot and its failures. */
public class MinedTrace(
    public val harness: Digest,
    public val key: TrialKey,
    public val snapshot: TraceSnapshot,
    failures: List<TraceFailure>,
) {
    public val failures: List<TraceFailure> = immutable(failures)
}

/** Thresholds fixed before mining; [minTasks] counts distinct tasks, not occurrences. */
public data class MiningPolicy(val minTasks: Int, val costShareFloor: BigDecimal) {
    init { require(minTasks >= 2); fraction(costShareFloor) }
}

/** The same failure class and signature seen in at least `minTasks` distinct tasks. */
public data class RepeatedFailure(
    val failureClass: FailureClass,
    val signature: ErrorSignature,
    val mast: Set<MastCategory>,
    val modules: Set<String>,
    val tasks: Int,
    val occurrences: Int,
)

/** Exclusive span cost summed per phase; [share] is null when any contributing or total cost is unknown. */
public data class CostConcentration(val phase: Phase, val cost: Money, val share: BigDecimal?, val concentrated: Boolean)

public class MiningReport internal constructor(
    public val harness: Digest,
    public val policy: MiningPolicy,
    public val traces: Int,
    excluded: List<TrialKey>,
    mast: Map<MastCategory, Int>,
    public val untagged: Int,
    classes: Map<FailureClass, Int>,
    repeated: List<RepeatedFailure>,
    costs: List<CostConcentration>,
    public val totalCost: Money,
) {
    /** Traces of another harness version: never mixed into this version's distribution. */
    public val excluded: List<TrialKey> = immutable(excluded)
    public val mast: Map<MastCategory, Int> = Collections.unmodifiableMap(LinkedHashMap(mast))
    public val classes: Map<FailureClass, Int> = Collections.unmodifiableMap(LinkedHashMap(classes))
    public val repeated: List<RepeatedFailure> = immutable(repeated)
    public val costs: List<CostConcentration> = immutable(costs)
}

/** §12.3 first two cycle steps: collect versioned traces, find repeated failures or cost concentration. Pure. */
public object TraceMining {
    @JvmStatic
    public fun mine(harness: Digest, currency: String, policy: MiningPolicy, traces: List<MinedTrace>): MiningReport {
        val (kept, other) = traces.sortedWith(compareBy(trialOrder) { it.key }).partition { it.harness == harness }
        require(kept.map { it.key }.distinct().size == kept.size) { "duplicate trace for one trial" }
        require(kept.all { it.snapshot.currency == currency }) { "trace currency mismatch" }
        val failures = kept.flatMap { trace -> trace.failures.map { trace.key to it } }
        val mast = MastCategory.entries.associateWith { c -> failures.count { it.second.mast == c } }
        val classes = FailureClass.entries.associateWith { c -> failures.count { it.second.failureClass == c } }
            .filterValues { it > 0 }
        val repeated = failures.groupBy { it.second.failureClass to it.second.signature }
            .map { (k, group) ->
                RepeatedFailure(k.first, k.second, group.mapNotNull { it.second.mast }.toSortedSet(),
                    group.mapNotNull { it.second.module }.toSortedSet(),
                    group.map { it.first.repository to it.first.task }.distinct().size, group.size)
            }
            .filter { it.tasks >= policy.minTasks }
            .sortedWith(compareByDescending<RepeatedFailure> { it.tasks }.thenByDescending { it.occurrences }
                .thenBy { it.failureClass.ordinal }.thenBy { it.signature.operation }.thenBy { it.signature.text })
        // Exclusive cost is counted once per span: identical re-captures collapse, conflicting ones are refused.
        val spans = kept.flatMap { trace ->
            trace.snapshot.spans.distinct().also { s ->
                require(s.map { it.id }.distinct().size == s.size) { "conflicting duplicate span in ${trace.key}" }
            }
        }
        val total = spans.fold(Money.zero(currency)) { acc, s -> acc + s.exclusiveCost }
        val costs = Phase.entries.mapNotNull { phase ->
            val group = spans.filter { it.phase == phase }
            if (group.isEmpty()) return@mapNotNull null
            val cost = group.fold(Money.zero(currency)) { acc, s -> acc + s.exclusiveCost }
            // Unknown cost is never read as zero, so no share can be claimed from it (FX-59).
            val share = if (cost.unknown || total.unknown || total.amount.signum() == 0) null
                else cost.amount.divide(total.amount, arithmetic)
            CostConcentration(phase, cost, share, share != null && share >= policy.costShareFloor)
        }.sortedWith(compareByDescending<CostConcentration> { it.share ?: BigDecimal.ONE.negate() }.thenBy { it.phase.ordinal })
        return MiningReport(harness, policy, kept.size, other.map { it.key }, mast,
            failures.count { it.second.mast == null }, classes, repeated, costs, total)
    }
}
