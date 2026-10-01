package io.astrolabe.os

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    // ---------------------------------------------------------- program resolution (D-363)

    @Test
    fun `a bare name runs the cmd shim found on the child's PATH`(@TempDir root: Path) {
        val bin = Files.createDirectories(root.resolve("bin"))
        val work = Files.createDirectories(root.resolve("work"))
        batch(bin.resolve("tool-d363.cmd"), "echo shim-ran %~1 %~2", "exit /b 3")

        val (exit, output) = run(work, listOf("tool-d363", "alpha", "beta!"), mapOf("PATH" to "$bin;${System.getenv("PATH")}"))

        assertEquals(ProcStatus.Exited(3), exit)
        assertTrue(output.contains("shim-ran alpha beta!"), "log was: $output")
    }

    @Test
    fun `an extra Path beside the inherited PATH both resolves the program and reaches the child`(@TempDir root: Path) {
        val bin = Files.createDirectories(root.resolve("bin-d374"))
        val work = Files.createDirectories(root.resolve("work"))
        batch(bin.resolve("tool-d374.cmd"), "echo seen=%PATH%")

        val (exit, output) = run(work, listOf("tool-d374"), mapOf("Path" to "$bin;${System.getenv("PATH")}"))

        assertEquals(ProcStatus.Exited(0), exit, "log was: $output")
        assertTrue(output.contains("seen=$bin;"), "the child sees the PATH the program was resolved through; log was: $output")
    }

    @Test
    fun `a relative script runs with or without its extension and never the POSIX script beside it`(@TempDir root: Path) {
        batch(root.resolve("tool.bat"), "echo local-ran %~1")
        Files.writeString(root.resolve("tool"), "#!/bin/sh\necho posix\n")

        val (withExtension, first) = run(root, listOf("./tool.bat", "one"))
        val (bare, second) = run(root, listOf("./tool", "two"))
        val (backslash, third) = run(root, listOf(".\\tool", "three"))

        assertEquals(listOf(ProcStatus.Exited(0), ProcStatus.Exited(0), ProcStatus.Exited(0)), listOf(withExtension, bare, backslash))
        assertTrue(first.contains("local-ran one"), "log was: $first")
        assertTrue(second.contains("local-ran two"), "log was: $second")
        assertTrue(third.contains("local-ran three"), "log was: $third")
    }

    @Test
    fun `an exe on PATH still launches directly`(@TempDir root: Path) {
        val resolved = assertNotNull(WindowsOwner.resolveProgram("where", root, System.getenv("PATH"), WindowsOwner.pathExtensions(System.getenv())))
        assertTrue(resolved.fileName.toString().equals("where.exe", ignoreCase = true), "resolved $resolved")

        val (exit, output) = run(root, listOf("where", "cmd"))

        assertEquals(ProcStatus.Exited(0), exit)
        assertTrue(output.lowercase().contains("cmd.exe"), "log was: $output")
    }

    @Test
    fun `a missing program fails with an actionable reason`(@TempDir root: Path) {
        val failure = assertFailsWith<OsFailure> { run(root, listOf("no-such-program-d363", "test")) }

        assertTrue(
            failure.message.orEmpty().startsWith("'no-such-program-d363' was not found in the working directory or on PATH (PATHEXT ."),
            "reason was: ${failure.message}",
        )
        assertEquals(2, failure.errorCode)
    }

    private fun batch(file: Path, vararg lines: String) {
        Files.writeString(file, (listOf("@echo off") + lines).joinToString("\r\n", postfix = "\r\n"))
    }

    private fun run(directory: Path, argv: List<String>, extra: Map<String, String> = emptyMap()): Pair<ProcStatus, String> {
        val log = Files.createTempFile(directory, "child", ".log")
        LocalOs().use { os ->
            val proc = os.spawn(SpawnSpec(Command.Argv(argv), directory, log, EnvPolicy(extra = extra)))
            val status = os.awaitTerminal(proc).status
            return status to Files.readString(log)
        }
    }
}
