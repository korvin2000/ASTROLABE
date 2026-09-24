package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Fact
import io.astrolabe.register.Register
import io.astrolabe.store.Store
import io.astrolabe.verify.Finding
import io.astrolabe.verify.FindingKind
import io.astrolabe.verify.Severity
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.2.2: typed `NEG` states render their own bounds (a later cell never reads "not found" as "absent"); the record-derived PIT candidates. */
class NegativeEvidenceTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val stamp = CandidateId(Digest.ofUtf8("s1"))
    private val ids = Identities(WorkId("W-8"), AttemptId("a1"), stamp, ContextId("cell-2"))
    private val diagnosis = Diagnosis("no feature flag for refunds", "when searched for refund_flag", "kb.search and look(find)", "no hit", "unknown", listOf("#4"), "s1", "a flag appears")

    private fun neg(negative: NegativeEvidence) =
        Candidate(CandidateKind.NEG, "refund-flag-${negative.state.wire}", "no refund feature flag", "subsystem:src/pay", diagnosis, negative = negative).note(NoteOrigin(extractor = Extractor.EXTRACTOR))

    /** What a later cell sees: the compiler's `line + body` and the `kb(get)` markdown. */
    private fun renders(note: Note) = listOf(note.line + "\n" + note.body, KbExport.markdown(note))

    @Test
    fun `a NEG note renders its state and bounds so not found is never read as absent`() {
        val searched = neg(NegativeEvidence.SearchedEmpty("src/pay/**", "abc123", "fts complete"))
        assertEquals("[searched-empty] no refund feature flag", searched.summary)
        assertEquals(NegativeState.SearchedEmpty, NegativeEvidence.stateOf(searched.summary))
        assertEquals(NegativeState.SearchedEmpty, NegativeEvidence.stateOf(searched.body))
        for (render in renders(searched)) {
            assertTrue("searched-empty(scope=src/pay/**, version=abc123, index coverage=fts complete)" in render, render)
            assertTrue("not found there at that version; not absent" in render, render)
            assertFalse("absent" in render.replace("not absent", ""), "no reading of absence outside the negation: $render")
        }
        for (unbounded in listOf(neg(NegativeEvidence.Unknown), neg(NegativeEvidence.Unsearched))) {
            for (render in renders(unbounded)) assertFalse("absent" in render, render)
            assertTrue("no reading either way" in unbounded.body)
        }
        val contradicted = neg(NegativeEvidence.Contradicted("#9"))
        assertTrue(contradicted.body.startsWith("contradicted by #9: the claim conflicts with that evidence · symptom: "), contradicted.body)
        val absent = neg(NegativeEvidence.VerifiedAbsent("the refund module's flags", "#12"))
        for (render in renders(absent)) assertTrue("verified-absent(domain=the refund module's flags) by #12: absent within that domain only; outside it unknown" in render, render)
        assertNull(NegativeEvidence.stateOf("no state here"))

        assertFailsWith<IllegalArgumentException> { Candidate(CandidateKind.NEG, "untyped", "no flag", "subsystem:src", diagnosis) }
        assertFailsWith<IllegalArgumentException> { Candidate(CandidateKind.LES, "typed-lesson", "a lesson", "subsystem:src", diagnosis, negative = NegativeEvidence.Unknown) }
        assertFailsWith<IllegalArgumentException> { NegativeEvidence.SearchedEmpty("src", "", "complete") }
    }

    private fun packet(register: Register) = ResultPacket(
        ids, "I1", "implement", 1, ExecutionGeneration.INITIAL, PacketBase(stamp, WorkspaceId("ws-main")), emptyMap(), PacketStatus.Done, null, null,
        register, emptyList(), emptyList(), emptyList(), listOf("#3"), stamp, Digest.ofUtf8("env"),
        PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), null, emptyList(), emptyList(), PacketCost(),
    )

    private fun register(cell: String, vararg deadEnds: DeadEnd, facts: List<Fact> = emptyList()) =
        Register.empty(ContextId(cell), "I1", "Fix refunds").copy(facts = facts, deadEnds = deadEnds.toList())

    @Test
    fun `x facts, findings at or above major and recurring dead ends derive PIT candidates and open items`() {
        val anchor = Anchor("src/pay/refund.py", FileVersion(Digest.ofUtf8("refund v1")), 12)
        val facts = listOf(
            Fact(1, ClaimKind.Refuted, "refund() is idempotent", anchor, evidenceId = "#2", refutedBy = "#5"),
            Fact(2, ClaimKind.Refuted, "an unevidenced refutation", null),
            Fact(3, ClaimKind.Verified, "refund() retries once", anchor, evidenceId = "#6"),
        )
        val recurring = DeadEnd(1, "Retry the gateway call", "#7", "src/pay", "when the gateway is idempotent")
        val earlier = register("cell-1", recurring.copy(text = "retry the  gateway call", evidence = "#1"), DeadEnd(2, "only once", "#8", "src/pay", "never"))
        val trace = ExtractionTrace(packet(register("cell-2", recurring, facts = facts)), sourceRevision = "s1")
        val findings = listOf(
            Finding(Severity.Blocker, "src/pay/refund.py:40@abc", "refund() double-charges on timeout", "add an idempotency key", FindingKind.Correctness),
            Finding(Severity.Major, "src/pay/api.py:8@abc", "charge() ignores the currency", null, FindingKind.Contract),
            Finding(Severity.Minor, "src/pay/api.py:9@abc", "naming", null, FindingKind.Quality),
        )

        val candidates = Derived.candidates(trace, findings, listOf(earlier))
        assertEquals(listOf("PIT-refuted-cell-2-1", "PIT-finding-<hash>", "PIT-finding-<hash>", "PIT-recurring-<hash>"), candidates.map { it.id.replace(Regex("(finding|recurring)-[0-9a-f]{12}$"), "$1-<hash>") })
        val refuted = candidates[0]
        assertEquals(listOf("#5", "#2"), refuted.diagnosis.evidence)
        assertEquals(listOf(NoteAnchor("src/pay/refund.py", anchor.version.digest.hex.take(12))), refuted.anchors)
        assertEquals("subsystem:src/pay", refuted.scope)
        assertTrue(Lint.conditional(refuted.diagnosis.render()), "a derived PIT states its conditions")
        assertEquals("subsystem:src/pay", candidates[1].scope)
        assertEquals(listOf("src/pay/refund.py:40@abc"), candidates[1].diagnosis.evidence)
        assertEquals("add an idempotency key", candidates[1].diagnosis.attempted)
        assertEquals("task:I1", candidates[3].scope)
        assertEquals(listOf("#1", "#7"), candidates[3].diagnosis.evidence, "the earlier cell's evidence first")
        assertTrue(Derived.recurring(listOf(register("cell-3", recurring)), "s1").isEmpty(), "one cell is no recurrence")

        val open = Derived.openItems(findings, from = 4)
        assertEquals(listOf(4, 5), open.map { it.n })
        assertEquals("review blocker: refund() double-charges on timeout at src/pay/refund.py:40@abc", open[0].text)
        assertEquals("src/pay/refund.py", open[0].trip)
        assertEquals("add an idempotency key", open[0].needs)
        assertNull(open[1].needs)

        // The extractor enqueues them as harness-derived candidates that pass the lint.
        TempRepo.create().use { repo ->
            repo.write("src/pay/refund.py", "def refund(): pass\n")
            repo.write("src/pay/api.py", "def charge(): pass\n")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val report = Extractor(store, HeuristicEstimator(), idGen, clock).run(trace, ids, findings, listOf(earlier))
                assertNull(report.failure)
                assertEquals(4, report.enqueued.size, report.refused.toString())
                val notes = Notes(store)
                val resolver = KbResolver.of({ Files.exists(repo.root.resolve(it)) }, { it.startsWith("#") || it.startsWith("src/") })
                for (entry in report.enqueued) {
                    val note = notes.get(entry.noteId)!!
                    assertEquals(Extractor.HARNESS_DERIVED, note.origin.extractor)
                    assertEquals(emptyList(), Lint.check(note, notes.all(), resolver), note.id)
                }
            }
        }
    }
}
