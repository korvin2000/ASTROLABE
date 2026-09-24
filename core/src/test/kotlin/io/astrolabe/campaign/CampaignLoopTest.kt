package io.astrolabe.campaign

import io.astrolabe.Config
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
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
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
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P2.2.2: the S1 campaign loop — plan cell, then one cell per ready increment, verified and committed in order. */
class CampaignLoopTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-s1"), AttemptId("a1"), "make a return 10 and b return 20")
    private val policy = CampaignPolicy(Tokens(400_000))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        // The full suite runs at S1 finish (P2.2.6): a counted pytest log certifies; Windows declares none (no `make`).
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    private val plan = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"}]}"""

    private fun planning(): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("planning two increments"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
        Scripted.Reply(listOf(say("plan ready"))),
    )

    private fun implement(c: OpenedCampaign, path: String, from: String, to: String, ids: String): List<Scripted> {
        val v = c.registry.version(path)!!
        return listOf(
            Scripted.Reply(listOf<Item>(say("reading $path"), read("r-$path", path))),
            Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e-$path", path, v, from, to))),
            Scripted.Reply(listOf<Item>(say("verifying"), call("v-$path", "verify", """{"what":"acceptance","ids":[$ids]}"""))),
            Scripted.Reply(listOf<Item>(say("done with $path"))),
        )
    }

    private fun model(replies: List<Scripted>) = CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator())

    @Test
    fun `a planned two-increment campaign runs one cell per increment and completes on receipts`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(Shape.S1, assertIs<ShapeDecision.Selected>(c.shape).shape)
            val replies = planning() +
                implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")
            val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            // P2.2.3: plan → implementing is a role switch — empty tail, the new role's mask, a STATUS revision.
            val planRequest = adapter.calls[0].request
            val firstImplementing = adapter.calls[2].request
            assertTrue(planRequest.mask!!.allows("task.propose") && !planRequest.mask!!.allows("edit.anchored"))
            assertTrue(firstImplementing.mask!!.allows("edit.anchored") && firstImplementing.mask!!.allows("run.run") && !planRequest.mask!!.allows("run.run"), "the implementing mask replaces the plan mask")
            val tail = firstImplementing.segment(io.astrolabe.provider.SegmentKind.T)!!.items
            assertTrue(tail.none { it is io.astrolabe.provider.ToolCall || it is io.astrolabe.provider.ToolResult || (it is io.astrolabe.provider.Message && it.role == io.astrolabe.provider.Role.Assistant) }, "nothing of the plan cell's transcript is inherited")
            assertTrue(adapter.validations.all { it.result == io.astrolabe.provider.Validation.Ok }, "FX-56: every request kept valid call/result pairing")
            val status = io.astrolabe.kb.Notes(c.store).revisions("STATUS-W-s1")
            assertEquals(listOf("role_switch", "cell_end", "cell_end"), status.map { it.body.lineSequence().first().substringAfter("boundary: ").substringBefore(" ·") })
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertEquals(listOf("I1", "I2"), state.graph.increments.map { it.id }, "the plan replaced the single-increment placeholder")
            assertTrue(state.graph.increments.all { it.status == IncrementStatus.Verified })
            assertEquals(listOf(RequirementStatus.Verified, RequirementStatus.Verified), state.ledger.entries.values.map { it.status })
            assertEquals(2, state.cells.size, "one implementing cell per increment; the plan cell is not an increment")
            assertEquals("def b():\n    return 20\n", Files.readString(repo.root.resolve("src/b.py")))
            val finish = run.finish!!
            assertEquals(listOf("src/a.py", "src/b.py"), finish.changes.agent.sorted())
            assertEquals(listOf("green", "green"), finish.acceptance.map { it.status })
            val suite = c.journal.events(io.astrolabe.evidence.JournalScope(request.work, kinds = setOf(io.astrolabe.evidence.JournalKind.Boundary))).map { it.text }.filter { it.startsWith("full suite (campaign end)") }
            assertEquals(listOf(if (WINDOWS) "full suite (campaign end): none declared by the repository — an explicit gap, acceptance runs stand" else "full suite (campaign end): green"), suite)
        }
    }

    @Test
    fun `a spent cell budget with unverified requirements ends budget_exhausted, never completed`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            val replies = planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"")
            val run = controller().run(c, model(replies), maxCells = 1)
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertEquals(IncrementStatus.Verified, state.graph.increments.first { it.id == "I1" }.status)
            assertEquals(IncrementStatus.Pending, state.graph.increments.first { it.id == "I2" }.status)
            assertTrue("1 requirements unverified" in run.state!!.reason!!, run.state!!.reason)
        }
    }

    @Test
    fun `finish never reports completed over a stale acceptance receipt`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            val va = c.registry.version("src/a.py")!!
            val vb = c.registry.version("src/b.py")!!
            // I2 verifies first and edits afterwards: its receipts describe a tree that no longer exists.
            val replies = planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") + listOf(
                Scripted.Reply(listOf<Item>(say("reading"), read("r-b", "src/b.py"))),
                Scripted.Reply(listOf<Item>(say("verifying early"), call("v-b", "verify", """{"what":"acceptance","ids":["AC-1","AC-2"]}"""))),
                Scripted.Reply(listOf<Item>(say("editing after the receipt"), anchored("e-b", "src/b.py", vb, "    return 2", "    return 20"))),
                Scripted.Reply(listOf<Item>(say("done"))),
            )
            assertTrue(va != vb)
            val run = controller().run(c, model(replies), maxCells = 3)
            assertTrue(run.outcome != CampaignOutcome.Completed, "never completed over a stale receipt: ${run.outcome} ${run.state?.reason}")
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.first { it.id == "I2" }.status != IncrementStatus.Verified)
        }
    }

    @Test
    fun `the campaign review predicate names why a review is owed`() {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contract = Contracts(SqliteContractRepository(store, clock), idGen, clock).current(request.work)!!
            assertEquals(null, CampaignFinish.reviewRequired(contract, 2))
            assertTrue(CampaignFinish.reviewRequired(contract.copy(shape = Shape.S2), 3)!!.contains("shape S2 with 3 increments"))
            assertEquals(null, CampaignFinish.reviewRequired(contract.copy(shape = Shape.S2), 2))
            assertTrue(CampaignFinish.reviewRequired(contract, 1, refactorMode = true)!!.contains("refactor mode"))
            val review = contract.copy(acceptance = contract.acceptance + Acceptance.Review("AC-R", "a maintainer approves", Origin.User))
            assertTrue(CampaignFinish.reviewRequired(review, 1)!!.contains("unsigned review items AC-R"))
        }
    }
}
