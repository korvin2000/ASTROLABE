package io.astrolabe.eval.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C2's provenance class in the audit: read from `campaign.finished`, `null` on older logs, and its shares per group. */
class AuditProvenanceTest {
    private fun log(format: JournalFormat, provenanceClass: String?): AuditLog = AuditLog(format)
        .turn(1).call(1_000, 0, 100, billed = "0.0012")
        .finished("completed", listOfNotNull(provenanceClass?.let { "provenanceClass" to it }).toMap())

    private fun audit(vararg runs: AuditLog): AuditReport =
        Audit.run(runs.mapIndexed { i, run -> AuditInput("run-$i", "arm", run.journal(), null) })

    @Test fun `the finish's class is read from either layout and an older log has none`() {
        val report = audit(log(JournalFormat.Bus, "agent_test"), log(JournalFormat.Studio, "independent"), log(JournalFormat.Bus, null))
        assertEquals(listOf("agent_test", "independent", null), report.runs.map { it.provenance.provenanceClass })
        assertTrue(report.runs.none { "campaign.finished.provenanceClass" in it.provenance.extra }, "a read field is no longer extra")
    }

    @Test fun `a group gives each class's share of its runs, and none while a run's class is unknown`() {
        val known = audit(log(JournalFormat.Bus, "independent"), log(JournalFormat.Bus, "agent_test"), log(JournalFormat.Bus, "agent_test"), log(JournalFormat.Bus, "unverified"))
            .groups.single().provenanceClasses
        assertEquals(listOf(1, 2, 1, 0), listOf(known.independent, known.agentTest, known.unverified, known.unknown))
        assertEquals(listOf(0.25, 0.5, 0.25), listOf(known.independentShare, known.agentTestShare, known.unverifiedShare))

        val mixed = audit(log(JournalFormat.Bus, "independent"), log(JournalFormat.Bus, null)).groups.single().provenanceClasses
        assertEquals(listOf(1, 0, 0, 1), listOf(mixed.independent, mixed.agentTest, mixed.unverified, mixed.unknown))
        assertNull(mixed.independentShare, "a share over runs of unknown class would be a guess")
        assertTrue("| arm · openrouter/m/one | 2 | 1 (—) | 0 (—) | 0 (—) | 1 |" in AuditMarkdown.render(audit(log(JournalFormat.Bus, "independent"), log(JournalFormat.Bus, null))))
    }
}
