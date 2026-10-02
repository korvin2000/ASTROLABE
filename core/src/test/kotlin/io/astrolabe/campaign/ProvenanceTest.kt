package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.IntegrityApproval
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
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
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.evidence.EvidenceKind
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.store.Store
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.Author
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Currency
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import io.astrolabe.verify.DecisionRecord
import io.astrolabe.verify.ObligationKind
import io.astrolabe.verify.ObligationResult
import io.astrolabe.verify.ProvenanceClass
import io.astrolabe.verify.ProvenanceKind
import io.astrolabe.verify.Resolution
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.ResultStatus
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.RiskAcceptor
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The provenance axis (§4.4 C2): who set each requirement, who created each check, what ran on which tree, who took the
 * residual risk, and the class the finish receipt and `campaign.finished` carry while the outcome stays `completed`.
 */
class ProvenanceTest {
    @TempDir
    lateinit var stateRoot: Path
    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-02T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-provenance"), AttemptId("a1"), "document function a")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
    private val missing = Command(listOf("astrolabe-missing-runner-c2"))
    private val passed = ResultStatus.Passed
    private val failed = ResultStatus.Failed
    private val unverified = ResultStatus.Unverified

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a(): return 1\n")
        repo.write("tests/test_a.py", "def test_a():\n    assert 1 == 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() = repo.close()

    /** Opens the contract with [acceptance]; `R1` is the user's request checked by [r1], [host] adds `R2` set by the host. */
    private fun seed(acceptance: List<Acceptance>, r1: List<String>, host: List<String>? = null) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val requirements = derived.requirements.map { it.copy(acceptance = r1) } +
                listOfNotNull(host?.let { Requirement("R2", "keep the suite green", it, authorityRef = "host:setup") })
            // Two requirements select S1; its run-only contract is its own plan, so no plan cell runs.
            contracts.open(derived.copy(shape = if (host == null) Shape.S0 else Shape.S1, scope = derived.scope.copy(writePaths = listOf("src/", "tests/")),
                requirements = requirements, acceptance = acceptance))
        }
    }

    /** A host that has no reviewer and accepts what could not be verified as [decider]. */
    private fun accepting(decider: Decider, by: String) = object : Authority by AutonomousAuthority() {
        override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision =
            AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, decider, by, "not verified: no reviewer")
    }

    /** Runs the seeded campaign to its end; returns the run and its `campaign.finished` event. */
    private fun run(
        authority: Authority = AutonomousAuthority(),
        integrity: IntegrityApproval = IntegrityApproval.Autonomous,
        /** Runs before the reply at each index: what changes the tree outside the agent's tools. */
        before: (Int) -> Unit = {},
        replies: (OpenedCampaign) -> List<Scripted> = { listOf(Scripted.Reply(listOf(say("done")))) },
    ): Pair<S0Run, AgentEvent.Campaign.Finished> = runBlocking {
        val events = Events(clock)
        val recorder = EventRecorder().also(events::subscribe)
        events.use {
            val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, integrityApproval = integrity), clock, idGen, events)
            controller.open(repo.root, request, policy).use { c ->
                val turns = replies(c).mapIndexed { i, reply -> ScriptedModel.Turn({ true }, { before(i); reply }) }
                val model = CellModel(FakeAdapter(ScriptedModel(turns)), FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)
                val run = controller.run(c, model, authority)
                val finished = withTimeout(5_000) {
                    while (recorder.ofType<AgentEvent.Campaign.Finished>().isEmpty()) delay(10)
                    recorder.ofType<AgentEvent.Campaign.Finished>().single()
                }
                run to finished
            }
        }
    }

    @Test
    fun `a check's class follows whose evidence it is and whether it passed at the final tree`() {
        assertEquals(listOf(Author.User, Author.Host, Author.Host, Author.Model), listOf(Origin.User, Origin.Harness, Origin.Amended(3), Origin.Model("R1")).map(Author::of))
        assertEquals(ProvenanceClass.Independent, ProvenanceClass.item(Author.User, passed))
        assertEquals(ProvenanceClass.Independent, ProvenanceClass.item(Author.Host, passed))
        assertEquals(ProvenanceClass.AgentTest, ProvenanceClass.item(Author.Model, passed))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.item(Author.User, unverified))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.item(Author.Model, failed))
        val requests = listOf(UserRequest("U1", Instant.parse("2026-10-02T10:00:00Z"), "do it"))
        assertEquals(Author.User, Author.of(Requirement("R1", "do it", emptyList(), authorityRef = "U1"), requests))
        assertEquals(Author.Host, Author.of(Requirement("R2", "keep it green", emptyList(), authorityRef = "host:setup"), requests))
    }

    @Test
    fun `a requirement is independent when every declared check passed, else agent_test on the agent's passes, and the campaign takes the worst`() {
        assertEquals(ProvenanceClass.Independent, ProvenanceClass.requirement(listOf(passed, passed), emptyList()))
        assertEquals(ProvenanceClass.Independent, ProvenanceClass.requirement(listOf(passed), listOf(passed, failed)), "the agent's checks never lower a declared verification")
        assertEquals(ProvenanceClass.AgentTest, ProvenanceClass.requirement(emptyList(), listOf(passed)))
        assertEquals(ProvenanceClass.AgentTest, ProvenanceClass.requirement(listOf(passed, unverified), listOf(passed)), "a declared check accepted unverified leaves the agent's word")
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.requirement(listOf(failed), listOf(passed)), "a declared check that failed is never covered by the agent's")
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.requirement(emptyList(), listOf(passed, failed)))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.requirement(listOf(unverified), emptyList()))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.requirement(emptyList(), emptyList()))
        assertEquals(ProvenanceClass.AgentTest, ProvenanceClass.campaign(listOf(ProvenanceClass.Independent, ProvenanceClass.AgentTest)))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.campaign(listOf(ProvenanceClass.Independent, ProvenanceClass.Unverified, ProvenanceClass.AgentTest)))
        assertEquals(ProvenanceClass.Unverified, ProvenanceClass.campaign(emptyList()))
        assertEquals("agent_test", ProvenanceClass.AgentTest.wire)
    }

    @Test
    fun `the resolver carries the criterion's origin into item provenance and names who took the risk`() {
        val green = ObligationResult("AC-1", ObligationKind.Run, passed, "AC-1: green", "r-1", origin = Origin.Model("R1"))
        val tested = Resolver.resolve(listOf(green)).provenance.single()
        assertEquals(listOf(ProvenanceKind.Tested, Author.Model, passed, RiskAcceptor.Runtime), listOf(tested.how, tested.checkBy, tested.result, tested.riskAcceptedBy))

        val open = ObligationResult("AC-2", ObligationKind.Run, unverified, "AC-2: no receipt", origin = Origin.User)
        val accept = AcceptanceDecision("d-1", 1, CandidateId(Digest.ofUtf8("tree")), DecisionKind.Accept, Decider.Policy, "host:policy", "runner missing")
        val resolved = Resolver.resolve(listOf(open), decision = DecisionRecord("rec-1", "inc-1", accept, listOf("AC-2")))
        assertEquals(Resolution.Complete, resolved.resolution)
        val accepted = resolved.provenance.single()
        assertEquals(listOf(ProvenanceKind.Accepted, Author.User, unverified, RiskAcceptor.Policy), listOf(accepted.how, accepted.checkBy, accepted.result, accepted.riskAcceptedBy))
        assertEquals(RiskAcceptor.User, accepted.copy(decider = Decider.User).riskAcceptedBy)
    }

    @Test
    fun `a declared check green at the final tree completes independent, and the finish event says so`() {
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User)), r1 = listOf("AC-1"))
        val (run, finished) = run()
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertEquals("completed" to ProvenanceClass.Independent, finish.status to finish.provenanceClass)
        val line = finish.acceptance.single()
        assertEquals(listOf<Any?>("green", "tested", Origin.User, Author.User, printing.text, passed, "runtime", RiskAcceptor.Runtime, ProvenanceClass.Independent),
            listOf(line.status, line.provenance, line.origin, line.checkBy, line.command, line.result, line.verifiedBy, line.riskAcceptedBy, line.provenanceClass))
        assertTrue(finish.checksRun.any { it.receiptId == line.receiptId }, "the line names the receipt the check ran under")
        assertEquals(finish.stamp.hash8, line.stamp, "the receipt is the final tree's")
        val requirement = finish.requirements.single()
        assertEquals(listOf<Any?>(Author.User, listOf("AC-1"), ProvenanceClass.Independent), listOf(requirement.by, requirement.acceptance, requirement.provenanceClass))
        assertEquals(listOf("completed", "independent"), listOf(finished.outcome, finished.provenanceClass))
    }

    @Test
    fun `a user requirement checked only by the model's test is agent_test, and the campaign takes its worst requirement`() {
        // The host's R2 declares the command; the model's AC-2 strengthens R1 with the same command (D-262 lets it run).
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.Model("R1"))), r1 = listOf("AC-2"), host = listOf("AC-1"))
        val (run, finished) = run()
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        val (r1, r2) = finish.requirements
        assertEquals(listOf<Any?>("R1", Author.User, listOf("AC-2"), ProvenanceClass.AgentTest), listOf(r1.id, r1.by, r1.acceptance, r1.provenanceClass))
        assertEquals(listOf<Any?>("R2", Author.Host, "host:setup", ProvenanceClass.Independent), listOf(r2.id, r2.by, r2.authorityRef, r2.provenanceClass))
        assertEquals(Author.Model to ProvenanceClass.AgentTest, finish.acceptance.single { it.id == "AC-2" }.let { it.checkBy to it.provenanceClass })
        assertEquals("completed" to ProvenanceClass.AgentTest, finish.status to finish.provenanceClass)
        assertEquals("agent_test", finished.provenanceClass)
    }

    @Test
    fun `a declared check accepted unverified beside the model's green test completes agent_test`() {
        // AC-0 only declares the command the model's AC-2 reuses (D-262); R1 rests on AC-1, which cannot run, and AC-2.
        seed(listOf(Acceptance.Run("AC-0", printing, Origin.User), Acceptance.Run("AC-1", missing, Origin.User), Acceptance.Run("AC-2", printing, Origin.Model("R1"))),
            r1 = listOf("AC-1", "AC-2"))
        val (run, finished) = run(accepting(Decider.Policy, "studio:policy(auto)"))
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        val declared = finish.acceptance.single { it.id == "AC-1" }
        assertEquals(listOf<Any?>("accepted", unverified, RiskAcceptor.Policy, ProvenanceClass.Unverified),
            listOf(declared.provenance, declared.result, declared.riskAcceptedBy, declared.provenanceClass))
        assertEquals(ProvenanceClass.AgentTest, finish.requirements.single().provenanceClass)
        assertEquals("completed" to ProvenanceClass.AgentTest, finish.status to finish.provenanceClass)
        assertTrue(finish.notVerified.any { it.startsWith("AC-1: accepted without verification") }, "the decider's acceptance stays visible")
        assertEquals("agent_test", finished.provenanceClass)
    }

    @Test
    fun `the model's own test beside a host review nobody answered completes agent_test, never independent`() {
        // C1a: a test command the model runs becomes its own check (CHK-model-*, origin model); the host's review has no reviewer.
        val pytest = if (WINDOWS) "pytest.cmd".also { repo.write(it, "@type pytest_pass.txt\r\n") } else "./pytest".also {
            repo.write("pytest", "#!/bin/sh\ncat pytest_pass.txt\n")
            java.nio.file.Files.setPosixFilePermissions(repo.root.resolve("pytest"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        repo.commit("test runner")
        seed(listOf(Acceptance.Check("AC-1", "a reviewer approves the change", Origin.Amended(1))), r1 = listOf("AC-1"))
        val (run, finished) = run(accepting(Decider.Policy, "studio:policy(auto)")) {
            listOf(Scripted.Reply(listOf(call("t1", "run", """{"argv":["$pytest","-q"]}"""))), Scripted.Reply(listOf(say("done"))))
        }
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        val own = finish.checksRun.single { it.checkId.startsWith(Checks.MODEL_PREFIX) }
        assertEquals(listOf<Any?>(Origin.Model("R1"), EvidenceKind.Tests, "passed"), listOf(own.checkOrigin, own.evidenceKind, own.outcome))
        val requirement = finish.requirements.single()
        assertEquals(listOf(own.checkId), requirement.agentChecks)
        assertEquals(ProvenanceClass.AgentTest, requirement.provenanceClass)
        assertEquals(Author.Host to unverified, finish.acceptance.single().let { it.checkBy to it.result })
        assertEquals("completed" to ProvenanceClass.AgentTest, finish.status to finish.provenanceClass)
        assertEquals("agent_test", finished.provenanceClass)
    }

    @Test
    fun `a declared check green only for an earlier candidate is not independent at the final tree`() = runBlocking<Unit> {
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User)), r1 = listOf("AC-1"))
        val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)
        controller.open(repo.root, request, policy).use { c ->
            val model = CellModel(FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(say("done"))))), FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)
            val finish = assertNotNull(controller.run(c, model).finish)
            assertEquals(ProvenanceClass.Independent, finish.provenanceClass)
            // The same green receipt, read as stale for the tree at hand: it speaks for another candidate (D-337).
            val stale = Currency(finish.acceptance.single().receiptId, Applicability.Stale, eligible = true, green = true, reasons = listOf("src/a.py changed"))
            val again = FinishReceipts.build(c, emptyList(), mapOf(Checks.acceptId("AC-1") to stale), SqliteReceipts(c.store, clock)::get)
            assertEquals(unverified to ProvenanceClass.Unverified, again.acceptance.single().let { it.result to it.provenanceClass })
            assertEquals(ProvenanceClass.Unverified, again.requirements.single().provenanceClass)
            assertEquals(ProvenanceClass.Unverified, again.provenanceClass)
        }
    }

    @Test
    fun `the model's own check item approved by a reviewer is agent_test, and the line names who verified it`() {
        seed(listOf(Acceptance.Check("AC-2", "a returns the documented value", Origin.Model("R1"))), r1 = listOf("AC-2"))
        val reviewer = object : Authority by AutonomousAuthority() {
            override suspend fun review(request: ReviewRequest): Verdict =
                Verdict(request.id, request.contractRevision, request.candidate, VerdictOutcome.Approve, confidence = 0.9, signedBy = "host:alice")
        }
        val (run, finished) = run(reviewer)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        val line = finish.acceptance.single()
        assertEquals(listOf<Any?>("reviewed", Author.Model, "human", ProvenanceClass.AgentTest), listOf(line.provenance, line.checkBy, line.verifiedBy, line.provenanceClass))
        assertEquals(ProvenanceClass.AgentTest, finish.requirements.single().provenanceClass)
        assertEquals("agent_test", finished.provenanceClass)
    }

    @Test
    fun `accept-unverified with no check passing completes unverified, with the risk taken by the policy`() {
        seed(listOf(Acceptance.Run("AC-1", missing, Origin.User)), r1 = listOf("AC-1"))
        val (run, finished) = run(accepting(Decider.Policy, "studio:policy(auto)"))
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        val line = finish.acceptance.single()
        assertEquals(listOf<Any?>("accepted", RiskAcceptor.Policy, ProvenanceClass.Unverified), listOf(line.provenance, line.riskAcceptedBy, line.provenanceClass))
        assertEquals(ProvenanceClass.Unverified, finish.requirements.single().provenanceClass)
        assertEquals("completed" to ProvenanceClass.Unverified, finish.status to finish.provenanceClass)
        assertEquals("unverified", finished.provenanceClass)
        assertEquals(Origin.User, finish.acceptedWithoutVerification.single().origin)
    }

    @Test
    fun `a user's acceptance without verification is the user's risk and still unverified`() {
        seed(listOf(Acceptance.Run("AC-1", missing, Origin.User)), r1 = listOf("AC-1"))
        val finish = assertNotNull(run(accepting(Decider.User, "user:test")).first.finish)
        assertEquals(RiskAcceptor.User to ProvenanceClass.Unverified, finish.acceptance.single().let { it.riskAcceptedBy to it.provenanceClass })
        assertEquals(ProvenanceClass.Unverified, finish.provenanceClass)
    }

    @Test
    fun `a test edit an earlier cell left in the tree is the agent's evidence even when the last cell raised no flag`() {
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User)), r1 = listOf("AC-1"))
        val path = "tests/test_a.py"
        var asked = 0
        // The first completion is sent back for rework; the continuation changes nothing and raises no flag of its own.
        val host = object : Authority by AutonomousAuthority() {
            override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? = if (++asked > 1) null else
                AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Rework, Decider.User, "user:test", "keep the test as it was")
        }
        val (run, finished) = run(host, IntegrityApproval.Human) { c ->
            listOf(
                Scripted.Reply(listOf(read("read-test", path))),
                Scripted.Reply(listOf(anchored("edit-test", path, c.registry.version(path)!!, "    assert 1 == 1", "    assert (1 == 1)"))),
                Scripted.Reply(listOf(say("done"))),
                Scripted.Reply(listOf(say("done"))),
            )
        }
        val finish = assertNotNull(run.finish)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        assertEquals(listOf(path), finish.acceptanceSurfaceUnreviewed)
        assertEquals("tested" to ProvenanceClass.AgentTest, finish.acceptance.single().let { it.provenance to it.provenanceClass })
        assertEquals(ProvenanceClass.AgentTest, finish.provenanceClass)
        assertEquals("agent_test", finished.provenanceClass)
    }

    @Test
    fun `a test file back at its s0 text, line endings aside, is no surface change at the final tree`() {
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User)), r1 = listOf("AC-1"))
        val path = "tests/test_a.py"
        // A checkout with other line endings rewrites the test between turns: its bytes moved, its lines did not.
        val (run, finished) = run(accepting(Decider.Policy, "studio:policy(auto)"), IntegrityApproval.Human,
            replies = { listOf(Scripted.Reply(listOf(read("read-src", "src/a.py"))), Scripted.Reply(listOf(say("done")))) },
            before = { i -> if (i == 1) repo.write(path, "def test_a():\r\n    assert 1 == 1   \r\n\r\n") })
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertTrue(path in finish.changes.agent + finish.changes.byRun + finish.changes.unattributed, "the path moved since s0")
        assertEquals(emptyList(), finish.acceptanceSurfaceUnreviewed)
        assertEquals(ProvenanceClass.Independent, finish.provenanceClass)
        assertEquals("independent", finished.provenanceClass)
    }

    @Test
    fun `an approving review clears a surface change only for this contract, this version and, under human approval, a human`() {
        val path = "tests/test_a.py"
        val candidate = CandidateId(Digest.ofUtf8("final"))
        val now = FileVersion.of("now".toByteArray())
        val verdict = Verdict("rq-1", 1, candidate, VerdictOutcome.Approve, confidence = 0.9, signedBy = "judge")
        val line = "acceptance surface: $path (test_file) modified by edit #1 · unclassified · required: CHK-accept-AC-1 · review: pending"
        val record = io.astrolabe.delegate.ReviewRecord("p-1", ReviewScope.Increment, "inc-1", 1, candidate, emptyList(), mapOf(path to now), verdict,
            path = listOf("high"), integrity = listOf(line))
        fun covers(r: io.astrolabe.delegate.ReviewRecord, human: Boolean = false) = FinishReceipts.covers(r, path, now, candidate, 1, human)
        assertTrue(covers(record))
        assertTrue(!covers(record, human = true), "under human approval a judge's verdict clears no flag (D-320)")
        assertTrue(covers(record.copy(path = listOf("high", "human")), human = true))
        assertTrue(!covers(record.copy(evidenceVersions = mapOf(path to FileVersion.of("before".toByteArray())))), "a later edit of the path is not covered")
        assertTrue(!covers(record.copy(evidenceVersions = emptyMap(), candidate = CandidateId(Digest.ofUtf8("earlier")))), "no version of the path and another candidate")
        assertTrue(covers(record.copy(evidenceVersions = emptyMap())), "no version of the path, but this very candidate")
        assertTrue(!covers(record.copy(contractVersion = 2)), "another contract version")
        assertTrue(!covers(record.copy(integrity = listOf(line.replace(path, "tests/test_b.py")))), "it names another path")
    }

    @Test
    fun `a test edit accepted without an approving review leaves the green declared check the agent's evidence`() {
        seed(listOf(Acceptance.Run("AC-1", printing, Origin.User)), r1 = listOf("AC-1"))
        val path = "tests/test_a.py"
        // Under human integrity approval the host reviews the flag; this host has no reviewer, so its policy accepts it.
        val (run, finished) = run(accepting(Decider.Policy, "studio:policy(auto)"), IntegrityApproval.Human) { c ->
            listOf(
                Scripted.Reply(listOf(read("read-test", path))),
                Scripted.Reply(listOf(anchored("edit-test", path, c.registry.version(path)!!, "    assert 1 == 1", "    assert (1 == 1)"))),
                Scripted.Reply(listOf(say("done"))),
            )
        }
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        val finish = assertNotNull(run.finish)
        assertEquals(listOf(path), finish.acceptanceSurfaceUnreviewed)
        val line = finish.acceptance.single()
        assertEquals(listOf<Any?>("tested", Author.User, passed, ProvenanceClass.AgentTest), listOf(line.provenance, line.checkBy, line.result, line.provenanceClass))
        assertEquals(ProvenanceClass.AgentTest, finish.requirements.single().provenanceClass)
        assertEquals("completed" to ProvenanceClass.AgentTest, finish.status to finish.provenanceClass)
        assertEquals("agent_test", finished.provenanceClass)
    }
}
