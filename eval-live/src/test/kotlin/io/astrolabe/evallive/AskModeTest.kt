package io.astrolabe.evallive

import io.astrolabe.RunSpec
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP-WG: the Studio's `ask` mode for the gate's live run — the scripted user answers, the same work resumes. */
class AskModeTest {
    @TempDir
    lateinit var dir: Path

    /** A task without a test command: its result is unverified and goes to an acceptance decision. */
    private fun task(): BenchTask {
        val taskDir = dir.resolve("tasks").resolve("ask")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        return BenchTask("ask", "long", "Ask", "Add a second line to notes.txt.", taskDir, AcceptanceSpec(listOf("git", "--version")), hidden, hidden, hidden, null)
    }

    private fun plan(mode: HostMode) = BenchPlan(
        tasks = listOf(task()), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
        // A short temp name: the core's recovery blob paths come close to the 260 characters git on Windows can open.
        out = dir.resolve("out-${mode.wire}"), temp = dir.resolve("t${mode.ordinal}"), maxCells = 2, deadline = Duration.ofMinutes(5), mode = mode,
    )

    /** Every model call writes the change and ends with a summary. */
    private fun bench(plan: BenchPlan): RunResult {
        val adapter = FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, {
                val workspace = Files.list(plan.temp).use { runs -> runs.filter { it.fileName.toString().startsWith("run-") }.toList() }.single().resolve("workspace")
                workspace.resolve("notes.txt").writeText("first line\nsecond line\n")
                Scripted.Reply(listOf(Message.text(Role.Assistant, "done")))
            }, once = false),
        )))
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }
        return Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()
    }

    private fun acceptance(result: RunResult): List<String> = result.policyDecisions.filter { it.kind == "acceptance" }.map { it.outcome }

    @Test
    fun `ask stops for the user and completes the same work after one answer`() {
        val result = bench(plan(HostMode.Ask))

        assertNull(result.failure, result.failure)
        assertEquals("ask", result.mode)
        val ask = assertNotNull(result.ask)
        assertEquals(1, ask.answers, "$ask")
        assertEquals("completed", ask.outcome, "$ask")
        assertEquals("completed", result.outcome, result.reason)
        assertEquals(2, ask.segments.size, "$ask")
        val (waiting, resumed) = ask.segments
        assertEquals("waiting_for_input", waiting.outcome, "$waiting")
        assertEquals(waiting.workId, resumed.workId, "the answer resumes the same work: $ask")
        assertEquals(listOf("waiting", "answered"), acceptance(result))
        val phases = assertNotNull(result.phases)
        assertTrue(phases.opens >= 2 && phases.finishAttempts >= 1, "$phases")
    }

    @Test
    fun `auto accepts on the policy's word and keeps its result fingerprint`() {
        val plan = plan(HostMode.Auto)
        val result = bench(plan)

        assertNull(result.failure, result.failure)
        assertEquals("auto", result.mode)
        assertNull(result.ask)
        assertEquals("completed", result.outcome, result.reason)
        assertEquals(listOf("accepted"), acceptance(result))
        assertTrue(assertNotNull(result.phases).opens >= 1)
        // The configuration fingerprint of an `auto` bench is the one it had before modes existed.
        val before = Fingerprints.sha256(
            listOf(
                plan.arm.canonical, "provider=${plan.provider}", "model=fake-main", "effort=${plan.effort.name}", "effortExplicit=${plan.effortExplicit}",
                "maxCells=${plan.maxCells}", "deadlineSeconds=${plan.deadline.seconds}", "limits=${RunSpec.LIMITS}", "leaseMinutes=${RunSpec.LEASE_MINUTES}",
                "guardWindows=${RunSpec.TOKEN_GUARD_WINDOWS}",
            ).joinToString("\n"),
        )
        assertEquals(before, Fingerprints.config(plan, "fake-main"))
        assertEquals(before, assertNotNull(result.key).config)
        assertNotEquals(before, Fingerprints.config(plan.copy(mode = HostMode.Ask), "fake-main"))
    }
}
