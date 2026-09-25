package io.astrolabe.route

import io.astrolabe.id.AttemptId
import io.astrolabe.recover.FailureClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EscalationTest {
    private val initial = SubstantiveAttempt(AttemptId("att-1"), Tier.Low, "helper", "totals round half-up")

    @Test fun `FX-31 a deceptively hard cheap task escalates with evidence or asks and never invents behaviour`() {
        val first = AttemptAllowance("inc-1", 2, listOf(initial))
        // Unverified: nothing escalates on the model's word.
        assertIs<EscalationStep.NotEscalated>(Escalation.next(first, null))

        // The business oracle is ambiguous: the user is asked; no expected behaviour is made up, no stronger model is tried.
        val ambiguous = VerifiedFailure(FailureClass.BehaviouralTestFailure, "AC2 expects both 10.05 and 10.04", listOf("rcpt-7"), question = "Which rounding does the invoice total use?")
        val ask = assertIs<EscalationStep.Ask>(Escalation.next(first, ambiguous))
        assertEquals("Which rounding does the invoice total use?", ask.question)
        assertEquals(listOf("rcpt-7"), ask.evidenceRefs)

        // A verified behavioural failure: N+1 (never below the function floor) with the evidence and a stated change.
        val failed = VerifiedFailure(FailureClass.BehaviouralTestFailure, "AC2 red: 10.04 != 10.05", listOf("rcpt-8"))
        val escalate = assertIs<EscalationStep.Escalate>(Escalation.next(first, failed))
        assertEquals(Tier.Medium, escalate.tier)
        assertEquals(EscalationChange.StrongerModel, escalate.change)
        assertEquals(listOf("rcpt-8"), escalate.evidenceRefs)
        assertTrue("rcpt-8" in escalate.line && "StrongerModel" in escalate.line)

        // Missing repository contract → probe evidence at the same tier; setup failure → a different tool.
        assertEquals(EscalationChange.ProbeEvidence, (Escalation.next(first, VerifiedFailure(FailureClass.MissingRepositoryContract, "caller unresolved", listOf("r"))) as EscalationStep.Escalate).change)
        assertEquals(EscalationChange.DifferentTool, (Escalation.next(first, VerifiedFailure(FailureClass.BuildEnvironment, "jdk missing", listOf("r"))) as EscalationStep.Escalate).change)
        // Cell-level classes are not escalations: they return to their handler through recover().
        assertIs<EscalationStep.NotEscalated>(Escalation.next(first, VerifiedFailure(FailureClass.StaleAnchor, "expect mismatch", listOf("r"))))

        // Repeating the same attempt under a new label is not recovery; a later attempt states its change.
        val relabelled = initial.copy(attempt = AttemptId("att-2"), change = EscalationChange.StrongerModel)
        assertTrue(Escalation.admit(first, relabelled)!!.contains("not recovery"))
        assertTrue(Escalation.admit(first, initial.copy(attempt = AttemptId("att-2"), tier = Tier.Medium, profile = "main"))!!.contains("states its change"))

        // The escalated attempt fails too: budget.attempts (2) is spent and a new attempt id does not replenish it.
        val second = first.plus(SubstantiveAttempt(AttemptId("att-2"), Tier.Medium, "main", "totals round half-up", EscalationChange.StrongerModel, listOf("rcpt-8")))
        assertEquals(0, second.remaining)
        val blocked = assertIs<EscalationStep.Blocked>(Escalation.next(second, failed))
        assertTrue("budget.attempts 2" in blocked.reason)
        val third = SubstantiveAttempt(AttemptId("att-3"), Tier.High, "escalation", "banker's rounding", EscalationChange.AlternativeAttempt)
        assertTrue(Escalation.admit(second, third)!!.contains("spent"))
        assertFailsWith<IllegalStateException> { second.plus(third) }
        assertNull(Escalation.admit(first, third))
    }
}
