package io.astrolabe.campaign

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellCheckpoint
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.PartialReason
import io.astrolabe.cell.ResultPacket
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.Views
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.register.Register
import io.astrolabe.store.openStore
import io.astrolabe.tool.state.BlockedRequest
import io.astrolabe.verify.Applicability
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.Currency
import io.astrolabe.verify.ExitKind
import io.astrolabe.verify.Verifier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.9.1: the controller's lifecycle is one state machine over typed records (§3.2, §3.7, §5.9, invariant 11). */
class LifecycleTest {
    @TempDir
    lateinit var root: Path

    private val stamp = CandidateId(Digest.ofUtf8("s1"))
    private val later = CandidateId(Digest.ofUtf8("s2"))
    private val contract = Contract(
        WorkId("W-life"), 1, AttemptId("a1"), Mode.Autonomous, Shape.S0,
        listOf(UserRequest("U1", Instant.EPOCH, "Fix the parser")),
        listOf(Requirement("R1", "the parser accepts empty input", listOf("AC1"), authorityRef = "U1")),
        listOf(Acceptance.Run("AC1", Command(listOf("pytest", "-q")), Origin.User)),
        emptyList(), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )
    private val graph = RequirementGraph(listOf(
        Increment("I1", listOf("R1"), listOf("AC1"), listOf("src/"), 1, title = "Fix it", produces = Production.Artifact),
    ))
    private val c1 = ContextId("cell-1")
    private val c2 = ContextId("cell-2")

    private fun CampaignState.then(vararg transitions: Transition): CampaignState =
        transitions.fold(this) { s, t -> Lifecycle.apply(s, contract, t) }

    private fun opened() = Lifecycle.open(contract, graph)
    private fun running() = opened().then(Transition.Reconciled())
    private fun dispatched(cell: ContextId = c1) = running().then(Transition.Dispatched("I1", cell))

    private fun packet(cell: ContextId, status: PacketStatus, blocked: BlockedRequest? = null, reason: String? = "stop") = ResultPacket(
        Identities(contract.workId, contract.attemptId, stamp, cell), "I1", "implement", contract.version, ExecutionGeneration.INITIAL,
        PacketBase(stamp, WorkspaceId("ws-main")), emptyMap(), status, if (status == PacketStatus.Done) null else reason, null,
        Register.empty(cell, "I1", "Fix it"), emptyList(), emptyList(), emptyList(), emptyList(), stamp, Digest.ofUtf8("env"),
        PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), blocked, emptyList(), emptyList(), PacketCost(),
    )

    private fun checkpoint(cell: ContextId, status: CellStatus, touched: List<String> = emptyList()) =
        CellCheckpoint(cell, "I1", 1, status, 1, stamp, 0, 0, emptyList(), touched, emptyList(), 0)

    private fun completed(cell: ContextId = c1) = CellExit.Completed(1, Register.empty(cell, "I1", "Fix it"), checkpoint(cell, CellStatus.Completed), packet(cell, PacketStatus.Done), "done", emptyList())
    private fun blocked(question: String?, cell: ContextId = c1): CellExit.Blocked {
        val request = BlockedRequest("need the fixture", listOf("#3"), question, 1)
        return CellExit.Blocked(1, Register.empty(cell, "I1", "Fix it"), checkpoint(cell, CellStatus.Blocked), packet(cell, PacketStatus.Blocked, request), request)
    }
    private fun partial(reason: PartialReason, cell: ContextId = c1) =
        CellExit.Partial(1, Register.empty(cell, "I1", "Fix it"), checkpoint(cell, CellStatus.Partial), packet(cell, PacketStatus.Partial), reason, "continue")
    private fun failed(cell: ContextId = c1) =
        CellExit.Failed(1, Register.empty(cell, "I1", "Fix it"), checkpoint(cell, CellStatus.Failed), packet(cell, PacketStatus.Failed), "provider error")
    private fun cancelled(cell: ContextId = c1) =
        CellExit.Cancelled(1, Register.empty(cell, "I1", "Fix it"), checkpoint(cell, CellStatus.Cancelled), packet(cell, PacketStatus.Cancelled), "cancelled by host")

    private fun accepted(state: CampaignState, cell: ContextId = c1, at: CandidateId = stamp): CompletionResult.Accepted {
        val increment = state.graph.increments.single { it.id == "I1" }
        return assertIs(Verifier().accept(
            CompletionProposal("I1", "done", contract.version, stamp, at, null, Digest.ofUtf8("env")),
            contract, increment, Register.empty(cell, "I1", "Fix it"), Ledger.initial(contract), at,
            mapOf("AC1" to Currency("rcpt-1", Applicability.Current, true, true, emptyList())),
        ))
    }

    private fun verified(): CampaignState {
        val returned = dispatched().then(Transition.Returned(completed()))
        return returned.then(Transition.Committed(accepted(returned), stamp))
    }

    @Test
    fun `an S0 campaign reaches completed only through receipts, one transition at a time`() {
        val returned = dispatched().then(Transition.Returned(completed()))
        val close = assertIs<Disposition.Close>(Lifecycle.disposition(completed(), accepted(returned)))
        val committed = returned.then(Transition.Committed(close.accepted, stamp))
        assertEquals(IncrementStatus.Verified, committed.graph.increments.single().status)
        assertEquals(RequirementStatus.Verified, committed.ledger["R1"]!!.status)
        assertTrue(committed.ledger["R1"]!!.stampValid)

        val done = committed.then(Transition.Finishing(stamp), Transition.Finished(stamp, listOf("rcpt-final")))
        assertEquals(CampaignPhase.Ended, done.phase)
        assertEquals(CampaignOutcome.Completed, done.outcome)
        assertEquals(6, done.seq, "open is seq 0; six transitions")
        assertEquals(listOf(CellState(c1, "I1", CellStatus.Completed)), done.cells)
    }

    @Test
    fun `every transition is legal only in its phases`() {
        val all: List<(CampaignState) -> Transition> = listOf(
            { Transition.Reconciled() },
            { Transition.Dispatched("I1", c2) },
            { Transition.Returned(completed(it.running?.cell ?: c1)) },
            { s -> Transition.Committed(if (s.cells.any { it.status == CellStatus.Completed }) accepted(s) else accepted(dispatched().then(Transition.Returned(completed()))), stamp) },
            { Transition.Unblocked("I1", "U2") },
            { Transition.IncrementCancelled("I1", "withdrawn") },
            { Transition.Finishing(stamp) },
            { Transition.Finished(stamp, listOf("rcpt-final")) },
            { Transition.Stopped(CampaignOutcome.Cancelled, "host cancelled") },
            { Transition.Resumed("answer arrived") },
        )
        val names = listOf("Reconciled", "Dispatched", "Returned", "Committed", "Unblocked", "IncrementCancelled", "Finishing", "Finished", "Stopped", "Resumed")
        val states = mapOf(
            "opened" to opened(),
            "running" to running(),
            "cell running" to dispatched(),
            "cell completed" to dispatched().then(Transition.Returned(completed())),
            "cell blocked" to dispatched().then(Transition.Returned(blocked("which fixture?"))),
            "verified" to verified(),
            "finishing" to verified().then(Transition.Finishing(stamp)),
            "waiting for input" to running().then(Transition.Stopped(CampaignOutcome.WaitingForInput, "question")),
            "completed" to verified().then(Transition.Finishing(stamp), Transition.Finished(stamp, listOf("r"))),
            "failed" to running().then(Transition.Stopped(CampaignOutcome.Failed, "harness error")),
        )
        val legal = mapOf(
            "opened" to setOf("Reconciled", "IncrementCancelled", "Stopped"),
            "running" to setOf("Dispatched", "IncrementCancelled", "Stopped"),
            "cell running" to setOf("Returned"),
            "cell completed" to setOf("Dispatched", "Committed", "IncrementCancelled", "Stopped"),
            "cell blocked" to setOf("Unblocked", "IncrementCancelled", "Stopped"),
            "verified" to setOf("Committed", "Finishing", "Stopped"),  // a re-commit refreshes regression evidence
            "finishing" to setOf("Finished", "Stopped"),
            "waiting for input" to setOf("Resumed"),
            "completed" to emptySet(),
            "failed" to emptySet(),
        )
        for ((name, state) in states) {
            val allowed = all.indices.filter { i ->
                runCatching { Lifecycle.apply(state, contract, all[i](state)) }.isSuccess
            }.map { names[it] }.toSet()
            assertEquals(legal.getValue(name), allowed, "legal transitions from '$name'")
        }
    }

    @Test
    fun `a partial, blocked, failed or cancelled cell never verifies its increment`() {
        val completedElsewhere = dispatched().then(Transition.Returned(completed()))
        val acceptance = accepted(completedElsewhere)
        for (exit in listOf(partial(PartialReason.TurnBudget), blocked(null), failed(), cancelled())) {
            val returned = dispatched().then(Transition.Returned(exit))
            assertFailsWith<IllegalStateException>(exit.status.name) { returned.then(Transition.Committed(acceptance, stamp)) }
            assertFailsWith<IllegalArgumentException>(exit.status.name) { Lifecycle.disposition(exit, acceptance) }
            assertTrue(returned.graph.increments.single().status != IncrementStatus.Verified)
        }
    }

    @Test
    fun `a stale or superseded completion is not committed`() {
        val returned = dispatched().then(Transition.Returned(completed()))
        assertFailsWith<IllegalStateException>("tree moved") { returned.then(Transition.Committed(accepted(returned), later)) }
        // A second cell on the same increment supersedes the first one's completion.
        val continued = returned.then(Transition.Dispatched("I1", c2), Transition.Returned(completed(c2)))
        assertFailsWith<IllegalStateException>("superseded cell") { continued.then(Transition.Committed(accepted(returned), stamp)) }
        continued.then(Transition.Committed(accepted(continued, c2), stamp))
    }

    @Test
    fun `completed needs every requirement verified at the final stamp`() {
        assertFailsWith<IllegalStateException>("nothing verified") { dispatched().then(Transition.Returned(completed()), Transition.Finishing(stamp)) }
        assertFailsWith<IllegalStateException>("verified at another stamp") { verified().then(Transition.Finishing(later)) }
        assertFailsWith<IllegalStateException>("final acceptance at another stamp") { verified().then(Transition.Finishing(stamp), Transition.Finished(later, listOf("r"))) }
        assertFailsWith<IllegalArgumentException> { Transition.Finished(stamp, emptyList()) }
        assertFailsWith<IllegalArgumentException> { Transition.Stopped(CampaignOutcome.Completed, "claimed") }
    }

    @Test
    fun `a blocked packet parks the increment until the authority answers, and a waiting campaign resumes`() {
        val waiting = dispatched().then(Transition.Returned(blocked("which fixture?")))
        assertEquals(IncrementStatus.Blocked, waiting.graph.increments.single().status)
        val stop = assertIs<Disposition.Stop>(Lifecycle.disposition(blocked("which fixture?"), null))
        assertEquals(CampaignOutcome.WaitingForInput, stop.outcome)
        val ended = waiting.then(Transition.Stopped(stop.outcome, stop.reason))

        val resumed = ended.then(Transition.Resumed("the user answered"), Transition.Unblocked("I1", "U2"), Transition.Reconciled())
        assertEquals(CampaignPhase.Running, resumed.phase)
        assertNull(resumed.outcome)
        assertEquals(IncrementStatus.InProgress, resumed.graph.increments.single().status)
        resumed.then(Transition.Dispatched("I1", c2))
    }

    @Test
    fun `the dispatch table covers every exit, partial reason and verifier result`() {
        val returned = dispatched().then(Transition.Returned(completed()))
        val refused = CompletionResult.Refused(listOf("AC1: no current receipt"), 1, recoveryDirected = false)
        val exhausted = CompletionResult.Refused(listOf("AC1: no current receipt"), 2, recoveryDirected = true)
        val notCompleted = CompletionResult.NotCompleted(ExitKind.Partial, "partial")

        assertIs<Disposition.Close>(Lifecycle.disposition(completed(), accepted(returned)))
        assertEquals(Disposition.Continue("completion refused: AC1: no current receipt", CampaignOutcome.Failed), Lifecycle.disposition(completed(), refused))
        assertEquals(CampaignOutcome.Failed, assertIs<Disposition.Stop>(Lifecycle.disposition(completed(), exhausted)).outcome)
        assertFailsWith<IllegalArgumentException> { Lifecycle.disposition(completed(), null) }
        assertFailsWith<IllegalArgumentException> { Lifecycle.disposition(completed(), notCompleted) }

        assertEquals(Disposition.Stop(CampaignOutcome.WaitingForInput, "need the fixture"), Lifecycle.disposition(blocked("which?"), null))
        assertEquals(Disposition.Stop(CampaignOutcome.BlockedExternal, "need the fixture"), Lifecycle.disposition(blocked(null), null))
        assertEquals(Disposition.Stop(CampaignOutcome.Failed, "provider error"), Lifecycle.disposition(failed(), null))
        assertEquals(Disposition.Stop(CampaignOutcome.Cancelled, "cancelled by host"), Lifecycle.disposition(cancelled(), notCompleted))

        val fallbacks = PartialReason.entries.associateWith { reason ->
            assertIs<Disposition.Continue>(Lifecycle.disposition(partial(reason), notCompleted)).fallback
        }
        assertEquals(mapOf(
            PartialReason.TurnBudget to CampaignOutcome.BudgetExhausted,
            PartialReason.TokenBudget to CampaignOutcome.BudgetExhausted,
            PartialReason.Reserve to CampaignOutcome.BudgetExhausted,
            PartialReason.Pressure to CampaignOutcome.Failed,
            PartialReason.CompletionStalled to CampaignOutcome.Failed,
        ), fallbacks)
        // Only the table's outcomes exist; completed is never a disposition of a single cell.
        assertEquals(setOf("waiting_for_process", "waiting_for_input", "blocked_external"), CampaignOutcome.entries.filter { it.resumable }.map { it.wire }.toSet())
    }

    @Test
    fun `an invalid graph never opens and a foreign contract never applies`() {
        assertFailsWith<IllegalArgumentException> { Lifecycle.open(contract, RequirementGraph(emptyList())) }
        val other = contract.copy(attemptId = AttemptId("a2"))
        assertFailsWith<IllegalArgumentException> { Lifecycle.apply(opened(), other, Transition.Reconciled()) }
    }

    @Test
    fun `sizing counts turns, continuations, pressure rebuilds and the touched union at each cell end`() {
        val pressured = CellExit.Partial(
            4, Register.empty(c1, "I1", "Fix it"), checkpoint(c1, CellStatus.Partial, listOf("src/a.py", "src/b.py")),
            packet(c1, PacketStatus.Partial), PartialReason.Pressure, "replan",
        )
        val finished = CellExit.Completed(
            3, Register.empty(c2, "I1", "Fix it"), checkpoint(c2, CellStatus.Completed, listOf("src/b.py", "src/c.py")),
            packet(c2, PacketStatus.Done), "done", emptyList(),
        )
        val state = dispatched().then(Transition.Returned(pressured), Transition.Dispatched("I1", c2), Transition.Returned(finished))
        val sizing = state.graph.increments.single().sizing
        assertEquals(7, sizing.turns)
        assertEquals(1, sizing.continuations, "the second cell continues the increment")
        assertEquals(1, sizing.rebuilds, "the pressure stop is a decomposition failure")
        assertEquals(listOf("src/a.py", "src/b.py", "src/c.py"), sizing.touched)
        assertEquals(3, sizing.filesTouched)

        val interrupted = dispatched().then(Transition.Interrupted(checkpoint(c1, CellStatus.Cancelled, listOf("src/a.py"))))
        assertEquals(1, interrupted.graph.increments.single().sizing.turns)
        val lost = dispatched().then(Transition.Lost(c1, null))
        assertEquals(0, lost.graph.increments.single().sizing.turns, "a lost cell without a checkpoint adds nothing it cannot show")
    }

    @Test
    fun `the store keeps the state with its ledger and refuses a save that does not extend it`() {
        val clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC)
        val store = openStore(root, clock)
        store.use {
            val campaigns = SqliteCampaigns(store, clock)
            val states = listOf(opened(), running(), dispatched(), dispatched().then(Transition.Returned(completed())))
            states.forEach(campaigns::save)
            val final = verified().then(Transition.Finishing(stamp), Transition.Finished(stamp, listOf("rcpt-final")))
            val committed = verified()
            campaigns.save(committed)
            assertEquals(committed, campaigns.load(contract.workId, contract.attemptId))

            assertFailsWith<StaleCampaignState>("replay") { campaigns.save(dispatched()) }
            assertFailsWith<StaleCampaignState>("skip") { campaigns.save(final) }

            val view = Views(store).ledger(contract.workId)
            assertEquals(listOf("I1"), view.increments.map { it.key })
            assertEquals(listOf("R1"), view.entries.map { it.key })
            assertTrue(view.entries.single().body.toString().contains("Verified"))
            assertTrue(view.sizing.single().body.toString().contains("\"turns\":1"), view.sizing.toString())
        }
        val memory = InMemoryCampaigns()
        memory.save(opened())
        assertFailsWith<StaleCampaignState> { memory.save(opened()) }
        memory.save(running())
        assertEquals(running(), memory.load(contract.workId, contract.attemptId))
    }
}
