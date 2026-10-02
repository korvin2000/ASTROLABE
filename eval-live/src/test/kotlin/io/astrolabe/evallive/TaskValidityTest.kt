package io.astrolabe.evallive

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Plan §9.2, WP-B2: every task's hidden acceptance fails on the unchanged base, fails on the known-wrong patch and
 * passes on the reference solution; the hidden parts ship as packaged resources, never as open files.
 */
class TaskValidityTest {
    @TempDir
    lateinit var temp: Path

    private val tasks: Path = Path.of(System.getProperty("evallive.tasks"))

    @Test
    fun `every task is sound - acceptance red on the base and on the wrong patch, green on the reference, visible tests green on the reference`() {
        val interpreters = Interpreters.detect()
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val all = BenchTask.all(tasks)
        assertTrue(all.map { it.id }.containsAll(listOf("api-currency", "bugfix-pagination", "env-launcher", "interrupt-csv", "investigate-totals", "red-test", "rest-todo", "ui-clear-done")), all.map { it.id }.toString())
        val acceptance = Acceptance(interpreters, temp)
        for (task in all) {
            val v = acceptance.validate(task, Acceptance.VISIBLE_TESTS)
            assertTrue(!v.onBase.passed, "${task.id}: the acceptance passes on the base\n${v.onBase.outputTail}")
            assertTrue(!v.onWrong.passed, "${task.id}: the acceptance passes on the wrong patch\n${v.onWrong.outputTail}")
            assertTrue(v.onReference.passed, "${task.id}: the acceptance fails on the reference\n${v.onReference.outputTail}")
            assertEquals(true, v.visibleGreenOnReference, "${task.id}: the visible tests fail on the reference")
            assertTrue(v.sound)
        }
    }

    /** An installed task directory: only what the agent may see. */
    private fun installed(id: String): Path {
        val root = temp.resolve("installed")
        val dir = root.resolve(id)
        Files.createDirectories(dir)
        Files.copy(tasks.resolve(id).resolve("task.json"), dir.resolve("task.json"))
        Files.copy(tasks.resolve(id).resolve("prompt.md"), dir.resolve("prompt.md"))
        Trees.copy(tasks.resolve(id).resolve("base"), dir.resolve("base"))
        return root
    }

    @Test
    fun `an installed task without open hidden parts gets them from the packaged resources`() {
        val root = installed("bugfix-pagination")
        BenchTask.HIDDEN_PARTS.forEach { assertTrue(Files.notExists(root.resolve("bugfix-pagination").resolve(it))) }

        val packaged = BenchTask.select(root, listOf("bugfix-pagination")).single()
        val source = BenchTask.load(tasks.resolve("bugfix-pagination"))

        assertEquals(source.hidden.digest, packaged.hidden.digest)
        assertEquals(source.reference.digest, packaged.reference.digest)
        assertEquals(source.wrong.digest, packaged.wrong.digest)
        assertEquals(source.hidden.files.keys, packaged.hidden.files.keys)
        assertContentEquals(source.wrong.files.getValue("catalog/paging.py"), packaged.wrong.files.getValue("catalog/paging.py"))
    }

    @Test
    fun `a task without its wrong patch does not load`() {
        val root = temp.resolve("partial")
        val dir = root.resolve("bugfix-pagination")
        Trees.copy(tasks.resolve("bugfix-pagination"), dir, skipTop = setOf("wrong"))
        val failure = assertFailsWith<IllegalArgumentException> { BenchTask.load(dir) }
        assertTrue("wrong/" in failure.message.orEmpty(), failure.message)

        // Installed, with no bundle that knows the task: also a load error, never a task without its hidden parts.
        val bare = installed("bugfix-pagination").resolve("bugfix-pagination")
        assertFailsWith<IllegalArgumentException> { BenchTask.load(bare, HiddenBundle(ClassLoader.getPlatformClassLoader())) }
    }
}
