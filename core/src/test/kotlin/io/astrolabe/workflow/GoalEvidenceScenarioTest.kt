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
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.Scripted
import io.astrolabe.verify.Checks
import io.astrolabe.verify.ProvenanceClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private suspend fun declaredGoal(s: Scenario) {
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertEquals(ProvenanceClass.Independent, finish.provenanceClass, finish.notVerified.toString())
        assertEquals(listOf("AC-1"), finish.requirements.single().goalEvidence)
        assertEquals(emptyList(), fired(s, "entry"), "the contract holds the goal acceptance")
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

        /** The model's own goal command: the same printed pytest run as `AC-1`, another command line. */
        val goal: Command = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type .\\${DirtyRepo.OUTPUT}"))
            else Command(listOf("/bin/sh", "-c", "cat ./${DirtyRepo.OUTPUT}"))
    }
}
