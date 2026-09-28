package io.astrolabe.event

import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the read projections of [Views] under `exports/` as derived files: one JSON document per
 * view plus a Markdown summary (§4, risk 19).
 *
 * These files are views, never authorities — the database is canonical, and nothing reads an export
 * back in. They are written to be *diffable*: stable file names, a total order inside every view,
 * two-space indented JSON, LF line endings and UTF-8 regardless of platform, and no value that is
 * not already in a row. Writing twice over unchanged rows produces byte-identical files, so a diff
 * between two exports is a diff between two states.
 */
public object Export {
    private val JSON = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    /** File name of the Markdown summary. */
    public const val SUMMARY: String = "summary.md"

    /**
     * Writes every view of [work] — and, for each id in [contexts], the per-cell register and
     * workset views — into [exportsDir], creating it if needed. Returns the files written, in
     * written order.
     */
    @JvmStatic
    public fun write(
        views: Views,
        work: WorkId,
        exportsDir: Path,
        contexts: List<ContextId> = emptyList(),
    ): List<Path> {
        val documents = views.snapshot {
            val contract = views.contract(work)
            val ledger = views.ledger(work)
            val checks = views.checks(work)
            val budget = views.budget(work)
            val receipts = views.receipts(work)
            val finish = views.finishReceipt(work)
            val ordered = contexts.distinct().sortedBy { it.value }
            val registers = ordered.map(views::register)
            val worksets = ordered.map(views::workset)
            val result = linkedMapOf(
                "contract.json" to json(ContractView.serializer(), contract),
                "ledger.json" to json(LedgerView.serializer(), ledger),
                "checks.json" to json(ChecksView.serializer(), checks),
                "budget.json" to json(BudgetView.serializer(), budget),
                "receipts.json" to json(ReceiptView.serializer(), receipts),
                "finish-receipt.json" to json(FinishReceiptView.serializer(), finish),
            )
            for (register in registers) {
                result["register-${register.context.value}.json"] = json(RegisterView.serializer(), register)
            }
            for (workset in worksets) {
                result["workset-${workset.context.value}.json"] = json(WorksetView.serializer(), workset)
            }
            result[SUMMARY] = summary(work, contract, ledger, checks, budget, receipts, finish, registers, worksets)
            result
        }
        Files.createDirectories(exportsDir)
        return documents.map { (name, content) -> text(exportsDir, name, content) }
    }

    private fun <T> json(serializer: KSerializer<T>, value: T): String =
        JSON.encodeToString(serializer, value) + "\n"

    private fun text(dir: Path, name: String, content: String): Path {
        val file = dir.resolve(name)
        Files.write(file, content.toByteArray(StandardCharsets.UTF_8))
        return file
    }

    private fun summary(
        work: WorkId,
        contract: ContractView,
        ledger: LedgerView,
        checks: ChecksView,
        budget: BudgetView,
        receipts: ReceiptView,
        finish: FinishReceiptView,
        registers: List<RegisterView>,
        worksets: List<WorksetView>,
    ): String = buildString {
        // Counts and ids only: an export that invented a timestamp would stop being diffable.
        append("# ").append(work.value).append("\n\n")
        append("Derived view of the project store. The database is canonical; this file is not.\n\n")
        append("## Contract\n\n")
        append("- contract versions: ").append(contract.contracts.size)
        contract.latestVersion?.let { append(" (latest v").append(it).append(")") }
        append("\n")
        append("- requests: ").append(contract.requests.size).append("\n")
        append("- requirements: ").append(contract.requirements.size).append("\n")
        append("- acceptance criteria: ").append(contract.acceptance.size).append("\n")
        append("- constraints: ").append(contract.constraints.size).append("\n")
        append("- amendments: ").append(contract.amendments.size).append("\n\n")
        append("## Ledger\n\n")
        append("- increments: ").append(ledger.increments.size).append("\n")
        append("- ledger entries: ").append(ledger.entries.size).append("\n")
        append("- sizing rows: ").append(ledger.sizing.size).append("\n\n")
        append("## Checks\n\n")
        append("- checks with receipts: ").append(checks.checkCount).append("\n")
        append("- receipts: ").append(receipts.receipts.size).append("\n")
        for ((checkId, rows) in checks.byCheck) {
            append("- `").append(checkId).append("`: ").append(rows.size).append("\n")
        }
        append("\n## Budget\n\n")
        append("- invocations: ").append(budget.invocations).append("\n\n")
        append("## Cells\n\n")
        if (registers.isEmpty()) {
            append("- none exported\n")
        } else {
            for (register in registers) {
                append("- `").append(register.context.value).append("`: register versions ")
                append(register.historyCount)
                val exports = worksets.first { it.context == register.context }.exports.size
                append(", workset exports ").append(exports).append("\n")
            }
        }
        append("\n## Finish receipt\n\n")
        append(finish.packet?.let { "- `${it.key}`\n" } ?: "- not issued\n")
    }
}
