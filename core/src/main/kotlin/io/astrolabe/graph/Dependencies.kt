package io.astrolabe.graph

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment

/** Iterative Tarjan: O(V + E) time/space before sorting diagnostics, including very deep plans. */
internal fun cycles(edges: Map<String, Set<String>>): List<List<String>> {
    data class Frame(val id: String, val remaining: Iterator<String>)

    val index = HashMap<String, Int>()
    val low = HashMap<String, Int>()
    val active = HashSet<String>()
    val stack = ArrayDeque<String>()
    val frames = ArrayDeque<Frame>()
    val result = ArrayList<List<String>>()
    fun enter(id: String) {
        index[id] = index.size
        low[id] = index.getValue(id)
        active += id
        stack.addLast(id)
        frames.addLast(Frame(id, edges.getValue(id).iterator()))
    }

    for (root in edges.keys) {
        if (root in index) continue
        enter(root)
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (frame.remaining.hasNext()) {
                val child = frame.remaining.next()
                if (child !in edges) continue
                if (child !in index) enter(child)
                else if (child in active) low[frame.id] = minOf(low.getValue(frame.id), index.getValue(child))
            } else {
                frames.removeLast()
                if (frames.isNotEmpty()) {
                    val parent = frames.last().id
                    low[parent] = minOf(low.getValue(parent), low.getValue(frame.id))
                }
                if (low.getValue(frame.id) == index.getValue(frame.id)) {
                    val component = ArrayList<String>()
                    do {
                        val member = stack.removeLast()
                        active.remove(member)
                        component += member
                    } while (member != frame.id)
                    if (component.size > 1 || frame.id in edges.getValue(frame.id)) result += component.sorted()
                }
            }
        }
    }
    return result.sortedBy { it.first() }
}

/** Kahn's traversal computes longest prerequisite depth, independent of input/queue order. */
internal fun depths(edges: Map<String, Set<String>>): Map<String, Int> {
    val remaining = edges.mapValues { it.value.size }.toMutableMap()
    val dependents = HashMap<String, MutableList<String>>()
    for ((id, dependencies) in edges) for (dependency in dependencies) {
        check(dependency in edges) { "$id references unknown dependency $dependency" }
        dependents.getOrPut(dependency) { ArrayList() }.add(id)
    }
    val ready = ArrayDeque(edges.keys.filter { remaining.getValue(it) == 0 })
    val depth = edges.keys.associateWith { 0 }.toMutableMap()
    var visited = 0
    while (ready.isNotEmpty()) {
        val id = ready.removeFirst()
        visited++
        for (child in dependents[id].orEmpty()) {
            depth[child] = maxOf(depth.getValue(child), depth.getValue(id) + 1)
            remaining[child] = remaining.getValue(child) - 1
            if (remaining.getValue(child) == 0) ready.addLast(child)
        }
    }
    check(visited == edges.size) { "dependency cycle requires a joint increment or explicit replanning" }
    return depth
}

/** Only nodes with requirement prerequisites need a reachability search; stop once all producers are found. */
internal fun requirementDependencies(
    contract: Contract,
    active: List<Increment>,
    owners: Map<String, List<Increment>>,
): List<GraphIssue> {
    val requirements = contract.requirements.associateBy { it.id }
    val byId = active.associateBy { it.id }
    val issues = ArrayList<GraphIssue>()
    for (increment in active.sortedBy { it.id }) {
        val needed = increment.requirementIds.flatMap { requirements[it]?.dependsOn.orEmpty() }
            .flatMap { owners[it].orEmpty() }.mapTo(HashSet()) { it.id }
        needed.remove(increment.id) // A joint increment can resolve a requirement cycle.
        if (needed.isEmpty()) continue
        val pending = ArrayDeque(increment.dependsOn)
        val visited = HashSet<String>()
        while (pending.isNotEmpty() && needed.isNotEmpty()) {
            val id = pending.removeLast()
            if (!visited.add(id)) continue
            needed.remove(id)
            byId[id]?.dependsOn?.let { pending.addAll(it) }
        }
        if (needed.isNotEmpty()) issues += GraphIssue(
            GraphIssueCode.MissingRequirementDependency, listOf(increment.id),
            "requirement prerequisites need increment dependencies on ${needed.sorted().joinToString()}",
        )
    }
    return issues
}
