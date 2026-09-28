package io.astrolabe.os

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** F-016: process-group escape must not escape ownership or terminal settlement. */
@EnabledOnOs(OS.LINUX)
class LinuxSubreaperTest {

    enum class Outcome { ROOT_EXIT, GROUP_EXIT, CANCELLATION, DEADLINE, CLOSE }

    @ParameterizedTest
    @EnumSource(Outcome::class)
    fun `detached double fork is gone before terminal settlement`(outcome: Outcome, @TempDir directory: Path) {
        withDetachedChild(directory, deadlineSeconds = if (outcome == Outcome.DEADLINE) 10L else null) { os, proc, pids ->
            val expected = when (outcome) {
                Outcome.ROOT_EXIT -> {
                    releaseRoot(directory, "go")
                    ProcStatus.Exited(37)
                }
                Outcome.GROUP_EXIT -> {
                    releaseRoot(directory, "kill-group")
                    ProcStatus.Exited(137)
                }
                Outcome.CANCELLATION -> {
                    assertEquals(ProcStatus.Cancelled, os.terminate(proc).status)
                    ProcStatus.Cancelled
                }
                Outcome.DEADLINE -> ProcStatus.DeadlineExceeded
                Outcome.CLOSE -> {
                    os.close()
                    ProcStatus.Cancelled
                }
            }

            assertEquals(expected, os.awaitTerminal(proc).status)
            // No grace period: a terminal record itself promises that every descendant is gone.
            (pids + proc.pid).forEach { pid ->
                assertFalse(ChildCommands.isAlive(pid), "pid $pid survived $outcome settlement")
                assertFalse(Files.exists(Path.of("/proc/$pid")), "pid $pid was not reaped before settlement")
            }
            assertEquals(expected, assertNotNull(os.resolve(proc.sidecarPath)).status)
        }
    }

    @Test
    fun `argv executable is resolved using the requested PATH`(@TempDir directory: Path) {
        // A non-ASCII directory name also checks that the supervisor decodes paths in a UTF-8 locale.
        val bin = Files.createDirectory(directory.resolve("custom bïn"))
        val executable = bin.resolve("astrolabe-path-probe")
        Files.writeString(executable, "#!/bin/sh\nprintf 'custom-path-ok\\n'\nexit 23\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        LocalOs().use { os ->
            val proc = os.spawn(
                SpawnSpec(
                    command = Command.Argv(listOf(executable.fileName.toString())),
                    workingDirectory = directory,
                    logPath = directory.resolve("path.log"),
                    environment = EnvPolicy(extra = mapOf("PATH" to bin.toString())),
                ),
            )

            assertEquals(ProcStatus.Exited(23), os.awaitTerminal(proc).status)
            assertTrue(Files.readString(proc.log).contains("custom-path-ok"))
        }
    }

    private fun withDetachedChild(
        directory: Path,
        deadlineSeconds: Long? = null,
        test: (LocalOs, Proc, List<Long>) -> Unit,
    ) {
        val os = LocalOs()
        var proc: Proc? = null
        try {
            val started = os.spawn(
                SpawnSpec(
                    command = Command.Argv(listOf("python3", "-c", DETACHED_CHILD)),
                    workingDirectory = directory,
                    logPath = directory.resolve("detached.log"),
                    deadlineSeconds = deadlineSeconds,
                ),
            )
            proc = started
            os.awaitLogMatch(started, Regex("DETACHED_READY"))
            val pids = PID_FILES.map { Files.readString(directory.resolve(it)).trim().toLong() }
            val root = procFields(pids[0])
            val detached = procFields(pids[2])
            assertTrue(ChildCommands.isAlive(pids[0]), "root must remain alive until the test triggers settlement")
            assertTrue(ChildCommands.isAlive(pids[2]), "detached descendant must be alive before settlement")
            assertEquals(pids[0], root[2].toLong(), "root must lead its process group")
            assertEquals(pids[0], root[3].toLong(), "root must lead its own session")
            assertNotEquals(root[2], detached[2], "descendant did not escape the root process group")
            assertNotEquals(root[3], detached[3], "descendant did not create a separate session")
            assertEquals(pids[1], detached[3].toLong(), "the intermediate process must have called setsid")
            assertEquals(pids[2], detached[2].toLong(), "the final descendant must have called setpgid")
            assertFalse(Files.exists(Path.of("/proc/${pids[1]}")), "intermediate must have exited and been reaped")
            assertNotEquals(pids[0], detached[1].toLong(), "double-fork descendant must have been reparented")
            test(os, started, pids)
        } finally {
            // Also runs against the old group-only implementation, which leaves the daemon alive.
            // Each fork's parent records its child before exiting, so cleanup needs no tree scan.
            try {
                os.close()
            } finally {
                val knownPids = PID_FILES.mapNotNull { name ->
                    runCatching { Files.readString(directory.resolve(name)).trim().toLong() }.getOrNull()
                } + listOfNotNull(proc?.pid)
                knownPids.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
                knownPids.forEach { pid -> ChildCommands.awaitPidGone(pid, 1_000L) }
            }
        }
    }

    // /proc stat fields after comm: state, ppid, pgrp, session, ...
    private fun procFields(pid: Long): List<String> =
        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").split(" ")

    private fun releaseRoot(directory: Path, action: String) {
        Files.move(Files.writeString(directory.resolve("exit-request"), action), directory.resolve("exit-root"))
    }

    private companion object {
        val PID_FILES = listOf("root.pid", "intermediate.pid", "detached.pid")

        val DETACHED_CHILD = """
            import os, pathlib, signal, time

            pathlib.Path('root.pid').write_text(str(os.getpid()))
            read_fd, write_fd = os.pipe()
            intermediate = os.fork()
            if intermediate == 0:
                os.close(read_fd)
                os.setsid()
                detached = os.fork()
                if detached != 0:
                    pathlib.Path('detached.pid').write_text(str(detached))
                    os._exit(0)
                os.setpgid(0, 0)
                os.write(write_fd, b'ready')
                os.close(write_fd)
                time.sleep(300)
                os._exit(0)

            pathlib.Path('intermediate.pid').write_text(str(intermediate))
            os.close(write_fd)
            assert os.read(read_fd, 5) == b'ready'
            os.close(read_fd)
            os.waitpid(intermediate, 0)
            print('DETACHED_READY', flush=True)
            while not pathlib.Path('exit-root').exists():
                time.sleep(0.01)
            if pathlib.Path('exit-root').read_text() == 'kill-group':
                os.killpg(os.getpgrp(), signal.SIGKILL)
            os._exit(37)
        """.trimIndent()
    }
}
