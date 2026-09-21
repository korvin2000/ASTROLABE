package io.astrolabe.os

import java.util.concurrent.TimeUnit

/**
 * Bounded waits for OS-adapter tests. They observe through the public [Os] contract only, so they
 * exercise the same resolution path a resumed harness uses.
 */

/** Polls until [proc] reaches a terminal status; throws when it has not within [timeoutSeconds]. */
public fun Os.awaitTerminal(proc: Proc, timeoutSeconds: Long = 30): Proc {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    var last = reattach(proc)
    while (System.nanoTime() < deadline) {
        if (last.status.isTerminal) return last
        Thread.sleep(20L)
        last = reattach(proc)
    }
    throw AssertionError("pid ${proc.pid} still ${last.status} after ${timeoutSeconds}s")
}

/**
 * Accumulates log output from cursor 0 until [pattern] matches; throws when it has not within
 * [timeoutSeconds]. Returns everything read so far.
 */
public fun Os.awaitLogMatch(proc: Proc, pattern: Regex, timeoutSeconds: Long = 30): String {
    val started = System.nanoTime()
    val deadline = started + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    val seen = StringBuilder()
    var cursor = 0L
    var status = proc.status
    while (System.nanoTime() < deadline) {
        val poll = poll(proc, cursor, 1)
        seen.append(poll.text())
        cursor = poll.nextCursorBytes
        status = poll.status
        if (pattern.containsMatchIn(seen)) return seen.toString()
        if (poll.status.isTerminal && !poll.timedOut && poll.newBytes.isEmpty()) break
    }
    // Naming the status separates "the child was still starting" from "the child was killed first".
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    throw AssertionError(
        "pattern $pattern never appeared in the log of pid ${proc.pid} " +
            "after ${elapsed}ms, status $status; saw: $seen",
    )
}
