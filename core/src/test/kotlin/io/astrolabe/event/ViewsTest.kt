package io.astrolabe.event

import io.astrolabe.fixtures.StoreInspector
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.store.openStore
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

/** P0.4.3: read projections over the P0.5 tables (§4, risk 19). */
class ViewsTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `the contract view joins every contract-owned table in a stable order`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).contract(SEEDED_WORK)

            assertEquals(listOf("1", "2"), view.contracts.map { it.key })
            assertEquals(2L, view.latestVersion)
            assertEquals(listOf("request-1", "request-2"), view.requests.map { it.key })
            assertEquals(listOf("req-a", "req-b"), view.requirements.map { it.key })
            assertEquals(listOf("acc-1"), view.acceptance.map { it.key })
            assertEquals(listOf("con-1"), view.constraints.map { it.key })
            assertEquals(listOf("amend-1"), view.amendments.map { it.key })

            val first = view.contracts.first()
            assertEquals("contracts", first.table)
            assertEquals(SEEDED_WORK, first.identities.work)
            assertEquals(SEEDED_AT, first.createdAt)
            assertEquals(JsonPrimitive("first"), first.body.jsonObject["title"])
        }
    }

    @Test
    fun `the ledger view reads increments, entries and sizing`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).ledger(SEEDED_WORK)

            assertEquals(listOf("inc-1"), view.increments.map { it.key })
            assertEquals(listOf("req-a"), view.entries.map { it.key })
            assertEquals(listOf("inc-1/cell-1"), view.sizing.map { it.key })
        }
    }

    @Test
    fun `the register view names the latest version and counts the history`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).register(SEEDED_CONTEXT)

            val latest = assertNotNull(view.latest)
            assertEquals(3, view.historyCount)
            assertEquals("3", latest.key)
            assertEquals(JsonPrimitive(3), latest.body.jsonObject["v"])
        }
    }

    @Test
    fun `the workset view lists the exports of one cell`() {
        openStore(root).use { store ->
            store.seedCampaign()
            assertEquals(listOf("ws-1", "ws-2"), Views(store).workset(SEEDED_CONTEXT).exports.map { it.key })
        }
    }

    @Test
    fun `the checks view groups receipts by check`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).checks(SEEDED_WORK)

            assertEquals(listOf("check-build", "check-unit"), view.byCheck.keys.toList())
            assertEquals(listOf("receipt-3"), view.byCheck.getValue("check-build").map { it.key })
            assertEquals(listOf("receipt-1", "receipt-2"), view.byCheck.getValue("check-unit").map { it.key })
            assertEquals(2, view.checkCount)
            assertEquals(3, view.receiptCount)
        }
    }

    @Test
    fun `the budget view aggregates the per-call usage rows`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).budget(SEEDED_WORK)

            assertEquals(2, view.invocations)
            assertEquals(listOf("inv-1", "inv-2"), view.usage.map { it.key })
        }
    }

    @Test
    fun `the receipt view lists every receipt of a work item`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val view = Views(store).receipts(SEEDED_WORK)
            assertEquals(listOf("receipt-1", "receipt-2", "receipt-3"), view.receipts.map { it.key })
        }
    }

    @Test
    fun `the finish receipt view finds the packet of that kind, or nothing`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val views = Views(store)

            val packet = assertNotNull(views.finishReceipt(SEEDED_WORK).packet)
            assertEquals("packet-finish", packet.key)
            assertEquals(JsonPrimitive("delivered"), packet.body.jsonObject["outcome"])

            assertNull(views.finishReceipt(WorkId("other-work")).packet)
        }
    }

    @Test
    fun `views of an unknown work item or context are empty, never a failure`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val views = Views(store)
            val unknownWork = WorkId("no-such-work")
            val unknownContext = ContextId("no-such-context")

            assertTrue(views.contract(unknownWork).contracts.isEmpty())
            assertTrue(views.ledger(unknownWork).increments.isEmpty())
            assertTrue(views.checks(unknownWork).byCheck.isEmpty())
            assertEquals(0, views.budget(unknownWork).invocations)
            assertTrue(views.receipts(unknownWork).receipts.isEmpty())
            assertNull(views.register(unknownContext).latest)
            assertTrue(views.workset(unknownContext).exports.isEmpty())
        }
    }

    @Test
    fun `reading the views never writes`() {
        openStore(root).use { store ->
            store.seedCampaign()
            val inspector = StoreInspector(store)
            val before = inspector.counts()

            val views = Views(store)
            views.contract(SEEDED_WORK)
            views.ledger(SEEDED_WORK)
            views.register(SEEDED_CONTEXT)
            views.workset(SEEDED_CONTEXT)
            views.checks(SEEDED_WORK)
            views.budget(SEEDED_WORK)
            views.receipts(SEEDED_WORK)
            views.finishReceipt(SEEDED_WORK)

            assertEquals(before, inspector.counts(), "a projection must not change a single row")
        }
    }
}
