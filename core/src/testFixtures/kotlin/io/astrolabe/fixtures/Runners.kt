package io.astrolabe.fixtures

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

/** What a fixture runner printed and how it ended (TODO P0.6.4). */
public data class RunOutput(
    public val exitCode: Int,
    public val stdout: String,
    public val stderr: String,
    public val timedOut: Boolean,
) {
    /** Both streams, for assertions that do not care which one carried a line. */
    public val combined: String
        get() = if (stderr.isEmpty()) stdout else stdout + stderr

    public val succeeded: Boolean
        get() = exitCode == 0 && !timedOut
}

/**
 * Locates the language runners the fixture repositories need and runs them (TODO P0.6.4).
 *
 * This is a *test* helper: it spawns with [ProcessBuilder] and kills a timed-out tree best effort.
 * The harness's own process ownership (job objects / process groups, `Proc` identity, log cursors)
 * is P0.6.1 and is deliberately not reused here — a fixture must not depend on the code under test.
 *
 * Every lookup returns `null` rather than throwing, so a test can skip visibly
 * (`Assumptions.assumeTrue(Runners.node() != null)`) instead of failing or passing silently.
 * Results are probed once per JVM.
 */
public object Runners {

    private val windows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")

    private val python: String? by lazy {
        firstThatRuns(listOf("python", "python3", "py"), listOf("--version")) {
            it.contains("Python 3")
        }
    }

    private val pytest: Boolean by lazy {
        val interpreter = python ?: return@lazy false
        probe(listOf(interpreter, "-m", "pytest", "--version"))?.succeeded == true
    }

    private val node: String? by lazy {
        firstThatRuns(listOf("node"), listOf("--version")) { NODE_VERSION.containsMatchIn(it) }
    }

    private val gradle: String? by lazy {
        firstThatRuns(gradleCandidates(), listOf("--version")) { it.contains("Gradle ") }
    }

    /** The `python` on this host, preferring `python`, then `python3`, then `py`. */
    public fun python(): String? = python

    /** Whether the [python] interpreter can run pytest; it is absent on hosts with no install. */
    public fun pytest(): Boolean = pytest

    /** The `node` on this host. */
    public fun node(): String? = node

    /**
     * A Gradle launcher: `GRADLE_HOME/bin`, then `gradle` on `PATH`, then the newest distribution
     * the wrapper has unpacked under `GRADLE_USER_HOME` (default `~/.gradle`). The last one is
     * what makes `gradle-small` runnable on a machine and a CI runner that only ever use
     * `./gradlew` — the outer build unpacks its own distribution before any test runs.
     */
    public fun gradle(): String? = gradle

    /**
     * Runs [argv] in [cwd] and returns everything it printed.
     *
     * `stdout` and `stderr` stay separate (a runner's diagnostics are not its report) and are
     * drained on their own threads, because a chatty runner fills one pipe and blocks while the
     * other is being read. On timeout the descendants are destroyed before the process itself.
     */
    public fun run(
        argv: List<String>,
        cwd: Path,
        timeoutSeconds: Long = 120,
        env: Map<String, String> = emptyMap(),
    ): RunOutput {
        require(argv.isNotEmpty()) { "argv must name a program" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive, got $timeoutSeconds" }
        val builder = ProcessBuilder(argv).directory(cwd.toFile())
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        val out = Drain(process.inputStream)
        val err = Drain(process.errorStream)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            destroyTree(process)
            process.waitFor(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return RunOutput(
            exitCode = if (finished) process.exitValue() else TIMED_OUT_EXIT_CODE,
            stdout = out.await(),
            stderr = err.await(),
            timedOut = !finished,
        )
    }

    // -------------------------------------------------------------- probing

    private fun firstThatRuns(
        candidates: List<String>,
        versionArgs: List<String>,
        accept: (String) -> Boolean,
    ): String? = candidates.firstOrNull { candidate ->
        val output = probe(listOf(candidate) + versionArgs)
        output != null && output.succeeded && accept(output.combined)
    }

    private fun probe(argv: List<String>): RunOutput? = try {
        run(argv, workingDirectory(), timeoutSeconds = PROBE_TIMEOUT_SECONDS)
    } catch (_: IOException) {
        null // not on PATH
    }

    private fun workingDirectory(): Path = Paths.get("").toAbsolutePath()

    private fun gradleCandidates(): List<String> {
        val executable = if (windows) "gradle.bat" else "gradle"
        val candidates = mutableListOf<String>()
        System.getenv("GRADLE_HOME")?.let { home ->
            val launcher = Paths.get(home, "bin", executable)
            if (Files.isRegularFile(launcher)) candidates += launcher.toString()
        }
        candidates += executable
        candidates += unpackedDistributions(executable)
        return candidates
    }

    private fun unpackedDistributions(executable: String): List<String> {
        val gradleUserHome = System.getenv("GRADLE_USER_HOME")?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".gradle")
        val dists = gradleUserHome.resolve("wrapper").resolve("dists")
        if (!Files.isDirectory(dists)) return emptyList()
        // wrapper/dists/gradle-<version>-<kind>/<hash>/gradle-<version>/bin/<executable>
        return try {
            Files.find(dists, DIST_DEPTH, { path, attributes ->
                attributes.isRegularFile &&
                    path.fileName.toString() == executable &&
                    path.parent?.fileName?.toString() == "bin"
            }).use { found ->
                found.asSequence()
                    .map(Path::toString)
                    .sortedWith(compareByDescending(VERSION_ORDER) { versionOf(it) })
                    .toList()
            }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** The `9.7.1` of `…/dists/gradle-9.7.1-bin/<hash>/gradle-9.7.1/bin/gradle`, else empty. */
    private fun versionOf(path: String): List<Int> =
        DIST_VERSION.find(path.replace('\\', '/'))
            ?.groupValues?.get(1)
            ?.split('.')
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()

    private fun destroyTree(process: Process) {
        runCatching { process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly) }
        process.destroyForcibly()
    }

    /** Reads one stream to exhaustion off-thread; [await] returns what it got. */
    private class Drain(stream: InputStream) {
        @Volatile
        private var text: String = ""

        private val thread = Thread({
            text = try {
                stream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            } catch (_: IOException) {
                ""
            }
        }, "astrolabe-fixture-drain").apply {
            isDaemon = true
            start()
        }

        fun await(): String {
            thread.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS))
            return text
        }
    }

    private const val PROBE_TIMEOUT_SECONDS = 30L
    private const val DRAIN_TIMEOUT_SECONDS = 10L
    private const val TIMED_OUT_EXIT_CODE = -1
    private const val DIST_DEPTH = 5
    private val NODE_VERSION = Regex("""^v\d+\.""", RegexOption.MULTILINE)
    private val DIST_VERSION = Regex("""/gradle-(\d+(?:\.\d+)*)/bin/""")
    private val VERSION_ORDER: Comparator<List<Int>> = Comparator { left, right ->
        val size = maxOf(left.size, right.size)
        var verdict = 0
        for (index in 0 until size) {
            verdict = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
            if (verdict != 0) break
        }
        verdict
    }
}
