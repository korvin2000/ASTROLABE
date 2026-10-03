package io.astrolabe.evidence

import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.store.Tx
import kotlinx.serialization.json.Json
import java.time.Clock

/** The durable allowance a begin marker claims before dispatching an automatic regression run. */
public enum class ReceiptClaim {
    Baseline,
    StopRerun,
    ;

    internal fun validate(marker: Receipt) {
        val kind = if (this == Baseline) "baseline_started" else "rerun_started"
        require(marker.limits.any { it.kind == kind }) { "claim requires its begin marker" }
        require(this != StopRerun || marker.workspaceId != null) { "a stop rerun claim requires durable workspace attribution" }
    }

    internal fun matches(marker: Receipt, previous: Receipt): Boolean {
        if (previous.ids.work != marker.ids.work || previous.ids.attempt != marker.ids.attempt || previous.checkId != marker.checkId) return false
        return when (this) {
            Baseline -> previous.limits.any { it.kind == "baseline" || it.kind == "baseline_started" }
            StopRerun -> previous.limits.any { it.kind == "rerun_started" } &&
                (previous.workspaceId == marker.workspaceId || previous.workspaceId == null) &&
                previous.checkDefinitionVersion == marker.checkDefinitionVersion && previous.stampAfter == marker.stampAfter
        }
    }
}

/** Persistence seam for receipts (§4.3): immutable rows, one per check invocation; the scheduler is the writer. */
public interface Receipts {
    /** Records [receipt]; its raw blob, when any, must already be published. A receipt id is never reused. */
    public fun record(receipt: Receipt)

    public fun get(receiptId: String): Receipt?

    /** Every receipt of [checkId] in the order it was recorded (I-05: never by its timestamp). */
    public fun forCheck(checkId: String): List<Receipt>

    /**
     * Atomically records [marker] only if [scope] has not been claimed. Shared backing stores must override this
     * per-instance default with a transaction covering the lookup and insertion across all their writers.
     */
    public fun claim(marker: Receipt, scope: ReceiptClaim): Boolean = synchronized(this) {
        scope.validate(marker)
        if (forCheck(marker.checkId).any { scope.matches(marker, it) }) false else {
            record(marker)
            true
        }
    }
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

    @Synchronized
    override fun claim(marker: Receipt, scope: ReceiptClaim): Boolean {
        scope.validate(marker)
        if (rows.values.any { scope.matches(marker, it) }) return false
        record(marker)
        return true
    }
}

/** Receipts in the store (`receipts` table). */
public class SqliteReceipts(private val store: Store, private val clock: Clock) : Receipts {
    override fun record(receipt: Receipt): Unit = store.db.tx { tx -> insert(tx, receipt) }

    override fun claim(marker: Receipt, scope: ReceiptClaim): Boolean = store.db.tx { tx ->
        scope.validate(marker)
        val previous = tx.query("SELECT body FROM receipts WHERE work_id = ? AND attempt_id = ? AND check_id = ? ORDER BY rowid",
            marker.ids.work, marker.ids.attempt, marker.checkId) { decode(it.string("body")) }
        if (previous.any { scope.matches(marker, it) }) false else {
            insert(tx, marker)
            true
        }
    }

    private fun insert(tx: Tx, receipt: Receipt) {
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
        store.db.query("SELECT body FROM receipts WHERE check_id = ? ORDER BY rowid", checkId) { decode(it.string("body")) }

    private fun decode(body: String) = JSON.decodeFromString(Receipt.serializer(), body)

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
