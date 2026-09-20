package io.astrolabe.evidence

import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.time.Clock

/** Persistence seam for receipts (§4.3): immutable rows, one per check invocation; the scheduler is the writer. */
public interface Receipts {
    /** Records [receipt]; its raw blob, when any, must already be published. A receipt id is never reused. */
    public fun record(receipt: Receipt)

    public fun get(receiptId: String): Receipt?

    /** Every receipt of [checkId], oldest first. */
    public fun forCheck(checkId: String): List<Receipt>
}

public class InMemoryReceipts : Receipts {
    private val rows = LinkedHashMap<String, Receipt>()

    @Synchronized
    override fun record(receipt: Receipt) {
        require(receipt.receiptId !in rows) { "receipt ${receipt.receiptId} already recorded" }
        rows[receipt.receiptId] = receipt
    }

    @Synchronized
    override fun get(receiptId: String): Receipt? = rows[receiptId]

    @Synchronized
    override fun forCheck(checkId: String): List<Receipt> = rows.values.filter { it.checkId == checkId }
}

/** Receipts in the store (`receipts` table). */
public class SqliteReceipts(private val store: Store, private val clock: Clock) : Receipts {
    override fun record(receipt: Receipt): Unit = store.db.tx { tx ->
        tx.execute(
            "INSERT INTO receipts (receipt_id, work_id, attempt_id, candidate_id, context_id, check_id, stamp_before, stamp_after, outcome, raw_blob, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            receipt.receiptId, receipt.ids.work, receipt.ids.attempt, receipt.stampAfter, receipt.ids.context, receipt.checkId,
            receipt.stampBefore, receipt.stampAfter, receipt.outcome.name, receipt.raw, Migrations.SCHEMA_VERSION, clock.instant(),
            JSON.encodeToString(Receipt.serializer(), receipt),
        )
    }

    override fun get(receiptId: String): Receipt? =
        store.db.query("SELECT body FROM receipts WHERE receipt_id = ?", receiptId) { decode(it.string("body")) }.firstOrNull()

    override fun forCheck(checkId: String): List<Receipt> =
        store.db.query("SELECT body FROM receipts WHERE check_id = ? ORDER BY created_at, rowid", checkId) { decode(it.string("body")) }

    private fun decode(body: String) = JSON.decodeFromString(Receipt.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
