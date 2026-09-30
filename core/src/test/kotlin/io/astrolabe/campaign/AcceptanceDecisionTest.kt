package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Request
import io.astrolabe.store.Store
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import io.astrolabe.verify.Finding
import io.astrolabe.verify.FindingKind
import io.astrolabe.verify.ObligationKind
import io.astrolabe.verify.ProvenanceKind
import io.astrolabe.verify.ResultStatus
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Severity
import io.astrolabe.verify.StopCode
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 0 (D-337–D-342) through the real controller: what cannot be verified waits for a decision — never `blocked` or
 * `failed` (I1) — the decision and the wait cost no model call (I5), a red test is never accepted (I2), a rejection
 * reaches the cell whole and is not reviewed twice (I3, I4), and provenance says who accepted what (I7).
 */
class AcceptanceDecisionTest {
    @TempDir
    lateinit var stateRoot: Path
    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-30T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-decide"), AttemptId("a1"), "document function a")
    private val policy = CampaignPolicy(Tokens(400_000))

    /** A test runner that is not installed: every receipt is `unavailable`. */
    private val missing = Acceptance.Run("AC-1", Command(listOf("astrolabe-missing-runner-p0")), Origin.User)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a(): return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.write("pytest_fail.txt", javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() = repo.close()

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    private fun seed(vararg acceptance: Acceptance) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                shape = Shape.S0,
                scope = derived.scope.copy(writePaths = listOf("src/")),
                requirements = derived.requirements.map { it.copy(acceptance = acceptance.map { item -> item.id }) },
                acceptance = acceptance.toList(),
            ))
        }
    }

    private fun adapter(vararg replies: Scripted) = FakeAdapter(ScriptedModel.of(*replies))

    private fun model(adapter: FakeAdapter) = CellModel(adapter, FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)

    private fun texts(request: Request): String =
        request.segments.flatMap { it.items }.filterIsInstance<io.astrolabe.provider.Message>().joinToString("\n") { it.text }

    /** The host authority of a test: records decision and review requests and answers them as told. */
    private class Host(
        val decide: (AcceptanceDecisionRequest) -> AcceptanceDecision? = { null },
        val verdict: (ReviewRequest) -> Verdict? = { null },
    ) : Authority by AutonomousAuthority() {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        val reviews = ArrayList<ReviewRequest>()

        override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
            asked += request
            return decide.invoke(request)
        }

        override suspend fun review(request: ReviewRequest): Verdict? {
            reviews += request
            return verdict.invoke(request)
        }
    }

    private fun answer(kind: DecisionKind, decider: Decider = Decider.User, by: String = "user:test", reason: String = "checked it by hand") =
        { r: AcceptanceDecisionRequest -> AcceptanceDecision(r.id, r.contractRevision, r.candidate, kind, decider, by, reason) }

    @Test
    fun `an unverifiable check waits for a decision, and accepting it later costs no model call (I1, I5, I7)`() = runBlocking<Unit> {
        seed(missing)
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter(Scripted.Reply(listOf(say("done"))))
            val none = Host()
            val run = controller().run(c, model(adapter), none)
            assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
            assertEquals(StopCode.AcceptanceDecision, run.state?.stopCode)
            assertEquals(1, adapter.calls.size, "the cell ends at its first completion proposal")
            val item = none.asked.single().items.single()
            assertEquals("AC-1" to ResultStatus.Unverified, item.obligation to item.status)
            assertTrue(item.reason.contains("cannot start"), item.reason)
        }
        controller().open(repo.root, request, policy).use { c ->
            assertNotNull(Acceptances(c.store, clock).open(request.work, request.attempt), "the pending completion survives the store")
            val adapter = adapter()
            val none = Host()
            val run = controller().run(c, model(adapter), none)
            assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
            assertEquals(0, adapter.calls.size, "waiting again costs nothing")
            assertEquals(1, none.asked.size)
        }
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter()
            val yes = Host(answer(DecisionKind.Accept))
            val run = controller().run(c, model(adapter), yes)
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(0, adapter.calls.size, "an accept completes with no model call")
            val finish = assertNotNull(run.finish)
            val line = finish.acceptance.single { it.id == "AC-1" }
            assertEquals(listOf("accepted", "accepted", "user:test", "user", "checked it by hand"), listOf(line.status, line.provenance, line.acceptedBy, line.decider, line.acceptedReason))
            assertTrue(finish.notVerified.any { it.startsWith("AC-1: accepted without verification by user:test") }, finish.notVerified.toString())
            assertEquals(ProvenanceKind.Accepted, c.state!!.ledger.entries.getValue("R1").provenance.single().how)
            assertNull(Acceptances(c.store, clock).open(request.work, request.attempt), "the pending completion is applied")
        }
    }

    @Test
    fun `a policy decision completes in the same run and is labelled as the policy's`() = runBlocking<Unit> {
        seed(missing)
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter(Scripted.Reply(listOf(say("done"))))
            val run = controller().run(c, model(adapter), Host(answer(DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "not verified: runner missing")))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(1, adapter.calls.size)
            val line = assertNotNull(run.finish).acceptance.single { it.id == "AC-1" }
            assertEquals("policy" to "not verified: runner missing", line.decider to line.acceptedReason)
        }
    }

    @Test
    fun `a red test is never put to a decision nor accepted (I2)`() = runBlocking<Unit> {
        val red = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_fail.txt & exit /b 1")) else Command(listOf("/bin/sh", "-c", "cat pytest_fail.txt; exit 1"))
        seed(Acceptance.Run("AC-1", red, Origin.User))
        controller().open(repo.root, request, policy).use { c ->
            val yes = Host(answer(DecisionKind.Accept))
            val run = controller().run(c, model(adapter(Scripted.Reply(listOf(say("done"))), Scripted.Reply(listOf(say("done"))))), yes)
            assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
            assertTrue(yes.asked.none { r -> r.items.any { it.kind == ObligationKind.Run && it.status == ResultStatus.Failed } })
        }
    }

    @Test
    fun `a decision about an old tree is void - a changed file sends the work back to a cell (A4)`() = runBlocking<Unit> {
        seed(missing)
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(CampaignOutcome.WaitingForInput, controller().run(c, model(adapter(Scripted.Reply(listOf(say("done"))))), Host()).outcome)
        }
        repo.write("src/a.py", "def a(): return 2\n")
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter(Scripted.Reply(listOf(say("done again"))))
            val yes = Host(answer(DecisionKind.Accept))
            val run = controller().run(c, model(adapter), yes)
            assertEquals(1, adapter.calls.size, "the stale pending completion is void: a cell runs")
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertTrue(Acceptances(c.store, clock).pending(request.work, request.attempt).first().status == PendingStatus.Void)
        }
    }

    @Test
    fun `rework runs one continuation with the decider's words pinned (A4, D-340)`() = runBlocking<Unit> {
        seed(missing)
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(CampaignOutcome.WaitingForInput, controller().run(c, model(adapter(Scripted.Reply(listOf(say("done"))))), Host()).outcome)
        }
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter(Scripted.Reply(listOf(say("added the docstring"))))
            var asked = 0
            val host = Host({ r -> asked++; if (asked == 1) answer(DecisionKind.Rework, reason = "add a docstring to a")(r) else null })
            val run = controller().run(c, model(adapter), host)
            assertEquals(1, adapter.calls.size, "one continuation cell")
            assertTrue("rework requested by user:test: add a docstring to a" in texts(adapter.calls.first().request))
            assertEquals(CampaignOutcome.WaitingForInput, run.outcome, "the unchanged candidate asks again")
            assertEquals(2, host.asked.size)
            assertTrue(Acceptances(c.store, clock).decisions(request.work, request.attempt).single().spent)
        }
    }

    @Test
    fun `a review rejection reaches the cell whole, is not asked twice, and stands for the user after its round (A5)`() = runBlocking<Unit> {
        seed(Acceptance.Check("AC-2", "the docstring explains a", Origin.User))
        val findings = listOf(
            Finding(Severity.Major, "src/a.py:1", "no docstring", kind = FindingKind.Correctness),
            Finding(Severity.Blocker, "src/a.py:1", "a returns the wrong value", kind = FindingKind.Correctness),
            Finding(Severity.Major, "src/b.py:3", "b is missing", kind = FindingKind.Contract),
        )
        controller().open(repo.root, request, policy).use { c ->
            val path = "src/a.py"
            val adapter = adapter(
                Scripted.Reply(listOf(say("done"))),
                Scripted.Reply(listOf(say("done, unchanged"))),
            )
            val host = Host(verdict = { r -> Verdict(r.id, r.contractRevision, r.candidate, VerdictOutcome.Revise, findings, confidence = 0.9, signedBy = "host:reviewer") })
            val run = controller().run(c, model(adapter), host)
            assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
            assertEquals(StopCode.ReviewRejected, run.state?.stopCode)
            assertEquals(2, adapter.calls.size, "one rework round, then the user decides")
            val second = texts(adapter.calls[1].request)
            findings.forEach { assertTrue(it.issue in second, "finding '${it.issue}' reaches the same cell: $second") }
            assertTrue("review of AC-2 by host:reviewer" in second)
            assertEquals(1, host.reviews.size, "the unchanged candidate is reviewed once (I3)")
            assertEquals(1, host.asked.size)
            assertEquals(StopCode.ReviewRejected, host.asked.single().code)
            assertTrue(c.registry.version(path) != null)
        }
        controller().open(repo.root, request, policy).use { c ->
            val adapter = adapter()
            val host = Host(answer(DecisionKind.Accept, reason = "fine as it is"))
            val run = controller().run(c, model(adapter), host)
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(0, adapter.calls.size)
            assertTrue(host.reviews.isEmpty(), "the stored rejection is reused, never asked again")
            assertEquals("accepted", assertNotNull(run.finish).acceptance.single { it.id == "AC-2" }.status)
        }
    }
}
