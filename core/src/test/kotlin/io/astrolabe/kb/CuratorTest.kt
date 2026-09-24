package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.1.1: queue, lint, the D7 admission policy, batches that roll back independently of code (FX-36). */
class CuratorTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val ids = Identities(WorkId("W-42"), AttemptId("a1"), context = ContextId("cell-1"))
    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var curator: Curator
    private lateinit var writer: KbWriter
    private val notes get() = Notes(store)

    @BeforeTest
    fun open() {
        repo = TempRepo.create()
        repo.write("src/pay/api.py", "def charge(): pass\n")
        repo.write("src/pay/refund.py", "def refund(): pass\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        writer = KbWriter(store, HeuristicEstimator(), clock)
        val resolver = KbResolver.of({ Files.exists(repo.root.resolve(it)) }, { it.startsWith("#") })
        curator = Curator(store, HeuristicEstimator(), idGen, clock, resolver)
    }

    @AfterTest
    fun close() {
        store.close()
        repo.close()
    }

    private fun pit(id: String, scope: String = "subsystem:pay", confidence: Double = 0.9, body: String = "Failed when the retry ran on the wall clock; passes with a frozen clock.", evidence: List<String> = listOf("#7")) =
        Note(id, NoteKind.PIT, NoteStatus.Candidate, "refund retries double-charge on the wall clock", body, scope, listOf(NoteAnchor("src/pay/refund.py")), confidence = confidence, basis = NoteBasis(evidenceRefs = evidence))

    @Test
    fun `FX-36 - one failed use stays a scoped conditional PIT candidate, its global generalization is refused, the policy admits only at low confidence`() {
        curator.queue.enqueue(pit("PIT-retry"), ids)
        curator.queue.enqueue(pit("PIT-retry-global", scope = "global"), ids)
        curator.queue.enqueue(pit("PIT-retry-ban", body = "Never retry refunds.", evidence = listOf("#7")), ids)
        curator.queue.enqueue(pit("PIT-retry-low", confidence = 0.5), ids)

        val batch = curator.admit(ids, AdmissionMode.Autonomous)

        assertEquals(listOf("PIT-retry-low"), batch.admitted)
        assertEquals(setOf("PIT-retry-global", "PIT-retry-ban"), batch.rejected.keys)
        assertTrue(batch.rejected.getValue("PIT-retry-global").any { it.rule == LintRule.OneOffGeneralization }, batch.rejected.toString())
        assertTrue(batch.rejected.getValue("PIT-retry-ban").any { it.rule == LintRule.OneOffGeneralization }, batch.rejected.toString())
        assertTrue(batch.waiting.getValue("PIT-retry").contains("confidence 0.9"), batch.waiting.toString())
        // The candidate waits as it was proposed: scoped, conditional, never rewritten and never a ban.
        val waiting = notes.get("PIT-retry")!!
        assertEquals(NoteStatus.Candidate, waiting.status)
        assertEquals(pit("PIT-retry"), waiting)
        assertEquals(QueueStatus.Queued, curator.queue.all().first { it.noteId == "PIT-retry" }.status)
        val admitted = notes.get("PIT-retry-low")!!
        assertEquals(NoteStatus.Admitted, admitted.status)
        assertEquals(AdmissionPolicy.POLICY, admitted.origin.admittedBy)
        assertNull(admitted.signedBy)
        assertEquals(batch.id, curator.queue.all().first { it.noteId == "PIT-retry-low" }.batch)
    }

    @Test
    fun `an ADR is never auto-admitted and is signed only through the authority`() = runBlocking {
        val adr = Note("ADR-cents", NoteKind.ADR, NoteStatus.Candidate, "money is integer cents", "Decided with finance.", "subsystem:pay", listOf(NoteAnchor("src/pay/api.py")), confidence = 0.4, basis = NoteBasis(evidenceRefs = listOf("#1", "#2")))
        curator.queue.enqueue(adr, ids)
        val autonomous = curator.admit(ids, AdmissionMode.Autonomous)
        assertTrue(autonomous.admitted.isEmpty() && "ADR-cents" in autonomous.waiting, autonomous.toString())

        val pending = curator.admitWith(ids, AutonomousAuthority(), contractRevision = 1)
        assertTrue(pending.admitted.isEmpty() && "ADR-cents" in pending.waiting, pending.toString())
        assertEquals(NoteStatus.Candidate, notes.get("ADR-cents")!!.status)

        val signed = curator.admitWith(ids, accepting("user:alice"), contractRevision = 1)
        assertEquals(listOf("ADR-cents"), signed.admitted)
        val note = notes.get("ADR-cents")!!
        assertEquals(NoteStatus.Admitted, note.status)
        assertEquals("user:alice", note.signedBy)
        assertEquals("user:alice", note.origin.admittedBy)
    }

    @Test
    fun `contradiction lint - a flipped negation or a second contract for a governed symbol is rejected`() {
        writer.write(Note("LES-clock", NoteKind.LES, NoteStatus.Admitted, "refund retries need a frozen clock", "Retries depend on time.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        writer.write(Note("CON-charge", NoteKind.CON, NoteStatus.Admitted, "charge takes integer cents", "Callers pass cents.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py", symbol = "charge")), basis = NoteBasis(evidenceRefs = listOf("#3"))), ids)
        curator.queue.enqueue(Note("LES-clock-2", NoteKind.LES, NoteStatus.Candidate, "refund retries do not need a frozen clock", "Seen twice.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), basis = NoteBasis(evidenceRefs = listOf("#4", "#5"))), ids)
        curator.queue.enqueue(Note("CON-charge-2", NoteKind.CON, NoteStatus.Candidate, "charge takes a float amount", "Callers pass floats.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py", symbol = "charge")), basis = NoteBasis(evidenceRefs = listOf("#6"))), ids)
        curator.queue.enqueue(Note("CON-charge-3", NoteKind.CON, NoteStatus.Candidate, "charge takes integer cents and a currency", "Callers pass cents and a code.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py", symbol = "charge")), basis = NoteBasis(evidenceRefs = listOf("#6")), supersedes = "CON-charge"), ids)

        val batch = curator.admit(ids, AdmissionMode.Interactive)

        assertEquals(setOf("LES-clock-2", "CON-charge-2"), batch.rejected.keys)
        assertEquals("contradicts LES-clock: 'refund retries need a frozen clock'", batch.rejected.getValue("LES-clock-2").single { it.rule == LintRule.Contradiction }.detail)
        assertTrue(batch.rejected.getValue("CON-charge-2").single { it.rule == LintRule.Contradiction }.detail.contains("src/pay/api.py#charge governed by CON-charge"))
        assertEquals(mapOf("CON-charge-3" to "queued for the user"), batch.waiting)
        assertEquals(NoteStatus.Rejected, notes.get("LES-clock-2")!!.status)
        assertEquals(NoteStatus.Admitted, notes.get("LES-clock")!!.status)
    }

    @Test
    fun `a batch rolls back independently of code - notes return to the queue, the tree and the index follow`() {
        curator.queue.enqueue(pit("PIT-a", confidence = 0.5), ids)
        curator.queue.enqueue(Note("LES-b", NoteKind.LES, NoteStatus.Candidate, "freeze the clock in refund tests", "Retries depend on time.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), confidence = 0.6, basis = NoteBasis(evidenceRefs = listOf("#8", "#9"))), ids)
        val tree = snapshot()
        val batch = curator.admit(ids, AdmissionMode.Autonomous)
        assertEquals(listOf("PIT-a", "LES-b"), batch.admitted)
        assertTrue("subsystem-pay.md" in batch.indexWritten, batch.indexWritten.toString())
        val index = store.layout.kb.resolve("index").resolve("subsystem-pay.md")
        assertTrue(Files.readString(index).contains("- LES-b: freeze the clock"))

        val rolled = curator.rollback(batch.id, ids)

        assertEquals(listOf("PIT-a", "LES-b"), rolled)
        assertEquals(tree, snapshot(), "a memory rollback never touches the tree")
        for (id in rolled) {
            val note = notes.get(id)!!
            assertEquals(NoteStatus.Candidate, note.status)
            assertNull(note.origin.admittedBy)
            val entry = curator.queue.all().first { it.noteId == id }
            assertEquals(QueueStatus.Queued, entry.status)
            assertEquals(batch.id, entry.rolledBackFrom)
            assertNull(entry.batch)
        }
        assertEquals(3, notes.revisions("PIT-a").size, "candidate, admitted, rolled back: every step is a revision")
        assertTrue(!Files.exists(index) || !Files.readString(index).contains("LES-b"), "the index is regenerated from the notes")
        assertEquals(listOf("PIT-a", "LES-b"), curator.queue.pending().map { it.noteId }, "the batch is admissible again")
    }

    private fun snapshot(): Map<String, String> = Files.walk(repo.root).use { paths ->
        paths.filter { Files.isRegularFile(it) && ".git" !in it.relativeTo(repo.root).toString() }
            .toList().associate { it.relativeTo(repo.root).toString().replace('\\', '/') to Files.readString(it) }
    }

    private fun accepting(by: String): Authority = object : Authority {
        override suspend fun ask(question: Question): Answer? = null
        override suspend fun approve(request: DClassRequest): Decision = throw UnsupportedOperationException("unused")
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = Resolution(proposal.id, proposal.contractRevision, ResolutionOutcome.Accepted, by, "reviewed")
        override suspend fun review(request: ReviewRequest): Verdict? = null
    }
}
