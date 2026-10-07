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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WF-12, its question half (task-workflow §3.5, WD-22, D-344): a task that asks a question ends with an answer even when
 * the model ran the suite to answer it. The run keeps its execution authority — W-class, as test runners are by design —
 * and what the answer reads is the observed durable effect: the suite wrote only output under `build/`, outside the
 * candidate, so the candidate is snapshot 0's and the outcome is `answered`, with the run as the answer's evidence.
 */
class AnswerScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `WF-12 a question answered after running the suite ends answered with the candidate at s0 and the run still W-class`() = runBlocking<Unit> {
        DirtyRepo.create(1, DirtyRepo.Variant.ScratchOutput, bigBytes = 0, settle = false).use { dirty ->
            Scenario(dirty.root, stateRoot, text = "does total ignore negative items? answer the question, change nothing").use { s ->
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
}
