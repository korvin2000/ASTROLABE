package io.astrolabe.os

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The [Os] implementation for the machine this harness runs on.
 *
 * Each launch gets a daemon supervisor thread that outlives the requesting coroutine: it enforces
 * the execution deadline, terminates the tree and writes the terminal record to the sidecar. The
 * requesting coroutine may be cancelled at any moment without losing that record (D-26).
 *
 * A launch's descendants never outlive its root process: when the root exits, the supervisor
 * terminates the container (job object / process group) before releasing it, so both platforms
 * behave identically. A survivable, detached mode is a separate later capability (D-43).
 *
 * Requires `--enable-native-access=ALL-UNNAMED`; see [Os].
 */
public class LocalOs @JvmOverloads public constructor(
    private val clock: Clock = Clock.systemUTC(),
    override val ownerToken: OwnerToken = OwnerToken.random(),
) : Os {

    private val owner: ProcessOwner = ProcessOwner.forThisPlatform()
    private val supervised = ConcurrentHashMap<IdentityKey, Supervision>()

    override fun spawn(spec: SpawnSpec): Proc {
        val workingDirectory = spec.workingDirectory.toAbsolutePath().normalize()
        if (!Files.isDirectory(workingDirectory)) throw NoSuchFileException("$workingDirectory is not a directory")
        val logPath = spec.logPath.toAbsolutePath().normalize()
        logPath.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(logPath)) Files.createFile(logPath)

        val native = owner.start(
            OwnedStart(spec.command, workingDirectory, resolveEnvironment(spec.environment), logPath),
        )
        var identityKey: IdentityKey? = null
        try {
            val key = IdentityKey(
                native.pid,
                native.startEpochMillis ?: observedIdentity(native.pid)?.startEpochMillis,
            )
            identityKey = key
            val proc = Proc(
                pid = native.pid,
                startedAtEpochMillis = clock.millis(),
                identityKey = key,
                ownerToken = ownerToken,
                logPath = logPath.toString(),
                logCursorBytes = 0L,
                status = ProcStatus.Running,
                command = spec.command,
                workingDirectory = workingDirectory.toString(),
                deadlineSeconds = spec.deadlineSeconds,
            )
            val supervision = Supervision(native, proc)
            supervised[key] = supervision
            // Written before the supervisor starts, so the terminal record can never be overwritten
            // by this initial one.
            writeSidecar(proc)
            Thread.ofPlatform().daemon().name("astrolabe-proc-${native.pid}").start { supervise(supervision, key) }
            return proc
        } catch (failure: Throwable) {
            // The child is already running; leaving it unsupervised would be exactly the orphan
            // this adapter exists to prevent.
            identityKey?.let { supervised.remove(it) }
            runCatching { native.terminateTree() }
            runCatching { native.close() }
            throw failure
        }
    }

    override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
        require(sinceCursorBytes >= 0) { "cursor must not be negative, was $sinceCursorBytes" }
        require(observationTimeoutSeconds >= 0) { "observation timeout must not be negative" }
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(observationTimeoutSeconds)
        while (true) {
            // Status first, then bytes: a terminal status observed before the read guarantees the
            // read sees everything the process ever wrote.
            val current = reattach(proc)
            val bytes = readLog(current.log, sinceCursorBytes)
            if (bytes.isNotEmpty() || current.status.isTerminal) {
                return Poll(bytes, sinceCursorBytes + bytes.size, current.status, timedOut = false)
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                // §13.1: the observation timed out, the execution did not. Not a reason to relaunch.
                return Poll(EMPTY_BYTES, sinceCursorBytes, current.status, timedOut = true)
            }
            Thread.sleep(minOf(POLL_INTERVAL_MILLIS, remainingNanos / 1_000_000L + 1))
        }
    }

    override fun terminate(proc: Proc): Proc {
        val supervision = supervisionOf(proc)
        if (supervision != null) {
            claim(supervision, TerminationCause.CANCELLATION)
            supervision.terminateTree()
            return awaitSettled(supervision, TERMINATE_CONFIRM_MILLIS)
        }
        val observed = observedIdentity(proc.pid)
        if (observed != null && proc.identityKey.matches(observed)) owner.terminateUnowned(proc.pid)
        return reattach(proc)
    }

    override fun advanceCursor(proc: Proc, cursorBytes: Long): Proc {
        require(cursorBytes >= 0) { "cursor must not be negative, was $cursorBytes" }
        val supervision = supervisionOf(proc)
        if (supervision != null) {
            return supervision.lock.withLock {
                supervision.proc = supervision.proc.copy(logCursorBytes = cursorBytes)
                writeSidecar(supervision.proc)
                supervision.proc
            }
        }
        // Only the cursor moves: the status field belongs to whichever supervisor wrote it (§2.3).
        val current = (readSidecar(proc.sidecarPath) ?: proc).copy(logCursorBytes = cursorBytes)
        writeSidecar(current)
        return current
    }

    override fun reattach(proc: Proc): Proc {
        if (proc.ownerToken == ownerToken) {
            supervisionOf(proc)?.let { return it.snapshot() }
            val persisted = runCatching { readSidecar(proc.sidecarPath) }.getOrNull()
            if (persisted != null && persisted.status.isTerminal) return persisted
            if (proc.status.isTerminal) return proc
            // Our token, no live supervision, no terminal record: the supervisor that would have
            // written one is gone, so the outcome is unknowable.
            return proc.copy(status = ProcStatus.Lost)
        }
        // Another harness launched it: its terminal record is the only authority on the outcome.
        if (proc.status.isTerminal) return proc
        val observed = observedIdentity(proc.pid)
        return if (observed != null && proc.identityKey.matches(observed)) {
            // Alive but unowned: terminable by pid/pgid, exit code no longer obtainable.
            proc.copy(status = ProcStatus.Running)
        } else {
            // Dead, recycled or unidentifiable. Never relaunched blindly (§13.1, IX-20).
            proc.copy(status = ProcStatus.Lost)
        }
    }

    override fun resolve(sidecarPath: Path): Proc? = readSidecar(sidecarPath)?.let { reattach(it) }

    override fun replaceFileAtomically(path: Path, bytes: ByteArray) {
        val target = path.toAbsolutePath().normalize()
        val directory = target.parent ?: throw OsFailure("replaceFileAtomically", 0, "$target has no parent directory")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, target.fileName.toString(), TEMPORARY_SUFFIX)
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: AtomicMoveNotSupportedException) {
                // D-44: an unsupported rename guarantee fails closed instead of degrading silently.
                throw OsFailure("replaceFileAtomically", 0, "no atomic replace for $target").apply {
                    initCause(unsupported)
                }
            }
            forceDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun realPath(path: Path): Path? = try {
        path.toRealPath()
    } catch (missing: NoSuchFileException) {
        null
    }

    override fun close() {
        supervised.values.toList().forEach { supervision ->
            claim(supervision, TerminationCause.CANCELLATION)
            supervision.terminateTree()
            awaitSettled(supervision, CLOSE_SETTLE_MILLIS)
        }
    }

    // --- supervision ------------------------------------------------------------------------

    private fun supervise(supervision: Supervision, identityKey: IdentityKey) {
        val deadlineNanos = supervision.proc.deadlineSeconds
            ?.let { System.nanoTime() + TimeUnit.SECONDS.toNanos(it) }
        var exitCode: Int? = null
        try {
            while (true) {
                val slice = when (deadlineNanos) {
                    null -> SUPERVISOR_SLICE_MILLIS
                    else -> minOf(
                        SUPERVISOR_SLICE_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1L),
                    )
                }
                if (supervision.native.awaitExit(slice)) break
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos &&
                    claim(supervision, TerminationCause.DEADLINE)
                ) {
                    // §13.1: a deadline kills the tree; it never replays the command.
                    supervision.terminateTree()
                }
            }
            exitCode = supervision.native.exitCode()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            settle(supervision, exitCode)
            supervised.remove(identityKey)
            supervision.release()
        }
    }

    /** Records the single terminal status. The first claimed cause wins; both are decided here. */
    private fun settle(supervision: Supervision, exitCode: Int?) {
        val finishedAt = clock.millis()
        supervision.lock.withLock {
            if (supervision.exited) return
            supervision.exited = true
            val status = when {
                exitCode == null -> ProcStatus.Lost
                supervision.cause == TerminationCause.DEADLINE -> ProcStatus.DeadlineExceeded
                supervision.cause == TerminationCause.CANCELLATION -> ProcStatus.Cancelled
                else -> ProcStatus.Exited(exitCode)
            }
            supervision.proc = supervision.proc.copy(status = status, finishedAtEpochMillis = finishedAt)
            runCatching { writeSidecar(supervision.proc) }
            supervision.settled.signalAll()
        }
    }

    private fun claim(supervision: Supervision, cause: TerminationCause): Boolean = supervision.lock.withLock {
        if (supervision.exited || supervision.cause != null) {
            false
        } else {
            supervision.cause = cause
            true
        }
    }

    private fun awaitSettled(supervision: Supervision, timeoutMillis: Long): Proc = supervision.lock.withLock {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!supervision.exited && remainingNanos > 0) {
            remainingNanos = supervision.settled.awaitNanos(remainingNanos)
        }
        supervision.proc
    }

    // --- records and files ------------------------------------------------------------------

    private fun writeSidecar(proc: Proc) {
        replaceFileAtomically(proc.sidecarPath, JSON.encodeToString(Proc.serializer(), proc).toByteArray(Charsets.UTF_8))
    }

    private fun readSidecar(path: Path): Proc? =
        if (!Files.exists(path)) null else JSON.decodeFromString(Proc.serializer(), Files.readString(path))

    private fun readLog(log: Path, cursor: Long): ByteArray {
        if (!Files.exists(log)) return EMPTY_BYTES
        return FileChannel.open(log, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size <= cursor) {
                EMPTY_BYTES
            } else {
                val buffer = ByteBuffer.allocate(minOf(size - cursor, MAX_POLL_BYTES).toInt())
                channel.position(cursor)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // read until the requested window is filled or the file ends
                }
                buffer.flip()
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
    }

    private fun forceDirectory(directory: Path) {
        // Windows cannot open a directory as a channel; there the rename itself is the durability
        // point. Elsewhere this is best effort, as some filesystems refuse a directory fsync.
        if (isWindows) return
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun resolveEnvironment(policy: EnvPolicy): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        if (policy.includeEssentials) {
            owner.essentialEnvironmentNames.forEach { name -> inherited(name)?.let { resolved[name] = it } }
        }
        policy.inheritedNames.forEach { name -> inherited(name)?.let { resolved[name] = it } }
        resolved.putAll(policy.extra)
        return resolved
    }

    /**
     * `System.getenv(String)` matches the platform's own rule — case-insensitive on Windows, exact
     * on POSIX. The `Map` from `System.getenv()` does not: on Windows it is a plain case-sensitive
     * map, so a parent whose block spells `SYSTEMROOT` would silently drop `SystemRoot` here and
     * hand the child an environment too thin to start .NET-based programs.
     */
    private fun inherited(name: String): String? = System.getenv(name)

    /** Live supervision, but only for records this instance launched (`§13.4` owner token). */
    private fun supervisionOf(proc: Proc): Supervision? =
        if (proc.ownerToken == ownerToken) supervised[proc.identityKey] else null

    /** Liveness and identity only: `ProcessHandle` is never the owner of a launched process. */
    private fun observedIdentity(pid: Long): IdentityKey? {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!handle.isAlive) return null
        val start = handle.info().startInstant().orElse(null) ?: return null
        return IdentityKey(pid, start.toEpochMilli())
    }

    private class Supervision(val native: OwnedProcess, initial: Proc) {
        val lock: ReentrantLock = ReentrantLock()
        val settled: Condition = lock.newCondition()

        var proc: Proc = initial
        var cause: TerminationCause? = null
        var exited: Boolean = false
        private var released: Boolean = false

        fun snapshot(): Proc = lock.withLock { proc }

        fun terminateTree(): Unit = lock.withLock { if (!released) native.terminateTree() }

        /** Only the supervisor thread releases, so no other thread can use a closed handle. */
        fun release() {
            lock.withLock {
                if (!released) {
                    released = true
                    runCatching { native.terminateTree() } // descendants never outlive the root process
                    runCatching { native.close() }
                }
            }
        }
    }

    private enum class TerminationCause { DEADLINE, CANCELLATION }

    private companion object {
        val JSON = Json { encodeDefaults = true }
        val EMPTY_BYTES = ByteArray(0)
        val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        const val TEMPORARY_SUFFIX = ".astrolabe-tmp"
        const val POLL_INTERVAL_MILLIS = 20L
        const val SUPERVISOR_SLICE_MILLIS = 25L
        const val TERMINATE_CONFIRM_MILLIS = 10_000L
        const val CLOSE_SETTLE_MILLIS = 10_000L
        const val MAX_POLL_BYTES = 1L shl 20
    }
}
