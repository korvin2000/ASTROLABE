package io.astrolabe.os

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.math.abs

/**
 * Operating-system adapter: owned process launch, durable log capture, atomic file replacement and
 * real-path resolution.
 *
 * A launched process is owned from birth by a kernel container (Windows job object, POSIX session)
 * so that its whole tree is terminable and no descendant can break away (`§13.1`, D-43). Every
 * launch is described by a [Proc] record that is persisted next to its log file, independently of
 * the coroutine that requested it, so a resumed harness reads status and log cursor from the
 * filesystem alone.
 *
 * Five outcomes are distinguished and never conflated (D-43):
 *  * an **observation timeout** — [poll] returned with [Poll.timedOut] set while the process is
 *    still [ProcStatus.Running]; this is a normal result, not a failure, and never justifies a
 *    second launch (`§13.1`, IX-20);
 *  * an **execution deadline** — [SpawnSpec.deadlineSeconds] elapsed, the tree was terminated,
 *    [ProcStatus.DeadlineExceeded]; timeouts kill the tree and never replay;
 *  * a **cancellation request** — [terminate] was called, [ProcStatus.Cancelled];
 *  * a **confirmed termination** — [ProcStatus.Exited] with the real exit code;
 *  * **lost/unknown** — [ProcStatus.Lost]: the handle is unrecoverable (typically after harness
 *    death). A lost process is never relaunched blindly; this adapter has no relaunch operation.
 *
 * Processes do not survive harness death by default. On Windows the job object is created with
 * `JOB_OBJECT_LIMIT_KILL_ON_CLOSE`, so the tree dies with the JVM that owns it. On POSIX the
 * session survives the owner; such a process resolves to [ProcStatus.Running] but *unowned* — its
 * exit code can no longer be observed, so it becomes [ProcStatus.Lost] once it disappears.
 *
 * **Native access.** The bundled [LocalOs] binds process-control APIs through `java.lang.foreign`.
 * These are *restricted* methods: JDK 26 runs them under `--illegal-native-access=warn` (a warning
 * on first use) and a later release will block them. Consumers of this library must therefore
 * launch the JVM with `--enable-native-access=ALL-UNNAMED`; `:core`'s test task already does.
 *
 * Implementations are thread-safe. [close] terminates every process this instance still owns.
 */
public interface Os : AutoCloseable {

    /** Random token identifying this harness process; stamped into every [Proc] it launches. */
    public val ownerToken: OwnerToken

    /**
     * Launches [spec] and returns immediately with a [ProcStatus.Running] record whose sidecar has
     * already been written. A supervisor thread, independent of the caller, enforces the deadline
     * and writes the terminal record.
     */
    @Throws(IOException::class)
    public fun spawn(spec: SpawnSpec): Proc

    /**
     * Waits up to [observationTimeoutSeconds] for new log bytes after [sinceCursorBytes] or for the
     * process to reach a terminal status, whichever comes first.
     *
     * Returning with [Poll.timedOut] set and [Poll.status] still [ProcStatus.Running] is a normal
     * outcome: the observation timed out, the execution did not (`§13.1`, FX-22).
     */
    @Throws(IOException::class)
    public fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll

    /**
     * Requests termination of the whole process tree and returns the settled record. Cancellation
     * and an expiring deadline race to a single terminal status: the first claim wins (D-26).
     */
    @Throws(IOException::class)
    public fun terminate(proc: Proc): Proc

    /** Persists a new log cursor for [proc] so a resumed harness continues where this one stopped. */
    @Throws(IOException::class)
    public fun advanceCursor(proc: Proc, cursorBytes: Long): Proc

    /**
     * Re-resolves [proc] against reality: live supervision for this owner, otherwise the terminal
     * record, otherwise liveness plus [IdentityKey] agreement, otherwise [ProcStatus.Lost].
     */
    public fun reattach(proc: Proc): Proc

    /** Reads the sidecar at [sidecarPath] and [reattach]es it; `null` when the file is absent. */
    @Throws(IOException::class)
    public fun resolve(sidecarPath: Path): Proc?

    /**
     * Replaces [path] with [bytes] by writing a sibling temporary file, forcing it to disk and
     * renaming it atomically.
     *
     * The rename is atomic against readers, but it is *not* a compare-and-replace: a concurrent
     * external writer's version is silently overwritten. Stronger guarantees need host-coordinated
     * publication (D-47).
     */
    @Throws(IOException::class)
    public fun replaceFileAtomically(path: Path, bytes: ByteArray)

    /** Canonical real path of [path], or `null` when it does not exist. */
    @Throws(IOException::class)
    public fun realPath(path: Path): Path?

    /** Terminates every process still owned by this instance and releases its native handles. */
    override fun close()
}

/** What to launch: an argument vector, or one shell command line reported as a wrapper. */
@Serializable
public sealed interface Command {

    /** Direct launch of [argv]; `argv[0]` is resolved through `PATH`. No shell is involved. */
    @Serializable
    @SerialName("argv")
    public data class Argv(public val argv: List<String>) : Command {
        init {
            require(argv.isNotEmpty()) { "argv must not be empty" }
            require(argv.first().isNotBlank()) { "argv[0] must not be blank" }
        }
    }

    /** One shell invocation: `cmd.exe /d /s /c "<commandLine>"` on Windows, `sh -c` on POSIX. */
    @Serializable
    @SerialName("shell")
    public data class Shell(public val commandLine: String) : Command {
        init {
            require(commandLine.isNotBlank()) { "shell command line must not be blank" }
        }
    }
}

/**
 * Environment allowlist. The child never inherits the parent environment implicitly: it receives
 * the platform essentials (when [includeEssentials]) plus the inherited variables named in
 * [inheritedNames] plus [extra], which wins on conflict.
 */
public data class EnvPolicy(
    public val inheritedNames: Set<String> = emptySet(),
    public val extra: Map<String, String> = emptyMap(),
    public val includeEssentials: Boolean = true,
)

/** A launch request. [logPath] is a store-owned file; stdout and stderr are appended to it. */
public data class SpawnSpec(
    public val command: Command,
    public val workingDirectory: Path,
    public val logPath: Path,
    public val environment: EnvPolicy = EnvPolicy(),
    public val deadlineSeconds: Long? = null,
) {
    init {
        require(deadlineSeconds == null || deadlineSeconds > 0) {
            "deadlineSeconds must be positive, was $deadlineSeconds"
        }
    }
}

/** Random per-harness token; a [Proc] carrying a different token belongs to another harness. */
@Serializable
public data class OwnerToken(public val value: String) {
    init {
        require(value.isNotBlank()) { "owner token must not be blank" }
    }

    public companion object {
        private val RANDOM = SecureRandom()

        /** A fresh 128-bit token. */
        public fun random(): OwnerToken {
            val bytes = ByteArray(16)
            RANDOM.nextBytes(bytes)
            return OwnerToken(HexFormat.of().formatHex(bytes))
        }
    }
}

/**
 * Process identity beyond the PID (`§13.1`): the PID together with the process start time as the
 * JDK reports it (`ProcessHandle.info().startInstant()`). A recycled PID carries a later start
 * time and therefore fails [matches].
 */
@Serializable
public data class IdentityKey(
    public val pid: Long,
    public val startEpochMillis: Long? = null,
) {
    /**
     * True when [observed] is the same process. An unknown start time on either side never
     * matches: identity that cannot be confirmed is resolved as lost, never as running.
     *
     * Start times are compared with [START_TOLERANCE_MILLIS] slack because on Linux the JDK
     * derives them from `/proc` boot time, which can differ by a rounding step between JVMs; on
     * Windows both sides read the same creation `FILETIME` and agree exactly.
     */
    public fun matches(observed: IdentityKey): Boolean {
        val mine = startEpochMillis ?: return false
        val theirs = observed.startEpochMillis ?: return false
        return pid == observed.pid && abs(mine - theirs) <= START_TOLERANCE_MILLIS
    }

    public companion object {
        /** Slack absorbing cross-JVM boot-time rounding on Linux; see [matches]. */
        public const val START_TOLERANCE_MILLIS: Long = 1_000L
    }
}

/** The five distinguished outcomes of an owned launch (D-43). */
@Serializable
public sealed interface ProcStatus {

    /** True for every status but [Running]; a terminal status is never revised. */
    public val isTerminal: Boolean
        get() = this !is Running

    /** Alive, or believed alive by the last observation. */
    @Serializable
    @SerialName("running")
    public data object Running : ProcStatus

    /** Confirmed termination with a real exit code. */
    @Serializable
    @SerialName("exited")
    public data class Exited(public val exitCode: Int) : ProcStatus

    /** The execution deadline elapsed and the tree was terminated. Never replayed. */
    @Serializable
    @SerialName("deadline_exceeded")
    public data object DeadlineExceeded : ProcStatus

    /** Termination was requested through [Os.terminate] and confirmed. */
    @Serializable
    @SerialName("cancelled")
    public data object Cancelled : ProcStatus

    /** The handle is unrecoverable — outcome unknown. Never relaunched blindly (`§13.1`). */
    @Serializable
    @SerialName("lost")
    public data object Lost : ProcStatus
}

/**
 * Durable record of one owned launch, persisted as `<log>.proc.json` beside its log file by atomic
 * replacement, so it outlives the requesting coroutine and the harness itself.
 */
@Serializable
public data class Proc(
    public val pid: Long,
    public val startedAtEpochMillis: Long,
    public val identityKey: IdentityKey,
    public val ownerToken: OwnerToken,
    public val logPath: String,
    public val logCursorBytes: Long,
    public val status: ProcStatus,
    public val command: Command,
    public val workingDirectory: String,
    public val deadlineSeconds: Long? = null,
    public val finishedAtEpochMillis: Long? = null,
) {
    /** The store-owned log file receiving stdout and stderr. */
    public val log: Path
        get() = Path.of(logPath)

    /** Where this record is persisted. */
    public val sidecarPath: Path
        get() = sidecarPathFor(log)

    public companion object {
        /** `<log>.proc.json`, the sidecar holding the record for [logPath]. */
        public fun sidecarPathFor(logPath: Path): Path =
            logPath.resolveSibling(logPath.fileName.toString() + ".proc.json")
    }
}

/**
 * Result of one [Os.poll]: the bytes produced since the requested cursor, and where to resume.
 *
 * `equals`/`hashCode`/`toString` are written out because [newBytes] is an array, for which the
 * generated members would compare by identity and print a reference.
 */
public data class Poll(
    public val newBytes: ByteArray,
    public val nextCursorBytes: Long,
    public val status: ProcStatus,
    public val timedOut: Boolean,
) {
    /** [newBytes] decoded as UTF-8, for callers rendering the log. */
    public fun text(): String = newBytes.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is Poll &&
                    newBytes.contentEquals(other.newBytes) &&
                    nextCursorBytes == other.nextCursorBytes &&
                    status == other.status &&
                    timedOut == other.timedOut
                )

    override fun hashCode(): Int {
        var result = newBytes.contentHashCode()
        result = 31 * result + nextCursorBytes.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + timedOut.hashCode()
        return result
    }

    override fun toString(): String =
        "Poll(newBytes=${newBytes.size}B, nextCursorBytes=$nextCursorBytes, status=$status, timedOut=$timedOut)"
}

/** A native OS call refused the request. [errorCode] is `GetLastError` on Windows, `errno`/return on POSIX. */
public class OsFailure(
    public val call: String,
    public val errorCode: Int,
    message: String,
) : IOException(message)
