package io.astrolabe.kb

import io.astrolabe.Defaults
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Roles
import io.astrolabe.context.Compiled
import io.astrolabe.context.CompileInputs
import io.astrolabe.context.Compiler
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.DClassPolicy
import io.astrolabe.contract.Increment
import io.astrolabe.Mode
import io.astrolabe.contract.Origin
import io.astrolabe.graph.Production
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P4.1.3: D-37 ranking is deterministic, precision-gated and capped; CON bypasses the cap; focus notes; the CAL path (D-42). */
class InjectionTest {
    private val estimator = HeuristicEstimator()
    private val work = WorkId("W-42")

    private fun les(id: String, scope: String = "subsystem:pay", evidence: Int = 2, depends: List<String> = emptyList(), validated: String? = null, status: NoteStatus = NoteStatus.Admitted) =
        Note(id, NoteKind.LES, status, "lesson $id about refunds", "Advice $id.", scope, basis = NoteBasis(evidenceRefs = List(evidence) { "#$it" }), validity = NoteValidity(depends, validated))

    private val con1 = Note("CON-1", NoteKind.CON, NoteStatus.Admitted, "refund API takes integer cents", "Callers pass cents.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py")))
    private val con9 = Note("CON-9", NoteKind.CON, NoteStatus.Admitted, "ledger events are append-only", "Never rewrite events.", "src/ledger/**", listOf(NoteAnchor("src/ledger/events.py")))
    private val notes = listOf(
        con1, con9, les("LES-2", validated = "s1"), les("LES-3", scope = "global", evidence = 0), les("LES-4", depends = listOf("CON-1@v1")),
        les("LES-5", status = NoteStatus.Stale), les("LES-6", status = NoteStatus.Candidate),
        Note("STATUS-W-9", NoteKind.STATUS, NoteStatus.Admitted, "another task's checkpoint", "b", "task:W-9"),
    )

    private fun inputs(vararg writeScope: String = arrayOf("src/pay/**"), already: Set<String> = emptySet()) =
        InjectionInputs(Roles.implementing, work, writeScope.toList(), contractsInPlay = setOf("CON-1"), stamp = "s1", alreadyInjected = already)

    @Test
    fun `ranking is deterministic for equal inputs and independent of the input order`() {
        val a = Injection.select(notes, inputs(), estimator)
        val b = Injection.select(notes, inputs(), estimator)
        val c = Injection.select(notes.shuffled(Random(7)), inputs(), estimator)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(listOf("CON-1", "LES-4", "LES-2"), a.notes.map { it.id })
        assertTrue(a.selected.first().mandatory && a.selected.drop(1).none { it.mandatory })
        assertTrue(a.selected[1].score > a.selected[2].score, "the dependency overlap ranks LES-4 above LES-2: ${a.selected.map { it.score }}")
        val excluded = a.excluded.associate { it.id to it.reason }
        assertEquals(setOf("CON-9", "LES-3", "LES-5", "STATUS-W-9"), excluded.keys)
        assertTrue(excluded.getValue("CON-9").startsWith("score") && excluded.getValue("LES-3").startsWith("score"), excluded.toString())
        assertTrue(excluded.getValue("LES-5").startsWith("stale"), excluded.toString())
        assertEquals("STATUS of another task", excluded.getValue("STATUS-W-9"))
        assertNull(a.excluded.firstOrNull { it.id == "LES-6" }, "a candidate is not admitted, so it is not a flagged exclusion")
    }

    @Test
    fun `CON anchored in the write scope bypasses the cap and the threshold, nothing is injected when nothing is relevant`() {
        val many = (1..12).map { les("LES-$it") }
        val capped = Injection.select(listOf(con1) + many, inputs(), estimator)
        assertEquals(9, capped.selected.size)
        assertEquals("CON-1", capped.notes.first().id)
        assertEquals(4, capped.excluded.count { it.reason.startsWith("over the cap") })
        val weights = InjectionWeights(maxTokens = 40)
        val byTokens = Injection.select(listOf(con1) + many, inputs(), estimator, weights)
        assertTrue(byTokens.selected.drop(1).sumOf { it.tokens } <= 40 && byTokens.selected.size < 9, byTokens.log)

        val irrelevant = Injection.select(listOf(con9, les("LES-3", scope = "global", evidence = 0)), inputs(), estimator)
        assertTrue(irrelevant.selected.isEmpty(), irrelevant.log)
        val touched = Injection.select(listOf(con9), inputs().copy(touched = setOf("src/ledger/events.py")), estimator)
        assertEquals(listOf("CON-9"), touched.notes.map { it.id })
    }

    @Test
    fun `unchanged advice is never re-injected within a cell, an anchor that moved is an eligibility failure`() {
        val again = Injection.select(notes, inputs(already = setOf(Injection.digest(notes[2]))), estimator)
        assertEquals("unchanged advice already injected in this cell", again.excluded.first { it.id == "LES-2" }.reason)
        val moved = con1.copy(anchors = listOf(NoteAnchor("src/pay/api.py", "deadbeef")))
        val stale = Injection.select(listOf(moved), inputs().copy(currentVersion = { null }), estimator)
        assertEquals("anchor src/pay/api.py is gone from the tree", stale.excluded.single().reason)
    }

    @Test
    fun `focus notes render once per cell within their budget, anchored in the focus directory or the files touched`() {
        val anchored = (1..30).map { Note("LES-f$it", NoteKind.LES, NoteStatus.Admitted, "focus lesson $it about the refund path, its retries, the idempotency key and the frozen clock the tests need", "b", "subsystem:pay", listOf(NoteAnchor("src/pay/refund.py"))) }
        val elsewhere = Note("PIT-e", NoteKind.PIT, NoteStatus.Admitted, "elsewhere", "b", "subsystem:pay", listOf(NoteAnchor("src/ledger/events.py")))
        val focus = FocusNotes(anchored + elsewhere + con1, estimator, maxTokens = 300, compiled = setOf("CON-1"))
        val first = focus.render("src/pay", emptySet())!!
        assertTrue(estimator.estimate(first).tokens <= 300)
        val shown = first.lines().size
        assertTrue(shown in 1 until 30, "the budget cuts the list: $shown")
        assertTrue("CON-1" !in first, "a note already compiled into [K] is never repeated")
        val second = focus.render("src/pay", emptySet())!!
        assertTrue(first.lines().none { it in second.lines() }, "each note is shown once per cell")
        val touched = focus.render(null, setOf("src/ledger/events.py"))
        assertEquals("- PIT-e: elsewhere", touched)
        assertNull(focus.render(null, setOf("src/ledger/events.py")))
    }

    @Test
    fun `the CAL note reaches the plan cell through injection and replaces the calibration block`() {
        val cal = Note("CAL-repo", NoteKind.CAL, NoteStatus.Admitted, "increments touching 3 files run 2 cells on average", "Measured over 14 increments.", "global", origin = NoteOrigin(admittedBy = "harness"))
        val planning = InjectionInputs(Roles.plan, work, listOf("src/"))
        assertEquals(listOf("CAL-repo"), Injection.select(listOf(cal), planning, estimator).notes.map { it.id })
        assertEquals("kind CAL is outside the review role's note scope", Injection.eligibility(cal, mapOf(cal.id to cal), planning.copy(role = Roles.review)))
        val contract = Contract(
            work, 1, AttemptId("a1"), Mode.Autonomous, Shape.S1, listOf(UserRequest("U1", Instant.EPOCH, "Fix refunds")),
            listOf(Requirement("R1", "refunds use integer cents", listOf("AC1"), authorityRef = "U1")),
            listOf(Acceptance.Run("AC1", Command(listOf("pytest")), Origin.User)),
            listOf(Constraint("C1", "never change the public refund signature", "U1")), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
            Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
        )
        val increment = Increment("I1", listOf("R1"), listOf("AC1"), listOf("src/pay/"), 2, title = "refunds", produces = Production.Artifact)
        val block = "CAL (harness statistics, data not instruction): 4 increments"
        val withNote = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.plan, "prime\n", inputs = CompileInputs(notes = listOf(cal), calibration = block)))
        assertEquals(listOf("note.CAL-repo"), withNote.k.sections.map { it.id })
        val withoutNote = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.plan, "prime\n", inputs = CompileInputs(calibration = block)))
        assertEquals(listOf("calibration"), withoutNote.k.sections.map { it.id })
    }
}
