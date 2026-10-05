package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Acceptances
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Command
import io.astrolabe.contract.Shape
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.Scripted
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WF-5, its output part (plan §7.2, W3, owner №32) on the dirty repository in S1: a check that writes only output under a
 * declared root — the fixture's `build/` — moves no candidate and voids no decision (c5); a check that rewrites its inputs
 * stops once with the paths named and typed (c15) — a tracked file under `build/` and a tracked source among them, which
 * stay in identity — and the user's accept of that candidate records it accepted; a red final that also rewrote an input
 * stays red whatever the host accepts (P1-4). The guards count requests, receipts and outcomes, so the fixture has one tool
 * file, no big file and no settling wait; the scenarios play at once on first use (plan §7.2 rule 3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutputPolicyScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(
        SCRATCH to { policy(SCRATCH, DirtyRepo.Variant.ScratchOutput, { scratchGate }, ::scratchOutput) },
        REWRITES to { policy(REWRITES, DirtyRepo.Variant.RewritesData, { rewrites }, ::rewrittenInputs) },
        RED to { policy(RED, DirtyRepo.Variant.RewritesData, { redRewrites }, ::redStaysRed) },
    )
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `WF-5 a final gate that writes only output under a declared root moves no candidate and asks nothing`() = played.getValue(SCRATCH).getOrThrow()

    @Test
    fun `a gate that rewrites its inputs stops once naming them, scratch written meanwhile voids no decision, and the accept records them accepted`() =
        played.getValue(REWRITES).getOrThrow()

    @Test
    fun `a red final gate that also rewrote an input stays red whatever the host accepts`() = played.getValue(RED).getOrThrow()

    private suspend fun scratchOutput(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        s.decide { r -> asked += r; null }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        assertTrue(asked.isEmpty(), "no decision was asked: ${asked.map { it.items }}")
        assertTrue(Files.exists(s.root.resolve("build/report.txt")), "the gate wrote its output")
        val c = s.campaign!!
        val accept = SqliteReceipts(c.store, s.clock).forCheck(Checks.acceptId("AC-1"))
        assertEquals(1, accept.size, "AC-1 ran once: the gate's output did not move the candidate it certified")
        assertEquals(accept.single().stampAfter, c.stamper.stamp(fresh = true).id, "the final candidate is the one AC-1 certified")
        assertEquals(listOf(1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
    }

    private suspend fun rewrittenInputs(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        s.decide { r ->
            asked += r
            // Output under a declared root while the decision is asked (a dev server, a report): no move of the candidate (c5).
            Files.writeString(s.root.resolve("build/late.txt"), "written while asked\n")
            AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "the rewritten files are expected")
        }
        val run = s.play(::edit)
        val final = asked.single { it.incrementId == null }
        val item = final.items.single { it.rewrittenInputs.isNotEmpty() }
        assertEquals(listOf(TRACKED_OUTPUT, DirtyRepo.DATA, UTIL), item.rewrittenInputs, "c15: the rewritten inputs are named, typed: ${item.reason}")
        assertTrue(DirtyRepo.DATA in item.reason, item.reason)
        assertEquals(CampaignOutcome.Completed, run.outcome, "the accept of the candidate it saw applies: ${run.state?.reason}")
        val c = s.campaign!!
        val record = Acceptances(c.store, s.clock).decisions(s.request.work, s.request.attempt).single { it.incrementId == null }
        assertEquals(DecisionKind.Accept to Decider.User, record.decision.kind to record.decision.decider)
        assertTrue(item.obligation in record.obligations, "the rewritten obligation is accepted, never verified: ${record.obligations}")
        assertEquals(listOf(1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
    }

    private suspend fun redStaysRed(s: Scenario) {
        s.decide { r -> AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "auto mode accepts what could not be verified") }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
    }

    /** The plan cell is skipped (the acceptance is executable); one cell edits, verifies `AC-1` and reports done. */
    private fun edit(c: OpenedCampaign): List<Scripted> = Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    /** S1 over the dirty repository with `AC-1` a plain printing check and [gate] the campaign's quality gate; [name] keeps its store apart. */
    private fun policy(name: String, variant: DirtyRepo.Variant, gate: DirtyRepo.() -> Command, body: suspend (Scenario) -> Unit) = runBlocking {
        DirtyRepo.create(1, variant, bigBytes = 0, settle = false).use { dirty ->
            dirty.repo.write(TRACKED_OUTPUT, "[1]\n")
            dirty.repo.commit("tracked output under build")
            Files.writeString(dirty.root.resolve(FAILING), recorded("pytest-fail-param.txt"))
            val quality = dirty.gate()
            Scenario(dirty.root, stateRoot.resolve(name), configure = { it.copy(qualityGates = listOf(quality)) }).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(printing, shape = Shape.S1)
                s.open()
                body(s)
            }
        }
    }

    private companion object {
        const val SCRATCH = "scratch"
        const val REWRITES = "rewrites"
        const val RED = "red"
        const val FAILING = "pytest_fail.txt"
        const val TRACKED_OUTPUT = "build/keep.json"
        const val UTIL = "src/util.py"

        val printing: Command = shell("type ${DirtyRepo.OUTPUT}", "cat ${DirtyRepo.OUTPUT}")

        /**
         * The scratch variant's write without its `if not exist … mkdir` prelude (`build/` is there: the tracked output),
         * so the line is recognised as the pytest run it prints, as the data variant's is.
         */
        val scratchGate: Command = shell("echo scratch> build\\report.txt & type ${DirtyRepo.OUTPUT}", "echo scratch > build/report.txt; cat ${DirtyRepo.OUTPUT}")

        /** Passes, and rewrites a tracked data file, a tracked file under `build/` and a tracked source. */
        val rewrites: Command = shell(
            "echo [2]> data\\notes.json & echo [2]> build\\keep.json & echo x = 1> src\\util.py & type ${DirtyRepo.OUTPUT}",
            "echo [2] > data/notes.json; echo [2] > build/keep.json; echo x = 1 > src/util.py; cat ${DirtyRepo.OUTPUT}",
        )

        /** Fails, and rewrites the tracked data file. */
        val redRewrites: Command = shell("echo [3]> data\\notes.json & type $FAILING & exit /b 1", "echo [3] > data/notes.json; cat $FAILING; exit 1")

        fun shell(windows: String, posix: String): Command =
            if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

        fun recorded(name: String): String =
            OutputPolicyScenarioTest::class.java.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
