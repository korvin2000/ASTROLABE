package io.astrolabe.evallive

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.contract.Acceptance
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
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
}
