package io.astrolabe.workspace

import io.astrolabe.os.FileMode
import io.astrolabe.os.RepositoryForm
import io.astrolabe.os.UnsupportedRepositoryForm
import io.astrolabe.verify.ScratchPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * P1.2.3 / FX-06 / IX-12: the initial dirty-state record. Tracked delta, the user's staged content
 * and relevant untracked files are captured byte-exactly, ignored inputs are accounted for, and a
 * `.gitattributes` end-of-line rule with a live clean filter changes nothing about the bytes.
 */
class DirtyStateTest {

    @Test
    fun `capture records tracked delta, staged content and untracked files with an s0 stamp`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 99\n")
            fixture.repo.write("src/b.py", "def b():\n    return 42\n")
            fixture.repo.stage("src/b.py")
            fixture.repo.untracked("scratch.txt", "user notes\n")

            val snapshot = fixture.dirtyState.capture()

            assertEquals(0, snapshot.turn)
            assertEquals(
                listOf("scratch.txt", "src/a.py", "src/b.py"),
                snapshot.entries.map { it.path },
            )
            assertEquals(fixture.stamper.report().stamp.id, snapshot.stampId)
            assertEquals(fixture.repo.git.revParse("HEAD").hex, snapshot.baseCommit)

            // Every captured byte is on disk as an exact recovery blob.
            for (entry in snapshot.entries) {
                assertContentEquals(fixture.bytes(entry.path), fixture.dirtyState.bytesOf(entry))
            }

            // D-53: the user's staged content is captured separately, from the index, untouched.
            val staged = snapshot.staged.single { it.path == "src/b.py" }
            assertEquals(FileMode.REGULAR, staged.mode)
            assertEquals(0, staged.stage)
            assertContentEquals(
                "def b():\n    return 42\n".toByteArray(),
                fixture.store.blobs.get(staged.digest),
            )
        }
    }

    @Test
    fun `a CRLF rule and a live clean filter do not alter the captured bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state) { repo ->
            repo.gitattributes("* text=auto eol=lf\n")
            // `git stripspace` is a real, always-available clean filter that rewrites content: it
            // removes trailing whitespace from every line it is given. Raw capture must bypass it.
            repo.cleanFilter("strip", "git stripspace", "*.py")
            repo.crlfVariant()
        }.use { fixture ->
            val crlf = "def a():   \r\n    return 123   \r\n".toByteArray(StandardCharsets.UTF_8)
            fixture.repo.write("src/a.py", crlf)
            val untrackedBytes = "x = 'lower'   \r\n".toByteArray(StandardCharsets.UTF_8)
            fixture.repo.write("notes.py", untrackedBytes)

            val snapshot = fixture.dirtyState.capture()

            val tracked = snapshot.entry("src/a.py")!!
            assertContentEquals(
                crlf,
                fixture.dirtyState.bytesOf(tracked),
                "CRLF and trailing whitespace survive capture (IX-12)",
            )
            val untracked = snapshot.entry("notes.py")!!
            assertContentEquals(
                untrackedBytes,
                fixture.dirtyState.bytesOf(untracked),
                "the configured clean filter never ran",
            )
        }
    }

    @Test
    fun `ignored inputs are counted and excluded, unreadable ones are named`(@TempDir state: Path) {
        WorkspaceFixture.create(state) { repo ->
            repo.write(".gitignore", "build/\n")
            repo.commit("ignore build")
        }.use { fixture ->
            fixture.repo.untracked("build/out.o", "binary\n")
            fixture.repo.untracked("kept.txt", "kept\n")

            val snapshot = fixture.dirtyState.capture()

            assertEquals(listOf("kept.txt"), snapshot.entries.map { it.path })
            assertTrue(snapshot.ignoredCount >= 1, "the exclusion is explicit: ${snapshot.ignoredCount}")
            assertTrue(snapshot.unreadable.isEmpty())
        }
    }

    @Test
    fun `a deleted tracked file is captured as a deletion, not as missing bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            Files.delete(fixture.repo.resolve("src/b.py"))

            val snapshot = fixture.dirtyState.capture()
            val entry = snapshot.entry("src/b.py")!!

            assertEquals(SnapshotEntryKind.Deleted, entry.kind)
            assertTrue(!entry.present)
            assertEquals(setOf("src/b.py"), snapshot.paths - snapshot.presentPaths)
        }
    }

    @Test
    fun `the manifest digest is identity and excludes the turn and the capture time`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 3\n")

            val first = fixture.dirtyState.capture(turn = 0)
            fixture.clock.advance(Duration.ofHours(2))
            val second = fixture.dirtyState.capture(turn = 5)

            assertEquals(first.manifestDigest, second.manifestDigest)
            assertTrue(second.capturedAt.isAfter(first.capturedAt))

            val decoded = Snapshot.decode(Snapshot.encodeToBytes(second))
            assertEquals(second, decoded)
            assertEquals(second.manifestDigest, decoded.manifestDigest)
        }
    }

    @Test
    fun `an unsupported repository form is refused by name`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.write(".gitmodules", "[submodule \"x\"]\n\tpath = x\n\turl = ./x\n")

            val failure = assertFailsWith<UnsupportedRepositoryForm> { fixture.dirtyState.capture() }

            assertEquals(RepositoryForm.SUBMODULES, failure.form)
            assertTrue(failure.message!!.contains("submodules"), failure.message!!)
        }
    }

    // ------------------------------------------------------------------ FX-06

    @Test
    fun `user changes survive an agent edit and are separated in the finish receipt`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            // The user's own dirty work, present before the campaign opens.
            fixture.repo.modify("src/b.py", "def b():\n    return 'user work'\n")
            fixture.repo.untracked("user-notes.md", "do not touch\n")
            val userB = fixture.bytes("src/b.py")
            val userNotes = fixture.bytes("user-notes.md")

            val initial = fixture.dirtyState.capture()

            // The campaign edits a different file; a run touches a third.
            fixture.repo.modify("src/a.py", "def a():\n    return 'agent'\n")
            fixture.repo.untracked("generated.txt", "by the formatter\n")
            val finalReport = fixture.stamper.report()

            val separated = DirtyState.separate(
                initial = initial,
                final = finalReport,
                agentEdits = setOf("src/a.py"),
                runTouched = setOf("generated.txt"),
            )

            assertEquals(setOf("src/a.py"), separated.agent)
            assertEquals(setOf("generated.txt"), separated.byRun)
            assertEquals(setOf("src/b.py", "user-notes.md"), separated.preExistingUserChanges)
            assertTrue(separated.unattributed.isEmpty())
            assertEquals(ChangeSource.PreExistingUserChanges, separated.sourceOf("src/b.py"))

            // FX-06: the user's bytes are still exactly what they were.
            assertContentEquals(userB, fixture.bytes("src/b.py"))
            assertContentEquals(userNotes, fixture.bytes("user-notes.md"))
        }
    }

    @Test
    fun `a change nobody claims is reported as unattributed rather than blamed on the user`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state).use { fixture ->
            val initial = fixture.dirtyState.capture()
            assertTrue(initial.entries.isEmpty(), "a clean tree has an empty dirty-state record")

            fixture.repo.modify("src/a.py", "def a():\n    return 'from outside'\n")

            val separated = DirtyState.separate(
                initial = initial,
                final = fixture.stamper.report(),
                agentEdits = emptySet(),
                runTouched = emptySet(),
            )

            assertEquals(setOf("src/a.py"), separated.unattributed)
            assertTrue(separated.preExistingUserChanges.isEmpty())
            assertNotNull(separated.sourceOf("src/a.py"))
        }
    }

    @Test
    fun `a capture whose first attempt sees a concurrent change succeeds on retry`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 2\n")
            val attempts = ArrayList<Int>()
            fixture.dirtyState.beforeRecheck = { attempt ->
                attempts += attempt
                if (attempt == 1) fixture.repo.modify("src/a.py", "def a():\n    return 3\n")
            }

            val snapshot = fixture.dirtyState.capture()

            assertEquals(listOf(1, 2), attempts)
            val entry = snapshot.entries.single { it.path == "src/a.py" }
            assertContentEquals("def a():\n    return 3\n".toByteArray(), fixture.dirtyState.bytesOf(entry))
        }
    }

    @Test
    fun `a writer that never settles still fails capture after the last attempt`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            var n = 0
            fixture.dirtyState.beforeRecheck = { fixture.repo.modify("src/a.py", "writer ${++n}\n") }

            assertFailsWith<SnapshotIntegrityError> { fixture.dirtyState.capture() }
            assertEquals(3, n)
        }
    }

    @Test
    fun `a same-size rewrite with a restored modification time before the recheck is never captured stale`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.untracked("notes/n.txt", "first version\n")
            // Outside the racy window, so only the change time (ctime, NTFS ChangeTime) or a second read can see the rewrite.
            Thread.sleep(2_200)
            val file = fixture.repo.resolve("notes/n.txt")
            val attempts = ArrayList<Int>()
            fixture.dirtyState.beforeRecheck = { attempt ->
                attempts += attempt
                if (attempt == 1) {
                    val modified = Files.getLastModifiedTime(file)
                    Files.write(file, "other version\n".toByteArray())
                    Files.setLastModifiedTime(file, modified)
                }
            }

            val snapshot = fixture.dirtyState.capture(fresh = true)

            assertEquals(listOf(1, 2), attempts, "the recheck saw the rewrite and the capture was taken again")
            assertContentEquals("other version\n".toByteArray(), fixture.dirtyState.bytesOf(assertNotNull(snapshot.entry("notes/n.txt"))))
            assertEquals(fixture.stamper.stamp(fresh = true).id, snapshot.stampId)
        }
    }

    // ---------------------------------------------------------- content reuse (D-364)

    @Test
    fun `a second capture of an unchanged tree reads no content and writes the same manifest bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 7\n")
            fixture.repo.untracked("notes/big.txt", "x".repeat(10_000))
            fixture.settle()

            val first = fixture.dirtyState.capture(turn = 1)
            val reads = fixture.workspace.contents.reads.get()
            val second = fixture.dirtyState.capture(turn = 1)

            assertEquals(reads, fixture.workspace.contents.reads.get(), "no content was read again")
            assertContentEquals(Snapshot.encodeToBytes(first), Snapshot.encodeToBytes(second))
            val fresh = Workspace(io.astrolabe.id.WorkspaceId("ws-fresh"), fixture.repo.root, fixture.repo.git)
            val uncached = DirtyState(fresh, fixture.store.blobs, Stamper(fresh, TEST_ENV), fixture.ids, fixture.clock).capture(turn = 1)
            assertContentEquals(Snapshot.encodeToBytes(uncached), Snapshot.encodeToBytes(second))
        }
    }

    @Test
    fun `a changed file is read and stored again`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.untracked("notes.txt", "aaaa\n")
            fixture.settle()
            fixture.dirtyState.capture()
            val reads = fixture.workspace.contents.reads.get()

            fixture.repo.write("notes.txt", "bbbb\n")
            fixture.settle(ageSeconds = 1800)
            val entry = fixture.dirtyState.capture().entry("notes.txt")!!

            assertTrue(fixture.workspace.contents.reads.get() > reads)
            assertEquals(io.astrolabe.id.Digest.of("bbbb\n".toByteArray()), entry.digest)
            assertContentEquals("bbbb\n".toByteArray(), fixture.dirtyState.bytesOf(entry))
        }
    }

    @Test
    fun `a snapshot captured through reuse restores the right bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 'user'\n")
            fixture.repo.untracked("notes.txt", "keep me\n")
            fixture.settle()
            fixture.dirtyState.capture()
            val reads = fixture.workspace.contents.reads.get()
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture(0))
            assertEquals(reads, fixture.workspace.contents.reads.get(), "snapshot 0 came from the reuse path")

            fixture.repo.modify("src/a.py", "def a():\n    return 'agent'\n")
            fixture.repo.write("notes.txt", "overwritten\n")
            shadow.snapshot(1)

            kotlin.test.assertIs<RestoreResult.Restored>(shadow.restore(0))
            assertEquals("def a():\n    return 'user'\n", String(fixture.bytes("src/a.py"), StandardCharsets.UTF_8))
            assertEquals("keep me\n", String(fixture.bytes("notes.txt"), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `a writer that only adds output under a declared root never fails a capture under the output policy`(@TempDir state: Path) {
        WorkspaceFixture.create(state, scratch = ScratchPolicy.BUILT_IN) { repo ->
            val exclude = repo.root.resolve(".git/info/exclude")
            Files.createDirectories(exclude.parent)
            Files.write(exclude, ByteArray(0))
            repo.config("core.excludesFile", exclude.toString().replace('\\', '/'))
        }.use { fixture ->
            fixture.repo.untracked("notes/n.txt", "kept\n")
            fixture.repo.untracked("build/old.txt", "output\n")
            var n = 0
            // A dev server writing build output through the whole capture: the raw git status differs at every recheck (P2-3).
            fixture.dirtyState.beforeRecheck = { fixture.repo.untracked("build/new-${++n}.txt", "output $n\n") }

            val snapshot = fixture.dirtyState.capture(fresh = true)

            assertEquals(1, n, "one attempt: output under a root is no change of what was captured")
            assertEquals(listOf("notes/n.txt"), snapshot.entries.map { it.path })
            assertEquals(ScratchPolicy.BUILT_IN.id, snapshot.scratchPolicy)
            assertEquals(1, snapshot.scratchCount)
            assertEquals(snapshot, fixture.dirtyState.latest, "the instance keeps its own last capture for the atlas")
        }
    }
}
