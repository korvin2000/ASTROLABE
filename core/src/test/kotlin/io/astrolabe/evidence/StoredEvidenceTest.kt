package io.astrolabe.evidence

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.Views
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoredEvidenceTest {
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private lateinit var repo: TempRepo
    private lateinit var stateRoot: Path
    private lateinit var store: Store
    private val work = WorkId("W-1")
    private val ids = Identities(work, AttemptId("a1"), context = ContextId("cell-1"))

    @BeforeEach
    fun open() {
        repo = TempRepo.create()
        repo.write("a.txt", "a")
        repo.commit("initial")
        stateRoot = Files.createTempDirectory("astrolabe-state")
        store = Store.open(stateRoot, repo.git, clock)
    }

    @AfterEach
    fun close() {
        store.close()
        repo.close()
        stateRoot.toFile().deleteRecursively()
    }

    private fun reopen() {
        store.close()
        store = Store.open(stateRoot, repo.git, clock)
    }

    @Test
    fun `journal is append-only, sequenced per work and searchable with scope completeness`() {
        val journal = Journal(store, clock)
        val a = journal.append(JournalEvent("ev-1", ids, 1, JournalKind.Call, text = "look read src/router.py", at = clock.instant()))
        val b = journal.append(JournalEvent("ev-2", ids, 1, JournalKind.Result, text = "result #1 router.py 80-96", refs = listOf("#1"), at = clock.instant()))
        val other = journal.append(JournalEvent("ev-3", Identities(WorkId("W-2"), AttemptId("a1")), null, JournalKind.Boundary, at = clock.instant()))
        assertEquals(listOf(1L, 2L, 1L), listOf(a.seq, b.seq, other.seq))
        reopen()
        val reopened = Journal(store, clock)
        assertEquals(2L, reopened.lastSeq(work))
        assertEquals(listOf("ev-1", "ev-2"), reopened.events(JournalScope(work)).map { it.eventId })
        val hits = reopened.search("router", JournalScope(work))
        assertEquals(2, hits.events.size)
        assertTrue(hits.complete)
        val limited = reopened.search("router", JournalScope(work), limit = 1)
        assertEquals(1, limited.events.size)
        assertTrue(!limited.complete)
        assertEquals(0, reopened.search("router", JournalScope(work, kinds = setOf(JournalKind.Nudge))).events.size)
        assertEquals("ev-2", reopened.search("#1", JournalScope(work)).events.single().eventId)
    }

    @Test
    fun `intents survive a reopen and open ones stay unknown until reconciled`() {
        val intents = SqliteIntentJournal(store, clock)
        val intent = Intent("int-1", ids, "act-1", listOf("pip", "install", "x"), null, "installs", at = clock.instant())
        intents.record(intent)
        intents.update("int-1", IntentStatus.Dispatched)
        reopen()
        val after = SqliteIntentJournal(store, clock)
        assertEquals(listOf("int-1"), after.open().map { it.intentId })
        assertEquals(IntentStatus.Dispatched, after.get("int-1")!!.status)
        after.update("int-1", IntentStatus.Committed)
        assertTrue(after.open().isEmpty())
    }

    @Test
    fun `aliases are campaign-global, monotone and never recycled across reopen`() {
        val aliases = SqliteAliases(store, clock)
        assertEquals(1, aliases.allocate(work, "journal-1", "result", ContextId("cell-1"), null).number)
        assertEquals(2, aliases.allocate(work, "journal-2", "result", ContextId("cell-1"), null).number)
        reopen()
        val again = SqliteAliases(store, clock)
        assertEquals(3, again.allocate(work, "journal-3", "result", ContextId("cell-2"), null).number)
        assertEquals("journal-2", again.resolve(work, 2)!!.canonicalId)
        assertEquals(3, again.byCanonical(work, "journal-3")!!.number)
        assertNull(again.resolve(work, 9))
        assertEquals(1, again.allocate(WorkId("W-2"), "j", "result", null, null).number)
    }

    @Test
    fun `parallel children allocate distinct campaign-global aliases (IX-06)`() {
        val aliases = SqliteAliases(store, clock)
        val children = (1..4).map { c ->
            Thread { repeat(25) { n -> aliases.allocate(work, "child-$c-$n", "result", ContextId("child-$c"), null) } }
        }
        children.forEach(Thread::start)
        children.forEach(Thread::join)
        val all = (1..100).map { assertNotNull(aliases.resolve(work, it)) }
        assertNull(aliases.resolve(work, 101))
        assertEquals(100, all.map { it.canonicalId }.toSet().size, "no number designates two artifacts")
        all.forEach { assertEquals(it.context!!.value, it.canonicalId.substringBeforeLast('-'), "provenance stays with the producing child") }
    }

    @Test
    fun `contracts persist versions and the projection tables feed the contract view`() {
        val repository = SqliteContractRepository(store, clock)
        val contracts = Contracts(repository, FixedIdGen(), clock)
        contracts.open(contract())
        contracts.strengthen(work, Acceptance.Run("AC-2", Command(listOf("pytest", "-k", "x")), Origin.Model("R1")))
        val proposal = contracts.propose(work, ContextId("cell-1"), "narrow AC-1", "slow", weakening = true)
        contracts.amendByUser(work, "Also cover the retry path.") { c ->
            c.copy(requirements = c.requirements + Requirement("R2", "retry covered", listOf("AC-1"), authorityRef = "U-1"))
        }
        reopen()
        val after = SqliteContractRepository(store, clock)
        val history = after.history(work)
        assertEquals(listOf(1, 2), history.map { it.version })
        assertEquals(2, history.last().acceptance.size)
        assertEquals(listOf(proposal.id), history.last().amendmentsPending.map { it.id })
        val view = Views(store).contract(work)
        assertEquals(2L, view.latestVersion)
        assertEquals(setOf("U1", "U-1"), view.requests.map { it.key }.toSet())
        assertEquals(listOf("R1", "R2"), view.requirements.map { it.key })
        assertEquals(listOf("AC-1", "AC-2"), view.acceptance.map { it.key })
        assertEquals(listOf("C1"), view.constraints.map { it.key })
        assertNotNull(view.amendments.singleOrNull())
    }

    private fun contract() = Contract(
        workId = work,
        version = 1,
        attemptId = AttemptId("a1"),
        mode = Mode.Autonomous,
        shape = Shape.S0,
        requests = listOf(UserRequest("U1", clock.instant(), "Add idempotency-key handling.")),
        requirements = listOf(Requirement("R1", "key stored", listOf("AC-1"), authorityRef = "U1")),
        acceptance = listOf(Acceptance.Run("AC-1", Command(listOf("pytest", "-q")), Origin.Harness, scope = "touched")),
        constraints = listOf(io.astrolabe.contract.Constraint("C1", "no refund flow changes", "user")),
        exclusions = emptyList(),
        contractsTouched = emptyList(),
        scope = Scope(listOf("src/"), listOf(".git/")),
        budget = Budget.of(Defaults(), Tokens(1_000_000)),
        authorization = Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
    )
}
