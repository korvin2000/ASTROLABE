package io.astrolabe.verify

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import kotlin.test.Test
import kotlin.test.assertEquals

/** P3.1.4: the §8.1 layer table's scheduled rows select their checks and name what they cannot test. */
class LayersTest {
    private val cmd = Command(listOf("pytest", "-q"))

    private fun check(id: String, kind: CheckKind, trigger: Trigger, vararg accept: String) =
        Check(id, kind, Selector.Named(cmd), Closure.Known(setOf("src/a.py")), CostClass.Fast, trigger, acceptanceIds = accept.toList(), command = cmd)

    private fun registry(vararg checks: Check): Checks = Checks.empty().also { r -> checks.forEach { r.register(it) } }

    @Test
    fun `a step move runs the step accept and blast checks that are due, and names the missing blast and unknown accepts`() {
        val step = check("CHK-accept-AC-1", CheckKind.Acceptance, Trigger.IncrementEnd, "AC-1")
        val full = check(Checks.FULL, CheckKind.Full, Trigger.CampaignEnd, "AC-1")
        val checks = registry(step, full)

        val selection = Layers.select(Layer.BlastAndStepAccept, checks, listOf("AC-1", "run: make lint")) { true }
        assertEquals(listOf("CHK-accept-AC-1"), selection.run.map { it.id }, "the full suite never runs at a step boundary")
        assertEquals(listOf("accept run: make lint: no registered check", Layers.NO_BLAST), selection.notTested)

        assertEquals(emptyList(), Layers.select(Layer.BlastAndStepAccept, checks, listOf("AC-1")) { false }.run, "a current receipt stands")

        val blast = check(Checks.TESTS_BLAST, CheckKind.Unit, Trigger.StepBoundary)
        val withBlast = Layers.select(Layer.BlastAndStepAccept, registry(blast, step), listOf("AC-1")) { true }
        assertEquals(listOf(Checks.TESTS_BLAST, "CHK-accept-AC-1"), withBlast.run.map { it.id })
        assertEquals(emptyList(), withBlast.notTested)
    }

    @Test
    fun `increment end runs the acceptance only, campaign end the suite and the quality gates`() {
        val accept = check("CHK-accept-AC-1", CheckKind.Acceptance, Trigger.IncrementEnd, "AC-1")
        val full = check(Checks.FULL, CheckKind.Full, Trigger.CampaignEnd, "AC-1")
        val gate = check(Checks.QUALITY_GATE, CheckKind.Quality, Trigger.CampaignEnd)

        assertEquals(listOf("CHK-accept-AC-1"), Layers.select(Layer.IncrementAcceptance, registry(accept, full), listOf("AC-1")) { true }.run.map { it.id })

        val end = Layers.select(Layer.FullSuiteAndQuality, registry(accept, full, gate), emptyList()) { true }
        assertEquals(listOf(Checks.FULL, Checks.QUALITY_GATE), end.run.map { it.id })
        assertEquals(emptyList(), end.notTested)

        val bare = Layers.select(Layer.FullSuiteAndQuality, registry(accept), emptyList()) { true }
        assertEquals(emptyList(), bare.run)
        assertEquals(listOf("full suite: none declared by the repository", "quality gates: none configured"), bare.notTested)
    }
}
