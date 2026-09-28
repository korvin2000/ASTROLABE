package io.astrolabe.os

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class WindowsOwnerTest {
    @Test
    fun `failure before job assignment terminates the suspended child`(@TempDir root: Path) {
        var pid = 0L
        // Taken while the child exists, the handle carries its start time: a reused PID is never judged or killed.
        var child: ProcessHandle? = null
        val owner = WindowsOwner { created ->
            pid = created
            child = ProcessHandle.of(created).orElse(null)
            throw OsFailure("AssignProcessToJobObject", 5, "injected assignment failure")
        }
        try {
            assertFailsWith<OsFailure> {
                owner.start(OwnedStart(Command.Argv(listOf("cmd.exe", "/d", "/c", "echo ran>ran.txt")),
                    root, System.getenv(), root.resolve("child.log")))
            }
            assertTrue(pid > 0)
            assertFalse(child?.isAlive ?: false, "unassigned process must be gone")
            assertFalse(Files.exists(root.resolve("ran.txt")), "the child never resumed")
        } finally {
            child?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }
}
