package io.astrolabe.evallive

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.Protocol
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.provider.ToolCall
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 5 P1 #3, as the Studio's `HostGuidanceTest`: the working notes follow the protocol of the frozen attempt's
 * main line — the direct arm is told `state(note)` and `task(finish)` — and the structured notes keep their bytes (WF-15).
 */
class StudioGuidanceTest {
    @TempDir
    lateinit var dir: Path

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `the structured notes keep their bytes and the direct notes are the Studio's`() {
        assertEquals(1771, StudioPolicy.WORKING_NOTES.length)
        assertEquals(STRUCTURED_SHA256, sha256(StudioPolicy.WORKING_NOTES))
        assertEquals(DIRECT_SHA256, sha256(StudioPolicy.DIRECT_WORKING_NOTES))
        assertTrue(StudioPolicy.workingNotes(Protocol.Structured) === StudioPolicy.WORKING_NOTES)
        assertTrue(StudioPolicy.workingNotes(Protocol.Direct) === StudioPolicy.DIRECT_WORKING_NOTES)
        assertFalse("\"patch\"" in StudioPolicy.DIRECT_WORKING_NOTES || "no tool call" in StudioPolicy.DIRECT_WORKING_NOTES)
    }

    @Test
    fun `the direct arm is told the direct notes and opens once`() {
        val (texts, result) = bench(Arms.DIRECT)
        assertTrue(texts.any { StudioPolicy.DIRECT_WORKING_NOTES in it }, "the model saw no direct notes")
        assertTrue(texts.none { StudioPolicy.WORKING_NOTES in it }, "a direct cell was told the structured notes")
        assertEquals(1, assertNotNull(result.phases).opens, "${result.phases}")
    }

    @Test
    fun `the default arm is told the structured notes and opens once`() {
        val (texts, result) = bench(Arms.DEFAULT)
        assertTrue(texts.any { StudioPolicy.WORKING_NOTES in it }, "the model saw no structured notes")
        assertTrue(texts.none { StudioPolicy.DIRECT_WORKING_NOTES in it }, "a structured cell was told the direct notes")
        assertEquals(1, assertNotNull(result.phases).opens, "${result.phases}")
    }

    /** A task without a test command on [arm]; every request's messages and the result. The main line ends as its protocol does. */
    private fun bench(arm: Arm): Pair<List<String>, RunResult> {
        val taskDir = dir.resolve("tasks").resolve("guide")
        Files.createDirectories(taskDir.resolve("base"))
        taskDir.resolve("base").resolve("notes.txt").writeText("first line\n")
        val hidden = HiddenFiles.of(mapOf("check.txt" to "hidden".toByteArray()))
        val task = BenchTask("guide", "long", "Guide", "Say what notes.txt holds.", taskDir, AcceptanceSpec(listOf("git", "--version")), hidden, hidden, hidden, null)
        val plan = BenchPlan(
            tasks = listOf(task), models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 1,
            out = dir.resolve("out-${arm.name}"), temp = dir.resolve("t-${arm.name}"), maxCells = 2, deadline = Duration.ofMinutes(5), arm = arm,
        )
        val texts = CopyOnWriteArrayList<String>()
        val adapter = FakeAdapter(ScriptedModel(listOf(
            ScriptedModel.Turn({ true }, { request ->
                texts += request.items.filterIsInstance<Message>().joinToString("\n") { it.text }
                if (arm.protocol.core == Protocol.Direct) Scripted.Reply(listOf(ToolCall("c${texts.size}", "task", """{"op":"finish","text":"notes.txt holds one line."}""")))
                else Scripted.Reply(listOf(Message.text(Role.Assistant, "notes.txt holds one line.")))
            }, once = false),
        )))
        val models = ModelSource { ModelBinding(adapter, FakeProfiles.main, EstimatorFactory { HeuristicEstimator() }) }
        val result = Bench(plan, models, Interpreters.detect("python"), Clock.systemUTC(), FixedIdGen()).run().single()
        assertNull(result.failure, result.failure)
        return texts.toList() to result
    }

    private companion object {
        /** SHA-256 of the Studio's `Guidance.NOTES` before the direct variant (Studio `1f5c1ba`), pinned there too. */
        const val STRUCTURED_SHA256: String = "83f55bce69debe25978b0fa9fc0a9e3a3384201fc2de24df431b9278eeff7845"

        /** SHA-256 of the Studio's `Guidance.DIRECT_NOTES`: the copy is verbatim. */
        const val DIRECT_SHA256: String = "b7d3b220ae56fe71992505cc526cdb259e71efb23f34a505717969c0785d4d70"
    }
}
