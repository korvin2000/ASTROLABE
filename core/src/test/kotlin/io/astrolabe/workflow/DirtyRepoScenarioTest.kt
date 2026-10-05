package io.astrolabe.workflow

import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.event.AgentEvent
import io.astrolabe.telemetry.CountedPhase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan §7.2 (W0): one task on the dirty repository through the real composition, from open to a terminal outcome, with
 * the phase counters in its events. The printed `WF-baseline` lines are the "before" numbers for W2 (300 and 1500
 * untracked files); they are recorded, not guarded here — the WF-2/3/4 guards are W2's. The 1500-file run costs about
 * 3,000 git processes today (WD-01), past the suite's three minutes on Windows, so it runs only with
 * `ASTROLABE_WF_1500=1` until W2 makes the boundary cost independent of the untracked count.
 */
class DirtyRepoScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `a task on the dirty repository reaches a terminal outcome and its events carry the phase counters`() {
        val counted = scenario(300)
        val opens = counted.filter { it.counted == CountedPhase.Open.wire }
        assertEquals(listOf(1, 2), opens.map { it.opens }, "one open event per open: the first and the reopen")
        val first = opens.first()
        assertTrue(first.gitProcesses > 0 && first.filesRead > 0 && first.objectsWritten > 0, "the first open counts its capture: $first")
        assertTrue(first.bytesRead >= DirtyRepo.BIG_BYTES, "the first open read the big file: $first")
        val snapshots = counted.filter { it.counted == CountedPhase.Snapshot.wire }
        assertTrue(snapshots.isNotEmpty() && snapshots.all { it.gitProcesses > 0 }, "every snapshot is counted: $snapshots")
        assertTrue(snapshots.any { it.objectsWritten > 0 }, "the snapshot after the edit writes objects: $snapshots")
        val finish = counted.filter { it.counted == CountedPhase.Finish.wire }
        assertEquals(listOf(1), finish.map { it.finishAttempts }, "one finalization attempt")
        assertTrue(finish.single().gitProcesses > 0 && finish.single().filesRead > 0, "the finalization's fresh stamp is counted: $finish")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ASTROLABE_WF_1500", matches = "1")
    fun `the phase counters at 1500 untracked files are the baseline beside 300`() {
        val counted = scenario(1500)
        assertTrue(counted.any { it.counted == CountedPhase.Open.wire && it.filesRead > 0 })
    }

    /** Open, one edit-verify-done cell, reopen; prints the counters as `WF-baseline` lines and returns them. */
    private fun scenario(files: Int): List<AgentEvent.Telemetry.PhaseCounted> = runBlocking {
        DirtyRepo.create(files).use { dirty ->
            Scenario(dirty.root, stateRoot).use { s ->
                s.seed(dirty.check)
                s.open()
                val run = s.play { c -> Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)") }
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                s.reopen()
                s.counted().onEach {
                    println("WF-baseline files=$files ${it.counted} git=${it.gitProcesses} read=${it.filesRead} bytes=${it.bytesRead} objects=${it.objectsWritten} opens=${it.opens} finish=${it.finishAttempts}")
                }
            }
        }
    }
}
