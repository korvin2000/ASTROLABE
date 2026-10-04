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
import io.astrolabe.provider.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-B7: what the runner needs for long scenarios — a repository without a commit, a message mid-run, two sessions of one work. */
class ScenarioTest {
    @TempDir
    lateinit var dir: Path

    /** A small task without Python: the acceptance is `git --version`, the hidden parts are placeholders. */
    private fun task(baseCommit: Boolean = true, message: MessageSpec? = null, reopen: ReopenSpec? = null): BenchTask {
        val taskDir = dir.resolve("tasks").resolve("scenario")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        taskDir.resolve("base").resolve("README.md").writeText("# notes\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        return BenchTask(
            "scenario", "long", "Scenario", "Add a second line to notes.txt.", taskDir, AcceptanceSpec(listOf("git", "--version")),
            hidden, hidden, hidden, null, baseCommit, message, reopen,
        )
    }

    private fun plan(task: BenchTask) = BenchPlan(
        tasks = listOf(task), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
        out = dir.resolve("out"), temp = dir.resolve("tmp"), maxCells = 2, deadline = Duration.ofMinutes(5),
    )

    private fun bench(plan: BenchPlan, adapter: FakeAdapter): RunResult {
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }
        return Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()
    }

    private fun workspace(plan: BenchPlan): Path =
        Files.list(plan.temp).use { runs -> runs.filter { it.fileName.toString().startsWith("run-") }.toList() }.single().resolve("workspace")

    private fun text(request: Request): String = request.items.filterIsInstance<Message>().joinToString("\n") { it.text }

    private val done = Scripted.Reply(listOf(Message.text(Role.Assistant, "done")))

    /** Reads the base at every call until [finish] says the attempt may end; a cap keeps a script that never finishes bounded. */
    private fun reading(calls: AtomicInteger, finish: (Request, Int) -> Boolean): FakeAdapter = FakeAdapter(ScriptedModel(listOf(
        ScriptedModel.Turn({ true }, { request ->
            val n = calls.incrementAndGet()
            if (n > CALL_CAP || finish(request, n)) done
            else Scripted.Reply(listOf(ToolCall("c$n", "look", """{"what":"read","target":"${if (n % 2 == 0) "notes.txt" else "README.md"}"}""")))
        }, once = false),
    )))

    @Test
    fun `a repository without a commit stays without one and the diff is taken against its base content`() {
        val plan = plan(task(baseCommit = false))
        val commits = CopyOnWriteArrayList<Boolean>()
        val adapter = FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, {
                val workspace = workspace(plan)
                commits += GitRepo.hasCommit(workspace)
                workspace.resolve("notes.txt").writeText("first line\nsecond line\n")
                done
            }, once = false),
        )))

        val result = bench(plan, adapter)

        assertNull(result.failure, result.failure)
        assertNotNull(result.outcome)
        assertEquals(false, result.baseCommit)
        assertTrue(commits.isNotEmpty() && commits.none { it }, "the workspace had no commit while the agent worked: $commits")
        assertEquals(1, result.changedFiles)
        val diff = PlannedRun(result.order, plan.tasks.single(), result.model, result.repeat).dir(plan.out).resolve("workspace.diff").readText()
        assertTrue("notes.txt" in diff && "+second line" in diff && "README.md" !in diff, diff)
    }

    @Test
    fun `a message sent while the agent works reaches the running campaign's contract`() {
        val message = MessageSpec(2, "Also keep the first line unchanged.")
        val plan = plan(task(message = message))
        val calls = AtomicInteger()
        val seen = CopyOnWriteArrayList<Int>()
        val adapter = reading(calls) { request, n -> (message.text in text(request)).also { if (it) seen += n } || n > 6 }

        val result = bench(plan, adapter)

        assertNull(result.failure, result.failure)
        assertNotNull(result.outcome)
        val delivered = assertNotNull(result.message)
        // B5 (B7 review P2-2): the measured count when the message reached the contract, not the script's number.
        val at = assertNotNull(delivered.deliveredAt, "$delivered")
        assertTrue(at >= 2, "$delivered")
        assertTrue(assertNotNull(delivered.contractVersion) > 1, "$delivered")
        assertTrue(seen.isNotEmpty() && seen.none { it <= at }, "the model had the message only after it was delivered at $at: $seen")
        assertTrue(assertNotNull(result.totals).modelResponses > 2)
    }

    @Test
    fun `a task in two sessions closes the work and opens the same work in the same store again`() {
        val plan = plan(task(reopen = ReopenSpec(2)))
        val calls = AtomicInteger()
        // The first session never ends on its own; a cell that starts without tool results after the close ends the work.
        val adapter = reading(calls) { request, n -> n > 3 && request.items.none { it is ToolResult } }

        val result = bench(plan, adapter)

        assertNull(result.failure, result.failure)
        val reopen = assertNotNull(result.reopen)
        assertEquals(2, reopen.segments.size, "$reopen")
        val (first, second) = reopen.segments
        // B5 (B7 review P2-2): the measured responses of the first session when the close ended it, the cancelled call included.
        assertTrue(assertNotNull(reopen.closedAt) >= 2, "$reopen")
        assertEquals(assertNotNull(first.totals).modelResponses, reopen.closedAt, "$reopen")
        assertNull(first.outcome, "a closed session has no outcome: $first")
        assertEquals(StudioAttempt.CLOSED, first.reason)
        assertTrue(first.workId != null && first.workId == second.workId, "one work across both sessions: $reopen")
        assertEquals(1, first.openedContractVersion, "the first session created the work")
        assertTrue(assertNotNull(second.openedContractVersion) >= 2, "the second session found the contract the first one stored: $second")
        assertNull(second.failure, second.failure)
        assertNotNull(second.outcome, "$second")
        assertEquals(second.workId, result.workId)
        assertEquals(second.outcome, result.outcome)
        assertEquals(reopen.segments.sumOf { it.totals!!.modelResponses }, assertNotNull(result.totals).modelResponses)
    }

    @Test
    fun `a close that comes after the run ended on its own keeps the outcome`() = runBlocking {
        // B5 (B7 review P2-1): the close reaches a run that has already ended.
        val ended = CompletableDeferred("completed")
        val late = SessionClose()
        late.request(ended)
        assertEquals("completed", late.await(ended))
        assertFalse(late.closed, "a run that ended first was not closed")

        val running = CompletableDeferred<String>()
        val close = SessionClose()
        close.request(running)
        assertNull(close.await(running))
        assertTrue(close.closed)

        val stopped = CompletableDeferred<String>().also { it.cancel() }
        assertFailsWith<CancellationException> { SessionClose().await(stopped) }
        Unit
    }

    private companion object {
        const val CALL_CAP = 40
    }
}
