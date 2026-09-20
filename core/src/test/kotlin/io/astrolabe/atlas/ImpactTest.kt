package io.astrolabe.atlas

import io.astrolabe.Defaults
import io.astrolabe.id.WorkspaceId
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImpactTest {
    private val scope = ImpactScope(WorkspaceId("main"), "pay")
    private fun file(path: String) = ImpactFile(scope, path)
    private fun graph(
        files: Set<ImpactFile>,
        edges: Set<ImpactImport> = emptySet(),
        complete: Boolean = true,
        coverage: Set<ImpactScope> = files.mapTo(linkedSetOf()) { it.scope },
        tier: IndexTier = IndexTier.LanguageService,
        unresolved: Set<ImpactDependency> = emptySet(),
    ) = ImpactGraph("v1", "fixture", files, edges, tier, complete, coverage, unresolved)

    private fun check(
        id: String,
        closure: Set<ImpactFile>?,
        containing: ImpactScope = scope,
        tests: Set<ImpactFile> = emptySet(),
        naming: Set<ImpactFile> = emptySet(),
        complete: Boolean = closure != null,
    ) = ImpactCheck(id, containing, tests, naming, closure, complete)

    private fun hunk(path: ImpactFile, at: Long, deleted: Long, added: Long, fanin: Long?) = ImpactHunk(
        path, ImpactLines(at, deleted), ImpactLines(at, added), "f",
        ImpactFanIn(fanin, IndexTier.LanguageService, fanin != null),
    )

    private fun risk(hunks: List<ImpactHunk>?, theta: Int = 40): ImpactAnalysis {
        val files = hunks?.mapTo(linkedSetOf()) { it.file } ?: setOf(file("a"))
        return Impact.analyze(ImpactRequest(graph(files), files, hunks, emptyList(), emptyList(), true),
            Defaults(theta = theta))
    }

    @Test
    fun `reverse blast includes edits transitive importers and cycles but not dependencies`() {
        val a = file("a.kt")
        val b = file("b.kt")
        val c = file("c.kt")
        val dependency = file("dependency.kt")
        val graph = ImpactGraph(
            "v1", "fixture", setOf(a, b, c, dependency),
            setOf(ImpactImport(b, a), ImpactImport(c, b), ImpactImport(b, c), ImpactImport(a, dependency)),
            IndexTier.LanguageService, true, setOf(scope), emptySet(),
        )
        val result = Impact.analyze(ImpactRequest(graph, setOf(a), emptyList(), emptyList(), emptyList(), true))
        assertEquals(setOf(a, b, c), result.blast)
        assertTrue(result.blastComplete)
        assertTrue(result.verificationScopes.isEmpty())
    }

    @Test
    fun `diamond joins tests naming and transitive acceptance while anchors intersect only edits FX54`() {
        val a = file("a")
        val b = file("b")
        val c = file("c")
        val test = file("test")
        val other = file("other")
        val graph = graph(setOf(a, b, c, test, other), setOf(
            ImpactImport(b, a), ImpactImport(c, a), ImpactImport(test, b), ImpactImport(test, c),
        ))
        val checks = listOf(check("file-test", emptySet(), tests = setOf(test)),
            check("named", emptySet(), naming = setOf(c)), check("acceptance", setOf(test)),
            check("unaffected", setOf(other)))
        val contracts = listOf(ImpactContract("CON-edit", setOf(a), true),
            ImpactContract("ADR-importer", setOf(b), true))
        val result = Impact.analyze(ImpactRequest(graph, setOf(a), emptyList(), checks, contracts, true))
        assertEquals(setOf(a, b, c, test), result.blast)
        assertEquals(setOf("file-test", "named", "acceptance"), result.affectedTests)
        assertEquals(setOf("CON-edit"), result.contractsTouched)
        assertTrue(result.complete)
    }

    @Test
    fun `identical names in different packages and workspaces never merge`() {
        val a = file("shared.kt")
        val b = ImpactFile(ImpactScope(scope.workspace, "shipping"), a.path)
        val c = ImpactFile(ImpactScope(WorkspaceId("child"), scope.packageId), a.path)
        val result = Impact.analyze(ImpactRequest(graph(setOf(a, b, c)), setOf(a), emptyList(),
            listOf(check("a", setOf(a)), check("b", setOf(b)), check("c", setOf(c))),
            listOf(ImpactContract("other", setOf(b, c), true)), true))
        assertEquals(setOf(a), result.blast)
        assertEquals(setOf("a"), result.affectedTests)
        assertTrue(result.contractsTouched.isEmpty())
    }

    @Test
    fun `runtime-only unresolved dependency widens even when its importer is disconnected FX37`() {
        val a = file("a")
        val plugin = ImpactFile(ImpactScope(scope.workspace, "plugin"), "plugin")
        val unresolved = setOf(ImpactDependency(plugin, "dynamic import of a"))
        val graph = graph(setOf(a, plugin), unresolved = unresolved)
        val result = Impact.analyze(ImpactRequest(graph, setOf(a), emptyList(),
            listOf(check("plugin-test", setOf(plugin), plugin.scope)), emptyList(), true))
        assertFalse(result.blastComplete)
        assertEquals(setOf(a), result.blast)
        assertEquals(setOf(scope, plugin.scope), result.verificationScopes)
        assertEquals(setOf("plugin-test"), result.affectedTests)
        assertEquals(unresolved, result.request.graph.unresolved)
        assertEquals(graph.coverage, result.request.graph.coverage)
        assertTrue(result.issues.any { "dynamic import" in it })
    }

    @Test
    fun `unknown and partial check closures are retained and require their containing suites`() {
        val a = file("a")
        val other = file("other")
        val unknown = ImpactScope(WorkspaceId("unknown"), null)
        val result = Impact.analyze(ImpactRequest(graph(setOf(a, other)), setOf(a), emptyList(),
            listOf(check("unknown", null), check("partial", setOf(other), complete = false),
                check("unknown-package", null, unknown)), emptyList(), true))
        assertTrue(result.blastComplete)
        assertFalse(result.complete)
        assertEquals(setOf("unknown", "partial", "unknown-package"), result.affectedTests)
        assertEquals(setOf(scope, unknown), result.verificationScopes)
    }

    @Test
    fun `lexical incomplete and uncovered graphs cannot prove empty impact`() {
        val a = file("a")
        for (graph in listOf(graph(setOf(a), tier = IndexTier.Lexical),
            graph(setOf(a), complete = false), graph(setOf(a), coverage = emptySet()),
            graph(emptySet(), coverage = setOf(scope)))) {
            val result = Impact.analyze(ImpactRequest(graph, setOf(a), null, emptyList(), emptyList(), false))
            assertFalse(result.blastComplete)
            assertEquals(setOf(a), result.blast)
            assertEquals(setOf(scope), result.verificationScopes)
            assertNull(result.risk.estimate)
        }
    }

    @Test
    fun `workspace fallback subsumes its packages but keeps other workspaces`() {
        val a = file("a")
        val unknown = ImpactFile(ImpactScope(scope.workspace, null), "unknown")
        val child = ImpactFile(ImpactScope(WorkspaceId("child"), "pay"), "a")
        val result = Impact.analyze(ImpactRequest(graph(setOf(a, unknown, child), complete = false),
            setOf(a), emptyList(), emptyList(), emptyList(), true))
        assertEquals(setOf(unknown.scope, child.scope), result.verificationScopes)
    }

    @Test
    fun `unknown file packages cannot establish complete graph closures or contract anchors`() {
        val a = file("a")
        val unknown = ImpactFile(ImpactScope(scope.workspace, null), a.path)
        val graphResult = Impact.analyze(ImpactRequest(graph(setOf(unknown)), setOf(unknown),
            emptyList(), emptyList(), emptyList(), true))
        assertFalse(graphResult.blastComplete)
        assertEquals(setOf(unknown.scope), graphResult.verificationScopes)
        val result = Impact.analyze(ImpactRequest(graph(setOf(a)), setOf(a), emptyList(),
            listOf(check("unknown", setOf(unknown)), check("unknown-name", emptySet(), naming = setOf(unknown)),
                check("unknown-test", emptySet(), tests = setOf(unknown))),
            listOf(ImpactContract("unknown-anchor", setOf(unknown), true)), true))
        assertEquals(setOf("unknown", "unknown-name", "unknown-test"), result.affectedTests)
        assertEquals(setOf(unknown.scope), result.verificationScopes)
        assertFalse(result.contractsComplete)
        assertFalse(result.complete)
    }

    @Test
    fun `incomplete contract inventory and unknown anchors never prove absence`() {
        val a = file("a")
        for (inventory in listOf(true, false)) {
            val result = Impact.analyze(ImpactRequest(graph(setOf(a)), setOf(a), emptyList(), emptyList(),
                listOf(ImpactContract("unknown", null, false), ImpactContract("partial", setOf(a), false)),
                inventory))
            assertEquals(setOf("partial"), result.contractsTouched)
            assertFalse(result.contractsComplete)
            assertFalse(result.complete)
        }
        val result = Impact.analyze(ImpactRequest(graph(setOf(a)), setOf(a), emptyList(), emptyList(),
            emptyList(), false))
        assertFalse(result.contractsComplete)
    }

    @Test
    fun `risk example equals 40 and only 41 exceeds the configured threshold`() {
        val a = file("a")
        val hunks = listOf(hunk(a, 0, 0, 10, 3), hunk(a, 20, 5, 0, 1))
        val at = risk(hunks).risk
        assertEquals(BigInteger.valueOf(15), at.changedLines)
        assertEquals(40.0, at.estimate)
        assertEquals(false, at.exceedsThreshold)
        assertFalse(at.requiresSlowChecks)
        val over = risk(hunks + hunk(a, 30, 1, 0, 0)).risk
        assertEquals(41.0, over.estimate)
        assertEquals(true, over.exceedsThreshold)
        assertEquals(false, risk(hunks, theta = 40).risk.exceedsThreshold)
        assertEquals(true, risk(hunks, theta = 39).risk.exceedsThreshold)
        assertFailsWith<IllegalArgumentException> { risk(hunks, theta = -1) }
    }

    @Test
    fun `replacement counts both sides duplicate hunks once and counts cannot overflow Long`() {
        val a = file("a")
        val replacement = hunk(a, 0, 5, 5, 0)
        assertEquals(BigInteger.TEN, risk(listOf(replacement, replacement)).risk.changedLines)
        val huge = risk(listOf(hunk(a, 0, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))).risk
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, huge.changedLines)
        assertTrue(huge.estimate!!.isFinite())
        assertEquals(true, huge.exceedsThreshold)
    }

    @Test
    fun `non-identical overlapping hunks are invalid in either coordinate space`() {
        val a = file("a")
        val first = hunk(a, 0, 5, 5, 1)
        val oldOverlap = first.copy(oldLines = ImpactLines(4, 1), newLines = ImpactLines(10, 1))
        val newOverlap = first.copy(oldLines = ImpactLines(10, 1), newLines = ImpactLines(4, 1))
        for (second in listOf(oldOverlap, newOverlap, first.copy(symbol = "different"))) {
            assertFailsWith<IllegalArgumentException> { risk(listOf(first, second)) }
            assertFailsWith<IllegalArgumentException> { risk(listOf(second, first)) }
        }
        assertEquals(BigInteger.valueOf(12), risk(listOf(first, hunk(a, 5, 1, 1, 0))).risk.changedLines)
    }

    @Test
    fun `unknown fanin differs from proven zero and from an incomplete numeric estimate`() {
        val h = hunk(file("a"), 0, 1, 0, null)
        assertNull(risk(listOf(h)).risk.estimate)
        assertNull(risk(listOf(h)).risk.exceedsThreshold)
        assertTrue(risk(listOf(h)).risk.requiresSlowChecks)
        val estimate = risk(listOf(h.copy(fanIn = ImpactFanIn(0, IndexTier.Lexical, false)))).risk
        assertEquals(1.0, estimate.estimate)
        assertFalse(estimate.complete)
        assertNull(estimate.exceedsThreshold)
        val known = risk(listOf(hunk(file("a"), 0, 1, 0, 0))).risk
        assertTrue(known.complete)
        assertEquals(false, known.exceedsThreshold)
        assertFalse(risk(listOf(hunk(file("a"), 0, 1, 0, 0).copy(symbol = null))).risk.complete)
    }

    @Test
    fun `zero-line changes have zero risk without inventing fanin`() {
        val risk = risk(listOf(hunk(file("a"), 1, 0, 0, null))).risk
        assertEquals(BigInteger.ZERO, risk.changedLines)
        assertEquals(0.0, risk.estimate)
        assertTrue(risk.complete)
    }

    @Test
    fun `invalid identities references counts and attestations are rejected`() {
        for (path in listOf("", "/a", "../a", "a/../b", "a//b", "a\\b", "C:/a", "a\n")) {
            assertFailsWith<IllegalArgumentException> { file(path) }
        }
        assertFailsWith<IllegalArgumentException> { ImpactScope(scope.workspace, " ") }
        assertFailsWith<IllegalArgumentException> { ImpactLines(-1, 0) }
        assertFailsWith<IllegalArgumentException> { ImpactLines(0, -1) }
        assertFailsWith<IllegalArgumentException> { ImpactLines(Long.MAX_VALUE, 1) }
        assertFailsWith<IllegalArgumentException> { ImpactFanIn(-1, IndexTier.LanguageService, false) }
        assertFailsWith<IllegalArgumentException> { ImpactFanIn(null, IndexTier.LanguageService, true) }
        assertFailsWith<IllegalArgumentException> { ImpactFanIn(0, IndexTier.Lexical, true) }
        assertFailsWith<IllegalArgumentException> { graph(setOf(file("a")), setOf(ImpactImport(file("a"), file("b")))) }
        assertFailsWith<IllegalArgumentException> { graph(setOf(file("a")), unresolved = setOf(ImpactDependency(file("b"), "x"))) }
        assertFailsWith<IllegalArgumentException> { check("a", null, complete = true) }
        assertFailsWith<IllegalArgumentException> { ImpactContract("a", null, true) }
        assertFailsWith<IllegalArgumentException> { ImpactRequest(graph(setOf(file("a"))), emptySet(),
            listOf(hunk(file("a"), 0, 1, 1, 0)), emptyList(), emptyList(), true) }
        assertFailsWith<IllegalArgumentException> { ImpactRequest(graph(setOf(file("a"))), emptySet(),
            emptyList(), listOf(check("a", emptySet()), check("a", emptySet())), emptyList(), true) }
    }

    @Test
    fun `snapshots copy every nested collection and results reject mutation`() {
        val a = file("a")
        val files = linkedSetOf(a)
        val edges = linkedSetOf(ImpactImport(a, a))
        val coverage = linkedSetOf(scope)
        val unresolved = linkedSetOf(ImpactDependency(a, "reflection"))
        val graph = graph(files, edges, coverage = coverage, unresolved = unresolved)
        val check = check("test", files, tests = files, naming = files)
        val contract = ImpactContract("con", files, true)
        val hunks = arrayListOf(hunk(a, 0, 1, 0, 0))
        val checks = arrayListOf(check)
        val contracts = arrayListOf(contract)
        val request = ImpactRequest(graph, files, hunks, checks, contracts, true)
        files.clear(); edges.clear(); coverage.clear(); unresolved.clear()
        hunks.clear(); checks.clear(); contracts.clear()
        val result = Impact.analyze(request)
        assertEquals(setOf(a), result.blast)
        assertEquals(setOf("test"), result.affectedTests)
        assertEquals(setOf("con"), result.contractsTouched)
        assertEquals(BigInteger.ONE, result.risk.changedLines)
        assertEquals(1, graph.imports.size)
        assertEquals(1, graph.unresolved.size)
        assertEquals(1, graph.coverage.size)
        for (collection in listOf(graph.files, graph.imports, graph.coverage, graph.unresolved,
            request.edits, request.hunks!!, request.checks, request.contracts, check.closure!!,
            check.testFiles, check.namingTargets, contract.anchors!!, result.blast, result.affectedTests,
            result.contractsTouched, result.verificationScopes, result.issues)) {
            assertFailsWith<UnsupportedOperationException> { (collection as MutableCollection<*>).clear() }
        }
    }

    @Test
    fun `long import cycle terminates without recursion`() {
        val files = (0 until 20_000).map { file("f$it") }
        val edges = files.indices.mapTo(linkedSetOf()) { ImpactImport(files[it], files[(it + 1) % files.size]) }
        val result = Impact.analyze(ImpactRequest(graph(files.toSet(), edges), setOf(files.first()),
            emptyList(), emptyList(), emptyList(), true))
        assertEquals(files.toSet(), result.blast)
        assertTrue(result.blastComplete)
    }
}
