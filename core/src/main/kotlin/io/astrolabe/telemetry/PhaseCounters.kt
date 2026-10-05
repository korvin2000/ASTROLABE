package io.astrolabe.telemetry

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Phase
import io.astrolabe.id.Identities
import io.astrolabe.os.Git
import io.astrolabe.workspace.Workspace

/** The phases §7.2 counts (`phase.counted`): a campaign open, a shadow snapshot of a tree, a finalization attempt. */
public enum class CountedPhase(public val wire: String, internal val phase: Phase) {
    Open("open", Phase.Understand),
    Snapshot("snapshot", Phase.Compact),
    Finish("finish", Phase.Verify),
}

/**
 * The counters of [workspace] and its git at the start of a phase (§7.2). Both are monotonic per instance, so the
 * phase's cost is a difference; concurrent work on the same tree during the phase is counted with it.
 */
internal class PhaseMark private constructor(
    private val workspace: Workspace,
    private val gitProcesses: Long,
    private val filesRead: Long,
    private val bytesRead: Long,
    private val objectsWritten: Long,
    private val blobsRead: Long,
    private val blobBytesRead: Long,
) {
    fun counted(ids: Identities, phase: CountedPhase, opens: Int, finishAttempts: Int): AgentEvent.Telemetry.PhaseCounted =
        AgentEvent.Telemetry.PhaseCounted(
            ids, phase.wire,
            gitProcesses = workspace.git.processesStarted - gitProcesses,
            filesRead = workspace.filesRead - filesRead,
            bytesRead = workspace.bytesRead - bytesRead,
            objectsWritten = workspace.git.objectsWritten - objectsWritten,
            opens = opens, finishAttempts = finishAttempts,
            blobsRead = workspace.blobsRead - blobsRead,
            blobBytesRead = workspace.blobBytesRead - blobBytesRead,
            phase = phase.phase,
        )

    companion object {
        /** A phase that failed before its workspace existed (an open refused by the store or its project lock): its git counts only. */
        fun beforeWorkspace(ids: Identities, git: Git, phase: CountedPhase, opens: Int, finishAttempts: Int = 0): AgentEvent.Telemetry.PhaseCounted =
            AgentEvent.Telemetry.PhaseCounted(
                ids, phase.wire, gitProcesses = git.processesStarted, filesRead = 0, bytesRead = 0, objectsWritten = git.objectsWritten,
                opens = opens, finishAttempts = finishAttempts, blobsRead = 0, blobBytesRead = 0, phase = phase.phase,
            )

        /** [gitProcesses] overrides the git baseline when the phase began before [workspace] existed (an open). */
        fun of(workspace: Workspace, gitProcesses: Long = workspace.git.processesStarted, objectsWritten: Long = workspace.git.objectsWritten): PhaseMark =
            PhaseMark(workspace, gitProcesses, workspace.filesRead, workspace.bytesRead, objectsWritten, workspace.blobsRead, workspace.blobBytesRead)
    }
}
