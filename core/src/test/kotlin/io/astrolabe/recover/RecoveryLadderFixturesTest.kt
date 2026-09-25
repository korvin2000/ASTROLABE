package io.astrolabe.recover

import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.Escalation
import io.astrolabe.route.EscalationChange
import io.astrolabe.route.EscalationStep
import io.astrolabe.route.SubstantiveAttempt
import io.astrolabe.route.Tier
import io.astrolabe.route.VerifiedFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * P4.8.2: one recovery-ladder fixture per §13.2 failure class (D-173). Each walks `recover` to its end, feeding back
 * what every step did (reconciled, retried and failed again, repaired and still failing), and checks the labelled
 * trajectory, its §13.1 bounds, the owning handler with the evidence and the conceal text, and what the P4.5.2
 * escalation map does with the class once it is a verified failure.
 */
class RecoveryLadderFixturesTest {
    private val ladder = Ladder()

    /** A labelled fixture: where the failure starts, the state reconciliation finds, and the expected walk and escalation. */
    private data class Case(
        val failureClass: FailureClass,
        val trajectory: List<String>,
        val escalation: EscalationChange?,
        val start: ExecutionState = ExecutionState.DurablyCompleted,
        val safeToRetry: Boolean = false,
        val reconciledTo: ExecutionState = ExecutionState.DurablyCompleted,
    )

    private val cases = listOf(
        Case(FailureClass.TransportRateLimit, listOf("reconcile", "retry 1", "retry 2", "return Adapter"), null, ExecutionState.Unknown, safeToRetry = true, reconciledTo = ExecutionState.Dispatched),
        Case(FailureClass.InvalidToolArguments, listOf("return Cell"), null),
        Case(FailureClass.StaleAnchor, listOf("return Cell"), null),
        Case(FailureClass.BuildEnvironment, listOf("repair", "return RepairHelper"), EscalationChange.DifferentTool),
        Case(FailureClass.BehaviouralTestFailure, listOf("return Cell"), EscalationChange.StrongerModel),
        Case(FailureClass.MissingRepositoryContract, listOf("return Probe"), EscalationChange.ProbeEvidence),
        Case(FailureClass.RepeatedFailedHypothesis, listOf("return RecoveryLadder"), EscalationChange.AlternativeAttempt),
        Case(FailureClass.TruncatedModelResponse, listOf("return Adapter"), null, ExecutionState.Running),
        Case(FailureClass.UnknownActionOutcome, listOf("reconcile", "return Runner"), null, ExecutionState.Unknown, reconciledTo = ExecutionState.EffectObserved),
        Case(FailureClass.LostConstraint, listOf("return Compiler"), null),
        Case(FailureClass.AuthorizationDenial, listOf("return Cell"), null, ExecutionState.IntentRecorded),
        Case(FailureClass.BudgetExhaustion, listOf("return Controller"), null),
        Case(FailureClass.SupersededUnit, listOf("return Controller"), null, ExecutionState.Running),
    )

    /** Runs the ladder to its end; every non-terminal step is taken and fails again. */
    private fun walk(start: Failure, reconciledTo: ExecutionState): Pair<List<String>, Recovery.Return> {
        val steps = ArrayList<String>()
        var failure = start
        while (true) {
            check(steps.size < 8) { "an unbounded walk: $steps" }
            failure = when (val r = ladder.recover(failure)) {
                is Recovery.Reconcile -> failure.copy(reconciled = true, state = reconciledTo).also { steps += "reconcile" }
                is Recovery.Retry -> failure.copy(retries = r.attempt).also { steps += "retry ${r.attempt}" }
                is Recovery.Repair -> failure.copy(repairs = failure.repairs + 1).also { steps += "repair" }
                is Recovery.Return -> return (steps + "return ${r.handler.name}") to r
            }
        }
    }

    private val attempt = SubstantiveAttempt(AttemptId("a1"), Tier.High, "main", "first hypothesis", evidenceRefs = listOf("rcpt-1"))

    @Test
    fun `every failure class walks its labelled bounded ladder to its handler and escalates only by its mapped change`() {
        assertEquals(FailureClass.entries.toSet(), cases.map { it.failureClass }.toSet(), "one fixture per §13.2 class")
        for (case in cases) {
            val kind = case.failureClass
            val start = Failure(kind, case.start, "run.run", "fixture ${kind.name}", listOf("#1", "rcpt-7"), safeToRetry = case.safeToRetry)
            val (trajectory, end) = walk(start, case.reconciledTo)
            assertEquals(case.trajectory, trajectory, kind.name)
            assertEquals(kind.handler, end.handler, kind.name)
            assertTrue(end.failure.retries <= ladder.limits.retries && end.failure.repairs <= ladder.limits.repairs, "$kind stays within §13.1 bounds")
            assertEquals(listOf("#1", "rcpt-7"), end.evidenceRefs, "$kind returns with its evidence")
            kind.mustNotConceal?.let { assertTrue(it in end.message, "$kind: '${end.message}' conceals '$it'") }
            assertTrue(kind.response in end.message, kind.name)

            val step = Escalation.next(AttemptAllowance("I1", 2, listOf(attempt)), VerifiedFailure(kind, "verified ${kind.name}", listOf("rcpt-7")))
            when (val change = case.escalation) {
                null -> assertTrue(assertIs<EscalationStep.NotEscalated>(step, kind.name).reason.contains("returns to ${kind.handler.name}"), kind.name)
                else -> assertEquals(change, assertIs<EscalationStep.Escalate>(step, kind.name).change, kind.name)
            }
        }
    }

    @Test
    fun `the recovery ladder handler opens an alternative attempt and a spent allowance blocks instead`() {
        val idGen = FixedIdGen()
        val (_, end) = walk(Failure(FailureClass.RepeatedFailedHypothesis, ExecutionState.DurablyCompleted, "verify.acceptance", "AC-2 red with the same fingerprint", listOf("rcpt-2")), ExecutionState.DurablyCompleted)
        assertEquals(Handler.RecoveryLadder, end.handler)
        val failures = listOf(1, 2).map { VerifiedFailure(FailureClass.RepeatedFailedHypothesis, "AC-2 red after repair $it", listOf("rcpt-${it + 1}")) }
        val register = Register.empty(ContextId("cell-1"), "I1", "totals")
        val opened = assertIs<AlternativeDecision.Opened>(Alternative.open(AttemptAllowance("I1", 2, listOf(attempt)), failures, register, listOf("rcpt-2", "rcpt-3"), 3, "second hypothesis", idGen)).alternative
        assertEquals(EscalationChange.AlternativeAttempt, opened.substantive.change)
        assertEquals(Tier.ExtraHigh, opened.substantive.tier)
        assertEquals(0, opened.allowance.remaining)
        val blocked = Escalation.next(opened.allowance, failures.last())
        assertTrue(assertIs<EscalationStep.Blocked>(blocked).reason.contains("budget.attempts 2 spent"), blocked.toString())
    }
}
