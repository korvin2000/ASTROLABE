package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Outcome
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Message
import io.astrolabe.recover.AcceptanceCheck
import io.astrolabe.recover.CorrectedCall
import io.astrolabe.recover.FailureClass
import io.astrolabe.recover.GuardLimits
import io.astrolabe.recover.Guards
import io.astrolabe.recover.Recovery
import io.astrolabe.recover.Repair
import io.astrolabe.recover.RepairOutcome
import io.astrolabe.recover.RepairProposal
import io.astrolabe.recover.AlternativeDecision
import io.astrolabe.recover.GuardVerdict
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.EscalationChange
import io.astrolabe.route.Router
import io.astrolabe.route.SubstantiveAttempt
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P5.8.1 recovery wiring (D-171, D-254): verified failures of a live campaign go through `Ladder.recover` and the
 * campaign's `Guards`, which a resumed controller rebuilds from the journal; nudges and an opened alternative reach
 * the increment's next cell; a trip asks; a granted capsule repair never turns a failure into success.
 */
class RecoveryCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-rec"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val failing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "exit 1")) else Command(listOf("/bin/sh", "-c", "exit 1"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val plan = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"}]}"""

    private fun seed(attempts: Int) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                shape = Shape.S1,
                requirements = listOf(
                    Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                    Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = derived.requests.single().id),
                ),
                acceptance = listOf(Acceptance.Run("AC-1", failing, Origin.User), Acceptance.Run("AC-2", failing, Origin.User)),
                budget = derived.budget.copy(attempts = attempts),
            ))
        }
    }

    private fun texts(adapter: FakeAdapter, call: Int): String =
        adapter.calls[call].request.segments.flatMap { it.items }.filterIsInstance<Message>().joinToString("\n") { it.text }

    private fun boundary(c: OpenedCampaign): List<String> =
        c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).map { it.text }

    @Test
    fun `repeated verified failures nudge, open an alternative, trip the no-progress budget and survive a resume`() = runBlocking<Unit> {
        seed(attempts = 4)
        val ctl = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)
        // The plan cell, then cells that claim completion without evidence: each is a stalled completion of I1.
        val replies = listOf<Scripted>(
            Scripted.Reply(listOf(say("planning"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
            Scripted.Reply(listOf(say("plan ready"))),
        ) + List(8) { Scripted.Reply(listOf(say("done"))) }
        val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))
        val recorded: List<String>
        ctl.open(repo.root, request, policy).use { c ->
            val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            val lines = boundary(c)
            val recovered = lines.filter { it.startsWith("recover BehaviouralTestFailure of I1") }
            assertEquals(4, recovered.size, lines.joinToString("\n"))
            assertTrue(recovered[0].endsWith("return · guards: pass"), recovered[0])
            assertTrue(recovered.drop(1).all { "return · guards: no progress" in it }, recovered.toString())
            assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
            assertTrue(run.state!!.reason!!.startsWith("no progress: 3 equivalent failures across the campaign"), run.state!!.reason)

            // §13.3: the same hypothesis failed twice, but the ladder already stands at ExtraHigh on it: the same label again is refused.
            val refused = lines.filter { it.startsWith("alternative attempt of I1 refused: ") }
            assertTrue(refused.isNotEmpty() && refused.all { "on the same hypothesis under a new label is not recovery" in it }, refused.toString())
            // The nudge reaches the increment's next cell (call 6: plan 0–1, then two calls per stalled cell).
            val third = texts(adapter, 6)
            assertTrue("no progress: the same failure, fix and state as in" in third, third)
            recorded = CampaignRecovery(c.journal, idGen, clock, request.work, GuardLimits.of(Defaults())).noProgressEvents
            assertEquals(3, recorded.size)
        }
        // A resumed controller rebuilds the same guard state from the journal: a resume never replenishes the budget.
        Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen).open(repo.root, request, policy).use { c ->
            assertEquals(recorded, CampaignRecovery(c.journal, idGen, clock, request.work, GuardLimits.of(Defaults())).noProgressEvents)
        }
    }

    @Test
    fun `a hypothesis that failed twice below the top tier opens an alternative attempt pinned for the next cell`() {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val journal = Journal(store, clock)
            val ids = Identities(request.work, request.attempt, context = ContextId("cell-2"))
            val stamp = CandidateId(Digest("b".repeat(64)))
            val recovery = CampaignRecovery(journal, idGen, clock, request.work, GuardLimits.of(Defaults()))
            val hypothesis = "scale inside total"
            recovery.failed(ids, "I1", FailureClass.BehaviouralTestFailure, "AC-1 red", listOf("rcpt-1"), hypothesis, stamp)
            recovery.failed(ids, "I1", FailureClass.BehaviouralTestFailure, "AC-1 red", listOf("rcpt-2"), hypothesis, stamp)
            val allowance = AttemptAllowance("I1", 4, listOf(
                SubstantiveAttempt(AttemptId("a1"), Tier.Medium, "main", hypothesis, evidenceRefs = listOf("rcpt-1")),
                SubstantiveAttempt(AttemptId("a1"), Tier.High, "main", hypothesis, EscalationChange.StrongerModel, listOf("rcpt-2")),
            ))
            val register = Register.empty(ContextId("cell-2"), "I1", "a returns 10")
            val opened = assertIs<AlternativeDecision.Opened>(recovery.alternative(ids, allowance, register, listOf("rcpt-3"), 1, hypothesis)).alternative
            assertEquals(Tier.ExtraHigh, opened.substantive.tier)
            assertEquals(listOf("rcpt-2", "rcpt-3"), opened.keptReceipts, "the previous attempt's evidence and the caller's receipts are kept")
            val line = recovery.lines("I1").last()
            assertTrue(line.startsWith("alternative attempt ${opened.attempt.value} replaces a1: "), line)
            assertTrue(journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).any { it.text == line })
        }
    }

    @Test
    fun `a build or environment failure gets one scoped repair that never reports a failure fixed`() = runBlocking<Unit> {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val journal = Journal(store, clock)
            val ids = Identities(request.work, request.attempt, context = ContextId("cell-1"))
            val stamp = CandidateId(Digest("a".repeat(64)))
            val limits = GuardLimits.of(Defaults())
            val recovery = CampaignRecovery(journal, idGen, clock, request.work, limits)
            val kind = CampaignRecovery.classify(listOf(Outcome.InfraError))
            assertEquals(FailureClass.BuildEnvironment, kind)
            assertEquals(FailureClass.BehaviouralTestFailure, CampaignRecovery.classify(listOf(Outcome.Failed)))

            val routed = recovery.failed(ids, "I1", kind, "pytest: error: unrecognized arguments --cov", listOf("rcpt-1"), "unstated", stamp)
            assertIs<Recovery.Repair>(routed.recovery)
            val policy = RoutingPolicy(TierTable("t1", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"))), FakeProfiles.all.filterKeys { it in setOf("helper", "main") })
            val packet = RoutingPacket(null, 2_000, 1_000)
            // The helper claims a fix; the original acceptance still fails, so it is never reported fixed.
            var checked = 0
            val repair = Repair(Router(), { RepairProposal.Fixed(CorrectedCall("run.run", """{"argv":["pip","install","pytest-cov"]}"""), "installed") }, {
                checked++
                AcceptanceCheck(false, "AC-1 still red")
            }, HeuristicEstimator())
            val outcome = recovery.repair(ids, "I1", routed, listOf("AC-1"), emptyMap(), Tokens(50_000), Shape.S2, packet, policy, repair)
            assertIs<RepairOutcome.Escalated>(outcome)
            assertEquals(2, checked, "each claimed fix is re-checked against the original acceptance")
            assertEquals(listOf(outcome.diagnosis), recovery.lines("I1"))

            // The one repair is spent, durably: the next failure of the same signature returns to its handler, also after a resume.
            val again = recovery.failed(ids, "I1", kind, "pytest: error: unrecognized arguments --cov", listOf("rcpt-2"), "unstated", stamp)
            assertTrue(assertIs<Recovery.Return>(again.recovery).reason.startsWith("repair spent (1)"))
            val resumed = CampaignRecovery(journal, idGen, clock, request.work, limits)
            val third = resumed.failed(ids, "I1", kind, "pytest: error: unrecognized arguments --cov", listOf("rcpt-3"), "unstated", stamp)
            assertIs<Recovery.Return>(third.recovery)
            assertEquals(2, resumed.noProgressEvents.size, "the replayed guards count the earlier equivalent failure")
            assertEquals(Guards.NO_PROGRESS, (third.verdict as GuardVerdict.Nudge).guard)

            // Below S2 the helper is never called.
            val s1 = recovery.repair(ids, "I2", recovery.failed(ids, "I2", kind, "missing module", listOf("rcpt-4"), "unstated", stamp), listOf("AC-2"), emptyMap(), Tokens(50_000), Shape.S1, packet, policy, repair)
            assertTrue(assertIs<RepairOutcome.Escalated>(s1).reason.startsWith("not available in S1"), s1.reason)
            assertEquals(2, checked)
        }
    }
}
