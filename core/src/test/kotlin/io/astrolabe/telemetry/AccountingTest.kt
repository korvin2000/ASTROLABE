package io.astrolabe.telemetry

import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.event.Phase
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.Billing
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Charge
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.11.2: per-call pricing from the dated table, missing usage stays unknown (FX-59), exports under `exports/`. */
class AccountingTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val work = WorkId("W-1")
    private val ids = Identities(work, AttemptId("a1"), context = ContextId("cell-1"))
    private val provenance = UsageProvenance("fake", "fake-main", "fake/1")

    private fun <T> withStore(block: (Store) -> T): T = TempRepo.create().use { repo ->
        repo.write("README.md", "# fixture\n")
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use(block)
    }

    private fun table(perMillion: Map<BillingDimension, String>, vararg tiers: Pair<Long, Map<BillingDimension, String>>) = FakeProfiles.main.priceTable.copy(
        perMillion = perMillion.mapValues { BigDecimal(it.value) },
        tiers = tiers.map { (above, prices) -> io.astrolabe.provider.PriceTier(above, prices.mapValues { BigDecimal(it.value) }) },
    )

    /** A profile billing only uncached input and output, priced by [prices]. */
    private fun plain(prices: io.astrolabe.provider.PriceTable) = FakeProfiles.main.copy(
        capabilities = FakeProfiles.main.capabilities.copy(
            usageFields = setOf(BillingDimension.UNCACHED_INPUT, BillingDimension.OUTPUT),
            caching = FakeProfiles.main.capabilities.caching.copy(writeClasses = emptySet()),
        ),
        priceTable = prices,
    )

    @Test
    fun `the reservation is the dearest table a call up to its input bound can be billed at, also when tiers fall`() {
        val input = BillingDimension.UNCACHED_INPUT
        val output = BillingDimension.OUTPUT
        // Tiers do not accumulate: above 200 input falls back to $1 — a call of 200 still pays $10.
        val falling = plain(table(mapOf(input to "1", output to "0"), 100L to mapOf(input to "10"), 200L to mapOf(input to "1")))
        assertEquals(0, BigDecimal("0.00201").compareTo(Accounting.estimateCost(falling, 201, 0).amount))
        // A higher tier that leaves output unstated is billed at the base output, below the lower tier's.
        val unstated = plain(table(mapOf(input to "1", output to "5"), 100L to mapOf(output to "50"), 200L to mapOf(input to "2")))
        assertEquals(0, BigDecimal("0.0503").compareTo(Accounting.estimateCost(unstated, 300, 1_000).amount)) // 300 × 1 + 1000 × 50 beats 300 × 2 + 1000 × 5
        // Tiers above the bound are unreachable.
        assertEquals(0, BigDecimal("0.0051").compareTo(Accounting.estimateCost(unstated, 100, 1_000).amount))
        assertFalse(Accounting.estimateCost(falling, 201, 0).unknown)
    }

    @Test
    fun `the reservation is unknown, never zero, without a price for every input dimension the route can bill`() {
        val readAndOutput = table(mapOf(BillingDimension.CACHE_READ to "0.3", BillingDimension.OUTPUT to "2"))
        assertTrue(Accounting.estimateCost(plain(readAndOutput), 1_000, 1_000).unknown)
        // The fake profile declares both cache-write classes: a table without the one-hour write is unknown too.
        val no1h = FakeProfiles.main.copy(priceTable = FakeProfiles.main.priceTable.copy(perMillion = FakeProfiles.main.priceTable.perMillion - BillingDimension.CACHE_WRITE_1H))
        assertTrue(Accounting.estimateCost(no1h, 1_000, 1_000).unknown)
        // A higher tier missing a dimension falls back to the base price, so it stays known.
        val tiered = plain(table(mapOf(BillingDimension.UNCACHED_INPUT to "1", BillingDimension.OUTPUT to "2"), 10L to mapOf(BillingDimension.OUTPUT to "3")))
        assertFalse(Accounting.estimateCost(tiered, 1_000, 1_000).unknown)
        assertFalse(Accounting.estimateCost(FakeProfiles.main, 1_000, 1_000).unknown)
    }

    @Test
    fun `a plan-billed profile without a price is marked unpriced, costs a money limit nothing, and requests and minutes still bound it`() = withStore { store ->
        // Missing catalog prices are not a plan: a per-token table without them is an unknown charge (FX-59).
        assertTrue(Accounting.estimateCost(plain(table(emptyMap())), 1_000, 1_000).unknown)
        assertEquals(Charge.Paid, table(emptyMap()).charge)
        // C16: a plan-billed table may state the model's official price (nominal); without a base price it has no tiers.
        assertEquals(Charge.Nominal, table(mapOf(BillingDimension.OUTPUT to "2")).copy(billing = Billing.Plan).charge)
        assertFailsWith<IllegalArgumentException> { table(emptyMap(), 10L to mapOf(BillingDimension.OUTPUT to "2")).copy(billing = Billing.Plan) }
        val plan = plain(table(emptyMap()).copy(billing = Billing.Plan))
        assertEquals(Charge.Unpriced, plan.priceTable.charge)
        val estimate = Accounting.estimateCost(plan, 600_000, 131_072)
        assertFalse(estimate.unknown)
        assertEquals(0, estimate.amount.signum())
        val accounting = Accounting(store, clock)
        val limit = io.astrolabe.provider.Money("USD", BigDecimal("50"))
        assertTrue(accounting.reserve(ids, "first", plan, 10, estimate, 100, limit), "the hold of a plan-billed call fits any money limit")
        val call = accounting.record(ids, "first", plan, null, null)
        assertFalse(call.money.unknown)
        assertEquals(Charge.Unpriced, call.charge, "the call says it has no money accounting")
        assertEquals(1, Accounting.totals(accounting.calls(ids.work), 0, "USD").unpricedCalls)
        val spend = io.astrolabe.budget.LimitSpend.of(accounting.calls(ids.work), 1_000, "USD")
        assertEquals(1, spend.unpricedRequests)
        assertEquals(io.astrolabe.budget.CostBasis.None, spend.costBasis, "no priced call")
        assertEquals(0, spend.cost!!.amount.signum())
        val limits = io.astrolabe.budget.TaskLimits(maxCost = limit, maxRequests = 3_000)
        val next = io.astrolabe.budget.LimitRule.nextCost(spend, estimate)
        assertEquals(io.astrolabe.budget.LimitDecision.Within, io.astrolabe.budget.LimitRule.decide(limits, spend, next))
        val requests = io.astrolabe.budget.LimitRule.decide(io.astrolabe.budget.TaskLimits(maxCost = limit, maxRequests = 1), spend, next)
        assertEquals(io.astrolabe.budget.LimitKind.Requests, assertIs<io.astrolabe.budget.LimitDecision.Exhausted>(requests).kind)
        val late = io.astrolabe.budget.LimitSpend.of(accounting.calls(ids.work), 60_000, "USD")
        val minutes = io.astrolabe.budget.LimitRule.decide(io.astrolabe.budget.TaskLimits(maxCost = limit, maxMinutes = 1), late, next)
        assertEquals(io.astrolabe.budget.LimitKind.Minutes, assertIs<io.astrolabe.budget.LimitDecision.Exhausted>(minutes).kind)
    }

    @Test
    fun `a subscription model is charged at its official price as nominal spend, counted as real and stopped by a money limit`() = withStore { store ->
        val prices = mapOf(BillingDimension.UNCACHED_INPUT to "2", BillingDimension.OUTPUT to "10")
        val paid = plain(table(prices))
        val nominal = plain(table(prices).copy(billing = Billing.Plan))
        // Admission and routing read one price: a subscription model never ranks as a free one.
        assertEquals(Accounting.estimateCost(paid, 100_000, 10_000), Accounting.estimateCost(nominal, 100_000, 10_000))
        val estimate = Accounting.estimateCost(nominal, 100_000, 10_000)
        assertEquals(0, BigDecimal("0.30").compareTo(estimate.amount))
        val accounting = Accounting(store, clock)
        // A nominal call never counts a reported bill: what a subscription "billed" is not its official price.
        val usage = BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 100_000L, BillingDimension.OUTPUT to 10_000L), provenance,
            billed = io.astrolabe.provider.Money.zero("USD"))
        for (id in listOf("first", "second")) {
            assertTrue(accounting.reserve(ids, id, nominal, 110_000, estimate, 1_000_000, null))
            val call = accounting.record(ids, id, nominal, null, usage)
            assertEquals(Charge.Nominal, call.charge)
            assertEquals(0, BigDecimal("0.30").compareTo(call.money.amount))
        }
        val totals = Accounting.totals(accounting.calls(ids.work), 1, "USD")
        assertEquals(0, BigDecimal("0.60").compareTo(totals.money.amount), "nominal spend is counted as real")
        assertEquals(0, BigDecimal("0.60").compareTo(totals.nominalMoney!!.amount))
        assertEquals(0, totals.paidMoney!!.amount.signum())
        assertEquals(0, BigDecimal("0.60").compareTo(totals.costPerAcceptedTask!!.amount))
        val spend = io.astrolabe.budget.LimitSpend.of(accounting.calls(ids.work), 0, "USD")
        assertEquals(io.astrolabe.budget.CostBasis.Nominal, spend.costBasis)
        assertEquals(0, BigDecimal("0.60").compareTo(spend.cost!!.amount))
        assertEquals(0, BigDecimal("0.60").compareTo(spend.nominalCost!!.amount))
        assertEquals(0, spend.paidCost!!.amount.signum())
        val limits = io.astrolabe.budget.TaskLimits(maxCost = io.astrolabe.provider.Money("USD", BigDecimal("0.80")))
        val stop = io.astrolabe.budget.LimitRule.decide(limits, spend, io.astrolabe.budget.LimitRule.nextCost(spend, estimate))
        val exhausted = assertIs<io.astrolabe.budget.LimitDecision.Exhausted>(stop)
        assertEquals(io.astrolabe.budget.LimitKind.Cost, exhausted.kind)
        assertTrue("(nominal)" in exhausted.reason, exhausted.reason)
        val status = io.astrolabe.budget.LimitRule.status(limits, spend)
        assertEquals(spend.nominalCost, status.nominalCost)
        // A positive bill on the subscription is real money: the call is paid at the bill, counted once.
        val charged = accounting.record(ids, "third", nominal, null, usage.copy(billed = io.astrolabe.provider.Money("USD", BigDecimal("2"))))
        assertEquals(Charge.Paid, charged.charge)
        assertEquals(0, BigDecimal("2").compareTo(charged.money.amount))
        val after = io.astrolabe.budget.LimitSpend.of(accounting.calls(ids.work), 0, "USD")
        assertEquals(0, BigDecimal("2").compareTo(after.paidCost!!.amount))
        assertEquals(0, BigDecimal("0.60").compareTo(after.nominalCost!!.amount))
        assertEquals(0, BigDecimal("2.60").compareTo(after.cost!!.amount))
        assertEquals(0, BigDecimal("2").compareTo(Accounting.totals(accounting.calls(ids.work), 0, "USD").paidMoney!!.amount))
    }

    @Test
    fun `a reservation of unknown cost is refused under a cost cap`() = withStore { store ->
        val accounting = Accounting(store, clock)
        val unknown = Accounting.estimateCost(plain(table(mapOf(BillingDimension.CACHE_READ to "0.3", BillingDimension.OUTPUT to "2"))), 1_000, 1_000)
        assertFalse(accounting.reserve(ids, "first", FakeProfiles.main, 10, unknown, 100, io.astrolabe.provider.Money("USD", BigDecimal("10"))))
    }

    @Test
    fun `unsettled calls and failed extraction retain campaign funding`() = withStore { store ->
        val accounting = Accounting(store, clock)
        val free = io.astrolabe.provider.Money.zero("USD")
        assertTrue(accounting.reserve(ids, "first", FakeProfiles.main, 60, free, 100, null))
        assertFalse(accounting.reserve(ids, "second", FakeProfiles.main, 60, free, 100, null))
        accounting.record(ids, "first", FakeProfiles.main, null,
            BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 10L, BillingDimension.OUTPUT to 10L), provenance))
        assertTrue(accounting.reserveExtraction(ids, "extraction", 60, null, 100, null, "USD"))
        accounting.extraction(ids, "extraction", null, 60, null)
        assertEquals(20L, accounting.remainingTokens(work, 100))
        assertFalse(accounting.reserveExtraction(ids, "next-extraction", 30, null, 100, null, "USD"))
    }

    @Test
    fun `a call settled without usage keeps its reserved money as known funding under a cost cap`() = withStore { store ->
        val accounting = Accounting(store, clock)
        val cap = io.astrolabe.provider.Money("USD", BigDecimal("1.00"))
        val reserved = io.astrolabe.provider.Money("USD", BigDecimal("0.30"))
        assertTrue(accounting.reserve(ids, "first", FakeProfiles.main, 10, reserved, 100, cap))
        accounting.record(ids, "first", FakeProfiles.main, null, null)
        assertEquals(io.astrolabe.provider.Money("USD", BigDecimal("0.70")), accounting.remainingCost(work, cap))
        assertTrue(accounting.reserve(ids, "second", FakeProfiles.main, 10, reserved, 100, cap))
        assertTrue(accounting.reserveExtraction(ids, "extraction", 10, reserved, 100, cap, "USD"))
        accounting.extraction(ids, "extraction", null, 10, null, "USD", reserved)
        assertFalse(accounting.reserve(ids, "third", FakeProfiles.main, 10, reserved, 100, cap))
        assertTrue(accounting.reserve(ids, "fourth", FakeProfiles.main, 10, io.astrolabe.provider.Money("USD", BigDecimal("0.10")), 100, cap))
    }

    @Test
    fun `FX-59 a call without usage is unknown spend, never zero, and poisons the totals honestly`() = withStore { store ->
        val accounting = Accounting(store, clock)
        val priced = accounting.record(ids, "inv-1", FakeProfiles.main, null, BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000L, BillingDimension.OUTPUT to 100L), provenance))
        assertFalse(priced.money.unknown)
        assertTrue(priced.money.amount.signum() > 0, "priced from the profile's dated table")
        assertEquals(FakeProfiles.main.priceTable.date.toString(), priced.priceTableDate)
        val missing = accounting.record(ids, "inv-2", FakeProfiles.main, null, null)
        assertTrue(missing.money.unknown)
        assertNull(missing.quantities.billedUsage)
        val partial = accounting.record(ids, "inv-3", FakeProfiles.main, null, BillableUsage.missing(provenance, FakeProfiles.dimensions))
        assertTrue(partial.money.unknown)

        val totals = accounting.totals(work, accepted = 1, currency = "USD")
        assertEquals(3, totals.calls)
        assertEquals(1, totals.callsWithoutUsage)
        assertTrue(totals.money.unknown, "one unknown call makes the sum unknown")
        assertNull(totals.costPerAcceptedTask, "no economic claim over unknown spend")
        assertNull(totals.quantities[BillingDimension.OUTPUT])
        assertNull(Accounting.totals(accounting.calls(work).take(1), accepted = 0, currency = "USD").costPerAcceptedTask, "undefined at zero accepted")
        assertEquals(0, priced.money.amount.compareTo(Accounting.totals(accounting.calls(work).take(1), 1, "USD").costPerAcceptedTask!!.amount))
    }

    @Test
    fun `cold and warm calls are reported apart`() = withStore { store ->
        val accounting = Accounting(store, clock)
        accounting.record(ids, "inv-1", FakeProfiles.main, null, BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 1_000L, BillingDimension.CACHE_READ to 0L), provenance))
        accounting.record(ids, "inv-2", FakeProfiles.main, null, BillableUsage(mapOf(BillingDimension.UNCACHED_INPUT to 100L, BillingDimension.CACHE_READ to 900L), provenance))
        val totals = accounting.totals(work, 1, "USD")
        assertTrue(totals.coldMoney.amount > BigDecimal.ZERO && totals.warmMoney.amount > BigDecimal.ZERO)
        assertEquals(0, (totals.coldMoney + totals.warmMoney).amount.compareTo(totals.money.amount))
    }

    @Test
    fun `exports are derived files, and the OpenTelemetry span file exists only behind its flag`() = withStore { store ->
        Accounting(store, clock).record(ids, "inv-1", FakeProfiles.main, null, BillableUsage(mapOf(BillingDimension.OUTPUT to 10L), provenance))
        val spans = Spans(FixedIdGen())
        spans.end(spans.start(Phase.Edit, ids))
        val off = Export(store).write(work, 1, "USD", spans)
        assertEquals(listOf("usage.json", "accounting.json"), off.map { it.fileName.toString() })
        assertTrue(off.all { it.startsWith(store.layout.exports) })
        val on = Export(store, Config(flags = Flags(otelExport = true))).write(work, 1, "USD", spans)
        val otel = Files.readString(on.last())
        assertTrue("\"gen_ai.request.model\":\"fake-main\"" in otel && "\"name\":\"edit\"" in otel, otel)
    }
}
