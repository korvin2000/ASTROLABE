package io.astrolabe.atlas

import io.astrolabe.id.WorkspaceId
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImpactOracleTest {
    @Test
    fun `500 directed graphs and joins agree with Floyd Warshall in two permutations`() {
        val random = Random(327)
        repeat(500) { trial ->
            val files = (0 until random.nextInt(1, 10)).map {
                ImpactFile(ImpactScope(WorkspaceId("w${it % 2}"), "p${it % 3}"), "f${it / 6}")
            }
            val imports = buildSet {
                for (from in files.indices) for (to in files.indices) {
                    if (random.nextInt(4) == 0) add(ImpactImport(files[from], files[to]))
                }
            }
            val edits = files.filter { random.nextBoolean() }.toSet()
            val reach = Array(files.size) { from -> BooleanArray(files.size) { to ->
                from == to || ImpactImport(files[from], files[to]) in imports
            } }
            // Independent dense transitive closure, following forward dependencies from each candidate.
            for (via in files.indices) for (from in files.indices) for (to in files.indices) {
                reach[from][to] = reach[from][to] || (reach[from][via] && reach[via][to])
            }
            val expectedBlast = files.filterIndexed { from, _ ->
                files.indices.any { to -> files[to] in edits && reach[from][to] }
            }.toSet()
            fun subset() = files.filter { random.nextBoolean() }.toSet()
            val checks = (0..5).map {
                ImpactCheck("check$it", files[it % files.size].scope, subset(), subset(), subset(), true)
            }
            val contracts = (0..3).map { ImpactContract("contract$it", subset(), true) }
            val expectedChecks = checks.filter {
                (it.testFiles + it.namingTargets + it.closure.orEmpty()).intersect(expectedBlast).isNotEmpty()
            }.mapTo(linkedSetOf()) { it.id }
            val expectedContracts = contracts.filter {
                it.anchors.orEmpty().intersect(edits).isNotEmpty()
            }.mapTo(linkedSetOf()) { it.id }
            val coverage = files.mapTo(linkedSetOf()) { it.scope }
            val summaries = (0..1).map { order ->
                val graph = ImpactGraph("v1", "seed327-$trial", files.shuffled(random).toSet(),
                    imports.shuffled(random).toSet(), IndexTier.LanguageService, true,
                    coverage.shuffled(random).toSet(), emptySet())
                val result = Impact.analyze(ImpactRequest(graph, edits.shuffled(random).toSet(), emptyList(),
                    checks.shuffled(random), contracts.shuffled(random), true))
                assertEquals(expectedBlast, result.blast, "blast trial=$trial order=$order")
                assertEquals(expectedChecks, result.affectedTests, "checks trial=$trial")
                assertEquals(expectedContracts, result.contractsTouched, "contracts trial=$trial")
                assertTrue(result.blastComplete)
                assertTrue(result.verificationScopes.isEmpty())
                listOf(result.blast.toList(), result.affectedTests.toList(), result.contractsTouched.toList(),
                    result.issues.toList(), result.risk)
            }
            assertEquals(summaries[0], summaries[1], "stable ordering trial=$trial")
        }
    }

    @Test
    fun `300 hunk sets agree with integer line enumeration and power-of-two fanin factors`() {
        val random = Random(3271)
        val file = ImpactFile(ImpactScope(WorkspaceId("w"), "p"), "a")
        val graph = ImpactGraph("v1", "seed3271", setOf(file), emptySet(),
            IndexTier.LanguageService, true, setOf(file.scope), emptySet())
        repeat(300) {
            val oldWeights = HashMap<Int, Int>()
            val newWeights = HashMap<Int, Int>()
            val hunks = (0 until random.nextInt(0, 12)).map { index ->
                val deleted = random.nextInt(0, 8)
                val added = random.nextInt(0, 8)
                val exponent = random.nextInt(0, 7)
                val at = index * 10
                for (line in at until at + deleted) oldWeights[line] = 1 + exponent
                for (line in at until at + added) newWeights[line] = 1 + exponent
                ImpactHunk(file, ImpactLines(at.toLong(), deleted.toLong()), ImpactLines(at.toLong(), added.toLong()),
                    "symbol$index", ImpactFanIn((1L shl exponent) - 1, IndexTier.LanguageService, true))
            }
            val expectedLines = oldWeights.size + newWeights.size
            val expectedRisk = oldWeights.values.sum() + newWeights.values.sum()
            val results = (0..1).map {
                Impact.analyze(ImpactRequest(graph, setOf(file), (hunks + hunks).shuffled(random),
                    emptyList(), emptyList(), true)).risk
            }
            assertEquals(results[0], results[1])
            assertEquals(BigInteger.valueOf(expectedLines.toLong()), results[0].changedLines)
            assertEquals(expectedRisk.toDouble(), results[0].estimate)
            assertEquals(expectedRisk > 40, results[0].exceedsThreshold)
        }
    }

    @Test
    fun `incomplete graph permutations preserve conservative scopes and diagnostics`() {
        val scopes = listOf(ImpactScope(WorkspaceId("w"), "p"), ImpactScope(WorkspaceId("w"), null),
            ImpactScope(WorkspaceId("other"), "p"))
        val files = scopes.map { ImpactFile(it, "f") }
        val unknown = setOf(ImpactDependency(files[2], "runtime edge"), ImpactDependency(files[0], "reflection"))
        val checks = files.mapIndexed { i, file -> ImpactCheck("c$i", file.scope, emptySet(), emptySet(), null, false) }
        val results = listOf(false, true).map { reverse ->
            val graph = ImpactGraph("v1", "fixture", files.let { if (reverse) it.reversed() else it }.toSet(),
                emptySet(), IndexTier.Lexical, false, scopes.toSet(),
                unknown.let { if (reverse) it.reversed().toSet() else it })
            Impact.analyze(ImpactRequest(graph, setOf(files[0]), null,
                checks.let { if (reverse) it.reversed() else it }, emptyList(), false))
        }
        assertFalse(results[0].complete)
        assertEquals(setOf(scopes[1], scopes[2]), results[0].verificationScopes)
        assertEquals(results[0].verificationScopes.toList(), results[1].verificationScopes.toList())
        assertEquals(results[0].issues.toList(), results[1].issues.toList())
        assertEquals(results[0].affectedTests.toList(), results[1].affectedTests.toList())
    }
}
