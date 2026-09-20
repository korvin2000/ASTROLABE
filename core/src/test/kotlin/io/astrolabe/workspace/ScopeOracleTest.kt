package io.astrolabe.workspace

import io.astrolabe.contract.Scope
import io.astrolabe.id.WorkspaceId
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.*

class ScopeOracleTest {
    private val destination = WorkspaceId("destination")
    private val limits = ScopeSearchLimits(10_000, 20_000, 2_000_000)

    @Test fun `independent recursive matcher agrees on exhaustive short strings`() {
        val patterns = strings("ab/*?", 3) + listOf("**/a", "a/**/b", "a***", "./a", "a/", "[a]?", "**//")
        val paths = strings("ab./", 4).filter(::canonical)
        for (pattern in patterns) for (path in paths)
            assertEquals(matches(pattern, path), PathPattern.matches(pattern, path), "pattern=$pattern path=$path")
    }

    @Test fun `generated scope languages agree with exhaustive paths and independently checked witnesses`() {
        val random = Random(515)
        val patterns = strings("ab/*?", 3) + listOf("**/a", "a/**/b", "a/", ".", "..", "a//b")
        val paths = strings("abc./", 5).filter(::canonical).sortedBy { it.length }
        fun scope(): Scope = Scope(List(random.nextInt(3)) { patterns[random.nextInt(patterns.size)] },
            List(random.nextInt(3)) { patterns[random.nextInt(patterns.size)] })
        fun allows(scope: Scope, path: String): Boolean = scope.writePaths.any { matches(it, path) } &&
            scope.protectedPaths.none { matches(it, path) }
        repeat(250) {
            val left = scope()
            val right = scope()
            for (relation in ScopeRelation.entries) {
                fun accepted(path: String): Boolean = allows(left, path) &&
                    if (relation == ScopeRelation.Intersection) allows(right, path) else !allows(right, path)
                val shortest = paths.firstOrNull(::accepted)
                val report = if (relation == ScopeRelation.Intersection)
                    ScopeAlgebra.intersection(destination, left, right, limits)
                else ScopeAlgebra.difference(destination, left, right, limits)
                when (val result = report.result) {
                    ScopeResult.Disjoint -> assertNull(shortest, "$left $relation $right")
                    is ScopeResult.Overlap -> {
                        assertTrue(canonical(result.witnessPath))
                        assertTrue(accepted(result.witnessPath), "$left $relation $right witness=${result.witnessPath}")
                        if (shortest != null) assertTrue(result.witnessPath.codePointCount(0, result.witnessPath.length) <= shortest.length)
                    }
                    is ScopeResult.Unknown -> fail("small generated language exhausted limits: $report")
                }
            }
        }
    }

    private fun strings(alphabet: String, maxLength: Int): List<String> {
        val result = mutableListOf("")
        var level = listOf("")
        repeat(maxLength) {
            level = level.flatMap { prefix -> alphabet.map { prefix + it } }
            result += level
        }
        return result
    }

    private fun canonical(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        !(path.length >= 2 && path[0] in ('A'..'Z') + ('a'..'z') && path[1] == ':')

    /** Independent string recursion: no automata, product states, Java regex, or production helpers. */
    private fun matches(pattern: String, path: String): Boolean {
        val raw = pattern.replace('\\', '/').removePrefix("./")
        if (raw.isEmpty() || raw == "/" || path.isEmpty()) return false
        if (raw == "**") return true
        if ('*' !in raw && '?' !in raw) {
            if ('/' !in raw) return path.substringAfterLast('/') == raw
            val prefix = raw.trimEnd('/')
            return path == prefix || path.startsWith(prefix + "/")
        }
        val glob = raw.trimEnd('/').codePoints().toArray()
        val text = path.codePoints().toArray()
        val memo = HashMap<Pair<Int, Int>, Boolean>()
        fun dot(point: Int): Boolean = point !in listOf(10, 13, 0x85, 0x2028, 0x2029)
        fun match(g: Int, p: Int): Boolean = memo.getOrPut(g to p) {
            when {
                g == glob.size -> p == text.size
                glob[g] == '*'.code && glob.getOrNull(g + 1) == '*'.code -> {
                    if (glob.getOrNull(g + 2) == '/'.code) {
                        match(g + 3, p) || (p until text.size).takeWhile { dot(text[it]) }
                            .any { text[it] == '/'.code && match(g + 3, it + 1) }
                    } else match(g + 2, p) || (p < text.size && dot(text[p]) && match(g, p + 1))
                }
                glob[g] == '*'.code -> match(g + 1, p) || (p < text.size && text[p] != '/'.code && match(g, p + 1))
                glob[g] == '?'.code -> p < text.size && text[p] != '/'.code && match(g + 1, p + 1)
                else -> p < text.size && glob[g] == text[p] && match(g + 1, p + 1)
            }
        }
        return match(0, 0)
    }
}
