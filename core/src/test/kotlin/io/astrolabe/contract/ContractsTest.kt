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
import io.astrolabe.event.ContractView
import io.astrolabe.event.Export
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.event.Views
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.store.StoreError
import io.astrolabe.store.openStore
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ContractsTest {
    @TempDir
    lateinit var root: Path

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
    fun `a resolution for a different proposal cannot resolve the pending amendment`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
            contracts.open(contract())
            val amendment = contracts.propose(work, null, "add retry coverage", "coverage missing", weakening = false)
            val before = contracts.current(work)
            var applied = false
            val authority = resolving { proposal ->
                Resolution("other-proposal", proposal.contractRevision, outcome, "human:alice")
            }

            val result = contracts.resolve(work, amendment.id, authority) { applied = true; it }

            assertEquals(before, result, outcome.name)
            assertEquals(before, contracts.current(work), outcome.name)
            assertFalse(applied, outcome.name)
            assertTrue(contracts.resolved().isEmpty(), outcome.name)
        }
    }

    @Test
    fun `a resolution for a different revision cannot resolve the pending amendment`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
            contracts.open(contract())
            val amendment = contracts.propose(work, null, "add retry coverage", "coverage missing", weakening = false)
            val before = contracts.current(work)
            var applied = false
            val authority = resolving { proposal ->
                Resolution(proposal.id, proposal.contractRevision + 1, outcome, "human:alice")
            }

            val result = contracts.resolve(work, amendment.id, authority) { applied = true; it }

            assertEquals(before, result, outcome.name)
            assertEquals(before, contracts.current(work), outcome.name)
            assertFalse(applied, outcome.name)
            assertTrue(contracts.resolved().isEmpty(), outcome.name)
        }
    }

    @Test
    fun `accepted reply preserves proposals and strengthening added while authority was suspended`() = runTest {
        val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contract())
        val amendment = contracts.propose(work, null, "add retry coverage", "coverage missing", weakening = false)
        val requested = CompletableDeferred<AmendmentProposal>()
        val reply = CompletableDeferred<Resolution>()
        var appliedTo: Contract? = null
        val pending = async {
            contracts.resolve(work, amendment.id, resolving { requested.complete(it); reply.await() }) {
                appliedTo = it
                it.copy(exclusions = it.exclusions + "billing UI")
            }
        }
        val proposal = requested.await()
        val second = contracts.propose(work, null, "add timeout coverage", "timeout path uncovered", weakening = false)
        val strengthening = Acceptance.Run("AC-4", Command(listOf("pytest", "-k", "idempot")), Origin.Model("R1"))
        val latest = contracts.strengthen(work, strengthening)

        reply.complete(Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Accepted, "human:alice"))
        val result = pending.await()

        assertEquals(latest, appliedTo)
        assertEquals(2, result.version)
        assertEquals(listOf(second), result.amendmentsPending)
        assertEquals(strengthening, result.acceptance("AC-4"))
        assertEquals(latest.exclusions + "billing UI", result.exclusions)
        assertEquals(result, contracts.current(work))
        assertEquals(AmendmentStatus.Accepted, contracts.resolved().single().status)
    }

    @Test
    fun `rejected reply preserves proposals and strengthening added while authority was suspended`() = runTest {
        val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contract())
        val amendment = contracts.propose(work, null, "narrow retry coverage", "slow suite", weakening = true)
        val requested = CompletableDeferred<AmendmentProposal>()
        val reply = CompletableDeferred<Resolution>()
        val pending = async {
            contracts.resolve(work, amendment.id, resolving { requested.complete(it); reply.await() }) {
                error("a rejected proposal must not apply")
            }
        }
        val proposal = requested.await()
        val second = contracts.propose(work, null, "add timeout coverage", "timeout path uncovered", weakening = false)
        val strengthening = Acceptance.Run("AC-4", Command(listOf("pytest", "-k", "idempot")), Origin.Model("R1"))
        val latest = contracts.strengthen(work, strengthening)

        reply.complete(Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Rejected, "human:alice"))
        val result = pending.await()

        assertEquals(latest.copy(amendmentsPending = listOf(second)), result)
        assertEquals(result, contracts.current(work))
        assertEquals(AmendmentStatus.Rejected, contracts.resolved().single().status)
    }

    @Test
    fun `pending reply returns the latest contract after same revision changes`() = runTest {
        val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contract())
        val amendment = contracts.propose(work, null, "add retry coverage", "coverage missing", weakening = false)
        val requested = CompletableDeferred<AmendmentProposal>()
        val reply = CompletableDeferred<Resolution>()
        val pending = async {
            contracts.resolve(work, amendment.id, resolving { requested.complete(it); reply.await() }) {
                error("a pending proposal must not apply")
            }
        }
        val proposal = requested.await()
        val latest = contracts.strengthen(work, Acceptance.Run("AC-4", Command(listOf("pytest", "-k", "idempot")), Origin.Model("R1")))

        reply.complete(Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Pending, "human:alice"))

        assertEquals(latest, pending.await())
        assertEquals(latest, contracts.current(work))
        assertTrue(contracts.resolved().isEmpty())
    }

    @Test
    fun `a user amendment supersedes an outstanding resolution without applying or recording it`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
            contracts.open(contract())
            val amendment = contracts.propose(work, null, "add retry coverage", "coverage missing", weakening = false)
            val requested = CompletableDeferred<AmendmentProposal>()
            val reply = CompletableDeferred<Resolution>()
            var applied = false
            val pending = async {
                contracts.resolve(work, amendment.id, resolving { requested.complete(it); reply.await() }) {
                    applied = true
                    it
                }
            }
            val proposal = requested.await()
            val latest = contracts.amendByUser(work, "Also cover refunds.")

            reply.complete(Resolution(proposal.id, proposal.contractRevision, outcome, "human:alice"))

            assertEquals(latest, pending.await(), outcome.name)
            assertEquals(latest, contracts.current(work), outcome.name)
            assertFalse(applied, outcome.name)
            assertTrue(contracts.resolved().isEmpty(), outcome.name)
        }
    }

    @Test
    fun `a late reply cannot resolve an amendment already removed from the pending list`() = runTest {
        val contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contract())
        val amendment = contracts.propose(work, null, "narrow retry coverage", "slow suite", weakening = true)
        val requested = CompletableDeferred<AmendmentProposal>()
        val reply = CompletableDeferred<Resolution>()
        var applied = false
        val pending = async {
            contracts.resolve(work, amendment.id, resolving { requested.complete(it); reply.await() }) {
                applied = true
                it
            }
        }
        val proposal = requested.await()
        val latest = contracts.resolve(work, amendment.id, AutonomousAuthority()) { error("rejected") }
        val resolved = contracts.resolved()

        reply.complete(Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Accepted, "human:alice"))

        assertEquals(latest, pending.await())
        assertEquals(latest, contracts.current(work))
        assertFalse(applied)
        assertEquals(resolved, contracts.resolved())
    }

    private fun resolving(resolve: suspend (AmendmentProposal) -> Resolution): Authority = object : Authority by AutonomousAuthority() {
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = resolve.invoke(proposal)
    }

    @Test
    fun `accepted and rejected resolutions and provenance survive reopening the store`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val path = root.resolve(outcome.name)
            lateinit var expected: Amendment
            lateinit var expectedContract: Contract
            openStore(path, clock).use { store ->
                val contracts = Contracts(SqliteContractRepository(store, clock), FixedIdGen(), clock)
                contracts.open(contract())
                val proposal = contracts.propose(work, null, "exclude billing UI", "out of scope", weakening = false)
                expected = proposal.copy(status = AmendmentStatus.valueOf(outcome.name), resolvedBy = "human:alice")
                expectedContract = contracts.resolve(work, proposal.id, resolving {
                    Resolution(it.id, it.contractRevision, outcome, "human:alice")
                }) { it.copy(exclusions = it.exclusions + "billing UI") }
                assertEquals(listOf(expected), contracts.resolved())
            }

            openStore(path, clock).use { store ->
                val reopened = Contracts(SqliteContractRepository(store, clock), FixedIdGen(), clock)
                assertEquals(expectedContract, reopened.current(work), outcome.name)
                assertEquals(if (outcome == ResolutionOutcome.Accepted) 2 else 1, reopened.history(work).size)
                assertEquals(listOf(expected), reopened.resolved(), outcome.name)
            }
        }
    }

    @Test
    fun `amendment projections and exports carry final status and authority after resolution`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val path = root.resolve(outcome.name)
            lateinit var expected: Amendment
            openStore(path, clock).use { store ->
                val contracts = Contracts(SqliteContractRepository(store, clock), FixedIdGen(), clock)
                contracts.open(contract())
                val proposal = contracts.propose(work, null, "exclude billing UI", "out of scope", weakening = false)
                expected = proposal.copy(status = AmendmentStatus.valueOf(outcome.name), resolvedBy = "human:alice")
                contracts.resolve(work, proposal.id, resolving {
                    Resolution(it.id, it.contractRevision, outcome, "human:alice")
                }) { it.copy(exclusions = it.exclusions + "billing UI") }
            }

            openStore(path, clock).use { store ->
                assertEquals(listOf(outcome.name), store.db.query("SELECT status FROM amendments WHERE work_id = ?", work) {
                    it.string("status")
                })
                val view = Views(store).contract(work)
                assertEquals(expected, Json.decodeFromJsonElement(Amendment.serializer(), view.amendments.single().body))
                Export.write(Views(store), work, store.layout.exports)
                val exported = Json.decodeFromString(ContractView.serializer(), Files.readString(store.layout.exports.resolve("contract.json")))
                assertEquals(expected, Json.decodeFromJsonElement(Amendment.serializer(), exported.amendments.single().body))
            }
        }
    }

    @Test
    fun `failure persisting final amendment status rolls back contract and resolution together`() = runTest {
        for (outcome in listOf(ResolutionOutcome.Accepted, ResolutionOutcome.Rejected)) {
            val path = root.resolve(outcome.name)
            lateinit var before: Contract
            openStore(path, clock).use { store ->
                val contracts = Contracts(SqliteContractRepository(store, clock), FixedIdGen(), clock)
                contracts.open(contract())
                val proposal = contracts.propose(work, null, "exclude billing UI", "out of scope", weakening = false)
                before = contracts.current(work)!!
                val beforeView = Views(store).contract(work)
                store.db.tx { tx ->
                    for (operation in listOf("INSERT", "UPDATE")) {
                        tx.execute(
                            "CREATE TRIGGER fail_resolution_${operation.lowercase()} BEFORE $operation ON amendments " +
                                "WHEN NEW.status != 'Pending' BEGIN SELECT RAISE(ABORT, 'injected resolution failure'); END",
                        )
                    }
                }

                assertFailsWith<StoreError> {
                    contracts.resolve(work, proposal.id, resolving {
                        Resolution(it.id, it.contractRevision, outcome, "human:alice")
                    }) { it.copy(exclusions = it.exclusions + "billing UI") }
                }

                assertEquals(before, contracts.current(work), outcome.name)
                assertEquals(listOf(before), contracts.history(work), outcome.name)
                assertEquals(beforeView, Views(store).contract(work), outcome.name)
                assertTrue(contracts.resolved().isEmpty(), outcome.name)
            }

            openStore(path, clock).use { store ->
                val reopened = Contracts(SqliteContractRepository(store, clock), FixedIdGen(), clock)
                assertEquals(before, reopened.current(work), outcome.name)
                assertTrue(reopened.resolved().isEmpty(), outcome.name)
            }
        }
    }

    @Test
    fun `resolved amendments belong to the repository across Contracts instances`() = runTest {
        val repository = InMemoryContractRepository()
        val contracts = Contracts(repository, FixedIdGen(), clock)
        contracts.open(contract())
        val proposal = contracts.propose(work, null, "narrow retry coverage", "slow suite", weakening = true)
        contracts.resolve(work, proposal.id, AutonomousAuthority()) { error("rejected") }

        val reopened = Contracts(repository, FixedIdGen(), clock)
        assertEquals(contracts.resolved(), reopened.resolved())
        assertEquals(AmendmentStatus.Rejected, reopened.resolved().single().status)
    }

    @Test
    fun `ledger starts pending for every requirement`() {
        val ledger = Ledger.initial(contract())
        assertEquals(listOf("R1"), ledger.unfinished())
        assertEquals(RequirementStatus.Pending, ledger["R1"]!!.status)
    }
}
