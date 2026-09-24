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
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
