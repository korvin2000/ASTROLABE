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
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.HandoffCause
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FaultKind
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Message
import io.astrolabe.provider.SegmentKind
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A-D.6 on the fake adapter: a direct main-line cell hands off — under pressure in every loop, on a spent turn budget
 * only in `runS0` — and the same increment continues in an epoch paid from a durable grant kept apart from `maxCells`.
 * The grant and its spends live in the journal, so a reopen restores them and an undispatched paid continuation is
 * replayed without a second charge.
 */
class HandoffTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-04T10:00:00Z")
    private val idGen = FixedIdGen()
    private val policy = CampaignPolicy(Tokens(5_000_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun seed(request: CampaignRequest, shape: Shape, turnsPerCell: Int = 80, writePaths: List<String>? = null) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(
                derived.copy(
                    shape = shape,
                    scope = writePaths?.let { derived.scope.copy(writePaths = it) } ?: derived.scope,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "a stays a function", listOf("AC-2"), authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                    budget = derived.budget.copy(turnsPerCell = turnsPerCell),
                ),
            )
        }
    }

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = io.astrolabe.fixtures.FakeProfiles.all, protocol = Protocol.Direct), clock, idGen)

    private fun model(scripted: (Int) -> Scripted): CellModel {
        var n = 0
        val script = ScriptedModel(listOf(ScriptedModel.Turn({ true }, { n += 1; scripted(n) }, once = false)))
        return CellModel(FakeAdapter(script), io.astrolabe.fixtures.FakeProfiles.main, HeuristicEstimator())
    }

    /** Every request is refused for size: a direct cell rebuilds once and hands off on the second refusal. */
    private fun overflowing(each: (Int) -> Unit = {}): CellModel = model { n -> each(n); Scripted.Fault(FaultKind.ContextOverflow) }

    private class Record(val type: String, val payload: JsonObject)

    private fun records(c: OpenedCampaign): List<Record> = c.journal.events(JournalScope(c.ids.work, kinds = setOf(JournalKind.Boundary)))
        .mapNotNull { e -> (e.payload as? JsonObject)?.let { p -> (p["type"] as? JsonPrimitive)?.contentOrNull?.let { Record(it, p) } } }

    private fun JsonObject.text(key: String): String = (this[key] as JsonPrimitive).contentOrNull!!

    private fun spends(c: OpenedCampaign) = records(c).filter { it.type == "handoff" }.map { it.payload }

    private fun grants(c: OpenedCampaign) = records(c).filter { it.type == "handoff-grant" }.map { it.payload.text("limit").toInt() }

    @Test
    fun `a direct S1 line hands off under pressure into epochs outside maxCells until the default grant is spent`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-s1"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S1)
        val controller = controller()
        controller.open(repo.root, request, policy).use { c ->
            val model = overflowing()
            val run = controller.run(c, model, maxCells = 1)

            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            assertEquals("the campaign's 8 handoffs are spent with 2 requirements unverified", run.state?.reason, "not the cell cap: an epoch is not counted there")
            assertEquals(BudgetStop.CellCap, run.budgetStop, "the host continues it as it continues the cell cap")
            val increment = c.state!!.graph.increments.single()
            assertEquals(9, increment.cells.size, "one counted cell and eight epochs")
            assertEquals(listOf(8), grants(c))
            val spent = spends(c)
            assertEquals((1..8).map { it.toString() }, spent.map { it.text("n") })
            assertTrue(spent.all { it.text("cause") == "pressure" && it.text("increment") == increment.id })
            assertEquals(increment.cells.dropLast(1).map { it.value }, spent.map { it.text("from") })
            assertEquals(increment.cells.drop(1).map { it.value }, spent.map { it.text("to") }, "each spend names the cell that was dispatched")
            assertEquals(9, ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).size)
            assertTrue(ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).all { it.cause == HandoffCause.Pressure && it.function == io.astrolabe.route.RoutingFunction.Implementing })

            assertEquals(8, increment.sizing.handoffs)
            assertEquals(0, increment.sizing.continuations)
            assertEquals(9, increment.sizing.rebuilds, "the in-cell rebuild of each epoch; the handoff itself adds none")
            assertTrue(controller.router.calibration.entries().isEmpty(), "a handoff is never a calibration record")
            assertTrue(records(c).none { it.type == "substantive-attempt" }, "a handoff is not a verified failure")
            val economics = Economics.report(c, clock)
            assertEquals(listOf("done") + List(8) { "epoch" }, economics.cells.map { it.boundaryReason })
            assertEquals(mapOf(increment.id to 0), economics.continuationsPerIncrement)
        }
    }

    @Test
    fun `runS0 continues a direct cell that spent its turns with work done, carrying its packet, until the grant is spent`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-s0"), AttemptId("a1"), "make a return 10")
        // Three turns: one working turn and the two of the reserve, where a run still executes.
        seed(request, Shape.S0, turnsPerCell = 3)
        val controller = controller()
        controller.open(repo.root, request, policy).use { c ->
            val echo = io.astrolabe.cell.echo("still working")
            val argv = echo.argv.joinToString(",") { io.astrolabe.cell.CellFixture.quote(it) }
            var n = 0
            val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { n += 1; Scripted.Reply(listOf(say("working"), call("r$n", "run", """{"argv":[$argv]}"""))) }, once = false))))
            val run = controller.runS0(c, CellModel(adapter, io.astrolabe.fixtures.FakeProfiles.main, HeuristicEstimator()), maxHandoffs = 2)

            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            assertEquals("the campaign's 2 handoffs are spent with 2 requirements unverified", run.state?.reason)
            val increment = c.state!!.graph.increments.single()
            assertEquals(3, increment.cells.size)
            assertEquals(listOf("turn_budget", "turn_budget"), spends(c).map { it.text("cause") })
            assertEquals(2, increment.sizing.handoffs)
            val k = adapter.calls.map { call -> call.request.segment(SegmentKind.K)?.items.orEmpty().filterIsInstance<Message>().joinToString("\n") { it.text } }
            assertTrue(k.any { "Previous packet: continued (handoff)" in it }, "an epoch carries its predecessor's packet")
        }
    }

    @Test
    fun `a reopen after a crash restores the grant and its spends instead of a new limit`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-crash"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        controller().open(repo.root, request, policy).use { c ->
            // The epoch's first request dies with the process: the spend is journaled and its successor dispatched.
            val crashing = overflowing { n -> if (n == 3) throw AssertionError("simulated process death") }
            assertFailsWith<AssertionError> { controller().runS0(c, crashing, maxHandoffs = 1) }
            assertEquals(listOf(1), grants(c))
            assertEquals(1, spends(c).size)
        }
        controller().open(repo.root, request, policy).use { c ->
            val run = controller().runS0(c, overflowing(), maxHandoffs = 5)

            assertEquals("the campaign's 1 handoffs are spent with 2 requirements unverified", run.state?.reason, "the remainder of the grant in force, not a new limit of 5")
            assertEquals(listOf(1), grants(c), "a crash is not an explicit resume")
            assertEquals(1, spends(c).size)
        }
    }

    @Test
    fun `an undispatched paid continuation is replayed under its spend, never charged twice and never refused for a zero remainder`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-replay"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        val paid = ContextId("cell-paid")
        controller().open(repo.root, request, policy).use { c ->
            // The lease runs out during the handed-off cell's last call, so the run stops before the epoch is paid for.
            val first = controller().runS0(c, overflowing { n -> if (n == 2) clock.advance(Duration.ofHours(2)) }, maxHandoffs = 1)
            assertEquals(CampaignOutcome.BlockedExternal, first.outcome, first.state?.reason)
            assertTrue(spends(c).isEmpty())
            // What a process that died between the spend and the `Dispatched` row leaves behind.
            val handoffs = Handoffs(c.journal, idGen, clock, c.ids)
            val increment = c.state!!.graph.increments.single()
            handoffs.spend(handoffs.current()!!, 1, increment.cells.single(), paid, increment.id, HandoffCause.Pressure)
        }
        controller().open(repo.root, request, policy).use { c ->
            val run = controller().runS0(c, overflowing(), maxHandoffs = 0)

            assertEquals(listOf(1, 0), grants(c), "the explicit resume renewed the grant with this run's limit")
            assertTrue(paid in c.state!!.graph.increments.single().cells, "the epoch ran under the id its spend names")
            assertEquals(1, spends(c).size, "the replay charged nothing")
            assertEquals("the campaign's 0 handoffs are spent with 2 requirements unverified", run.state?.reason)
        }
    }

    @Test
    fun `a reopen after the handoff limit renews the grant and the work goes on, each epoch charged once`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-reopen"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        controller().open(repo.root, request, policy).use { c ->
            val run = controller().runS0(c, overflowing(), maxHandoffs = 1)
            assertEquals("the campaign's 1 handoffs are spent with 2 requirements unverified", run.state?.reason)
            assertEquals(BudgetStop.CellCap, run.budgetStop)
            assertEquals(2, c.state!!.graph.increments.single().cells.size)
        }
        controller().open(repo.root, request, policy).use { c ->
            val run = controller().runS0(c, overflowing(), maxHandoffs = 1)

            assertEquals(listOf(1, 1), grants(c), "the reopen renewed the grant")
            val cells = c.state!!.graph.increments.single().cells
            assertEquals(3, cells.size, "the handed-off cell continued in a new epoch")
            val spent = spends(c)
            assertEquals(cells.dropLast(1).map { it.value }, spent.map { it.text("from") }, "one spend for each continued cell")
            assertEquals(cells.drop(1).map { it.value }, spent.map { it.text("to") })
            assertEquals(2, spent.map { it.text("grant") }.distinct().size, "each spend under its own grant")
            assertEquals(BudgetStop.CellCap, run.budgetStop)
        }
    }

    @Test
    fun `the grant is the latest one unless an explicit resume is newer, and a spend is found by the cell it continues`() {
        Store.open(stateRoot.resolve("journal"), repo.git, clock).use { store ->
            val ids = Identities(WorkId("W-grant"), AttemptId("a1"))
            val handoffs = Handoffs(Journal(store, clock), idGen, clock, ids)
            assertNull(handoffs.current())
            val first = handoffs.grant(3)
            assertEquals(first.id, handoffs.grant(7).id, "a run with a grant in force journals none")
            assertEquals(3, handoffs.current()!!.limit)
            handoffs.spend(first, 1, ContextId("cell-a"), ContextId("cell-b"), "I1", HandoffCause.TurnBudget)
            assertEquals(ContextId("cell-b"), handoffs.spendOf(ContextId("cell-a"))!!.to)
            assertEquals(HandoffCause.TurnBudget, handoffs.spendOf(ContextId("cell-a"))!!.cause)
            assertNull(handoffs.spendOf(ContextId("cell-b")))
            handoffs.resumed("reopened after blocked_external")
            assertNull(handoffs.current(), "an explicit resume retires the grant")
            val renewed = handoffs.grant(7)
            assertEquals(7, renewed.limit)
            assertTrue(handoffs.spends(renewed.id).isEmpty())
            assertEquals(1, handoffs.spends(first.id).size)
        }
    }

    private val testPath = "tests/test_a.py"

    /** A weakening seed: a required test on disk and a scope the cell may edit it in. */
    private fun seedWithTest(request: CampaignRequest) {
        repo.write(testPath, "def test_a():\n    assert 1 == 1\n")
        repo.commit("a test")
        seed(request, Shape.S0, writePaths = listOf("src/", "tests/"))
    }

    /** Epoch A reads the test and weakens its assertion, then hands off under pressure. */
    private fun weakening(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(read("read-test", testPath))),
        Scripted.Reply(listOf(anchored("weaken", testPath, c.registry.version(testPath)!!, "    assert 1 == 1", "    assert 1"))),
        Scripted.Fault(FaultKind.ContextOverflow),
        Scripted.Fault(FaultKind.ContextOverflow),
    )

    /** Epoch B claims completion; the review its completion owes approves. */
    private val finishing: List<Scripted> = listOf(
        Scripted.Reply(listOf(call("f", "task", """{"op":"finish","text":"a returns 10"}"""))),
        Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
    )

    /** [script] request by request, then `done`; [each] sees the request number first. */
    private fun scripted(script: List<Scripted>, each: (Int) -> Unit = {}): CellModel =
        model { n -> each(n); script.getOrElse(n - 1) { Scripted.Reply(listOf(say("done"))) } }

    /** Epoch B's completion is bound by epoch A's weakening as one cell's would be: only an approving review resolves it. */
    private fun assertBoundByEpochA(c: OpenedCampaign, run: S0Run) {
        val increment = c.state!!.graph.increments.single()
        assertEquals(2, increment.cells.size, run.state?.reason)
        val kept = ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).single()
        assertEquals(listOf(testPath), kept.testIntegrity().map { it.path }, "the handoff keeps the flag")
        val flag = assertNotNull(run.exit, run.state?.reason).packet.flags.testIntegrity.single { it.path == testPath }
        assertTrue(flag.requiredChecks.isNotEmpty() && "weakened" in flag.kind, flag.line)
        assertTrue(flag.verdict?.approved == true, "epoch B resolved it only through the review: ${flag.line}")
        val review = assertNotNull(io.astrolabe.delegate.ReviewCell.latest(c.store, c.ids), "epoch B's completion asked for the review")
        assertEquals(increment.id, review.incrementId)
        assertTrue(review.integrity.any { testPath in it }, "the reviewer saw epoch A's change: ${review.integrity}")
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
    }

    @Test
    fun `a test weakened before a handoff binds the completion of the epoch that continues it`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-weaken"), AttemptId("a1"), "make a return 10")
        seedWithTest(request)
        controller().open(repo.root, request, policy).use { c ->
            assertBoundByEpochA(c, controller().runS0(c, scripted(weakening(c) + finishing)))
        }
    }

    @Test
    fun `a test weakened before a handoff binds the epoch's completion after a reopen too`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-weaken-reopen"), AttemptId("a1"), "make a return 10")
        seedWithTest(request)
        controller().open(repo.root, request, policy).use { c ->
            // The lease runs out during the handed-off cell's last call: the run stops before the epoch.
            val first = controller().runS0(c, scripted(weakening(c)) { n -> if (n == 4) clock.advance(Duration.ofHours(2)) })
            assertEquals(CampaignOutcome.BlockedExternal, first.outcome, first.state?.reason)
        }
        controller().open(repo.root, request, policy).use { c ->
            assertBoundByEpochA(c, controller().runS0(c, scripted(finishing)))
        }
    }

    /** Epoch B claims completion; the review its completion owes rejects epoch A's weakened assertion. */
    private val rejected: List<Scripted> = listOf(
        Scripted.Reply(listOf(call("f", "task", """{"op":"finish","text":"a returns 10"}"""))),
        Scripted.Reply(listOf(say("""{"verdict":"reject","confidence":0.9,"findings":[{"severity":"blocker","location":"$testPath:2","issue":"the assertion no longer checks the value","suggestedFix":"restore the criterion","kind":"test-integrity"}]}"""))),
    )

    @Test
    fun `a test weakened before a handoff refuses the epoch's completion when the review it owes rejects it`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-weaken-reject"), AttemptId("a1"), "make a return 10")
        seedWithTest(request)
        controller().open(repo.root, request, policy).use { c ->
            val run = controller().runS0(c, scripted(weakening(c) + rejected))
            val increment = c.state!!.graph.increments.single()
            assertEquals(2, increment.cells.size, run.state?.reason)
            assertEquals(listOf(testPath), ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).single().testIntegrity().map { it.path }, "the handoff keeps the flag")
            val review = assertNotNull(io.astrolabe.delegate.ReviewCell.latest(c.store, c.ids), "epoch B's completion asked for the review")
            assertEquals(increment.id, review.incrementId)
            assertTrue(review.integrity.any { testPath in it }, "the reviewer saw epoch A's change: ${review.integrity}")
            assertTrue(!review.approved, "the review rejected it")
            val flag = assertNotNull(run.exit, run.state?.reason).packet.flags.testIntegrity.single { it.path == testPath }
            assertTrue(flag.blocksCompletion, "the rejection resolves nothing: ${flag.line}")
            assertTrue(run.outcome != CampaignOutcome.Completed, "the epoch's completion is refused: ${run.outcome} ${run.state?.reason}")
            assertTrue(increment.status != IncrementStatus.Verified, "${increment.status}")
        }
    }

    @Test
    fun `a handoff kept before its row is applied ahead of the reopen's unblock after a host fix, so its epoch inherits the flag and the public impact`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-orphan-unblock"), AttemptId("a1"), "make a return 10")
        repo.write(testPath, "def test_a():\n    assert 1 == 1\n")
        repo.write("src/b.py", "from a import a\n\n\ndef b():\n    return a()\n")
        repo.commit("a test and a caller of a")
        seed(request, Shape.S1, writePaths = listOf("src/", "tests/"))
        // A planned graph of two independent increments: I1 is blocked on the host (the placeholder check sees a plan, so
        // no plan cell replaces it); I2's cell hands off and its process dies before the row.
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contract = checkNotNull(Contracts(SqliteContractRepository(store, clock), idGen, clock).current(request.work))
            fun increment(id: String, requirement: String, acceptance: String, status: IncrementStatus) = io.astrolabe.contract.Increment(
                id, listOf(requirement), listOf(acceptance), listOf("src/", "tests/"), 2, status, title = requirement, produces = io.astrolabe.graph.Production.Artifact,
            )
            val graph = io.astrolabe.graph.RequirementGraph(listOf(increment("I1", "R1", "AC-1", IncrementStatus.Blocked), increment("I2", "R2", "AC-2", IncrementStatus.Pending)))
            SqliteCampaigns(store, clock).save(Lifecycle.open(contract, graph))
        }
        lateinit var handedOff: ContextId
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(IncrementStatus.Blocked, c.state!!.graph.increments.first { it.id == "I1" }.status)
            // Epoch A weakens the test and changes the signature of `a`, which src/b.py calls, then hands off.
            val epochA = weakening(c).take(2) + listOf(
                Scripted.Reply(listOf(read("read-a", "src/a.py"))),
                Scripted.Reply(listOf(anchored("widen", "src/a.py", c.registry.version("src/a.py")!!, "def a():", "def a(x):"))),
                Scripted.Fault(FaultKind.ContextOverflow),
                Scripted.Fault(FaultKind.ContextOverflow),
            )
            c.store.db.tx { it.execute("CREATE TRIGGER die BEFORE INSERT ON campaigns WHEN EXISTS (SELECT 1 FROM packets WHERE kind = 'returned_handoff') BEGIN SELECT RAISE(ABORT, 'simulated process death'); END") }
            assertFails { controller().run(c, scripted(epochA), maxHandoffs = 1) }
            c.store.db.tx { it.execute("DROP TRIGGER die") }
            val kept = ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).single()
            assertEquals("I2", kept.incrementId)
            assertEquals(listOf(testPath), kept.testIntegrity().map { it.path }, "the record keeps the flag")
            assertEquals(listOf("a"), kept.impactNudges().map { it.definition.symbol }, "the record keeps the public impact")
            handedOff = kept.cell
            // The host settles what I1 was blocked on while the work is closed: the reopen unblocks I1.
            c.contracts.amendByHost(request.work, "the fixture I1 needs is in place") { it }
        }
        controller().open(repo.root, request, policy).use { c ->
            assertEquals(CellStatus.Partial, c.state!!.cells.single { it.cell == handedOff }.status, "the kept return is applied as its row, not as a lost cell")
            assertTrue(c.state!!.graph.increments.first { it.id == "I1" }.status != IncrementStatus.Blocked, "the host fix unblocked I1 in the same open")
            val run = controller().run(c, overflowing(), maxHandoffs = 1)

            assertEquals("the campaign's 1 handoffs are spent with 2 requirements unverified", run.state?.reason)
            val epoch = spends(c).single().also { assertEquals(handedOff.value, it.text("from"), "the epoch was paid from the grant") }.text("to")
            val successor = ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).single { it.cell.value == epoch }
            assertEquals(listOf(testPath), successor.testIntegrity().map { it.path }, "the epoch inherited the test-integrity flag")
            assertEquals(listOf("a"), successor.impactNudges().map { it.definition.symbol }, "the epoch inherited the unresolved public impact")
            val increment = c.state!!.graph.increments.first { it.id == "I2" }
            assertEquals(listOf(handedOff.value, epoch), increment.cells.map { it.value })
            assertEquals(0, increment.sizing.continuations)
        }
    }

    @Test
    fun `a handoff kept before its row was written is applied at the reopen, so its increment continues as a paid epoch`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-handoff-orphan"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        controller().open(repo.root, request, policy).use { c ->
            // The process dies between the kept record and the `Returned` row that refers to it.
            c.store.db.tx { it.execute("CREATE TRIGGER die BEFORE INSERT ON campaigns WHEN EXISTS (SELECT 1 FROM packets WHERE kind = 'returned_handoff') BEGIN SELECT RAISE(ABORT, 'simulated process death'); END") }
            assertFails { controller().runS0(c, overflowing(), maxHandoffs = 1) }
            c.store.db.tx { it.execute("DROP TRIGGER die") }
            assertEquals(1, ReturnedHandoffs(c.store, clock).all(c.ids.work, c.ids.attempt).size)
            assertTrue(spends(c).isEmpty())
        }
        controller().open(repo.root, request, policy).use { c ->
            val handedOff = c.state!!.cells.single()
            assertEquals(CellStatus.Partial, handedOff.status, "the kept return is applied as its row, not as a lost cell")
            val run = controller().runS0(c, overflowing(), maxHandoffs = 1)

            assertEquals("the campaign's 1 handoffs are spent with 2 requirements unverified", run.state?.reason)
            val increment = c.state!!.graph.increments.single()
            assertEquals(2, increment.cells.size, "the epoch continued the handed-off cell")
            assertEquals(listOf(handedOff.cell.value), spends(c).map { it.text("from") }, "the epoch was paid from the grant")
            assertEquals(1, increment.sizing.handoffs)
            assertEquals(0, increment.sizing.continuations)
        }
    }

    @Test
    fun `an unresolved public impact nudge is kept with the handoff and stays pending in the epoch's ledger`() {
        val definition = io.astrolabe.atlas.ChangedDefinition("src/a.py", "a", io.astrolabe.atlas.DeclarationKind.entries.first(), io.astrolabe.atlas.DefinitionChange.Signature, public = true)
        val nudge = io.astrolabe.cell.ImpactNudge(definition, references = 3, turn = 2)
        val kept = KeptImpact.of(nudge)
        assertEquals(nudge, kept.nudge())
        val ledger = io.astrolabe.cell.ImpactNudges().also { it.carry(listOf(kept.nudge())) }
        assertEquals(listOf(nudge.missing), ledger.unresolvedPublic.map { it.missing }, "the epoch's exit gate lists it as the cell's did")
        ledger.inspected("a")
        assertTrue(ledger.unresolvedPublic.isEmpty(), "look(refs) in the epoch resolves it")
    }

    @Test
    fun `a review finding carried into an epoch is numbered after the archived open items and never revives a closed one`() {
        val closed = io.astrolabe.register.OpenItem(1, "review major: total() rounds at src/a.py:2", closed = true, closedEvidence = "#4")
        val register = io.astrolabe.register.Register.empty(ContextId("cell-a"), "I1", "a returns 10")
            .copy(archive = io.astrolabe.register.RegisterArchive(open = listOf(closed)))
        fun finding(issue: String) = io.astrolabe.verify.Finding(io.astrolabe.verify.Severity.Major, "src/a.py:2", issue, kind = io.astrolabe.verify.FindingKind.Correctness)
        // Findings are numbered in order before the known ones are dropped, so the new finding goes first.
        val carried = Controller.withOpenItems(register, listOf(finding("a() ignores its input"), finding("total() rounds")))
        assertEquals(listOf(2), carried.open.map { it.n }, "o1 is archived: the new item is o2, and the closed finding stays closed")
        assertEquals("review major: a() ignores its input at src/a.py:2", carried.open.single().text)
        assertEquals(register.archive, carried.archive)
    }
}
