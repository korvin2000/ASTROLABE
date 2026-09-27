package io.astrolabe.tool.run

import io.astrolabe.os.Os
import io.astrolabe.os.Proc
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** A process observed to its terminal state: its output, and whether the observation itself was lost. */
public class Observed(public val proc: Proc, public val output: ByteArray, public val lost: Boolean)

/** The one poll loop of the harness: `run`, `verify` and the checkers observe processes the same way. */
public object Executions {
    internal const val MAX_CAPTURE_BYTES: Int = 8 * 1024 * 1024

    internal suspend fun observeCancellable(os: Os, proc: Proc, sliceSeconds: Long, timeoutSeconds: Long): Observed = try {
        runInterruptible(Dispatchers.IO) { observeLoop(os, proc, sliceSeconds, timeoutSeconds) }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { os.terminate(proc) }.exceptionOrNull()?.let(cancelled::addSuppressed)
        }
        throw cancelled
    }

    /**
     * Polls [proc] until it is terminal, in slices of at most [sliceSeconds] (never longer than [timeoutSeconds]).
     * An `IOException` while observing marks the result [Observed.lost]: the process may still be running, and the
     * caller must reconcile rather than relaunch (§13.1).
     */
    @JvmStatic
    public fun observe(os: Os, proc: Proc, sliceSeconds: Long, timeoutSeconds: Long): Observed = try {
        observeLoop(os, proc, sliceSeconds, timeoutSeconds)
    } catch (interrupted: InterruptedException) {
        Thread.interrupted()
        runCatching { os.terminate(proc) }.exceptionOrNull()?.let(interrupted::addSuppressed)
        throw interrupted
    }

    private fun observeLoop(os: Os, proc: Proc, sliceSeconds: Long, timeoutSeconds: Long): Observed {
        require(sliceSeconds > 0 && timeoutSeconds > 0) { "slice and timeout must be positive" }
        var current = proc
        var cursor = 0L
        val output = java.io.ByteArrayOutputStream()
        var truncated = false
        return try {
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("process observation interrupted")
                val poll = os.poll(current, cursor, if (current.status.isTerminal) 0 else minOf(sliceSeconds, timeoutSeconds))
                val retained = minOf(poll.newBytes.size, MAX_CAPTURE_BYTES - output.size())
                output.write(poll.newBytes, 0, retained)
                truncated = truncated || retained < poll.newBytes.size
                if (poll.newBytes.isNotEmpty() && poll.nextCursorBytes <= cursor) return Observed(current, output.toByteArray(), lost = true)
                cursor = poll.nextCursorBytes
                current = current.copy(status = poll.status)
                if (current.status.isTerminal && poll.newBytes.isEmpty()) break
            }
            Observed(current, output.toByteArray(), lost = truncated)
        } catch (failure: IOException) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("process observation interrupted").also { it.initCause(failure) }
            Observed(current, output.toByteArray(), lost = true)
        }
    }
}
