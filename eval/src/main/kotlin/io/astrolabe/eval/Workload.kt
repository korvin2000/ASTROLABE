package io.astrolabe.eval

import io.astrolabe.id.Digest
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections

public enum class WorkloadPartition { Development, Selection, Final }
public enum class WorkloadGrouping { Repository, TaskFamily }

public data class WorkloadTrialKey(val task: String, val repetition: Int) {
    init { label(task); require(repetition >= 0) }
}

/** [weight] is task mass, counted once across all repetitions. Null metadata is never imputed. */
public class WorkloadTask(
    public val id: String,
    repetitions: Set<Int>,
    public val repository: String?,
    public val family: String?,
    public val stratum: String?,
    public val weight: BigDecimal?,
    public val time: Instant?,
) {
    public val repetitions: Set<Int> = frozenSet(repetitions.sorted())
    init {
        label(id)
        listOfNotNull(repository, family, stratum).forEach(::label)
        require(this.repetitions.isNotEmpty() && this.repetitions.all { it >= 0 })
        require(weight == null || weight.signum() >= 0)
    }
}

public data class WorkloadLink(val first: String, val second: String) {
    init { label(first); label(second) }
}

/** A half-open interval; absent endpoints are unbounded. */
public data class WorkloadWindow(val fromInclusive: Instant?, val untilExclusive: Instant?) {
    init { require(fromInclusive == null || untilExclusive == null || fromInclusive < untilExclusive) }
    public fun contains(time: Instant): Boolean =
        (fromInclusive == null || time >= fromInclusive) && (untilExclusive == null || time < untilExclusive)
}

/** Null [stratum] means partition total. All quantities use task-weight units. */
public data class WorkloadQuota(
    val partition: WorkloadPartition,
    val stratum: String?,
    val minimum: BigDecimal,
    val maximum: BigDecimal,
    val target: BigDecimal,
    val penalty: BigDecimal,
) {
    init {
        stratum?.let(::label)
        require(minimum.signum() >= 0 && maximum >= minimum)
        require(target >= minimum && target <= maximum && penalty.signum() >= 0)
    }
}

/** Frozen sampling design only; it neither inspects model outcomes nor certifies absence of answer leakage. */
public class WorkloadPolicy(
    public val version: String,
    strata: Map<String, Boolean>,
    grouping: Set<WorkloadGrouping>,
    links: List<WorkloadLink>,
    allowed: Map<String, Set<WorkloadPartition>>,
    windows: Map<WorkloadPartition, WorkloadWindow>,
    quotas: List<WorkloadQuota>,
) {
    public val strata: Map<String, Boolean> = frozenMap(strata.toSortedMap())
    public val grouping: Set<WorkloadGrouping> = frozenSet(grouping.sortedBy { it.ordinal })
    public val links: List<WorkloadLink> = immutable(links.map {
        if (it.first <= it.second) it else WorkloadLink(it.second, it.first)
    }.distinct().sortedWith(compareBy(WorkloadLink::first, WorkloadLink::second)))
    public val allowed: Map<String, Set<WorkloadPartition>> = frozenMap(allowed.toSortedMap().mapValues {
        frozenSet(it.value.sortedBy { p -> p.ordinal })
    })
    public val windows: Map<WorkloadPartition, WorkloadWindow> = frozenMap(windows.toSortedMap())
    public val quotas: List<WorkloadQuota> = immutable(quotas.sortedWith(
        compareBy<WorkloadQuota> { it.partition.ordinal }.thenBy { it.stratum },
    ))
    init {
        label(version)
        require(this.strata.isNotEmpty())
        this.strata.keys.forEach(::label); this.allowed.keys.forEach(::label)
        require(this.windows.isEmpty() || this.windows.keys == WorkloadPartition.entries.toSet())
        require(this.quotas.map { it.partition to it.stratum }.distinct().size == this.quotas.size)
        require(WorkloadPartition.entries.all { p -> this.quotas.any { it.partition == p && it.stratum == null } })
        require(this.quotas.all { it.stratum == null || it.stratum in this.strata })
    }
    public val fingerprint: Digest = fingerprint("workload-policy", buildList {
        add(version); add(this@WorkloadPolicy.strata.size)
        this@WorkloadPolicy.strata.forEach { (s, complex) -> add(s); add(complex) }
        add(this@WorkloadPolicy.grouping.size); addAll(this@WorkloadPolicy.grouping)
        add(this@WorkloadPolicy.links.size)
        this@WorkloadPolicy.links.forEach { add(it.first); add(it.second) }
        add(this@WorkloadPolicy.allowed.size)
        this@WorkloadPolicy.allowed.forEach { (id, parts) -> add(id); add(parts.size); addAll(parts) }
        add(this@WorkloadPolicy.windows.size)
        this@WorkloadPolicy.windows.forEach { (p, w) ->
            add(p); optional(w.fromInclusive); optional(w.untilExclusive)
        }
        add(this@WorkloadPolicy.quotas.size)
        this@WorkloadPolicy.quotas.forEach {
            add(it.partition); optional(it.stratum)
            addAll(listOf(it.minimum, it.maximum, it.target, it.penalty).map { n -> n.stripTrailingZeros() })
        }
    })
}

public class WorkloadDesign(public val policy: WorkloadPolicy, tasks: List<WorkloadTask>) {
    public val tasks: List<WorkloadTask> = immutable(tasks.sortedBy { it.id })
    init {
        val ids = this.tasks.map { it.id }.toSet()
        require(this.tasks.isNotEmpty() && ids.size == this.tasks.size) { "empty or duplicate task table" }
        require(policy.links.all { it.first in ids && it.second in ids }) { "link to absent task" }
        require(policy.allowed.keys.all { it in ids }) { "allowance for absent task" }
        require(this.tasks.all { it.stratum == null || it.stratum in policy.strata }) { "undeclared stratum" }
    }
    public val fingerprint: Digest = fingerprint("workload-design", buildList {
        add(policy.fingerprint); add(this@WorkloadDesign.tasks.size)
        this@WorkloadDesign.tasks.forEach {
            add(it.id); add(it.repetitions.size); addAll(it.repetitions)
            optional(it.repository); optional(it.family); optional(it.stratum)
            optional(it.weight?.stripTrailingZeros()); optional(it.time)
        }
    })
}

public class WorkloadGroup internal constructor(tasks: List<String>, allowed: Set<WorkloadPartition>) {
    public val tasks: List<String> = immutable(tasks)
    public val allowed: Set<WorkloadPartition> = frozenSet(allowed)
}

/** Denominator and numerator are explicit; zero weight is not an undefined percentage presented as zero. */
public data class WorkloadMass(
    val partition: WorkloadPartition,
    val stratum: String?,
    val tasks: Int,
    val trials: Long,
    val weight: BigDecimal,
    val complexWeight: BigDecimal,
)

public enum class WorkloadSplitStatus { Feasible, Optimal, Infeasible, SearchLimit, UnknownMetadata, InvalidInput }

public class WorkloadSplitResult internal constructor(
    public val design: WorkloadDesign,
    public val status: WorkloadSplitStatus,
    groups: List<WorkloadGroup>,
    assignment: Map<WorkloadTrialKey, WorkloadPartition>,
    totals: List<WorkloadMass>,
    public val objective: BigDecimal?,
    public val visitedNodes: Long,
    public val searchComplete: Boolean,
    issues: List<String>,
) {
    public val groups: List<WorkloadGroup> = immutable(groups)
    public val assignment: Map<WorkloadTrialKey, WorkloadPartition> = frozenMap(assignment)
    public val totals: List<WorkloadMass> = immutable(totals)
    public val issues: List<String> = immutable(issues)
}

internal fun <T> frozenSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun MutableList<Any?>.optional(value: Any?) { add(value != null); add(value ?: "") }
