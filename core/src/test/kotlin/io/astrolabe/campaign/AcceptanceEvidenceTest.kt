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
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
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
import io.astrolabe.verify.Checks
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
            assertEquals("assessed" to "reviewed", assertNotNull(result.finish).acceptance.single().let { it.status to it.provenance })
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
    fun `human integrity approval routes a required test edit to the host instead of the review cell`() = runBlocking<Unit> {
        seed(Acceptance.Review("AC-R", "a maintainer approves a", Origin.User))
        val human = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, integrityApproval = io.astrolabe.IntegrityApproval.Human), clock, idGen)
        human.open(repo.root, request, policy).use { c ->
            val path = "tests/test_a.py"
            val plan = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1","AC-R"],"write_scope":["src/","tests/"],"expected_files":1,"produces":"artifact"}]}"""
            val reviewer = Reviewer()
            val result = human.run(c, model(
                Scripted.Reply(listOf(call("plan", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
                Scripted.Reply(listOf(say("plan ready"))),
                Scripted.Reply(listOf(read("read-test", path))),
                Scripted.Reply(listOf(anchored("edit-test", path, c.registry.version(path)!!, "    assert 1 == 1", "    assert (1 == 1)"))),
                Scripted.Reply(listOf(say("done"))),
                // Offered to a review cell; under Human it must not be the flag's approver.
                Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
            ), reviewer, maxCells = 1)
            assertTrue(reviewer.requests.isNotEmpty(), result.state?.reason)
            val flag = assertNotNull(result.exit, result.state?.reason).packet.flags.testIntegrity.single { it.path == path }
            assertEquals("host:alice", assertNotNull(flag.verdict, "the host's answer is attached to the flag").signedBy)
            assertTrue(flag.blocksCompletion, "a verdict that does not say a person reviewed resolves no flag under Human (C11)")
        }
    }

    /** An S0 cell edits a test file behind a required check with no Check/Review item: a flag-only review. */
    private fun s0TestEdit(approval: io.astrolabe.IntegrityApproval): Pair<Reviewer, io.astrolabe.verify.TestIntegrityFlag> = runBlocking {
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        seed(Acceptance.Run("AC-2", printing, Origin.User))
        val ctl = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, integrityApproval = approval), clock, idGen)
        ctl.open(repo.root, request, policy).use { c ->
            assertEquals(Shape.S0, c.contract.shape)
            val path = "tests/test_a.py"
            val reviewer = Reviewer()
            val result = ctl.run(c, model(
                Scripted.Reply(listOf(read("read-test", path))),
                Scripted.Reply(listOf(anchored("edit-test", path, c.registry.version(path)!!, "    assert 1 == 1", "    assert (1 == 1)"))),
                Scripted.Reply(listOf(say("done"))),
                Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
            ), reviewer, maxCells = 1)
            val flag = assertNotNull(result.exit, result.state?.reason).packet.flags.testIntegrity.single { it.path == path }
            assertTrue(flag.requiredChecks.isNotEmpty(), "the edit must touch a required check")
            reviewer to flag
        }
    }

    @Test
    fun `S0 under autonomous integrity approval resolves a required test edit through the review cell`() {
        val (reviewer, flag) = s0TestEdit(io.astrolabe.IntegrityApproval.Autonomous)
        assertTrue(reviewer.requests.isEmpty(), "the host is only the fallback")
        val verdict = assertNotNull(flag.verdict, "the flag must be resolved")
        assertTrue(verdict.approved)
        assertNotEquals("host:alice", verdict.signedBy)
    }

    @Test
    fun `S0 under human integrity approval asks the host about a required test edit, and a model's answer leaves it waiting for a person`() {
        val (reviewer, flag) = s0TestEdit(io.astrolabe.IntegrityApproval.Human)
        assertTrue(reviewer.requests.isNotEmpty() && reviewer.requests.all { it.humanOnly })
        assertEquals("host:alice", assertNotNull(flag.verdict, "the host's answer is attached to the flag").signedBy)
        assertTrue(flag.blocksCompletion, "a verdict that does not say a person reviewed resolves no flag under Human (C11)")
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

    @Test
    fun `a declared acceptance command the model runs is the acceptance receipt, with no second run at the stop`() = runBlocking<Unit> {
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                requirements = derived.requirements.map { it.copy(acceptance = listOf("AC-1")) },
                acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User)),
            ))
        }
        controller().open(repo.root, request, policy).use { c ->
            val line = if (WINDOWS) "type pytest_pass.txt" else "cat pytest_pass.txt"
            val adapter = FakeAdapter(ScriptedModel.of(
                Scripted.Reply(listOf(call("t1", "run", """{"cmd":"$line"}"""))),
                Scripted.Reply(listOf(say("done"))),
            ))
            val result = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000))
            assertEquals(CampaignOutcome.Completed, result.outcome, result.state?.reason)
            assertEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
            assertTrue(adapter.calls.any { it.request.toString().contains("receipt CHK-accept-AC-1: accept AC-1: ✓") }, "the run result itself carries the receipt")
            val receipts = SqliteReceipts(c.store, clock).forCheck(Checks.acceptId("AC-1"))
            assertEquals(1, receipts.size, "verify-on-stop reused the run's receipt instead of running the command again")
            assertEquals(Outcome.Passed, receipts.single().outcome)
            assertTrue(receipts.single().independent)
        }
    }

    @Test
    fun `a red check of the model's own on the final tree does not block completion`() = runBlocking<Unit> {
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        repo.write("pytest_fail.txt", javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        // A legacy test with someone else's failure, run by the model as its own check.
        val legacy = if (WINDOWS) {
            repo.write("pytest.cmd", "@type pytest_fail.txt\r\n@exit /b 1\r\n")
            "pytest.cmd"
        } else {
            repo.write("pytest", "#!/bin/sh\ncat pytest_fail.txt\nexit 1\n")
            java.nio.file.Files.setPosixFilePermissions(repo.root.resolve("pytest"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
            "./pytest"
        }
        repo.commit("legacy test runner")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                requirements = derived.requirements.map { it.copy(acceptance = listOf("AC-1")) },
                acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User)),
            ))
        }
        controller().open(repo.root, request, policy).use { c ->
            val result = controller().run(c, model(
                Scripted.Reply(listOf(call("t1", "run", """{"argv":["$legacy","tests/test_legacy.py"]}"""))),
                Scripted.Reply(listOf(say("done"))),
            ))
            val own = c.checks.all().single { it.id.startsWith(Checks.MODEL_PREFIX) }
            assertEquals(Outcome.Failed, own.last?.outcome, "the scenario has a red check of the model's own")
            assertEquals(CampaignOutcome.Completed, result.outcome, result.state?.reason)
            assertEquals(RequirementStatus.Verified, c.state!!.ledger.entries.getValue("R1").status)
        }
    }
}
