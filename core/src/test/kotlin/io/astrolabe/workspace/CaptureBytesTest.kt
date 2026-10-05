package io.astrolabe.workspace

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.verify.ScratchPolicy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WD-01/WD-02 (W2): the capture now reads each file once and the snapshot writes only new objects, but the bytes it
 * produces are the bytes it produced before. The expected values below were computed by the capture before that change
 * (`79337a0`) on this very tree; the base commit differs per run (commit time), so the manifest is compared with the base
 * commit and the stamp id pinned, and the stamp by its base-independent hashes.
 */
class CaptureBytesTest {

    @Test
    fun `stamp, manifest and snapshot tree bytes are the ones the capture produced before one read per file`(@TempDir state: Path) {
        assertEquals(EXPECTED, captured(state, ScratchPolicy.NONE))
    }

    /**
     * W3: under the output policy the manifests and the untracked stamp are v2 — they name the policy — and output under a
     * declared root (`out/report.txt`) is outside them: the snapshot trees and the tracked delta are v1's, byte for byte.
     * The v2 values were computed by this capture when the policy was introduced.
     */
    @Test
    fun `under the output policy the manifests and the untracked stamp are v2 and the output stays out of the trees`(@TempDir state: Path) {
        val actual = captured(state, ScratchPolicy.BUILT_IN) { it.repo.untracked("out/report.txt", "scratch\n") }
        assertEquals(listOf(EXPECTED[1], EXPECTED[3], EXPECTED[4]), listOf(actual[1], actual[3], actual[4]))
        assertEquals(EXPECTED_V2, actual)
    }

    private fun captured(state: Path, scratch: ScratchPolicy, more: (WorkspaceFixture) -> Unit = {}): List<String> =
        WorkspaceFixture.create(state, scratch = scratch) { repo ->
            repo.write("data/table.bin", ByteArray(100_000) { (it * 7 + (it ushr 9)).toByte() })
            repo.write("src/gone.py", "gone\n")
            repo.write(".gitignore", "build/\n")
            repo.commit("more")
        }.use { fixture ->
            fixture.repo.modify("src/a.py", "def a():\r\n    return 10\r\n")
            fixture.repo.modify("src/b.py", "def b():\n    return 20\n")
            fixture.repo.stage("src/b.py")
            fixture.repo.modify("src/b.py", "def b():\n    return 30\n")
            java.nio.file.Files.delete(fixture.repo.resolve("src/gone.py"))
            fixture.repo.untracked("notes/todo.txt", "write the thing\n")
            fixture.repo.untracked("notes/empty.txt", "")
            fixture.repo.untracked("devtools/pkg/tool.txt", "tool\n")
            fixture.repo.write("devtools/blob.bin", ByteArray(70_000) { (it * 31).toByte() })
            fixture.repo.untracked("build/out.o", "ignored\n")
            more(fixture)
            val shadow = fixture.shadowRef()

            val s0 = fixture.dirtyState.capture(0, fresh = true)
            shadow.open(s0)
            fixture.repo.modify("notes/todo.txt", "write the other thing\n")
            val s1 = fixture.dirtyState.capture(1)
            shadow.snapshot(s1)
            val report = fixture.stamper.report(fresh = true)

            val actual = listOf(
                "s0 manifest " + normalized(s0),
                "s0 tree " + tree(fixture, shadow, 0),
                "s1 manifest " + normalized(s1),
                "s1 tree " + tree(fixture, shadow, 1),
                "stamp tracked " + report.stamp.trackedDeltaHash.hex,
                "stamp untracked " + report.stamp.untrackedManifestHash.hex,
            )
            println(actual.joinToString("\n", prefix = "CAPTURE-BYTES ${scratch.version}\n"))
            actual
        }

    /** The manifest bytes with the per-run base commit and stamp id pinned, as one digest. */
    private fun normalized(snapshot: Snapshot): String =
        Digest.of(Snapshot.encodeToBytes(snapshot.copy(baseCommit = "base", stampId = CandidateId(Digest.ofUtf8("stamp"))))).hex

    private fun tree(fixture: WorkspaceFixture, shadow: ShadowRef, turn: Int): String =
        fixture.rawGit("rev-parse", "${shadow.record(turn)!!.commit}^{tree}").trim()

    private companion object {
        val EXPECTED: List<String> = listOf(
            "s0 manifest fd1acf5ae178df7c1cb01a288f36a31dd2fccc14bac58399477d574b1312fbcf",
            "s0 tree 6a8c3147266e9f2272dff018c2fb7c4ec16664df",
            "s1 manifest 85221582e140cfc0ff09371a2025e68579700c108194870d635d96d304ec5ac9",
            "s1 tree 4b97e7568e0d68d46f9a086e21396510265ff8c3",
            "stamp tracked 68c703dac2bcc6b634b34db71b237accbf9f9c25aba20eaceb014ceb43ec051c",
            "stamp untracked 93a15db6c06736240cec12e73658e19cf284e8a6e41c4c6368528be16cef00f2",
        )

        val EXPECTED_V2: List<String> = listOf(
            "s0 manifest 50ef896d31a72a148159fe46a685b6d9c77a72973f3df8a0cf8d4f7c5119b937",
            "s0 tree 6a8c3147266e9f2272dff018c2fb7c4ec16664df",
            "s1 manifest ca647ba1c198ba85584fca3a451e0b42b9dfec7a7df0285a85ab828c59793b2a",
            "s1 tree 4b97e7568e0d68d46f9a086e21396510265ff8c3",
            "stamp tracked 68c703dac2bcc6b634b34db71b237accbf9f9c25aba20eaceb014ceb43ec051c",
            "stamp untracked e591ec59a33b33c800d5aaf82d736b7917940f2a81ac3a713ca0f09d9daa4867",
        )
    }
}
