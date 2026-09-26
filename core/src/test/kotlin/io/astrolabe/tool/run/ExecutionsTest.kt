package io.astrolabe.tool.run

import io.astrolabe.os.Command
import io.astrolabe.os.IdentityKey
import io.astrolabe.os.Os
import io.astrolabe.os.OwnerToken
import io.astrolabe.os.Poll
import io.astrolabe.os.Proc
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
