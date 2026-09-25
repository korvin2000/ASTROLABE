package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
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
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.delegate.Judge
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
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P4.8.1 FX-49 (S2 parameterization, D-170): the operational fixtures of the S1 loop — completion on receipts,
 * cancellation before dispatch, a late completion, lease expiry, reservation — rerun in S2, where a hard-to-reverse
 * contract owes an increment review of every completed cell before the verifier sees it.
 */
class S2CampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-s2"), AttemptId("a1"), "make a return 10 and b return 20")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        seed(request, policy.tokens)
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    /** Hard reversibility makes the risk high: S2 is selected and the D-34 floor owes every increment a review (D-122). */
    private fun seed(request: CampaignRequest, tokens: Tokens) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), tokens).contract
            contracts.open(
                derived.copy(
                    shape = Shape.S2,
                    risk = Risk(1, Reversibility.Hard, false),
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                ),
            )
        }
    }

    private fun controller(lease: Duration = Duration.ofHours(1)) = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen, leaseDuration = lease)

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

    private fun approve(): Scripted = Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}""")))

    /** Plan, I1, its review, I2, its review: call indices 0–1, 2–5, 6, 7–10, 11. */
    private fun campaign(c: OpenedCampaign): List<Scripted> = planning() +
        implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") + approve() +
        implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"") + approve()

    // The review child's 30K budget (D-123) admits a turn only under a narrowed output headroom.
    private fun model(adapter: FakeAdapter) = CellModel(adapter, FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)

    private fun acting(replies: List<Scripted>, at: Int, act: () -> Unit) =
        FakeAdapter(ScriptedModel(replies.mapIndexed { i, r -> ScriptedModel.Turn({ true }, { if (i == at) act(); r }) }))

    private fun texts(request: Request): String =
        request.segments.flatMap { it.items }.filterIsInstance<Message>().joinToString("\n") { it.text }

    @Test
    fun `FX-49 S2 - every increment is reviewed before it is verified and the campaign completes on receipts`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(Shape.S2, assertIs<ShapeDecision.Selected>(c.shape).shape)
            val adapter = FakeAdapter(ScriptedModel.of(*campaign(c).toTypedArray()))
            val run = controller().run(c, model(adapter))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(12, adapter.calls.size)
            for (i in listOf(6, 11)) {
                val judge = adapter.calls[i].request
                assertTrue(Judge.OUTPUT in texts(judge), "call $i is the review cell")
                assertTrue(!judge.mask!!.allows("edit.anchored") && !judge.mask!!.allows("verify.review"), "the judge neither edits nor asks for its own review")
            }
            // IX-11 (S2): the obligation reaches the judge as its definition, not an id.
            val secondReview = texts(adapter.calls[11].request)
            assertTrue("AC-2" in secondReview && printing.argv.last() in secondReview, "AC-2 is shown to the judge with its command")
            assertTrue(adapter.validations.all { it.result == io.astrolabe.provider.Validation.Ok })
            assertEquals(adapter.calls.size, Accounting(c.store, clock).calls(request.work).size, "every call, the review cells' included, is priced")
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertTrue(state.graph.increments.all { it.status == IncrementStatus.Verified })
            assertEquals(listOf(RequirementStatus.Verified, RequirementStatus.Verified), state.ledger.entries.values.map { it.status })
            assertEquals("def b():\n    return 20\n", Files.readString(repo.root.resolve("src/b.py")))
        }
    }

    @Test
    fun `FX-49 S2 - a cancellation before dispatch stops the campaign without a model call`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            c.cancellation.cancel("host stop")
            val adapter = FakeAdapter(ScriptedModel.of(*planning().toTypedArray()))
            val run = controller().run(c, model(adapter))
            assertEquals(CampaignOutcome.Cancelled, run.outcome, run.state?.reason)
            assertTrue(adapter.calls.isEmpty())
        }
    }

    @Test
    fun `FX-49 S2 - a completion after cancellation is archived without a review and the verified increment stands`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy).use { c ->
            val adapter = acting(campaign(c), 10) { c.cancellation.cancel("superseded by the host") }
            val run = controller().run(c, model(adapter))
            assertEquals(CampaignOutcome.Cancelled, run.outcome, run.state?.reason)
            assertTrue(run.state!!.reason!!.startsWith("late completion archived; publication refused"), run.state!!.reason)
            assertEquals(11, adapter.calls.size, "no review cell is started for a completion that can no longer publish")
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertEquals(IncrementStatus.Verified, state.graph.increments.first { it.id == "I1" }.status)
            assertEquals(RequirementStatus.Pending, state.ledger.entries.getValue("R2").status)
            assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { "late completion of I2" in it.text })
        }
    }

    @Test
    fun `FX-49 S2 - lease expiry mid-campaign revokes publication and blocks, the running cell is not stopped`() = runBlocking<Unit> {
        controller(lease = Duration.ofMinutes(5)).open(repo.root, request, policy).use { c ->
            val run = controller().run(c, model(acting(campaign(c), 5) { clock.advance(Duration.ofMinutes(10)) }))
            assertIs<CellExit.Completed>(run.exit, "the running action was not stopped by the expiry")
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome)
            assertTrue("expired" in run.state!!.reason!!, run.state!!.reason)
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.none { it.status == IncrementStatus.Verified })
        }
    }

    @Test
    fun `FX-49 S2 - reservation refuses what the budget cannot cover and spend stays within it`() = runBlocking<Unit> {
        val small = CampaignRequest(WorkId("W-s2-small"), AttemptId("a1"), request.text)
        val budget = Tokens(4_000)
        seed(small, budget)
        controller().open(repo.root, small, CampaignPolicy(budget)).use { c ->
            val run = controller().run(c, model(FakeAdapter(ScriptedModel.of(*campaign(c).toTypedArray()))))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            val spent = Accounting(c.store, clock).calls(small.work).sumOf { it.quantities.billedUsage ?: 0 }
            assertTrue(spent <= budget.value, "billed $spent within the $budget budget")
        }
    }
}
