package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.RequirementStatus
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
import io.astrolabe.store.Store
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Regression coverage for evidence crossing the controller/cell completion boundary (F-082, F-089). */
class AcceptanceEvidenceTest {
    @TempDir
    lateinit var stateRoot: Path
    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-28T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-evidence"), AttemptId("a1"), "inspect a")
    private val policy = CampaignPolicy(Tokens(400_000))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        // Match the campaign fixtures: Windows uses explicit acceptance commands; POSIX also declares a full suite.
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a(): return 1\n")
        repo.write("tests/test_a.py", "def test_a():\n    assert 1 == 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() = repo.close()

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    private fun seed(item: Acceptance, withRun: Boolean = true) {
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        val acceptance = if (withRun) listOf(Acceptance.Run("AC-1", printing, Origin.User), item) else listOf(item)
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                shape = if (item is Acceptance.Review) Shape.S2 else Shape.S0,
                scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                requirements = derived.requirements.map { it.copy(acceptance = acceptance.map { item -> item.id }) },
                acceptance = acceptance,
            ))
        }
    }

    private fun model(vararg replies: Scripted) = CellModel(FakeAdapter(ScriptedModel.of(*replies)), FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)

    private class Reviewer(private val staleRevision: Boolean = false) : Authority by AutonomousAuthority() {
        val requests = ArrayList<ReviewRequest>()
        override suspend fun review(request: ReviewRequest): Verdict {
            requests += request
            return Verdict(request.id, request.contractRevision + if (staleRevision) 1 else 0, request.candidate,
                VerdictOutcome.Approve, confidence = 0.9, signedBy = "host:alice")
        }
    }

    @Test
    fun `current host assessment lets a Check obligation complete through the production gate`() = runBlocking<Unit> {
        val criterion = "the implementation of a is readable"
        seed(Acceptance.Check("AC-C", criterion, Origin.User), withRun = false)
        controller().open(repo.root, request, policy).use { c ->
            val reviewer = Reviewer()
            val result = controller().run(c, model(Scripted.Reply(listOf(say("done")))), reviewer)
            assertEquals(CampaignOutcome.Completed, result.outcome, result.state?.reason)
            assertTrue(reviewer.requests.any { it.criteria.any { text -> criterion in text } })
            assertEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
            assertEquals("accepted", assertNotNull(result.finish).acceptance.single().status)
        }
    }

    @Test
    fun `a Check obligation without a host assessment remains unverified`() = runBlocking<Unit> {
        seed(Acceptance.Check("AC-C", "the implementation of a is readable", Origin.User))
        controller().open(repo.root, request, policy).use { c ->
            val result = controller().run(c, model(Scripted.Reply(listOf(say("I assessed AC-C and it passes")))))
            assertNotEquals(CampaignOutcome.Completed, result.outcome)
            assertNotEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
        }
    }

    @Test
    fun `a host assessment for another contract revision cannot complete a Check obligation`() = runBlocking<Unit> {
        seed(Acceptance.Check("AC-C", "the implementation of a is readable", Origin.User))
        controller().open(repo.root, request, policy).use { c ->
            val reviewer = Reviewer(staleRevision = true)
            val result = controller().run(c, model(Scripted.Reply(listOf(say("done")))), reviewer)
            assertTrue(reviewer.requests.isNotEmpty(), "the host must receive the actual criterion before assessing it")
            assertNotEquals(CampaignOutcome.Completed, result.outcome)
            assertNotEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
        }
    }

    @Test
    fun `host approval resolves a justified required test edit at cell completion`() = runBlocking<Unit> {
        seed(Acceptance.Check("AC-C", "the test still checks its original condition", Origin.User))
        controller().open(repo.root, request, policy).use { c ->
            val path = "tests/test_a.py"
            val reviewer = Reviewer()
            val result = controller().run(c, model(
                Scripted.Reply(listOf(read("read-test", path))),
                Scripted.Reply(listOf(anchored("edit-test", path, c.registry.version(path)!!, "    assert 1 == 1", "    assert (1 == 1)"))),
                Scripted.Reply(listOf(say("done"))),
            ), reviewer)
            assertEquals(CampaignOutcome.Completed, result.outcome, result.state?.reason)
            val flag = assertNotNull(result.exit).packet.flags.testIntegrity.single { it.path == path }
            assertTrue(flag.requiredChecks.isNotEmpty(), "the regression must exercise a required acceptance surface")
            assertTrue(flag.verdict?.approved == true)
            assertFalse(flag.blocksCompletion)
            assertTrue(reviewer.requests.isNotEmpty())
        }
    }

    @Test
    fun `an approved increment review reaches the implementing cell completion gate`() = runBlocking<Unit> {
        seed(Acceptance.Review("AC-R", "a maintainer approves a", Origin.User))
        controller().open(repo.root, request, policy).use { c ->
            val plan = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1","AC-R"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
            val result = controller().run(c, model(
                Scripted.Reply(listOf(call("plan", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
                Scripted.Reply(listOf(say("plan ready"))),
                Scripted.Reply(listOf(call("review", "verify", """{"what":"review","scope":"increment"}"""))),
                Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
                Scripted.Reply(listOf(say("done"))),
            ), Reviewer(), maxCells = 1)
            assertEquals(CampaignOutcome.Completed, result.outcome, result.state?.reason)
            assertEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
        }
    }
}
