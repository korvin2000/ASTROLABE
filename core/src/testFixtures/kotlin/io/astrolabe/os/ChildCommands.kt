package io.astrolabe.os

/**
 * Portable child commands for OS-adapter tests (P0.6.1).
 *
 * Every command uses only what both targets always ship — `cmd.exe` and `powershell.exe` on
 * Windows, `/bin/sh` and `sleep` on POSIX — so no OS test is skipped for a missing interpreter
 * (D-12: Windows and Linux are equal targets).
 */
public object ChildCommands {

    /** Marker the grandchild launchers print so a test can find the descendant's pid. */
    public const val GRANDCHILD_MARKER: String = "GRANDCHILD="

    private val GRANDCHILD_PATTERN = Regex("""GRANDCHILD=(\d+)""")

    /** True on Windows; POSIX otherwise. macOS is untested and unsupported (D-12). */
    public val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Prints [line] and exits 0. */
    public fun print(line: String): Command =
        Command.Shell(if (isWindows) "echo $line" else "echo $line")

    /** Prints [line] through an argument vector rather than a shell wrapper. */
    public fun printViaArgv(line: String): Command = Command.Argv(
        if (isWindows) listOf("cmd.exe", "/d", "/s", "/c", "echo $line")
        else listOf("/bin/sh", "-c", "echo $line"),
    )

    /** Prints [first], waits [gapSeconds], prints [second], exits 0. */
    public fun printTwice(first: String, second: String, gapSeconds: Int): Command = Command.Shell(
        if (isWindows) "echo $first&${idleCommand(gapSeconds)}&echo $second"
        else "echo $first; sleep $gapSeconds; echo $second",
    )

    /** Exits with [code] without writing anything. */
    public fun exitWith(code: Int): Command =
        Command.Shell(if (isWindows) "exit /b $code" else "exit $code")

    /** Stays alive for about [seconds] and writes nothing at all. */
    public fun sleep(seconds: Int): Command =
        Command.Shell(if (isWindows) idleCommand(seconds) else "sleep $seconds")

    /**
     * Launches a long-lived grandchild, prints `GRANDCHILD=<pid>`, then stays alive for [seconds].
     * The grandchild is a separate process tree node, so terminating only the root would leave it
     * running — which is exactly what job-object and process-group ownership must prevent.
     */
    public fun spawnGrandchildThenSleep(seconds: Int): Command =
        grandchildCommand(followUp = seconds)

    /** Launches a long-lived grandchild, prints `GRANDCHILD=<pid>` and exits immediately. */
    public fun spawnGrandchildThenExit(): Command = grandchildCommand(followUp = 0)

    /** The pid printed by [spawnGrandchildThenSleep] / [spawnGrandchildThenExit], if present. */
    public fun grandchildPid(logText: String): Long? =
        GRANDCHILD_PATTERN.find(logText)?.groupValues?.get(1)?.toLong()

    /** Liveness by pid, the way a resumed harness would check it. */
    public fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    /** Waits for [pid] to disappear; false when it is still alive after [timeoutMillis]. */
    public fun awaitPidGone(pid: Long, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!isAlive(pid)) return true
            Thread.sleep(25L)
        }
        return !isAlive(pid)
    }

    /** Silent busy-wait: `ping` is the only sleep Windows ships in every edition. */
    private fun idleCommand(seconds: Int): String = "ping -n ${seconds + 1} 127.0.0.1 >NUL"

    private fun grandchildCommand(followUp: Int): Command = if (isWindows) {
        // Single quotes only: the whole script is one MSVCRT-quoted argument, so embedding double
        // quotes here would need escaping that powershell.exe parses differently from the CRT.
        val script = buildString {
            append("\$p = Start-Process -FilePath cmd.exe ")
            append("-ArgumentList '/d','/s','/c','${idleCommand(600)}' -NoNewWindow -PassThru; ")
            append("Write-Output ('$GRANDCHILD_MARKER' + \$p.Id)")
            if (followUp > 0) append("; Start-Sleep -Seconds $followUp")
        }
        Command.Argv(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script))
    } else {
        val script = buildString {
            append("sleep 600 >/dev/null 2>&1 & echo $GRANDCHILD_MARKER\$!")
            if (followUp > 0) append("; sleep $followUp")
        }
        Command.Shell(script)
    }
}
