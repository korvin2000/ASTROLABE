package io.astrolabe.budget

import io.astrolabe.Defaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BudgetTest {
    @Test
    fun `concurrent reservations never overspend (FX-25)`() = runBlocking {
        val reservations = Reservations(Tokens(1_000))
        val granted = (1..300).map {
            async(Dispatchers.Default) { reservations.reserve(Tokens(10), "call-$it") }
        }.awaitAll().filterNotNull()
        assertEquals(100, granted.size)
        assertEquals(Tokens(1_000), reservations.heldTokens)
        assertEquals(Tokens.ZERO, reservations.available)
        assertNull(reservations.reserve(Tokens(1), "late"))
    }

    @Test
    fun `unknown usage keeps its hold until reconciled and overruns are recorded`() {
        val reservations = Reservations(Tokens(100))
        val r = assertNotNull(reservations.reserve(Tokens(60), "model call"))
        assertEquals(Tokens(40), reservations.available)
        // usage unknown: nothing changes until the terminal usage is reconciled
        assertEquals(Tokens(60), reservations.heldTokens)
        r.reconcile(Tokens(75))
        assertEquals(Tokens(75), reservations.spent)
        assertEquals(Tokens(15), reservations.overrunTokens)
        assertEquals(Tokens(25), reservations.available)
        assertFailsWith<IllegalStateException> { r.reconcile(Tokens(1)) }

        val cheap = assertNotNull(reservations.reserve(Tokens(20), "check"))
        cheap.reconcile(Tokens(5))
        assertEquals(Tokens(20), reservations.available)

        val never = assertNotNull(reservations.reserve(Tokens(20), "cancelled before dispatch"))
        never.release()
        assertEquals(Tokens(20), reservations.available)
        assertEquals(Reservations.State.Released, never.state)
    }

    @Test
    fun `cell reserve is 15 percent plus 5 percent raised to known check costs`() {
        val reserve = Reserve.cell(Tokens(100_000), 40, Reserves())
        assertEquals(Tokens(15_000), reserve.verificationTokens)
        assertEquals(6, reserve.verificationTurns)
        assertEquals(Tokens(5_000), reserve.recoveryTokens)
        assertEquals(2, reserve.recoveryTurns)
        assertEquals(Tokens(20_000), reserve.tokens)
        val raised = Reserve.cell(Tokens(100_000), 40, Reserves(), knownCheckCostTokens = Tokens(22_000))
        assertEquals(Tokens(22_000), raised.verificationTokens)
        assertEquals(Tokens(10_000), Reserve.campaign(Tokens(100_000), 0.10))
        assertFailsWith<IllegalArgumentException> { Reserves(0.0, 0.05) }
    }

    @Test
    fun `budget from defaults and token arithmetic`() {
        val budget = Budget.of(Defaults(), Tokens(2_500_000))
        assertEquals(12, budget.cells)
        assertEquals(40, budget.turnsPerCell)
        assertEquals(2, budget.attempts)
        assertEquals(Tokens(3), Tokens(5) - Tokens(2))
        assertEquals(Tokens.ZERO, Tokens(2) - Tokens(5))
        assertEquals(Tokens(34), Tokens(100).fraction(0.334))
        assertFailsWith<IllegalArgumentException> { Tokens(-1) }
    }

    @Test
    fun `heuristic estimator is never exact and weighs code heavier than prose`() {
        val estimator = HeuristicEstimator()
        val prose = estimator.estimate("the quick brown fox jumps over the lazy dog and keeps running")
        val code = estimator.estimate("fun f(x:Int)=x.let{it*2}.also{println(it)}.toString().length")
        assertFalse(prose.exact)
        assertTrue(prose.marginTokens >= 1)
        assertTrue(code.tokens.toDouble() / code.tokens.let { "fun f(x:Int)=x.let{it*2}.also{println(it)}.toString().length".length } >
            prose.tokens.toDouble() / "the quick brown fox jumps over the lazy dog and keeps running".length)
        assertEquals(0, estimator.estimate("").tokens)
        assertEquals("heuristic-bytes", estimator.id)
    }
}
