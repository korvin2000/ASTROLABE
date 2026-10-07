package io.astrolabe.workflow

import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.evidence.Intent
import io.astrolabe.fixtures.Scripted
import io.astrolabe.telemetry.CountedPhase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WF-12, its question half (task-workflow §3.5, WD-22, D-344): a task that asks a question ends with an answer even when
 * the model ran the suite to answer it. The run keeps its execution authority — W-class, as test runners are by design —
 * and what the answer reads is the observed durable effect: the suite wrote only output under `build/`, outside the
 * candidate, so the candidate is snapshot 0's and the outcome is `answered`, with the run as the answer's evidence. A run
 * that moved the candidate is a durable effect though a later run restored it (P1 #11, T-44). The plays run at once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswerScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(SCRATCH to ::scratchOnly, RESTORED to ::changedThenRestored)
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `WF-12 a question answered after running the suite ends answered with the candidate at s0 and the run still W-class`() = played.getValue(SCRATCH).getOrThrow()

    @Test
    fun `T-44 a run that moved the candidate is a durable effect though a later run restored it, so the task cannot end answered`() =
        played.getValue(RESTORED).getOrThrow()

    private fun scratchOnly() = runBlocking<Unit> {
        DirtyRepo.create(1, DirtyRepo.Variant.ScratchOutput, bigBytes = 0, settle = false).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(SCRATCH), text = QUESTION).use { s ->
                s.seed(dirty.check)
                val c = s.open()
                val run = s.play {
                    listOf(
                        Scripted.Reply(listOf(say("running the suite"), call("c1", "run", """{"argv":${JsonArray(dirty.check.argv.map(::JsonPrimitive))}}"""))),
                        Scripted.Reply(listOf(say("answering"), call("c2", "task", """{"op":"answer","text":"no: total sums every item, negative ones included"}"""))),
                    )
                }
                assertEquals(CampaignOutcome.Answered, run.outcome, run.state?.reason)
                assertTrue(Files.exists(dirty.root.resolve("build/report.txt")), "the suite wrote its output")
                assertEquals(c.s0.stampId, c.stamper.stamp(fresh = true).id, "the candidate is snapshot 0's")
                assertEquals(0L, s.counted(CountedPhase.Snapshot).sumOf { it.objectsWritten }, "no snapshot stored an object: nothing moved the candidate")
                val intents = c.store.db.query("SELECT body FROM intents WHERE work_id = ?", c.ids.work) { Json.decodeFromString(Intent.serializer(), it.string("body")) }
                assertEquals(listOf("W"), intents.map { it.expectedEffect.substringBefore(' ') }, "the run's execution authority is unchanged: $intents")
                assertTrue(s.adapter!!.calls.isNotEmpty())
                assertEquals(listOf(dirty.check.argv.joinToString(" ")), run.finish?.answerEvidence, "the answered receipt lists the run as the answer's evidence")
            }
        }
    }

    /** One run rewrites the tracked data file, keeping its bytes under `build/`; the next restores them exactly. */
    private fun changedThenRestored() = runBlocking<Unit> {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(RESTORED), text = QUESTION).use { s ->
                s.seed(dirty.check)
                val c = s.open()
                val run = s.play {
                    listOf(
                        Scripted.Reply(listOf(say("trying a value"), call("c1", "run", """{"argv":${JsonArray(change.argv.map(::JsonPrimitive))}}"""))),
                        Scripted.Reply(listOf(say("putting it back"), call("c2", "run", """{"argv":${JsonArray(restore.argv.map(::JsonPrimitive))}}"""))),
                        Scripted.Reply(listOf(say("answering"), call("c3", "task", """{"op":"answer","text":"no: total sums every item, negative ones included"}"""))),
                        Scripted.Reply(listOf(say("done"))),
                    )
                }
                assertEquals(c.s0.stampId, c.stamper.stamp(fresh = true).id, "the second run restored the candidate exactly")
                assertNotEquals(CampaignOutcome.Answered, run.outcome, "a restored mutation is a durable effect: ${run.state?.reason}")
                val denied = s.adapter!!.calls.getOrNull(3)?.request?.toString().orEmpty()
                assertTrue("an answer ends a task that changed nothing, and this one did" in denied, "the answer is denied with the run that moved the candidate")
            }
        }
    }

    private companion object {
        const val SCRATCH = "scratch"
        const val RESTORED = "restored"
        const val QUESTION = "does total ignore negative items? answer the question, change nothing"

        /** Keeps the tracked data file under `build/` (outside identity), then rewrites it: the candidate moves. */
        val change = if (io.astrolabe.cell.WINDOWS) io.astrolabe.contract.Command(listOf("cmd.exe", "/d", "/s", "/c", "if not exist build mkdir build & copy /y data\\notes.json build\\notes.json & echo [2]> data\\notes.json"))
            else io.astrolabe.contract.Command(listOf("/bin/sh", "-c", "mkdir -p build && cp data/notes.json build/notes.json && echo [2] > data/notes.json"))

        /** Copies the kept bytes back: the candidate is snapshot 0's again. */
        val restore = if (io.astrolabe.cell.WINDOWS) io.astrolabe.contract.Command(listOf("cmd.exe", "/d", "/s", "/c", "copy /y build\\notes.json data\\notes.json"))
            else io.astrolabe.contract.Command(listOf("/bin/sh", "-c", "cp build/notes.json data/notes.json"))
    }
}
