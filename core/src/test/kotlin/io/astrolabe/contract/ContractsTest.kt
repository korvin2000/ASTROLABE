package io.astrolabe.contract

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContractsTest {
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val work = WorkId("W-0042")

    private fun contract() = Contract(
        workId = work,
        version = 1,
        attemptId = AttemptId("a1"),
        mode = Mode.Autonomous,
        shape = Shape.S0,
        requests = listOf(UserRequest("U1", clock.instant(), "Add idempotency-key handling to POST /payments; public API unchanged.")),
        requirements = listOf(Requirement("R1", "idempotency key stored and checked per merchant", listOf("AC-1"), authorityRef = "U1")),
        acceptance = listOf(
            Acceptance.Run("AC-1", Command(listOf("pytest", "tests/payments", "-q")), Origin.User),
            Acceptance.Check("AC-2", "no public signature change in src/api/", Origin.User),
            Acceptance.Review("AC-3", "retry semantics cannot duplicate side effects", Origin.User),
        ),
        constraints = listOf(Constraint("C1", "do not change the refund flow", "user")),
        exclusions = listOf("refund flow"),
        contractsTouched = emptyList(),
        scope = Scope(listOf("src/pay/", "tests/payments/"), listOf("migrations/", ".github/")),
        budget = Budget.of(Defaults(), Tokens(2_500_000)),
        authorization = Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
        risk = Risk(2, Reversibility.Easy, contractTouch = false),
    )

    @Test
    fun `contract validates its references and round trips`() {
        val c = contract()
        val text = Json.encodeToString(Contract.serializer(), c)
        assertEquals(c, Json.decodeFromString(Contract.serializer(), text))
        assertTrue(text.contains("\"type\":\"run\""), text)
        assertFailsWith<IllegalArgumentException> { c.copy(requirements = listOf(Requirement("R9", "x", listOf("AC-99"), authorityRef = "U1"))) }
        assertFailsWith<IllegalArgumentException> { c.copy(acceptance = c.acceptance + c.acceptance.first()) }
        assertEquals("pytest tests/payments -q", (c.acceptance("AC-1") as Acceptance.Run).command.text)
    }

    @Test
    fun `the model can only add strengthening items never edit or remove acceptance (FX-15)`() {
        val c = contract()
        val strengthened = c.strengthen(Acceptance.Run("AC-4", Command(listOf("pytest", "-k", "idempot")), Origin.Model(strengthens = "R1")))
        assertEquals(4, strengthened.acceptance.size)
        assertEquals(1, strengthened.version)
        assertFailsWith<IllegalArgumentException> { c.strengthen(Acceptance.Run("AC-1", Command(listOf("true")), Origin.Model("R1"))) }
        assertFailsWith<IllegalArgumentException> { c.strengthen(Acceptance.Run("AC-5", Command(listOf("true")), Origin.User)) }
    }

    @Test
    fun `proposals stay pending, weakenings are rejected by policy, user amendments bump the version`() = runTest {
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock, events)
            contracts.open(contract())
            val proposal = contracts.propose(work, null, "AC-1 cmd → pytest -k 'not slow'", "slow suite needs a live DB", weakening = true)
            assertEquals(AmendmentStatus.Pending, proposal.status)
            assertEquals(1, contracts.current(work)!!.version)
            assertEquals(listOf(proposal), contracts.current(work)!!.amendmentsPending)

            val afterReject = contracts.resolve(work, proposal.id, AutonomousAuthority()) { it }
            assertEquals(1, afterReject.version, "a rejected weakening never bumps the version")
            assertTrue(afterReject.amendmentsPending.isEmpty())
            assertEquals(AmendmentStatus.Rejected, contracts.resolved().single().status)

            val amended = contracts.amendByUser(work, "Also cover the retry path.") { c ->
                c.copy(requirements = c.requirements + Requirement("R2", "retry path covered", listOf("AC-1"), authorityRef = "U-1"))
            }
            assertEquals(2, amended.version)
            assertEquals(2, amended.requests.size)
            assertEquals("Also cover the retry path.", amended.objective)
            assertEquals(listOf(contract(), amended), contracts.history(work).map { it.copy(amendmentsPending = emptyList()) })

            assertTrue(recorder.awaitCount(3))
            assertEquals(
                listOf("AmendmentProposed", "AmendmentResolved", "Amended"),
                recorder.events.map { it::class.simpleName },
            )
        }
    }

    @Test
    fun `an authority may accept a non-weakening proposal which then bumps the version with provenance`() = runTest {
        val accepting = object : Authority {
            override suspend fun ask(question: Question): Answer? = null
            override suspend fun approve(request: DClassRequest): Decision = Decision(request.id, request.contractRevision, false)
            override suspend fun resolve(proposal: AmendmentProposal): Resolution =
                Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Accepted, "human:alice")
            override suspend fun review(request: ReviewRequest): Verdict? = null
        }
        val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contract())
        val proposal = contracts.propose(work, null, "add AC-5 run: pytest -k retry", "retry path uncovered", weakening = false)
        val next = contracts.resolve(work, proposal.id, accepting) { c ->
            c.copy(acceptance = c.acceptance + Acceptance.Run("AC-5", Command(listOf("pytest", "-k", "retry")), Origin.Amended(c.version + 1), obligationVersion = c.version + 1))
        }
        assertEquals(2, next.version)
        assertNotNull(next.acceptance("AC-5"))
        assertEquals(AmendmentStatus.Accepted, contracts.resolved().single().status)
        assertEquals("human:alice", contracts.resolved().single().resolvedBy)
        assertEquals(2, contracts.history(work).size)
    }

    @Test
    fun `ledger starts pending for every requirement`() {
        val ledger = Ledger.initial(contract())
        assertEquals(listOf("R1"), ledger.unfinished())
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status)
    }
}
