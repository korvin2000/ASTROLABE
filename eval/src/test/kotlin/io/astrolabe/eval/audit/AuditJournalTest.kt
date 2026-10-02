package io.astrolabe.eval.audit

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuditJournalTest {
    private fun log(format: JournalFormat) = AuditLog(format)
        .turn(1).call(6_131, 0, 749, reasoning = 469, billed = "0.00127403793", upstream = "Relace")
        .tool("look", "read", "⟦result #1 tool=look class=R v={a.js: 1593} truncated=no effects=none status=ok⟧")
        .turn(2).call(13_302, 4_096, 1_161, reasoning = 912, billed = "0.002562258204", upstream = "Relace")

    @Test fun `both layouts give the same calls and turns`() {
        val bus = RunTrace.of(log(JournalFormat.Bus).journal())
        val studio = RunTrace.of(log(JournalFormat.Studio).journal())
        assertEquals(JournalFormat.Bus, log(JournalFormat.Bus).journal().format)
        assertEquals(JournalFormat.Studio, log(JournalFormat.Studio).journal().format)
        assertEquals(bus.calls, studio.calls)
        val first = bus.calls.first()
        assertEquals(CallUsage(6_131, 0, 0, 749, 469, setOf(PriceClass.CacheWrite)), first.usage)
        assertEquals(1, first.turn)
        assertEquals(100L, first.anchorTokens)
        assertEquals(Binding("openrouter", "m/one", "Relace"), first.binding)
        assertEquals(0, BigDecimal("0.00127403793").compareTo(first.billed))
        assertEquals(listOf("look.read"), bus.at("cell-1", 1)!!.ops)
        assertEquals(listOf("a.js"), studio.at("cell-1", 1)!!.outcomes.single().paths)
    }

    @Test fun `what a response did not report stays null, never zero`() {
        val trace = RunTrace.of(AuditLog(JournalFormat.Bus).turn(1).call(500, null, 20).turn(2).call(1, 1, 1, respond = false).journal())
        val (partial, unanswered) = trace.calls
        assertNull(partial.usage!!.cacheRead)
        assertNull(partial.usage!!.input)
        assertEquals(setOf(PriceClass.CacheWrite), partial.usage!!.absent)
        assertNull(partial.usage!!.reasoning)
        assertNull(partial.billed)
        assertNull(unanswered.usage)
        assertEquals("no response", unanswered.failure)
        val unread = Journal.parse(listOf("{}", "not json", ""))
        assertEquals(2, unread.unreadableLines)
    }

    @Test fun `an old journal bills from OpenRouter's native cost and no other provider's`() {
        val trace = RunTrace.of(AuditLog(JournalFormat.Studio)
            .turn(1).call(100, 0, 10, nativeCost = "0.0001")
            .turn(2).call(100, 0, 10, nativeCost = "0.0001", provider = "other").journal())
        assertEquals(0, BigDecimal("0.0001").compareTo(trace.calls[0].billed))
        assertEquals("USD", trace.calls[0].billedCurrency)
        assertNull(trace.calls[1].billed)
    }

    @Test fun `the journal links commands to results and dates eviction batches`() {
        val args = """{"argv": ["node", "scripts/smoke.js"], "cwd": ".", "intent": "smoke"}"""
        val pollArgs = """{"op": "poll", "handle": "h"}"""
        val trace = RunTrace.of(AuditLog(JournalFormat.Studio)
            .turn(3).call(100, 0, 10)
            .entry("call", "turn 3 model output", AuditLog.calls(Triple("c1", "run", args), Triple("c2", "run", pollArgs)))
            .entry("result", "call c1: ⟦result #9 tool=run class=W stamp=cc3a truncated=no effects=unknown status=failed⟧")
            .entry("result", "call c2: ⟦result #8 tool=run class=W truncated=no effects=unknown status=running⟧")
            .entry("result", "call c3: schema error in call c3: unexpected token")
            .entry("result", "⟦not executed: the edit batch did not apply fully⟧")
            .entry("boundary", "eviction age at turn 16: 19 stubbed · 0 trimmed · 4 losses", at = 16).journal())
        val (run, poll, schema, skipped) = trace.at("cell-1", 3)!!.outcomes
        assertEquals(ToolOutcome("run", "failed", emptyList(), "node scripts/smoke.js", "run", alias = "#9"), run)
        assertEquals("poll", poll.op)
        assertNull(poll.command)
        // The journal's error results enter [T] too, with no alias to recall them by; the bus never emits them.
        assertEquals(ToolOutcome("?", "error", emptyList()), schema)
        assertEquals(ToolOutcome("?", "not-executed", emptyList()), skipped)
        assertEquals(EvictionRecord("age", 19, 0, 4), trace.at("cell-1", 16)!!.eviction)
        assertEquals((args.length + pollArgs.length).toLong(), trace.at("cell-1", 3)!!.visibleChars)
    }
}
