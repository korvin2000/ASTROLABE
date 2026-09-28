package io.astrolabe.tool.run

import io.astrolabe.os.Command
import io.astrolabe.os.IdentityKey
import io.astrolabe.os.Os
import io.astrolabe.os.OwnerToken
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionsTest {
    @Test
    fun `observation drains every cursor chunk after a running process becomes terminal`() {
        val os = ChunkedLogOs()

        val result = Executions.observe(os, os.proc(ProcStatus.Running), sliceSeconds = 30, timeoutSeconds = 60)

        assertContentEquals(os.output, result.output)
        assertEquals(ProcStatus.Exited(1), result.proc.status)
        assertFalse(result.lost)
    }

    @Test
    fun `observation drains every cursor chunk when the supplied process is already terminal`() {
        val os = ChunkedLogOs()

        val result = Executions.observe(os, os.proc(ProcStatus.Exited(1)), sliceSeconds = 30, timeoutSeconds = 60)

        assertContentEquals(os.output, result.output)
        assertEquals(ProcStatus.Exited(1), result.proc.status)
        assertFalse(result.lost)
    }

    @Test
    fun `capture cap discards excess bytes while observing through terminal EOF`() {
        val os = LargeLogOs(chunks = 10)

        val result = Executions.observe(os, os.proc, sliceSeconds = 30, timeoutSeconds = 60)

        assertEquals(8 * 1024 * 1024, result.output.size)
        assertTrue(result.truncated, "discarded output must not be reported as a complete capture")
        assertFalse(result.lost, "a capped capture still observed the process to its terminal state")
        assertEquals(ProcStatus.Exited(1), result.proc.status, "capture limit must not stop process observation")
        assertEquals(11, os.polls, "all remaining chunks and terminal EOF must be drained")
        assertEquals(0.toByte(), result.output.first())
        assertEquals(7.toByte(), result.output.last())
    }

    @Test
    fun `capture exactly at the cap remains complete`() {
        val os = LargeLogOs(chunks = 8)

        val result = Executions.observe(os, os.proc, sliceSeconds = 30, timeoutSeconds = 60)

        assertEquals(8 * 1024 * 1024, result.output.size)
        assertFalse(result.lost)
        assertFalse(result.truncated)
        assertEquals(ProcStatus.Exited(1), result.proc.status)
        assertEquals(9, os.polls)
    }

    @Test
    fun `cancelling observation interrupts a waiting poll and settles the owned process`() = runBlocking {
        val os = WaitingLogOs()
        val observation = launch(Dispatchers.Default) {
            Executions.observeCancellable(os, os.proc, sliceSeconds = 30, timeoutSeconds = 60)
        }
        try {
            withTimeout(5_000) { os.pollStarted.await() }
            withTimeout(5_000) { observation.cancelAndJoin() }

            assertTrue(observation.isCancelled)
            assertTrue(os.pollInterrupted, "cancellation must interrupt the blocking observation")
            assertEquals(1, os.terminations.get())
            assertEquals(ProcStatus.Cancelled, os.settled?.status, "owned process must settle before cancellation completes")
        } finally {
            os.releasePoll.countDown()
            observation.cancelAndJoin()
        }
    }

    @Test
    fun `cancelling continuously available output stops observation without waiting for idle polling`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stop = AtomicBoolean()
        val terminations = AtomicInteger()
        val fixture = ChunkedLogOs()
        val os = object : Os by fixture {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                started.complete(Unit)
                return if (stop.get()) Poll(byteArrayOf(), sinceCursorBytes, ProcStatus.Exited(0), false)
                else Poll(byteArrayOf(1), sinceCursorBytes + 1, ProcStatus.Running, false)
            }

            override fun terminate(proc: Proc): Proc {
                terminations.incrementAndGet()
                return proc.copy(status = ProcStatus.Cancelled)
            }
        }
        val observation = launch(Dispatchers.Default) {
            Executions.observeCancellable(os, fixture.proc(ProcStatus.Running), sliceSeconds = 30, timeoutSeconds = 60)
        }
        try {
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) { observation.cancelAndJoin() }
            assertEquals(1, terminations.get(), "continuous output must not postpone process termination")
        } finally {
            stop.set(true)
            observation.cancelAndJoin()
        }
    }

    @Test
    fun `interrupted log IO terminates the process instead of becoming an ordinary lost observation`() {
        val fixture = ChunkedLogOs()
        val terminations = AtomicInteger()
        val os = object : Os by fixture {
            override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
                Thread.currentThread().interrupt()
                throw IOException("log channel closed by interruption")
            }

            override fun terminate(proc: Proc): Proc {
                terminations.incrementAndGet()
                return proc.copy(status = ProcStatus.Cancelled)
            }
        }
        try {
            assertFailsWith<InterruptedException> {
                Executions.observe(os, fixture.proc(ProcStatus.Running), sliceSeconds = 30, timeoutSeconds = 60)
            }
            assertEquals(1, terminations.get())
        } finally {
            Thread.interrupted()
        }
    }
}

private class LargeLogOs(private val chunks: Int) : Os by ChunkedLogOs() {
    val proc = ChunkedLogOs().proc(ProcStatus.Running)
    var polls = 0
        private set

    override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
        polls++
        val chunk = (sinceCursorBytes / CHUNK_SIZE).toInt()
        val bytes = if (chunk < chunks) ByteArray(CHUNK_SIZE) { chunk.toByte() } else byteArrayOf()
        val status = if (chunk >= chunks - 1) ProcStatus.Exited(1) else ProcStatus.Running
        if (proc.status.isTerminal) assertEquals(0L, observationTimeoutSeconds)
        return Poll(bytes, sinceCursorBytes + bytes.size, status, false)
    }

    private companion object {
        const val CHUNK_SIZE = 1024 * 1024
    }
}

private class WaitingLogOs : Os by ChunkedLogOs() {
    val proc = ChunkedLogOs().proc(ProcStatus.Running)
    val pollStarted = CompletableDeferred<Unit>()
    val releasePoll = CountDownLatch(1)
    val terminations = AtomicInteger()
    @Volatile var pollInterrupted = false
        private set
    @Volatile var settled: Proc? = null
        private set

    override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
        pollStarted.complete(Unit)
        try {
            releasePoll.await(30, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            pollInterrupted = true
            throw interrupted
        }
        return Poll(byteArrayOf(), sinceCursorBytes, ProcStatus.Exited(0), false)
    }

    override fun terminate(proc: Proc): Proc {
        terminations.incrementAndGet()
        return proc.copy(status = ProcStatus.Cancelled).also { settled = it }
    }
}

internal class ChunkedLogOs : Os {
    override val ownerToken = OwnerToken("chunked-log-test")
    val output = ("x".repeat(4 * CHUNK_SIZE) + "\nFINAL FAILURE\n").toByteArray()

    fun proc(status: ProcStatus): Proc = Proc(42, 1000, IdentityKey(42, 1000), ownerToken,
        "unused.log", 0, status, Command.Argv(listOf("echo", "fixture")), ".")

    override fun spawn(spec: SpawnSpec): Proc = proc(ProcStatus.Running).copy(
        logPath = spec.logPath.toString(), command = spec.command, workingDirectory = spec.workingDirectory.toString(),
    )

    override fun poll(proc: Proc, sinceCursorBytes: Long, observationTimeoutSeconds: Long): Poll {
        if (proc.status.isTerminal) assertEquals(0L, observationTimeoutSeconds, "terminal draining must not wait")
        val start = sinceCursorBytes.toInt()
        val end = minOf(start + CHUNK_SIZE, output.size)
        return Poll(output.copyOfRange(start, end), end.toLong(), ProcStatus.Exited(1), false)
    }

    override fun terminate(proc: Proc): Proc = error("observation must not terminate or relaunch")
    override fun advanceCursor(proc: Proc, cursorBytes: Long): Proc = proc.copy(logCursorBytes = cursorBytes)
    override fun reattach(proc: Proc): Proc = proc
    override fun resolve(sidecarPath: Path): Proc? = error("unused")
    override fun replaceFileAtomically(path: Path, bytes: ByteArray): Unit = error("unused")
    override fun realPath(path: Path): Path? = error("unused")
    override fun close() = Unit

    private companion object {
        const val CHUNK_SIZE = 64
    }
}
