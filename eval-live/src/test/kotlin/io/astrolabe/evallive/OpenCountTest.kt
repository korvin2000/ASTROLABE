package io.astrolabe.evallive

import io.astrolabe.RunSpec
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.event.Events
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.WorkId
import io.astrolabe.os.Git
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.store.Store
import io.astrolabe.telemetry.CountedPhase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-12 (WF-1): a start opens the campaign once, as the Studio does after W7 — the host notes and the declared checks go in with the first open. */
class OpenCountTest {
    @TempDir
    lateinit var dir: Path

    private val tasks: Path = Path.of(System.getProperty("evallive.tasks"))

    private fun plan(task: BenchTask) = BenchPlan(
        tasks = listOf(task), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
        // A short temp name: the core's recovery blob paths come close to the 260 characters git on Windows can open.
        out = dir.resolve("out"), temp = dir.resolve("t"), maxCells = 2, deadline = Duration.ofMinutes(5),
    )

    private fun bench(plan: BenchPlan, interpreters: Interpreters): RunResult {
        val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(Message.text(Role.Assistant, "done"))) }, once = false))))
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }
        return Bench(plan, models, interpreters, Clock.systemUTC(), FixedIdGen()).run().single()
    }

    @Test
    fun `the dirty task whose suite the core derives opens once and says its dirt is in the tree`() {
        val interpreters = Interpreters.detect()
        assumeTrue(runCatching { interpreters.expand(Interpreters.PYTHON) }.isSuccess, "no Python 3 on the PATH")

        val result = bench(plan(BenchTask.select(tasks, listOf("real-dirty-repo")).single()), interpreters)

        assertNull(result.failure, result.failure)
        assertEquals(VerificationSetup("tests", "declared", listOf(listOf("python", "-m", "unittest", "discover", "-s", "tests"))), result.verification)
        val phases = assertNotNull(result.phases)
        assertEquals(1, phases.opens, "one open for the start: $phases")
        assertEquals(DirtResult("devtools", inTree = true, written = true), result.dirt)
    }

    @Test
    fun `a project with nothing executable takes the review item through the declared checks and opens once`() {
        val taskDir = dir.resolve("tasks").resolve("review")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        val task = BenchTask("review", "long", "Review", "Add a second line to notes.txt.", taskDir, AcceptanceSpec(listOf("git", "--version")), hidden, hidden, hidden)

        val result = bench(plan(task), Interpreters.detect("python"))

        assertNull(result.failure, result.failure)
        assertEquals(VerificationSetup("review", "none", emptyList()), result.verification)
        val phases = assertNotNull(result.phases)
        assertEquals(1, phases.opens, "the review item is declared at the open, no amendment and no second open: $phases")
        assertNull(result.dirt)
    }

    @Test
    fun `the preflight reads the suites a new work is derived from and declares the review item only where there is none`() {
        val project = dir.resolve("project")
        Files.createDirectories(project.resolve("tests"))
        project.resolve("pyproject.toml").writeText("[project]\nname = \"p\"\nversion = \"0\"\n")
        project.resolve("tests").resolve("test_a.py").writeText("import unittest\n")
        val git = Proc.run(listOf("git", "init", "-q"), project, Duration.ofMinutes(1))
        assertEquals(0, git.exitCode, git.output)

        val suite = StudioPolicy.expected(project)
        assertEquals(VerificationSetup("tests", "declared", listOf(listOf("python", "-m", "unittest", "discover", "-s", "tests"))), suite)
        assertTrue(StudioPolicy.declaredChecks(suite).isEmpty(), "a suite the core derives needs no declared check")

        val bare = dir.resolve("bare")
        Files.createDirectories(bare)
        bare.resolve("notes.txt").writeText("x\n")
        Proc.run(listOf("git", "init", "-q"), bare, Duration.ofMinutes(1))
        val review = StudioPolicy.expected(bare)
        assertEquals(VerificationSetup("review", "none", emptyList()), review)
        val declared = StudioPolicy.declaredChecks(review).single()
        assertTrue(declared is Acceptance.Check && declared.text == StudioPolicy.REVIEW_TEXT, "$declared")
    }
    @Test
    fun `a reopen over a goal check the model added reads it by origin and opens once`() {
        // Review 5 P2 #1: a `run:` item of the model's with no sniffed suite behind it is `declared` by origin before the
        // open; read by matching sniffed suites after it, it was `saved`, and the notes that differed cost a second open.
        val workspace = dir.resolve("ws")
        Files.createDirectories(workspace)
        workspace.resolve("notes.txt").writeText("first line\n")
        GitRepo.initWithBase(workspace)
        val clock = Clock.systemUTC()
        val idGen = FixedIdGen()
        val stateRoot = dir.resolve("state")
        val spec = RunSpec.defaults(FakeProfiles.main, stateRoot.toString()).copy(maxCells = 2)
        val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(Message.text(Role.Assistant, "done"))) }, once = false))))
        val binding = ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() })
        val work = WorkId("W-goal")
        Events(clock).use { events ->
            val seen = CopyOnWriteArrayList<EventRecord>()
            events.subscribe({ seen += it }, 100_000).use {
                val first = runBlocking { StudioAttempt(clock, idGen).run(workspace, "Add a second line to notes.txt.", binding, events, spec, Duration.ofMinutes(5), work = work) }
                assertNull(first.failure, first.failure)
                assertEquals(VerificationSetup("review", "none", emptyList()), first.verification)
                Store.open(stateRoot, Git(workspace), clock).use { store ->
                    Contracts(SqliteContractRepository(store, clock), idGen, clock).amendByHost(work, "goal check of the model") { c ->
                        val goal = Acceptance.Run("AC-goal", Command(listOf("git", "--version")), Origin.Model(c.acceptance.first().id))
                        c.copy(acceptance = c.acceptance + goal, requirements = c.requirements.map { it.copy(acceptance = it.acceptance + goal.id) })
                    }
                }
                val from = events.lastSeq
                val second = runBlocking { StudioAttempt(clock, idGen).run(workspace, "Add a second line to notes.txt.", binding, events, spec, Duration.ofMinutes(5), work = work) }
                assertNull(second.failure, second.failure)
                assertEquals(VerificationSetup("tests", "declared", listOf(listOf("git", "--version"))), second.verification)
                val until = System.nanoTime() + Duration.ofSeconds(30).toNanos()
                while (seen.maxOfOrNull { it.seq } != events.lastSeq && System.nanoTime() < until) Thread.sleep(10)
                val own = seen.filter { it.seq > from }.map { it.event }
                assertEquals(1, own.filterIsInstance<AgentEvent.Telemetry.PhaseCounted>().count { it.counted == CountedPhase.Open.wire }, "WF-1: one open for the reopen")
                assertTrue(own.filterIsInstance<AgentEvent.Warning>().none { it.kind == "preflight-diverged" }, "the preflight guessed the notes the open found")
            }
        }
    }
}
