package io.astrolabe.evallive

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan §9.2: every task's hidden acceptance fails on the unchanged base and passes on the reference solution. */
class TaskValidityTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `every task is sound - acceptance red on the base, green on the reference, visible tests green on the reference`() {
        val interpreters = Interpreters.detect()
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val tasks = BenchTask.all(Path.of(System.getProperty("evallive.tasks")))
        assertEquals(listOf("api-currency", "bugfix-pagination", "red-test", "rest-todo"), tasks.map { it.id })
        val acceptance = Acceptance(interpreters, temp)
        for (task in tasks) {
            val v = acceptance.validate(task, Acceptance.VISIBLE_TESTS)
            assertTrue(!v.onBase.passed, "${task.id}: the acceptance passes on the base\n${v.onBase.outputTail}")
            assertTrue(v.onReference.passed, "${task.id}: the acceptance fails on the reference\n${v.onReference.outputTail}")
            assertEquals(true, v.visibleGreenOnReference, "${task.id}: the visible tests fail on the reference")
        }
    }
}
