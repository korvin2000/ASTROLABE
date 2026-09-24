package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.InvalidConfig
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.SqliteCheckpoints
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
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
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P1.9.4 lifecycle controls in the S0 shape: attempt freeze, cancellation, leases, budgets (FX-25/26/48/49, IX-02/18). */
class ControlsTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    private var seeded = false

    /** Stores the contract once, with a `run:` item the pytest shaper counts and the campaign budget [tokens]. */
    private fun seed(tokens: Tokens = policy.tokens) {
        if (seeded) return
        seeded = true
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun config(defaults: Defaults = Defaults()) = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = defaults)

    private fun controller(config: Config = config(), lease: Duration = Duration.ofHours(1)) =
        Controller(config, clock, idGen, leaseDuration = lease)

    private fun open(controller: Controller = controller(), policy: CampaignPolicy = this.policy): OpenedCampaign {
        seed(policy.tokens)
        return controller.open(repo.root, request, policy)
    }

    private fun model(adapter: FakeAdapter) = CellModel(adapter, FakeProfiles.main, HeuristicEstimator())

    /** read → edit → verify acceptance → [last], which may act (cancel, expire) just before the done claim returns. */
    private fun edits(c: OpenedCampaign, last: () -> Unit = {}): ScriptedModel {
        val v = c.registry.version("src/a.py")!!
        val replies = listOf(
            Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py"))),
            Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
            Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
        )
        return ScriptedModel(replies.map { r -> ScriptedModel.Turn({ true }, { r }) } + ScriptedModel.Turn({ true }, {
            last()
            Scripted.Reply(listOf(say("done: a returns 10")))
        }))
    }

    @Test
    fun `IX-02 IX-18 the attempt runs under the configuration frozen at its first open`() {
        val first = config(Defaults(anchorMaxTokens = 2_000))
        open(controller(first)).use { c -> assertEquals(first, c.attempt.config) }
        open(controller(config(Defaults(anchorMaxTokens = 3_000)))).use { c ->
            assertEquals(first, c.attempt.config, "an edit during the attempt waits for the next attempt")
            assertTrue(c.attempt.controls.allEnabled && c.attempt.production)
        }
    }

    @Test
    fun `each attempt keeps its own frozen snapshot, exported under campaigns, and the next id is the controller's (P2-2-5)`() {
        val first = config(Defaults(anchorMaxTokens = 2_000))
        open(controller(first)).use { c ->
            val attempts = Attempts(c.store, clock)
            assertEquals(listOf(request.attempt), attempts.all(request.work).map { it.first })
            assertEquals(io.astrolabe.id.AttemptId("a2"), attempts.next(request.work))
            val view = c.store.layout.campaigns.resolve(request.work.value).resolve(request.attempt.value).resolve("attempt-config.json")
            assertTrue(java.nio.file.Files.readString(view).contains("\"anchorMaxTokens\": 2000") || java.nio.file.Files.readString(view).contains("\"anchorMaxTokens\":2000"))
            val second = io.astrolabe.AttemptConfig.freeze(config(Defaults(anchorMaxTokens = 3_000)))
            attempts.save(request.work, attempts.next(request.work), second)
            assertEquals(first, attempts.load(request.work, request.attempt)!!.config, "an earlier attempt's snapshot never changes")
            assertEquals(second, attempts.load(request.work, io.astrolabe.id.AttemptId("a2")))
            assertEquals(io.astrolabe.id.AttemptId("a3"), attempts.next(request.work))
        }
        open(controller(config(Defaults(anchorMaxTokens = 4_000)))).use { c ->
            assertEquals(first, c.attempt.config, "the resumed attempt runs under its original snapshot after the config changed")
        }
    }

    @Test
    fun `a configuration that fails validation never starts an attempt`() {
        assertFailsWith<InvalidConfig> { open(controller(config(Defaults(alpha = 0.0)))) }
    }

    @Test
    fun `cancellation before dispatch stops the campaign cancelled without a model call`() = runBlocking<Unit> {
        open().use { c ->
            c.cancellation.cancel("host stop")
            val adapter = FakeAdapter(edits(c))
            val run = controller().runS0(c, model(adapter))
            assertEquals(CampaignOutcome.Cancelled, run.outcome)
            assertTrue(adapter.calls.isEmpty())
        }
    }

    @Test
    fun `cancellation mid call interrupts the cell, which settles a cancelled checkpoint`() = runBlocking<Unit> {
        open().use { c ->
            val adapter = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "thinking")))), holdResponses = true)
            val started = CompletableDeferred<Unit>()
            val running = async {
                controller().runS0(c, model(adapter))
            }
            withTimeout(10_000) {
                while (c.state?.running == null) kotlinx.coroutines.delay(10)
                started.complete(Unit)
            }
            kotlinx.coroutines.delay(200)
            c.cancellation.cancel("host stop")
            val run = withTimeout(10_000) { running.await() }
            assertEquals(CampaignOutcome.Cancelled, run.outcome)
            val cell = run.state!!.cells.single()
            assertEquals(CellStatus.Cancelled, cell.status)
            assertEquals(CellStatus.Cancelled, SqliteCheckpoints(c.store, clock).latest(cell.cell)!!.status)
        }
    }

    @Test
    fun `FX-26 single-cell - a completion that arrives after cancellation is archived, never published`() = runBlocking<Unit> {
        open().use { c ->
            val run = controller().runS0(c, model(FakeAdapter(edits(c) { c.cancellation.cancel("superseded by the host") })))
            assertIs<CellExit.Completed>(run.exit)
            assertEquals(CampaignOutcome.Cancelled, run.outcome)
            assertTrue(run.state!!.reason!!.startsWith("late completion archived; publication refused"))
            assertEquals(RequirementStatus.Pending, run.state!!.ledger.entries.getValue("R1").status)
            assertEquals("def a():\n    return 10\n", Files.readString(repo.root.resolve("src/a.py")), "the effect stays, reconciled, not rolled back")
            assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { "late completion" in it.text })
        }
    }

    @Test
    fun `a lease is single writer and its expiry revokes publication only`() = runBlocking<Unit> {
        open(controller(lease = Duration.ofMinutes(5))).use { c ->
            val lease = assertNotNull(c.lease)
            assertFailsWith<LeaseHeld> { Leases(c.store, clock).acquire(Controller.WORKSPACE, c.ids, "someone-else", Duration.ofMinutes(5)) }
            val run = controller().runS0(c, model(FakeAdapter(edits(c) { clock.advance(Duration.ofMinutes(10)) })))
            assertIs<CellExit.Completed>(run.exit, "the running action was not stopped by the expiry")
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome)
            assertTrue("expired" in run.state!!.reason!!, run.state!!.reason)
            assertEquals(lease.generation, Leases(c.store, clock).current(Controller.WORKSPACE)!!.generation)
        }
    }

    @Test
    fun `FX-25 S0 form - admission refuses what the budget cannot cover and spend stays within it`() = runBlocking<Unit> {
        val budget = Tokens(4_000)
        open(policy = CampaignPolicy(budget)).use { c ->
            val run = controller().runS0(c, model(FakeAdapter(edits(c))))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            val spent = Accounting(c.store, clock).calls(request.work).sumOf { it.quantities.billedUsage ?: 0 }
            assertTrue(spent <= budget.value, "billed $spent within the $budget budget")
            assertEquals("partial", run.finish!!.status, "a budget stop reports partial, never verified")
            assertEquals(listOf("R1", "AC-1"), run.finish!!.notVerified)
        }
    }

    @Test
    fun `FX-48 FX-49 S0 - a tiny task runs S0 with every mandatory control on and its overhead measured`() = runBlocking<Unit> {
        open().use { c ->
            assertEquals(Shape.S0, assertIs<ShapeDecision.Selected>(c.shape).shape)
            assertTrue(c.attempt.controls.allEnabled && c.lease != null && !c.cancellation.cancelled)
            val run = controller().runS0(c, model(FakeAdapter(edits(c))))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            // Overhead is measured, not assumed zero: turns and every priced call are on record.
            assertEquals(4, run.exit!!.turns)
            assertEquals(4, Accounting(c.store, clock).calls(request.work).size)
        }
    }
}
