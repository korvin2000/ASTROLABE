package io.astrolabe.eval

import io.astrolabe.id.Digest
import java.math.BigDecimal

/** Exact, bounded metadata assignment. D-59; runner integrity remains the caller's responsibility. */
public object WorkloadSplit {
    @JvmStatic
    public fun validate(
        design: WorkloadDesign,
        expectedDesign: Digest,
        assignment: Map<WorkloadTrialKey, WorkloadPartition>,
    ): WorkloadSplitResult {
        val problem = Problem(design)
        val supplied = assignment.toMap()
        val issues = mutableListOf<String>()
        if (expectedDesign != design.fingerprint) issues += "design fingerprint mismatch"
        val keys = design.tasks.flatMap { t -> t.repetitions.map { WorkloadTrialKey(t.id, it) } }.toSet()
        (keys - supplied.keys).forEach { issues += "missing trial $it" }
        (supplied.keys - keys).sortedWith(compareBy(WorkloadTrialKey::task, WorkloadTrialKey::repetition))
            .forEach { issues += "unexpected trial $it" }
        if (issues.isNotEmpty()) return problem.result(WorkloadSplitStatus.InvalidInput, issues = issues)
        problem.unknownFailure()?.let { return it }
        for (group in problem.groups) {
            val parts = group.tasks.flatMap { id -> problem.tasks.getValue(id).repetitions.map {
                supplied.getValue(WorkloadTrialKey(id, it))
            } }.toSet()
            if (parts.size != 1) issues += "split component ${group.tasks}"
            else if (parts.single() !in group.allowed) issues += "disallowed partition for ${group.tasks}"
        }
        val totals = problem.totals(supplied)
        for (q in design.policy.quotas) {
            val mass = totals.single { it.partition == q.partition && it.stratum == q.stratum }.weight
            if (mass < q.minimum || mass > q.maximum) issues += "quota violation ${q.partition}/${q.stratum}"
        }
        return if (issues.isEmpty()) problem.failure() ?: problem.result(WorkloadSplitStatus.Feasible, supplied)
        else problem.result(WorkloadSplitStatus.InvalidInput, supplied, issues = issues)
    }

    /** [maxNodes] bounds attempted component assignments, not metadata preprocessing; zero is permitted. */
    @JvmStatic
    public fun solve(design: WorkloadDesign, maxNodes: Long): WorkloadSplitResult {
        require(maxNodes >= 0)
        val problem = Problem(design)
        problem.failure()?.let { return it }
        val groups = problem.groups
        val quotas = design.policy.quotas
        val amounts = groups.map { g -> quotas.map { q ->
            g.tasks.sumOf { id -> problem.tasks.getValue(id).let { t ->
                if (q.stratum == null || q.stratum == t.stratum) t.weight!! else BigDecimal.ZERO
            } }
        } }
        // An optimistic bound: each remaining component may contribute to every allowed partition.
        val suffix = Array(groups.size + 1) { Array(quotas.size) { BigDecimal.ZERO } }
        for (d in groups.indices.reversed()) for (q in quotas.indices) {
            suffix[d][q] = suffix[d + 1][q] +
                if (quotas[q].partition in groups[d].allowed) amounts[d][q] else BigDecimal.ZERO
        }
        val mass = Array(quotas.size) { BigDecimal.ZERO }
        fun possible(depth: Int): Boolean = quotas.indices.all { q ->
            mass[q] <= quotas[q].maximum && mass[q] + suffix[depth][q] >= quotas[q].minimum
        }
        if (!possible(0)) return problem.result(WorkloadSplitStatus.Infeasible,
            complete = true, issues = listOf("quotas exceed attainable mass"))
        fun bound(depth: Int): BigDecimal = quotas.indices.sumOf { q ->
            val upper = mass[q] + suffix[depth][q]
            val target = quotas[q].target
            val distance = when { target < mass[q] -> mass[q] - target; target > upper -> target - upper
                else -> BigDecimal.ZERO }
            quotas[q].penalty * distance
        }
        val choices = groups.map { it.allowed.toList() }
        val next = IntArray(groups.size)
        val selected = IntArray(groups.size)
        fun adjust(depth: Int, undo: Boolean) {
            for (q in quotas.indices) if (quotas[q].partition == choices[depth][selected[depth]]) {
                mass[q] = if (undo) mass[q] - amounts[depth][q] else mass[q] + amounts[depth][q]
            }
        }
        var depth = 0
        var visited = 0L
        var best: BigDecimal? = null
        var witness: IntArray? = null
        while (depth >= 0) {
            if (depth == groups.size) {
                val score = bound(depth)
                if (best == null || score < best) { best = score; witness = selected.copyOf() }
                depth--
                if (depth >= 0) adjust(depth, true)
            } else if (next[depth] == choices[depth].size) {
                next[depth] = 0
                depth--
                if (depth >= 0) adjust(depth, true)
            } else {
                if (visited == maxNodes) break
                visited++
                selected[depth] = next[depth]++
                adjust(depth, false)
                if (possible(depth + 1) && (best == null || bound(depth + 1) < best)) depth++
                else adjust(depth, true)
            }
        }
        val assignment = linkedMapOf<WorkloadTrialKey, WorkloadPartition>()
        witness?.let { w -> groups.forEachIndexed { d, group -> group.tasks.forEach { id ->
            problem.tasks.getValue(id).repetitions.forEach {
                assignment[WorkloadTrialKey(id, it)] = choices[d][w[d]]
            }
        } } }
        val complete = depth < 0
        val status = when {
            !complete -> WorkloadSplitStatus.SearchLimit
            witness == null -> WorkloadSplitStatus.Infeasible
            else -> WorkloadSplitStatus.Optimal
        }
        val issues = when (status) {
            WorkloadSplitStatus.SearchLimit -> listOf("assignment limit $maxNodes reached; ${groups.size} components")
            WorkloadSplitStatus.Infeasible -> listOf("all allowed component assignments exhausted or soundly pruned")
            else -> emptyList()
        }
        return problem.result(status, assignment, visited, complete, issues)
    }
}

private class Problem(val design: WorkloadDesign) {
    val tasks = design.tasks.associateBy { it.id }
    private val unknown = mutableListOf<String>()
    val groups: List<WorkloadGroup>
    init {
        val parent = IntArray(tasks.size) { it }
        val rank = IntArray(tasks.size)
        fun root(index: Int): Int {
            var i = index
            while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
            return i
        }
        fun join(a: Int, b: Int) {
            var x = root(a); var y = root(b)
            if (x == y) return
            if (rank[x] < rank[y]) { val swap = x; x = y; y = swap }
            parent[y] = x
            if (rank[x] == rank[y]) rank[x]++
        }
        val indices = design.tasks.mapIndexed { i, t -> t.id to i }.toMap()
        for (rule in design.policy.grouping) {
            val first = hashMapOf<String, Int>()
            design.tasks.forEachIndexed { i, task ->
                val value = when (rule) {
                    WorkloadGrouping.Repository -> task.repository
                    WorkloadGrouping.TaskFamily -> task.family
                }
                if (value == null) unknown += "${task.id}: unknown $rule"
                else first.putIfAbsent(value, i)?.let { join(it, i) }
            }
        }
        design.policy.links.forEach { join(indices.getValue(it.first), indices.getValue(it.second)) }
        design.tasks.forEach {
            if (it.stratum == null) unknown += "${it.id}: unknown stratum"
            if (it.weight == null) unknown += "${it.id}: unknown weight"
            if (design.policy.windows.isNotEmpty() && it.time == null) unknown += "${it.id}: unknown time"
        }
        groups = design.tasks.indices.groupBy(::root).values.map { members ->
            val allowed = WorkloadPartition.entries.filter { p -> members.all { i ->
                val task = design.tasks[i]
                (design.policy.allowed[task.id]?.contains(p) != false) &&
                    (task.time == null || design.policy.windows[p]?.contains(task.time) != false)
            } }.toSet()
            WorkloadGroup(members.map { design.tasks[it].id }, allowed)
        }.sortedBy { it.tasks.first() }
    }

    fun unknownFailure(): WorkloadSplitResult? =
        if (unknown.isEmpty()) null else result(WorkloadSplitStatus.UnknownMetadata, issues = unknown)

    fun failure(): WorkloadSplitResult? {
        unknownFailure()?.let { return it }
        val reasons = mutableListOf<String>()
        val total = design.tasks.sumOf { it.weight!! }
        val complex = design.tasks.filter { design.policy.strata[it.stratum] == true }.sumOf { it.weight!! }
        if (total.signum() == 0) reasons += "workload has zero total mass"
        if (complex * BigDecimal(2) < total) reasons += "complex workload mass $complex below half of $total"
        groups.filter { it.allowed.isEmpty() }.forEach { reasons += "no allowed partition for ${it.tasks}" }
        return if (reasons.isEmpty()) null else result(WorkloadSplitStatus.Infeasible,
            complete = true, issues = reasons)
    }

    fun totals(assignment: Map<WorkloadTrialKey, WorkloadPartition>): List<WorkloadMass> =
        WorkloadPartition.entries.flatMap { p -> (listOf(null) + design.policy.strata.keys).map { stratum ->
            val members = design.tasks.filter { t -> (stratum == null || t.stratum == stratum) &&
                t.repetitions.all { assignment[WorkloadTrialKey(t.id, it)] == p } }
            WorkloadMass(p, stratum, members.size, members.sumOf { it.repetitions.size.toLong() },
                members.sumOf { it.weight!! },
                members.filter { design.policy.strata[it.stratum] == true }.sumOf { it.weight!! })
        } }

    fun result(
        status: WorkloadSplitStatus,
        assignment: Map<WorkloadTrialKey, WorkloadPartition> = emptyMap(),
        visited: Long = 0,
        complete: Boolean = false,
        issues: List<String> = emptyList(),
    ): WorkloadSplitResult {
        val ordered = assignment.toSortedMap(compareBy(WorkloadTrialKey::task, WorkloadTrialKey::repetition))
        val totals = if (assignment.isEmpty() || status == WorkloadSplitStatus.InvalidInput) emptyList()
            else totals(assignment)
        val objective = if (totals.isEmpty() || status == WorkloadSplitStatus.InvalidInput) null
            else design.policy.quotas.sumOf { q -> q.penalty *
                (totals.single { it.partition == q.partition && it.stratum == q.stratum }.weight - q.target).abs() }
        return WorkloadSplitResult(design, status, groups, ordered, totals, objective, visited, complete, issues)
    }
}
