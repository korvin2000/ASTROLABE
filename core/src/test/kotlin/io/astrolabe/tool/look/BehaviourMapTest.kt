package io.astrolabe.tool.look

import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.kb.Behaviour
import io.astrolabe.kb.BehaviourMaps
import io.astrolabe.kb.BmapLocator
import io.astrolabe.kb.BmapOrigin
import io.astrolabe.kb.BmapStore
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.LocatorStatus
import io.astrolabe.kb.NoteStatus
import io.astrolabe.os.search.Searches
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.3.2: `BMAP` notes and `look(bmap)` — stale locators are `unresolved`; a map never replaces a current read. */
class BehaviourMapTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var look: Look
    private val workset = Workset()
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val estimator = HeuristicEstimator()
    private val idGen = FixedIdGen()

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/payments/refund.py", "def refund(amount):\n    return post(amount)\n\n\ndef _round(x):\n    return x\n")
        repo.write("src/payments/ledger.py", "def post(amount):\n    return amount\n")
        repo.write("tests/test_refund.py", "from src.payments.refund import refund\n\n\ndef test_refund():\n    assert refund(1) == 1\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        look = Look(
            workspace, VersionRegistry(workspace), workset, Atlas.build(repo.root), Searches.jvm(), Journal(store, clock),
            SqliteObservations(store, clock), SqliteAliases(store, clock), store.blobs, Redaction(), estimator, idGen, ids, bmaps = BmapStore(store),
        )
    }

    @AfterTest
    fun tearDown() {
        store.close()
        repo.close()
    }

    private suspend fun look(json: String): ToolOutcome =
        look.execute((ToolCalls.parse(listOf(ProviderCall("c1", "look", json))) as ParsedCalls.Valid).calls.single(), TurnContext(1, workset.snapshot(), Reservations(Tokens(100_000))))

    @Test
    fun `stale locators are shown unresolved and a map never replaces a current read`() = runTest {
        val map = BehaviourMaps.fromIndex(look.atlas, "payments", "src/payments")
        assertEquals(listOf("ledger", "refund"), map.behaviours.map { it.name })
        val refund = map.behaviours.single { it.name == "refund" }
        assertEquals(listOf("refund"), refund.entryPoints.map { it.symbol }, "private helpers are not entry points")
        assertEquals(refund.entryPoints.single(), BmapLocator.parse(refund.entryPoints.single().text))

        val writer = KbWriter(store, estimator, clock)
        writer.write(BmapStore(store).candidate(map, "payments: refund and ledger entry points", ids), ids)
        assertEquals("not_found", look("""{"what":"bmap","target":"payments"}""").header!!.runtime.status, "a candidate map is not served")
        writer.setStatus(map.noteId, NoteStatus.Admitted, ids)

        val overview = look("""{"what":"bmap","target":"payments"}""")
        assertEquals("ok", overview.header!!.runtime.status)
        assertTrue("src/payments/refund.py::refund@" in overview.body && "0 unresolved" in overview.body, overview.body)
        assertTrue(BehaviourMaps.NOT_SOURCE in overview.body)
        assertTrue(overview.tokens <= BehaviourMaps.SUBSYSTEM_MAX_TOKENS, "look(bmap, subsystem) is a few hundred tokens: ${overview.tokens}")

        // The symbol moves out of its file: the locator is unresolved at the next atlas, the untouched file stays current.
        repo.write("src/payments/refund.py", "def issue_refund(amount):\n    return post(amount)\n")
        look.atlas = Atlas.build(repo.root)
        val detail = look("""{"what":"bmap","target":"payments#refund"}""")
        assertTrue("src/payments/refund.py::refund@${refund.entryPoints.single().hash} unresolved" in detail.body, detail.body)
        val validation = BehaviourMaps.validate(map, look.atlas)
        assertEquals(LocatorStatus.Current, validation.status.getValue(map.behaviours.single { it.name == "ledger" }.entryPoints.single()))
        assertEquals(LocatorStatus.Changed, validation.status.getValue(refund.implementation.single()))

        // No map result grants coverage: an edit still needs a current read.
        assertEquals(emptyMap(), detail.header!!.versions)
        assertTrue(workset.entries.isEmpty(), "a map line is not a read")
        val read = look("""{"what":"read","target":"src/payments/refund.py"}""")
        assertEquals("ok", read.header!!.runtime.status)
        assertFalse(workset.entries.isEmpty())

        // A worker observation is folded only when every locator resolves; the excerpt stays within the [R] cap.
        val hash = look.atlas.row("src/payments/refund.py")!!.hash8
        assertNull(BehaviourMaps.fold(map, Behaviour("refund", listOf(BmapLocator("src/payments/refund.py", hash, "refund"))), look.atlas))
        val folded = assertNotNull(BehaviourMaps.fold(map, Behaviour("refund", listOf(BmapLocator("src/payments/refund.py", hash, "issue_refund")), stateWritten = listOf("refunds")), look.atlas))
        assertEquals(2, folded.version)
        assertEquals(BmapOrigin.Observations, folded.origin)
        val excerpt = BehaviourMaps.excerpt(BehaviourMaps.validate(folded, look.atlas), estimator)
        assertTrue("issue_refund" in excerpt && estimator.estimate(excerpt).upperBoundTokens <= BehaviourMaps.EXCERPT_MAX_TOKENS, excerpt)
    }
}
