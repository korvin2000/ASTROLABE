package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.BalanceProfile
import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.CostBasis
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.TaskLimits
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
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Money
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Terminal
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 (plan §4.6) on the fake adapter: money, minutes and request limits stop a campaign before they are crossed, keep
 * the reserve for verification and report, name the best verified candidate in an honest partial result, and resume
 * the same attempt once the host raises them; the balance profile is chosen at start and frozen for the attempt.
 */
class TaskLimitsTest {
    @Test
    fun `a request limit stops before it is crossed and names the best verified candidate`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, policy(TaskLimits(maxRequests = 8))).use { c ->
                val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                    implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
                val run = controller(events).run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))

                assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
                assertTrue(run.state!!.reason!!.startsWith("task limit reached (requests)"), run.state!!.reason)
                // 8 requests, 2 held for verification and report: I1 is done at 6, so I2 never starts.
                assertEquals(6, adapter.calls.size)
                assertEquals(6, Accounting(c.store, clock).calls(request.work).size)
                assertEquals(IncrementStatus.Verified, c.state!!.graph.increments.single { it.id == "I1" }.status)
                assertTrue(c.state!!.graph.increments.single { it.id == "I2" }.cells.isEmpty())
                val limit = assertNotNull(run.limit)
                assertEquals(listOf("R1"), limit.verified)
                assertTrue(limit.workingTree)
                assertEquals(c.stamper.report().candidateId, limit.bestCandidate)
                assertEquals(6, limit.status.requests)
                assertEquals(2, limit.status.reserveRequests)
                assertTrue(run.state!!.reason!!.endsWith(TaskLimitControl.RAISE_TO_CONTINUE), "every limit stop tells the host how to continue")
                // The honest partial result: the receipt names the candidate and keeps its C2 provenance class.
                val finish = assertNotNull(run.finish)
                assertEquals("partial", finish.status)
                assertEquals(limit, finish.limit)
                assertEquals(io.astrolabe.verify.ProvenanceClass.Unverified, finish.provenanceClass, "R2 is unverified: the campaign takes its worst requirement")
                assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("limits: reserve reached (requests)") })
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Budget.LimitReached>().size == 2 }
        assertEquals(listOf("reserve", "stopped"), recorder.ofType<AgentEvent.Budget.LimitReached>().map { it.stage })
        assertEquals("requests", recorder.ofType<AgentEvent.Budget.LimitReached>().last().limit)
        assertEquals("raise_limit", recorder.ofType<AgentEvent.Budget.LimitReached>().last().action)
        val spent = recorder.ofType<AgentEvent.Budget.Spent>().map { it.status.requests }
        assertEquals((0..6).toList(), spent, "one counter update before each model call, and the last at the stop")
    }

    @Test
    fun `the reserve lets the last increment verify and report within the request limit`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 11))).use { c ->
            val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            // Generation stops at 8; I2's verify and done turns run on the 3 held back, and the campaign completes.
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(10, adapter.calls.size)
            // §5.9 gate in `[A]` from the ninth request on: verify and report, edits to the cell's own files only (D-366).
            assertTrue("reserve reached: verify and report" in texts(adapter.calls[8].request), "the gate line, once")
            assertTrue(adapter.calls.drop(8).all { "reserve reached ·" in texts(it.request) }, "the gauge on every reserve turn")
            assertTrue(adapter.calls.take(8).none { "reserve reached" in texts(it.request) })
            assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).any { it.text.startsWith("limits: reserve reached (requests)") })
            assertNull(run.limit)
        }
    }

    @Test
    fun `a money limit counts the billed amounts and stops before it is crossed`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, policy(TaskLimits(maxCost = usd("0.80")))).use { c ->
                val fake = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                    implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
                val run = controller(events).run(c, CellModel(Billing(fake, usd("0.10")), FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 2_000))

                assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
                assertTrue(run.state!!.reason!!.startsWith("task limit reached (money)"), run.state!!.reason)
                // Reserve min(3 · 0.10, 0.2 · 0.80) = 0.16: generation while spend + 0.10 ≤ 0.64, so no cell starts at 0.60.
                assertEquals(6, fake.calls.size)
                val limit = assertNotNull(run.limit)
                assertEquals(0, BigDecimal("0.60").compareTo(limit.status.cost!!.amount))
                assertEquals(CostBasis.Billed, limit.status.costBasis)
                assertEquals(0, BigDecimal("0.16").compareTo(limit.status.reserveCost!!.amount))
                assertEquals(listOf("R1"), limit.verified)
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Budget.LimitReached>().any { it.stage == "stopped" } }
        assertTrue(recorder.ofType<AgentEvent.Budget.Spent>().drop(1).all { it.status.costBasis == CostBasis.Billed }, "the event says which amounts were used")
    }

    @Test
    fun `a minutes limit runs on the injected clock`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 7))).use { c ->
            val replies = planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")
            // Every model call takes one minute: the reserve is min(3 · 1, 0.2 · 7) = 1.4 min, so no cell starts at 6.
            val adapter = FakeAdapter(ScriptedModel(replies.map { r -> ScriptedModel.Turn({ true }, { clock.advance(Duration.ofMinutes(1)); r }) }))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            assertTrue(run.state!!.reason!!.startsWith("task limit reached (minutes)"), run.state!!.reason)
            assertEquals(6, adapter.calls.size)
            assertEquals(360_000L, run.limit!!.status.elapsedMillis)
            assertEquals(84_000L, run.limit!!.status.reserveMillis)
        }
        // A reopen never counts the time the task stood stopped.
        clock.advance(Duration.ofHours(5))
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 7))).use { c ->
            assertEquals(CampaignOutcome.BudgetExhausted, c.state!!.outcome, "still at 6 of 7 minutes: no room")
            assertEquals(360_000L, TaskLimitControl(idGen, clock, null).spend(c).elapsedMillis)
        }
    }

    @Test
    fun `only the host raises a limit - a reopen spends nothing twice and a raised limit resumes the same attempt`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 8))).use { c ->
            val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"")).toTypedArray()))
            assertEquals(CampaignOutcome.BudgetExhausted, controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator())).outcome)
        }
        // The same limits: still stopped, nothing dispatched, the spend as it was (D-392).
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 8))).use { c ->
            val adapter = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(say("should never run")))))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome)
            assertTrue(adapter.calls.isEmpty())
            assertEquals(6, Accounting(c.store, clock).calls(request.work).size)
            assertEquals(listOf("R1"), run.limit!!.verified, "the receipt of a reopen still names the candidate")
        }
        // The host raises the limit: the same attempt continues with I2 and completes; I1 is not run again.
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 20))).use { c ->
            assertEquals(CampaignPhase.Running, c.state!!.phase)
            assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { it.text.startsWith("limits: raised by the host: 20 requests") })
            val adapter = FakeAdapter(ScriptedModel.of(*implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"").toTypedArray()))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(request.attempt, c.state!!.attempt)
            assertEquals(1, c.state!!.cells.count { it.increment == "I1" })
            assertEquals(10, Accounting(c.store, clock).calls(request.work).size)
            assertNull(run.limit)
        }
    }

    @Test
    fun `the balance profile is chosen at start and frozen for the attempt`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000), balance = BalanceProfile.Economy)).use { c ->
                assertEquals(BalanceProfile.Economy, c.attempt.config.balance)
                assertEquals(3_000, c.attempt.config.defaults.lookBudgetTokens)
                val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"")).toTypedArray()))
                controller(events).run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()), maxCells = 1)
                // A dear model (15 USD per million output) one step below its configured effort; three quarters of its window.
                assertTrue(adapter.calls.all { it.request.effort == Effort.Low }, adapter.calls.map { it.request.effort }.toString())
                assertTrue(adapter.calls.all { it.request.profile.capabilities.contextLimitTokens == 150_000 })
            }
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000), balance = BalanceProfile.Thorough)).use { c ->
                assertEquals(BalanceProfile.Economy, c.attempt.config.balance, "invariant 12: the attempt keeps its profile")
            }
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
                assertEquals(BalanceProfile.Economy, c.attempt.config.balance, "a reopen naming none asks for the frozen one")
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Warning>().any { it.kind == "config-frozen" } }
        assertEquals(1, recorder.ofType<AgentEvent.Warning>().count { it.kind == "config-frozen" }, "only the reopen that asked for another profile")
    }

    @Test
    fun `no limit and no profile change nothing`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, policy(TaskLimits.NONE)).use { c ->
                assertEquals(AttemptConfig.freeze(config()).fingerprint, c.attempt.fingerprint)
                val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                    implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
                val run = controller(events).run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                assertTrue(adapter.calls.all { it.request.effort == Effort.Medium && it.request.profile == FakeProfiles.main })
                assertTrue(c.journal.events(JournalScope(request.work)).none { it.text.startsWith("limits:") }, "no limit, no session records")
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Campaign.Finished>().isNotEmpty() }
        assertTrue(recorder.events.none { it is AgentEvent.Budget.Spent || it is AgentEvent.Budget.LimitReached })
    }

    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-03T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-c3"), AttemptId("a1"), "make a return 10 and b return 20")

    private fun policy(limits: TaskLimits) = CampaignPolicy(Tokens(400_000), limits = limits)

    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    private fun config() = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = alwaysPlan)

    private fun controller(events: Events? = null) = Controller(config(), clock, idGen, events)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), Tokens(400_000)).contract
            contracts.open(derived.copy(
                shape = Shape.S1,
                requirements = listOf(
                    Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                    Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = derived.requests.single().id),
                ),
                acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
            ))
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

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

    private fun texts(request: Request): String =
        request.segments.flatMap { it.items }.filterIsInstance<io.astrolabe.provider.Message>().joinToString("\n") { it.text }

    /** Events reach a subscriber asynchronously: waits until [ready] holds, at most five seconds. */
    private fun awaitEvents(recorder: EventRecorder, ready: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!ready() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(ready(), "events not delivered: ${recorder.events.map { it::class.simpleName }}")
    }

    /** The fake adapter with every call billed [perCall], as a gateway reports it (D-378). */
    private class Billing(private val inner: FakeAdapter, private val perCall: Money) : ProviderAdapter by inner {
        override fun start(request: Request, id: InvocationId): Invocation {
            val call = inner.start(request, id)
            fun Response.billed() = copy(usage = usage?.copy(billed = perCall))
            return object : Invocation by call {
                override suspend fun await(): Response = call.await().billed()
                override suspend fun terminal(): Terminal = call.terminal().let { it.copy(usage = it.usage?.copy(billed = perCall), response = it.response?.billed()) }
            }
        }
    }
}
