package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.resultText
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Item
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolResult
import io.astrolabe.store.Store
import io.astrolabe.verify.RefactorMode
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.ReviewScope
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
 * P3.5.2 (§8.9 items 4–6, D-23): a refactor campaign ends `completed` only with a signed approving campaign-scope
 * review over the full diff, with the equivalence report on the finish receipt; no reviewer ⇒ `blocked`, never skipped.
 */
class RefactorCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-rf"), AttemptId("a1"), "make a return 10 and b return 20")
    private val policy = CampaignPolicy(Tokens(400_000))

    /** A `-rA` pass log: the short summary names every test, so identities compare (a `-q` dot log never could). */
    private val passLog = """
        |============================= test session starts ==============================
        |platform linux -- Python 3.12.3, pytest-8.2.0, pluggy-1.5.0
        |rootdir: /home/dev/shop
        |collected 2 items
        |
        |tests/test_ab.py ..                                                      [100%]
        |
        |=========================== short test summary info ============================
        |PASSED tests/test_ab.py::test_a
        |PASSED tests/test_ab.py::test_b
        |============================== 2 passed in 0.05s ===============================
        |""".trimMargin()

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", passLog)
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val user = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = user),
                        Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = user),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                    // D-75: the explicit flag activates refactor mode without behaviour-preserving words in the requirements.
                    constraints = derived.constraints + Constraint(RefactorMode.FLAG, "refactor_mode", user),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    private val plan = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"}],
        "con":[{"summary":"a/b return-value contract","scope":"src/"}],
        "refactor_checklist":{"behaviour_to_preserve":"the test suite and its identities","interfaces_to_change":"a() and b() return values",
          "compatibility_duration":"none","callers_consumers":"tests/test_ab.py","data_configuration_dependencies":"none",
          "independent_acceptance_checks":"AC-1, AC-2","shared_decision":"CON a/b return-value contract"}}"""

    private fun planning(): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("planning the refactor"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
        Scripted.Reply(listOf(say("plan ready"))),
    )

    private fun implement(c: OpenedCampaign, path: String, from: String, to: String, ids: String, extra: List<Scripted> = emptyList()): List<Scripted> {
        val v = c.registry.version(path)!!
        return listOf(
            Scripted.Reply(listOf<Item>(say("reading $path"), read("r-$path", path))),
            Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e-$path", path, v, from, to))),
            Scripted.Reply(listOf<Item>(say("verifying"), call("v-$path", "verify", """{"what":"acceptance","ids":[$ids]}"""))),
        ) + extra + listOf(Scripted.Reply(listOf<Item>(say("done with $path"))))
    }

    /** A host reviewer: the other authority paths stay autonomous. */
    private class Reviewer(private val outcome: VerdictOutcome, private val name: String) : Authority by AutonomousAuthority() {
        val requests = ArrayList<ReviewRequest>()

        override suspend fun review(request: ReviewRequest): Verdict {
            requests += request
            return Verdict(request.id, request.contractRevision, request.candidate, outcome, confidence = 0.9, signedBy = name)
        }
    }

    @Test
    fun `a refactor campaign completes on an approving signed review, with the equivalence report and verdict on the finish receipt`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            assertTrue(RefactorMode.isActive(c.contract))
            val reviewer = Reviewer(VerdictOutcome.Approve, "alice")
            // verify(review) in the last cell: the human form routes to the authority (unmasked in P3.5.2); the gate reuses its signed verdict.
            val cellReview = listOf(Scripted.Reply(listOf<Item>(say("asking for the campaign review"), call("rv", "verify", """{"what":"review","scope":"campaign"}"""))))
            val replies = planning() +
                implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"", extra = cellReview)
            val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))

            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()), authority = reviewer)

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val request = reviewer.requests.single()
            assertEquals(ReviewScope.Campaign, request.scope)
            assertNotNull(request.diffRef, "the full diff travels as a blob")
            assertTrue(request.receipts.isNotEmpty(), "the receipts current at the candidate")
            assertTrue(request.rubric.any { it.startsWith("behaviour preserved (§8.9 item 5)") } && request.rubric.any { it.contains("interfaces changed as declared") }, request.rubric.toString())
            assertTrue(request.rubric.any { it.contains("CON note(s): new: a/b return-value contract") }, request.rubric.toString())
            val diff = String(c.store.blobs.get(io.astrolabe.id.Digest(request.diffRef!!)), Charsets.UTF_8)
            assertTrue(diff.contains("+++ b/src/a.py") && diff.contains("-    return 1") && diff.contains("+    return 10") && diff.contains("2 paths differ"), diff)
            // The cell saw the verdict rendered as a tool result.
            val results = adapter.calls.flatMap { it.request.segment(SegmentKind.T)?.items.orEmpty() }.filterIsInstance<ToolResult>().map(::resultText)
            val rendered = results.lastOrNull { it.contains("── Review ──") } ?: error("no rendered review among ${results.size} tool results: ${results.map { it.lineSequence().first() }}")
            assertTrue(rendered.contains("approve by alice"), rendered)
            // The finish receipt carries the equivalence report and the reused verdict.
            val finish = run.finish!!
            assertEquals("completed", finish.status)
            val review = assertNotNull(finish.review)
            assertEquals("approve" to "alice", review.verdict to review.signedBy)
            assertEquals(request.id, review.requestId)
            assertTrue(review.reused, "the gate reused the verdict signed at the same stamp instead of asking twice")
            assertNull(review.unavailable)
            val equivalence = assertNotNull(finish.equivalence)
            assertTrue(equivalence.equivalent, equivalence.render())
            assertEquals(2, equivalence.preserved)
            assertEquals(c.s0.stampId, equivalence.s0)
            assertEquals(finish.stamp, equivalence.sn)
            val boundary = c.journal.events(JournalScope(request.ids.work, kinds = setOf(JournalKind.Boundary))).map { it.text }
            assertTrue(boundary.any { it.startsWith("campaign review ${request.id} (human path substitutes the review cell, D-23): approve by alice") }, boundary.toString())
            assertTrue(boundary.any { it.contains("reused: signed by alice") }, boundary.toString())
        }
    }

    @Test
    fun `without a reviewer the refactor campaign is blocked, never completed, and the receipt says why`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            val replies = planning() +
                implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")
            val run = controller().run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()))

            assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
            val reason = run.state!!.reason!!
            assertTrue(reason.startsWith("campaign review required: refactor mode") && reason.contains("no reviewer answered") && reason.contains("never skipped (D-23)"), reason)
            val finish = run.finish!!
            assertEquals("blocked_external", finish.status)
            val review = assertNotNull(finish.review)
            assertNull(review.verdict)
            assertTrue(review.unavailable!!.contains("no reviewer answered"), review.unavailable)
            assertNotNull(finish.equivalence, "the equivalence evidence was computed before the review was asked")
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertTrue(state.graph.increments.all { it.status == io.astrolabe.contract.IncrementStatus.Verified }, "the increments verified; only the campaign gate is unmet")
        }
    }

    @Test
    fun `a rejecting verdict never completes the campaign`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            val replies = planning() +
                implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")
            val run = controller().run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()), authority = Reviewer(VerdictOutcome.Reject, "bob"))

            assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
            assertTrue(run.state!!.reason!!.contains("campaign review reject by bob"), run.state!!.reason)
            assertEquals("reject" to "bob", run.finish!!.review!!.verdict to run.finish!!.review!!.signedBy)
        }
    }
}
