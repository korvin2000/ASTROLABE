package io.astrolabe.recover

import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.id.ExecutionGeneration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderTest {
    private val ladder = Ladder()

    private fun failure(kind: FailureClass, state: ExecutionState = ExecutionState.DurablyCompleted, safe: Boolean = false, retries: Int = 0, repairs: Int = 0, reconciled: Boolean = false) =
        Failure(kind, state, "run.run", "detail of ${kind.name}", listOf("#7"), safeToRetry = safe, retries = retries, repairs = repairs, reconciled = reconciled)

    /** One row per §13.2 class: the first decision `recover` takes for a fresh, reconciled failure of that class. */
    private val table: List<Triple<FailureClass, Boolean, (Recovery) -> Unit>> = listOf(
        Triple(FailureClass.TransportRateLimit, true, { r -> assertEquals(1, assertIs<Recovery.Retry>(r).attempt) }),
        Triple(FailureClass.InvalidToolArguments, false, { r -> assertEquals(Handler.Cell, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.StaleAnchor, false, { r -> assertEquals(Handler.Cell, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.BuildEnvironment, false, { r -> assertIs<Recovery.Repair>(r) }),
        Triple(FailureClass.BehaviouralTestFailure, false, { r -> assertEquals(Handler.Cell, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.MissingRepositoryContract, false, { r -> assertEquals(Handler.Probe, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.RepeatedFailedHypothesis, false, { r -> assertEquals(Handler.RecoveryLadder, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.TruncatedModelResponse, false, { r -> assertEquals(Handler.Adapter, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.UnknownActionOutcome, false, { r -> assertIs<Recovery.Reconcile>(r) }),
        Triple(FailureClass.LostConstraint, false, { r -> assertEquals(Handler.Compiler, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.AuthorizationDenial, false, { r -> assertEquals(Handler.Cell, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.BudgetExhaustion, false, { r -> assertEquals(Handler.Controller, assertIs<Recovery.Return>(r).handler) }),
        Triple(FailureClass.SupersededUnit, false, { r -> assertEquals(Handler.Controller, assertIs<Recovery.Return>(r).handler) }),
    )

    @Test fun `every failure class has a row and its first bounded response`() {
        assertEquals(FailureClass.entries.toSet(), table.map { it.first }.toSet(), "the table covers every §13 class")
        for ((kind, safe, expect) in table) expect(ladder.recover(failure(kind, safe = safe)))
    }

    @Test fun `what escalation must not conceal is in every message that escalates`() {
        for (kind in FailureClass.entries) {
            val conceal = kind.mustNotConceal ?: continue
            val message = when (val r = ladder.recover(failure(kind, retries = 2, repairs = 1, reconciled = true))) {
                is Recovery.Return -> r.message
                is Recovery.Repair -> r.message
                is Recovery.Reconcile -> r.message
                is Recovery.Retry -> error("$kind is never retried after two retries")
            }
            assertTrue(conceal in message, "$kind: '$message' conceals '$conceal'")
        }
        val repair = assertIs<Recovery.Repair>(ladder.recover(failure(FailureClass.BuildEnvironment)))
        assertTrue("failing setup ≠ failing implementation" in repair.message)
        val reconcile = assertIs<Recovery.Reconcile>(ladder.recover(failure(FailureClass.UnknownActionOutcome)))
        assertTrue("never blind-replay a non-idempotent chain" in reconcile.message)
    }

    @Test fun `unknown outcomes reconcile first and retries are bounded to classified-safe operations`() {
        assertIs<Recovery.Reconcile>(ladder.recover(failure(FailureClass.TransportRateLimit, ExecutionState.Unknown, safe = true)))
        val first = assertIs<Recovery.Retry>(ladder.recover(failure(FailureClass.TransportRateLimit, safe = true)))
        val second = assertIs<Recovery.Retry>(ladder.recover(failure(FailureClass.TransportRateLimit, safe = true, retries = 1)))
        assertEquals(listOf(1_000L, 2_000L), listOf(first.backoffMillis, second.backoffMillis), "deterministic backoff")
        val exhausted = assertIs<Recovery.Return>(ladder.recover(failure(FailureClass.TransportRateLimit, safe = true, retries = 2)))
        assertEquals(Handler.Adapter, exhausted.handler)
        assertTrue("retries exhausted" in exhausted.reason)
        val unsafe = assertIs<Recovery.Return>(ladder.recover(failure(FailureClass.TransportRateLimit, ExecutionState.EffectObserved, safe = false)))
        assertTrue("never replayed" in unsafe.reason, "a non-idempotent action is never auto-replayed")
        val spent = assertIs<Recovery.Return>(ladder.recover(failure(FailureClass.BuildEnvironment, repairs = 1)))
        assertEquals(Handler.RepairHelper, spent.handler)
        assertEquals(listOf("#7"), spent.evidenceRefs, "the return carries the evidence")
        assertIs<Recovery.Return>(ladder.recover(failure(FailureClass.UnknownActionOutcome, reconciled = true)))
    }

    @Test fun `fences check generation authority reservation and reconcile before a new writer`() {
        val ok = PublicationAuthority { null }
        val g0 = ExecutionGeneration.INITIAL
        assertNull(Fence.dispatch(g0, g0, ok, reserved = true))
        assertTrue(Fence.dispatch(g0, g0.next(), ok, reserved = true)!!.contains("superseded"))
        assertEquals("lease expired", Fence.dispatch(g0, g0, { "lease expired" }, reserved = true))
        assertEquals("no atomic budget reservation", Fence.dispatch(g0, g0, ok, reserved = false))
        val late = Fence.publish(g0, g0.next(), ok)
        assertTrue(!late.allowed && late.persistObservation, "a refused publication still persists the late observation")
        assertTrue(Fence.publish(g0, g0, ok).allowed)
        assertTrue(Fence.grant(listOf("intent i-3 (run.run)"))!!.contains("i-3"))
        assertNull(Fence.grant(emptyList()))
    }
}
