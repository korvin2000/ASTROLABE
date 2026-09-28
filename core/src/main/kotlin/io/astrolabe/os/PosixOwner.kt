package io.astrolabe.os

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Each Linux launch has an isolated kernel subreaper; detached descendants remain its children. */
internal class PosixOwner : ProcessOwner {
    override val essentialEnvironmentNames: Set<String> = setOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR")

    override fun start(start: OwnedStart): OwnedProcess {
        if (System.getProperty("os.name") != "Linux") throw OsFailure("subreaper", 0, "only Linux and Windows process ownership are supported")
        val helper = LinuxSubreaper::class.java
        val location = helper.protectionDomain.codeSource?.location
            ?: throw IOException("cannot locate the Linux process supervisor")
        require(location.protocol == "file") { "the Linux process supervisor needs a local class or jar" }
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms8m", "-Xmx32m", "-XX:+UseSerialGC", "--enable-native-access=ALL-UNNAMED",
            "-cp", Path.of(location.toURI()).toString(), helper.name,
        ).directory(start.workingDirectory.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(start.logPath.toFile()))
            .apply {
                // The helper decodes -cp and PATH with the locale's charset; the target gets only its own envp.
                val env = environment()
                env.clear()
                LOCALE_VARIABLES.forEach { name -> System.getenv(name)?.let { env[name] = it } }
                if (env.isEmpty()) env["LC_ALL"] = "C.UTF-8"
            }.start()
        val input = DataInputStream(process.inputStream)
        val output = DataOutputStream(process.outputStream)
        val startup = FutureTask {
            val argv = when (val command = start.command) {
                is Command.Argv -> command.argv
                is Command.Shell -> listOf("/bin/sh", "-c", command.commandLine)
            }
            fun string(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            string(start.logPath.toString())
            output.writeInt(argv.size)
            argv.forEach(::string)
            output.writeInt(start.environment.size)
            start.environment.forEach { (key, value) -> string("$key=$value") }
            output.flush()
            check(input.readInt() == LinuxSubreaper.STARTED) { "Linux supervisor refused the launch; see ${start.logPath}" }
        }
        Thread.ofPlatform().daemon().name("astrolabe-linux-start").start(startup)
        try {
            startup.get(30, TimeUnit.SECONDS)
            return SubreaperProcess(process, input)
        } catch (failure: Exception) {
            // TERM runs the helper's cleanup hook; never SIGKILL a live owner of detached descendants.
            process.destroy()
            startup.cancel(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw IOException("Linux process launch failed: ${failure.cause?.message ?: failure.message}", failure)
        }
    }

    override fun terminateUnowned(pid: Long): Boolean = ProcessHandle.of(pid).map { it.destroy() }.orElse(false)

    private companion object {
        val LOCALE_VARIABLES = listOf("LANG", "LC_ALL", "LC_CTYPE")
    }
}

/** Completion is acknowledged only after waitpid reports ECHILD in the isolated subreaper. */
private class SubreaperProcess(private val process: Process, private val input: DataInputStream) : OwnedProcess {
    override val pid: Long = process.pid()
    override val startEpochMillis: Long? = process.info().startInstant().orElse(null)?.toEpochMilli()
    private val rootExit = java.util.concurrent.CompletableFuture<Int>()
    private val quiescent = java.util.concurrent.CompletableFuture<Unit>()

    init {
        Thread.ofPlatform().daemon().name("astrolabe-linux-replies-$pid").start {
            try {
                check(input.readInt() == LinuxSubreaper.ROOT_EXIT) { "missing root exit acknowledgment" }
                rootExit.complete(input.readInt())
                check(input.readInt() == LinuxSubreaper.FINISHED) { "missing descendant cleanup acknowledgment" }
                quiescent.complete(Unit)
            } catch (failure: Exception) {
                rootExit.completeExceptionally(failure)
                quiescent.completeExceptionally(failure)
            }
        }
    }

    override fun awaitExit(timeoutMillis: Long): Boolean = try {
        rootExit.get(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        true
    } catch (_: java.util.concurrent.TimeoutException) { false }

    override fun exitCode(): Int = rootExit.getNow(null)
        ?: throw OsFailure("subreaper", 0, "root exit not yet observed")

    override fun terminateTree() { process.outputStream.close() }

    override fun awaitTreeExit(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return try {
            quiescent.get(timeoutMillis, TimeUnit.MILLISECONDS)
            process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS) && process.exitValue() == 0
        } catch (_: java.util.concurrent.ExecutionException) { false }
          catch (_: java.util.concurrent.TimeoutException) { false }
    }

    override fun close() {
        terminateTree()
        if (process.isAlive && !process.waitFor(12, TimeUnit.SECONDS)) process.destroy()
        input.close()
        process.errorStream.close()
    }
}
