package io.astrolabe.event

import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.store.Row
import io.astrolabe.store.Store
import io.astrolabe.store.StoredRow
import io.astrolabe.store.storedRow
import kotlinx.serialization.Serializable

/** Contract authority as one joined read: the contract versions and everything hanging off them (§4.1). */
@Serializable
public data class ContractView(
    val work: WorkId,
    val contracts: List<StoredRow>,
    val requests: List<StoredRow>,
    val requirements: List<StoredRow>,
    val acceptance: List<StoredRow>,
    val constraints: List<StoredRow>,
    val amendments: List<StoredRow>,
) {
    /** Highest contract version present, or `null` before the first contract is committed. */
    val latestVersion: Long? get() = contracts.mapNotNull { it.key.toLongOrNull() }.maxOrNull()
}

/** Increments, their ledger transitions and their sizing (§4.2). The controller is the only writer. */
@Serializable
public data class LedgerView(
    val work: WorkId,
    val increments: List<StoredRow>,
    val entries: List<StoredRow>,
    val sizing: List<StoredRow>,
)

/** The STATE register of one cell: the current version and how many preceded it (§5.2). */
@Serializable
public data class RegisterView(
    val context: ContextId,
    val latest: StoredRow?,
    val historyCount: Int,
)

/** Exported worksets of one cell (§5.3). */
@Serializable
public data class WorksetView(
    val context: ContextId,
    val exports: List<StoredRow>,
)

/** Receipts grouped by check, in check order then receipt order (§8.1). */
@Serializable
public data class ChecksView(
    val work: WorkId,
    val byCheck: Map<String, List<StoredRow>>,
) {
    val checkCount: Int get() = byCheck.size
    val receiptCount: Int get() = byCheck.values.sumOf { it.size }
}

/** Per-invocation usage as recorded, native and normalized side by side (§15.2). */
@Serializable
public data class BudgetView(
    val work: WorkId,
    val invocations: Int,
    val usage: List<StoredRow>,
)

/** Every receipt of a work item, immutable as recorded (§4.3). */
@Serializable
public data class ReceiptView(
    val work: WorkId,
    val receipts: List<StoredRow>,
)

/** The finish receipt packet, once the campaign has produced one (§3.7). */
@Serializable
public data class FinishReceiptView(
    val work: WorkId,
    val packet: StoredRow?,
)

/**
 * Read projections over the P0.5 tables (§4, risk 19: "the exports exist for a UI to consume").
 *
 * Every function here is a pure `SELECT`: views never write, never derive authority and never
 * compete with the database — the Markdown and JSON under `exports/` are derived views of exactly
 * these reads. Every query carries a total `ORDER BY` so two runs over the same rows produce the
 * same projection, which is what makes [Export] byte-deterministic.
 */
public class Views(private val store: Store) {

    public fun contract(work: WorkId): ContractView = ContractView(
        work = work,
        contracts = rows("contracts", "$COMMON, version", "work_id = ?", "version", work.value) {
            it.long("version").toString()
        },
        requests = rows("requests", "$COMMON, id, seq", "work_id = ?", "id", work.value) { it.string("id") },
        requirements = rows(
            "requirements", "$COMMON, id, contract_version", "work_id = ?", "contract_version, id", work.value,
        ) { it.string("id") },
        acceptance = rows(
            "acceptance", "$COMMON, id, contract_version, kind", "work_id = ?", "contract_version, id", work.value,
        ) { it.string("id") },
        constraints = rows(
            "constraints", "$COMMON, id, contract_version", "work_id = ?", "contract_version, id", work.value,
        ) { it.string("id") },
        amendments = rows("amendments", "$COMMON, id, status", "work_id = ?", "id", work.value) { it.string("id") },
    )

    public fun ledger(work: WorkId): LedgerView = LedgerView(
        work = work,
        increments = rows("increments", "$COMMON, id, status", "work_id = ?", "id", work.value) { it.string("id") },
        entries = rows(
            "ledger", "$COMMON, requirement_id, status", "work_id = ?", "requirement_id", work.value,
        ) { it.string("requirement_id") },
        sizing = rows(
            "sizing", "$COMMON, increment_id, cell_id", "work_id = ?", "increment_id, cell_id", work.value,
        ) { "${it.string("increment_id")}/${it.string("cell_id")}" },
    )

    public fun register(context: ContextId): RegisterView {
        val versions = rows(
            "register_versions", "$COMMON, version", "context_id = ?", "version DESC", context.value,
        ) { it.long("version").toString() }
        return RegisterView(context = context, latest = versions.firstOrNull(), historyCount = versions.size)
    }

    public fun workset(context: ContextId): WorksetView = WorksetView(
        context = context,
        exports = rows("workset_exports", "$COMMON, id", "context_id = ?", "id", context.value) { it.string("id") },
    )

    public fun checks(work: WorkId): ChecksView {
        val grouped = LinkedHashMap<String, MutableList<StoredRow>>()
        store.db.query(
            "SELECT $COMMON, $RECEIPT_COLUMNS FROM receipts WHERE work_id = ? ORDER BY check_id, receipt_id",
            work.value,
        ) { row -> row.string("check_id") to row.storedRow("receipts", row.string("receipt_id")) }
            .forEach { (checkId, receipt) -> grouped.getOrPut(checkId) { ArrayList() }.add(receipt) }
        return ChecksView(work = work, byCheck = grouped)
    }

    public fun budget(work: WorkId): BudgetView {
        val usage = rows(
            "usage", "$COMMON, invocation_id, profile_id, native, normalized", "work_id = ?", "invocation_id", work.value,
        ) { it.string("invocation_id") }
        return BudgetView(work = work, invocations = usage.size, usage = usage)
    }

    public fun receipts(work: WorkId): ReceiptView = ReceiptView(
        work = work,
        receipts = rows("receipts", "$COMMON, $RECEIPT_COLUMNS", "work_id = ?", "receipt_id", work.value) {
            it.string("receipt_id")
        },
    )

    public fun finishReceipt(work: WorkId): FinishReceiptView {
        val packets = rows(
            "packets", "$COMMON, id, kind", "work_id = ? AND kind = ?", "created_at, id",
            work.value, FINISH_RECEIPT_KIND,
        ) { it.string("id") }
        return FinishReceiptView(work = work, packet = packets.lastOrNull())
    }

    private fun rows(
        table: String,
        columns: String,
        where: String,
        order: String,
        vararg params: Any?,
        key: (Row) -> String,
    ): List<StoredRow> = store.db.query(
        "SELECT $columns FROM $table WHERE $where ORDER BY $order",
        *params,
    ) { it.storedRow(table, key(it)) }

    public companion object {
        /** Packet kind of the campaign's finish receipt (§3.7). */
        public const val FINISH_RECEIPT_KIND: String = "finish_receipt"

        /** The columns every table carries; a projection always reads them (§3.3, D-25). */
        private const val COMMON =
            "work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body"

        private const val RECEIPT_COLUMNS =
            "receipt_id, check_id, stamp_before, stamp_after, outcome, raw_blob"
    }
}
