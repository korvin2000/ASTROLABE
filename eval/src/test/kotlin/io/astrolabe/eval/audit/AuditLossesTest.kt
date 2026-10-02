package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuditLossesTest {
    /** Prices of the synthetic route: 1 / 0.1 / 2 per million (uncached / cache read / output). */
    private fun bill(u: Long, c: Long, o: Long): String =
        (BigDecimal(u).movePointLeft(6) + BigDecimal(c).movePointLeft(7) + BigDecimal(2 * o).movePointLeft(6)).toPlainString()

    private fun tests(status: String) = "⟦result #1 tool=run class=W stamp=cc3a truncated=no effects=unknown status=$status⟧"

    private fun AuditLog.request(u: Long, c: Long, o: Long, billed: Boolean = true) =
        call(u, c, o, reasoning = o / 2, billed = if (billed) bill(u, c, o) else null)

    private fun edit(id: String) = AuditLog.calls(Triple(id, "edit", """{"ops": [{"create": "a.html", "content": "${"x".repeat(400)}"}]}"""))

    /**
     * Requests carry content `C` plus a 100-token anchor; the provider caches the previous content. Turn 3 ends with an
     * eviction batch (cached 3000 of 6900), turn 4 with a reserve gate (full miss), turn 5 with a Workset drop (cached
     * 5000 of 8400), and before request 7 the provider misses on its own (4000 of 9000).
     */
    private fun run(format: JournalFormat, billed: Boolean = true): AuditLog = AuditLog(format)
        .turn(1).request(5_000, 0, 100, billed).tool("run", "run", tests("failed"))
        .turn(2).request(1_100, 4_900, 120, billed).tool("run", "run", tests("passed")).entry("call", "turn 2", edit("e1"))
        .turn(3).request(1_100, 5_900, 140, billed).tool("run", "run", tests("failed"))
        .entry("boundary", "eviction age at turn 3: 4 stubbed · 0 trimmed · 0 losses")
        .turn(4).request(4_500, 3_000, 160, billed).gate("reserve").entry("call", "turn 4", edit("e2"))
        .turn(5).request(8_500, 0, 180, billed).tool("run", "run", tests("passed")).dropped("a.js")
        .turn(6).request(4_100, 5_000, 200, billed).tool("run", "poll", "⟦result #2 tool=run class=W truncated=no effects=unknown status=running⟧")
        .turn(7).request(6_100, 4_000, 220, billed)
        .turn(8).request(1_100, 10_000, 240, billed)

    private fun audit(log: AuditLog, catalog: Catalog = Catalog.EMPTY, result: JsonObject? = null): RunAudit =
        Audit.run(listOf(AuditInput("run", "arm", log.journal(), result)), catalog, Defaults().copy(k = 3)).runs.single()

    private fun money(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(assertNotNull(actual)), "$expected vs $actual")

    @Test fun `billed calls split the cost by class and reasoning stays inside output`() {
        val a = audit(run(JournalFormat.Studio)).anatomy
        assertEquals(MoneyBasis.Billed, a.basis)
        assertEquals(31_500L, a.uncachedInput.tokens)
        assertEquals(32_800L, a.cacheRead.tokens)
        assertNull(a.cacheWrite.tokens)
        assertEquals(1_360L, a.output.tokens)
        assertEquals(680L, a.reasoning.tokens)
        money("0.0375", a.total)
        money("0.0315", a.uncachedInput.money)
        money("0.00328", a.cacheRead.money)
        money("0.00272", a.output.money)
        money("0.00136", a.reasoning.money)
        money("0", a.unexplained)
        assertEquals(1.0, a.uncachedInput.share!! + a.cacheRead.share!! + a.output.share!!, 1e-12)
        assertTrue(a.prices.single().startsWith("billed fit"))
    }

    @Test fun `without a bill the catalog's list prices give an estimate`() {
        val catalog = Catalog.parse("""{"models": [{"provider": "openrouter", "id": "m/one", "prices": {"currency": "USD", "input": 1, "output": 2, "cacheRead": 0.1}}]}""")
        val a = audit(run(JournalFormat.Bus, billed = false), catalog).anatomy
        assertEquals(MoneyBasis.Estimate, a.basis)
        assertNull(a.billed)
        money("0.0375", a.estimate)
        assertTrue(a.prices.single().startsWith("catalog"))
        assertEquals(MoneyBasis.None, audit(run(JournalFormat.Bus, billed = false)).anatomy.basis)
    }

    @Test fun `cache share, q hat and breaks by cause`() {
        val cache = audit(run(JournalFormat.Studio)).cache
        assertEquals(32_800.0 / 64_300, cache.hitShare!!, 1e-12)
        assertEquals(4, cache.eligibleSteps)
        assertEquals(24_800.0 / 29_800, cache.qHat!!, 1e-12)
        assertEquals(1.0, cache.reliability)
        assertEquals(
            listOf(4 to BreakCause.Eviction, 5 to BreakCause.Mask, 6 to BreakCause.ImmediateStub, 7 to BreakCause.Provider),
            cache.breaks.map { it.turn to it.cause },
        )
        assertEquals(listOf(3_900L, 7_400L, 3_400L, 5_000L), cache.breaks.map { it.step.shortfall })
    }

    @Test fun `every loss is measured from the Studio journal`() {
        val w = audit(run(JournalFormat.Studio)).wastes.associateBy { it.waste }
        val tail = w.getValue(Waste.TailAfterResult)
        assertEquals(3, tail.calls)
        assertTrue(tail.detail!!.contains("run tests green at turn 5"))
        money(listOf(bill(4_100, 5_000, 200), bill(6_100, 4_000, 220), bill(1_100, 10_000, 240)).sumOf(::BigDecimal).toPlainString(), tail.money)
        money(bill(4_100, 5_000, 200), w.getValue(Waste.BackgroundPolls).money)
        money("0.00351", w.getValue(Waste.CadenceEviction).money)
        money("0.00666", w.getValue(Waste.PrefixMiss).money)
        money("0.00306", w.getValue(Waste.ImmediateStubs).money)
        money("0.0008", w.getValue(Waste.AnchorTail).money)
        val bodies = w.getValue(Waste.WrittenBodies)
        assertTrue(bodies.money!!.signum() > 0)
        assertTrue(bodies.detail!!.contains("a.html ×2"))
        assertEquals(bodies.money!!.toDouble() / 0.0375, bodies.share!!, 1e-9)
    }

    @Test fun `what the bus cannot show is unmeasured with a reason`() {
        val w = audit(run(JournalFormat.Bus)).wastes.associateBy { it.waste }
        assertNull(w.getValue(Waste.WrittenBodies).money)
        assertNotNull(w.getValue(Waste.WrittenBodies).unmeasured)
        // No batch is logged on the bus: the k = 3 cadence explains turn 6's break, and cannot stub anything at turn 3.
        assertEquals(
            listOf(4 to BreakCause.Provider, 5 to BreakCause.Mask, 6 to BreakCause.ImmediateStub, 7 to BreakCause.Eviction),
            audit(run(JournalFormat.Bus)).cache.breaks.map { it.turn to it.cause },
        )
        val quiet = audit(AuditLog(JournalFormat.Bus).turn(1).call(100, 0, 10, billed = "0.0001", anchor = null).turn(2).call(10, 100, 10, billed = "0.0001", anchor = null))
            .wastes.associateBy { it.waste }
        assertNotNull(quiet.getValue(Waste.TailAfterResult).unmeasured)
        assertNotNull(quiet.getValue(Waste.AnchorTail).unmeasured)
        val unanswered = audit(run(JournalFormat.Bus).turn(9).call(1, 1, 1, respond = false)).wastes.associateBy { it.waste }
        assertEquals("1 of its 4 calls have no cost: no usage or charge reported", unanswered.getValue(Waste.TailAfterResult).unmeasured)
    }

    @Test fun `provenance names who required, checked and accepted`() {
        val studio = run(JournalFormat.Studio)
            .event("studio.user_message", mapOf("role" to "request", "text" to "x"), context = null)
            .event("contract.amended", mapOf("version" to 2, "by" to "host: verification setup (review)"), context = null)
            .event("studio.verification", mapOf("kind" to "review", "source" to "none"), context = null)
            .gate("acceptance-surface", "acceptance surface: tests/test_a.py · modified")
            .tool("edit", "anchored", "⟦result #5 tool=edit class=W v={src/a.py: 1a2b, tests/test_a.py: 3c4d} truncated=no effects=observed status=ok⟧")
            .entry("boundary", "acceptance decision d1: accept by studio:policy(auto) (policy): not verified: AC-1")
            .finished("completed", mapOf("provenance" to "axis"))
        val p = audit(studio).provenance
        assertEquals(listOf("user request", "amended v2 by host: verification setup (review)"), p.requirements)
        assertEquals("review (none)", p.verification)
        assertEquals("run tests (model): failed×2, passed×2", p.checks.single())
        assertEquals("accept by studio:policy(auto) (policy) (not verified)", p.acceptance)
        assertEquals(true, p.acceptUnverified)
        assertEquals(listOf("acceptance surface: tests/test_a.py · modified"), p.acceptanceSurface)
        assertEquals(listOf("tests/test_a.py"), p.testEdits)
        assertEquals(JsonPrimitive("axis"), p.extra["campaign.finished.provenance"])

        val result = AuditLog.json(mapOf(
            "outcome" to "completed", "verification" to mapOf("kind" to "tests", "source" to "declared", "commands" to listOf(listOf("pytest"))),
            "policyDecisions" to listOf(mapOf("kind" to "acceptance", "outcome" to "accepted")),
            "acceptance" to mapOf("passed" to true, "exitCode" to 0, "timedOut" to false), "origin" to "new axis",
        )) as JsonObject
        val bus = audit(run(JournalFormat.Bus).finished("completed"), result = result).provenance
        assertEquals("tests (declared): pytest", bus.verification)
        assertEquals(true, bus.acceptUnverified)
        assertEquals("passed (exit 0)", bus.external)
        assertEquals(JsonPrimitive("new axis"), bus.extra["result.origin"])
        assertEquals(false, audit(run(JournalFormat.Bus).finished("completed")).provenance.acceptUnverified)
    }
}
