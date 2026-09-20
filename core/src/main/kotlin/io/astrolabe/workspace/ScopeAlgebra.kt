package io.astrolabe.workspace

import io.astrolabe.contract.Scope
import io.astrolabe.id.WorkspaceId
import java.util.BitSet
import java.util.Collections
import java.util.regex.PatternSyntaxException

public enum class ScopeRelation { Intersection, Difference }
public enum class ScopeUnknownReason { NfaLimit, StateLimit, TransitionLimit, UnsupportedUnicode, MatcherFailure }

public data class ScopeSearchLimits(val nfaStates: Int, val productStates: Int, val transitions: Long) {
    init { require(nfaStates >= 1 && productStates >= 1 && transitions >= 1) }
}

/** For Difference, Disjoint proves containment; Overlap names a left-scope path outside the right scope. */
public sealed interface ScopeResult {
    public data object Disjoint : ScopeResult
    public data class Overlap(val witnessPath: String) : ScopeResult
    public data class Unknown(val reason: ScopeUnknownReason, val detail: String) : ScopeResult
}

public class ScopeAnalysis internal constructor(
    public val destination: WorkspaceId,
    public val relation: ScopeRelation,
    public val left: Scope,
    public val right: Scope,
    public val limits: ScopeSearchLimits,
    public val result: ScopeResult,
    public val nfaStates: Int,
    public val visitedStates: Int,
    public val exploredTransitions: Long,
) {
    public val policyVersion: String get() = "scope-algebra-v1"
}

/**
 * Exact-case lexical analysis of open scopes in ONE integration destination (D-58).
 * Witnesses may name future paths. This does not validate OS names, case aliases, symlinks or write authority.
 */
public object ScopeAlgebra {
    @JvmStatic
    public fun intersection(destination: WorkspaceId, left: Scope, right: Scope, limits: ScopeSearchLimits): ScopeAnalysis =
        analyze(destination, left, right, limits, ScopeRelation.Intersection)

    @JvmStatic
    public fun difference(destination: WorkspaceId, child: Scope, parent: Scope, limits: ScopeSearchLimits): ScopeAnalysis =
        analyze(destination, child, parent, limits, ScopeRelation.Difference)

    private fun analyze(destination: WorkspaceId, a: Scope, b: Scope, limits: ScopeSearchLimits, relation: ScopeRelation): ScopeAnalysis {
        fun snapshot(scope: Scope): Scope = Scope(
            Collections.unmodifiableList(scope.writePaths.distinct().sorted()),
            Collections.unmodifiableList(scope.protectedPaths.distinct().sorted()),
        )
        val left = snapshot(a)
        val right = snapshot(b)
        val nfa = ScopeNfa(limits.nfaStates)
        var visited = 0
        var transitions = 0L
        fun result(outcome: ScopeResult): ScopeAnalysis =
            ScopeAnalysis(destination, relation, left, right, limits, outcome, nfa.states.size, visited, transitions)
        try {
            for ((index, patterns) in listOf(left.writePaths, left.protectedPaths, right.writePaths, right.protectedPaths).withIndex())
                for (pattern in patterns) nfa.add(pattern, 1 shl index)
            val alphabet = ScopeAlphabet.representatives(nfa.literals)
            val start = ProductState(nfa.closure(BitSet().apply { set(0) }), 0)
            val nodes = arrayListOf(SearchNode(start, -1, -1))
            val seen = hashMapOf(start to 0)
            visited = 1
            var head = 0
            while (head < nodes.size) {
                val node = nodes[head]
                if (canonicalEnd(node.state.canonical) && accepts(nfa.flags(node.state.states), relation)) {
                    val path = witness(nodes, head)
                    val compatible = left.allowsWrite(path) && when (relation) {
                        ScopeRelation.Intersection -> right.allowsWrite(path)
                        ScopeRelation.Difference -> !right.allowsWrite(path)
                    }
                    return result(if (compatible) ScopeResult.Overlap(path) else ScopeResult.Unknown(
                        ScopeUnknownReason.MatcherFailure, "constructed witness disagrees with PathPattern",
                    ))
                }
                for (point in alphabet) {
                    if (transitions == limits.transitions) throw ScopeLimit(ScopeUnknownReason.TransitionLimit)
                    transitions++
                    val canonical = canonicalStep(node.state.canonical, point)
                    if (canonical < 0) continue
                    val next = ProductState(nfa.step(node.state.states, point), canonical)
                    if (next in seen) continue
                    if (nodes.size == limits.productStates) throw ScopeLimit(ScopeUnknownReason.StateLimit)
                    seen[next] = nodes.size
                    nodes += SearchNode(next, head, point)
                    visited++
                }
                head++
            }
            return result(ScopeResult.Disjoint)
        } catch (limit: ScopeLimit) {
            return result(ScopeResult.Unknown(limit.reason, "analysis stopped before an exhaustive proof"))
        } catch (unsupported: ScopeUnicode) {
            return result(ScopeResult.Unknown(ScopeUnknownReason.UnsupportedUnicode, "unpaired surrogate in pattern"))
        } catch (failure: PatternSyntaxException) {
            return result(ScopeResult.Unknown(ScopeUnknownReason.MatcherFailure, "PathPattern rejected witness verification"))
        } catch (failure: StackOverflowError) {
            // Only the existing regex-based witness verifier recurses; search and construction are iterative.
            return result(ScopeResult.Unknown(ScopeUnknownReason.MatcherFailure, "PathPattern verification exhausted its stack"))
        }
    }
}

private data class ProductState(val states: BitSet, val canonical: Int)
private data class SearchNode(val state: ProductState, val parent: Int, val point: Int)
private class ScopeLimit(val reason: ScopeUnknownReason) : RuntimeException()
private class ScopeUnicode : RuntimeException()

private fun accepts(flags: Int, relation: ScopeRelation): Boolean {
    val left = flags and 1 != 0 && flags and 2 == 0
    val right = flags and 4 != 0 && flags and 8 == 0
    return left && if (relation == ScopeRelation.Intersection) right else !right
}

private fun witness(nodes: List<SearchNode>, end: Int): String {
    val points = ArrayList<Int>()
    var current = end
    while (nodes[current].parent >= 0) {
        points += nodes[current].point
        current = nodes[current].parent
    }
    return buildString { for (i in points.indices.reversed()) appendCodePoint(points[i]) }
}

// segment: empty/dot/dot-dot/other; prefix: start/one ASCII letter/other; bit 16: nonblank seen.
private fun canonicalStep(state: Int, point: Int): Int {
    val segment = state and 3
    val prefix = (state shr 2) and 3
    if (prefix == 1 && point == ':'.code) return -1
    val nextPrefix = if (prefix == 0 && asciiLetter(point)) 1 else 2
    val nextSegment = when {
        point == '/'.code -> if (segment == 3) 0 else return -1
        point == '.'.code && segment < 2 -> segment + 1
        else -> 3
    }
    val nonblank = state and 16 != 0 || !blank(point)
    return nextSegment or (nextPrefix shl 2) or if (nonblank) 16 else 0
}

private fun canonicalEnd(state: Int): Boolean = state and 3 == 3 && state and 16 != 0
private fun asciiLetter(point: Int): Boolean = point in 65..90 || point in 97..122
private fun blank(point: Int): Boolean = Character.isWhitespace(point) || Character.isSpaceChar(point)
private fun lineTerminator(point: Int): Boolean = point == 10 || point == 13 || point == 0x85 || point == 0x2028 || point == 0x2029
private const val ANY = -1
private const val SEGMENT = -2
private const val DOT = -3

/** Tagged Thompson NFA: final flags independently record the four unions. No eager DFA complements. */
private class ScopeNfa(private val limit: Int) {
    class State(var flags: Int = 0, val epsilon: MutableList<Int> = ArrayList(), val edges: MutableList<Edge> = ArrayList())
    data class Edge(val symbol: Int, val target: Int)
    val states = arrayListOf(State())
    val literals = HashSet<Int>()

    private fun state(): Int {
        if (states.size == limit) throw ScopeLimit(ScopeUnknownReason.NfaLimit)
        states += State()
        return states.lastIndex
    }
    private fun edge(from: Int, symbol: Int, to: Int) {
        states[from].edges += Edge(symbol, to)
        if (symbol >= 0) literals += symbol
    }
    private fun literal(from: Int, point: Int): Int = state().also { edge(from, point, it) }
    private fun star(from: Int, symbol: Int): Int = state().also {
        states[from].epsilon += it
        edge(from, symbol, from)
    }
    private fun optionalDirectory(from: Int, symbol: Int): Int {
        val end = state()
        val loop = state()
        states[from].epsilon.addAll(listOf(end, loop))
        edge(loop, symbol, loop)
        edge(loop, '/'.code, end)
        return end
    }

    fun add(pattern: String, flag: Int) {
        val raw = pattern.replace('\\', '/').removePrefix("./")
        if (raw.isEmpty() || raw == "/") return
        if (raw.codePoints().anyMatch { it in 0xD800..0xDFFF }) throw ScopeUnicode()
        var current = state()
        states[0].epsilon += current
        val glob = raw.any { it == '*' || it == '?' }
        if (raw == "**") current = star(current, ANY)
        else if (!glob) {
            if ('/' !in raw) current = optionalDirectory(current, ANY)
            for (point in raw.trimEnd('/').codePoints().toArray()) current = literal(current, point)
            if ('/' in raw) {
                val end = state()
                states[current].epsilon += end
                val loop = literal(current, '/'.code)
                edge(loop, ANY, loop)
                states[loop].epsilon += end
                current = end
            }
        } else {
            val points = raw.trimEnd('/').codePoints().toArray()
            var index = 0
            while (index < points.size) {
                val point = points[index]
                current = when {
                    point == '*'.code && points.getOrNull(index + 1) == '*'.code -> {
                        index++
                        if (points.getOrNull(index + 1) == '/'.code) {
                            index++
                            optionalDirectory(current, DOT)
                        } else star(current, DOT)
                    }
                    point == '*'.code -> star(current, SEGMENT)
                    point == '?'.code -> literal(current, SEGMENT)
                    else -> literal(current, point)
                }
                index++
            }
        }
        states[current].flags = states[current].flags or flag
    }

    fun closure(seed: BitSet): BitSet {
        val result = seed.clone() as BitSet
        val pending = ArrayDeque<Int>()
        var position = seed.nextSetBit(0)
        while (position >= 0) { pending.addLast(position); position = seed.nextSetBit(position + 1) }
        while (pending.isNotEmpty()) {
            for (target in states[pending.removeLast()].epsilon) if (!result[target]) {
                result.set(target)
                pending.addLast(target)
            }
        }
        return result
    }

    fun step(active: BitSet, point: Int): BitSet {
        val next = BitSet()
        var position = active.nextSetBit(0)
        while (position >= 0) {
            for (edge in states[position].edges) {
                val matches = when (edge.symbol) {
                    ANY -> true
                    DOT -> !lineTerminator(point)
                    SEGMENT -> point != '/'.code
                    else -> point == edge.symbol
                }
                if (matches) next.set(edge.target)
            }
            position = active.nextSetBit(position + 1)
        }
        return closure(next)
    }

    fun flags(active: BitSet): Int {
        var flags = 0
        var position = active.nextSetBit(0)
        while (position >= 0) {
            flags = flags or states[position].flags
            position = active.nextSetBit(position + 1)
        }
        return flags
    }
}

/** Every literal is a singleton. All remaining characters with identical predicate values are equivalent. */
private object ScopeAlphabet {
    private val preferred = "abcdefghijklmnopqrstuvwxyz0123456789_-./".codePoints().toArray().toList()
    private fun rank(point: Int): Int = preferred.indexOf(point).let { if (it >= 0) it else preferred.size + point }
    private fun category(point: Int): Int = when {
        point == 0 || point == '\\'.code || point in 0xD800..0xDFFF -> -1
        point == '/'.code -> 1
        point == '.'.code -> 2
        point == ':'.code -> 3
        asciiLetter(point) -> 4
        else -> (if (blank(point)) 8 else 0) or (if (lineTerminator(point)) 16 else 0)
    }
    private val ranges: Map<Int, List<IntRange>> by lazy {
        val result = linkedMapOf<Int, MutableList<IntRange>>()
        var start = 0
        var previous = category(0)
        for (point in 1..0x110000) {
            val next = if (point == 0x110000) -2 else category(point)
            if (next != previous) {
                if (previous >= 0) result.getOrPut(previous) { ArrayList() } += start until point
                start = point
                previous = next
            }
        }
        result
    }

    fun representatives(literals: Set<Int>): List<Int> {
        val points = literals.filter { category(it) >= 0 }.toMutableSet()
        for ((kind, intervals) in ranges) {
            val preferredPoint = preferred.firstOrNull { category(it) == kind && it !in literals }
            val point = preferredPoint ?: intervals.firstNotNullOfOrNull { interval ->
                var candidate = interval.first
                while (candidate <= interval.last && candidate in literals) candidate++
                candidate.takeIf { it <= interval.last }
            }
            if (point != null) points += point
        }
        return points.sortedBy(::rank)
    }
}
