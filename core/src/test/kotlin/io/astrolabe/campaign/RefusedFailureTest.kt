package io.astrolabe.campaign

import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.ObligationKind
import io.astrolabe.verify.ObligationResult
import io.astrolabe.verify.Resolver
import io.astrolabe.verify.ResultStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D-367: which refused completions spend `budget.attempts`. */
class RefusedFailureTest {
    private val moved = "proposal binds contract v3; the committed contract is v4"
    private val unverified = ObligationResult("AC-2", ObligationKind.Run, ResultStatus.Unverified, "AC-2: outcome unavailable")

    private fun refused(results: List<ObligationResult>, other: List<String>, binding: List<String>): CompletionResult.Refused {
        val resolved = Resolver.resolve(results, other, binding = binding)
        return CompletionResult.Refused(resolved.missing, attempts = 1, recoveryDirected = false, resolved = resolved)
    }

    @Test
    fun `a proposal overtaken by an amendment is not a failed attempt`() {
        assertNull(IncrementAttempts.refusedFailure(refused(emptyList(), emptyList(), listOf(moved))))
        assertNull(IncrementAttempts.refusedFailure(refused(listOf(unverified), emptyList(), listOf(moved))))
    }

    @Test
    fun `an open item or a failed obligation beside the binding is a failed attempt`() {
        val open = refused(emptyList(), listOf("step 1 [>] 'x' has no disposition"), listOf(moved))
        assertEquals(open.missing, IncrementAttempts.refusedFailure(open))
        val plain = refused(emptyList(), listOf("step 1 [>] 'x' has no disposition"), emptyList())
        assertEquals(plain.missing, IncrementAttempts.refusedFailure(plain))
    }
}
