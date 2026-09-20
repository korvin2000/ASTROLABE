package io.astrolabe.workspace

import io.astrolabe.os.FileMode
import io.astrolabe.os.RepositoryForm
import io.astrolabe.os.UnsupportedRepositoryForm
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
}
