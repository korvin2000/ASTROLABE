package io.astrolabe.os

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import java.util.concurrent.atomic.AtomicLong

/**
 * T-13 (plan §7.2): the counts of one phase call. Git processes, written objects and tree and blob reads are added to
 * the tally installed for the calling thread — and to every tally it is nested in — besides their instances' monotonic
 * totals, so a phase counts its own work and never what another caller does meanwhile on the same shared instances (a
 * host's `git status` during an open). A synchronous phase runs [within] its tally; a suspending one carries it as the
 * coroutine context [element], which follows the coroutine onto whatever thread resumes it.
 */
internal class PhaseTally private constructor(private val parent: PhaseTally?) {
    enum class Count { GitProcesses, ObjectsWritten, FilesRead, BytesRead, BlobsRead, BlobBytesRead }

    private val counts = Array(Count.entries.size) { AtomicLong() }

    operator fun get(count: Count): Long = counts[count.ordinal].get()

    /** The context element that installs this tally for a coroutine and its children. */
    val element: ThreadContextElement<PhaseTally?> get() = CURRENT.asContextElement(this)

    /** Runs [block] with this tally installed on the calling thread; the previous one is restored after it. */
    fun <T> within(block: () -> T): T {
        val before = CURRENT.get()
        CURRENT.set(this)
        try {
            return block()
        } finally {
            CURRENT.set(before)
        }
    }

    companion object {
        private val CURRENT = ThreadLocal<PhaseTally?>()

        /** A new tally nested in the one installed for the calling thread or coroutine, if any. */
        fun open(): PhaseTally = PhaseTally(CURRENT.get())

        /** Adds [n] to [count] of the installed tally and the tallies it is nested in; nothing when none is installed. */
        fun add(count: Count, n: Long = 1) {
            var tally = CURRENT.get()
            while (tally != null) {
                tally.counts[count.ordinal].addAndGet(n)
                tally = tally.parent
            }
        }
    }
}
