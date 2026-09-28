package io.astrolabe.os

import java.nio.file.Path

/**
 * Launches within a Windows job or beneath an isolated Linux subreaper. Linux orphan adoption
 * retains detached descendants; only kernel-confirmed cleanup permits a terminal outcome.
 *
 * Internal: the ownership mechanism is an implementation detail of [LocalOs]. `ProcessHandle` is
 * used only for liveness and identity checks, never as the owner.
 */
internal interface ProcessOwner {

    /** Environment names the platform needs for a child to run at all (`§2.3` env allowlist). */
    val essentialEnvironmentNames: Set<String>

    /** Launches [start]; the returned process is already inside its container. */
    fun start(start: OwnedStart): OwnedProcess

    /**
     * Best-effort termination of a process this harness does not own — a survivor of a previous
     * harness. Returns true when the request was accepted. The exit code stays unobservable, so
     * the caller resolves the process as [ProcStatus.Lost] once it disappears.
     */
    fun terminateUnowned(pid: Long): Boolean

    companion object {
        /** The owner for the running platform. Only Windows and POSIX are supported (D-12). */
        fun forThisPlatform(): ProcessOwner =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                WindowsOwner()
            } else {
                PosixOwner()
            }
    }
}

/** Fully resolved launch request: no inherited environment, absolute paths only. */
internal class OwnedStart(
    val command: Command,
    val workingDirectory: Path,
    val environment: Map<String, String>,
    val logPath: Path,
)

/** A live child owned by its container. Not thread-safe against concurrent [close]. */
internal interface OwnedProcess : AutoCloseable {

    val pid: Long

    /**
     * Process creation time in epoch milliseconds when the platform reports it exactly, else null
     * and the caller falls back to `ProcessHandle`.
     */
    val startEpochMillis: Long?

    /** Waits at most [timeoutMillis]; true once the process has exited. */
    fun awaitExit(timeoutMillis: Long): Boolean

    /** Exit code; only meaningful after [awaitExit] returned true. */
    fun exitCode(): Int

    /** Requests termination of every owned descendant, including detached Linux children. */
    fun terminateTree()

    /** Confirms the owned container has no remaining writers; false when the deadline expires. */
    fun awaitTreeExit(timeoutMillis: Long): Boolean

    /** Releases native handles. On Windows this also kills any survivor (kill-on-close). */
    override fun close()
}
