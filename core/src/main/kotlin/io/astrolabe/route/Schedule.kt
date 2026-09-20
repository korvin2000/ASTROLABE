package io.astrolabe.route

import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.provider.Money
import java.util.Collections

/** Frozen cache identity; equal role/profile alone never implies a reusable prefix or a free switch. */
public data class ScheduleContext(
    val role: String,
    val profile: Digest,
    val systemPrefix: Digest,
    val rolePrefix: Digest,
    val cacheNamespace: String,
) {
    init { scheduleLabel(role); scheduleLabel(cacheNamespace) }
}

/** Directed transition, or additional precedence from [from] before [to]. */
public data class ScheduleEdge(val from: String, val to: String) {
    init { scheduleLabel(from); scheduleLabel(to) }
}

public enum class ScheduleCostModel { Pairwise, HistoryDependent }

/** Complete supplied matrix; estimates include all variable charges, with fixed charges listed separately. */
public class ScheduleCosts(
    public val version: String,
    public val currency: String,
    contexts: Map<String, ScheduleContext>,
    initial: Map<String, Money>,
    switches: Map<ScheduleEdge, Money>,
    fixed: Map<String, Money>,
    public val provenance: String,
    public val model: ScheduleCostModel,
) {
    public val contexts: Map<String, ScheduleContext> = scheduleMap(contexts.toSortedMap())
    public val initial: Map<String, Money> = scheduleMap(initial.toSortedMap())
    public val switches: Map<ScheduleEdge, Money> = scheduleMap(switches.toSortedMap(edgeOrder))
    public val fixed: Map<String, Money> = scheduleMap(fixed.toSortedMap())
    init {
        scheduleLabel(version); require(provenance.isNotBlank())
        require(currency.length == 3 && currency.all { it in 'A'..'Z' })
        (this.contexts.keys + this.initial.keys + this.fixed.keys).forEach(::scheduleLabel)
        (this.initial.values + this.switches.values + this.fixed.values).forEach {
            require(it.currency == currency && it.amount.signum() >= 0) { "invalid or mixed-currency charge" }
        }
    }
    public val fingerprint: Digest = scheduleFingerprint("schedule-costs", buildList {
        addAll(listOf(version, currency, provenance, model, this@ScheduleCosts.contexts.size))
        this@ScheduleCosts.contexts.forEach { (id, c) ->
            addAll(listOf(id, c.role, c.profile, c.systemPrefix, c.rolePrefix, c.cacheNamespace))
        }
        add(this@ScheduleCosts.initial.size)
        this@ScheduleCosts.initial.forEach { (id, cost) -> add(id); charge(cost) }
        add(this@ScheduleCosts.switches.size)
        this@ScheduleCosts.switches.forEach { (edge, cost) -> add(edge.from); add(edge.to); charge(cost) }
        add(this@ScheduleCosts.fixed.size)
        this@ScheduleCosts.fixed.forEach { (id, cost) -> add(id); charge(cost) }
    })
}

/** Offline projection. Completion/constraint declarations must come from the owner of the campaign. */
public class ScheduleProblem(
    public val graph: RequirementGraph,
    selected: Set<String>,
    completedPrerequisites: Set<String>,
    precedence: Set<ScheduleEdge>,
    public val constraintsComplete: Boolean,
    public val costs: ScheduleCosts,
) {
    public val selected: Set<String> = scheduleSet(selected.sorted())
    public val completedPrerequisites: Set<String> = scheduleSet(completedPrerequisites.sorted())
    public val precedence: Set<ScheduleEdge> = scheduleSet(precedence.sortedWith(edgeOrder))
    init { (this.selected + this.completedPrerequisites).forEach(::scheduleLabel) }
    public val fingerprint: Digest = scheduleFingerprint("schedule-problem", buildList {
        add(costs.fingerprint); add(constraintsComplete); add(graph.increments.size)
        graph.increments.sortedBy { it.id }.forEach { add(it.id); add(it.definitionDigest()); add(it.status) }
        add(this@ScheduleProblem.selected.size); addAll(this@ScheduleProblem.selected)
        add(this@ScheduleProblem.completedPrerequisites.size); addAll(this@ScheduleProblem.completedPrerequisites)
        add(this@ScheduleProblem.precedence.size)
        this@ScheduleProblem.precedence.forEach { add(it.from); add(it.to) }
    })
}

/** Dense table entries include unreachable cells. Limits are resource policy, not runtime defaults. */
public data class ScheduleLimits(val maxIncrements: Int, val maxTableEntries: Long, val maxTransitions: Long) {
    init { require(maxIncrements >= 0 && maxTableEntries >= 0 && maxTransitions >= 0) }
}

public enum class ScheduleStatus {
    Optimal, InvalidInput, UnsatisfiedPrerequisites, UnknownCosts, Unsupported, ResourceLimit,
}

public class ScheduleResult internal constructor(
    public val problem: ScheduleProblem,
    public val status: ScheduleStatus,
    order: List<String>,
    public val variableCost: Money?,
    public val fixedCost: Money?,
    public val totalCost: Money?,
    public val states: Long,
    public val transitions: Long,
    issues: List<String>,
) {
    public val order: List<String> = Collections.unmodifiableList(order.toList())
    public val issues: List<String> = Collections.unmodifiableList(issues.toList())
}

private val edgeOrder = compareBy(ScheduleEdge::from, ScheduleEdge::to)
private fun scheduleLabel(value: String) { require(value.isNotBlank() && value == value.trim()) }
private fun <K, V> scheduleMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun <T> scheduleSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
private fun MutableList<Any>.charge(money: Money) { add(money.amount.stripTrailingZeros()); add(money.unknown) }
private fun scheduleFingerprint(kind: String, values: List<Any>): Digest = Digest.ofUtf8(
    CanonicalEncoding.encode(kind, 1, values.mapIndexed { i, value -> i.toString() to value.toString() }),
)
