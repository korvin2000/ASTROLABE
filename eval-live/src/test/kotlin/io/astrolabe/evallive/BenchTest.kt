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
import io.astrolabe.provider.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The runner on the fake provider: a fresh workspace per run, the hidden acceptance out of the agent's reach, results on disk. */
class BenchTest {
    @TempDir
    lateinit var dir: Path

    private val tasks: Path = Path.of(System.getProperty("evallive.tasks"))
    private val interpreters = Interpreters.detect()

    private fun plan(repeats: Int) = BenchPlan(
        tasks = BenchTask.select(tasks, listOf("bugfix-pagination")), models = listOf("fake-main"), provider = "fake",
        repeats = repeats, seed = 7, out = dir.resolve("out"), temp = dir.resolve("tmp"), maxCells = 1, deadline = Duration.ofMinutes(5),
    )

    /** What the file system and the request looked like at one model call, while the agent was working. */
    private data class Seen(val runDirs: Set<String>, val hiddenPaths: List<String>, val requestMentionsHidden: Boolean)

    private fun observe(temp: Path, hiddenNames: Set<String>, request: Request): Seen {
        val paths = tree(temp)
        val runDirs = paths.filter { it.parent == temp && it.fileName.toString().startsWith("run-") }.map { it.fileName.toString() }.toSet()
        val hidden = paths.filter { it.fileName.toString() == Acceptance.HIDDEN || it.fileName.toString() in hiddenNames }.map { temp.relativize(it).toString() }
        val text = request.items.filterIsInstance<Message>().joinToString("\n") { it.text }
        return Seen(runDirs, hidden, Acceptance.HIDDEN in text || hiddenNames.any { it in text } || "test_reproducer_succeeds" in text)
    }

    /** Every path under [root]; entries that vanish while the campaign writes are skipped. */
    private fun tree(root: Path): List<Path> {
        val out = ArrayList<Path>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = FileVisitResult.CONTINUE.also { out.add(dir) }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = FileVisitResult.CONTINUE.also { out.add(file) }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return out
    }

    private fun script(onCall: (Request) -> Unit): FakeAdapter {
        val reply = Scripted.Reply(listOf(Message.text(Role.Assistant, "done, trust me")))
        return FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { request -> onCall(request); reply }, once = false))))
    }

    @Test
    fun `each run works in its own temporary workspace where the hidden acceptance never appears`() {
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val plan = plan(repeats = 2)
        val task = plan.tasks.single()
        val hiddenNames = task.hidden.files.keys.map { it.substringAfterLast('/') }.toSet()
        val seen = CopyOnWriteArrayList<Seen>()
        val adapter = script { seen += observe(plan.temp, hiddenNames, it) }
        val binds = AtomicInteger()
        val models = ModelSource { binds.incrementAndGet(); ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }

        val results = Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run()

        assertEquals(2, results.size)
        assertTrue(seen.isNotEmpty(), "the agent was called")
        assertTrue(seen.all { it.runDirs.size == 1 }, "one run directory at a time: $seen")
        assertEquals(2, seen.flatMap { it.runDirs }.toSet().size, "every run had its own directory")
        assertTrue(seen.all { it.hiddenPaths.isEmpty() }, "no hidden acceptance file while the agent works: $seen")
        assertTrue(seen.none { it.requestMentionsHidden }, "the agent's requests never name the hidden acceptance")
        assertTrue(tree(plan.temp).none { it != plan.temp }, "run and acceptance copies are removed: ${tree(plan.temp)}")

        for (result in results) {
            assertNull(result.failure, "the attempt ran through the controller: ${result.failure}")
            assertNotNull(result.outcome)
            // As in the Studio: the core sniffs the visible tests (`pyproject.toml` + `tests/`) and accepts against them.
            assertEquals(VerificationSetup("tests", "declared", listOf(listOf("python", "-m", "unittest", "discover", "-s", "tests"))), result.verification)
            val acceptance = assertNotNull(result.acceptance)
            assertEquals(false, acceptance.passed, "the base is unchanged, so its bug is still there")
            assertEquals(1, acceptance.exitCode)
            assertEquals(task.hidden.digest, result.acceptanceDigest)
            assertEquals(0, result.changedFiles)
            val totals = assertNotNull(result.totals)
            assertTrue(totals.modelRequests >= 1 && totals.modelResponses >= 1)
            assertNotNull(result.attemptWallMillis)
            val runDir = PlannedRun(result.order, task, result.model, result.repeat).dir(plan.out)
            assertEquals(result, Summary.read(runDir.resolve("result.json")))
            val events = runDir.resolve("events.jsonl").readLines().filter { it.isNotBlank() }
            assertTrue(events.size >= totals.modelRequests, "the whole event log is kept")
            assertTrue(events.all { "seq" in Json.parseToJsonElement(it).jsonObject })
            assertTrue(runDir.resolve("acceptance.log").readText().contains("FAIL"), "the acceptance output is kept")
        }
        assertEquals(listOf(1, 2), results.map { it.order }.sorted())
        val csv = plan.out.resolve("summary.csv").readLines().filter { it.isNotBlank() }
        assertEquals(listOf(Summary.COLUMNS.joinToString(",")), csv.take(1))
        assertEquals(3, csv.size)
        assertEquals(2, (Json.parseToJsonElement(plan.out.resolve("summary.json").readText()) as JsonArray).size)

        // A second bench over the same results directory keeps them and calls no model.
        assertEquals(results.toSet(), Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run().toSet())
        assertEquals(2, binds.get())
    }

    private fun text(request: Request): String = request.items.filterIsInstance<Message>().joinToString("\n") { it.text }

    /** The interrupt task with its interruption after [afterResponses] responses. */
    private fun interruptPlan(afterResponses: Int? = null): BenchPlan {
        val task = BenchTask.select(tasks, listOf("interrupt-csv")).single()
        val spec = assertNotNull(task.interrupt)
        val chosen = afterResponses?.let {
            BenchTask(task.id, task.kind, task.title, task.prompt, task.dir, task.acceptance, task.hidden, task.reference, task.wrong, InterruptSpec(it, spec.constraint))
        } ?: task
        return BenchPlan(
            tasks = listOf(chosen), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 3,
            out = dir.resolve("out"), temp = dir.resolve("tmp"), maxCells = 1, deadline = Duration.ofMinutes(5),
        )
    }

    /** Reads a different file of the base at every call, so the attempt never ends on its own; the follow-up finishes. */
    private fun interruptScript(constraint: String, followUps: MutableList<String>, endFirst: Boolean): FakeAdapter {
        val files = listOf("reports/export.py", "reports/weekly.py", "reports/daily.py", "tests/test_reports.py", "pyproject.toml")
        val calls = AtomicInteger()
        val done = Scripted.Reply(listOf(Message.text(Role.Assistant, "done")))
        return FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ constraint in text(it) }, { request -> followUps += text(request); done }, once = false),
            ScriptedModel.Turn({ true }, {
                if (endFirst) {
                    done
                } else {
                    val n = calls.incrementAndGet()
                    Scripted.Reply(listOf(ToolCall("c$n", "look", """{"what":"read","target":"${files[(n - 1) % files.size]}"}""")))
                }
            }, once = false),
        )))
    }

    @Test
    fun `an interrupted task is stopped after K responses and continued with the constraint as the Studio's follow-up`() {
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val plan = interruptPlan()
        val spec = assertNotNull(plan.tasks.single().interrupt)
        assertEquals(3, spec.afterResponses)
        val followUps = CopyOnWriteArrayList<String>()
        val adapter = interruptScript(spec.constraint, followUps, endFirst = false)
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }

        val result = Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run().single()

        assertNull(result.failure, result.failure)
        val interrupt = assertNotNull(result.interrupt)
        assertEquals(InterruptMode.CancelResume, interrupt.mode)
        assertEquals(3, interrupt.atResponse)
        assertEquals(2, interrupt.segments.size)
        val (first, second) = interrupt.segments
        assertEquals("cancelled", first.outcome, first.reason)
        assertTrue(assertNotNull(first.totals).modelResponses >= 3, "${first.totals}")
        assertTrue(first.workId != second.workId, "the follow-up is a new run of the same task")
        assertNotNull(second.outcome)
        assertEquals(second.workId, result.workId)
        assertEquals(second.outcome, result.outcome)

        val request = followUps.first()
        assertTrue("[Context from earlier in this task." in request && "Outcome: stopped by the user before it finished." in request, request)
        assertTrue(request.substringAfter("[End of context]").trim().startsWith(spec.constraint), request)

        val totals = assertNotNull(result.totals)
        assertEquals(interrupt.segments.sumOf { it.totals!!.modelResponses }, totals.modelResponses, "the run's totals are the sum of its segments")
        assertEquals(interrupt.segments.sumOf { it.totals!!.modelRequests }, totals.modelRequests)
        assertEquals(interrupt.segments.sumOf { it.attemptWallMillis!! }, result.attemptWallMillis)

        val runDir = PlannedRun(result.order, plan.tasks.single(), result.model, result.repeat).dir(plan.out)
        assertEquals(result, Summary.read(runDir.resolve("result.json")))
        assertTrue("\"mode\": \"cancelResume\"" in runDir.resolve("result.json").readText())
        assertEquals("cancelResume", plan.out.resolve("summary.csv").readLines().filter { it.isNotBlank() }.last().substringAfterLast(','))
    }

    @Test
    fun `an agent that ends before K responses gets the constraint as a follow-up`() {
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val plan = interruptPlan(afterResponses = 50)
        val spec = assertNotNull(plan.tasks.single().interrupt)
        val followUps = CopyOnWriteArrayList<String>()
        val adapter = interruptScript(spec.constraint, followUps, endFirst = true)
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }

        val result = Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run().single()

        val interrupt = assertNotNull(result.interrupt)
        assertEquals(InterruptMode.FollowUp, interrupt.mode)
        assertNull(interrupt.atResponse)
        assertEquals(2, interrupt.segments.size)
        assertTrue(interrupt.segments[0].outcome != "cancelled", "${interrupt.segments[0]}")
        val request = followUps.first()
        assertTrue("Outcome: stopped by the user" !in request && spec.constraint in request, request)
        assertEquals(interrupt.segments.sumOf { it.totals!!.modelResponses }, assertNotNull(result.totals).modelResponses)
    }

    @Test
    fun `the acceptance judges the workspace the attempt left`() {
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")
        val plan = plan(repeats = 1)
        val task = plan.tasks.single()
        // Stands in for an agent that fixed the bug: the reference solution lands in the workspace during the attempt.
        val adapter = script {
            val workspace = tree(plan.temp).single { p -> p.fileName.toString() == "workspace" && p.parent.parent == plan.temp }
            task.reference.write(workspace)
        }
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }

        val result = Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run().single()

        assertEquals(true, result.acceptance?.passed, result.acceptance?.outputTail)
        assertEquals(2, result.changedFiles, "the fix and its new test")
        val diff = PlannedRun(result.order, task, result.model, result.repeat).dir(plan.out).resolve("workspace.diff").readText()
        assertTrue("catalog/paging.py" in diff && "tests/test_last_page.py" in diff, diff)
    }
}
