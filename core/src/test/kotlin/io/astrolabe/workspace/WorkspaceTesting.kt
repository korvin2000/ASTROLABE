package io.astrolabe.workspace

import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixtureSupport
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.LocalOs
import io.astrolabe.store.Store
import org.junit.jupiter.api.Assumptions
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal val FIXED_INSTANT: Instant = Instant.parse("2026-09-20T10:00:00Z")

internal val TEST_ENV: EnvFingerprint = EnvFingerprint.compute(
    EnvInputs(
        osName = "test-os",
        osArch = "test-arch",
        toolVersions = sortedMapOf("git" to "2.45.1", "python" to "3.12.4"),
        runnerPolicyId = "trusted-local/v1",
    ),
)

/**
 * A repository, a project store and the whole P1.2 workspace surface wired together, so a test
 * spends its lines on the behaviour under test rather than on assembly.
 */
internal class WorkspaceFixture private constructor(
    val repo: TempRepo,
    val store: Store,
    val os: LocalOs,
    val clock: FakeClock,
    env: EnvFingerprint,
) : AutoCloseable {

    val workspace: Workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
    val ids: Identities = Identities(work = WorkId("work-1"), attempt = AttemptId("attempt-1"))
    val registry: VersionRegistry = VersionRegistry(workspace)
    val stamper: Stamper = Stamper(workspace, env)
    val dirtyState: DirtyState = DirtyState(workspace, store.blobs, stamper, ids, clock)
    val preimages: Preimages = Preimages(workspace, store.blobs, ids, clock)

    fun shadowRef(): ShadowRef = ShadowRef(
        work = ids.work,
        attempt = ids.attempt,
        workspace = workspace,
        store = store,
        dirtyState = dirtyState,
        os = os,
        clock = clock,
    )

    /** Raw bytes of a repository-relative path; never through a decoder. */
    fun bytes(path: String): ByteArray = Files.readAllBytes(repo.resolve(path))

    fun digestOf(path: String): Digest = Digest.of(bytes(path))

    /** `git for-each-ref` over everything but this campaign's shadow refs. */
    fun userRefs(): List<String> = rawGit("for-each-ref", "--format=%(refname) %(objectname)")
        .lines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("refs/astrolabe/") }
        .sorted()

    /** The bytes of the user's index file, so a test can assert it was not touched at all. */
    fun indexBytes(): ByteArray {
        val index = repo.root.resolve(".git").resolve("index")
        return if (Files.exists(index)) Files.readAllBytes(index) else ByteArray(0)
    }

    fun rawGit(vararg argv: String): String {
        val command = listOf("git") + argv
        val builder = ProcessBuilder(command).directory(repo.root.toFile()).redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C"
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val process = builder.start()
        process.outputStream.close()
        val output = process.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        val exit = process.waitFor()
        check(exit == 0) { "git ${argv.joinToString(" ")} exited $exit: $output" }
        return output
    }

    override fun close() {
        runCatching { store.close() }
        runCatching { os.close() }
        runCatching { repo.close() }
    }

    companion object {
        /** A repository with one commit, a project store under [stateRoot] and a frozen clock. */
        fun create(stateRoot: Path, env: EnvFingerprint = TEST_ENV, build: (TempRepo) -> Unit = {}): WorkspaceFixture {
            val repo = TempRepo.create()
            try {
                repo.write("src/a.py", "def a():\n    return 1\n")
                repo.write("src/b.py", "def b():\n    return 2\n")
                repo.write("README.md", "# fixture\n")
                repo.commit("initial")
                build(repo)
                val clock = FakeClock(FIXED_INSTANT)
                val store = Store.open(stateRoot, repo.git, clock)
                return WorkspaceFixture(repo, store, LocalOs(clock), clock, env)
            } catch (failure: Throwable) {
                repo.close()
                throw failure
            }
        }
    }
}

/** Skips the test visibly when the host cannot provide the shape the fixture needs. */
internal fun requireSupported(support: FixtureSupport): Path = when (support) {
    is FixtureSupport.Created -> support.path
    is FixtureSupport.Unsupported -> {
        Assumptions.assumeTrue(false, support.reason)
        error("unreachable")
    }
}

/**
 * Creates a Windows directory junction at [link] pointing at [target] with `cmd /c mklink /J`, or
 * reports why the host refused. A junction needs no privilege, unlike a symlink, but `cmd` only
 * exists on Windows.
 */
internal fun createJunction(link: Path, target: Path): FixtureSupport {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        return FixtureSupport.Unsupported("junctions exist only on Windows")
    }
    return try {
        Files.createDirectories(target)
        link.parent?.let { Files.createDirectories(it) }
        val process = ProcessBuilder(
            "cmd",
            "/c",
            "mklink",
            "/J",
            link.toString(),
            target.toString(),
        ).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        val exit = process.waitFor()
        if (exit == 0 && Files.exists(link)) {
            FixtureSupport.Created(link)
        } else {
            FixtureSupport.Unsupported("mklink /J exited $exit: ${output.trim()}")
        }
    } catch (failure: Exception) {
        FixtureSupport.Unsupported("cannot create a junction here: ${failure.message}")
    }
}
