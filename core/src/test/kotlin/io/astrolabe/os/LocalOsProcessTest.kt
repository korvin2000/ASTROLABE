package io.astrolabe.os

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P0.6.1: spawn, log cursors, observation timeout, deadline and cancellation (`§13.1`, D-43). */
class LocalOsProcessTest {

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
    fun `confirmed termination carries the exit code and a finish time`() {
        val proc = spawn(ChildCommands.print("hello-astrolabe"))

        val first = os.poll(proc, 0L, 30L)
        assertFalse(first.timedOut, "output should arrive well inside the observation window")
        assertTrue(first.text().contains("hello-astrolabe"), "log was: ${first.text()}")

        val settled = os.awaitTerminal(proc)
        assertEquals(ProcStatus.Exited(0), settled.status)
        assertNotNull(settled.finishedAtEpochMillis)
        assertEquals(ProcStatus.Exited(0), assertNotNull(os.resolve(proc.sidecarPath)).status)
    }

    @Test
    fun `a non-zero exit code is captured rather than turned into a failure`() {
        val proc = spawn(ChildCommands.exitWith(7))

        assertEquals(ProcStatus.Exited(7), os.awaitTerminal(proc).status)
    }

    @Test
    fun `an argument vector launches without a shell wrapper`() {
        val proc = spawn(ChildCommands.printViaArgv("argv-form-ok"))

        val output = os.awaitLogMatch(proc, Regex("argv-form-ok"))

        assertTrue(output.contains("argv-form-ok"))
        assertEquals(ProcStatus.Exited(0), os.awaitTerminal(proc).status)
    }

    @Test
    fun `a second poll returns only new bytes and the cursor is persisted`() {
        val proc = spawn(ChildCommands.printTwice("first-line", "second-line", gapSeconds = 2))

        val first = os.poll(proc, 0L, 30L)
        assertTrue(first.text().contains("first-line"), "log was: ${first.text()}")
        assertFalse(first.text().contains("second-line"), "the gap should keep the lines apart")
        assertTrue(first.nextCursorBytes > 0L)

        val stored = os.advanceCursor(proc, first.nextCursorBytes)
        assertEquals(first.nextCursorBytes, stored.logCursorBytes)
        assertEquals(
            first.nextCursorBytes,
            assertNotNull(os.resolve(proc.sidecarPath)).logCursorBytes,
            "a resumed harness must read the cursor back from the sidecar",
        )

        val second = os.poll(proc, first.nextCursorBytes, 30L)
        assertTrue(second.text().contains("second-line"), "log was: ${second.text()}")
        assertFalse(second.text().contains("first-line"), "already-read bytes must not repeat")
        assertTrue(second.nextCursorBytes > first.nextCursorBytes)
    }

    @Test
    fun `an observation timeout leaves the process running and is not a failure`() {
        val proc = spawn(ChildCommands.sleep(60))

        val poll = os.poll(proc, 0L, 1L)

        // FX-22: the observation timed out, the execution did not. No relaunch is justified.
        assertTrue(poll.timedOut)
        assertEquals(ProcStatus.Running, poll.status)
        assertEquals(0L, poll.nextCursorBytes)
        assertTrue(poll.newBytes.isEmpty())
        assertEquals(ProcStatus.Running, os.reattach(proc).status)
        assertTrue(ChildCommands.isAlive(proc.pid))
    }

    @Test
    fun `the execution deadline terminates the process and records DeadlineExceeded`() {
        val proc = spawn(ChildCommands.sleep(120), deadlineSeconds = 2L)

        val settled = os.awaitTerminal(proc)

        assertEquals(ProcStatus.DeadlineExceeded, settled.status)
        assertTrue(ChildCommands.awaitPidGone(proc.pid, 10_000L))
        assertEquals(ProcStatus.DeadlineExceeded, assertNotNull(os.resolve(proc.sidecarPath)).status)
    }

    @Test
    fun `a cancellation request settles as Cancelled`() {
        val proc = spawn(ChildCommands.sleep(120))

        val settled = os.terminate(proc)

        assertEquals(ProcStatus.Cancelled, settled.status)
        assertTrue(ChildCommands.awaitPidGone(proc.pid, 10_000L))
        assertEquals(ProcStatus.Cancelled, assertNotNull(os.resolve(proc.sidecarPath)).status)
    }

    @Test
    fun `a deadline racing a cancellation settles to exactly one terminal status`() {
        val proc = spawn(ChildCommands.sleep(120), deadlineSeconds = 1L)

        // Aim the cancellation at the moment the deadline fires; either may win, never both.
        Thread.sleep(950L)
        os.terminate(proc)

        val settled = os.awaitTerminal(proc)
        assertTrue(
            settled.status == ProcStatus.Cancelled || settled.status == ProcStatus.DeadlineExceeded,
            "unexpected terminal status ${settled.status}",
        )
        repeat(3) {
            Thread.sleep(100L)
            assertEquals(settled.status, assertNotNull(os.resolve(proc.sidecarPath)).status)
            assertEquals(settled.status, os.reattach(proc).status)
        }
        // A poll after termination reports the same settled status, with no duplicate execution.
        val poll = os.poll(proc, 0L, 1L)
        assertFalse(poll.timedOut)
        assertEquals(settled.status, poll.status)
    }

    @Test
    fun `terminating an already settled process keeps its recorded status`() {
        val proc = spawn(ChildCommands.print("done"))
        val settled = os.awaitTerminal(proc)
        assertEquals(ProcStatus.Exited(0), settled.status)

        assertEquals(ProcStatus.Exited(0), os.terminate(proc).status)
    }

    @Test
    fun `the child receives the platform essentials plus only allowlisted extras`() {
        val windows = ChildCommands.isWindows
        val probe = if (windows) "%ASTROLABE_PROBE%" else "\$ASTROLABE_PROBE"
        val absent = if (windows) "%ASTROLABE_ABSENT%" else "\$ASTROLABE_ABSENT"
        val essential = if (windows) "%SystemRoot%" else "\$HOME"
        val proc = os.spawn(
            SpawnSpec(
                command = Command.Shell("echo probe=$probe absent=$absent essential=$essential"),
                workingDirectory = directory,
                logPath = nextLog(),
                environment = EnvPolicy(extra = mapOf("ASTROLABE_PROBE" to "granted")),
            ),
        )

        val output = os.awaitLogMatch(proc, Regex("probe="))

        assertTrue(output.contains("probe=granted"), "log was: $output")
        // Regression: the essentials must be looked up the way the platform names them. A parent
        // whose block spells `SYSTEMROOT` used to leave the child without a system root at all.
        assertTrue(
            Regex(if (windows) """essential=\w:\\""" else """essential=/\S""").containsMatchIn(output),
            "the platform essentials must reach the child; log was: $output",
        )
        // Windows leaves an unset %VAR% literal in place; POSIX expands it to the empty string.
        assertFalse(output.contains("absent=granted"))
    }

    private fun spawn(command: Command, deadlineSeconds: Long? = null): Proc = os.spawn(
        SpawnSpec(
            command = command,
            workingDirectory = directory,
            logPath = nextLog(),
            deadlineSeconds = deadlineSeconds,
        ),
    )

    private fun nextLog(): Path = directory.resolve("child-${logCounter.incrementAndGet()}.log")
}
