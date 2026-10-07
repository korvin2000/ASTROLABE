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
import java.time.Clock
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-14: a dirt directory may be an absolute or a UNC path; dirt outside the tree is not dirt of the repository and never stops a run. */
class DirtPlacementTest {
    @TempDir
    lateinit var dir: Path

    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun workspace(): Path = Files.createDirectories(dir.resolve("ws"))

    private fun small(target: Path) = DirtSpec(target.toString(), files = 3, bigMegabytes = 0)

    @Test
    fun `a dirt directory is a relative name or an absolute path of any of the three forms, never a way out of the tree`() {
        DirtSpec("devtools")
        DirtSpec("C:\\tools\\dirt")
        DirtSpec("C:/tools/dirt")
        DirtSpec("\\\\server\\share\\dirt")
        DirtSpec("/var/tools/dirt")
        for (bad in listOf("", "  ", "../x", "a/../b", "C:\\tools\\..\\x", ".git/x", "\\\\server", "\\\\server\\")) {
            assertFailsWith<IllegalArgumentException>(bad) { DirtSpec(bad) }
        }
    }

    @Test
    fun `an absolute directory outside the tree is written there, reported as outside, and removed when the run ends`() {
        val target = dir.resolve("elsewhere").resolve("tools")
        val placement = small(target).write(workspace())

        val result = placement.result
        assertEquals(DirtResult(target.toString(), inTree = false, written = true, reason = result.reason), result)
        assertNotNull(result.reason)
        assertTrue(target.resolve("pkg-0").resolve("file-0.txt").exists() && target.resolve("bundle.bin").exists())
        assertFalse(dir.resolve("ws").resolve("tools").exists(), "nothing of it lies in the workspace")

        placement.close()
        assertFalse(target.exists(), "what the runner wrote outside the tree is removed")
    }

    @Test
    fun `an absolute directory inside the tree is dirt of the tree like a relative one`() {
        val workspace = workspace()
        val inside = workspace.resolve("devtools-abs")
        val placement = small(inside).write(workspace)

        assertEquals(DirtResult(inside.toString(), inTree = true, written = true), placement.result)
        assertTrue(inside.resolve("bundle.bin").exists())
        placement.close()
        assertTrue(inside.exists(), "dirt in the tree stays for the run's end to clean with the workspace")
    }

    @Test
    fun `a directory that is already there is left as it is, and so is one that cannot be written, with the reason`() {
        val there = Files.createDirectories(dir.resolve("there"))
        there.resolve("mine.txt").writeText("keep")
        val kept = small(there).write(workspace())
        assertFalse(kept.result.written)
        assertTrue("already there" in kept.result.reason.orEmpty(), kept.result.reason)
        kept.close()
        assertEquals("keep", there.resolve("mine.txt").toFile().readText())
        assertFalse(there.resolve("pkg-0").exists())

        val file = dir.resolve("a-file")
        file.writeText("x")
        val blocked = small(file.resolve("below")).write(workspace())
        assertFalse(blocked.result.written)
        assertTrue("not writable" in blocked.result.reason.orEmpty(), blocked.result.reason)
        blocked.close()
        assertEquals("x", file.toFile().readText())
    }

    @Test
    fun `a path of the other system is skipped with its reason, not written under some other name`() {
        val foreign = if (windows) "/tmp/eval-live-dirt" else "C:\\eval-live-dirt"
        val placement = DirtSpec(foreign, files = 2, bigMegabytes = 0).write(workspace())

        assertEquals(DirtResult(foreign, inTree = false, written = false, reason = "not an absolute path on this system"), placement.result)
        assertFalse(dir.resolve("ws").resolve(foreign).exists())
        placement.close()
    }

    @Test
    fun `a run with dirt outside the tree records it in result json and removes it, one that cannot write it still runs`() {
        val outside = dir.resolve("outside-dirt")
        val written = run(DirtSpec(outside.toString(), files = 3, bigMegabytes = 0), "w")
        assertNull(written.failure, written.failure)
        assertEquals(DirtResult(outside.toString(), inTree = false, written = true, reason = written.dirt?.reason), written.dirt)
        assertNotNull(written.dirt?.reason)
        assertFalse(outside.exists(), "removed when the run ended")

        val file = dir.resolve("a-file")
        file.writeText("x")
        val blocked = run(DirtSpec(file.resolve("below").toString(), files = 3, bigMegabytes = 0), "b")
        assertNull(blocked.failure, blocked.failure)
        assertNotNull(blocked.outcome)
        assertEquals(false, blocked.dirt?.written)
        assertTrue("not writable" in blocked.dirt?.reason.orEmpty(), blocked.dirt.toString())
    }

    /** One run of a small task with [dirt] on the fake adapter; [name] keeps the directories of the runs apart. */
    private fun run(dirt: DirtSpec, name: String): RunResult {
        val taskDir = dir.resolve("tasks-$name").resolve("dirty")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        val task = BenchTask(
            "dirty", "long", "Dirty", "Add a second line to notes.txt.", taskDir, AcceptanceSpec(listOf("git", "--version")),
            hidden, hidden, hidden, dirt = dirt,
        )
        val plan = BenchPlan(
            tasks = listOf(task), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
            out = dir.resolve("out-$name"), temp = dir.resolve("t$name"), maxCells = 2, deadline = Duration.ofMinutes(5),
        )
        val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(Message.text(Role.Assistant, "done"))) }, once = false))))
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }
        return Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()
    }
}
