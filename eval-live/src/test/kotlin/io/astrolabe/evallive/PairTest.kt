package io.astrolabe.evallive

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plan §9.2 (pairs): the second task of a pair runs after its first in one working directory and one store, and only it is measured. */
class PairTest {
    @TempDir
    lateinit var dir: Path

    /** A small task without Python: the acceptance is `git --version`, the hidden parts are placeholders. */
    private fun task(id: String, prompt: String, after: String? = null): BenchTask {
        val taskDir = dir.resolve("tasks").resolve(id)
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("base\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        return BenchTask(id, "pair", id, prompt, taskDir, AcceptanceSpec(listOf("git", "--version")), hidden, hidden, hidden, after = after)
    }

    private fun text(request: Request): String = request.items.filterIsInstance<Message>().joinToString("\n") { it.text }

    private fun runDirs(plan: BenchPlan): List<Path> =
        Files.list(plan.temp).use { runs -> runs.filter { it.fileName.toString().startsWith("run-") }.toList() }

    private fun commits(workspace: Path): String =
        Proc.run(listOf("git", "rev-list", "--count", "HEAD"), workspace, Duration.ofSeconds(30)).output.trim()

    @Test
    fun `the second task of a pair runs after the first in the same workspace and store and is measured alone`() {
        val first = task("first", "Add the line FIRST to notes.txt.")
        val second = task("second", "Add the line SECOND to notes.txt.", after = "first").pairedWith(first)
        val plan = BenchPlan(
            tasks = listOf(second), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
            out = dir.resolve("out"), temp = dir.resolve("tmp"), maxCells = 2, deadline = Duration.ofMinutes(5),
        )
        val seen = CopyOnWriteArrayList<String>()
        val adapter = FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, { request ->
                val run = runDirs(plan).single()
                val workspace = run.resolve("workspace")
                val notes = workspace.resolve("notes.txt")
                val which = if ("line SECOND" in text(request)) "second" else "first"
                seen += "$which ${run.fileName} ${Files.isDirectory(run.resolve("state"))} ${commits(workspace)} ${notes.readText().trim().replace("\n", "+")}"
                if (which == "first") notes.writeText("base\nFIRST\n") else if ("SECOND" !in notes.readText()) notes.writeText(notes.readText() + "SECOND\n")
                Scripted.Reply(listOf(Message.text(Role.Assistant, "done")))
            }, once = false),
        )))
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }

        val result = Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()

        assertNull(result.failure, result.failure)
        assertEquals("second", result.task)
        val pair = assertNotNull(result.pair)
        assertEquals("first", pair.after)
        assertEquals(true, pair.firstAcceptance?.passed, "${pair.firstAcceptance}")
        val opening = assertNotNull(pair.first)
        assertTrue(opening.workId != null && result.workId != null, "$opening")
        assertNotEquals(opening.workId, result.workId, "each task of a pair is a work of its own")
        // One working directory and one state root for both; the first's result was committed before the second began.
        val firstCalls = seen.filter { it.startsWith("first ") }
        val secondCalls = seen.filter { it.startsWith("second ") }
        assertTrue(firstCalls.isNotEmpty() && secondCalls.isNotEmpty(), "$seen")
        assertEquals(firstCalls + secondCalls, seen.toList(), "the first task runs to its end before the second")
        assertEquals(1, seen.map { it.split(' ')[1] }.distinct().size, "one run directory: $seen")
        assertTrue(seen.all { it.split(' ')[2] == "true" }, "one state root, there for both: $seen")
        assertTrue(firstCalls.all { it.split(' ')[3] == "1" } && secondCalls.all { it.split(' ')[3] == "2" }, "$seen")
        assertTrue(secondCalls.first().endsWith("base+FIRST"), "the second task finds what the first left: $seen")
        // Measured alone: totals, phases and the diff are the second task's.
        assertEquals(secondCalls.size.toLong(), assertNotNull(result.totals).modelResponses.toLong(), "$seen")
        assertEquals(firstCalls.size.toLong(), assertNotNull(opening.totals).modelResponses.toLong(), "$seen")
        assertEquals(1, assertNotNull(result.phases).opens, "${result.phases}")
        assertEquals(1, result.changedFiles)
        val diff = PlannedRun(result.order, second, result.model, result.repeat).dir(plan.out).resolve("workspace.diff").readText()
        assertTrue("+SECOND" in diff && "+FIRST" !in diff, diff)
    }

    @Test
    fun `a pair resolves its first task by id when the tasks load`() {
        val root = dir.resolve("set")
        for ((id, after) in listOf("one" to null, "two" to "one", "three" to "nobody")) {
            val taskDir = root.resolve(id)
            for (part in listOf("base") + BenchTask.HIDDEN_PARTS) {
                Files.createDirectories(taskDir.resolve(part))
                taskDir.resolve(part).resolve("f.txt").writeText(part)
            }
            taskDir.resolve("prompt.md").writeText("Do $id.")
            val afterField = after?.let { ""","after":"$it"""" } ?: ""
            taskDir.resolve("task.json").writeText("""{"id":"$id","class":"pair","title":"$id","acceptance":{"argv":["git","--version"]}$afterField}""")
        }
        val failure = assertFailsWith<IllegalArgumentException> { BenchTask.all(root) }
        assertTrue("nobody" in failure.message.orEmpty(), failure.message)

        Trees.delete(root.resolve("three"))
        val tasks = BenchTask.all(root).associateBy { it.id }
        assertEquals("one", tasks.getValue("two").first?.id)
        assertNull(tasks.getValue("one").first)
    }
}
