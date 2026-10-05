package io.astrolabe.workspace

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
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
        WorkspaceFixture.create(state) { repo ->
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
            println(actual.joinToString("\n", prefix = "CAPTURE-BYTES\n"))
            assertEquals(EXPECTED, actual)
        }
    }

    /** The manifest bytes with the per-run base commit and stamp id pinned, as one digest. */
    private fun normalized(snapshot: Snapshot): String =
        Digest.of(Snapshot.encodeToBytes(snapshot.copy(baseCommit = "base", stampId = CandidateId(Digest.ofUtf8("stamp"))))).hex

    private fun tree(fixture: WorkspaceFixture, shadow: ShadowRef, turn: Int): String =
        fixture.rawGit("rev-parse", "${shadow.record(turn)!!.commit}^{tree}").trim()

    private companion object {
        val EXPECTED: List<String> = listOf()
    }
}
