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

/**
 * POSIX [ProcessOwner]: `posix_spawnp` with `POSIX_SPAWN_SETSID`, so the child is a session and
 * process-group leader (`pgid == pid`) before it execs and the whole tree is reachable with
 * `kill(-pgid)` (D-43). Older libcs without `POSIX_SPAWN_SETSID` fall back to
 * `POSIX_SPAWN_SETPGROUP` with pgroup 0, which yields the same `pgid == pid`. Either way the
 * leadership is confirmed with `getpgid` before the launch is accepted, never assumed.
 *
 * Unlike a Windows job object, a POSIX session outlives the harness: a process orphaned by a
 * harness crash keeps running and resolves as running-but-unowned, then [ProcStatus.Lost] once it
 * disappears. Validated on Linux; other POSIX platforms are unsupported until tested (D-12).
 */
internal class PosixOwner : ProcessOwner {

    override val essentialEnvironmentNames: Set<String> = setOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR")

    override fun start(start: OwnedStart): OwnedProcess = Arena.ofConfined().use { arena ->
        val fileActions = arena.allocate(FILE_ACTIONS_BYTES, STRUCT_ALIGNMENT)
        val attributes = arena.allocate(SPAWNATTR_BYTES, STRUCT_ALIGNMENT)
        checkCall("posix_spawn_file_actions_init", Libc.fileActionsInit.callInt(fileActions))
        try {
            checkCall("posix_spawnattr_init", Libc.attrInit.callInt(attributes))
            try {
                configureSession(attributes)
                val chdir = Libc.fileActionsAddChdir
                    ?: throw OsFailure("posix_spawn_file_actions_addchdir_np", 0, "libc lacks addchdir_np (glibc >= 2.29 required)")
                checkCall("addchdir_np", chdir.callInt(fileActions, arena.allocateFrom(start.workingDirectory.toString())))
                checkCall(
                    "addopen(stdin)",
                    Libc.fileActionsAddOpen.callInt(fileActions, 0, arena.allocateFrom("/dev/null"), O_RDONLY, 0),
                )
                checkCall(
                    "addopen(stdout)",
                    Libc.fileActionsAddOpen.callInt(
                        fileActions, 1, arena.allocateFrom(start.logPath.toString()), O_WRONLY_CREAT_APPEND, LOG_MODE,
                    ),
                )
                // stderr shares stdout's open file description, so both streams append in order.
                checkCall("adddup2(stderr)", Libc.fileActionsAddDup2.callInt(fileActions, 1, 2))

                val (file, argv) = renderArgv(start.command)
                val pidSlot = arena.allocate(ValueLayout.JAVA_INT)
                val spawned = Libc.spawnp.callInt(
                    pidSlot,
                    arena.allocateFrom(file),
                    fileActions,
                    attributes,
                    cStringArray(arena, argv),
                    cStringArray(arena, start.environment.map { (name, value) -> "$name=$value" }),
                )
                if (spawned != 0) throw OsFailure("posix_spawnp", spawned, "posix_spawnp($file) failed: errno=$spawned")
                ownGroupLeader(pidSlot.get(ValueLayout.JAVA_INT, 0L).toLong())
            } finally {
                Libc.attrDestroy.callInt(attributes)
            }
        } finally {
            Libc.fileActionsDestroy.callInt(fileActions)
        }
    }

    override fun terminateUnowned(pid: Long): Boolean {
        // The group form only when the pid really leads that group — a previous harness created it
        // with SETSID. Otherwise `kill(-pid)` would reach a group this harness never launched.
        val target = if (groupIdOf(pid) == pid) -pid else pid
        return Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Libc.CAPTURE)
            Libc.kill.callInt(capture, target.toInt(), SIGKILL) == 0
        }
    }

    /**
     * Confirms the child really is its own session and group leader before anyone relies on
     * `kill(-pgid)`. A flag that a libc accepted but did not honour would otherwise turn tree
     * termination into a signal aimed at an unrelated process group, so a child that landed
     * elsewhere is killed and the launch refused (D-12: the mechanism is tested, not claimed).
     */
    private fun ownGroupLeader(pid: Long): OwnedProcess {
        val process = PosixProcess(pid)
        val group = groupIdOf(pid)
        // -1: the child is already gone (or a zombie we cannot query); containment is then moot.
        if (group == -1L || group == pid) return process
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Libc.CAPTURE)
            Libc.kill.callInt(capture, pid.toInt(), SIGKILL)
        }
        process.awaitExit(GROUP_CHECK_REAP_MILLIS)
        throw OsFailure(
            "posix_spawnp", 0,
            "child $pid landed in process group $group instead of leading its own; refusing a launch whose tree cannot be terminated",
        )
    }

    private fun groupIdOf(pid: Long): Long = Arena.ofConfined().use { arena ->
        val capture = arena.allocate(Libc.CAPTURE)
        Libc.getpgid.callInt(capture, pid.toInt()).toLong()
    }

    private fun configureSession(attributes: MemorySegment) {
        val withSession = Libc.attrSetFlags.callInt(attributes, POSIX_SPAWN_SETSID.toShort())
        if (withSession == 0) return
        // glibc < 2.26 rejects the flag; a new process group gives the same kill(-pgid) reach.
        checkCall("posix_spawnattr_setflags", Libc.attrSetFlags.callInt(attributes, POSIX_SPAWN_SETPGROUP.toShort()))
        checkCall("posix_spawnattr_setpgroup", Libc.attrSetPgroup.callInt(attributes, 0))
    }

    private fun renderArgv(command: Command): Pair<String, List<String>> = when (command) {
        is Command.Argv -> command.argv.first() to command.argv
        is Command.Shell -> "/bin/sh" to listOf("sh", "-c", command.commandLine)
    }

    private fun cStringArray(arena: Arena, values: List<String>): MemorySegment {
        val array = arena.allocate(ValueLayout.ADDRESS, (values.size + 1).toLong())
        values.forEachIndexed { index, value ->
            array.setAtIndex(ValueLayout.ADDRESS, index.toLong(), arena.allocateFrom(value))
        }
        array.setAtIndex(ValueLayout.ADDRESS, values.size.toLong(), MemorySegment.NULL)
        return array
    }

    /** The `posix_spawn*` family returns its error number directly; errno is not involved. */
    private fun checkCall(call: String, result: Int) {
        if (result != 0) throw OsFailure(call, result, "$call failed: errno=$result")
    }

    private companion object {
        /** glibc x86_64: `posix_spawnattr_t` is 336 bytes and `posix_spawn_file_actions_t` 80. */
        const val SPAWNATTR_BYTES = 512L
        const val FILE_ACTIONS_BYTES = 256L
        const val STRUCT_ALIGNMENT = 16L
        const val POSIX_SPAWN_SETPGROUP = 0x02
        const val POSIX_SPAWN_SETSID = 0x80
        const val O_RDONLY = 0
        const val O_WRONLY_CREAT_APPEND = 0x441 // O_WRONLY | O_CREAT | O_APPEND
        const val LOG_MODE = 0x1A4 // 0644
        const val SIGKILL = 9
        const val GROUP_CHECK_REAP_MILLIS = 2_000L
    }
}

/** A child in its own session; `kill(-pid)` reaches every descendant that did not re-`setsid`. */
private class PosixProcess(override val pid: Long) : OwnedProcess {

    override val startEpochMillis: Long? = null // taken from ProcessHandle, the same source a resumed harness reads

    @Volatile
    private var waitStatus: Int? = null

    override fun awaitExit(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            if (reap()) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return false
            Thread.sleep(minOf(REAP_INTERVAL_MILLIS, remaining / 1_000_000L + 1))
        }
    }

    override fun exitCode(): Int {
        val status = waitStatus ?: throw OsFailure("waitpid", 0, "exit code requested before the process exited")
        // WIFEXITED / WEXITSTATUS, else the shell convention 128 + signal.
        return if (status and 0x7F == 0) (status shr 8) and 0xFF else 128 + (status and 0x7F)
    }

    override fun terminateTree() {
        Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Libc.CAPTURE)
            // ESRCH means the group is already gone; the supervisor observes the exit regardless.
            Libc.kill.callInt(capture, (-pid).toInt(), SIGKILL)
        }
    }

    /** Nothing to release: the process was reaped by [awaitExit] and holds no inherited descriptor. */
    override fun close(): Unit = Unit

    private fun reap(): Boolean {
        if (waitStatus != null) return true
        return Arena.ofConfined().use { arena ->
            val capture = arena.allocate(Libc.CAPTURE)
            val status = arena.allocate(ValueLayout.JAVA_INT)
            val reaped = Libc.waitpid.callInt(capture, pid.toInt(), status, WNOHANG)
            when {
                reaped == pid.toInt() -> {
                    waitStatus = status.get(ValueLayout.JAVA_INT, 0L)
                    true
                }
                reaped == 0 -> false
                else -> {
                    val errno = capture.get(ValueLayout.JAVA_INT, Libc.ERRNO)
                    // EINTR is a spurious wakeup; anything else means the exit code is unobtainable.
                    if (errno == EINTR) false
                    else throw OsFailure("waitpid", errno, "waitpid($pid) failed: errno=$errno")
                }
            }
        }
    }

    private companion object {
        const val WNOHANG = 1
        const val SIGKILL = 9
        const val EINTR = 4
        const val REAP_INTERVAL_MILLIS = 20L
    }
}

/** libc bindings, private to this file. */
private object Libc {
    private val linker: Linker = Linker.nativeLinker()
    private val libc: SymbolLookup = linker.defaultLookup()

    val CAPTURE: StructLayout = Linker.Option.captureStateLayout()
    val ERRNO: Long = CAPTURE.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    val spawnp: MethodHandle = bind(
        "posix_spawnp",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    val fileActionsInit: MethodHandle = bind(
        "posix_spawn_file_actions_init",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val fileActionsDestroy: MethodHandle = bind(
        "posix_spawn_file_actions_destroy",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val fileActionsAddOpen: MethodHandle = bind(
        "posix_spawn_file_actions_addopen",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
        ),
    )
    val fileActionsAddDup2: MethodHandle = bind(
        "posix_spawn_file_actions_adddup2",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    val fileActionsAddChdir: MethodHandle? = bindOrNull(
        "posix_spawn_file_actions_addchdir_np",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    val attrInit: MethodHandle = bind(
        "posix_spawnattr_init",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val attrDestroy: MethodHandle = bind(
        "posix_spawnattr_destroy",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    val attrSetFlags: MethodHandle = bind(
        "posix_spawnattr_setflags",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT),
    )
    val attrSetPgroup: MethodHandle = bind(
        "posix_spawnattr_setpgroup",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    val kill: MethodHandle = bindCapturing(
        "kill",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    val getpgid: MethodHandle = bindCapturing(
        "getpgid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    val waitpid: MethodHandle = bindCapturing(
        "waitpid",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )

    private fun bind(name: String, descriptor: FunctionDescriptor): MethodHandle =
        bindOrNull(name, descriptor) ?: throw OsFailure(name, 0, "libc!$name not found")

    private fun bindCapturing(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        libc.find(name).orElseThrow { OsFailure(name, 0, "libc!$name not found") },
        descriptor,
        Linker.Option.captureCallState("errno"),
    )

    private fun bindOrNull(name: String, descriptor: FunctionDescriptor): MethodHandle? =
        libc.find(name).map { linker.downcallHandle(it, descriptor) }.orElse(null)
}

private fun MethodHandle.callInt(vararg arguments: Any): Int = invokeWithArguments(*arguments) as Int
