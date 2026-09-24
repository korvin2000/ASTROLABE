package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.graph.Sizing
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.provider.Effort
import io.astrolabe.register.Register
import io.astrolabe.route.Tier
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.2.1: the post-cell extractor enqueues lint-passing candidates from the archived trace, contains failures and aggregates the CAL delta. */
class ExtractorTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val estimator = HeuristicEstimator()
    private val stamp = CandidateId(Digest.ofUtf8("s1"))
    private val ids = Identities(WorkId("W-7"), AttemptId("a1"), stamp, ContextId("cell-1"))
    private val series = CalibrationSeries("repo-1", "0.1", "d16-bands-v1")
    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var journal: Journal
    private lateinit var resolver: KbResolver

    @BeforeTest
    fun open() {
        repo = TempRepo.create()
        repo.write("src/pay/api.py", "def charge(): pass\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        journal = Journal(store, clock)
        resolver = KbResolver.of({ Files.exists(repo.root.resolve(it)) }, { it.startsWith("#") || it.startsWith("campaign:") })
    }

    @AfterTest
    fun close() {
        store.close()
        repo.close()
    }

    private fun packet(receipts: List<String> = listOf("#3")) = ResultPacket(
        ids, "I1", "implement", 1, ExecutionGeneration.INITIAL, PacketBase(stamp, WorkspaceId("ws-main")), emptyMap(), PacketStatus.Done, null, null,
        Register.empty(ids.context!!, "I1", "Fix it"), emptyList(), emptyList(), emptyList(), receipts, stamp, Digest.ofUtf8("env"),
        PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), null, emptyList(), emptyList(), PacketCost(),
    )

    private fun trace() = ExtractionTrace(packet(), listOf(JournalEvent("ev-a", ids, 1, JournalKind.Call, text = "look(read) src/pay/api.py", at = clock.instant(), seq = 1)))

    private fun diagnosis(evidence: List<String>) = Diagnosis(
        "charge() retried a timed-out call", "when the gateway answers 504 under load", "a blind retry", "double charge in the log",
        "the retry is not idempotent", evidence, "s1", "an idempotency key on charge()",
    )

    private fun candidate(kind: CandidateKind, name: String, evidence: List<String>) = Candidate(
        kind, name, "retry charge() only with an idempotency key", "subsystem:pay", diagnosis(evidence), listOf(NoteAnchor("src/pay/api.py")), confidence = 0.5,
    )

    @Test
    fun `a scripted extractor enqueues lint-passing candidates from the trace at the curation row's tier`() {
        var seen: Pair<Tier, Effort?>? = null
        val scripted = Extraction { trace, tier, effort ->
            seen = tier to effort
            ExtractionResult(
                listOf(candidate(CandidateKind.LES, "idempotent-retry", trace.receipts), candidate(CandidateKind.PIT, "blind-retry", trace.receipts)),
                tokens = 120,
            )
        }
        val extractor = Extractor(store, estimator, idGen, clock, scripted, journal)
        val report = extractor.run(trace(), ids)
        assertNull(report.failure)
        assertEquals(listOf("LES-idempotent-retry", "PIT-blind-retry"), report.enqueued.map { it.noteId })
        assertEquals(120, report.tokens)
        assertEquals(Tier.Low to Effort.Low, seen, "the §11.1 curation row: low tier, low effort")
        val notes = Notes(store)
        for (entry in report.enqueued) {
            val note = notes.get(entry.noteId)!!
            assertEquals(NoteStatus.Candidate, note.status)
            assertEquals(emptyList(), Lint.check(note, notes.all(), resolver), "lint-passing candidate ${note.id}")
            assertTrue(note.body.startsWith("symptom: ") && " · invalidation: " in note.body, "the §12.2 diagnosis shape")
            assertEquals(Extractor.EXTRACTOR, note.origin.extractor)
        }
        assertEquals(2, extractor.queue.pending().size)
        val line = journal.events(JournalScope(ids.work, kinds = setOf(JournalKind.Boundary))).single().text
        assertTrue("120 tokens charged to W-7" in line && "enqueued 2, refused 0" in line, line)
    }

    @Test
    fun `a failing or refusing extraction is journaled and never thrown`() {
        val failing = Extractor(store, estimator, idGen, clock, { _, _, _ -> throw IllegalStateException("provider refused") }, journal)
        val failed = failing.run(trace(), ids)
        assertEquals("IllegalStateException: provider refused", failed.failure)
        assertTrue(failed.enqueued.isEmpty())

        val oversized = candidate(CandidateKind.LES, "too-long", listOf("#3")).copy(diagnosis = diagnosis(listOf("#3")).copy(observed = "word ".repeat(200)))
        val refusing = Extractor(store, estimator, idGen, clock, { _, _, _ -> ExtractionResult(listOf(oversized, candidate(CandidateKind.LES, "kept", listOf("#3")))) }, journal)
        val report = refusing.run(trace(), ids)
        assertNull(report.failure)
        assertEquals(listOf("LES-kept"), report.enqueued.map { it.noteId })
        assertTrue("body is" in report.refused.getValue("LES-too-long"))
        val lines = journal.events(JournalScope(ids.work, kinds = setOf(JournalKind.Boundary))).map { it.text }
        assertTrue(lines[0].endsWith("failed: IllegalStateException: provider refused"), lines[0])
        assertTrue("refused 1 · LES-too-long: " in lines[1], lines[1])
    }

    @Test
    fun `the CAL delta is aggregated from CalibrationStats, admitted as harness and superseded by the next delta`() {
        fun observation(n: Int, continuations: Int) = CalibrationObservation(
            CalibrationId(WorkId("W-$n"), AttemptId("a1"), "I1"), series, "src/pay", 2, Sizing(turns = 3 + n, continuations = continuations, filesTouched = 2),
            CalibrationOutcome.Completed, "campaign:W-$n/a1",
        )
        val policy = CalibrationPolicy("d16-bands-v1", listOf(2, 6, Int.MAX_VALUE))
        val extractor = Extractor(store, estimator, idGen, clock, journal = journal)
        val curator = Curator(store, estimator, idGen, clock, resolver)

        assertTrue(extractor.calibrate({ CalibrationStats.aggregate(emptyList(), policy) }, series, ids).enqueued.isEmpty(), "no history, no note")
        val first = extractor.calibrate({ CalibrationStats.aggregate(listOf(observation(1, 0)), policy) }, series, ids)
        assertEquals(listOf("CAL-repo-1"), first.enqueued.map { it.noteId })
        val note = Notes(store).get("CAL-repo-1")!!
        assertTrue(note.body.startsWith("harness statistics (data, not instruction) over 1 increments"), note.body)
        assertTrue("0-2 files: overrun 0% (n=1)" in note.body && "src/pay: overrun 0% (n=1)" in note.body, note.body)
        assertTrue(estimator.estimate(note.body).tokens <= CalibrationNote.CAP_TOKENS)
        assertTrue(note.origin.extractor!!.startsWith(Extractor.HARNESS_CALIBRATION))
        assertEquals(emptyList(), Lint.check(note, Notes(store).all(), resolver))

        val batch = curator.admit(ids, AdmissionMode.Autonomous)
        assertEquals(listOf("CAL-repo-1"), batch.admitted)
        assertEquals(AdmissionPolicy.HARNESS, Notes(store).get("CAL-repo-1")!!.origin.admittedBy)

        assertTrue(extractor.calibrate({ CalibrationStats.aggregate(listOf(observation(1, 0)), policy) }, series, ids).enqueued.isEmpty(), "unchanged statistics are no delta")
        val second = extractor.calibrate({ CalibrationStats.aggregate(listOf(observation(1, 0), observation(2, 1)), policy) }, series, ids)
        assertEquals(listOf("CAL-repo-1-r2"), second.enqueued.map { it.noteId })
        assertEquals("CAL-repo-1", Notes(store).get("CAL-repo-1-r2")!!.supersedes)
        assertEquals(listOf("CAL-repo-1-r2"), curator.admit(ids, AdmissionMode.Autonomous).admitted)
        assertEquals(NoteStatus.Superseded, Notes(store).get("CAL-repo-1")!!.status)
        assertEquals(NoteStatus.Admitted, Notes(store).get("CAL-repo-1-r2")!!.status)

        val conflicting = extractor.calibrate({ throw IllegalArgumentException("conflicting calibration checkpoints") }, series, ids)
        assertNotNull(conflicting.failure)
        assertTrue(journal.events(JournalScope(ids.work)).last().text.endsWith("failed: IllegalArgumentException: conflicting calibration checkpoints"))
    }

    @Test
    fun `a model-written CAL candidate waits while the harness delta is admitted`() {
        val modelWritten = Note("CAL-repo-1", NoteKind.CAL, NoteStatus.Candidate, "calibration prior for repo-1: 9 increments", "median turns 1", "global", basis = NoteBasis(evidenceRefs = listOf("#3")), origin = NoteOrigin(extractor = "kb.propose"))
        assertEquals(AdmissionDecision.Wait("CAL waits for the user"), AdmissionPolicy.decide(modelWritten, emptyList(), AdmissionMode.Autonomous))
        val harness = modelWritten.copy(origin = NoteOrigin(extractor = Extractor.HARNESS_CALIBRATION + "calibration-stats-v1"))
        assertEquals(AdmissionDecision.Admit(AdmissionPolicy.HARNESS), AdmissionPolicy.decide(harness, emptyList(), AdmissionMode.Autonomous))
    }
}
