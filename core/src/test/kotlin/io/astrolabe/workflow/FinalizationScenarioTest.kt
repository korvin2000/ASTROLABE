package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Command
import io.astrolabe.contract.Shape
import io.astrolabe.fixtures.Scripted
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WF-5, WF-6 and WF-7 (plan §7.2, W1) on the dirty repository in S1: the campaign gate's quality gate is the fixture's
 * variant (b) — it prints a passing pytest run and rewrites the tracked `data/notes.json` — so the end checks move the
 * candidate and their own receipt cannot certify it (WD-14 stays W3's): the final acceptance waits for a decision. The
 * host is Studio's shape: it answers a decision request only by the id the user saw (WD-10).
 */
class FinalizationScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `a final re-entry without new information reruns nothing and keeps its request, and the user's accept then completes`() = finalization(300) { s ->
        val asked = ArrayList<AcceptanceDecisionRequest>()
        var answer: AcceptanceDecision? = null
        s.decide { r -> asked += r; answer?.takeIf { it.requestId == r.id } }
        val broken = ArrayList<String>()
        fun expect(ok: Boolean, what: () -> String) { if (!ok) broken += what() }

        val first = s.play(::edit)
        expect(first.outcome == CampaignOutcome.WaitingForInput) { "the first final ended ${first.outcome}: ${first.state?.reason}" }
        val request = asked.last { it.incrementId == null }
        val firstStop = checkNotNull(first.state?.reason)

        // WF-7: a reopen without changes knows the acceptance receipt the run left.
        val reopened = s.reopen()
        expect(reopened.checks[Checks.acceptId("AC-1")]?.last != null) { "WF-7: AC-1's receipt is unknown after the reopen" }
        val before = receipts(reopened)
        val second = s.play { emptyList() }
        val secondStop = second.state?.reason.orEmpty()
        expect(second.outcome == CampaignOutcome.WaitingForInput) { "the re-entry ended ${second.outcome}: $secondStop" }
        expect(s.adapter!!.calls.isEmpty()) { "the re-entry called the model ${s.adapter!!.calls.size} times" }
        expect(receipts(s.campaign!!) == before) { "WF-5: the re-entry without new information ran ${receipts(s.campaign!!) - before} checks" }
        val again = asked.last { it.incrementId == null }
        expect(again.id == request.id) { "WD-10: the repeated final request is ${again.id}, not ${request.id}" }
        expect(again.key == request.key) { "WD-10: the repeated final request's key moved: ${again.key} vs ${request.key}" }
        expect(secondStop != firstStop) { "WF-5: the same stop twice: $secondStop" }
        expect(request.id in secondStop) { "WF-5: the second stop does not name the request that lifts it: $secondStop" }
        expect("no receipt" !in secondStop) { "WF-7: 'no receipt' after a reopen without changes: $secondStop" }

        // WF-6: the user accepts the request they saw; the next entry applies it and runs nothing.
        answer = AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, "user", "the rewritten data file is expected")
        s.reopen()
        val held = receipts(s.campaign!!)
        val third = s.play { emptyList() }
        expect(third.outcome == CampaignOutcome.Completed) { "WF-6: the accepted candidate ended ${third.outcome}: ${third.state?.reason}" }
        expect(receipts(s.campaign!!) == held) { "WF-6: ${receipts(s.campaign!!) - held} checks ran after the accept" }
        val attempts = s.counted(CountedPhase.Finish).map { it.finishAttempts }
        expect(attempts == listOf(1, 1, 1)) { "one finalization attempt per entry: $attempts" }
        assertTrue(broken.isEmpty(), broken.joinToString("\n"))
    }

    @Test
    fun `a policy accept for the candidate the end checks left completes it and nothing runs after it`() = finalization(300) { s ->
        var atAccept = -1
        s.decide { r ->
            atAccept = receipts(s.campaign!!)
            AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "auto mode accepts what could not be verified")
        }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        assertEquals(atAccept, receipts(s.campaign!!), "WF-6: no check runs after the accept")
        assertEquals(listOf(1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
    }

    @Test
    fun `a real source edit while the final decision is asked still voids it`() = finalization(1) { s ->
        s.decide { r ->
            Files.writeString(s.root.resolve("src/util.py"), "def clamp(x, lo, hi):\n    return min(max(x, lo), hi)\n")
            AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "done")
        }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
        assertTrue("src/util.py" in run.state?.reason.orEmpty(), "the void names the moved path: ${run.state?.reason}")
    }

    @Test
    fun `a red final gate still fails the campaign`() = finalization(1, gate = { red }) { s ->
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
    }

    /** The plan cell is skipped (the acceptance is executable); one cell edits, verifies `AC-1` and reports done. */
    private fun edit(c: OpenedCampaign): List<Scripted> = Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    /** The receipts the campaign's store holds: a check that ran adds one. */
    private fun receipts(c: OpenedCampaign): Int = c.store.db.query("SELECT body FROM receipts") { it.string("body") }.size

    /** S1 over the dirty repository with `AC-1` a plain printing check and [gate] the campaign's quality gate. */
    private fun finalization(files: Int, gate: DirtyRepo.() -> Command = { check }, body: suspend (Scenario) -> Unit) = runBlocking {
        DirtyRepo.create(files, DirtyRepo.Variant.RewritesData).use { dirty ->
            Files.writeString(dirty.root.resolve(FAILING), recorded("pytest-fail-param.txt"))
            val quality = dirty.gate()
            Scenario(dirty.root, stateRoot, configure = { it.copy(qualityGates = listOf(quality)) }).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(printing, shape = Shape.S1)
                s.open()
                body(s)
            }
        }
    }

    private companion object {
        const val FAILING = "pytest_fail.txt"

        val printing: Command = shell("type ${DirtyRepo.OUTPUT}", "cat ${DirtyRepo.OUTPUT}")
        val red: Command = shell("type $FAILING & exit /b 1", "cat $FAILING; exit 1")

        fun shell(windows: String, posix: String): Command =
            if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

        fun recorded(name: String): String =
            FinalizationScenarioTest::class.java.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
