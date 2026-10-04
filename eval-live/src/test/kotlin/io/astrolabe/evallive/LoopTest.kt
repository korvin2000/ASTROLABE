package io.astrolabe.evallive

import io.astrolabe.RunSpec
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.TaskLimits
import io.astrolabe.event.Events
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.Role
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-B5: the reference arm `loop` on the fake provider — it reaches the acceptance, stops on each task limit and is counted like the core. */
class LoopTest {
    @TempDir
    lateinit var dir: Path

    private fun binding(adapter: FakeAdapter) = ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() })

    /** Reads, edits, then finishes: the second line lands in `notes.txt`, which the hidden acceptance compares. */
    private fun solving(): FakeAdapter = FakeAdapter(ScriptedModel(listOf(
        ScriptedModel.Turn({ true }, { request ->
            when (request.items.count { it is ToolResult }) {
                0 -> Scripted.Reply(listOf(ToolCall("c1", "read", """{"path":"notes.txt"}""")))
                1 -> Scripted.Reply(listOf(ToolCall("c2", "edit", """{"path":"notes.txt","old":"first line\n","new":"first line\nsecond line\n"}""")))
                else -> Scripted.Reply(listOf(Message.text(Role.Assistant, "Added the second line.")))
            }
        }, once = false),
    )))

    /** Never finishes: reads `notes.txt` at every call. */
    private fun endless(): FakeAdapter {
        val n = AtomicInteger()
        return FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(ToolCall("c${n.incrementAndGet()}", "read", """{"path":"notes.txt"}"""))) }, once = false),
        )))
    }

    @Test
    fun `the loop arm brings the task to its acceptance with the core's cache points, effort and headroom`() {
        val task = LoopFixtures.task(dir)
        val plan = LoopFixtures.plan(dir, task, Arms.LOOP)
        val adapter = solving()

        val result = Bench(plan, ModelSource { binding(adapter) }, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()

        assertNull(result.failure, result.failure)
        assertEquals("loop", result.arm)
        assertEquals("completed", result.outcome, result.reason)
        assertEquals(true, result.acceptance?.passed, result.acceptance?.outputTail)
        assertEquals(VerificationSetup("review", "none", emptyList()), result.verification)
        val totals = assertNotNull(result.totals)
        assertEquals(3, totals.modelRequests)
        assertEquals(3, totals.turns)
        assertEquals(2, totals.toolCalls)
        assertEquals(0, totals.cellsStarted)
        assertNull(result.cells)
        val runDir = PlannedRun(result.order, task, result.model, result.repeat, "loop").dir(plan.out)
        assertEquals(result, Summary.read(runDir.resolve("result.json")))
        assertTrue("+second line" in runDir.resolve("workspace.diff").readText())

        // README rules 4 and 6: one session key, [S] and [T] closed by breakpoints, the run's effort and output headroom.
        val spec = plan.spec(FakeProfiles.main, dir.resolve("state"))
        val calls = adapter.calls
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.segmentKinds == listOf(SegmentKind.S, SegmentKind.T) && it.breakpoints == listOf(SegmentKind.S, SegmentKind.T) }, "$calls")
        assertEquals(1, calls.map { it.request.sessionKey }.toSet().size)
        assertNotNull(calls.first().request.sessionKey)
        assertTrue(calls.all { it.request.effort == spec.effort && it.request.maxOutputTokens == spec.outputHeadroom(FakeProfiles.main) })
        assertTrue(calls.drop(1).all { it.cacheReadTokens > 0 }, "the transcript only grows, so its prefix is read from cache: $calls")
    }

    /** Runs the loop on its own with [limits] and the clock [clock]; returns how it ended and its totals. */
    private fun limited(limits: TaskLimits, clock: Clock = Clock.systemUTC(), notes: String = "first line\n"): Pair<AttemptOutcome, Totals> {
        val workspace = Files.createDirectories(dir.resolve("ws").resolve("w"))
        workspace.resolve("notes.txt").writeText(notes)
        val events = Events(Clock.systemUTC())
        val recorder = Recorder(events, dir.resolve("events.jsonl"))
        try {
            val base = RunSpec.defaults(FakeProfiles.main, dir.resolve("state").toString())
            val spec = base.copy(policy = base.policy.copy(limits = limits))
            val outcome = runBlocking { LoopAttempt(clock, FixedIdGen()).run(workspace, "Read the notes.", binding(endless()), events, spec, Duration.ofMinutes(5)) }
            recorder.drain()
            return outcome to Totals.of(recorder.events(), FakeProfiles.main.priceTable)
        } finally {
            recorder.close()
            events.close()
        }
    }

    @Test
    fun `the loop stops before the request that would pass the requests limit`() {
        val (outcome, totals) = limited(TaskLimits(maxRequests = 3))

        assertEquals("budget_exhausted", outcome.outcome, outcome.reason)
        assertEquals("task_limit_requests", outcome.stopCode)
        assertEquals(3, totals.modelRequests)
        assertEquals(3, totals.modelResponses)
    }

    @Test
    fun `the loop stops before the call that could pass the money limit`() {
        // Each read brings a long file into the transcript, so the spend grows fast.
        val (outcome, totals) = limited(TaskLimits(maxCost = Money("USD", BigDecimal("0.40"))), notes = "a line of the notes\n".repeat(3_000))

        assertEquals("budget_exhausted", outcome.outcome, outcome.reason)
        assertEquals("task_limit_money", outcome.stopCode)
        assertTrue(totals.modelRequests in 1..30, "$totals")
        assertTrue(BigDecimal(assertNotNull(totals.cost)) <= BigDecimal("0.40"), "the accounted spend stays within the limit: $totals")
    }

    @Test
    fun `the loop stops when the active minutes run out`() {
        // Every reading of the clock is thirty seconds later.
        val clock = object : Clock() {
            private val ticks = AtomicInteger()
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = Instant.parse("2026-10-04T00:00:00Z").plusSeconds(30L * ticks.getAndIncrement())
        }

        val (outcome, totals) = limited(TaskLimits(maxMinutes = 2), clock)

        assertEquals("budget_exhausted", outcome.outcome, outcome.reason)
        assertEquals("task_limit_minutes", outcome.stopCode)
        assertTrue(totals.modelRequests in 1..4, "$totals")
    }

    @Test
    fun `the accounting of the loop and of the core arm has the same fields in result json`() {
        val task = LoopFixtures.task(dir)
        val core = LoopFixtures.plan(dir, task, Arms.DEFAULT)
        // The core arm's agent: the edit lands during the call, then it reports done.
        val coreAdapter = FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, {
                LoopFixtures.workspace(core).resolve("notes.txt").writeText(LoopFixtures.EXPECTED)
                Scripted.Reply(listOf(Message.text(Role.Assistant, "done")))
            }, once = false),
        )))
        val byCore = Bench(core, ModelSource { binding(coreAdapter) }, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()
        val loop = core.copy(arm = Arms.LOOP)
        val byLoop = Bench(loop, ModelSource { binding(solving()) }, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()

        for (result in listOf(byCore, byLoop)) {
            assertNull(result.failure, result.failure)
            assertEquals(true, result.acceptance?.passed, "${result.arm}: ${result.acceptance?.outputTail}")
            val t = assertNotNull(result.totals, result.arm)
            assertTrue(t.modelRequests > 0 && t.modelRequests == t.modelResponses, "${result.arm}: $t")
            assertNotNull(t.uncachedInputTokens, "${result.arm}: $t")
            assertNotNull(t.outputTokens, "${result.arm}: $t")
            assertNotNull(t.cacheReadTokens, "${result.arm}: $t")
            assertNotNull(t.cost, "${result.arm}: $t")
            assertEquals("paid", t.costBasis, "${result.arm}: $t")
            assertEquals("USD", t.currency)
            assertEquals(mapOf("main" to t.modelResponses), t.profiles, "${result.arm}: $t")
        }
        assertEquals(listOf("default", "loop"), listOf(byCore.arm, byLoop.arm))
        // One results directory holds both arms side by side, each under its own key.
        assertTrue(Files.isRegularFile(PlannedRun(1, task, "fake-main", 1, "default").dir(core.out).resolve("result.json")))
        assertTrue(Files.isRegularFile(PlannedRun(1, task, "fake-main", 1, "loop").dir(core.out).resolve("result.json")))
        assertEquals(byCore.key?.copy(arm = "loop", config = "x"), byLoop.key?.copy(config = "x"))
    }
}

/** A small task without Python: the hidden acceptance compares `notes.txt` with the expected two lines (`git diff --no-index`). */
internal object LoopFixtures {
    const val EXPECTED: String = "first line\nsecond line\n"

    fun task(dir: Path, prompt: String = "Add the line 'second line' after the first line of notes.txt."): BenchTask {
        val taskDir = dir.resolve("tasks").resolve("notes")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        taskDir.resolve("base").resolve("README.md").writeText("# notes\n")
        val hidden = HiddenFiles.of(mapOf("notes.txt" to EXPECTED.toByteArray()))
        val acceptance = AcceptanceSpec(listOf("git", "diff", "--no-index", "--quiet", "--", "${Acceptance.HIDDEN}/notes.txt", "notes.txt"))
        return BenchTask("notes", "bugfix", "Notes", prompt, taskDir, acceptance, hidden, hidden, hidden)
    }

    fun plan(dir: Path, task: BenchTask, arm: Arm) = BenchPlan(
        tasks = listOf(task), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
        out = dir.resolve("out"), temp = dir.resolve("tmp"), maxCells = 2, deadline = Duration.ofMinutes(5), arm = arm,
    )

    fun workspace(plan: BenchPlan): Path =
        Files.list(plan.temp).use { runs -> runs.filter { it.fileName.toString().startsWith("run-") }.toList() }.single().resolve("workspace")
}
