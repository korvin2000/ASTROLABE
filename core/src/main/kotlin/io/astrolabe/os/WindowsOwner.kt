package io.astrolabe.os

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.TreeMap

/**
 * Windows [ProcessOwner]: one job object per launch, the child created suspended and assigned to
 * the job before it executes a single instruction (D-43).
 *
 * `JOB_OBJECT_LIMIT_KILL_ON_CLOSE` is set and no breakaway flag is set, so descendants cannot
 * leave the job and the whole tree dies when the job handle closes — including when this JVM dies.
 */
internal class WindowsOwner(private val beforeAssignment: (Long) -> Unit = {}) : ProcessOwner {

    override val essentialEnvironmentNames: Set<String> = setOf(
        "SystemRoot", "SystemDrive", "windir", "PATH", "PATHEXT", "COMSPEC", "TEMP", "TMP", "USERPROFILE",
    )

    override fun start(start: OwnedStart): OwnedProcess {
        // One case-insensitive view, later entries winning, for both program resolution and the child's block (D-374).
        val environment = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
            start.environment.forEach { (name, value) ->
                remove(name)
                put(name, value)
            }
        }
        val launch = plan(start.command, start.workingDirectory, environment)
        return Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Win32.CAPTURE)
            val security = arena.allocate(Win32.SECURITY_ATTRIBUTES).also {
                it.set(ValueLayout.JAVA_INT, 0L, Win32.SECURITY_ATTRIBUTES.byteSize().toInt())
                it.set(ValueLayout.ADDRESS, Win32.SA_DESCRIPTOR, MemorySegment.NULL)
                it.set(ValueLayout.JAVA_INT, Win32.SA_INHERIT, 1)
            }

            var log = MemorySegment.NULL
            var nul = MemorySegment.NULL
            var job = MemorySegment.NULL
            var attributes = MemorySegment.NULL
            try {
                // Inheritable append handle: every write lands at end of file, so the supervisor and
                // the child never fight over the file position.
                log = Win32.createFileW.callSegment(
                    capture,
                    arena.allocateFrom(start.logPath.toString(), StandardCharsets.UTF_16LE),
                    FILE_APPEND_DATA_AND_SYNCHRONIZE, FILE_SHARE_ALL, security,
                    OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL,
                )
                if (log.address() == INVALID_HANDLE) fail("CreateFileW(log)", capture)

                nul = Win32.createFileW.callSegment(
                    capture, arena.allocateFrom("NUL", StandardCharsets.UTF_16LE),
                    GENERIC_READ, FILE_SHARE_ALL, security,
                    OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, MemorySegment.NULL,
                )
                if (nul.address() == INVALID_HANDLE) fail("CreateFileW(NUL)", capture)

                job = Win32.createJobObjectW.callSegment(capture, MemorySegment.NULL, MemorySegment.NULL)
                if (job.address() == 0L) fail("CreateJobObjectW", capture)

                val limits = arena.allocate(Win32.JOB_EXTENDED_LIMITS)
                limits.set(ValueLayout.JAVA_INT, Win32.JOB_LIMIT_FLAGS, JOB_OBJECT_LIMIT_KILL_ON_CLOSE)
                if (Win32.setInformationJobObject.callInt(
                        capture, job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits,
                        Win32.JOB_EXTENDED_LIMITS.byteSize().toInt(),
                    ) == 0
                ) {
                    fail("SetInformationJobObject", capture)
                }

                val size = arena.allocate(ValueLayout.JAVA_LONG)
                Win32.initializeProcThreadAttributeList.callInt(capture, MemorySegment.NULL, 1, 0, size)
                val allocated = arena.allocate(size.get(ValueLayout.JAVA_LONG, 0L), 8)
                if (Win32.initializeProcThreadAttributeList.callInt(capture, allocated, 1, 0, size) == 0)
                    fail("InitializeProcThreadAttributeList", capture)
                attributes = allocated
                val handles = arena.allocate(ValueLayout.ADDRESS, 2)
                handles.setAtIndex(ValueLayout.ADDRESS, 0, nul)
                handles.setAtIndex(ValueLayout.ADDRESS, 1, log)
                if (Win32.updateProcThreadAttribute.callInt(
                        capture, attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, handles.byteSize(),
                        MemorySegment.NULL, MemorySegment.NULL,
                    ) == 0) fail("UpdateProcThreadAttribute(handle list)", capture)
                val startupInfo = arena.allocate(Win32.STARTUPINFOW.byteSize() + 8, 8)
                startupInfo.set(ValueLayout.JAVA_INT, 0L, startupInfo.byteSize().toInt())
                startupInfo.set(ValueLayout.ADDRESS, Win32.STARTUPINFOW.byteSize(), attributes)
                startupInfo.set(ValueLayout.JAVA_INT, Win32.SI_FLAGS, STARTF_USESTDHANDLES)
                startupInfo.set(ValueLayout.ADDRESS, Win32.SI_STDIN, nul)
                startupInfo.set(ValueLayout.ADDRESS, Win32.SI_STDOUT, log)
                startupInfo.set(ValueLayout.ADDRESS, Win32.SI_STDERR, log)
                val info = arena.allocate(Win32.PROCESS_INFORMATION)

                val created = Win32.createProcessW.callInt(
                    capture,
                    launch.application?.let { arena.allocateFrom(it, StandardCharsets.UTF_16LE) } ?: MemorySegment.NULL,
                    arena.allocateFrom(launch.commandLine, StandardCharsets.UTF_16LE),
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    1, // The explicit handle list excludes other simultaneous launches and host handles.
                    CREATE_SUSPENDED or CREATE_UNICODE_ENVIRONMENT or CREATE_NO_WINDOW or EXTENDED_STARTUPINFO_PRESENT,
                    environmentBlock(arena, environment),
                    arena.allocateFrom(start.workingDirectory.toString(), StandardCharsets.UTF_16LE),
                    startupInfo,
                    info,
                )
                if (created == 0) {
                    val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
                    if (launch.missing != null && (code == ERROR_FILE_NOT_FOUND || code == ERROR_PATH_NOT_FOUND))
                        throw OsFailure("CreateProcessW", code, launch.missing)
                    fail("CreateProcessW", capture)
                }

                val process = info.get(ValueLayout.ADDRESS, Win32.PI_PROCESS)
                val thread = info.get(ValueLayout.ADDRESS, Win32.PI_THREAD)
                val pid = info.get(ValueLayout.JAVA_INT, Win32.PI_PID).toLong() and 0xFFFF_FFFFL
                try {
                    beforeAssignment(pid)
                    if (Win32.assignProcessToJobObject.callInt(capture, job, process) == 0) {
                        fail("AssignProcessToJobObject", capture)
                    }
                    val startedAt = creationTimeEpochMillis(arena, capture, process)
                    if (Win32.resumeThread.callInt(capture, thread) == -1) fail("ResumeThread", capture)
                    WindowsProcess(pid, startedAt, process, job)
                } catch (failure: Throwable) {
                    try {
                        // Assignment may have failed: the suspended child need not belong to the job.
                        if (Win32.terminateProcess.callInt(capture, process, 1) == 0)
                            failure.addSuppressed(OsFailure("TerminateProcess", capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR), "failed launch cleanup"))
                        Win32.terminateJobObject.callInt(capture, job, 1)
                        if (Win32.waitForSingleObject.callInt(capture, process, 5_000) != 0)
                            failure.addSuppressed(OsFailure("WaitForSingleObject", 0, "failed launch did not exit within 5 seconds"))
                    } finally {
                        Win32.closeHandle.callInt(capture, process)
                    }
                    throw failure
                } finally {
                    Win32.closeHandle.callInt(capture, thread)
                }
            } catch (failure: Throwable) {
                if (job.address() != 0L) Win32.closeHandle.callInt(capture, job)
                throw failure
            } finally {
                if (attributes.address() != 0L) Win32.deleteProcThreadAttributeList.invokeWithArguments(capture, attributes)
                // The child holds its own duplicates; ours are no longer needed.
                if (log.address() != INVALID_HANDLE && log.address() != 0L) Win32.closeHandle.callInt(capture, log)
                if (nul.address() != INVALID_HANDLE && nul.address() != 0L) Win32.closeHandle.callInt(capture, nul)
            }
        }
    }

    override fun terminateUnowned(pid: Long): Boolean = Arena.ofConfined().use { arena ->
        val capture = arena.allocate(Win32.CAPTURE)
        val handle = Win32.openProcess.callSegment(capture, PROCESS_TERMINATE, 0, pid.toInt())
        if (handle.address() == 0L) return false
        try {
            // Single process only: the original job object died with the harness that created it.
            Win32.terminateProcess.callInt(capture, handle, 1) != 0
        } finally {
            Win32.closeHandle.callInt(capture, handle)
        }
    }

    private fun creationTimeEpochMillis(arena: Arena, capture: MemorySegment, process: MemorySegment): Long? {
        val times = arena.allocate(ValueLayout.JAVA_LONG, 4)
        val ok = Win32.getProcessTimes.callInt(
            capture, process,
            times.asSlice(0L, 8L), times.asSlice(8L, 8L), times.asSlice(16L, 8L), times.asSlice(24L, 8L),
        )
        if (ok == 0) return null
        // Same conversion the JDK applies to `ProcessHandle.info().startInstant()` on Windows, so
        // the two agree exactly and PID reuse is detectable across harness restarts.
        return (times.get(ValueLayout.JAVA_LONG, 0L) - FILETIME_EPOCH_OFFSET) / 10_000L
    }

    private fun environmentBlock(arena: Arena, environment: TreeMap<String, String>): MemorySegment {
        // CreateProcessW requires the Unicode block sorted case-insensitively and double-NUL ended.
        val block = buildString {
            environment.forEach { (name, value) -> append(name).append('=').append(value).append('\u0000') }
            append('\u0000')
        }
        return arena.allocateFrom(block, StandardCharsets.UTF_16LE)
    }

    /**
     * [application] is the resolved image CreateProcessW starts without searching, or null to let it
     * parse [commandLine]; [missing] is the failure text when the program was not found (D-363).
     */
    private class Launch(val application: String?, val commandLine: String, val missing: String?)

    /**
     * CreateProcessW only appends `.exe` and searches the *parent's* directories, so `npm` (a
     * `.cmd` shim) never starts. The program is resolved the way `cmd.exe` would, against the
     * child's working directory and the PATH/PATHEXT the child receives (D-363) — only those: a
     * child without `PATH` is searched for in its working directory alone, and one without
     * `PATHEXT` gets the system default (D-375).
     */
    private fun plan(command: Command, workingDirectory: Path, environment: Map<String, String>): Launch = when (command) {
        is Command.Shell -> Launch(null, shellLine(command.commandLine, " /v:off"), null)
        is Command.Argv -> {
            val program = command.argv.first()
            val extensions = pathExtensions(environment)
            val searchPath = variable(environment, "PATH")
            val resolved = resolveFor(program, workingDirectory, environment)
            when {
                resolved != null && isBatch(resolved.toString()) -> Launch(null, batchLine(listOf(resolved.toString()) + command.argv.drop(1)), null)
                resolved != null -> Launch(resolved.toString(), argvLine(command.argv), null)
                isBatch(program) -> Launch(null, batchLine(command.argv), null)
                else -> Launch(null, argvLine(command.argv), notFound(program, extensions, searchPath != null))
            }
        }
    }

    private fun argvLine(argv: List<String>): String = argv.joinToString(" ") { quoteArgument(it) }

    private fun batchLine(argv: List<String>): String {
        // cmd expands `%` even inside quotes and a `"` would end the quoting: refuse argv we cannot
        // preserve exactly. `!` is literal because `/v:off` overrides a registry-enabled delayed expansion.
        if (argv.any { arg -> arg.any { it in "\"%\r\n" } }) throw java.io.IOException("batch arguments contain unsupported command-interpreter characters")
        return shellLine(argv.joinToString(" ") { "\"$it\"" }, " /v:off")
    }

    // `/s` makes cmd.exe strip exactly the outer quotes and run the rest verbatim.
    private fun shellLine(commandLine: String, options: String): String =
        "${quoteArgument(comspec())} /d$options /s /c \"$commandLine\""

    private fun comspec(): String = System.getenv("COMSPEC")?.takeIf { it.isNotBlank() } ?: "cmd.exe"

    private fun fail(call: String, capture: MemorySegment): Nothing {
        val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
        throw OsFailure(call, code, "$call failed (GetLastError=$code)")
    }

    internal companion object {
        private const val FILE_APPEND_DATA_AND_SYNCHRONIZE = 0x0010_0004
        private const val FILE_SHARE_ALL = 0x0000_0007
        private const val GENERIC_READ = Int.MIN_VALUE // 0x80000000
        private const val OPEN_EXISTING = 3
        private const val OPEN_ALWAYS = 4
        private const val FILE_ATTRIBUTE_NORMAL = 0x0000_0080
        private const val STARTF_USESTDHANDLES = 0x0000_0100
        private const val PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L
        private const val EXTENDED_STARTUPINFO_PRESENT = 0x00080000
        private const val CREATE_SUSPENDED = 0x0000_0004
        private const val CREATE_UNICODE_ENVIRONMENT = 0x0000_0400
        private const val CREATE_NO_WINDOW = 0x0800_0000
        private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
        private const val JOB_OBJECT_LIMIT_KILL_ON_CLOSE = 0x0000_2000
        private const val PROCESS_TERMINATE = 0x0000_0001
        private const val INVALID_HANDLE = -1L
        private const val FILETIME_EPOCH_OFFSET = 116_444_736_000_000_000L
        private const val ERROR_FILE_NOT_FOUND = 2
        private const val ERROR_PATH_NOT_FOUND = 3
        private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"

        /**
         * The file `cmd.exe` would run for [program], or null. A bare name is looked up in
         * [workingDirectory], then in each [searchPath] directory; a name with a directory part
         * (`./gradlew`, `tools\x`, `C:\x`) only against [workingDirectory]. A name without an
         * extension is tried with each of [extensions] only, so `gradlew` finds `gradlew.bat`, not
         * the POSIX script beside it.
         */
        internal fun resolveProgram(program: String, workingDirectory: Path, searchPath: String?, extensions: List<String>): Path? {
            val fileName = try {
                Path.of(program).fileName?.toString()
            } catch (_: InvalidPathException) {
                null
            } ?: return null
            val dot = fileName.lastIndexOf('.')
            val extension = if (dot > 0) fileName.substring(dot) else null
            val names = when {
                extension == null -> extensions.map { program + it }
                extensions.any { it.equals(extension, ignoreCase = true) } -> listOf(program)
                else -> listOf(program) + extensions.map { program + it }
            }
            val directories = if (program.any { it == '/' || it == '\\' || it == ':' }) {
                listOf(workingDirectory)
            } else {
                listOf(workingDirectory) + searchPath.orEmpty().split(';')
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { entry -> try { workingDirectory.resolve(entry) } catch (_: InvalidPathException) { null } }
            }
            for (directory in directories) {
                for (name in names) {
                    val candidate = try { directory.resolve(name) } catch (_: InvalidPathException) { continue }
                    if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize()
                }
            }
            return null
        }

        /** [resolveProgram] against the child's own `PATH` and `PATHEXT` in [environment], nothing of the parent's (D-375). */
        internal fun resolveFor(program: String, workingDirectory: Path, environment: Map<String, String>): Path? =
            resolveProgram(program, workingDirectory, variable(environment, "PATH"), pathExtensions(environment))

        /** `PATHEXT` as the child sees it, else the system default; never the parent's (D-375). */
        internal fun pathExtensions(environment: Map<String, String>): List<String> =
            (variable(environment, "PATHEXT")?.takeIf { it.isNotBlank() } ?: DEFAULT_PATHEXT)
                .split(';').map { it.trim() }.filter { it.length > 1 && it.startsWith('.') }

        /** [name] in the child's block, compared case-insensitively like Windows does; `null` when the child has none. */
        private fun variable(environment: Map<String, String>, name: String): String? =
            environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun isBatch(program: String): Boolean = program.lowercase().let { it.endsWith(".bat") || it.endsWith(".cmd") }

        private fun notFound(program: String, extensions: List<String>, hasPath: Boolean): String {
            val where = if (program.any { it == '/' || it == '\\' || it == ':' }) "relative to the working directory"
            else if (hasPath) "in the working directory or on PATH"
            else "in the working directory (the child's environment has no PATH)"
            return "'$program' was not found $where (PATHEXT ${extensions.joinToString(";")})"
        }

        /**
         * Quotes one argument for the MSVCRT parser `CommandLineToArgvW` implements: backslashes
         * are literal unless they precede a quote, where each must be doubled.
         */
        internal fun quoteArgument(argument: String): String {
            if (argument.isNotEmpty() && argument.none { it == ' ' || it == '\t' || it == '"' }) return argument
            return buildString {
                append('"')
                var backslashes = 0
                for (character in argument) {
                    when (character) {
                        '\\' -> backslashes++
                        '"' -> {
                            repeat(backslashes * 2 + 1) { append('\\') }
                            backslashes = 0
                            append('"')
                        }
                        else -> {
                            repeat(backslashes) { append('\\') }
                            backslashes = 0
                            append(character)
                        }
                    }
                }
                repeat(backslashes * 2) { append('\\') }
                append('"')
            }
        }
    }
}

/** A child inside its job object. Terminating the job terminates the whole tree. */
private class WindowsProcess(
    override val pid: Long,
    override val startEpochMillis: Long?,
    private val process: MemorySegment,
    private val job: MemorySegment,
) : OwnedProcess {

    override fun awaitExit(timeoutMillis: Long): Boolean = Arena.ofConfined().use { arena ->
        val capture = arena.allocate(Win32.CAPTURE)
        val waited = Win32.waitForSingleObject.callInt(capture, process, timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        when (waited) {
            WAIT_OBJECT_0 -> true
            WAIT_TIMEOUT -> false
            else -> {
                val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
                throw OsFailure("WaitForSingleObject", code, "WaitForSingleObject returned $waited (GetLastError=$code)")
            }
        }
    }

    override fun exitCode(): Int = Arena.ofConfined().use { arena ->
        val capture = arena.allocate(Win32.CAPTURE)
        val slot = arena.allocate(ValueLayout.JAVA_INT)
        if (Win32.getExitCodeProcess.callInt(capture, process, slot) == 0) {
            val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
            throw OsFailure("GetExitCodeProcess", code, "GetExitCodeProcess failed (GetLastError=$code)")
        }
        slot.get(ValueLayout.JAVA_INT, 0L)
    }

    override fun terminateTree() {
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Win32.CAPTURE)
            if (Win32.terminateJobObject.callInt(capture, job, 1) == 0) {
                val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
                throw OsFailure("TerminateJobObject", code, "job termination failed (GetLastError=$code)")
            }
        }
    }

    override fun awaitTreeExit(timeoutMillis: Long): Boolean {
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Win32.CAPTURE)
            val accounting = arena.allocate(Win32.JOB_BASIC_ACCOUNTING)
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (true) {
                if (Win32.queryInformationJobObject.callInt(
                        capture, job, 1, accounting, accounting.byteSize().toInt(), MemorySegment.NULL,
                    ) == 0
                ) {
                    val code = capture.get(ValueLayout.JAVA_INT, Win32.LAST_ERROR)
                    throw OsFailure("QueryInformationJobObject", code, "job accounting failed (GetLastError=$code)")
                }
                if (accounting.get(ValueLayout.JAVA_INT, Win32.JOB_ACTIVE_PROCESSES) == 0) return true
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(10)
            }
        }
    }

    override fun close() {
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Win32.CAPTURE)
            Win32.closeHandle.callInt(capture, process)
            Win32.closeHandle.callInt(capture, job) // kill-on-close: any survivor dies here
        }
    }

    private companion object {
        const val WAIT_OBJECT_0 = 0
        const val WAIT_TIMEOUT = 0x0000_0102
    }
}

/**
 * kernel32 bindings, private to this file. Struct layouts are declared explicitly with named
 * padding for the x64 ABI; the `init` block asserts the documented sizes so a layout typo fails
 * loudly at class-init instead of corrupting memory.
 */
private object Win32 {
    private val linker: Linker = Linker.nativeLinker()
    private val kernel32: SymbolLookup = SymbolLookup.libraryLookup("kernel32", Arena.global())

    val CAPTURE: StructLayout = Linker.Option.captureStateLayout()
    val LAST_ERROR: Long = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"))

    // https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_basic_accounting_information
    val JOB_BASIC_ACCOUNTING: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("TotalUserTime"),
        ValueLayout.JAVA_LONG.withName("TotalKernelTime"),
        ValueLayout.JAVA_LONG.withName("ThisPeriodTotalUserTime"),
        ValueLayout.JAVA_LONG.withName("ThisPeriodTotalKernelTime"),
        ValueLayout.JAVA_INT.withName("TotalPageFaultCount"),
        ValueLayout.JAVA_INT.withName("TotalProcesses"),
        ValueLayout.JAVA_INT.withName("ActiveProcesses"),
        ValueLayout.JAVA_INT.withName("TotalTerminatedProcesses"),
    )
    val JOB_ACTIVE_PROCESSES: Long = JOB_BASIC_ACCOUNTING.byteOffset(MemoryLayout.PathElement.groupElement("ActiveProcesses"))

    val SECURITY_ATTRIBUTES: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("nLength"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpSecurityDescriptor"),
        ValueLayout.JAVA_INT.withName("bInheritHandle"),
        MemoryLayout.paddingLayout(4),
    ).withName("SECURITY_ATTRIBUTES") as StructLayout
    val SA_DESCRIPTOR: Long = SECURITY_ATTRIBUTES.byteOffset(MemoryLayout.PathElement.groupElement("lpSecurityDescriptor"))
    val SA_INHERIT: Long = SECURITY_ATTRIBUTES.byteOffset(MemoryLayout.PathElement.groupElement("bInheritHandle"))

    val STARTUPINFOW: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cb"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpReserved"),
        ValueLayout.ADDRESS.withName("lpDesktop"),
        ValueLayout.ADDRESS.withName("lpTitle"),
        ValueLayout.JAVA_INT.withName("dwX"),
        ValueLayout.JAVA_INT.withName("dwY"),
        ValueLayout.JAVA_INT.withName("dwXSize"),
        ValueLayout.JAVA_INT.withName("dwYSize"),
        ValueLayout.JAVA_INT.withName("dwXCountChars"),
        ValueLayout.JAVA_INT.withName("dwYCountChars"),
        ValueLayout.JAVA_INT.withName("dwFillAttribute"),
        ValueLayout.JAVA_INT.withName("dwFlags"),
        ValueLayout.JAVA_SHORT.withName("wShowWindow"),
        ValueLayout.JAVA_SHORT.withName("cbReserved2"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("lpReserved2"),
        ValueLayout.ADDRESS.withName("hStdInput"),
        ValueLayout.ADDRESS.withName("hStdOutput"),
        ValueLayout.ADDRESS.withName("hStdError"),
    ).withName("STARTUPINFOW") as StructLayout
    val SI_FLAGS: Long = STARTUPINFOW.byteOffset(MemoryLayout.PathElement.groupElement("dwFlags"))
    val SI_STDIN: Long = STARTUPINFOW.byteOffset(MemoryLayout.PathElement.groupElement("hStdInput"))
    val SI_STDOUT: Long = STARTUPINFOW.byteOffset(MemoryLayout.PathElement.groupElement("hStdOutput"))
    val SI_STDERR: Long = STARTUPINFOW.byteOffset(MemoryLayout.PathElement.groupElement("hStdError"))

    val PROCESS_INFORMATION: StructLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("hProcess"),
        ValueLayout.ADDRESS.withName("hThread"),
        ValueLayout.JAVA_INT.withName("dwProcessId"),
        ValueLayout.JAVA_INT.withName("dwThreadId"),
    ).withName("PROCESS_INFORMATION") as StructLayout
    val PI_PROCESS: Long = PROCESS_INFORMATION.byteOffset(MemoryLayout.PathElement.groupElement("hProcess"))
    val PI_THREAD: Long = PROCESS_INFORMATION.byteOffset(MemoryLayout.PathElement.groupElement("hThread"))
    val PI_PID: Long = PROCESS_INFORMATION.byteOffset(MemoryLayout.PathElement.groupElement("dwProcessId"))

    private val JOB_BASIC_LIMITS: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("PerProcessUserTimeLimit"),
        ValueLayout.JAVA_LONG.withName("PerJobUserTimeLimit"),
        ValueLayout.JAVA_INT.withName("LimitFlags"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("MinimumWorkingSetSize"),
        ValueLayout.JAVA_LONG.withName("MaximumWorkingSetSize"),
        ValueLayout.JAVA_INT.withName("ActiveProcessLimit"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("Affinity"),
        ValueLayout.JAVA_INT.withName("PriorityClass"),
        ValueLayout.JAVA_INT.withName("SchedulingClass"),
    ).withName("JOBOBJECT_BASIC_LIMIT_INFORMATION") as StructLayout

    private val IO_COUNTERS: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("ReadOperationCount"),
        ValueLayout.JAVA_LONG.withName("WriteOperationCount"),
        ValueLayout.JAVA_LONG.withName("OtherOperationCount"),
        ValueLayout.JAVA_LONG.withName("ReadTransferCount"),
        ValueLayout.JAVA_LONG.withName("WriteTransferCount"),
        ValueLayout.JAVA_LONG.withName("OtherTransferCount"),
    ).withName("IO_COUNTERS") as StructLayout

    val JOB_EXTENDED_LIMITS: StructLayout = MemoryLayout.structLayout(
        JOB_BASIC_LIMITS.withName("BasicLimitInformation"),
        IO_COUNTERS.withName("IoInfo"),
        ValueLayout.JAVA_LONG.withName("ProcessMemoryLimit"),
        ValueLayout.JAVA_LONG.withName("JobMemoryLimit"),
        ValueLayout.JAVA_LONG.withName("PeakProcessMemoryUsed"),
        ValueLayout.JAVA_LONG.withName("PeakJobMemoryUsed"),
    ).withName("JOBOBJECT_EXTENDED_LIMIT_INFORMATION") as StructLayout
    val JOB_LIMIT_FLAGS: Long = JOB_EXTENDED_LIMITS.byteOffset(
        MemoryLayout.PathElement.groupElement("BasicLimitInformation"),
        MemoryLayout.PathElement.groupElement("LimitFlags"),
    )

    val createFileW: MethodHandle = bind(
        "CreateFileW",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ),
    )
    val createJobObjectW: MethodHandle = bind(
        "CreateJobObjectW",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    val setInformationJobObject: MethodHandle = bind(
        "SetInformationJobObject",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
        ),
    )
    val assignProcessToJobObject: MethodHandle = bind(
        "AssignProcessToJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    val queryInformationJobObject: MethodHandle = bind(
        "QueryInformationJobObject",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
        ),
    )
    val createProcessW: MethodHandle = bind(
        "CreateProcessW",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    val initializeProcThreadAttributeList: MethodHandle = bind(
        "InitializeProcThreadAttributeList",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val updateProcThreadAttribute: MethodHandle = bind(
        "UpdateProcThreadAttribute",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    val deleteProcThreadAttributeList: MethodHandle = bind(
        "DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )
    val resumeThread: MethodHandle = bind(
        "ResumeThread",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val waitForSingleObject: MethodHandle = bind(
        "WaitForSingleObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    val getExitCodeProcess: MethodHandle = bind(
        "GetExitCodeProcess",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    val getProcessTimes: MethodHandle = bind(
        "GetProcessTimes",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    val terminateJobObject: MethodHandle = bind(
        "TerminateJobObject",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    val openProcess: MethodHandle = bind(
        "OpenProcess",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    val terminateProcess: MethodHandle = bind(
        "TerminateProcess",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    val closeHandle: MethodHandle = bind(
        "CloseHandle",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

    init {
        check(JOB_BASIC_ACCOUNTING.byteSize() == 48L)
        check(SECURITY_ATTRIBUTES.byteSize() == 24L) { "SECURITY_ATTRIBUTES is ${SECURITY_ATTRIBUTES.byteSize()}, expected 24" }
        check(STARTUPINFOW.byteSize() == 104L) { "STARTUPINFOW is ${STARTUPINFOW.byteSize()}, expected 104" }
        check(PROCESS_INFORMATION.byteSize() == 24L) { "PROCESS_INFORMATION is ${PROCESS_INFORMATION.byteSize()}, expected 24" }
        check(JOB_EXTENDED_LIMITS.byteSize() == 144L) {
            "JOBOBJECT_EXTENDED_LIMIT_INFORMATION is ${JOB_EXTENDED_LIMITS.byteSize()}, expected 144"
        }
    }

    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        kernel32.find(name).orElseThrow { OsFailure(name, 0, "kernel32!$name not found") },
        descriptor,
        Linker.Option.captureCallState("GetLastError"),
    )
}

/**
 * `invokeWithArguments` rather than `invokeExact`: it performs the argument and return adaptation
 * itself, which keeps the call sites free of Kotlin's signature-polymorphic call shape. Process
 * launches are rare, so the adaptation cost is irrelevant.
 */
private fun MethodHandle.callInt(vararg arguments: Any): Int = invokeWithArguments(*arguments) as Int

private fun MethodHandle.callSegment(vararg arguments: Any): MemorySegment =
    invokeWithArguments(*arguments) as MemorySegment
