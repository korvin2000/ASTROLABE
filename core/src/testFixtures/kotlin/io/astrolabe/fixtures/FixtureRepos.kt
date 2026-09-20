package io.astrolabe.fixtures

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The fixture repositories shipped as resources under `fixtures/repos/` (TODO P0.6.4).
 *
 * [dir] is both the resource directory and the name used in reports.
 */
public enum class Fixture(public val dir: String) {
    /** Python package with passing tests, same-name tests in two modules, parameterized cases. */
    PythonSmall("python-small"),

    /** [PythonSmall] plus one pre-existing failure and one skip (P1.7.5 baseline ledger). */
    PythonFailing("python-failing"),

    /** TypeScript package run by Node's built-in runner, no install step. */
    TsSmall("ts-small"),

    /** One Gradle/Kotlin module, two test classes in two packages, JUnit XML output. */
    GradleSmall("gradle-small"),
}

/**
 * Materializes a [Fixture] into a committed, runnable [TempRepo] (TODO P0.6.4).
 *
 * Every later test (P1.3 atlas/sniff, P1.6.6 shaping parsers, P1.7.5 baseline ledger, P1.12.2
 * vertical slice) starts from the same shape: a repository whose only commit is `initial`, whose
 * working tree is clean, and whose tests run with the host's own toolchain and no network.
 *
 * The file list is a hand-written index resource rather than a directory walk, because the
 * resources are read from the test-fixtures jar as often as from a directory and a jar cannot be
 * enumerated by path. `FixtureReposIndexTest` keeps the index and the directory in agreement.
 */
public object FixtureRepos {

    /** The repository-relative paths [materialize] writes, in index order. */
    public fun files(fixture: Fixture): List<String> {
        val index = resourceText("$INDEX/${fixture.dir}.txt", fixture)
        val paths = index.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        val duplicates = paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(duplicates.isEmpty()) { "${fixture.dir} index lists ${duplicates.sorted()} twice" }
        return paths
    }

    /**
     * Writes [fixture] into a fresh repository under [into] (a temporary directory when `null`)
     * and commits it as `initial`. The caller closes the returned repository.
     */
    public fun materialize(fixture: Fixture, into: Path? = null): TempRepo {
        val repo = TempRepo.create(into)
        try {
            for (relative in files(fixture)) {
                repo.write(relative, bytes(fixture, relative))
            }
            repo.commit("initial")
        } catch (failure: Throwable) {
            repo.close()
            throw failure
        }
        return repo
    }

    /** The bytes [materialize] would write at the repository-relative [relative]. */
    public fun bytes(fixture: Fixture, relative: String): ByteArray =
        resourceBytes("$REPOS/${fixture.dir}/${resourceName(relative)}", fixture)

    /**
     * The resource name carrying the repository path [relative].
     *
     * Gradle's default resource excludes drop `.gitignore` (and the other dot-git files) from
     * every copy, so a fixture ships its dotfiles with a leading `_` and they regain their dot on
     * materialization; nothing else in this package uses the resource spelling.
     */
    public fun resourceName(relative: String): String =
        relative.split('/').joinToString("/") { segment ->
            if (segment.startsWith('.')) "_" + segment.substring(1) else segment
        }

    /**
     * The canonical runner argv for [fixture], to be run with the repository root as the working
     * directory. Python prefers pytest and falls back to stdlib `unittest` where pytest is not
     * installed; both discover the same `unittest.TestCase` classes.
     *
     * Throws [IllegalStateException] when the runner is absent — callers skip on
     * `Runners.python()`/`node()`/`gradle()` being `null` first, so a missing runner is visible.
     */
    public fun testCommand(fixture: Fixture): List<String> = when (fixture) {
        Fixture.PythonSmall, Fixture.PythonFailing -> {
            val python = Runners.python() ?: missing("python", fixture)
            if (Runners.pytest()) {
                listOf(python, "-m", "pytest", "-q")
            } else {
                listOf(python, "-m", "unittest", "discover", "-s", "tests", "-v")
            }
        }

        Fixture.TsSmall -> listOf(Runners.node() ?: missing("node", fixture), "--test")

        // --offline: every coordinate the fixture pins is already in the shared Gradle cache,
        // put there by the outer build that is running this very test (D-02 version boundary).
        Fixture.GradleSmall -> listOf(
            Runners.gradle() ?: missing("gradle", fixture),
            "test",
            "--offline",
            "--no-daemon",
            "-q",
            "--console=plain",
        )
    }

    /**
     * Runs [testCommand] in [repo], with the environment that fixture needs: the Gradle fixture
     * gets this JVM's own `JAVA_HOME`, so its JDK 26 toolchain resolves without a download.
     */
    public fun runTests(
        fixture: Fixture,
        repo: TempRepo,
        timeoutSeconds: Long = 300,
    ): RunOutput = Runners.run(
        argv = testCommand(fixture),
        cwd = repo.root,
        timeoutSeconds = timeoutSeconds,
        env = when (fixture) {
            Fixture.GradleSmall -> mapOf("JAVA_HOME" to System.getProperty("java.home"))
            else -> emptyMap()
        },
    )

    /**
     * The directory holding [fixture]'s files, or `null` when only a packaged copy is reachable.
     *
     * Gradle puts the *test-fixtures jar* on the test runtime classpath, so the classpath lookup
     * usually fails here; the source tree is then found relative to the test task's working
     * directory (the `core` project directory, or the repository root). Only the index test needs
     * this — materialization always reads the classpath, which is what ships.
     */
    public fun resourceDirectory(fixture: Fixture): Path? {
        val url = FixtureRepos::class.java.getResource("$REPOS/${fixture.dir}")
        if (url != null && url.protocol == "file") return Paths.get(url.toURI())
        return SOURCE_ROOTS
            .map { Paths.get(it).resolve(fixture.dir) }
            .firstOrNull(Files::isDirectory)
            ?.toAbsolutePath()
            ?.normalize()
    }

    private fun resourceBytes(path: String, fixture: Fixture): ByteArray =
        FixtureRepos::class.java.getResourceAsStream(path)?.use { it.readAllBytes() }
            ?: error("fixture ${fixture.dir} lists '$path', which is not on the classpath")

    private fun resourceText(path: String, fixture: Fixture): String =
        String(resourceBytes(path, fixture), StandardCharsets.UTF_8)

    private fun missing(runner: String, fixture: Fixture): Nothing =
        error("no '$runner' on this host, so ${fixture.dir} cannot run; skip on Runners.$runner()")

    private const val REPOS = "/fixtures/repos"
    private const val INDEX = "/fixtures/index"
    private val SOURCE_ROOTS = listOf(
        "src/testFixtures/resources/fixtures/repos",
        "core/src/testFixtures/resources/fixtures/repos",
    )
}
