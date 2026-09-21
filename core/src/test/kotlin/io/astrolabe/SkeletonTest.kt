package io.astrolabe

import io.astrolabe.fixtures.JavaConsumerSmoke
import io.astrolabe.fixtures.Runners
import io.astrolabe.fixtures.TestKit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class SkeletonTest {
    @Test
    fun `java test fixture compiles against core`() {
        assertEquals("astrolabe-core via Astrolabe", JavaConsumerSmoke.describe())
        assertEquals("astrolabe-core-testFixtures", TestKit.NAME)
    }

    /**
     * P0.1.2: the recorded mode of `gradlew` is what a POSIX CI runner executes. A checkout of
     * mode `100644` fails with `Permission denied` (exit 126) before any test starts, which is
     * invisible on Windows — so the invariant is asserted here rather than discovered in CI.
     */
    @Test
    fun `gradlew is recorded executable so a POSIX runner can launch it`() {
        val root = repositoryRoot()
        assumeTrue(root != null && Files.isDirectory(root.resolve(".git")), "not a git checkout")
        val listed = Runners.run(listOf("git", "ls-files", "-s", "gradlew"), root!!, timeoutSeconds = 30)
        assumeTrue(listed.succeeded, "git is unavailable: ${listed.stderr}")
        assertTrue(
            listed.stdout.startsWith("100755 "),
            "gradlew must be recorded with the executable bit; git reports: ${listed.stdout.trim()}",
        )
    }

    /** The build root, found from the test's working directory (the module directory). */
    private fun repositoryRoot(): Path? = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
}
