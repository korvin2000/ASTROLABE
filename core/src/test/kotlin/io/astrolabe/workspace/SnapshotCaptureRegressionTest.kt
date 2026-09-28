package io.astrolabe.workspace

import io.astrolabe.id.Digest
import io.astrolabe.os.FileMode
import io.astrolabe.os.GitError
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.BlobStore
import io.astrolabe.store.CrashPoint
import io.astrolabe.store.FaultPoints
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotCaptureRegressionTest {

    @Test
    fun `tracked file replaced by a symlink captures link identity`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val link = fixture.repo.resolve("src/a.py")
            Files.delete(link)
            createSymlink(link, Path.of("b.py"))

            assertLinkCaptured(fixture, "src/a.py")
        }
    }

    @Test
    fun `untracked file symlink captures target spelling instead of target bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            createSymlink(fixture.repo.resolve("alias.py"), Path.of("src/a.py"))

            assertLinkCaptured(fixture, "alias.py")
        }
    }

    @Test
    fun `directory symlink captures the link instead of a directory or deletion`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            createSymlink(fixture.repo.resolve("source-link"), Path.of("src"))

            assertLinkCaptured(fixture, "source-link")
        }
    }

    @Test
    fun `dangling symlink captures its target even though the target does not exist`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            createSymlink(fixture.repo.resolve("dangling"), Path.of("missing-target"))

            assertLinkCaptured(fixture, "dangling")
        }
    }

    @Test
    fun `external final symlink captures only its link object`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val external = Files.writeString(state.resolve("outside.txt"), "outside workspace content")
            createSymlink(fixture.repo.resolve("external-link"), external)

            assertLinkCaptured(fixture, "external-link")
        }
    }

    @Test
    fun `a protected dirty tracked path cannot receive an authoritative stamp`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "private dirty bytes\n")
            val workspace = protectedWorkspace(fixture)

            assertFailsWith<IllegalStateException> { Stamper(workspace, TEST_ENV).stamp() }
        }
    }

    @Test
    fun `a protected dirty tracked path cannot become a successful recovery snapshot`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "private dirty bytes\n")
            val workspace = protectedWorkspace(fixture)
            val dirtyState = DirtyState(workspace, fixture.store.blobs, Stamper(workspace, TEST_ENV), fixture.ids, fixture.clock)

            assertFailsWith<IllegalStateException> { dirtyState.capture() }
        }
    }

    @Test
    fun `a missing staged blob cannot silently disappear from the snapshot`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "unique staged recovery content\n")
            fixture.repo.stage("src/a.py")
            val objectId = fixture.rawGit("rev-parse", ":src/a.py").trim()
            val objectPath = fixture.repo.root.resolve(".git/objects/${objectId.take(2)}/${objectId.drop(2)}")
            assertTrue(Files.isRegularFile(objectPath), "fixture staged blob must be a loose object")
            if (isWindows()) Files.setAttribute(objectPath, "dos:readonly", false)
            Files.delete(objectPath)

            assertFailsWith<GitError> { fixture.dirtyState.capture() }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `dirty tracked files behind a junction ancestor cannot be certified`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "dirty file behind junction\n")
            val source = fixture.repo.resolve("src")
            val moved = Files.move(source, state.resolve("moved-source"))
            requireSupported(createJunction(source, moved))
            try {
                assertFailsWith<IllegalStateException> { fixture.stamper.stamp() }
                assertFailsWith<IllegalStateException> { fixture.dirtyState.capture() }
            } finally {
                Files.delete(source)
            }
        }
    }

    @Test
    fun `a file changing during blob publication cannot mix manifest bytes with another stamp`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "first dirty version\n")
            var changed = false
            val blobs = BlobStore(
                fixture.store.layout,
                fixture.store.db,
                fixture.clock,
                FaultPoints(CrashPoint { point ->
                    if (point == BlobPoint.AFTER_ROW && !changed) {
                        changed = true
                        fixture.repo.modify("src/a.py", "second dirty version\n")
                    }
                }),
            )
            val dirtyState = DirtyState(fixture.workspace, blobs, fixture.stamper, fixture.ids, fixture.clock)

            val result = runCatching { dirtyState.capture() }

            assertTrue(changed, "the writer must run during publication to exercise mixed acquisition")
            val failure = result.exceptionOrNull()
            if (failure != null) {
                assertIs<IllegalStateException>(failure, "moving inputs must fail explicitly")
                return
            }
            val snapshot = result.getOrThrow()
            val entry = assertNotNull(snapshot.entry("src/a.py"))
            fixture.repo.write("src/a.py", assertNotNull(dirtyState.bytesOf(entry)))
            assertEquals(
                fixture.stamper.stamp().id,
                snapshot.stampId,
                "restoring the exact captured bytes must reproduce the snapshot's recorded identity",
            )
        }
    }

    @Test
    fun `an index change during blob publication never yields an incomplete staged snapshot`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "dirty a\n")
            fixture.repo.modify("src/b.py", "dirty b to stage during capture\n")
            var changed = false
            val blobs = BlobStore(
                fixture.store.layout,
                fixture.store.db,
                fixture.clock,
                FaultPoints(CrashPoint { point ->
                    if (point == BlobPoint.AFTER_ROW && !changed) {
                        changed = true
                        fixture.repo.stage("src/b.py")
                    }
                }),
            )
            val dirtyState = DirtyState(fixture.workspace, blobs, fixture.stamper, fixture.ids, fixture.clock)

            // D-274: the attempt that saw the change is discarded; the retry captures the settled index.
            val snapshot = dirtyState.capture()
            assertTrue(changed, "the index writer must run during acquisition")
            assertTrue(snapshot.staged.any { it.path == "src/b.py" }, "the retried snapshot carries the staged change")
        }
    }

    @Test
    fun `a base commit change during blob publication never yields a mixed snapshot`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "dirty a before base changes\n")
            var changed = false
            val blobs = BlobStore(
                fixture.store.layout,
                fixture.store.db,
                fixture.clock,
                FaultPoints(CrashPoint { point ->
                    if (point == BlobPoint.AFTER_ROW && !changed) {
                        changed = true
                        fixture.rawGit("commit", "--allow-empty", "-m", "advance base during capture")
                    }
                }),
            )
            val dirtyState = DirtyState(fixture.workspace, blobs, fixture.stamper, fixture.ids, fixture.clock)

            // D-274: the attempt that saw the change is discarded; the retry captures the new base.
            val snapshot = dirtyState.capture()
            assertTrue(changed, "the base writer must run during acquisition")
            assertEquals(fixture.repo.git.revParse("HEAD").hex, snapshot.baseCommit)
            assertEquals(fixture.stamper.stamp().id, snapshot.stampId)
        }
    }

    @Test
    fun `an untracked nested repository is a directory member and does not fail capture`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.rawGit("init", "-q", "nested")
            fixture.repo.write("nested/inner.txt", "inner\n")

            val report = fixture.stamper.report()
            val nested = assertNotNull(report.members.values.singleOrNull { it.path.trimEnd('/') == "nested" })
            assertEquals(EntryType.Directory, nested.type)
            assertEquals(report.stamp.id, fixture.stamper.stamp().id)
            val snapshot = fixture.dirtyState.capture()
            assertEquals(report.stamp.id, snapshot.stampId)
            assertTrue(snapshot.entries.none { it.path.trimEnd('/') == "nested" }, "a directory has no recovery bytes")
        }
    }

    @Test
    fun `a tracked file replaced by a directory does not fail the report`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            Files.delete(fixture.repo.resolve("README.md"))
            fixture.repo.write("README.md/c", "child\n")

            val report = fixture.stamper.report()
            assertEquals(EntryType.Directory, report.members["README.md"]?.type)
            assertTrue("README.md/c" in report.members)
            val snapshot = fixture.dirtyState.capture()
            assertTrue(snapshot.entry("README.md/c")?.present == true)
        }
    }

    @Test
    fun `a symlink target uses Git's forward slashes only where backslash is the separator`() {
        assertEquals("sub/target", gitLinkTarget("sub\\target", separator = '\\'))
        assertEquals("sub\\target", gitLinkTarget("sub\\target", separator = '/'))
        assertEquals("sub/target", gitLinkTarget("sub/target", separator = '/'))
        val expected = if (java.io.File.separatorChar == '\\') "a/b" else "a\\b"
        assertEquals(expected, gitLinkTarget("a\\b"))
    }

    private fun assertLinkCaptured(fixture: WorkspaceFixture, path: String) {
        // Git's spelling: a link to `a/b` reads back as `a\b` on Windows, and the stamp hashes what Git would.
        val targetBytes = linkTarget(fixture.repo.resolve(path)).toByteArray(Charsets.UTF_8)
        val report = fixture.stamper.report()
        val stamped = assertNotNull(report.members[path], "link must be a candidate member")
        assertEquals(EntryType.Symlink, stamped.type)
        assertEquals(FileMode.SYMLINK, stamped.mode)
        assertEquals(Digest.of(targetBytes), stamped.digest)

        val snapshot = fixture.dirtyState.capture()
        val entry = assertNotNull(snapshot.entry(path))
        assertEquals(SnapshotEntryKind.Symlink, entry.kind)
        assertEquals(FileMode.SYMLINK, entry.mode)
        assertContentEquals(targetBytes, fixture.dirtyState.bytesOf(entry))
        assertEquals(report.stamp.id, snapshot.stampId)

        val shadow = fixture.shadowRef()
        shadow.open(snapshot)
        val exported = fixture.store.layout.candidates.resolve("symlink-export")
        val materialized = shadow.materialize(snapshot.turn, exported)
        assertTrue(materialized.ok, "materialization mismatches: ${materialized.mismatches}")
        val exportedLink = exported.resolve(path)
        assertTrue(Files.isSymbolicLink(exportedLink), "materialization must retain the symlink object")
        assertContentEquals(targetBytes, linkTarget(exportedLink).toByteArray(Charsets.UTF_8))
    }

    private fun protectedWorkspace(fixture: WorkspaceFixture): Workspace = Workspace(
        fixture.workspace.id,
        fixture.repo.root,
        fixture.repo.git,
        ProtectedPaths(readDeniedPrefixes = ProtectedPaths.DEFAULT_READ_DENIED + "src/a.py"),
    )

    private fun createSymlink(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: IOException) {
            if (!isWindows()) throw failure
            assumeTrue(false, "Windows host cannot create symbolic links: ${failure.message}")
        } catch (failure: UnsupportedOperationException) {
            if (!isWindows()) throw failure
            assumeTrue(false, "Windows filesystem does not support symbolic links: ${failure.message}")
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
