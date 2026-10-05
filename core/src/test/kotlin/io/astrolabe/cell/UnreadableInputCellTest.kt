package io.astrolabe.cell

import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** WD-05 (W2): a file of the tree another process holds blocks the cell by name; the cell does not fail on it (R2). */
class UnreadableInputCellTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `a locked file in the tree blocks the cell by its path instead of failing it`() = runTest {
        CellFixture(stateRoot).use { f ->
            val file = f.repo.write("tools/build.lock", "held by a tool\n")
            val held = hold(file)
            assumeTrue(held != null, "this host reads a file without read permission (superuser?)")
            held!!.use {
                val exit = f.run(ScriptedModel.of(Scripted.Reply(listOf(say("never sent")))))

                val blocked = assertIs<CellExit.Blocked>(exit, "the cell blocks instead of failing: $exit")
                assertEquals(listOf("tools/build.lock"), blocked.request.evidence)
                assertTrue("tools/build.lock" in blocked.request.reason, blocked.request.reason)
            }
        }
    }

    /** Makes [file] unreadable until the handle closes: an exclusive range lock on Windows, no permissions elsewhere. */
    private fun hold(file: Path): AutoCloseable? {
        if (WINDOWS) {
            val channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val lock = channel.lock()
            return AutoCloseable { lock.release(); channel.close() }
        }
        val before = Files.getPosixFilePermissions(file)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        val restore = AutoCloseable { Files.setPosixFilePermissions(file, before) }
        if (runCatching { Files.readAllBytes(file) }.isFailure) return restore
        restore.close()
        return null
    }
}
