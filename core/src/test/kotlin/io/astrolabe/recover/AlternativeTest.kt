package io.astrolabe.recover

import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.register.DeadEnd
import io.astrolabe.register.Decision
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.EscalationChange
import io.astrolabe.route.SubstantiveAttempt
import io.astrolabe.route.Tier
import io.astrolabe.route.VerifiedFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AlternativeTest {
    private val idGen = FixedIdGen()
    private val initial = SubstantiveAttempt(AttemptId("a1"), Tier.High, "main", "cache totals by invoice key", evidenceRefs = listOf("rcpt-1"))
    private val register = Register(
        3, ContextId("cell-2"), "I1", "invoice totals",
        deadEnds = listOf(DeadEnd(1, "cache keyed by invoice id", "rcpt-2", "I1", "if keys become stable")),
        decisions = listOf(Decision(1, "totals are computed per line", "AC-2 names line rounding", null)),
    )
    private fun red(n: Int) = VerifiedFailure(FailureClass.BehaviouralTestFailure, "AC-2 red after fix $n", listOf("rcpt-${n + 1}"))

    @Test fun `an alternative attempt preserves previous evidence and consumes the same increment allowance`() {
        val allowance = AttemptAllowance("I1", 2, listOf(initial))
        // No speculative branching: one failure is refined in place; a stale anchor is recovered by its cell.
        assertTrue(assertIs<AlternativeDecision.Refused>(Alternative.open(allowance, listOf(red(1)), register, emptyList(), 4, "compute per line", idGen)).reason.contains("refine in place"))
        assertIs<AlternativeDecision.Refused>(Alternative.open(allowance, listOf(red(1), VerifiedFailure(FailureClass.StaleAnchor, "expect mismatch", listOf("r"))), register, emptyList(), 4, "compute per line", idGen))

        val opened = assertIs<AlternativeDecision.Opened>(Alternative.open(allowance, listOf(red(1), red(2)), register, listOf("rcpt-2", "rcpt-3"), 4, "compute per line", idGen)).alternative
        // A new controller-assigned id for the same contract; STATE's dead ends and decisions attached; empty tail.
        assertNotEquals(initial.attempt, opened.attempt)
        assertEquals(initial.attempt, opened.previous)
        assertEquals(4, opened.contractVersion)
        assertEquals(register.deadEnds, opened.deadEnds)
        assertEquals(register.decisions, opened.decisions)
        assertEquals(0, opened.rebuild.tailTurns)
        assertEquals(EscalationChange.AlternativeAttempt, opened.substantive.change)
        assertEquals(Tier.ExtraHigh, opened.substantive.tier)
        // Both attempts' evidence is kept.
        assertEquals(listOf("rcpt-1", "rcpt-2", "rcpt-3"), opened.keptReceipts)
        // The same allowance: the alternative is attempt 2 of 2, and its new id does not replenish it.
        assertEquals(listOf(initial.attempt, opened.attempt), opened.allowance.attempts.map { it.attempt })
        assertEquals(0, opened.allowance.remaining)
        val again = Alternative.open(opened.allowance, listOf(red(3), red(4)), register, emptyList(), 4, "compute per invoice", idGen)
        assertTrue(assertIs<AlternativeDecision.Refused>(again).reason.contains("budget.attempts 2"))

        // Selection by acceptance evidence, never by plurality over text.
        val required = setOf("R1", "R2")
        val first = AttemptEvidence(initial.attempt, listOf("rcpt-1"), setOf("R1"), required, claim = "all tests pass")
        val echo = AttemptEvidence(AttemptId("a9"), listOf("rcpt-9"), setOf("R1"), required, claim = "all tests pass")
        val second = AttemptEvidence(opened.attempt, listOf("rcpt-4", "rcpt-5"), required, required, claim = "R2 still open?")
        assertEquals(opened.attempt, Alternative.select(listOf(first, echo, second))?.attempt)
        assertEquals(null, Alternative.select(listOf(first, echo)))
    }
}
