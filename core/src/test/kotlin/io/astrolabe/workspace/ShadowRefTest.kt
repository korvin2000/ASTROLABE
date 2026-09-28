package io.astrolabe.workspace

import io.astrolabe.os.ObjectId
import io.astrolabe.os.RefUpdateRejected
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * P1.2.4 / IX-12 / FX-05: shadow-ref snapshots and `revert:turn:N`. The manifest is the authority,
 * the Git tree only indexes it, the user's refs and index are untouched, an expected-old-id mismatch
 * fails loudly, and a restore refuses divergent content instead of overwriting it.
 */
class ShadowRefTest {

    @Test
    fun `git clean filter equivalence cannot hide raw candidate changes or recovery bytes`(@TempDir state: Path) {
        WorkspaceFixture.create(state) { repo ->
            repo.cleanFilter("strip", "git stripspace", "*.txt")
            repo.write("filtered.txt", "hello \n")
            repo.commit("filtered content")
        }.use { fixture ->
            assertTrue(fixture.repo.git.status().entries.isEmpty(), "Git considers the filtered file clean")
            val before = fixture.stamper.report()
            assertTrue("filtered.txt" in before.members)
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            fixture.repo.write("filtered.txt", "hello\t\n")
            assertTrue(fixture.repo.git.status().entries.isEmpty())
            val after = fixture.stamper.report()
            assertNotEquals(before.candidateId, after.candidateId)
            assertEquals(setOf("filtered.txt"), Stamper.diff(before, after))
            shadow.snapshot(1)
            val output = state.resolve("raw-candidate")
            assertTrue(shadow.materialize(1, output).ok)
            assertEquals("hello\t\n", Files.readString(output.resolve("filtered.txt")))
            assertIs<RestoreResult.Restored>(shadow.restore(0))
            assertEquals("hello \n", Files.readString(fixture.repo.resolve("filtered.txt")))
            assertEquals(before.candidateId, fixture.stamper.stamp().id)
        }
    }

    @Test
    fun `materialize refuses an omitted clean tree entry`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            val output = state.resolve("export")
            Files.createDirectories(output.resolve("README.md"))
            val result = shadow.materialize(0, output)
            assertTrue(!result.ok)
            assertEquals(listOf("README.md"), result.mismatches)
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX, org.junit.jupiter.api.condition.OS.MAC)
    fun `snapshot exports and restores executable modes and guards chmod changes`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val script = fixture.repo.resolve("check.sh")
            Files.writeString(script, "#!/bin/sh\nexit 0\n")
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            val initial = fixture.dirtyState.capture()
            assertEquals(io.astrolabe.os.FileMode.EXECUTABLE, initial.entry("check.sh")!!.mode)
            val shadow = fixture.shadowRef()
            shadow.open(initial)
            val output = state.resolve("export")
            assertTrue(shadow.materialize(0, output).ok)
            assertEquals(0, ProcessBuilder(output.resolve("check.sh").toString()).start().waitFor())
            fixture.os.replaceFileAtomically(script, "#!/bin/sh\nexit 1\n".toByteArray())
            assertTrue(Files.isExecutable(script))
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            shadow.snapshot(1)
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            assertIs<RestoreResult.Divergent>(shadow.restore(0))
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
            assertIs<RestoreResult.Restored>(shadow.restore(0))
            assertEquals(0, ProcessBuilder(script.toString()).start().waitFor())
        }
    }

    @Test
    fun `reopen finishes an interrupted snapshot index publication including turn zero`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val failingOs = object : io.astrolabe.os.Os by fixture.os {
                override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
                    if (path.fileName.toString() == "ws-1.json") throw java.io.IOException("index publication failed")
                    fixture.os.replaceFileAtomically(path, bytes)
                }
            }
            val interrupted = ShadowRef(fixture.ids.work, fixture.ids.attempt, fixture.workspace,
                fixture.store, fixture.dirtyState, failingOs, fixture.clock)
            for (turn in 0..1) {
                fixture.repo.modify("src/a.py", "def a():\n    return ${turn + 10}\n")
                val captured = fixture.dirtyState.capture(turn)
                assertFailsWith<java.io.IOException> {
                    if (turn == 0) interrupted.open(captured) else interrupted.snapshot(captured)
                }
                val movedHead = interrupted.head()!!
                val reopened = fixture.shadowRef()
                assertEquals(movedHead.hex, reopened.record(turn)!!.commit)
                assertEquals(captured, reopened.manifest(turn))
                assertEquals(turn + 1, reopened.records().size)
                assertEquals(reopened.records(), fixture.shadowRef().records(), "recovery is idempotent")
            }
            fixture.shadowRef().snapshot(2)
            assertEquals(3, fixture.shadowRef().records().size)
        }
    }

    @Test
    fun `snapshots 1 to 3 move the ref and stay selectable in constant time`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            assertEquals(
                "refs/astrolabe/work-1/attempt-1/ws-1/head",
                shadow.ref,
            )
            assertNull(shadow.head())

            val zero = shadow.open(fixture.dirtyState.capture())
            val commits = mutableListOf(zero.commit)
            for (turn in 1..3) {
                fixture.repo.modify("src/a.py", "def a():\n    return $turn\n")
                commits.add(shadow.snapshot(turn).commit)
            }

            assertEquals(4, shadow.records().size)
            assertEquals(commits.distinct(), commits, "each turn is its own commit")
            assertEquals(ObjectId.parse(commits.last()), shadow.head(), "the ref moved to the newest")
            for (turn in 0..3) assertEquals(commits[turn], shadow.record(turn)!!.commit)
            assertNull(shadow.record(9))

            // The recorded manifest is what the tree indexes, and it round-trips.
            val second = shadow.manifest(2)!!
            assertEquals(2, second.turn)
            assertContentEquals(
                "def a():\n    return 2\n".toByteArray(),
                fixture.store.blobs.get(second.entry("src/a.py")!!.digest!!),
            )
        }
    }

    @Test
    fun `snapshotting never touches the user's refs, index or stash`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 5\n")
            fixture.repo.write("src/b.py", "def b():\n    return 6\n")
            fixture.repo.stage("src/b.py")
            val refsBefore = fixture.userRefs()
            val indexBefore = fixture.indexBytes()

            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            fixture.repo.modify("src/a.py", "def a():\n    return 7\n")
            shadow.snapshot(1)
            val restored = shadow.restore(0)

            assertIs<RestoreResult.Restored>(restored)
            assertEquals(refsBefore, fixture.userRefs(), "no user ref moved")
            assertContentEquals(indexBefore, fixture.indexBytes(), "the user's index is byte-identical")
            assertContentEquals("def a():\n    return 5\n".toByteArray(), fixture.bytes("src/a.py"))
            assertContentEquals(
                "def b():\n    return 6\n".toByteArray(),
                fixture.bytes("src/b.py"),
                "the staged file was not disturbed",
            )
        }
    }

    @Test
    fun `an expected-old-id mismatch fails loudly`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            val zero = shadow.open(fixture.dirtyState.capture())

            // Another writer moves the ref behind our back.
            val other = fixture.repo.git.revParse("HEAD")
            assertNotEquals(zero.commit, other.hex)
            fixture.rawGit("update-ref", shadow.ref, other.hex)

            fixture.repo.modify("src/a.py", "def a():\n    return 42\n")
            val failure = assertFailsWith<RefUpdateRejected> { shadow.snapshot(1) }

            assertEquals(shadow.ref, failure.ref)
            assertEquals(zero.commit, failure.expectedOld?.hex)
            assertEquals(other, fixture.repo.git.readRef(shadow.ref), "the ref is unchanged")
        }
    }

    @Test
    fun `a gitattributes filter never changes snapshot bytes and never runs at snapshot time`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state) { repo ->
            repo.gitattributes("* text=auto eol=lf\n")
            repo.cleanFilter("strip", "git stripspace", "*.py")
            repo.crlfVariant()
        }.use { fixture ->
            val dirty = "def a():   \r\n    return 321   \r\n".toByteArray(StandardCharsets.UTF_8)
            fixture.repo.write("src/a.py", dirty)

            val shadow = fixture.shadowRef()
            val zero = shadow.open(fixture.dirtyState.capture())

            // IX-12: the blob the tree indexes holds the raw bytes, trailing whitespace and CRLF.
            val treeBlob = fixture.rawGit("ls-tree", zero.commit, "--", "src/a.py")
                .trim()
                .substringAfter(' ')
                .substringAfter(' ')
                .substringBefore('\t')
            assertContentEquals(dirty, fixture.repo.git.catFile(ObjectId.parse(treeBlob)))
            assertContentEquals(dirty, fixture.bytes("src/a.py"), "snapshotting did not rewrite the file")

            // And a materialized candidate verifies against the manifest.
            val exported = state.resolve("candidate-0")
            val result = shadow.materialize(0, exported)
            assertTrue(result.ok, "mismatches: ${result.mismatches}")
            assertContentEquals(dirty, Files.readAllBytes(exported.resolve("src/a.py")))
        }
    }

    @Test
    fun `restore refuses divergent content instead of overwriting the human`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            fixture.repo.modify("src/a.py", "def a():\n    return 1\n")
            shadow.snapshot(1)
            fixture.repo.modify("src/a.py", "def a():\n    return 2\n")
            shadow.snapshot(2)

            // FX-05: the human edits the file after the last snapshot.
            val humanBytes = "def a():\n    return 'mine'\n".toByteArray()
            fixture.repo.write("src/a.py", humanBytes)

            val refused = shadow.restore(1)

            assertIs<RestoreResult.Divergent>(refused)
            assertEquals(listOf("src/a.py"), refused.paths)
            assertContentEquals(humanBytes, fixture.bytes("src/a.py"), "the human's bytes are untouched")
        }
    }

    @Test
    fun `restoring the newest turn after a human edit refuses instead of finding nothing to do`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            // Turn 1 is the newest snapshot, and the campaign really changed this file in it.
            fixture.repo.modify("src/a.py", "def a():\n    return 111\n")
            shadow.snapshot(1)

            val humanBytes = "def a():\n    return 'mine'\n".toByteArray()
            fixture.repo.write("src/a.py", humanBytes)

            val refused = shadow.restore(1)

            assertIs<RestoreResult.Divergent>(refused)
            assertEquals(listOf("src/a.py"), refused.paths)
            assertContentEquals(humanBytes, fixture.bytes("src/a.py"))
        }
    }

    @Test
    fun `restore puts back the bytes of the selected turn and removes what came after`(
        @TempDir state: Path,
    ) {
        WorkspaceFixture.create(state).use { fixture ->
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            fixture.repo.modify("src/a.py", "def a():\n    return 1\n")
            shadow.snapshot(1)
            fixture.repo.modify("src/a.py", "def a():\n    return 2\n")
            fixture.repo.untracked("added-later.txt", "turn two\n")
            shadow.snapshot(2)

            val restored = shadow.restore(1)

            assertIs<RestoreResult.Restored>(restored)
            assertEquals(listOf("src/a.py"), restored.written)
            assertEquals(listOf("added-later.txt"), restored.deleted)
            assertContentEquals("def a():\n    return 1\n".toByteArray(), fixture.bytes("src/a.py"))
            assertTrue(!Files.exists(fixture.repo.resolve("added-later.txt")))
        }
    }

    @Test
    fun `materialize exports a candidate and verifies every manifest entry`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 11\n")
            fixture.repo.untracked("scratch.txt", "notes\n")
            val shadow = fixture.shadowRef()
            shadow.open(fixture.dirtyState.capture())
            fixture.repo.modify("src/a.py", "def a():\n    return 22\n")
            shadow.snapshot(2)

            val exported = state.resolve("candidate-2")
            val result = shadow.materialize(2, exported)

            assertTrue(result.ok, "mismatches: ${result.mismatches}")
            assertEquals(2, result.verified, "both manifest entries were checked against their bytes")
            assertTrue(result.files.containsAll(listOf("README.md", "src/a.py", "src/b.py", "scratch.txt")))
            assertContentEquals("def a():\n    return 22\n".toByteArray(), Files.readAllBytes(exported.resolve("src/a.py")))
            assertContentEquals("notes\n".toByteArray(), Files.readAllBytes(exported.resolve("scratch.txt")))
            assertTrue(
                result.limitations.any { it.contains("object store") },
                "files outside the manifest report their provenance: ${result.limitations}",
            )
        }
    }

    @Test
    fun `snapshot 0 is the dirty state and cannot be replaced`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\n    return 'user'\n")
            val shadow = fixture.shadowRef()
            val initial = fixture.dirtyState.capture()
            shadow.open(initial)

            assertFailsWith<IllegalStateException> { shadow.open(fixture.dirtyState.capture()) }
            assertFailsWith<IllegalArgumentException> { shadow.snapshot(0) }
            assertEquals(initial.manifestDigest, shadow.manifest(0)!!.manifestDigest)
        }
    }
}
