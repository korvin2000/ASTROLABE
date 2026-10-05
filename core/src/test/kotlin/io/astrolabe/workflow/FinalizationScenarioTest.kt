package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WF-5, WF-6 and WF-7 (plan §7.2, W1) on the dirty repository in S1: the campaign gate's quality gate is the fixture's
 * variant (b) — it prints a passing pytest run and rewrites the tracked `data/notes.json` — so the end checks move the
 * candidate and their own receipt cannot certify it (WD-14 stays W3's): the final acceptance waits for a decision. The
 * host is Studio's shape: it answers a decision request only by the id the user saw (WD-10). The guards count checks
 * (receipts), requests and stops, none of which depends on the untracked files, so the fixture has one tool file, no big
 * file and no settling wait, and the three scenarios play at once on first use (plan §7.2 rule 3: the suite stays within
 * three minutes).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalizationScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(
        REENTRY to { finalization(REENTRY, body = ::reEntryThenAccept) },
        VOID to { finalization(VOID, body = ::voidThenPolicyAccept) },
        RED to { finalization(RED, gate = { red }, body = ::redGate) },
        APPLIED to { finalization(APPLIED, body = ::appliedThenStrengthened) },
    )
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `a final re-entry without new information reruns nothing and keeps its request, and the user's accept then completes across a crash`() =
        played.getValue(REENTRY).getOrThrow()

    @Test
    fun `a source edit while the final decision is asked still voids it, an item added at the same revision is asked anew, and a policy accept then completes`() =
        played.getValue(VOID).getOrThrow()

    @Test
    fun `a red final gate still fails the campaign`() = played.getValue(RED).getOrThrow()

    @Test
    fun `an item added after an applied accept is asked anew, and a crash between Finishing and Finished needs no second acceptance`() =
        played.getValue(APPLIED).getOrThrow()

    /**
     * WR P1-2 and P1-4. The host is Studio's shape: a kept answer is found by the request's key (D-430). The user's accept
     * applies and the entry dies right after the applied record; an unverified item is then added at the same revision —
     * the stored accept does not name it, so the user is asked again under a new key. The user accepts that; the entry dies
     * between `Finishing` and `Finished`; the reopen completes on the applied record with no check and no question.
     */
    private suspend fun appliedThenStrengthened(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        val kept = HashSet<String>()
        s.decide { r ->
            asked += r
            if (r.key in kept) AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "accepted as it is") else null
        }
        val first = s.play(::edit)
        assertEquals(CampaignOutcome.WaitingForInput, first.outcome, first.state?.reason)
        val request = asked.last { it.incrementId == null }
        kept += request.key
        s.reopen()
        s.controller.crashAfterFinalApplied = { throw Crash() }
        val applied = runCatching { s.play { emptyList() } }.exceptionOrNull()
        s.controller.crashAfterFinalApplied = null
        assertTrue(applied is Crash, "the fault point after the applied record did not fire: $applied")

        s.campaign!!.contracts.strengthen(s.request.work, Acceptance.Run("AC-2", printing, Origin.Model("AC-1")))
        s.reopen()
        val strengthened = s.play { emptyList() }
        assertEquals(CampaignOutcome.WaitingForInput, strengthened.outcome, strengthened.state?.reason)
        val reasked = asked.last { it.incrementId == null }
        assertNotEquals(request.key, reasked.key, "P1-2: the new obligation set is a new question")
        assertTrue(reasked.items.any { it.obligation == "AC-2" }, "P1-2: the user is asked about AC-2: ${reasked.items.map { it.obligation }}")

        kept += reasked.key
        s.reopen()
        val held = receipts(s.campaign!!)
        s.controller.crashAfterFinishing = { throw Crash() }
        val finishing = runCatching { s.play { emptyList() } }.exceptionOrNull()
        s.controller.crashAfterFinishing = null
        assertTrue(finishing is Crash, "the fault point between Finishing and Finished did not fire: $finishing")
        val questions = asked.size
        s.reopen()
        val done = s.play { emptyList() }
        assertEquals(CampaignOutcome.Completed, done.outcome, "P1-4: ${done.state?.reason}")
        assertEquals(held, receipts(s.campaign!!), "P1-4: a check ran after the accept")
        assertEquals(questions, asked.size, "P1-4: asked again: ${asked.drop(questions).map { (it.incrementId ?: "campaign") to it.items.map { i -> i.obligation } }}")
        assertTrue(s.adapter!!.calls.isEmpty(), "P1-4: the model was called")
    }

    private suspend fun reEntryThenAccept(s: Scenario) {
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

        // WF-6: the user accepts the request they saw; the next entry applies it — and dies right after the applied record.
        answer = AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, "user", "the rewritten data file is expected")
        s.reopen()
        val held = receipts(s.campaign!!)
        s.controller.crashAfterFinalApplied = { throw Crash() }
        val crashed = runCatching { s.play { emptyList() } }.exceptionOrNull()
        s.controller.crashAfterFinalApplied = null
        expect(crashed is Crash) { "the fault point between the applied record and Finishing did not fire: $crashed" }
        // The reopen after the crash resumes on the applied record: nothing runs after the accept.
        s.reopen()
        val third = s.play { emptyList() }
        expect(third.outcome == CampaignOutcome.Completed) { "WF-6: the accepted candidate ended ${third.outcome}: ${third.state?.reason}" }
        expect(receipts(s.campaign!!) == held) { "WF-6: ${receipts(s.campaign!!) - held} checks ran after the accept" }
        val attempts = s.counted(CountedPhase.Finish).map { it.finishAttempts }
        expect(attempts == listOf(1, 1, 1, 1)) { "one finalization attempt per entry: $attempts" }
        assertTrue(broken.isEmpty(), broken.joinToString("\n"))
    }

    private suspend fun voidThenPolicyAccept(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        var answer: AcceptanceDecision? = null
        var editing = true
        var policy = false
        var atAccept = -1
        s.decide { r ->
            asked += r
            when {
                editing -> {
                    editing = false
                    Files.writeString(s.root.resolve("src/util.py"), "def clamp(x, lo, hi):\n    return min(max(x, lo), hi)\n")
                    AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "done")
                }
                policy -> {
                    atAccept = receipts(s.campaign!!)
                    AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "auto mode accepts what could not be verified")
                }
                else -> answer?.takeIf { it.requestId == r.id }
            }
        }
        val voided = s.play(::edit)
        val reason = voided.state?.reason.orEmpty()
        assertEquals(CampaignOutcome.BlockedExternal, voided.outcome, reason)
        assertTrue("src/util.py" in reason, "the void names the moved path: $reason")
        assertFalse(DirtyRepo.DATA in reason, "the gate's own write is no move of the candidate it fixed: $reason")

        // The moved tree is new information: the end checks run again and a new request waits.
        s.reopen()
        assertEquals(CampaignOutcome.WaitingForInput, s.play { emptyList() }.outcome)
        val request = asked.last { it.incrementId == null }
        // The host adds an executable item at the same revision, then accepts the request it saw: that answer leaves it out.
        s.campaign!!.contracts.strengthen(s.request.work, Acceptance.Run("AC-2", printing, Origin.Model("AC-1")))
        answer = AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, "user", "done")
        s.reopen()
        val before = receipts(s.campaign!!)
        val after = s.play { emptyList() }
        assertEquals(CampaignOutcome.WaitingForInput, after.outcome, after.state?.reason)
        val reasked = asked.last { it.incrementId == null }
        assertNotEquals(request.id, reasked.id, "the stored results do not answer the new obligation set")
        assertTrue(reasked.items.any { it.obligation == "AC-2" }, "the new item is put to the decider: ${reasked.items.map { it.obligation }}")
        assertTrue(receipts(s.campaign!!) > before, "the end checks ran again for the new obligation set")

        // WF-6 (policy, the 5 October auto mode): the policy accepts the request now waiting; nothing runs after its accept.
        policy = true
        s.reopen()
        val accepted = s.play { emptyList() }
        assertEquals(CampaignOutcome.Completed, accepted.outcome, accepted.state?.reason)
        assertEquals(atAccept, receipts(s.campaign!!), "WF-6: no check runs after the policy's accept")
        assertEquals(listOf(1, 1, 1, 1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
    }

    private suspend fun redGate(s: Scenario) {
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
    }

    private class Crash : RuntimeException("injected crash after the applied record")

    /** The plan cell is skipped (the acceptance is executable); one cell edits, verifies `AC-1` and reports done. */
    private fun edit(c: OpenedCampaign): List<Scripted> = Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    /** The receipts the campaign's store holds: a check that ran adds one. */
    private fun receipts(c: OpenedCampaign): Int = c.store.db.query("SELECT body FROM receipts") { it.string("body") }.size

    /** S1 over the dirty repository with `AC-1` a plain printing check and [gate] the campaign's quality gate; [name] keeps its store apart. */
    private fun finalization(name: String, gate: DirtyRepo.() -> Command = { check }, body: suspend (Scenario) -> Unit) = runBlocking {
        DirtyRepo.create(1, DirtyRepo.Variant.RewritesData, bigBytes = 0, settle = false).use { dirty ->
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
        const val FAILING = "pytest_fail.txt"
        const val REENTRY = "re-entry"
        const val VOID = "void"
        const val RED = "red"
        const val APPLIED = "applied"

        val printing: Command = shell("type ${DirtyRepo.OUTPUT}", "cat ${DirtyRepo.OUTPUT}")
        val red: Command = shell("type $FAILING & exit /b 1", "cat $FAILING; exit 1")

        fun shell(windows: String, posix: String): Command =
            if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

        fun recorded(name: String): String =
            FinalizationScenarioTest::class.java.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
