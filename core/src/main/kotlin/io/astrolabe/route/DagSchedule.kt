package io.astrolabe.route

import io.astrolabe.contract.IncrementStatus
import io.astrolabe.graph.cycles
import io.astrolabe.provider.Money
import java.math.BigDecimal

/** Exact subset/last DP for a supplied pairwise model, never a runtime dispatch or billing guarantee. D-60. */
public object DagSchedule {
    @JvmStatic
    public fun solve(problem: ScheduleProblem, limits: ScheduleLimits): ScheduleResult {
        var states = 0L
        var transitions = 0L
        fun refuse(status: ScheduleStatus, issues: List<String>) =
            ScheduleResult(problem, status, emptyList(), null, null, null, states, transitions, issues)
        if (problem.costs.model != ScheduleCostModel.Pairwise || !problem.constraintsComplete)
            return refuse(ScheduleStatus.Unsupported, listOf("requires pairwise costs and all mandatory order constraints"))
        val byId = problem.graph.increments.associateBy { it.id }
        val ids = problem.selected.toList()
        val issues = mutableListOf<String>()
        for (id in ids) {
            if (byId[id] == null) issues += "unknown selected increment $id"
            else if (byId.getValue(id).status != IncrementStatus.Pending) issues += "non-pending increment $id"
            if (id in problem.completedPrerequisites) issues += "selected increment $id also declared completed"
        }
        problem.precedence.filter { it.from !in problem.selected || it.to !in problem.selected }
            .forEach { issues += "precedence outside selected slice $it" }
        if (issues.isNotEmpty()) return refuse(ScheduleStatus.InvalidInput, issues)
        val dependencies = ids.associateWith { id ->
            byId.getValue(id).dependsOn.toSet() + problem.precedence.filter { it.to == id }.map { it.from }
        }
        dependencies.forEach { (id, deps) -> (deps - problem.selected).sorted().forEach { external ->
            if (external !in problem.completedPrerequisites)
                issues += "$id needs explicit completion of $external"
            else if (byId[external]?.status?.let { it != IncrementStatus.Verified } == true)
                issues += "$id completion declaration conflicts with graph status of $external"
        } }
        if (issues.isNotEmpty()) return refuse(ScheduleStatus.UnsatisfiedPrerequisites, issues)
        val edges = dependencies.mapValues { (_, deps) -> deps.intersect(problem.selected) }
        val cycles = cycles(edges)
        if (cycles.isNotEmpty()) return refuse(ScheduleStatus.InvalidInput, cycles.map { "dependency cycle $it" })
        val n = ids.size
        if (n > limits.maxIncrements || n > 30)
            return refuse(ScheduleStatus.ResourceLimit, listOf("$n increments exceed configured/indexing limit"))
        val entries = (1L shl n) * n
        if (entries > limits.maxTableEntries || entries > Int.MAX_VALUE)
            return refuse(ScheduleStatus.ResourceLimit, listOf("requires $entries dense table entries"))
        val costs = problem.costs
        if ((costs.contexts.keys + costs.initial.keys + costs.fixed.keys).any { it !in problem.selected } ||
            costs.switches.keys.any { it.from !in problem.selected || it.to !in problem.selected || it.from == it.to })
            return refuse(ScheduleStatus.InvalidInput, listOf("cost table contains entries outside directed slice"))
        for (id in ids) {
            if (id !in costs.contexts) issues += "missing context $id"
            if (costs.initial[id]?.unknown != false) issues += "missing or unknown initial cost $id"
            if (costs.fixed[id]?.unknown != false) issues += "missing or unknown fixed cost $id"
            for (other in ids) if (other != id && costs.switches[ScheduleEdge(id, other)]?.unknown != false)
                issues += "missing or unknown switch cost $id -> $other"
        }
        if (issues.isNotEmpty()) return refuse(ScheduleStatus.UnknownCosts, issues)
        fun optimal(order: List<String>, variable: BigDecimal): ScheduleResult {
            val fixed = Money(costs.currency, costs.fixed.values.sumOf { it.amount })
            val moving = Money(costs.currency, variable)
            return ScheduleResult(problem, ScheduleStatus.Optimal, order, moving, fixed, moving + fixed,
                states, transitions, emptyList())
        }
        if (n == 0) return optimal(emptyList(), BigDecimal.ZERO)
        val index = ids.withIndex().associate { it.value to it.index }
        val prerequisites = IntArray(n) { i -> edges.getValue(ids[i]).fold(0) { mask, id -> mask or (1 shl index.getValue(id)) } }
        val dp = arrayOfNulls<BigDecimal>(entries.toInt())
        val parent = IntArray(entries.toInt()) { -1 }
        fun limited(): ScheduleResult = refuse(ScheduleStatus.ResourceLimit,
            listOf("DP transition limit ${limits.maxTransitions} reached"))
        for (v in 0 until n) if (prerequisites[v] == 0) {
            if (transitions == limits.maxTransitions) return limited()
            transitions++
            dp[(1 shl v) * n + v] = costs.initial.getValue(ids[v]).amount
            states++
        }
        val masks = 1 shl n
        for (mask in 1 until masks) for (last in 0 until n) {
            val at = mask * n + last
            val cost = dp[at] ?: continue
            for (v in 0 until n) if (mask and (1 shl v) == 0 && mask and prerequisites[v] == prerequisites[v]) {
                if (transitions == limits.maxTransitions) return limited()
                transitions++
                val next = (mask or (1 shl v)) * n + v
                val candidate = cost + costs.switches.getValue(ScheduleEdge(ids[last], ids[v])).amount
                val previous = dp[next]
                if (previous == null || candidate < previous) {
                    if (previous == null) states++
                    dp[next] = candidate
                    parent[next] = at
                }
            }
        }
        val full = (masks - 1) * n
        var best = -1
        for (last in 0 until n) if (dp[full + last] != null &&
            (best == -1 || dp[full + last]!! < dp[best]!!)) best = full + last
        check(best != -1) { "a validated finite DAG has a topological order" }
        val order = ArrayList<String>(n)
        var cursor = best
        while (cursor >= 0) { order += ids[cursor % n]; cursor = parent[cursor] }
        order.reverse()
        return optimal(order, dp[best]!!)
    }
}
