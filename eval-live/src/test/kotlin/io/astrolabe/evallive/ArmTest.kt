package io.astrolabe.evallive

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
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** WP-B5: arms as configurations — a kept result stands only for its own key, and an arm the core cannot honour is refused. */
class ArmTest {
    @TempDir
    lateinit var dir: Path

    private val binds = AtomicInteger()

    /** Answers at once: the cheapest attempt of either arm. */
    private val models = ModelSource {
        binds.incrementAndGet()
        ModelBinding(FakeAdapter(ScriptedModel(emptyList(), Scripted.Reply(listOf(Message.text(Role.Assistant, "done"))))), FakeProfiles.main, EstimatorFactory { HeuristicEstimator() })
    }

    private fun bench(plan: BenchPlan, code: String = "code-1"): RunResult =
        Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen(), code = code).run().single()

    @Test
    fun `a kept result stands only for the same arm, configuration, code and task`() {
        val task = LoopFixtures.task(dir)
        val plan = LoopFixtures.plan(dir, task, Arms.LOOP)
        val first = bench(plan)
        val runDir = PlannedRun(1, task, "fake-main", 1, "loop").dir(plan.out)
        val key = assertNotNull(first.key)
        assertEquals(ResultKey("loop", Fingerprints.config(plan, "fake-main"), "code-1", Fingerprints.task(task)), key)
        assertEquals(1, binds.get())

        assertEquals(first, bench(plan), "the same key: kept, no model bound")
        assertEquals(1, binds.get())

        val code = bench(plan, code = "code-2")
        assertEquals(2, binds.get(), "other code: run again")
        assertEquals("code-2", code.key?.code)
        assertTrue(Files.isRegularFile(runDir.resolveSibling("r1.stale-1").resolve("result.json")), "the earlier result is set aside, not lost")

        val config = bench(plan.copy(deadline = Duration.ofMinutes(6)), code = "code-2")
        assertEquals(3, binds.get(), "another configuration: run again")
        assertTrue(config.key?.config != code.key?.config)

        val changed = LoopFixtures.task(dir, prompt = "Add the line 'second line' to notes.txt, after the first one.")
        val other = bench(plan.copy(deadline = Duration.ofMinutes(6), tasks = listOf(changed)), code = "code-2")
        assertEquals(4, binds.get(), "another task: run again")
        assertTrue(other.key?.task != config.key?.task)
        assertTrue(Files.isRegularFile(runDir.resolveSibling("r1.stale-3").resolve("result.json")))

        // A result of another arm laid where the default arm looks is never taken for its own.
        val defaultDir = PlannedRun(1, task, "fake-main", 1, "default").dir(plan.out)
        Files.createDirectories(defaultDir)
        Files.copy(runDir.resolve("result.json"), defaultDir.resolve("result.json"), StandardCopyOption.REPLACE_EXISTING)
        val byDefault = bench(LoopFixtures.plan(dir, task, Arms.DEFAULT))
        assertEquals(5, binds.get(), "another arm: run again")
        assertEquals("default", byDefault.arm)
        assertEquals("default", byDefault.key?.arm)
    }

    @Test
    fun `an arm with a field the core cannot honour is refused, never run without it`() {
        val direct = assertFailsWith<UsageError> { Arms.named("direct") }
        assertTrue("D1" in direct.message.orEmpty(), direct.message)
        assertFailsWith<UsageError> { Arms.named("nope") }
        assertEquals(Arms.LOOP, Arms.named("loop"))
        assertEquals(Arms.DEFAULT, Arms.named("default"))

        val shape = Arm("forced", ArmRunner.Core, shape = "S2")
        assertTrue(shape.unsupported().single().contains("H2"), "${shape.unsupported()}")
        val table = Arm("table", ArmRunner.Core, models = mapOf("helper" to "fake-helper"))
        assertTrue(table.unsupported().single().contains("H3"), "${table.unsupported()}")
        assertTrue(Arm("odd", ArmRunner.Loop, protocol = Protocol.Direct).unsupported().isNotEmpty())
        val task = LoopFixtures.task(dir)
        assertFailsWith<IllegalArgumentException> { LoopFixtures.plan(dir, task, shape) }
        assertFailsWith<IllegalStateException> { table.spec(LoopFixtures.plan(dir, task, Arms.DEFAULT).spec(FakeProfiles.main, dir)) }

        // The command line refuses it before any provider is reached.
        val cli = assertFailsWith<UsageError> {
            Cli.execute(listOf("run", "--models", "fake-main", "--out", dir.resolve("out").toString(), "--arm", "direct", "--tasks", "bugfix-pagination"), Clock.systemUTC())
        }
        assertTrue("D1" in cli.message.orEmpty(), cli.message)
        assertTrue(Files.notExists(dir.resolve("out")), "nothing ran")
        assertEquals(0, binds.get())
    }
}
