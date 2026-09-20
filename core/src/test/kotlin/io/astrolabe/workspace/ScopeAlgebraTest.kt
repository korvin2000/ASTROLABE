package io.astrolabe.workspace

import io.astrolabe.contract.Scope
import io.astrolabe.id.WorkspaceId
import org.junit.jupiter.api.Test
import kotlin.test.*

class ScopeAlgebraTest {
    private val destination = WorkspaceId("integration")
    private val limits = ScopeSearchLimits(10_000, 20_000, 2_000_000)
    private fun scope(vararg writes: String, protected: List<String> = emptyList()): Scope = Scope(writes.toList(), protected)

    @Test fun `open scopes intersect on a future billing file`() {
        val result = ScopeAlgebra.intersection(destination, scope("src/**"), scope("**/billing.kt"), limits)
        assertEquals(ScopeResult.Overlap("src/billing.kt"), result.result)
        assertEquals(destination, result.destination)
        assertEquals(ScopeRelation.Intersection, result.relation)
    }

    @Test fun `protected exceptions are subtracted from both languages`() {
        assertEquals(ScopeResult.Disjoint,
            ScopeAlgebra.intersection(destination, scope("src/**", protected = listOf("**/billing.kt")), scope("**/billing.kt"), limits).result)
        assertEquals(ScopeResult.Disjoint,
            ScopeAlgebra.intersection(destination, scope("src/**"), scope("**", protected = listOf("src/")), limits).result)
    }

    @Test fun `difference proves containment or returns a child path outside parent`() {
        val child = scope("src/**")
        assertEquals(ScopeResult.Disjoint, ScopeAlgebra.difference(destination, child, scope("src/"), limits).result)
        assertEquals(ScopeResult.Overlap("src/a"),
            ScopeAlgebra.difference(destination, child, scope("src/**", protected = listOf("src/a")), limits).result)
    }

    @Test fun `canonical paths exclude empty and dot segments and drive roots`() {
        for (pattern in listOf("", "/", "./", ".", "..", "../x", "a/../x", "/root", "a//b", "C:/a")) {
            assertEquals(ScopeResult.Disjoint,
                ScopeAlgebra.intersection(destination, scope(pattern), scope("**"), limits).result, pattern)
        }
        assertEquals(ScopeResult.Overlap("a"),
            ScopeAlgebra.intersection(destination, scope("**/a"), scope("a"), limits).result)
    }

    @Test fun `literal glob directory and Unicode semantics match the existing matcher`() {
        for ((pattern, path) in listOf(
            "billing.kt" to "src/billing.kt", "src/" to "src", "a/b" to "a/b/c",
            "**/x" to "x", "a/**/b" to "a/b", "a/*/b" to "a/c/b", "a/***b" to "a/x/b",
            "./src\\*.kt/" to "src/a.kt", "a+[b]?.kt" to "a+[b]x.kt", "?/?.kt" to "🧭/β.kt",
            "*/x" to "\n/x", "**/x" to "a/b/x", "**//" to "a/b",
        )) {
            assertTrue(PathPattern.matches(pattern, path), "$pattern on $path")
            val result = ScopeAlgebra.intersection(destination, scope(pattern), scope("$path/"), limits).result
            assertIs<ScopeResult.Overlap>(result)
            assertTrue(PathPattern.matches(pattern, result.witnessPath))
        }
        val newline = ScopeAlgebra.difference(destination, scope("**"), scope("**/"), limits).result
        assertEquals(ScopeResult.Overlap("\u0085"), newline) // Java dot excludes NEL; bare ** includes it.
        assertEquals(ScopeResult.Disjoint,
            ScopeAlgebra.intersection(destination, scope("?"), scope("aa/"), limits).result)
        assertEquals(ScopeResult.Disjoint,
            ScopeAlgebra.intersection(destination, scope("src/*"), scope("src/a/b/"), limits).result)
    }

    @Test fun `resource exhaustion and malformed Unicode never prove disjointness`() {
        for ((budget, reason) in listOf(
            ScopeSearchLimits(1, 100, 1000) to ScopeUnknownReason.NfaLimit,
            ScopeSearchLimits(100, 1, 1000) to ScopeUnknownReason.StateLimit,
            ScopeSearchLimits(100, 100, 1) to ScopeUnknownReason.TransitionLimit,
        )) {
            val result = ScopeAlgebra.intersection(destination, scope("a"), scope("a"), budget)
            assertEquals(reason, assertIs<ScopeResult.Unknown>(result.result).reason)
            assertTrue(result.nfaStates <= budget.nfaStates)
            assertTrue(result.visitedStates <= budget.productStates)
            assertTrue(result.exploredTransitions <= budget.transitions)
        }
        assertEquals(ScopeUnknownReason.UnsupportedUnicode, assertIs<ScopeResult.Unknown>(
            ScopeAlgebra.intersection(destination, scope("\uD800"), scope("**"), limits).result).reason)
        assertFailsWith<IllegalArgumentException> { ScopeSearchLimits(0, 1, 1) }
    }

    @Test fun `scopes are snapshots and union order cannot change the proof or witness`() {
        val paths = mutableListOf("src/**", "tests/", "src/**")
        val protected = mutableListOf("**/secret", "**/private")
        val left = Scope(paths, protected)
        val right = scope("**/billing.kt", "src/**")
        val first = ScopeAlgebra.intersection(destination, left, right, limits)
        val permuted = ScopeAlgebra.intersection(destination, Scope(paths.reversed(), protected.reversed()), right, limits)
        assertEquals(first.result, permuted.result)
        assertEquals(first.visitedStates, permuted.visitedStates)
        paths.clear(); protected.clear()
        assertTrue(first.left.writePaths.isNotEmpty())
        assertTrue(first.left.protectedPaths.isNotEmpty())
        assertFailsWith<UnsupportedOperationException> { (first.left.writePaths as MutableList).clear() }
        assertEquals(first.result, ScopeAlgebra.intersection(destination, right, first.left, limits).result)
    }

    @Test fun `deep paths are processed iteratively and witnesses are shortest`() {
        val path = "a/".repeat(600) + "x"
        assertEquals(ScopeResult.Overlap(path),
            ScopeAlgebra.intersection(destination, scope(path), scope(path), limits).result)
        assertTrue(PathPattern.matches("a/*", "a/a"))
        assertFalse(PathPattern.matches("a/*", "a/a/a"))
        assertEquals(ScopeResult.Overlap("a/a/a"),
            ScopeAlgebra.intersection(destination, scope("**/a", protected = listOf("a?", "a/*")), scope("a/a/"), limits).result)
    }
}
