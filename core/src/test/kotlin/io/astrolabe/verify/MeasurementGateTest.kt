package io.astrolabe.verify

import io.astrolabe.contract.Command
import io.astrolabe.id.Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P5.3.2: the L4 measurement gate contract — project-defined commands seed `quality` checks, and a claim needs a matching artifact. */
class MeasurementGateTest {
    private val command = MeasurementCommand(Command(listOf("bench", "run")), workload = "1k requests replayed from prod trace", environment = "ci runner, 4 vCPU")

    @Test
    fun `seed turns project-defined measurement commands into on-demand quality checks, never invented`() {
        assertEquals(emptyList(), MeasurementGate.seed(emptyList()), "no gate exists without a project command")
        val checks = MeasurementGate.seed(listOf(command, command.copy(workload = "cold start")), names = listOf("bench", "cold-start"))
        assertEquals(listOf("CHK-l4-measurement-bench", "CHK-l4-measurement-cold-start"), checks.map { it.id })
        checks.forEach { check ->
            assertEquals(CheckKind.Quality, check.kind)
            assertEquals(Trigger.OnDemand, check.trigger, "an L4 gate needs a disposable environment; it is never scheduled automatically (P5.3)")
            assertEquals(CostClass.Expensive, check.costClass)
            assertEquals(command.command, check.command)
        }
        val defaultNamed = MeasurementGate.seed(listOf(command, command))
        assertEquals(listOf("CHK-l4-measurement-1", "CHK-l4-measurement-2"), defaultNamed.map { it.id })
    }

    @Test
    fun `a performance, agent-behaviour or safety claim needs an artifact naming its own check and receipt`() {
        val artifact = MeasurementArtifact(
            checkId = "CHK-l4-measurement-bench", receiptId = "rcpt-9", workload = command.workload, environment = command.environment,
            variability = Variability(VarianceBasis.Repeated, runs = 5, spread = "±3%"), blob = Digest.ofUtf8("log"),
        )
        assertEquals(emptyList(), MeasurementGate.validate(MeasurementClaim.Performance, "CHK-l4-measurement-bench", "rcpt-9", artifact))
        val noArtifact = MeasurementGate.validate(MeasurementClaim.Safety, "CHK-l4-measurement-bench", "rcpt-9", null)
        assertTrue(noArtifact.single().contains("workload, environment and variability"), noArtifact.toString())
        val wrongCheck = MeasurementGate.validate(MeasurementClaim.AgentBehaviour, "CHK-l4-measurement-other", "rcpt-9", artifact)
        assertTrue(wrongCheck.any { "not CHK-l4-measurement-other" in it }, wrongCheck.toString())
        val wrongReceipt = MeasurementGate.validate(MeasurementClaim.Performance, "CHK-l4-measurement-bench", "rcpt-1", artifact)
        assertTrue(wrongReceipt.any { "not rcpt-1" in it }, wrongReceipt.toString())
    }

    @Test
    fun `variability never invents a spread across runs it never took`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Variability(VarianceBasis.SingleRun, runs = 3) }
        val single = Variability(VarianceBasis.SingleRun, runs = 1)
        assertEquals(1, single.runs)
    }
}
