package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditResidencyTest {
    private fun bill(u: Long, c: Long, o: Long): String = (BigDecimal(u).movePointLeft(6) + BigDecimal(c).movePointLeft(7) + BigDecimal(2 * o).movePointLeft(6)).toPlainString()

    private fun read(n: Int) = "⟦result #$n tool=look class=R v={a.js: 1593} truncated=no effects=none status=ok⟧"

    private val state = "⟦result #- tool=state class=R truncated=no effects=none status=ok⟧"

    /**
     * Ten turns of a k = 2 cadence: each turn adds a 100-token message, a 1000-token read and, [withState], a 40-token
     * state line no alias names; from turn 4 every even turn stubs the results two turns old (a stub is about 20 tokens,
     * a state line a loss), and the next request misses from the first one.
     */
    private fun cadence(withState: Boolean): Pair<AuditLog, Long> {
        val base = 5_000L
        val message = 100L
        val stubbed = BooleanArray(11)
        fun size(s: Int) = message + (if (stubbed[s]) 20 else 1_000) + (if (!withState) 0 else if (stubbed[s]) 20 else 40)
        val log = AuditLog(JournalFormat.Studio)
        fun results(t: Int) { log.tool("look", "read", read(t)); if (withState) log.tool("state", "patch", state) }
        var input = base + 100
        log.turn(1).call(input, 0, message, reasoning = 0, billed = bill(input, 0, message))
        results(1)
        var previous = base
        var inputs = input
        for (t in 1..9) {
            var first = -1
            if (t % 2 == 0 && t >= 4) {
                for (s in 1..t - 2) if (!stubbed[s]) { if (first < 0) first = s; stubbed[s] = true }
                val each = if (withState) 2 else 1
                log.entry("boundary", "eviction age at turn $t: ${2 * each} stubbed · 0 trimmed · ${2 * (each - 1)} losses", at = t)
            }
            val content = base + (1..t).sumOf(::size)
            val cached = if (first > 0) base + (1 until first).sumOf(::size) + message else previous
            input = content + 100
            log.turn(t + 1).call(input - cached, cached, message, reasoning = 0, billed = bill(input - cached, cached, message))
            results(t + 1)
            previous = content
            inputs += input
        }
        return log to inputs
    }

    private fun whatIf(log: AuditLog, defaults: Defaults): ResidencyWhatIf =
        Audit.run(listOf(AuditInput("run", "arm", log.journal(), null)), Catalog.EMPTY, defaults).runs.single().residency

    @Test fun `the observed cadence replays the observed requests and other cadences are priced on them`() {
        val (log, inputs) = cadence(withState = false)
        val what = whatIf(log, Defaults().copy(k = 2))
        assertEquals(null, what.unmeasured)
        assertEquals(listOf(4, 6, 8), what.loggedBatches!!.map { it.turn })
        val (observed, slow, off) = what.scenarios
        assertEquals(listOf("k=2", "k=17", "off"), what.scenarios.map { it.label })
        assertEquals(what.loggedBatches!!.map { Triple(it.turn, it.stubbed, it.losses) }, observed.batches.map { Triple(it.turn, it.stubbed, it.losses) })
        assertEquals(inputs, observed.inputTokens)
        assertTrue(abs(what.calibrationError!!.toDouble()) < 0.01 * what.observedCost!!.toDouble(), "error ${what.calibrationError} of ${what.observedCost}")
        // Without a cadence nothing reaches R_max here: no batch, no re-paid prefix, and every result is carried.
        for (s in listOf(slow, off)) {
            assertEquals(emptyList(), s.batches)
            assertEquals(0L, s.repaidTokens)
            assertTrue(s.inputTokens > observed.inputTokens)
        }
    }

    @Test fun `at the capacity bound only recoverable results are evicted and unrecoverable ones are losses of age`() {
        val (log, _) = cadence(withState = true)
        // k = 2 keeps at most three turns of results (3120 tokens) live; without the cadence R_max = 3500 is reached from turn 4.
        val what = whatIf(log, Defaults().copy(k = 2, rMaxTokens = 3_500))
        val (observed, _, off) = what.scenarios
        assertEquals(what.loggedBatches!!.map { Triple(it.turn, it.stubbed, it.losses) }, observed.batches.map { Triple(it.turn, it.stubbed, it.losses) })
        assertTrue(observed.batches.all { it.trigger == "age" && it.losses == 2 })
        assertTrue(off.batches.isNotEmpty())
        assertTrue(off.batches.all { it.trigger == "budget" && it.losses == 0 && it.stubbed == 1 }, "${off.batches}")
        assertTrue(off.repaidTokens > 0)
        assertTrue(abs(what.calibrationError!!.toDouble()) < 0.02 * what.observedCost!!.toDouble(), "error ${what.calibrationError} of ${what.observedCost}")
    }

    @Test fun `an observed budget eviction the turn's own results triggered is replayed by the observed cadence`() {
        // No cadence within the run (k = 100); R_max = 3500 is crossed by each turn's new 1000-token read from turn 4 on,
        // and the bound stubs the oldest read; the next request misses from it.
        val base = 5_000L
        val stubbed = BooleanArray(11)
        fun size(s: Int) = 100L + if (stubbed[s]) 20 else 1_000
        val log = AuditLog(JournalFormat.Studio)
        var input = base + 100
        log.turn(1).call(input, 0, 100, reasoning = 0, billed = bill(input, 0, 100)).tool("look", "read", read(1))
        var previous = base
        for (t in 1..9) {
            var first = -1
            if (t >= 4) {
                first = t - 3
                stubbed[first] = true
                log.entry("boundary", "eviction budget at turn $t: 1 stubbed · 0 trimmed · 0 losses", at = t)
            }
            val content = base + (1..t).sumOf(::size)
            val cached = if (first > 0) base + (1 until first).sumOf(::size) + 100 else previous
            input = content + 100
            log.turn(t + 1).call(input - cached, cached, 100, reasoning = 0, billed = bill(input - cached, cached, 100)).tool("look", "read", read(t + 1))
            previous = content
        }
        val what = whatIf(log, Defaults().copy(k = 100, rMaxTokens = 3_500))
        val observed = what.scenarios.first()
        assertEquals((4..9).map { Triple(it, 1, 0) }, what.loggedBatches!!.map { Triple(it.turn, it.stubbed, it.losses) })
        assertEquals(what.loggedBatches!!.map { Triple(it.turn, it.stubbed, it.losses) }, observed.batches.map { Triple(it.turn, it.stubbed, it.losses) })
        assertTrue(observed.batches.all { it.trigger == "budget" })
        assertTrue(abs(what.calibrationError!!.toDouble()) < 0.02 * what.observedCost!!.toDouble(), "error ${what.calibrationError} of ${what.observedCost}")
    }

    @Test fun `a scenario that sends less than the observed cache write caps the write`() {
        // The provider wrote almost every input token to cache; k = 2 frees reads the observed run kept, so its later
        // requests are smaller than those writes.
        val u = listOf(101L, 205, 98, 310, 150, 222, 119, 260)
        val c = listOf(64L, 0, 640, 128, 1_024, 256, 512, 896)
        val o = listOf(110L, 95, 230, 150, 180, 120, 210, 160)
        val log = AuditLog(JournalFormat.Bus)
        for (j in 0 until 8) {
            val input = 5_100L + j * 1_100
            val write = input - u[j] - c[j]
            val billed = (BigDecimal(u[j]).movePointLeft(6) + BigDecimal(c[j]).movePointLeft(7) + BigDecimal(write).multiply(BigDecimal("1.25")).movePointLeft(6) +
                BigDecimal(2 * o[j]).movePointLeft(6)).toPlainString()
            log.turn(j + 1).call(u[j], c[j], o[j], reasoning = 0, billed = billed, write = write).tool("look", "read", read(j + 1))
        }
        val trace = RunTrace.of(log.journal())
        val book = PriceBook.of(trace.calls, Catalog.EMPTY)
        val defaults = Defaults().copy(k = 100)
        val what = ResidencyReplay.of(trace, Losses(trace, JournalFormat.Bus, book, defaults.k), book, JournalFormat.Bus, defaults, listOf(100, 2))
        assertEquals(null, what.unmeasured)
        val (observed, eager) = what.scenarios
        assertTrue(eager.batches.isNotEmpty())
        assertTrue(eager.inputTokens < observed.inputTokens)
        assertTrue(eager.cost!!.signum() > 0)
    }

    @Test fun `results no alias names stay small even when a turn has nothing else`() {
        // Each turn: a 100-token message and two state lines, growing the request by 1040 tokens; k = 2 stubs them as losses.
        val log = AuditLog(JournalFormat.Bus)
        for (j in 0 until 8) {
            val input = 5_100L + j * 1_040
            val cached = if (j == 0) 0L else 5_000L + (j - 1) * 1_040
            log.turn(j + 1).call(input - cached, cached, 100, reasoning = 0, billed = bill(input - cached, cached, 100))
                .tool("state", "patch", state).tool("state", "patch", state)
        }
        val batch = whatIf(log, Defaults().copy(k = 2)).scenarios.first().batches.first { it.turn == 4 }
        assertEquals(4 to 4, batch.stubbed to batch.losses)
        assertTrue(batch.freedTokens < 4 * ResidencyReplay.SMALL_RESULT_TOKENS, "${batch.freedTokens}")
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
