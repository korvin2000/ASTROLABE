package io.astrolabe.telemetry

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Phase
import io.astrolabe.id.Identities
import io.astrolabe.os.PhaseTally
import kotlinx.coroutines.withContext

/** The phases §7.2 counts (`phase.counted`): a campaign open, a shadow snapshot of a tree, a finalization attempt. */
public enum class CountedPhase(public val wire: String, internal val phase: Phase) {
    Open("open", Phase.Understand),
    Snapshot("snapshot", Phase.Compact),
    Finish("finish", Phase.Verify),
}

/**
 * The counters of one phase call (§7.2, T-13): the git processes, written objects and tree and blob reads the phase's
 * own work made — counted per call through its [PhaseTally], never as differences of totals on instances a host shares,
 * so a host's `git status` on the same instance while the phase runs is not the phase's. A nested phase (a snapshot
 * inside a finalization) adds to the phase it runs in, as before.
 */
internal class PhaseMark private constructor(private val tally: PhaseTally) {
    /** Runs the synchronous phase [block] counted by this mark. */
    fun <T> run(block: () -> T): T = tally.within(block)

    /** Runs the suspending phase [block] counted by this mark, on whichever threads it resumes. */
    suspend fun <T> runSuspending(block: suspend () -> T): T = withContext(tally.element) { block() }

    fun counted(ids: Identities, phase: CountedPhase, opens: Int, finishAttempts: Int): AgentEvent.Telemetry.PhaseCounted =
        AgentEvent.Telemetry.PhaseCounted(
            ids, phase.wire,
            gitProcesses = tally[PhaseTally.Count.GitProcesses],
            filesRead = tally[PhaseTally.Count.FilesRead],
            bytesRead = tally[PhaseTally.Count.BytesRead],
            objectsWritten = tally[PhaseTally.Count.ObjectsWritten],
            opens = opens, finishAttempts = finishAttempts,
            blobsRead = tally[PhaseTally.Count.BlobsRead],
            blobBytesRead = tally[PhaseTally.Count.BlobBytesRead],
            phase = phase.phase,
        )

    companion object {
        /** A new mark, nested in the phase the calling thread or coroutine runs in, if any. */
        fun begin(): PhaseMark = PhaseMark(PhaseTally.open())
    }
}
