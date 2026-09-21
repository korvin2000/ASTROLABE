package io.astrolabe.cell

import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reserve
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.register.Register
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.Effects
import io.astrolabe.tool.EnvelopeHeader
import io.astrolabe.tool.RuntimeFields
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.Ranges
import io.astrolabe.workset.Workset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P1.8.4: the ~20-token gauge on every result and after the turn (§5.7). */
class GaugesTest {

    private val estimator = HeuristicEstimator()
    private val version = FileVersion(Digest("a9f10b2c".repeat(8)))

    private fun workset(): Workset = Workset().apply {
        register(Entry("src/router.py", Ranges.single(80, 96), version, EntrySource.Look, turn = 3, resultId = "#17", tokens = 1_400))
        register(Entry("src/handlers/user.py", Ranges.single(30, 60), version, EntrySource.Look, turn = 4, resultId = "#21", tokens = 1_200))
    }

    private fun budget(): CellBudget.Snapshot =
        CellBudget(Tokens(100_000), 40, Reserve.cell(Tokens(100_000), 40, Reserves())).snapshot()

    private fun register() = Register(15, ContextId("ctx-1"), "I2", "thread ctx through handlers")

    @Test
    fun `every number comes from the component that owns it`() {
        val gauge = Gauges.of(38, budget(), "@d1e7: types ✓ · tests red", workset(), register(), turn = 14, turnsMax = 40)

        assertEquals(38, gauge.contextPercent)
        assertTrue(gauge.reserveOk)
        assertEquals(2, gauge.knownFiles)
        assertEquals(2_600, gauge.knownTokens)
        assertEquals(15, gauge.stateVersion)
        assertEquals(14, gauge.turn)
        assertEquals(
            "⟨ctx 38% · reserve ok · checks @d1e7: types ✓ · tests red · known 2/2.6K · STATE v15 · turn 14/40⟩",
            gauge.line(),
        )
    }

    @Test
    fun `the gauge is one short line whose only variable part is the checks summary`() {
        val summary = "@d1e7: types ✓ · tests red"
        val short = Gauges.of(38, budget(), "none", workset(), register(), 14, 40).line()
        val long = Gauges.of(38, budget(), summary, workset(), register(), 14, 40).line()

        assertEquals(1, short.lineSequence().count(), "the gauge is a glance, not a report")
        // §17's "~20 tokens" is a real tokenizer's count; the byte heuristic over-counts this
        // symbol-dense line, so the bound is generous on purpose. What it pins is that the line
        // cannot grow without bound: only the checks summary varies, and the checker caps that.
        assertTrue(estimator.estimate(short).tokens <= 30, "${estimator.estimate(short).tokens} tokens: $short")
        assertEquals(short.length - "none".length + summary.length, long.length)
    }

    @Test
    fun `a checks summary is never blank, because "none" and "unknown" are different answers`() {
        assertEquals("none", Gauges.of(0, budget(), "  ", workset(), register(), 1, 40).checks)
    }

    @Test
    fun `every result carries the gauge, with or without an envelope header`() {
        val gauge = Gauges.of(38, budget(), "none", workset(), register(), 14, 40)
        val header = EnvelopeHeader(
            resultAlias = "#17",
            tool = "look",
            effectClass = EffectClass.R,
            versions = mapOf("src/router.py" to version),
            stamp = null,
            truncated = false,
            effects = Effects.None,
            runtime = RuntimeFields("a-1", "ok", null, null, null, "complete"),
        )

        val enveloped = Gauges.result(ToolOutcome("1 def route(...)", header), gauge)
        assertTrue(enveloped.endsWith(gauge.line()), enveloped)
        assertTrue(enveloped.contains("1 def route(...)"), enveloped)

        val bare = Gauges.result(ToolOutcome("refused: unknown op"), gauge)
        assertEquals("refused: unknown op\n" + gauge.line(), bare)

        assertEquals(gauge.line(), Gauges.result(ToolOutcome(""), gauge), "even an empty result shows where the cell stands")
    }

    @Test
    fun `an out-of-range occupancy is a programming error, not a rendered lie`() {
        assertTrue(runCatching { Gauges.of(101, budget(), "none", workset(), register(), 1, 40) }.isFailure)
        assertTrue(runCatching { Gauges.of(-1, budget(), "none", workset(), register(), 1, 40) }.isFailure)
    }
}
