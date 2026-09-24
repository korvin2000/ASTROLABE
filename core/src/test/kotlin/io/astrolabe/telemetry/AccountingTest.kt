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
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.UsageProvenance
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
