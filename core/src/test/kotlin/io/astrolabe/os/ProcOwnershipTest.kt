package io.astrolabe.os

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P0.6.1 tree ownership: a descendant that the root process spawned must never survive the root's
 * container (Windows job object, POSIX session). D-43, IX-20.
 */
class ProcOwnershipTest {

    private lateinit var directory: Path
    private lateinit var os: LocalOs
    private val logCounter = AtomicInteger()

    @BeforeEach
    fun setUp(@TempDir temporary: Path) {
        directory = temporary
        os = LocalOs()
    }

    @AfterEach
    fun tearDown() {
        os.close()
    }

    @Test
    fun `terminating the root kills a grandchild it spawned`() {
        val proc = spawn(ChildCommands.spawnGrandchildThenSleep(120))
        val grandchild = grandchildPidOf(proc)
        assertTrue(ChildCommands.isAlive(grandchild), "the grandchild should be running before termination")

        assertEquals(ProcStatus.Cancelled, os.terminate(proc).status)

        assertTrue(ChildCommands.awaitPidGone(grandchild, 15_000L), "grandchild $grandchild survived termination")
    }

    /**
     * Three orderings have to hold for this scenario to test what it claims, and on a CI runner
     * none of them is free (P0.1.2, 2026-09-21):
     *
     *  1. the grandchild must exist before the deadline fires — the launcher's own start-up is under
     *     a second locally and was tens of seconds on the Windows runner, so it is measured, not
     *     assumed, and the deadline is a multiple of it;
     *  2. the root must still be alive when the deadline fires — so its lifetime is derived from the
     *     deadline instead of being a constant that a long deadline can outrun;
     *  3. the wait for a terminal status must outlast the deadline.
     */
    @Test
    fun `the execution deadline kills a grandchild it spawned`() {
        val deadline = (launcherStartupSeconds() * 3 + 5).coerceIn(MIN_DEADLINE_SECONDS, MAX_DEADLINE_SECONDS)
        val proc = spawn(ChildCommands.spawnGrandchildThenSleep((deadline * 3).toInt()), deadlineSeconds = deadline)
        val grandchild = grandchildPidOf(proc)

        assertEquals(ProcStatus.DeadlineExceeded, os.awaitTerminal(proc, deadline + 30L).status)

        assertTrue(ChildCommands.awaitPidGone(grandchild, 15_000L), "grandchild $grandchild survived the deadline")
    }

    @Test
    fun `a grandchild does not outlive the root process that spawned it`() {
        val proc = spawn(ChildCommands.spawnGrandchildThenExit())
        val grandchild = grandchildPidOf(proc)

        assertEquals(ProcStatus.Exited(0), os.awaitTerminal(proc).status)

        assertTrue(ChildCommands.awaitPidGone(grandchild, 15_000L), "grandchild $grandchild outlived its root")
    }

    @Test
    fun `closing the adapter terminates everything it still owns`() {
        val proc = spawn(ChildCommands.spawnGrandchildThenSleep(120))
        val grandchild = grandchildPidOf(proc)

        os.close()

        assertTrue(ChildCommands.awaitPidGone(proc.pid, 15_000L))
        assertTrue(ChildCommands.awaitPidGone(grandchild, 15_000L))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a POSIX child leads its own session and process group`() {
        val proc = spawn(ChildCommands.sleep(60))

        // /proc/<pid>/stat: pid (comm) state ppid pgrp session ...; comm may contain spaces.
        val fields = Files.readString(Path.of("/proc/${proc.pid}/stat")).substringAfterLast(") ").split(" ")

        assertEquals(proc.pid, fields[2].toLong(), "pgid must equal the pid so kill(-pgid) reaches the tree")
        assertEquals(proc.pid, fields[3].toLong(), "POSIX_SPAWN_SETSID must make the child a session leader")
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `windows argument quoting follows the MSVCRT rules`() {
        assertEquals("plain", WindowsOwner.quoteArgument("plain"))
        assertEquals("\"\"", WindowsOwner.quoteArgument(""))
        assertEquals("\"with space\"", WindowsOwner.quoteArgument("with space"))
        assertEquals("\"a\\\"b\"", WindowsOwner.quoteArgument("a\"b"))
        assertEquals("\"c:\\dir with space\\\\\"", WindowsOwner.quoteArgument("c:\\dir with space\\"))
    }

    /** Seconds this host needs to get the grandchild launcher's interpreter to its first output. */
    private fun launcherStartupSeconds(): Long {
        val started = System.nanoTime()
        val probe = spawn(ChildCommands.launcherReady())
        os.awaitLogMatch(probe, Regex(ChildCommands.READY_MARKER), timeoutSeconds = 120L)
        os.awaitTerminal(probe, 30L)
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + 1L
    }

    private fun grandchildPidOf(proc: Proc): Long {
        val text = os.awaitLogMatch(proc, Regex("""GRANDCHILD=\d+"""), timeoutSeconds = 45L)
        return assertNotNull(ChildCommands.grandchildPid(text), "no grandchild pid in: $text")
    }

    private fun spawn(command: Command, deadlineSeconds: Long? = null): Proc = os.spawn(
        SpawnSpec(
            command = command,
            workingDirectory = directory,
            logPath = directory.resolve("child-${logCounter.incrementAndGet()}.log"),
            deadlineSeconds = deadlineSeconds,
        ),
    )

    private companion object {
        /** Long enough for a warm launcher; [launcherStartupSeconds] raises it on a slow host. */
        const val MIN_DEADLINE_SECONDS = 10L

        /** Keeps the scenario bounded when a host is pathologically slow to start an interpreter. */
        const val MAX_DEADLINE_SECONDS = 90L
    }
}
