package io.astrolabe.workflow

import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.EvidencePurpose
import io.astrolabe.contract.Origin
import io.astrolabe.event.AgentEvent
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.Scripted
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.Checks
import io.astrolabe.verify.ProvenanceClass
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WF-12 (plan §7.2, task-workflow §3.8, W8, D-434): the goal apart from discovered suites. On the dirty repository a
 * sniffed-suite stand-in (`AC-1`, origin harness, no stated purpose: regression evidence) is green after the edit — the
 * entry gate asks once for a goal criterion, the sufficiency hint never says "finish now", and the campaign completes
 * `unverified` with "regression only" disclosed, never "verified independently". The model's stated goal criterion
 * (`model(strengthens R1)`, recorded as the plan intake records it) is evaluated by the run that realizes it — `verify`
 * still refuses to launch it (D-262) — and gives `agent_test`; the host's own goal item gives `independent`. Guards count
 * gate events, receipts and model requests; the plays run at once (plan §7.2 rule 3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoalEvidenceScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(
        REGRESSION to { play(REGRESSION, null, ::regressionOnly) },
        STATED to { play(STATED, null, ::statedCriterion) },
        DECLARED to { play(DECLARED, EvidencePurpose.Goal, ::declaredGoal) },
        CLAIM to { play(CLAIM, EvidencePurpose.Goal, ::statedClaim) },
        SCRATCH to ::scratchOnly,
        RESTORED to ::changedThenRestored,
    )
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `WF-12 green regression suites alone complete unverified as regression only, the entry gate asks once and nothing says finish now`() =
        played.getValue(REGRESSION).getOrThrow()

    @Test
    fun `WF-12 the model's stated goal criterion is evaluated by the run that realizes it, never launched by verify, and gives agent_test`() =
        played.getValue(STATED).getOrThrow()

    @Test
    fun `WF-12 the host's own goal item green at the final tree gives independent`() = played.getValue(DECLARED).getOrThrow()

    @Test
    fun `a goal claim the model states after the plan is decided at the final, never skipped`() = played.getValue(CLAIM).getOrThrow()

    @Test
    fun `WF-12 a question answered after running the suite ends answered with the candidate at s0 and the run still W-class`() = played.getValue(SCRATCH).getOrThrow()

    @Test
    fun `T-44 a run that moved the candidate is a durable effect though a later run restored it, so the task cannot end answered`() =
        played.getValue(RESTORED).getOrThrow()

    private suspend fun regressionOnly(s: Scenario) {
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertEquals(ProvenanceClass.Unverified, finish.provenanceClass, "a discovered suite is regression evidence, never independent (WF-12)")
        assertTrue(finish.notVerified.any { "regression only: no goal-level check of R1" in it }, finish.notVerified.toString())
        assertEquals(listOf("AC-1") to emptyList<String>(), finish.requirements.single().let { it.regression to it.goalEvidence })
        assertEquals(1, fired(s, "entry").size, "the entry gate asks once for one crisp goal criterion: ${fired(s, "entry")}")
        assertTrue(fired(s, "entry").single().startsWith("entry: editing while acceptance AC-1 is regression only"), fired(s, "entry").single())
        assertEquals(emptyList(), fired(s, "sufficiency"), "regression obligations alone never say finish now")
    }

    private suspend fun statedCriterion(s: Scenario) {
        val c = s.campaign!!
        c.contracts.strengthen(s.request.work, Acceptance.Run("AC-2", goal, Origin.Model("R1"), obligationVersion = c.contract.version))
        val version = checkNotNull(c.registry.version(DirtyRepo.SOURCE))
        val script = listOf(
            Scripted.Reply(listOf(say("reading"), read("c1", DirtyRepo.SOURCE))),
            Scripted.Reply(listOf(say("editing"), anchored("c2", DirtyRepo.SOURCE, version, "    return sum(items)", "    return sum(x for x in items if x >= 0)"))),
            Scripted.Reply(listOf(say("verifying my criterion"), call("c3", "verify", """{"what":"acceptance","ids":["AC-2"]}"""))),
            Scripted.Reply(listOf(say("running my criterion"), call("c4", "run", """{"argv":${JsonArray(goal.argv.map(::JsonPrimitive))}}"""))),
            Scripted.Reply(listOf(say("verifying the suite"), call("c5", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
            Scripted.Reply(listOf(say("done"))),
        )
        val run = s.play { script }
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val receipts = SqliteReceipts(c.store, s.clock).forCheck(Checks.acceptId("AC-2"))
        assertEquals(listOf(Outcome.Denied, Outcome.Passed), receipts.map { it.outcome }, "verify refused to launch it (D-262); the run realized it: $receipts")
        val finish = assertNotNull(run.finish)
        val line = finish.acceptance.single { it.id == "AC-2" }
        assertEquals(receipts.last().receiptId to "green", line.receiptId to line.status, "the item's receipt is the recognised run's")
        assertEquals(ProvenanceClass.AgentTest, finish.provenanceClass, "the model's criterion is the agent's evidence, never independent")
        assertEquals(listOf("AC-2"), finish.requirements.single().goalEvidence)
        assertEquals(script.size, s.adapter!!.calls.size, "the classification asks the model nothing")
    }

    /** P1 #6 (task-workflow §3.3): the model's goal claim stated after the plan is in no increment's acceptance; the final decides it. */
    private suspend fun statedClaim(s: Scenario) {
        val c = s.campaign!!
        c.contracts.strengthen(s.request.work, Acceptance.Check("AC-2", "total ignores negative items", Origin.Model("R1"), obligationVersion = c.contract.version, purpose = EvidencePurpose.Goal))
        val asked = ArrayList<io.astrolabe.verify.AcceptanceDecisionRequest>()
        s.decide { r -> asked += r; null }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.WaitingForInput, run.outcome, "the model's unassessed goal claim is put to the decider: ${run.state?.reason}")
        assertTrue(asked.any { r -> r.incrementId == null && r.items.any { it.obligation == "AC-2" } }, "the final asks about AC-2: ${asked.map { it.items.map { i -> i.obligation } }}")
    }

    private suspend fun declaredGoal(s: Scenario) {
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertEquals(ProvenanceClass.Independent, finish.provenanceClass, finish.notVerified.toString())
        assertEquals(listOf("AC-1"), finish.requirements.single().goalEvidence)
        assertEquals(emptyList(), fired(s, "entry"), "the contract holds the goal acceptance")
    }

    /**
     * WF-12, its question half (task-workflow §3.5, WD-22, D-344): a task that asks a question ends with an answer even when
     * the model ran the suite to answer it. The run keeps its execution authority — W-class, as test runners are by design —
     * and what the answer reads is the observed durable effect: the suite wrote only output under `build/`, outside the
     * candidate, so the candidate is snapshot 0's and the outcome is `answered`, with the run as the answer's evidence. A run
     * that moved the candidate is a durable effect though a later run restored it (P1 #11, T-44).
     */
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

    private fun fired(s: Scenario, gate: String): List<String> {
        s.counted()
        return s.recorder.ofType<AgentEvent.Cell.GateFired>().filter { it.gate == gate }.map { it.text }
    }

    private fun edit(c: io.astrolabe.campaign.OpenedCampaign): List<Scripted> =
        Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    /** A plain S0 campaign over the dirty repository with `AC-1` the fixture's printing check of [purpose] (`null`: sniffed). */
    private fun play(name: String, purpose: EvidencePurpose?, body: suspend (Scenario) -> Unit) = runBlocking {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(name)).use { s ->
                s.seed(dirty.check, purpose = purpose)
                s.open()
                body(s)
            }
        }
    }

    private companion object {
        const val REGRESSION = "regression"
        const val STATED = "stated"
        const val DECLARED = "declared"
        const val CLAIM = "claim"
        const val SCRATCH = "scratch"
        const val RESTORED = "restored"
        const val QUESTION = "does total ignore negative items? answer the question, change nothing"

        /** The model's own goal command: the same printed pytest run as `AC-1`, another command line. */
        val goal: Command = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type .\\${DirtyRepo.OUTPUT}"))
            else Command(listOf("/bin/sh", "-c", "cat ./${DirtyRepo.OUTPUT}"))

        /** Keeps the tracked data file under `build/` (outside identity), then rewrites it: the candidate moves. */
        val change = if (io.astrolabe.cell.WINDOWS) io.astrolabe.contract.Command(listOf("cmd.exe", "/d", "/s", "/c", "if not exist build mkdir build & copy /y data\\notes.json build\\notes.json & echo [2]> data\\notes.json"))
            else io.astrolabe.contract.Command(listOf("/bin/sh", "-c", "mkdir -p build && cp data/notes.json build/notes.json && echo [2] > data/notes.json"))

        /** Copies the kept bytes back: the candidate is snapshot 0's again. */
        val restore = if (io.astrolabe.cell.WINDOWS) io.astrolabe.contract.Command(listOf("cmd.exe", "/d", "/s", "/c", "copy /y build\\notes.json data\\notes.json"))
            else io.astrolabe.contract.Command(listOf("/bin/sh", "-c", "cp build/notes.json data/notes.json"))
    }
}
