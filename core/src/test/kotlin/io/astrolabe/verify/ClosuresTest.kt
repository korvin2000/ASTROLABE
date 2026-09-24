package io.astrolabe.verify

import io.astrolabe.atlas.Impact
import io.astrolabe.atlas.ImpactCheck
import io.astrolabe.atlas.ImpactFile
import io.astrolabe.atlas.ImpactGraph
import io.astrolabe.atlas.ImpactImport
import io.astrolabe.atlas.ImpactRequest
import io.astrolabe.atlas.ImpactScope
import io.astrolabe.atlas.IndexTier
import io.astrolabe.evidence.Closure
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** P3.1.1: closures for blast-selected checks from the impact engine, and closure manifests with membership. */
class ClosuresTest {
    private val scope = ImpactScope(WorkspaceId("w"), "app")
    private fun f(path: String) = ImpactFile(scope, path)

    private val a = f("src/a.py")
    private val b = f("src/b.py")
    private val c = f("src/c.py")
    private val testA = f("tests/test_a.py")
    private val testC = f("tests/test_c.py")

    // b imports a; test_a imports b; test_c imports c.
    private fun graph(complete: Boolean = true) = ImpactGraph(
        "g1", "fixture", setOf(a, b, c, testA, testC),
        setOf(ImpactImport(b, a), ImpactImport(testA, b), ImpactImport(testC, c)),
        IndexTier.Syntax, complete, setOf(scope), emptySet(),
    )

    private fun checks(closureComplete: Boolean = true) = listOf(
        ImpactCheck("T-a", scope, setOf(testA), emptySet(), setOf(testA, b, a), closureComplete),
        ImpactCheck("T-c", scope, setOf(testC), emptySet(), setOf(testC, c), true),
    )

    private fun analyze(graphComplete: Boolean = true, closureComplete: Boolean = true) =
        Impact.analyze(ImpactRequest(graph(graphComplete), setOf(a), null, checks(closureComplete), emptyList(), contractsComplete = true))

    @Test
    fun `a complete blast gives the selected tests' exact closure, never the unaffected ones`() {
        val analysis = analyze()
        assertEquals(setOf("T-a"), analysis.affectedTests)
        assertEquals(Closure.Known(setOf("src/a.py", "src/b.py", "tests/test_a.py")), Closures.blast(analysis))
    }

    @Test
    fun `an incomplete blast or check closure falls back to the containing package or unknown`() {
        val incomplete = analyze(graphComplete = false)
        assertEquals(Closure.Unknown, Closures.blast(incomplete), "no package path is guessed")
        assertEquals(Closure.Package("app"), Closures.blast(incomplete) { s -> s.packageId?.let { "app" } })
        assertEquals(Closure.Unknown, Closures.blast(analyze(closureComplete = false)))
    }

    private val tree = listOf("app/tests/test_x.py", "app/src/x.py", "other/y.py", "poetry.lock")
    private fun versions(files: Map<String, String>): (String) -> FileVersion? = { p -> files[p]?.let { FileVersion(Digest.ofUtf8(it)) } }
    private val bytes = mapOf("app/tests/test_x.py" to "t", "app/src/x.py" to "x", "other/y.py" to "y", "poetry.lock" to "l")

    @Test
    fun `a package closure pins its membership, so an added test file changes the manifest`() {
        val before = ClosureManifest.of(Closure.Package("app"), tree, versions(bytes))
        assertEquals(ClosureCompleteness.Complete, before.completeness)
        assertEquals(mapOf("app" to listOf("app/src/x.py", "app/tests/test_x.py")), before.directoryMembership)
        assertEquals(setOf("poetry.lock"), before.configAndLockfiles.keys, "lockfiles are pinned whatever the closure")

        val added = ClosureManifest.of(Closure.Package("app"), tree + "app/tests/test_new.py", versions(bytes + ("app/tests/test_new.py" to "n")))
        assertNotEquals(before.digest, added.digest, "a new member invalidates the closure")
        val edited = ClosureManifest.of(Closure.Package("app"), tree, versions(bytes + ("app/src/x.py" to "x2")))
        assertNotEquals(before.digest, edited.digest)
        val outside = ClosureManifest.of(Closure.Package("app"), tree, versions(bytes + ("other/y.py" to "y2")))
        assertEquals(before.digest, outside.digest, "a change outside the package leaves it")
    }

    @Test
    fun `known closures pin paths, missing paths are partial, excluded paths are named, unknown pins nothing`() {
        val known = ClosureManifest.of(Closure.Known(setOf("app/src/x.py", "gone.py")), tree, versions(bytes))
        assertEquals(ClosureCompleteness.Partial, known.completeness)
        assertEquals(listOf("gone.py: absent or unreadable"), known.exclusions)
        assertEquals(emptyMap(), known.directoryMembership)

        val scratch = ClosureManifest.of(Closure.Package("app"), tree, versions(bytes), excluded = { p -> "scratch".takeIf { p.startsWith("app/tests/") } })
        assertEquals(ClosureCompleteness.Complete, scratch.completeness)
        assertEquals(listOf("app/tests/test_x.py: scratch"), scratch.exclusions)

        val unknown = ClosureManifest.of(Closure.Unknown, tree, versions(bytes), fixtures = mapOf("db" to "fixture-v3"))
        assertEquals(ClosureCompleteness.Unknown, unknown.completeness)
        assertEquals(emptyMap(), unknown.pathsAtVersions)
        assertEquals(mapOf("db" to "fixture-v3"), unknown.fixtures)
    }
}
