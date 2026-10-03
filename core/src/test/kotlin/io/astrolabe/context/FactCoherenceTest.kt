package io.astrolabe.context

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Decision
import io.astrolabe.register.Fact
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import io.astrolabe.register.Validation
import io.astrolabe.register.ValidationContext
import io.astrolabe.register.Validator
import io.astrolabe.tool.state.ParsedPatch
import io.astrolabe.tool.state.PatchParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2.4.2 cross-cell fact coherence and bounded retention (§6.4): FX-20, FX-57. */
class FactCoherenceTest {
    private val estimator = HeuristicEstimator()
    private fun v(text: String) = FileVersion(Digest.ofUtf8(text))
    private val cell = ContextId("cell-1")
    private val evidence = setOf("#3", "#5", "#6", "#7", "#8")

    private val raceReport = Register(
        version = 4, cell = cell, increment = "I1", incrementTitle = "fix the checkout race",
        facts = listOf(
            Fact(1, ClaimKind.Verified, "race reproduced by test_concurrent_checkout", Anchor("tests/test_checkout.py", v("t-1")), "#3"),
            Fact(2, ClaimKind.Verified, "lock is taken per request", Anchor("src/checkout.py", v("c-1")), "#5"),
            Fact(3, ClaimKind.Refuted, "the cache causes the race", refutedBy = "#6"),
            Fact(4, ClaimKind.Verified, "retry count is 3", evidenceId = "#missing"),
        ),
        deadEnds = listOf(DeadEnd(1, "a global lock hides the race but deadlocks the refund path", "#7", "src/checkout.py", "refund path isolated")),
        decisions = listOf(Decision(1, "keep the per-request lock", "the refund path needs it", "global lock")),
    )

    @Test
    fun `durable cell retention ages once and archives on the next cell`(@org.junit.jupiter.api.io.TempDir state: java.nio.file.Path) {
        io.astrolabe.workspace.WorkspaceFixture.create(state).use { f ->
            fun capture(register: Register) = FactRetention.capture(f.store, f.ids, register, { v("moved") }, { it in evidence }, estimator, 1200, f.clock)
            val first = capture(raceReport.copy(deadEnds = emptyList(), decisions = emptyList()))
            assertEquals(ClaimKind.Hypothesis, first.register.fact(4)!!.kind)
            assertEquals(1, first.register.fact(1)!!.staleCells)
            assertEquals(first, capture(raceReport), "retrying a boundary reads its durable decision")
            val second = capture(first.register.copy(cell = ContextId("cell-2")))
            assertEquals(listOf(1, 2), second.archived.map { it.n })
            assertTrue(second.register.facts.none { it.kind == ClaimKind.Verified })
            val status = StatusNotes(io.astrolabe.kb.KbWriter(f.store, estimator, f.clock), io.astrolabe.kb.Notes(f.store), f.store.layout.kb)
            status.checkpoint(f.ids, StatusBoundary.CellEnd, second.archived, emptyList(), emptyList())
            assertEquals(second.archived, status.archived(f.ids.work))
        }
    }

    @Test
    fun `repeated rebuilds keep the race evidence reachable through dead ends and the archive (FX-20)`() {
        val moved = mapOf("tests/test_checkout.py" to v("t-2"), "src/checkout.py" to v("c-2"))
        var register = raceReport
        var streak = emptyMap<Int, Int>()
        val archive = ArrayList<ArchivedRecord>()
        repeat(3) {
            val pass = FactCoherence.retain(register, streak, referenced = setOf(2), currentVersion = { moved[it] }, evidenceExists = { it in evidence }, estimator = estimator)
            register = pass.register
            streak = pass.staleStreak
            archive += pass.archived
        }
        assertEquals(listOf(1), archive.map { it.n }, "stale for two consecutive cells and unreferenced ⇒ archived once")
        assertEquals("v race reproduced by test_concurrent_checkout  tests/test_checkout.py @${v("t-1").hash8} (stale @${v("t-1").hash8})", archive.single().text)
        assertEquals("#3", archive.single().evidence)
        assertEquals(v("c-1"), register.fact(2)!!.staleAt, "a referenced stale fact stays, rendered stale")
        assertEquals(ClaimKind.Hypothesis, register.fact(4)!!.kind, "a v fact whose evidence does not resolve is only a hypothesis")
        assertEquals(ClaimKind.Refuted, register.fact(3)!!.kind, "x facts are durable")
        val reachable = register.facts.mapNotNull { it.evidenceId ?: it.refutedBy } + register.deadEnds.mapNotNull { it.evidence } + archive.mapNotNull { it.evidence }
        assertTrue(reachable.containsAll(listOf("#3", "#5", "#6", "#7")), reachable.toString())
        assertEquals(raceReport.deadEnds, register.deadEnds)
    }

    @Test
    fun `refuted facts over the register cap are archived verbatim and required records raise a capacity gap (FX-57)`() {
        val refuted = (1..40).map { Fact(it, ClaimKind.Refuted, "hypothesis $it about the scheduler ordering under load was wrong", refutedBy = "#r$it") }
        val crowded = raceReport.copy(facts = refuted)
        val pass = FactCoherence.retain(crowded, emptyMap(), emptySet(), { null }, { true }, estimator, capTokens = 400)
        assertNull(pass.capacityGap, "archiving inactive records was enough")
        assertTrue(pass.archived.isNotEmpty() && pass.archived.all { it.reason.startsWith("inactive: refuted") })
        assertEquals((1..pass.archived.size).toList(), pass.archived.map { it.n }, "oldest first")
        assertEquals("x hypothesis 1 about the scheduler ordering under load was wrong  (refuted #r1)", pass.archived.first().text)
        assertEquals(40, pass.pitCandidates.size, "every x fact stays a PIT candidate")
        assertEquals(40, pass.register.facts.size + pass.archived.size, "nothing deleted")
        assertEquals(crowded.deadEnds, pass.register.deadEnds)

        val required = raceReport.copy(facts = emptyList(), deadEnds = (1..30).map { DeadEnd(it, "approach $it failed on the refund path", "#d$it", "src/", "never") })
        val tight = FactCoherence.retain(required, emptyMap(), emptySet(), { null }, { true }, estimator, capTokens = 200)
        assertNotNull(tight.capacityGap)
        assertEquals(30, tight.register.deadEnds.size, "required carry-forward is never deleted")
    }

    @Test
    fun `a stale fact copied back with an anchor version this cell never showed does not come back fresh`() {
        val shown = v("a-1")
        val now = v("a-2")
        val earlier = raceReport.copy(facts = listOf(Fact(1, ClaimKind.Verified, "a returns 1", Anchor("src/a.py", shown), "#3")), next = "check a")
        val carried = CarryForward.carry(earlier, emptyList(), null, { now }, { true }, emptyList(), emptyList()).register
        assertEquals(shown, carried.fact(1)!!.staleAt, "the carry tags the moved anchor stale")
        assertTrue("v(stale @${shown.hash8}) a returns 1" in RegisterRender.markdown(carried))

        // This cell knows only the current version, so the hash the carry rendered resolves to nothing (D-365).
        val context = object : ValidationContext {
            override fun evidenceExists(id: String): Boolean = id == "#3"
            override fun currentVersion(path: String): FileVersion? = now
            override fun acceptGreen(accept: String): Boolean = false
            override val redChecks: Set<String> = emptySet()
            override val greenOps: Set<Int> = emptySet()
            override val appliedOps: Set<Int> = emptySet()
        }
        fun add(version: String): Pair<Fact, List<String>> {
            val ops = Json.parseToJsonElement("""[{"fact.add":{"kind":"v","text":"a returns 1","evidence":"#3","anchor":{"path":"src/a.py","version":"$version"}}}]""").jsonArray
            val parsed = assertIs<ParsedPatch.Valid>(PatchParser.parse(ops, emptyMap(), context))
            val applied = assertIs<Validation.Applied>(Validator(estimator).check(carried, parsed.patch, context))
            return applied.register.facts.last() to parsed.notes
        }

        for (version in listOf(shown.hash8, "latest", "")) {
            val (back, notes) = add(version)
            assertEquals(ClaimKind.Hypothesis, back.kind, "anchor '$version': without its anchor a v fact could never go stale, so it is kept as h")
            assertEquals(null, back.anchor)
            assertTrue(notes.single().endsWith("v fact kept as h without an anchor (its staleness could not be tracked); add it again with the @hash a look showed"), notes.single())
            val next = CarryForward.carry(carried.copy(facts = carried.facts + back), emptyList(), null, { now }, { true }, emptyList(), emptyList()).register
            assertFalse(RegisterRender.markdown(next).lines().any { it.contains("- v a returns 1") }, "the fact does not render as a fresh v in the next cell")
        }
        val (full, _) = add(shown.digest.hex)
        assertEquals(ClaimKind.Verified, full.kind)
        assertTrue(full.stale, "a full hash keeps its anchor and is stale at once")
        val (current, _) = add(now.hash8)
        assertEquals(Anchor("src/a.py", now), current.anchor)
        assertFalse(current.stale, "a hash of the current version stays a fresh, tracked v fact")
    }
}
