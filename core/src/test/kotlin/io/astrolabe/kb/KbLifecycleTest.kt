package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.Roles
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import io.astrolabe.telemetry.KbHealth
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.1.2: invalidation with curator recheck (FX-35), usage counters, pruning, promotion, index regeneration, health. */
class KbLifecycleTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val work = WorkId("W-42")
    private val ids = Identities(work, AttemptId("a1"), context = ContextId("cell-1"))
    private val estimator = HeuristicEstimator()
    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var writer: KbWriter
    private lateinit var events: Events
    private lateinit var recorder: EventRecorder
    private lateinit var curator: Curator
    private val notes get() = Notes(store)
    private val usage get() = Usage(store, clock)

    @BeforeTest
    fun open() {
        repo = TempRepo.create()
        repo.write("src/pay/api.py", "def charge(): pass\n")
        repo.write("src/pay/refund.py", "def refund(): pass\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        writer = KbWriter(store, estimator, clock)
        events = Events(clock)
        recorder = EventRecorder().also(events::subscribe)
        curator = Curator(store, estimator, idGen, clock, KbResolver.of({ Files.exists(repo.root.resolve(it)) }, { true }), events = events)
    }

    @AfterTest
    fun close() {
        events.close()
        store.close()
        repo.close()
    }

    private fun con(id: String, summary: String, supersedes: String? = null) =
        Note(id, NoteKind.CON, NoteStatus.Admitted, summary, "Callers pass cents.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py", symbol = "charge")), basis = NoteBasis(evidenceRefs = listOf("#1")), supersedes = supersedes)

    private fun inputs() = InjectionInputs(Roles.implementing, work, listOf("src/pay/**"), currentVersion = null)

    @Test
    fun `FX-35 - a note referencing a superseded contract is flagged before injection and deprecated on recheck`() {
        writer.write(con("CON-7", "charge takes integer cents"), ids)
        writer.write(Note("LES-9", NoteKind.LES, NoteStatus.Admitted, "round to cents before calling charge", "Floats break it.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), basis = NoteBasis(evidenceRefs = listOf("#2", "#3")), validity = NoteValidity(dependsOn = listOf("CON-7@v1"))), ids)
        writer.write(Note("LES-10", NoteKind.LES, NoteStatus.Admitted, "freeze the clock in refund tests", "Retries depend on time.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), basis = NoteBasis(evidenceRefs = listOf("#4", "#5"))), ids)
        val before = Injection.select(notes.all(), inputs(), estimator)
        assertEquals(listOf("CON-7", "LES-10", "LES-9"), before.notes.map { it.id }.sorted())

        curator.supersede("CON-7", con("CON-8", "charge takes integer cents and a currency code", supersedes = "CON-7"), ids, by = "user:alice")

        val after = Injection.select(notes.all(), inputs(), estimator)
        assertEquals(listOf("CON-8", "LES-10"), after.notes.map { it.id }.sorted())
        assertEquals("references CON-7, which is superseded", after.excluded.first { it.id == "LES-9" }.reason)
        assertTrue(after.log.contains("excluded LES-9: references CON-7, which is superseded"), after.log)
        // The project horizon marks the dependent stale; the curator's recheck deprecates it: its contract is gone for good.
        NoteHorizon(writer, notes, ids).contractChanged("CON-7", 2)
        assertEquals(NoteStatus.Stale, notes.get("LES-9")!!.status)
        assertTrue(Injection.select(notes.all(), inputs(), estimator).excluded.first { it.id == "LES-9" }.reason.startsWith("stale"))
        val recheck = curator.recheck(ids, { null }, stamp = "s1")
        assertEquals(Recheck(readmitted = emptyList(), deprecated = listOf("LES-9"), stale = emptyList()), recheck)
        assertEquals(NoteStatus.Deprecated, notes.get("LES-9")!!.status)
        assertEquals("depends on CON-7 (superseded)", recorder.ofType<AgentEvent.Kb.Invalidated>().single { it.noteId == "LES-9" }.reason)
        assertEquals(listOf("CON-8"), recorder.ofType<AgentEvent.Kb.Admitted>().map { it.noteId })
    }

    @Test
    fun `recheck readmits a stale note whose anchors resolve again at their recorded versions`() {
        val refund = FileVersion(Digest.ofUtf8("refund v1"))
        writer.write(Note("LES-1", NoteKind.LES, NoteStatus.Stale, "keep refunds idempotent", "Retries happen.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py", refund.digest.hex.take(8))), basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        writer.write(Note("LES-2", NoteKind.LES, NoteStatus.Stale, "moved advice", "Its file moved.", "subsystem:pay", listOf(NoteAnchor("src/pay/gone.py", "abcd1234")), basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        val recheck = curator.recheck(ids, { path -> refund.takeIf { path == "src/pay/refund.py" } }, stamp = "s2")
        assertEquals(Recheck(readmitted = listOf("LES-1"), deprecated = emptyList(), stale = listOf("LES-2")), recheck)
        assertEquals("s2", notes.get("LES-1")!!.validity.lastValidated)
        assertEquals(NoteStatus.Admitted, notes.get("LES-1")!!.status)
    }

    @Test
    fun `usage counters, pruning of injected-never-cited advice and promotion of a recurring lesson`() {
        writer.write(Note("LES-5", NoteKind.LES, NoteStatus.Admitted, "never cited", "Nobody uses it.", "subsystem:pay", basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        writer.write(Note("PIT-3", NoteKind.PIT, NoteStatus.Admitted, "float rounding breaks refunds when amounts are floats", "Seen when amounts are floats.", "subsystem:pay", basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        writer.write(con("CON-1", "charge takes integer cents"), ids)
        for (cell in 1..5) {
            val inCell = ids.copy(context = ContextId("cell-$cell"))
            usage.record("LES-5", inCell, UsageEvent.Injected)
            usage.record("PIT-3", inCell, UsageEvent.Injected)
            usage.record("CON-1", inCell, UsageEvent.Injected)
            if (cell <= 3) usage.record("PIT-3", inCell, UsageEvent.Cited)
        }
        assertEquals(NoteUsage(injected = 5, cited = 3, lastCited = clock.instant().toString()), usage.of("PIT-3"))
        assertEquals(NoteUsage(injected = 5, cited = 0), usage.of("LES-5"))
        assertEquals(setOf("LES-5"), Usage.citations("Q1: apply LES-5 and CON-9 (needs: CON-9)", setOf("LES-5", "PIT-3")))

        // Prune: confidence halves per pass (unset counts as 0.5); below the floor the note is deprecated. CON never decays.
        assertEquals(listOf("LES-5"), curator.prune(ids))
        assertEquals(0.25, notes.get("LES-5")!!.confidence)
        assertEquals(NoteStatus.Admitted, notes.get("LES-5")!!.status)
        assertEquals(listOf("LES-5"), curator.prune(ids))
        assertEquals(NoteStatus.Deprecated, notes.get("LES-5")!!.status)
        assertEquals(NoteUsage(injected = 5, cited = 0), notes.get("LES-5")!!.usage)
        assertEquals(NoteStatus.Admitted, notes.get("CON-1")!!.status)
        assertTrue(recorder.ofType<AgentEvent.Kb.Invalidated>().single { it.noteId == "LES-5" }.reason.startsWith("pruned"))

        // Promote: a recurring PIT becomes a proposed task; the note becomes a pointer, nothing is committed.
        val promotions = curator.promote(ids)
        assertEquals(1, promotions.size)
        val promotion = promotions.single()
        assertEquals("PIT-3", promotion.noteId)
        assertEquals("PIT-3-pointer", promotion.pointerId)
        assertTrue(promotion.task.startsWith("promote PIT-3: add a test, linter rule or schema check for 'float rounding breaks refunds when amounts are floats' (cited 3 times)"), promotion.task)
        assertEquals(NoteStatus.Superseded, notes.get("PIT-3")!!.status)
        val pointer = notes.get("PIT-3-pointer")!!
        assertEquals(NoteStatus.Admitted, pointer.status)
        assertEquals("PIT-3", pointer.supersedes)
        assertTrue(pointer.body.startsWith("Promoted to a proposed task"))
        assertTrue(curator.promote(ids).isEmpty(), "a pointer is never promoted again")
    }

    @Test
    fun `the index is regenerated after every admission batch and health telemetry instruments its denominators`() {
        curator.queue.enqueue(con("CON-1", "charge takes integer cents").copy(status = NoteStatus.Candidate), ids)
        curator.queue.enqueue(Note("LES-1", NoteKind.LES, NoteStatus.Candidate, "freeze the clock in refund tests", "Retries depend on time.", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py")), confidence = 0.5, basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
        curator.queue.enqueue(Note("LES-2", NoteKind.LES, NoteStatus.Candidate, "a global lesson from one use", "Once.", "global", basis = NoteBasis(evidenceRefs = listOf("#1"))), ids)
        val indexDir = store.layout.kb.resolve("index")
        val empty = KbHealth.of(notes.all(), curator.queue.all(), usage.rows(), KbResolver.ALL, indexDir, estimator)
        assertEquals(3, empty.candidates)
        assertNull(empty.admissionRate)
        assertNull(empty.citedRate)
        assertNull(empty.locatorValidity)
        assertNull(empty.harmfulInjections)

        val batch = curator.admit(ids, AdmissionMode.Autonomous)
        assertEquals(listOf("LES-1"), batch.admitted)
        assertEquals(setOf("LES-2"), batch.rejected.keys)
        assertEquals(mapOf("CON-1" to "CON waits for the user"), batch.waiting)
        assertEquals(listOf("global.md", "contracts.md", "subsystem-pay.md"), batch.indexWritten)
        assertEquals("# Subsystem pay (regenerated)\n- LES-1: freeze the clock in refund tests\n", Files.readString(indexDir.resolve("subsystem-pay.md")))

        usage.record("LES-1", ids, UsageEvent.Injected)
        usage.record("LES-1", ids.copy(context = ContextId("cell-2")), UsageEvent.Injected)
        usage.record("LES-1", ids.copy(context = ContextId("cell-2")), UsageEvent.Cited)
        val health = KbHealth.of(notes.all(), curator.queue.all(), usage.rows(), KbResolver.of({ it == "src/pay/api.py" }, { true }), indexDir, estimator)
        assertEquals(1, health.candidates)
        assertEquals(1, health.admitted)
        assertEquals(1, health.rejected)
        assertEquals(1, health.waiting)
        assertEquals(0.5, health.admissionRate)
        assertEquals(2, health.injections)
        assertEquals(0.5, health.citedRate)
        assertEquals(0, health.staleInjections)
        assertEquals(0, health.repeatedMistakes)
        assertTrue(health.indexFresh)
        assertEquals(0.0, health.locatorValidity, "LES-1's anchor is refund.py, which this resolver does not know")
        // A note going stale after being injected counts as a stale injection; a hand-edited index is not fresh.
        writer.setStatus("LES-1", NoteStatus.Stale, ids)
        Files.writeString(indexDir.resolve("subsystem-pay.md"), "edited by hand\n")
        val degraded = KbHealth.of(notes.all(), curator.queue.all(), usage.rows(), KbResolver.ALL, indexDir, estimator)
        assertEquals(2, degraded.staleInjections)
        assertTrue(!degraded.indexFresh)
    }
}
