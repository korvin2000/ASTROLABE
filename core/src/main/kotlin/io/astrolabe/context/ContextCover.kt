package io.astrolabe.context

import io.astrolabe.budget.Tokens
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Collections

public data class ContextUnitId(val value: String) : Comparable<ContextUnitId> {
    init { require(value.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "non-canonical context unit id" } }
    override fun compareTo(other: ContextUnitId): Int = value.compareTo(other.value)
}

/** A planning cost, with estimator identity/version or exact-count provenance supplied by its producer. */
public data class ContextCost(val tokens: Tokens, val source: String, val estimated: Boolean) {
    init { require(source.isNotBlank()) }
}

/** §6.1 order, applied to feasible optional roots; prerequisites may cross classes. */
public enum class ContextPriority { AffectedContracts, CarryForward, Seeds, LocalImplementation, Lessons, Skills, Background }
public enum class ContextPlacement { K, System, Repository }

/** Calculation projection, not a Note or authorization record. Utility uses common caller-supplied points. */
public class ContextUnit @JvmOverloads public constructor(
    public val id: ContextUnitId,
    public val cost: ContextCost,
    dependsOn: Collection<ContextUnitId> = emptyList(),
    public val mandatory: Boolean = false,
    public val priority: ContextPriority = ContextPriority.Background,
    public val gain: Long = 0,
    public val placement: ContextPlacement = ContextPlacement.K,
) {
    public val dependsOn: List<ContextUnitId> = Collections.unmodifiableList(dependsOn.distinct().sorted())
    init { require(gain >= 0) { "utility must be non-negative" } }
}

/**
 * Disjoint serialized charges. System/repository unit costs are subsets of their corresponding charges.
 * Effective history is additional to pinned/retained text; null means unknown, never zero (D-06).
 */
public data class ContextBudget(
    val profileTokens: Tokens,
    val alpha: BigDecimal,
    val system: ContextCost,
    val repository: ContextCost,
    val pinnedHistory: ContextCost,
    val retainedProtocol: ContextCost,
    val effectiveHistory: ContextCost?,
    val reserves: ContextCost,
) {
    init { require(alpha > BigDecimal.ZERO && alpha <= BigDecimal.ONE) { "alpha must be in (0, 1]" } }
}

/** Null totals retain unknown effective history; knownFixedTokens is only the known subtotal. */
public data class ContextArithmetic(
    val limitTokens: BigInteger,
    val knownFixedTokens: BigInteger,
    val availableTokens: BigInteger?,
    val selectedTokens: BigInteger,
    val totalTokens: BigInteger?,
)

public enum class ContextSelectionStatus { Fit, Capacity, NeedsEvidence, UnknownHistory }
public enum class ContextOmission { Dependency, NoGain, Budget, SelectionRefused }
public enum class ContextIssueCode { MissingDependency, DependencyCycle }
public data class ContextIssue(val root: ContextUnitId, val dependency: ContextUnitId, val code: ContextIssueCode)

public class ContextPick internal constructor(
    public val root: ContextUnitId,
    addedIds: Collection<ContextUnitId>,
    public val tokens: BigInteger,
    public val gain: BigInteger,
) {
    public val addedIds: List<ContextUnitId> = Collections.unmodifiableList(addedIds.sorted())
}

/** Fit is a planning result only. P2.3.1 checks coverage/rendering; P2.3.3 authorizes dispatch. */
public class ContextSelection internal constructor(
    public val status: ContextSelectionStatus,
    units: Map<ContextUnitId, ContextUnit>,
    public val budget: ContextBudget,
    public val arithmetic: ContextArithmetic,
    mandatoryIds: Collection<ContextUnitId>,
    selectedIds: Collection<ContextUnitId>,
    omissions: Map<ContextUnitId, ContextOmission>,
    issues: List<ContextIssue>,
    picks: List<ContextPick>,
) {
    public val policyVersion: String = "context-cover-v1"
    public val units: Map<ContextUnitId, ContextUnit> = Collections.unmodifiableMap(units.toSortedMap())
    public val mandatoryIds: Set<ContextUnitId> = Collections.unmodifiableSet(mandatoryIds.toSortedSet())
    public val selectedIds: Set<ContextUnitId> = Collections.unmodifiableSet(selectedIds.toSortedSet())
    public val omissions: Map<ContextUnitId, ContextOmission> = Collections.unmodifiableMap(omissions.toSortedMap())
    public val issues: List<ContextIssue> = Collections.unmodifiableList(issues.toList())
    public val picks: List<ContextPick> = Collections.unmodifiableList(picks.toList())
}

/** D-55: dependency bundles, exact marginal gain/cost comparisons, no optimal-cover claim. */
public object ContextCover {
    @JvmStatic
    public fun select(units: Collection<ContextUnit>, budget: ContextBudget): ContextSelection {
        val byId = units.associateBy { it.id }.toSortedMap()
        require(byId.size == units.size) { "context unit ids must be unique" }
        for ((placement, charge) in listOf(ContextPlacement.System to budget.system,
            ContextPlacement.Repository to budget.repository)) {
            require(sum(byId.values.filter { it.placement == placement }) { it.cost.tokens.value } <=
                charge.tokens.value.toBigInteger()) { "referenced $placement content exceeds its fixed charge" }
        }
        val bundles = byId.keys.associateWith { closure(it, byId) }
        val mandatoryRoots = byId.values.filter { it.mandatory || it.placement != ContextPlacement.K }.map { it.id }
        val mandatory = mandatoryRoots.flatMap { bundles.getValue(it).ids }.toSet()
        val selected = mandatory.toMutableSet()
        val limit = budget.alpha.multiply(BigDecimal.valueOf(budget.profileTokens.value))
            .setScale(0, RoundingMode.FLOOR).toBigIntegerExact()
        val fixed = sum(listOfNotNull(budget.system, budget.repository, budget.pinnedHistory,
            budget.retainedProtocol, budget.effectiveHistory, budget.reserves)) { it.tokens.value }
        val available = if (budget.effectiveHistory == null) null else limit - fixed
        fun tokens(ids: Collection<ContextUnitId>): BigInteger = sum(ids.map(byId::getValue)
            .filter { it.placement == ContextPlacement.K }) { it.cost.tokens.value }
        var used = tokens(selected)
        val status = when {
            available == null -> ContextSelectionStatus.UnknownHistory
            mandatoryRoots.any { bundles.getValue(it).issues.isNotEmpty() } -> ContextSelectionStatus.NeedsEvidence
            used > available -> ContextSelectionStatus.Capacity
            else -> ContextSelectionStatus.Fit
        }
        val picks = ArrayList<ContextPick>()
        if (status == ContextSelectionStatus.Fit) {
            // ponytail: O(n³ log n) scan/sorting; optimize only with measured workloads and fresh marginal keys.
            while (true) {
                val candidates = byId.values.filter { it.id !in selected && bundles.getValue(it.id).issues.isEmpty() }
                    .map { unit ->
                        val added = bundles.getValue(unit.id).ids - selected
                        ContextPick(unit.id, added, tokens(added), sum(added.map(byId::getValue)) { it.gain })
                    }.filter { it.gain.signum() > 0 && it.tokens <= available!! - used }
                val pick = candidates.minWithOrNull { a, b -> compare(a, b, byId) } ?: break
                selected += pick.addedIds
                used += pick.tokens
                picks += pick
            }
        }
        val omissions = byId.keys.filter { it !in selected }.associateWith { id ->
            when {
                bundles.getValue(id).issues.isNotEmpty() -> ContextOmission.Dependency
                status != ContextSelectionStatus.Fit -> ContextOmission.SelectionRefused
                (bundles.getValue(id).ids - selected).all { byId.getValue(it).gain == 0L } -> ContextOmission.NoGain
                else -> ContextOmission.Budget
            }
        }
        return ContextSelection(status, byId, budget,
            ContextArithmetic(limit, fixed, available, used, if (available == null) null else fixed + used),
            mandatory, selected, omissions, bundles.values.flatMap { it.issues }, picks)
    }

    private fun compare(a: ContextPick, b: ContextPick, units: Map<ContextUnitId, ContextUnit>): Int {
        val priority = units.getValue(a.root).priority.compareTo(units.getValue(b.root).priority)
        if (priority != 0) return priority
        val ratio = when {
            a.tokens.signum() == 0 && b.tokens.signum() == 0 -> 0
            a.tokens.signum() == 0 -> -1
            b.tokens.signum() == 0 -> 1
            else -> (b.gain * a.tokens).compareTo(a.gain * b.tokens)
        }
        return if (ratio != 0) ratio else a.root.compareTo(b.root)
    }

    private data class Bundle(val ids: Set<ContextUnitId>, val issues: List<ContextIssue>)

    private fun closure(root: ContextUnitId, units: Map<ContextUnitId, ContextUnit>): Bundle {
        val colors = hashMapOf(root to 1)
        val stack = ArrayDeque<Pair<ContextUnitId, Iterator<ContextUnitId>>>()
        stack.addLast(root to units.getValue(root).dependsOn.iterator())
        val issues = linkedSetOf<ContextIssue>()
        while (stack.isNotEmpty()) {
            val (id, dependencies) = stack.last()
            if (!dependencies.hasNext()) {
                colors[id] = 2
                stack.removeLast()
                continue
            }
            val dependency = dependencies.next()
            when {
                dependency !in units -> issues += ContextIssue(root, dependency, ContextIssueCode.MissingDependency)
                colors[dependency] == 1 -> issues += ContextIssue(root, dependency, ContextIssueCode.DependencyCycle)
                dependency !in colors -> {
                    colors[dependency] = 1
                    stack.addLast(dependency to units.getValue(dependency).dependsOn.iterator())
                }
            }
        }
        return Bundle(colors.keys, issues.sortedWith(compareBy({ it.code }, { it.dependency })))
    }

    private fun <T> sum(values: Iterable<T>, value: (T) -> Long): BigInteger =
        values.fold(BigInteger.ZERO) { total, item -> total + value(item).toBigInteger() }
}
