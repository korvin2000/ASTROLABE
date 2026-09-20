package io.astrolabe.store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: one controller process per project store (D-44, D-26). */
class ProjectLockTest {

    @TempDir
    lateinit var root: Path

    private fun layout(): Layout = Layout(root).create()

    @Test
    fun `acquiring records the owning process`() {
        val layout = layout()
        ProjectLock.acquire(layout, TEST_CLOCK, harnessVersion = "9.9.9").use { lock ->
            assertEquals(ProcessHandle.current().pid(), lock.holder.pid)
            assertEquals(TEST_INSTANT, lock.holder.startedAt)
            assertEquals("9.9.9", lock.holder.harnessVersion)

            val recorded = String(Files.readAllBytes(layout.lockFile), StandardCharsets.UTF_8)
            assertTrue(recorded.contains("\"pid\":${lock.holder.pid}"), "lock file holds the owner: $recorded")
            assertTrue(recorded.contains("9.9.9"), "lock file holds the harness version: $recorded")
        }
    }

    @Test
    fun `a second controller fails to acquire ownership`() {
        val layout = layout()
        ProjectLock.acquire(layout, TEST_CLOCK).use {
            val refused = assertFailsWith<ProjectLockHeld> { ProjectLock.acquire(layout(), TEST_CLOCK) }
            assertEquals(layout.lockFile, refused.lockFile)
            val holder = assertNotNull(refused.holder, "the holder is readable from this process")
            assertEquals(ProcessHandle.current().pid(), holder.pid)
        }
    }

    @Test
    fun `ownership is released by close and can be taken again`() {
        val layout = layout()
        val first = ProjectLock.acquire(layout, TEST_CLOCK)
        first.close()
        ProjectLock.acquire(layout, TEST_CLOCK).use { second ->
            assertEquals(ProcessHandle.current().pid(), second.holder.pid)
        }
        // Closing twice is not an error: `use` on an already closed lock must stay safe.
        first.close()
    }

    @Test
    fun `the lock file is truncated, not appended, on each acquisition`() {
        val layout = layout()
        ProjectLock.acquire(layout, TEST_CLOCK, harnessVersion = "1.1.1").close()
        ProjectLock.acquire(layout, TEST_CLOCK, harnessVersion = "2.2.2").use {
            val recorded = String(Files.readAllBytes(layout.lockFile), StandardCharsets.UTF_8)
            assertTrue(recorded.contains("2.2.2"), "current owner recorded: $recorded")
            assertTrue(!recorded.contains("1.1.1"), "previous owner removed: $recorded")
        }
    }
}
