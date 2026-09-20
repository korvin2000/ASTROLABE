package io.astrolabe.tool.run

import io.astrolabe.os.Os
import io.astrolabe.os.Proc
import java.io.IOException

/** A process observed to its terminal state: its output, and whether the observation itself was lost. */
public class Observed(public val proc: Proc, public val output: ByteArray, public val lost: Boolean)

/** The one poll loop of the harness: `run`, `verify` and the checkers observe processes the same way. */
public object Executions {
    /**
     * Polls [proc] until it is terminal, in slices of at most [sliceSeconds] (never longer than [timeoutSeconds]).
     * An `IOException` while observing marks the result [Observed.lost]: the process may still be running, and the
     * caller must reconcile rather than relaunch (§13.1).
     */
    @JvmStatic
    public fun observe(os: Os, proc: Proc, sliceSeconds: Long, timeoutSeconds: Long): Observed {
        require(sliceSeconds > 0 && timeoutSeconds > 0) { "slice and timeout must be positive" }
        var current = proc
        var cursor = 0L
        val output = java.io.ByteArrayOutputStream()
        return try {
            while (!current.status.isTerminal) {
                val poll = os.poll(current, cursor, minOf(sliceSeconds, timeoutSeconds))
                output.write(poll.newBytes)
                cursor = poll.nextCursorBytes
                current = current.copy(status = poll.status)
            }
            output.write(os.poll(current, cursor, 0).newBytes)
            Observed(current, output.toByteArray(), lost = false)
        } catch (failure: IOException) {
            Observed(current, output.toByteArray(), lost = true)
        }
    }
}
