package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2.6.2: `kb.search` / `kb.get` over the store — admitted only, stale labelled, degraded never blocked (FX-46). */
class StoreKbTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val work = WorkId("W-42")
    private val ids = Identities(work, AttemptId("a1"), context = ContextId("cell-1"))
    private val api = FileVersion(Digest.ofUtf8("api v2"))
    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private val logs = ArrayList<KbSearchLog>()

    @BeforeTest
    fun open() {
        repo = TempRepo.create()
        repo.write("a.txt", "a")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        val writer = KbWriter(store, HeuristicEstimator(), clock)
        writer.write(Note("CON-7", NoteKind.CON, NoteStatus.Admitted, "payments API returns integer cents", "Amounts are integer cents.", "src/payments/**", listOf(NoteAnchor("src/payments/api.py", api.digest.hex.take(8)))), ids)
        writer.write(Note("PIT-3", NoteKind.PIT, NoteStatus.Admitted, "float rounding breaks refunds", "Seen when amounts are floats.", "subsystem:payments", listOf(NoteAnchor("src/refunds.py", "deadbeef"))), ids)
        writer.write(Note("LES-5", NoteKind.LES, NoteStatus.Candidate, "refund retries need a frozen clock", "Queued, not admitted.", "subsystem:payments"), ids)
        writer.write(Note("STATUS-W-9", NoteKind.STATUS, NoteStatus.Admitted, "checkpoint of another task about refunds", "b", "task:W-9"), ids)
    }

    @AfterTest
    fun close() {
        store.close()
        repo.close()
    }

    private fun kb() = StoreKb(store, work, { path -> if (path == "src/payments/api.py") api else FileVersion(Digest.ofUtf8("other")) }, onSearch = logs::add)

    @Test
    fun `search answers admitted notes by summary and anchor, labels moved anchors and logs why`() {
        val hits = kb().search("refunds cents", null, null, "need the money representation")
        assertEquals(listOf("CON-7", "PIT-3"), hits.hits.map { it.id }.sorted(), "candidates and other tasks' STATUS never answer")
        assertEquals(mapOf("CON-7" to false, "PIT-3" to true), hits.hits.associate { it.id to it.stale }, "a moved anchor is stale")
        assertTrue(hits.complete)
        assertNull(hits.degradation)
        assertEquals(listOf("PIT-3"), kb().search("api.py refunds", setOf("PIT"), "subsystem:payments", "scoped").hits.map { it.id })
        assertEquals(listOf("need the money representation", "scoped"), logs.map { it.why })

        assertTrue(kb().get("CON-7")!!.text.contains("summary: payments API returns integer cents"))
        assertNull(kb().get("LES-5"), "the queue is not the knowledge base")
        assertNull(kb().get("STATUS-W-9"))
        assertEquals(emptyList(), kb().search("-- ** --", null, null, "punctuation only").hits)
    }

    @Test
    fun `a cold full-text index degrades to a direct scan and says so (FX-46)`() {
        store.db.tx { it.execute("DELETE FROM notes_fts") }
        val hits = kb().search("cents", null, null, "index lost")
        assertEquals(listOf("CON-7"), hits.hits.map { it.id })
        assertTrue(hits.complete, "the direct scan covered every note")
        assertTrue(hits.degradation!!.startsWith("full-text index unavailable"), hits.degradation)
    }
}
