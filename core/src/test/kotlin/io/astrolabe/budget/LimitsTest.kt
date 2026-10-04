package io.astrolabe.budget

import io.astrolabe.campaign.BudgetStop
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Charge
import io.astrolabe.provider.Money
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.telemetry.Quantities
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private fun usd(amount: String) = Money("USD", BigDecimal(amount))

    private fun spend(requests: Int = 0, cost: String? = null, elapsedMillis: Long = 0, largest: String? = cost, basis: CostBasis = CostBasis.Estimated) =
        LimitSpend(requests, cost?.let(::usd), if (requests == 0) CostBasis.None else basis, elapsedMillis, largest?.let(::usd))

    @Test
    fun `the request reserve is three calls and never the whole limit`() {
        // max(0, min(3, L − 1)).
        assertEquals(0, LimitRule.reserveRequests(1))
        assertEquals(1, LimitRule.reserveRequests(2))
        assertEquals(2, LimitRule.reserveRequests(3))
        assertEquals(3, LimitRule.reserveRequests(4))
        assertEquals(3, LimitRule.reserveRequests(3_000), "small and proportional to a call, never a share of a large limit")
    }

    @Test
    fun `the money and time reserve holds three calls of the next call's price, leaving one call of work`() {
        // max(0, min(3C, L − C)).
        assertEquals(BigDecimal("0.30"), LimitRule.reserveAmount(BigDecimal("50"), BigDecimal("0.10")).setScale(2))
        assertEquals(BigDecimal("4.50"), LimitRule.reserveAmount(BigDecimal("10"), BigDecimal("1.50")).setScale(2), "three $1.50 calls fit, not one")
        assertEquals(BigDecimal("0.50"), LimitRule.reserveAmount(BigDecimal("1"), BigDecimal("0.50")).setScale(2), "one working call stays")
        assertEquals(BigDecimal("0.00"), LimitRule.reserveAmount(BigDecimal("1"), BigDecimal("2")).setScale(2), "a call dearer than the limit leaves no reserve")
        assertEquals(BigDecimal("0.00"), LimitRule.reserveAmount(BigDecimal("1"), BigDecimal.ZERO).setScale(2), "before any price is known there is nothing to hold")
        assertFailsWith<IllegalArgumentException> { LimitRule.reserveAmount(BigDecimal.ZERO, BigDecimal.ONE) }
    }

    @Test
    fun `requests - within, then the reserve, then exhausted before the limit is crossed, without overflow`() {
        val limits = TaskLimits(maxRequests = 11)
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 7)))
        val reserve = assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 8)))
        assertEquals(LimitKind.Requests, reserve.kind)
        assertTrue("the last 3 are held" in reserve.reason, reserve.reason)
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 10)))
        assertEquals(LimitKind.Requests, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 11))).kind)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(TaskLimits(maxRequests = Int.MAX_VALUE), spend(requests = Int.MAX_VALUE)), "S ≥ L, never S + 1")
        assertIs<LimitDecision.Reserve>(LimitRule.decide(TaskLimits(maxRequests = Int.MAX_VALUE), spend(requests = Int.MAX_VALUE - 1)))
    }

    @Test
    fun `money - one price C = max(E, u) decides at every point, and an unknown price never proceeds`() {
        val limits = TaskLimits(maxCost = usd("10"))
        // L = $10, S = $7, u = $1, the request estimated at $3: C = 3, R = min(9, 7) = 7 → generation refused, a report call admitted.
        val s = spend(requests = 7, cost = "7", largest = "1")
        val price = LimitRule.nextCost(s, usd("3"))
        assertEquals(usd("3"), price)
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, s, price))
        assertEquals(usd("1"), LimitRule.nextCost(s, usd("0.40")), "the dearest accounted call when it is dearer than the estimate")
        assertEquals(usd("1"), LimitRule.nextCost(s, null), "no estimate yet: the dearest call so far")
        // A request dearer than the whole limit: exhausted before the first call.
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(TaskLimits(maxCost = usd("2")), spend(), usd("2.40")))
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 2, cost = "2", largest = "1"), usd("1")), "2 + 1 + 3 ≤ 10")
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 7, cost = "6.01", largest = "1"), usd("1")), "6.01 + 1 + 3 > 10")
        val unknown = LimitSpend(1, Money.unknown("USD"), CostBasis.Estimated, 0, Money.unknown("USD"))
        assertEquals(LimitKind.Cost, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, unknown)).kind)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 1, cost = "0.10"), Money("EUR", BigDecimal("0.01"))), "another currency is not comparable")
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 1, cost = "0.10"), Money.unknown("USD")), "a call with no price bound (fail closed)")
        assertTrue(LimitRule.nextCost(spend(requests = 1, cost = "0.10"), Money("EUR", BigDecimal("0.01")))!!.unknown)
    }

    @Test
    fun `minutes - no call starts once a mean call would reach the reserve of three mean calls`() {
        val limits = TaskLimits(maxMinutes = 10)
        // 10 calls in 6 min: mean 36 s, R = min(108 s, 564 s) = 108 s; reserve after 600 − 36 − 108 = 456 s, exhausted after 564 s.
        assertEquals(LimitDecision.Within, LimitRule.decide(limits, spend(requests = 10, elapsedMillis = 360_000)))
        assertIs<LimitDecision.Reserve>(LimitRule.decide(limits, spend(requests = 10, elapsedMillis = 470_000)))
        assertEquals(LimitKind.Minutes, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 10, elapsedMillis = 570_000))).kind)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 0, elapsedMillis = 600_000)))
        val status = LimitRule.status(limits, spend(requests = 10, elapsedMillis = 360_000))
        assertEquals(600_000L, status.maxMillis)
        assertEquals(108_000L, status.reserveMillis)
    }

    @Test
    fun `a hard limit wins over another limit's reserve and no limit is always within`() {
        val limits = TaskLimits(maxCost = usd("1"), maxRequests = 10)
        assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend(requests = 10, cost = "0.50", largest = "0.05")))
        assertEquals(LimitDecision.Within, LimitRule.decide(TaskLimits.NONE, spend(requests = 1_000, cost = "999")))
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
    fun `a mixed task shows paid and nominal spend apart and its money limit counts their sum`() {
        val ids = Identities(WorkId("W-m"), AttemptId("a1"))
        fun call(id: String, money: Money, charge: Charge, billed: Money? = null) = CallAccount(
            id, ids, "main", BillableUsage(mapOf(BillingDimension.OUTPUT to 10L), UsageProvenance("fake", "fake", "reported"), billed = billed),
            money, "2026-01-01", Quantities(null, null, null, null), null, Instant.EPOCH, null, null, charge,
        )
        val calls = listOf(
            call("p1", usd("0.30"), Charge.Paid, billed = usd("0.25")),
            call("n1", usd("0.50"), Charge.Nominal),
            call("u1", Money.zero("USD"), Charge.Unpriced),
        )
        val spend = LimitSpend.of(calls, 0, "USD")
        assertEquals(3, spend.requests)
        assertEquals(1, spend.unpricedRequests)
        assertEquals(CostBasis.Mixed, spend.costBasis)
        assertEquals(0, BigDecimal("0.25").compareTo(spend.paidCost!!.amount))
        assertEquals(0, BigDecimal("0.50").compareTo(spend.nominalCost!!.amount))
        assertEquals(0, BigDecimal("0.75").compareTo(spend.cost!!.amount), "the limit counts paid plus nominal")
        assertEquals(usd("0.50"), spend.largestCallCost)
        // Paid alone (0.25 + 0.20) would fit 0.90; the sum (0.75 + 0.20) does not.
        val limits = TaskLimits(maxCost = usd("0.90"))
        assertEquals(LimitKind.Cost, assertIs<LimitDecision.Exhausted>(LimitRule.decide(limits, spend, usd("0.20"))).kind)
        assertIs<LimitDecision.Reserve>(LimitRule.decide(TaskLimits(maxCost = usd("1.50")), spend, usd("0.20")))
        val status = LimitRule.status(limits, spend, usd("0.20"))
        assertEquals(spend.paidCost, status.paidCost)
        assertEquals(spend.nominalCost, status.nominalCost)
        assertEquals(1, status.unpricedRequests)
        val totals = io.astrolabe.telemetry.Accounting.totals(calls, 0, "USD")
        assertEquals(0, BigDecimal("0.30").compareTo(totals.paidMoney!!.amount))
        assertEquals(0, BigDecimal("0.50").compareTo(totals.nominalMoney!!.amount))
        assertEquals(0, BigDecimal("0.80").compareTo(totals.money.amount))
        assertEquals(1, totals.unpricedCalls)
        // A positive bill on a plan-billed call is paid money, counted once; a zero bill leaves the nominal price.
        val bills = LimitSpend.of(listOf(
            call("n2", usd("0.10"), Charge.Nominal, billed = usd("2")),
            call("n3", usd("0.10"), Charge.Nominal, billed = usd("0")),
            call("u2", Money.zero("USD"), Charge.Unpriced, billed = usd("0.40")),
        ), 0, "USD")
        assertEquals(0, BigDecimal("2.40").compareTo(bills.paidCost!!.amount))
        assertEquals(0, BigDecimal("0.10").compareTo(bills.nominalCost!!.amount))
        assertEquals(0, BigDecimal("2.50").compareTo(bills.cost!!.amount))
        assertEquals(0, bills.unpricedRequests)
        assertEquals(CostBasis.Mixed, bills.costBasis)
        assertEquals(LimitKind.Cost, assertIs<LimitDecision.Exhausted>(LimitRule.decide(TaskLimits(maxCost = usd("1")), bills, usd("0.10"))).kind)
    }

    @Test
    fun `a spend, a limit status and a call recorded before nominal charges read back with their old basis`() {
        val json = Json { encodeDefaults = true }
        // A record as written before C16: without the new fields, the basis under the constant's old name.
        fun <T> old(serializer: KSerializer<T>, value: T, basis: String?): T {
            val full = json.encodeToJsonElement(serializer, value) as JsonObject
            val kept = full - setOf("paidCost", "nominalCost", "unpricedRequests", "charge")
            val written = if (basis == null) kept else kept + ("costBasis" to JsonPrimitive(basis))
            return json.decodeFromJsonElement(serializer, JsonObject(written))
        }
        val spend = old(LimitSpend.serializer(), LimitSpend(2, usd("0.40"), CostBasis.Estimated, 5, usd("0.30"), usd("0.40"), usd("0"), 0), "Estimated")
        assertEquals(CostBasis.Estimated, spend.costBasis)
        assertNull(spend.paidCost)
        assertNull(spend.nominalCost)
        assertEquals(0, spend.unpricedRequests)
        val status = old(LimitStatus.serializer(), LimitRule.status(TaskLimits(maxCost = usd("5")), spend(requests = 2, cost = "0.4", basis = CostBasis.Mixed)), "Mixed")
        assertEquals(CostBasis.Mixed, status.costBasis)
        assertNull(status.nominalCost)
        val call = CallAccount("c1", Identities(WorkId("W-o"), AttemptId("a1")), "main", null, Money.zero("USD"), "2026-01-01",
            Quantities(null, null, null, null), null, Instant.EPOCH, charge = Charge.Nominal)
        assertEquals(Charge.Paid, old(CallAccount.serializer(), call, null).charge, "a row written before C16 reads as paid")
        assertEquals(CostBasis.Nominal, Json.decodeFromString(CostBasis.serializer(), "\"nominal\""))
    }

    @Test
    fun `the cell gate refuses generation in the reserve and every spend once exhausted`() {
        var decision: LimitDecision = LimitDecision.Within
        val cell = CellBudget.of(Tokens(100_000), 40, Reserves(), limits = LimitGate { _, _ -> decision })
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
        val cell = CellBudget.of(Tokens(100_000), 40, Reserves(), limits = LimitGate { spend, estimate -> asked += spend to estimate.value; LimitDecision.Within })
        cell.startTurn(Spend.Generation)
        assertIs<Admission.Admitted>(cell.admit(Spend.Generation, Tokens(1_234))).release()
        assertEquals(listOf(Spend.Generation to 0L, Spend.Generation to 1_234L), asked)
    }

    @Test
    fun `limit kinds, cost bases and budget stops travel as their wire words and still read their constant names`() {
        fun <E : Enum<E>> check(serializer: KSerializer<E>, entries: List<E>, wire: (E) -> String) {
            for (entry in entries) {
                assertEquals("\"${wire(entry)}\"", Json.encodeToString(serializer, entry))
                assertEquals(entry, Json.decodeFromString(serializer, "\"${wire(entry)}\""))
                assertEquals(entry, Json.decodeFromString(serializer, "\"${entry.name}\""), "a record written before C14 names the constant")
            }
        }
        check(LimitKind.serializer(), LimitKind.entries) { it.wire }
        check(CostBasis.serializer(), CostBasis.entries) { it.wire }
        check(BudgetStop.serializer(), BudgetStop.entries) { it.wire }
        assertEquals(listOf("money", "minutes", "requests"), LimitKind.entries.map { it.wire })
        // What `budget.spent` and `budget.limit_reached` carry.
        val status = LimitRule.status(TaskLimits(maxCost = usd("5")), spend(requests = 1, cost = "1"))
        assertTrue("\"costBasis\":\"estimated\"" in Json.encodeToString(LimitStatus.serializer(), status))
    }
}
