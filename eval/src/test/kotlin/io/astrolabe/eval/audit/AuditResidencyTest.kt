package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditResidencyTest {
    private fun bill(u: Long, c: Long, o: Long): String = (BigDecimal(u).movePointLeft(6) + BigDecimal(c).movePointLeft(7) + BigDecimal(2 * o).movePointLeft(6)).toPlainString()

    /**
     * Ten turns of a k = 2 cadence: each turn adds a 100-token message and a 1000-token read; from turn 4 every even
     * turn stubs the results two turns old (a stub is about 20 tokens), and the next request misses from the first one.
     */
    private fun cadence(): Pair<AuditLog, Long> {
        val base = 5_000L
        val message = 100L
        val stubbed = BooleanArray(11)
        fun size(s: Int) = message + if (stubbed[s]) 20 else 1_000
        val log = AuditLog(JournalFormat.Studio)
        var input = base + 100
        log.turn(1).call(input, 0, message, reasoning = 0, billed = bill(input, 0, message))
            .tool("look", "read", "⟦result #1 tool=look class=R v={a.js: 1593} truncated=no effects=none status=ok⟧")
        var previous = base
        var inputs = input
        for (t in 1..9) {
            var first = -1
            if (t % 2 == 0 && t >= 4) {
                for (s in 1..t - 2) if (!stubbed[s]) { if (first < 0) first = s; stubbed[s] = true }
                log.entry("boundary", "eviction age at turn $t: 2 stubbed · 0 trimmed · 0 losses", at = t)
            }
            val content = base + (1..t).sumOf(::size)
            val cached = if (first > 0) base + (1 until first).sumOf(::size) + message else previous
            input = content + 100
            log.turn(t + 1).call(input - cached, cached, message, reasoning = 0, billed = bill(input - cached, cached, message))
                .tool("look", "read", "⟦result #${t + 1} tool=look class=R v={a.js: 1593} truncated=no effects=none status=ok⟧")
            previous = content
            inputs += input
        }
        return log to inputs
    }

    @Test fun `the observed cadence replays the observed requests and other cadences are priced on them`() {
        val (log, inputs) = cadence()
        val what = Audit.run(listOf(AuditInput("run", "arm", log.journal(), null)), Catalog.EMPTY, Defaults().copy(k = 2)).runs.single().residency
        assertEquals(null, what.unmeasured)
        assertEquals(listOf(4, 6, 8), what.loggedBatches)
        val (observed, slow, off) = what.scenarios
        assertEquals(listOf("k=2", "k=17", "off"), what.scenarios.map { it.label })
        assertEquals(listOf(4, 6, 8), observed.batches)
        assertEquals(inputs, observed.inputTokens)
        assertTrue(abs(what.modelError!!.toDouble()) < 0.01 * what.observedCost!!.toDouble(), "error ${what.modelError} of ${what.observedCost}")
        // Without a cadence nothing reaches R_max here: no batch, no re-paid prefix, and every result is carried.
        for (s in listOf(slow, off)) {
            assertEquals(emptyList(), s.batches)
            assertEquals(0L, s.repaidTokens)
            assertTrue(s.inputTokens > observed.inputTokens)
            assertEquals(0, s.low!!.add(s.high!!).subtract(s.cost!!.multiply(BigDecimal.TWO)).signum())
        }
    }

    @Test fun `a run without two consecutive priced calls is not replayed`() {
        val single = AuditLog(JournalFormat.Bus).turn(1).call(100, 0, 10, billed = "0.0001")
        val what = Audit.run(listOf(AuditInput("run", "arm", single.journal(), null))).runs.single().residency
        assertTrue(what.scenarios.isEmpty())
        assertEquals("no lineage with two consecutive calls of known usage", what.unmeasured)
        val unpriced = AuditLog(JournalFormat.Bus).turn(1).call(100, 0, 10).turn(2).call(10, 100, 10)
        assertTrue(Audit.run(listOf(AuditInput("run", "arm", unpriced.journal(), null))).runs.single().residency.unmeasured!!.startsWith("unpriced"))
    }
}
