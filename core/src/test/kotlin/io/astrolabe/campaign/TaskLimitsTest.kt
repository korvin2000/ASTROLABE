package io.astrolabe.campaign

import io.astrolabe.AttemptConfig
import io.astrolabe.BalanceProfile
import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.CostBasis
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.LimitDecision
import io.astrolabe.budget.LimitKind
import io.astrolabe.budget.LimitRule
import io.astrolabe.budget.LimitSpend
import io.astrolabe.budget.Spend
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
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.evidence.JournalEvent
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
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Money
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.Request
import io.astrolabe.provider.Response
import io.astrolabe.provider.Terminal
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 (plan §4.6) on the fake adapter: money, minutes and request limits stop a campaign before a call would cross them,
 * keep a reserve for verification and report, name the best verified candidate in an honest partial result, survive
 * reopens, and continue the same attempt once the host raises them; the balance profile is frozen for the attempt.
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
                assertEquals(BudgetStop.TaskLimitRequests, run.budgetStop)
                // 8 requests, 3 held: I1's done proposal ran on the reserve at 6, so I2 never starts.
                assertEquals(6, adapter.calls.size)
                assertEquals(IncrementStatus.Verified, c.state!!.graph.increments.single { it.id == "I1" }.status)
                assertTrue(c.state!!.graph.increments.single { it.id == "I2" }.cells.isEmpty())
                val limit = assertNotNull(run.limit)
                assertEquals(listOf("R1"), limit.verified)
                assertTrue(limit.workingTree)
                assertEquals(c.stamper.report().candidateId, limit.bestCandidate)
                assertEquals(3, limit.status.reserveRequests)
                assertTrue(run.state!!.reason!!.endsWith(TaskLimitControl.RAISE_TO_CONTINUE), "every limit stop tells the host how to continue")
                val finish = assertNotNull(run.finish)
                assertEquals("partial", finish.status)
                assertEquals(limit, finish.limit)
                assertEquals(finish.requirements.associate { it.id to it.provenanceClass }, limit.provenance, "the receipt's own provenance calculation")
                assertEquals(io.astrolabe.verify.ProvenanceClass.Unverified, finish.provenanceClass, "R2 is unverified: the campaign takes its worst requirement")
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Campaign.Finished>().isNotEmpty() }
        assertEquals(listOf("reserve", "stopped"), recorder.ofType<AgentEvent.Budget.LimitReached>().map { it.stage })
        assertEquals("raise_limit", recorder.ofType<AgentEvent.Budget.LimitReached>().last().action)
        assertEquals("task_limit_requests", recorder.ofType<AgentEvent.Campaign.Finished>().single().budgetStop)
        val spent = recorder.ofType<AgentEvent.Budget.Spent>().map { it.status.requests }
        assertEquals((0..6).toList(), spent.distinct(), "one counter update before each model call")
        assertEquals(6, spent.last(), "and the counter's last word at finish")
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
            assertTrue("reserve reached: verify and report" in texts(adapter.calls[8].request), "the §5.9 gate line")
            assertTrue(adapter.calls.take(8).none { "reserve reached" in texts(it.request) })
            assertNull(run.limit)
        }
    }

    @Test
    fun `small request limits stop deterministically without empty cells`() = runBlocking<Unit> {
        for ((limit, calls) in listOf(1 to 1, 2 to 2, 3 to 2)) {
            val work = CampaignRequest(WorkId("W-c3-r$limit"), AttemptId("a1"), request.text)
            seed(work)
            controller().open(repo.root, work, policy(TaskLimits(maxRequests = limit))).use { c ->
                val adapter = FakeAdapter(ScriptedModel.of(*planning().toTypedArray()))
                val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, "limit $limit: ${run.state?.reason}")
                assertEquals(BudgetStop.TaskLimitRequests, run.budgetStop, "limit $limit")
                assertEquals(calls, adapter.calls.size, "limit $limit")
                assertTrue(c.state!!.cells.isEmpty(), "limit $limit: no implementing cell is dispatched, empty or not")
                assertNull(run.limit!!.bestCandidate)
            }
        }
    }

    @Test
    fun `a money limit prices every call with its full output headroom and a limit below one call stops at once`() = runBlocking<Unit> {
        // Default headroom (16k output at 15 USD/M): one call is priced near $0.30, so $0.20 cannot admit even the first.
        controller().open(repo.root, request, policy(TaskLimits(maxCost = usd("0.20")))).use { c ->
            val fake = FakeAdapter(ScriptedModel.of(*planning().toTypedArray()))
            val run = controller().run(c, CellModel(Billing(fake, usd("0.10")), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            assertEquals(BudgetStop.TaskLimitMoney, run.budgetStop)
            assertTrue(fake.calls.isEmpty(), "no call crosses the limit")
            val needed = assertNotNull(run.limit!!.status.nextCallCost)
            assertTrue(needed.amount > BigDecimal("0.20"), "the stop names the price it could not admit: $needed")
        }
        // The host raises it: the same attempt continues, and $1 holds at most what one call more would need.
        controller().open(repo.root, request, policy(TaskLimits(maxCost = usd("5")))).use { c ->
            assertEquals(CampaignPhase.Running, c.state!!.phase)
            val fake = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
            val run = controller().run(c, CellModel(Billing(fake, usd("0.10")), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(request.attempt, c.state!!.attempt)
            val spend = TaskLimitControl(idGen, clock, null).spend(c)
            assertEquals(CostBasis.Billed, spend.costBasis)
            assertEquals(0, BigDecimal("1.0").compareTo(spend.cost!!.amount))
        }
    }

    @Test
    fun `a minutes limit runs on the injected clock and never counts the time a task stood stopped`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 7))).use { c ->
            // Every model call takes one minute: R = min(3, 7 − 1) = 3 min, so the reserve begins after 3 min.
            val run = controller().run(c, CellModel(minuteAdapter(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(BudgetStop.TaskLimitMinutes, run.budgetStop, run.state?.reason)
            assertEquals(360_000L, run.limit!!.status.elapsedMillis)
            assertEquals(180_000L, run.limit!!.status.reserveMillis)
            assertEquals(listOf("R1"), run.limit!!.verified)
        }
        clock.advance(Duration.ofHours(5))
        controller().open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
            assertEquals(TaskLimits(maxMinutes = 7), c.limits, "no limits named: the ones kept with the campaign")
            assertEquals(CampaignOutcome.BudgetExhausted, c.state!!.outcome)
            assertEquals(360_000L, TaskLimitControl(idGen, clock, null).spend(c).elapsedMillis)
        }
    }

    @Test
    fun `a run that died mid-session is closed at its last event, and a host's answer is not active time`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 60))).use { c ->
            val control = TaskLimitControl(idGen, clock, null)
            val session = assertNotNull(control.begin(c))
            val host = control.pausing(c, object : io.astrolabe.event.Authority by AutonomousAuthority() {
                override suspend fun ask(question: Question): io.astrolabe.event.Answer? = null.also { clock.advance(Duration.ofMinutes(30)) }
            })
            clock.advance(Duration.ofMinutes(1))
            host.ask(Question("q-1", c.contract.version, c.ids, "May I?"))
            clock.advance(Duration.ofMinutes(1))
            assertEquals(120_000L, control.spend(c).elapsedMillis, "two active minutes; the half hour waiting for the host is not")
            c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, text = "work", at = clock.instant()))
            assertTrue(c.limitState.session === session)
            // The process dies here: no session end is written.
        }
        clock.advance(Duration.ofHours(4))
        controller().open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
            assertEquals(120_000L, TaskLimitControl(idGen, clock, null).spend(c).elapsedMillis, "the four hours stopped are not counted")
        }
    }

    @Test
    fun `a host wait in one branch does not stop the clock while another branch works`() = runBlocking<Unit> {
        // C3r 1: a 60 s limit; branch A (a child cell) waits for the host from t = 10 s while the run itself works 600 s more.
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 1))).use { c ->
            val control = TaskLimitControl(idGen, clock, null)
            assertNotNull(control.begin(c))
            val answer = CompletableDeferred<io.astrolabe.event.Answer?>()
            val host = control.pausing(c, object : io.astrolabe.event.Authority by AutonomousAuthority() {
                override suspend fun ask(question: Question): io.astrolabe.event.Answer? = answer.await()
            })
            clock.advance(Duration.ofSeconds(10))
            val a = launch { control.branch(c) { host.ask(Question("q-a", c.contract.version, c.ids, "May I?")) } }
            yield()
            clock.advance(Duration.ofSeconds(600))
            val spend = control.spend(c)
            assertEquals(610_000L, spend.elapsedMillis, "the run's own work is active time while a child waits for the host")
            assertEquals(LimitKind.Minutes, (LimitRule.decide(c.limits, spend) as LimitDecision.Exhausted).kind)
            // Once every branch waits — the child and the run itself — the clock stops.
            val b = launch { host.ask(Question("q-b", c.contract.version, c.ids, "And I?")) }
            yield()
            clock.advance(Duration.ofMinutes(30))
            assertEquals(610_000L, control.spend(c).elapsedMillis, "all branches wait for the host: not active time")
            answer.complete(null)
            a.join()
            b.join()
            clock.advance(Duration.ofSeconds(1))
            assertEquals(611_000L, control.spend(c).elapsedMillis)
        }
    }

    @Test
    fun `a reopen keeps the latched reserve until the host changes the limits`() = runBlocking<Unit> {
        // C3r 4: L = 60 s. A 15 s call latches the reserve (15 + 15 + 45 > 60); after a 1 s call the raw rule is Within (16 + 8 + 24 ≤ 60).
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 1))).use { c ->
            val adapter = FakeAdapter(ScriptedModel(planning().zip(listOf(15L, 1L)).map { (r, s) -> ScriptedModel.Turn({ true }, { clock.advance(Duration.ofSeconds(s)); r }) }))
            val run = controller().run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(BudgetStop.TaskLimitMinutes, run.budgetStop, run.state?.reason)
            val spend = TaskLimitControl(idGen, clock, null).spend(c)
            assertEquals(16_000L, spend.elapsedMillis)
            assertEquals(LimitDecision.Within, LimitRule.decide(c.limits, spend), "the raw rule alone would reopen the working part")
        }
        controller().open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
            assertEquals(CampaignOutcome.BudgetExhausted, c.state!!.outcome, "the host changed no limit: the reserve stays latched")
            assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { it.text.startsWith("limits: still reached (minutes)") })
        }
        controller().open(repo.root, request, policy(TaskLimits(maxMinutes = 10))).use { c ->
            assertEquals(CampaignPhase.Running, c.state!!.phase, "a changed limit releases the latch")
        }
    }

    @Test
    fun `a raised limit with another still spent stays stopped and says which`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, policy(TaskLimits(maxMinutes = 7, maxRequests = 8))).use { c ->
                val run = controller(events).run(c, CellModel(minuteAdapter(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"")), FakeProfiles.main, HeuristicEstimator()))
                assertEquals(BudgetStop.TaskLimitRequests, run.budgetStop, run.state?.reason)
            }
            controller(events).open(repo.root, request, policy(TaskLimits(maxMinutes = 7, maxRequests = 20))).use { c ->
                assertEquals(CampaignOutcome.BudgetExhausted, c.state!!.outcome, "the minutes are still in the reserve")
                assertTrue(c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile))).any { it.text.startsWith("limits: still reached (minutes)") })
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Budget.LimitReached>().any { it.limit == "minutes" } }
        assertEquals("stopped", recorder.ofType<AgentEvent.Budget.LimitReached>().last { it.limit == "minutes" }.stage)
    }

    @Test
    fun `the candidate is the last accepted stamp, not the edited tree, and a raised limit continues the partial cell`() = runBlocking<Unit> {
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 11))).use { c ->
            val vb = c.registry.version("src/b.py")!!
            // I2 edits b.py at its last generation turn, then spends the reserve looking, never verifying.
            val replies = planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") + listOf(
                Scripted.Reply(listOf<Item>(say("reading"), read("r-b", "src/b.py"))),
                Scripted.Reply(listOf<Item>(say("editing"), anchored("e-b", "src/b.py", vb, "    return 2", "    return 20"))),
                Scripted.Reply(listOf<Item>(say("looking"), read("r-a2", "src/a.py"))),
                Scripted.Reply(listOf<Item>(say("looking"), read("r-p", "pytest_pass.txt"))),
                Scripted.Reply(listOf<Item>(say("looking"), read("r-b2", "src/b.py"))),
            )
            val run = controller().run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(BudgetStop.TaskLimitRequests, run.budgetStop, run.state?.reason)
            val limit = assertNotNull(run.limit)
            assertEquals(listOf("R1"), limit.verified)
            assertFalse(limit.workingTree, "b.py changed after the last accepted stamp; the user's tree is left as it is")
            assertTrue(limit.bestCandidate != limit.workingStamp)
            assertEquals("def b():\n    return 20\n", java.nio.file.Files.readString(repo.root.resolve("src/b.py")))
        }
        controller().open(repo.root, request, policy(TaskLimits(maxRequests = 30))).use { c ->
            val replies = listOf(
                Scripted.Reply(listOf<Item>(say("verifying"), call("v-b", "verify", """{"what":"acceptance","ids":["AC-1","AC-2"]}"""))),
                Scripted.Reply(listOf<Item>(say("done with src/b.py"))),
            )
            val run = controller().run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(2, c.state!!.cells.count { it.increment == "I2" }, "the partial cell is continued, not restarted")
        }
    }

    @Test
    fun `an S0 task stops on its limit and continues after the host lifts it`() = runBlocking<Unit> {
        val s0 = CampaignRequest(WorkId("W-c3-s0"), AttemptId("a1"), "make a return 10")
        seed(s0, shape = Shape.S0)
        controller().open(repo.root, s0, policy(TaskLimits(maxRequests = 2))).use { c ->
            val va = c.registry.version("src/a.py")!!
            val replies = listOf(
                Scripted.Reply(listOf<Item>(say("editing"), anchored("e-a", "src/a.py", va, "    return 1", "    return 10"))),
                Scripted.Reply(listOf<Item>(say("verifying"), call("v-a", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
            )
            val run = controller().runS0(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason)
            assertEquals(BudgetStop.TaskLimitRequests, run.budgetStop)
        }
        controller().open(repo.root, s0, policy(TaskLimits.NONE)).use { c ->
            assertEquals(TaskLimits.NONE, c.limits, "an explicit none lifts the kept limits")
            val run = controller().runS0(c, CellModel(FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf<Item>(say("done"))))), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        }
    }

    @Test
    fun `the reserve of a task limit pays for verification and the report, never for edits`() = runBlocking<Unit> {
        // C3r 2: 4 requests hold 3 back, so every turn after the first is a reserve turn — even for a file the cell changed (D-366 is the turn budget's).
        val s0 = CampaignRequest(WorkId("W-c3-repair"), AttemptId("a1"), "make a return 10")
        seed(s0, shape = Shape.S0)
        controller().open(repo.root, s0, policy(TaskLimits(maxRequests = 4))).use { c ->
            val adapter = FakeAdapter(ScriptedModel.of(
                Scripted.Reply(listOf<Item>(say("scratch"), call("e-new", "edit", """{"ops":[{"create":"src/new.py","content":"new"}],"why":"scratch"}"""))),
                Scripted.Reply(listOf<Item>(say("remove my scratch file"), call("e-del", "edit", """{"ops":[{"delete":"src/new.py","expect":"${io.astrolabe.id.Digest.of("new".toByteArray()).hex}"}],"why":"repair"}"""))),
                Scripted.Reply(listOf<Item>(say("verifying"), call("v-a", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
                Scripted.Reply(listOf<Item>(say("done"))),
            ))
            val run = controller().runS0(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(4, adapter.calls.size, run.state?.reason)
            assertTrue(java.nio.file.Files.exists(repo.root.resolve("src/new.py")), "the edit on the limit's reserve is refused")
            assertTrue(adapter.calls.drop(1).none { it.request.mask!!.allows("edit.delete") || it.request.mask!!.allows("edit.anchored") }, "no edit op on a task limit's reserve turn")
            assertTrue(CellBudget.GATE in texts(adapter.calls[1].request), "the gate line")
            assertTrue(adapter.calls.none { "repairs to your own files only" in texts(it.request) }, "never the repair line")
        }
    }

    @Test
    fun `a dearer rendered request reaches the reserve without ending the cell, and three verify calls still fit`() = runBlocking<Unit> {
        // C3r 3: L = $10, S = $6 over six calls of u = $1, the last estimate E = $1: the turn starts Within; the rendered E = $1.10 is Reserve.
        controller().open(repo.root, request, policy(TaskLimits(maxCost = usd("10")))).use { c ->
            val dear = FakeProfiles.main.copy(priceTable = FakeProfiles.prices("10", "10", "10", "10", "62.5"))
            val billed = BillableUsage(mapOf(BillingDimension.OUTPUT to 1L), UsageProvenance("fake", "fake-main", "test"), billed = usd("1"))
            repeat(6) { Accounting(c.store, clock).record(c.ids, "inv-$it", dear, null, billed) }
            c.limitState.lastEstimate = usd("1")
            val gate = TaskLimitControl(idGen, clock, null).cell(c, CellModel(FakeAdapter(ScriptedModel.of()), dear, HeuristicEstimator())).gate
            assertEquals(LimitDecision.Within, gate.check(Spend.Generation, Tokens.ZERO), "the turn starts at the last request's price")
            // $0.10 of input at $10 per million plus the $1.00 output headroom (16k at $62.5 per million).
            val rendered = Tokens(10_000L + 16_000L)
            assertIs<LimitDecision.Reserve>(gate.check(Spend.Generation, rendered))
            assertNull(c.limitState.block, "no refusal that ends the cell: the turn is rendered again as verify and report")
            assertIs<LimitDecision.Reserve>(gate.check(Spend.Check, rendered), "which the reserve admits")
            // The $4 left hold three verify-and-report calls at $1.10; a fourth would cross the limit.
            fun spent(amount: String) = LimitSpend(6, usd(amount), CostBasis.Billed, 0, usd("1.1"))
            for (amount in listOf("6", "7.1", "8.2")) assertIs<LimitDecision.Reserve>(LimitRule.decide(c.limits, spent(amount), usd("1.1")), amount)
            assertIs<LimitDecision.Exhausted>(LimitRule.decide(c.limits, spent("9.3"), usd("1.1")))
        }
    }

    @Test
    fun `run and check deadlines are cut at dispatch to the time left, and nothing starts without any`() = runBlocking<Unit> {
        // C3r 5: the cell is created with 60 s left; its model call takes 59 s, so a run launched after it gets 1 s, not 60.
        fun timed(seconds: Long, vararg items: Item) =
            FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, { clock.advance(Duration.ofSeconds(seconds)); Scripted.Reply(items.toList()) }))))
        fun handles(c: OpenedCampaign) = c.store.db.query("SELECT handle_id FROM handles WHERE work_id = ?", c.ids.work) { it.string("handle_id") }
            .map { io.astrolabe.tool.run.SqliteHandles(c.store, clock).get(it)!! }
        val early = CampaignRequest(WorkId("W-c3-deadline"), AttemptId("a1"), "make a return 10")
        seed(early, shape = Shape.S0)
        controller().open(repo.root, early, policy(TaskLimits(maxMinutes = 1))).use { c ->
            val run = controller().runS0(c, CellModel(timed(59, say("starting"), call("r-bg", "run", """{"op":"run","cmd":"echo hi","bg":true}""")), FakeProfiles.main, HeuristicEstimator()))
            assertEquals(BudgetStop.TaskLimitMinutes, run.budgetStop, run.state?.reason)
            assertEquals(listOf(1L), handles(c).map { it.proc.deadlineSeconds }, "the deadline is the time left at launch")
        }
        // A 61 s call leaves no active time: its run is refused and its check is not run (never a minimal second).
        val late = CampaignRequest(WorkId("W-c3-deadline-late"), AttemptId("a1"), "make a return 10")
        seed(late, shape = Shape.S0)
        controller().open(repo.root, late, policy(TaskLimits(maxMinutes = 1))).use { c ->
            val adapter = timed(61, say("starting"), call("r-bg", "run", """{"op":"run","cmd":"echo hi","bg":true}"""), call("v-a", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))
            val run = controller().runS0(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(BudgetStop.TaskLimitMinutes, run.budgetStop, run.state?.reason)
            assertTrue(handles(c).isEmpty(), "no process is launched")
            val outcomes = c.store.db.query("SELECT outcome FROM receipts WHERE work_id = ?", c.ids.work) { it.string("outcome") }
            assertTrue(outcomes.isNotEmpty() && outcomes.all { it == "NotRun" }, outcomes.toString())
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
                val run = controller(events).run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()), maxCells = 1)
                assertEquals(BudgetStop.CellCap, run.budgetStop)
                // A dear model (15 USD per million output) one step below its configured effort; three quarters of its window.
                assertTrue(adapter.calls.all { it.request.effort == Effort.Low }, adapter.calls.map { it.request.effort }.toString())
                assertTrue(adapter.calls.all { it.request.profile.capabilities.contextLimitTokens == 150_000 })
            }
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000), balance = BalanceProfile.Thorough)).use { c ->
                assertEquals(BalanceProfile.Economy, c.attempt.config.balance, "invariant 12: the attempt keeps its profile")
                assertEquals(CampaignPhase.Running, c.state!!.phase, "the cell cap counts per run: a reopen continues")
            }
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
                assertEquals(BalanceProfile.Economy, c.attempt.config.balance, "a reopen naming none asks for the frozen one")
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Warning>().any { it.kind == "config-frozen" } }
        assertEquals(1, recorder.ofType<AgentEvent.Warning>().count { it.kind == "config-frozen" }, "only the reopen that asked for another profile")
    }

    @Test
    fun `no limit and no profile change nothing but the counter`() = runBlocking<Unit> {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            controller(events).open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
                assertEquals(TaskLimits.NONE, c.limits)
                assertEquals(AttemptConfig.freeze(config()).fingerprint, c.attempt.fingerprint)
                val adapter = FakeAdapter(ScriptedModel.of(*(planning() + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") +
                    implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-1\",\"AC-2\"")).toTypedArray()))
                val run = controller(events).run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                assertTrue(adapter.calls.all { it.request.effort == Effort.Medium && it.request.profile == FakeProfiles.main })
                assertEquals(10, Accounting(c.store, clock).calls(request.work).size)
            }
        }
        awaitEvents(recorder) { recorder.ofType<AgentEvent.Campaign.Finished>().isNotEmpty() }
        assertTrue(recorder.ofType<AgentEvent.Budget.LimitReached>().isEmpty())
        assertEquals(10, recorder.ofType<AgentEvent.Budget.Spent>().last().status.requests, "the live counter runs without limits too")
        assertNull(recorder.ofType<AgentEvent.Budget.Spent>().last().status.maxRequests)
    }

    @Test
    fun `the cell boundary takes its seeds from the attempt's seed rule`() = runBlocking<Unit> {
        // D-398 at the cell boundary: I1's first cell reads a.py without naming it in Next or Focus and runs out of turns;
        // Seeds v1 carries nothing into the continuation, Seeds v2 carries the recent read.
        suspend fun continuationK(rule: io.astrolabe.context.SeedRule): String {
            val work = CampaignRequest(WorkId("W-c3-seed-${rule.wire}"), AttemptId("a1"), request.text)
            seed(work, defaults = io.astrolabe.Defaults(turnsPerCell = 5))
            val ctl = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = alwaysPlan.copy(seedRule = rule)), clock, idGen)
            return ctl.open(repo.root, work, CampaignPolicy(Tokens(400_000))).use { c ->
                val look = (1..4).map { Scripted.Reply(listOf<Item>(say("looking"), io.astrolabe.cell.CellFixture.Companion.tree("t-$it"))) }
                val adapter = FakeAdapter(ScriptedModel.of(*(planning() + Scripted.Reply(listOf<Item>(say("reading"), read("r-a", "src/a.py"))) + look).toTypedArray()))
                ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()), maxCells = 2)
                assertEquals(2, c.state!!.cells.count { it.increment == "I1" }, "a continuation cell ran")
                adapter.calls[7].request.segments.filter { it.kind == io.astrolabe.provider.SegmentKind.K }
                    .flatMap { it.items.filterIsInstance<io.astrolabe.provider.Message>() }.joinToString("\n") { it.text }
            }
        }
        assertFalse("SEED src/a.py" in continuationK(io.astrolabe.context.SeedRule.V1))
        assertTrue("SEED src/a.py" in continuationK(io.astrolabe.context.SeedRule.V2))
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

    /** Each model call takes one minute of the injected clock. */
    private fun minuteAdapter(replies: List<Scripted>) = FakeAdapter(ScriptedModel(replies.map { r -> ScriptedModel.Turn({ true }, { clock.advance(Duration.ofMinutes(1)); r }) }))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        seed(request)
    }

    private fun seed(work: CampaignRequest, shape: Shape = Shape.S1, defaults: io.astrolabe.Defaults = io.astrolabe.Defaults()) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(work.work, work.attempt, work.text, Atlas.build(repo.root), Config(defaults = defaults), Tokens(400_000)).contract
            val ref = derived.requests.single().id
            contracts.open(derived.copy(
                shape = shape,
                requirements = if (shape == Shape.S0) listOf(Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = ref))
                    else listOf(Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = ref), Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = ref)),
                acceptance = if (shape == Shape.S0) listOf(Acceptance.Run("AC-1", printing, Origin.User))
                    else listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
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
