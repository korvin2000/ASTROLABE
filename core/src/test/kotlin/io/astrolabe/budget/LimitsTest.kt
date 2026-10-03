package io.astrolabe.budget

import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.telemetry.Quantities
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C3 (plan §4.6): the reserve rule, the decision before each model call, the spend from records and the cell's gate. */
class LimitsTest {
    private val reserves = Reserves()

    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    private fun spend(requests: Int = 0, cost: String? = null, elapsedMillis: Long = 0, largest: String? = cost, basis: CostBasis = CostBasis.Estimated) =
        LimitSpend(requests, cost?.let(::usd), if (requests == 0) CostBasis.None else basis, elapsedMillis, largest?.let(::usd))

    @Test
    fun `the request reserve is three calls, capped by a fifth of a small limit, never the whole limit`() {
        // min(3, ⌈0.2·L⌉, L − 1): small and proportional to a call, never a fixed share of a large limit.
        assertEquals(0, LimitRule.reserveRequests(1, reserves))
        assertEquals(1, LimitRule.reserveRequests(2, reserves))
        assertEquals(1, LimitRule.reserveRequests(3, reserves))
        assertEquals(2, LimitRule.reserveRequests(8, reserves))
        assertEquals(2, LimitRule.reserveRequests(10, reserves), "⌈2.0⌉, never 3 through a binary rounding")
        assertEquals(3, LimitRule.reserveRequests(11, reserves))
        assertEquals(3, LimitRule.reserveRequests(3_000, reserves))
    }

    @Test
    fun `the money and time reserve is three calls, capped by a fifth of the limit, leaving one call of work`() {
        // max(0, min(3u, (v + r)·L, L − u)).
        assertEquals(BigDecimal("0.30"), LimitRule.reserveAmount(BigDecimal("50"), reserves, BigDecimal("0.10")).setScale(2), "a large limit holds three calls, not a fifth")
        assertEquals(BigDecimal("2.00"), LimitRule.reserveAmount(BigDecimal("10"), reserves, BigDecimal("1.50")).setScale(2), "a small one at most a fifth")
        assertEquals(BigDecimal("0.20"), LimitRule.reserveAmount(BigDecimal("1"), reserves, BigDecimal("0.50")).setScale(2))
        assertEquals(BigDecimal("0.00"), LimitRule.reserveAmount(BigDecimal("1"), reserves, BigDecimal("2")).setScale(2), "a call dearer than the limit leaves no reserve")
        assertEquals(BigDecimal("0.00"), LimitRule.reserveAmount(BigDecimal("1"), reserves, BigDecimal.ZERO).setScale(2), "before the first call there is nothing to verify")
        assertFailsWith<IllegalArgumentException> { LimitRule.reserveAmount(BigDecimal.ZERO, reserves, BigDecimal.ONE) }
    }

    @Test
    fun `requests - within, then the reserve, then exhausted before the limit is crossed`() {
        val limits = TaskLimits(maxRequests = 11)
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 7), reserves))
        val reserve = assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 8), reserves))
        assertEquals(LimitKind.Requests, reserve.kind)
        assertTrue("the last 3 are held" in reserve.reason, reserve.reason)
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 10), reserves))
        assertEquals(LimitKind.Requests, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 11), reserves)).kind)
    }

    @Test
    fun `money - the next call's conservative cost decides, an unknown or foreign spend never proceeds`() {
        val limits = TaskLimits(maxCost = usd("1.00"))
        // reserve = max(0.20, 2 · 0.10) = 0.20: generation while S + c ≤ 0.80.
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 4, cost = "0.60", largest = "0.10"), reserves, usd("0.20")))
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 4, cost = "0.60", largest = "0.10"), reserves, usd("0.25")))
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 4, cost = "0.90", largest = "0.10"), reserves, usd("0.20")))
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(), reserves), "nothing spent, no call priced yet")
        val unknown = LimitSpend(1, Money.unknown("USD"), CostBasis.Estimated, 0, Money.unknown("USD"))
        assertEquals(LimitKind.Cost, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, unknown, reserves)).kind)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 1, cost = "0.10"), reserves, Money("EUR", BigDecimal("0.01"))), "another currency is not comparable")
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 1, cost = "0.10"), reserves, Money.unknown("USD")), "an unpriceable next call")
    }

    @Test
    fun `minutes - the reserve is three mean calls, at most a fifth of the limit`() {
        val limits = TaskLimits(maxMinutes = 10)
        // 10 calls in 7 min 50 s: u = 47 s, reserve = min(141 s, 120 s) = 120 s, so the working part ends at 480 s.
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 10, elapsedMillis = 470_000), reserves))
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 10, elapsedMillis = 500_000), reserves))
        // 2 calls in 6 min: u = 180 s, reserve = min(540 s, 120 s) = 120 s.
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 2, elapsedMillis = 360_000), reserves))
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 2, elapsedMillis = 480_000), reserves))
        assertEquals(LimitKind.Minutes, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 2, elapsedMillis = 600_000), reserves)).kind)
        val status = LimitRule.status(limits, spend(requests = 10, elapsedMillis = 360_000), reserves)
        assertEquals(600_000L, status.maxMillis)
        assertEquals(108_000L, status.reserveMillis)
    }

    @Test
    fun `a hard limit wins over another limit's reserve and no limit is always within`() {
        val limits = TaskLimits(maxCost = usd("1"), maxRequests = 10)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 10, cost = "0.50", largest = "0.05"), reserves))
        assertEquals(LimitDecision.Within, LimitRule.decide(TaskLimits.NONE, spend(requests = 1_000, cost = "999"), reserves))
        assertFalse(TaskLimits.NONE.any)
        assertFailsWith<IllegalArgumentException> { TaskLimits(maxRequests = 0) }
        assertFailsWith<IllegalArgumentException> { TaskLimits(maxCost = Money.unknown("USD")) }
        assertEquals("money 1 USD, 10 requests", limits.toString())
    }

    @Test
    fun `the spend counts billed amounts first, then estimates, then holds, and names its basis`() {
        val ids = Identities(WorkId("W-l"), AttemptId("a1"))
        fun call(id: String, money: Money, billed: Money? = null, funded: Money? = null) = CallAccount(
            id, ids, "main", BillableUsage(mapOf(BillingDimension.OUTPUT to 10L), UsageProvenance("fake", "fake", "reported"), billed = billed),
            money, "2026-01-01", Quantities(null, null, null, null), null, Instant.EPOCH, null, funded,
        )
        val estimated = call("c1", usd("0.30"))
        val billed = call("c2", usd("0.30"), billed = usd("0.25"))
        val held = call("c3", Money.unknown("USD"), funded = usd("0.70"))
        assertEquals(LimitSpend(0, null, CostBasis.None, 5, null), LimitSpend.of(emptyList(), 5, "USD"))
        val mixed = LimitSpend.of(listOf(estimated, billed, held), 1_000, "USD")
        assertEquals(3, mixed.requests)
        assertEquals(BigDecimal("1.25"), mixed.cost!!.amount)
        assertFalse(mixed.cost!!.unknown)
        assertEquals(CostBasis.Mixed, mixed.costBasis)
        assertEquals(usd("0.70"), mixed.largestCallCost)
        assertEquals(CostBasis.Billed, LimitSpend.of(listOf(billed), 0, "USD").costBasis)
        assertEquals(CostBasis.Estimated, LimitSpend.of(listOf(estimated, held), 0, "USD").costBasis)
        assertTrue(LimitSpend.of(listOf(call("c4", Money.unknown("USD"))), 0, "USD").cost!!.unknown, "unknown, never zero")
        assertTrue(LimitSpend.of(listOf(estimated), 0, "EUR").cost!!.unknown, "a foreign amount is not counted as this currency")
    }

    @Test
    fun `the cell gate refuses generation in the reserve and every spend once exhausted`() {
        var decision: LimitDecision = LimitDecision.Within
        val cell = CellBudget.of(Tokens(100_000), 40, reserves, limits = LimitGate { _, _ -> decision })
        assertIs<Admission.Admitted>(cell.startTurn(Spend.Generation))
        assertFalse(cell.reserveReached)
        assertNull(cell.verdict().gate)

        decision = LimitDecision.Reserve(LimitKind.Requests, "task limit: 7 of 10 model requests spent")
        assertTrue(cell.reserveReached)
        assertEquals(CellBudget.GATE, cell.verdict().gate, "the §5.9 gate line: verify and report")
        val refused = assertIs<Admission.Refused>(cell.startTurn(Spend.Generation))
        assertTrue(refused.reason.startsWith("reserve reached: task limit"), refused.reason)
        assertIs<Admission.Refused>(cell.admit(Spend.Edit, Tokens(10)))
        assertIs<Admission.Admitted>(cell.startTurn(Spend.Check), "verify-and-report turns run on the reserve")
        assertIs<Admission.Admitted>(cell.admit(Spend.Check, Tokens(10))).release()

        decision = LimitDecision.Exhausted(LimitKind.Requests, "task limit: 10 of 10 model requests spent")
        assertIs<Admission.Refused>(cell.startTurn(Spend.Check))
        assertIs<Admission.Refused>(cell.admit(Spend.ResultPacket, Tokens(1)))
        assertEquals(2, cell.turnsTaken, "a refused turn takes no turn")
    }

    @Test
    fun `the gate is asked with the admission estimate before each call`() {
        val asked = ArrayList<Pair<Spend, Long>>()
        val cell = CellBudget.of(Tokens(100_000), 40, reserves, limits = LimitGate { spend, estimate -> asked += spend to estimate.value; LimitDecision.Within })
        cell.startTurn(Spend.Generation)
        assertIs<Admission.Admitted>(cell.admit(Spend.Generation, Tokens(1_234))).release()
        assertEquals(listOf(Spend.Generation to 0L, Spend.Generation to 1_234L), asked)
    }
}
