package io.astrolabe.workflow

import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.event.AgentEvent
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.telemetry.CountedPhase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan §7.2: one task on the dirty repository through the real composition — open, one edit-verify-done cell, reopen,
 * then a second work opened over the same project store — at 300 and at 1500 untracked files, each played once for the
 * whole class, both at once ([Scenario.concurrently]). The counters are printed as `WF-counters` lines (the W2 report's before and after numbers).
 *
 * Guards: WF-2 (the git processes of every open and every snapshot are the same at 300 and 1500 files, and a capture
 * reads each file at most once — the request names a path and a backticked identifier, as the live `real-dirty-repo` request
 * does, so the open's pre-scan builds the import graph and looks the identifier up in the outline of every file, the tool
 * files no parser models included: T-03) and WF-3 (the snapshot after one edited file writes one object; a new work publishes
 * nothing the store or the object database already holds).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirtyRepoScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    // Both sizes play at once on first use; a failed scenario fails every guard once instead of playing again for each.
    private val played by lazy { Scenario.concurrently(listOf(300, 1500), ::scenario) }

    private val at300: Played get() = played.getValue(300).getOrThrow()
    private val at1500: Played get() = played.getValue(1500).getOrThrow()

    @Test
    fun `a task on the dirty repository reaches a terminal outcome and its events carry the phase counters`() {
        val counted = at300.counted
        val opens = counted.filter { it.counted == CountedPhase.Open.wire }
        assertEquals(listOf(1, 2, 3), opens.map { it.opens }, "one open event per open: the first, the reopen and the second work")
        val first = opens.first()
        assertTrue(first.gitProcesses > 0 && first.filesRead > 0, "the first open counts its capture: $first")
        assertTrue(first.bytesRead >= DirtyRepo.BIG_BYTES, "the first open read the big file: $first")
        val snapshots = counted.filter { it.counted == CountedPhase.Snapshot.wire }
        assertTrue(snapshots.isNotEmpty() && snapshots.all { it.gitProcesses > 0 }, "every snapshot is counted: $snapshots")
        val finish = counted.filter { it.counted == CountedPhase.Finish.wire }
        assertEquals(listOf(1), finish.map { it.finishAttempts }, "one finalization attempt")
        assertTrue(finish.single().gitProcesses > 0 && finish.single().filesRead > 0, "the finalization's fresh stamp is counted: $finish")
    }

    @Test
    fun `WF-2 the git processes of every open and every snapshot do not grow from 300 to 1500 untracked files`() {
        for (phase in listOf(CountedPhase.Open, CountedPhase.Snapshot)) {
            val small = at300.counted.filter { it.counted == phase.wire }.map { it.gitProcesses }
            val large = at1500.counted.filter { it.counted == phase.wire }.map { it.gitProcesses }
            assertEquals(small, large, "git processes per ${phase.wire} at 300 and at 1500 untracked files")
        }
    }

    @Test
    fun `WF-2 a capture reads each file at most once`() {
        for (run in listOf(at300, at1500)) {
            val opens = run.counted.filter { it.counted == CountedPhase.Open.wire }
            for (open in opens) {
                // T-03: the capture reads each file once and the atlas takes its outlines from that read; the pre-scan's import
                // graph reads a parsed source again for its dynamic imports, a few manifests and configs besides — never a tool file.
                val reads = open.filesRead + open.blobsRead
                assertTrue(reads <= run.treeFiles + SLACK_FILES,
                    "${run.files} files: an open read ${open.filesRead} files and ${open.blobsRead} blobs for ${run.treeFiles} files")
                // The atlas never parses the big file, so twice its size means a capture read it twice.
                assertTrue(open.bytesRead + open.blobBytesRead < 2L * DirtyRepo.BIG_BYTES,
                    "${run.files} files: an open read ${open.bytesRead} bytes and ${open.blobBytesRead} blob bytes: the big file more than once")
            }
            for (snapshot in run.counted.filter { it.counted == CountedPhase.Snapshot.wire }) {
                assertTrue(snapshot.filesRead + snapshot.blobsRead <= run.treeFiles + SLACK_FILES, "${run.files} files: $snapshot")
                assertTrue(snapshot.bytesRead + snapshot.blobBytesRead < 2L * DirtyRepo.BIG_BYTES, "${run.files} files: $snapshot")
            }
        }
    }

    @Test
    fun `WF-3 the snapshot after one edited file writes one object and a new work publishes nothing already stored`() {
        for (run in listOf(at300, at1500)) {
            val snapshots = run.counted.filter { it.counted == CountedPhase.Snapshot.wire }
            assertEquals(1L, snapshots.sumOf { it.objectsWritten }, "${run.files} files: one edited file is one object: $snapshots")
            val second = run.counted.last { it.counted == CountedPhase.Open.wire }
            assertEquals(0L, second.objectsWritten, "${run.files} files: the second work's open wrote objects git holds: $second")
            // Its own snapshot-0 manifest (a new record) is the one recovery blob it may add; every captured byte is stored already.
            val published = run.recoveryBlobsAfter - run.recoveryBlobsBefore
            assertTrue(run.secondManifest.containsAll(published), "${run.files} files: the second work's open published more than its own manifest: $published")
        }
    }

    /** What one scenario left: its counters and the sizes the guards compare them with. */
    private class Played(
        val files: Int,
        val counted: List<AgentEvent.Telemetry.PhaseCounted>,
        val treeFiles: Int,
        val recoveryBlobsBefore: Set<String>,
        val recoveryBlobsAfter: Set<String>,
        val secondManifest: Set<String>,
    )

    /** Open, one edit-verify-done cell, reopen, then the second work's open; prints the counters as `WF-counters` lines. */
    private fun scenario(files: Int): Played = runBlocking {
        DirtyRepo.create(files).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve("$files"), text = REQUEST).use { s ->
                s.seed(dirty.check)
                s.open()
                val run = s.play { c -> Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)") }
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                val reopened = s.reopen()
                val recovery = reopened.store.layout.blobsRecovery
                val before = blobFiles(recovery)
                val second = s.open(CampaignRequest(WorkId("W-2"), AttemptId("a1"), REQUEST))
                val after = blobFiles(second.store.layout.blobsRecovery)
                val manifest = setOf(checkNotNull(second.shadow.record(0)).manifestBlob.hex)
                val tree = dirty.repo.git.lsFiles().size + dirty.repo.git.status().untracked.size
                val counted = s.counted().onEach {
                    println("WF-counters files=$files ${it.counted} git=${it.gitProcesses} read=${it.filesRead} bytes=${it.bytesRead} " +
                        "blobs=${it.blobsRead} blobBytes=${it.blobBytesRead} objects=${it.objectsWritten} opens=${it.opens} finish=${it.finishAttempts}")
                }
                Played(files, counted, tree, before, after, manifest)
            }
        }
    }

    private fun blobFiles(dir: Path): Set<String> =
        Files.list(dir).use { listing -> listing.filter(Files::isRegularFile).map { it.fileName.toString() }.toList().toSet() }

    private companion object {
        /** Reads beside the capture and the atlas: lock and package manifests, rules, the snapshot manifests. */
        const val SLACK_FILES = 20

        /**
         * Names a path and a backticked identifier, as a live request does: the open's pre-scan builds the import graph and its
         * symbol index reads the outline of every file for the identifier (T-03).
         */
        const val REQUEST = "make `total` in src/app.py ignore negative items"
    }
}
