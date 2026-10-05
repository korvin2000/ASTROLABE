package io.astrolabe.evallive

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WP-W0: the live task `real-dirty-repo` loads and its workspace is the §7.2 dirty repository, built offline. */
class DirtyTaskTest {
    @TempDir
    lateinit var temp: Path

    private val tasks: Path = Path.of(System.getProperty("evallive.tasks"))

    @Test
    fun `the dirty task registers and its workspace has untracked tooling, a big file and no gitignore`() {
        val task = BenchTask.select(tasks, listOf("real-dirty-repo")).single()
        assertEquals(DirtSpec("devtools", 1500, 20), task.dirt)
        assertFalse(Files.exists(task.base.resolve(".gitignore")))

        // As Bench.runOne lays it out: the base copied and committed, then the dirt written untracked.
        val workspace = temp.resolve("workspace")
        Trees.copy(task.base, workspace)
        val base = GitRepo.initWithBase(workspace)
        task.dirt!!.write(workspace)
        val untracked = GitRepo.changedFiles(workspace, base).filter { it.startsWith("devtools/") }
        assertTrue(untracked.size >= 1500, "untracked tool files: ${untracked.size}")
        assertTrue(Files.size(workspace.resolve("devtools/bundle.bin")) >= 20L * 1024 * 1024)

        // The visible tests write their report into the tree.
        val interpreters = Interpreters.detect()
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val result = Proc.run(Acceptance.VISIBLE_TESTS.map(interpreters::expand), workspace, Duration.ofMinutes(2))
        assertEquals(0, result.exitCode, result.output)
        assertTrue(Files.exists(workspace.resolve("build/test-report.txt")))
    }
}
