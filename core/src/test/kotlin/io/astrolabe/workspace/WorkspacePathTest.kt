package io.astrolabe.workspace

import io.astrolabe.fixtures.FixtureSupport
import io.astrolabe.fixtures.TempRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * P1.2.6 / IX-09: the one workspace-path contract. Traversal, absolute escapes, junction and
 * symlink targets, Windows case aliases and ancestor substitution must all leave protected and
 * outside bytes unchanged, and the guarantees publication does *not* provide must be reported.
 */
class WorkspacePathTest {

    private val repo: TempRepo = TempRepo.create().also {
        it.write("src/a.py", "def a():\n    return 1\n")
        it.write("migrations/0001_init.sql", "create table t(x int);\n")
        it.write("package-lock.json", "{}\n")
        it.commit("initial")
    }

    private val paths: WorkspacePath = WorkspacePath.of(repo.root)

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    // ----------------------------------------------------------- lexical form

    @Test
    fun `traversal and absolute escapes are refused by name`() {
        assertReason(RejectionReason.Traversal, paths.resolve("../x", Intent.Read))
        assertReason(RejectionReason.Traversal, paths.resolve("src/../../x", Intent.Read))
        assertReason(RejectionReason.Traversal, paths.resolve("src/../a.py", Intent.Mutate))
        assertReason(RejectionReason.Absolute, paths.resolve("C:\\Windows\\x", Intent.Read))
        assertReason(RejectionReason.Absolute, paths.resolve("/etc/passwd", Intent.Read))
        assertReason(RejectionReason.Absolute, paths.resolve("\\\\server\\share\\x", Intent.Read))
        assertReason(RejectionReason.Empty, paths.resolve("", Intent.Read))
        assertReason(RejectionReason.Empty, paths.resolve(".", Intent.Read))
        assertReason(RejectionReason.IllegalCharacter, paths.resolve("src/a\u0000.py", Intent.Read))
    }

    @Test
    fun `an existing file resolves to a canonical relative path and a real path`() {
        val resolved = assertResolved(paths.resolve("src/a.py", Intent.Mutate))

        assertEquals("src/a.py", resolved.relative)
        assertEquals(repo.root.resolve("src").resolve("a.py").toRealPath(), resolved.real)
        assertEquals(PathKind.Regular, resolved.kind)
        assertTrue(resolved.linkAncestors.isEmpty())
    }

    @Test
    fun `a file that does not exist yet resolves under an existing parent`() {
        val resolved = assertResolved(paths.resolve("src/new/deep.py", Intent.Mutate))

        assertEquals("src/new/deep.py", resolved.relative)
        assertEquals(PathKind.Missing, resolved.kind)
    }

    // -------------------------------------------------------------- protected

    @Test
    fun `protected paths are refused on the real relative path`() {
        assertReason(RejectionReason.Protected, paths.resolve(".git/config", Intent.Read))
        assertReason(RejectionReason.Protected, paths.resolve(".git/config", Intent.Mutate))
        assertReason(RejectionReason.Protected, paths.resolve("migrations/0001_init.sql", Intent.Mutate))
        assertReason(RejectionReason.Protected, paths.resolve("package-lock.json", Intent.Mutate))
        assertReason(RejectionReason.Protected, paths.resolve("sub/dir/package-lock.json", Intent.Mutate))

        // D-31 protects the *write* scope: reading CI configuration or a lock file is legitimate.
        assertResolved(paths.resolve("migrations/0001_init.sql", Intent.Read))
        assertResolved(paths.resolve("package-lock.json", Intent.Read))
    }

    @Test
    fun `a symlink into the protected git directory cannot launder a mutation`() {
        // Creating a symlink needs a privilege Windows may withhold; the junction cases cover the
        // same rule there, so this skips visibly rather than passing without exercising anything.
        requireSupported(repo.symlinkAncestor("src/cache", ".git"))
        val before = Files.readAllBytes(repo.root.resolve(".git").resolve("HEAD"))

        val read = paths.resolve("src/cache/HEAD", Intent.Read)
        val mutate = paths.resolve("src/cache/HEAD", Intent.Mutate)

        assertReason(RejectionReason.Protected, read)
        assertReason(RejectionReason.Protected, mutate)
        assertContentEquals(before, Files.readAllBytes(repo.root.resolve(".git").resolve("HEAD")))
    }

    // ------------------------------------------------------------- link kinds

    @Test
    fun `a junction ancestor pointing outside the workspace refuses mutation and reports the kind`(
        @TempDir outside: Path,
    ) {
        val target = outside.resolve("secrets")
        val link = requireSupported(createJunction(repo.root.resolve("src").resolve("linked"), target))
        Files.write(target.resolve("keys.txt"), "top-secret\n".toByteArray())
        val before = Files.readAllBytes(target.resolve("keys.txt"))

        val read = paths.resolve("src/linked/keys.txt", Intent.Read)
        val mutate = paths.resolve("src/linked/keys.txt", Intent.Mutate)

        assertReason(RejectionReason.OutsideRoot, read)
        assertReason(RejectionReason.OutsideRoot, mutate)
        assertContentEquals(before, Files.readAllBytes(target.resolve("keys.txt")))
        assertEquals(PathKind.Junction, WorkspacePath.kindOf(link))
    }

    @Test
    fun `a junction ancestor inside the workspace still refuses mutation and reads report it`() {
        val inside = repo.root.resolve("vendor")
        val link = requireSupported(createJunction(repo.root.resolve("src").resolve("vendor"), inside))
        Files.write(inside.resolve("v.py"), "v = 1\n".toByteArray())

        val read = assertResolved(paths.resolve("src/vendor/v.py", Intent.Read))
        assertEquals("vendor/v.py", read.relative, "the real path is the identity, not the spelling")
        assertEquals(
            listOf(LinkAncestor("src/vendor", PathKind.Junction)),
            read.linkAncestors,
            "a read reports the link kind (D-47)",
        )
        assertEquals(PathKind.Junction, WorkspacePath.kindOf(link))

        // Resolving the ancestor's own spelling for mutation is what gets refused.
        assertReason(RejectionReason.LinkAncestor, paths.resolve("src/vendor/v.py", Intent.Mutate))
    }

    @Test
    fun `a symlink target is never mutated in place`() {
        requireSupported(repo.symlinkAncestor("linkdir", "realdir"))
        assertReason(RejectionReason.LinkTarget, paths.resolve("linkdir", Intent.Mutate))
        // The same link reads fine and says what it is.
        assertEquals(PathKind.Symlink, assertResolved(paths.resolve("linkdir", Intent.Read)).kind)
        assertReason(RejectionReason.NotAFile, paths.resolve("realdir", Intent.Mutate))
    }

    // --------------------------------------------------------- case identity

    @Test
    fun `case aliases resolve to one identity on a case-insensitive filesystem`() {
        val alias = repo.caseAlias("src/a.py")
        if (alias is FixtureSupport.Unsupported) {
            assertFalse(paths.caseInsensitive, "a case-sensitive filesystem must not claim case folding")
            return
        }
        assertTrue(paths.caseInsensitive)

        val lower = assertResolved(paths.resolve("src/a.py", Intent.Mutate))
        val upper = assertResolved(paths.resolve("SRC/A.PY", Intent.Mutate))

        assertEquals(lower.relative, upper.relative, "one path identity, whatever the spelling")
        assertEquals(lower.real, upper.real)
    }

    @Test
    fun `a case-spelled protected path is still protected where the filesystem folds case`() {
        if (!paths.caseInsensitive) return
        assertReason(RejectionReason.Protected, paths.resolve(".GIT/config", Intent.Mutate))
        assertReason(RejectionReason.Protected, paths.resolve("MIGRATIONS/0001_init.sql", Intent.Mutate))
    }

    // ------------------------------------------------------------ publication

    @Test
    fun `revalidate accepts the file the resolution named`() {
        val resolved = assertResolved(paths.resolve("src/fresh.py", Intent.Mutate))
        Files.write(resolved.real, "x = 1\n".toByteArray())

        val again = assertResolved(paths.revalidate(resolved))

        assertEquals(resolved.real, again.real)
        assertEquals(PathKind.Regular, again.kind, "Missing → Regular is the write that just happened")
    }

    @Test
    fun `revalidate refuses a path substituted by a link after resolution`() {
        val resolved = assertResolved(paths.resolve("src/fresh.py", Intent.Mutate))
        Files.write(resolved.real, "x = 1\n".toByteArray())
        Files.delete(resolved.real)
        requireSupported(repo.symlinkAncestor("src/fresh.py", "outside-target"))

        assertReason(RejectionReason.LinkTarget, paths.revalidate(resolved))
    }

    @Test
    fun `the publication guarantees this contract does not provide are reported`() {
        val limits = WorkspacePath.publicationLimits()

        assertTrue(limits.atomicRename, "an atomic rename is what Os.replaceFileAtomically gives")
        assertFalse(limits.compareAndReplace, "IX-09: a rename is never a compare-and-replace")
        assertTrue(limits.revalidationIsBestEffort)
        assertTrue(limits.detail.contains("host-coordinated"), "the stronger mechanism is named: ${limits.detail}")
    }

    // ---------------------------------------------------------------- helpers

    private fun assertResolved(resolution: PathResolution): PathResolution.Resolved {
        assertIs<PathResolution.Resolved>(resolution, "expected a resolved path, got $resolution")
        return resolution
    }

    private fun assertReason(expected: RejectionReason, resolution: PathResolution) {
        assertIs<PathResolution.Rejected>(resolution, "expected $expected, got $resolution")
        assertEquals(expected, resolution.reason, resolution.detail)
    }
}
