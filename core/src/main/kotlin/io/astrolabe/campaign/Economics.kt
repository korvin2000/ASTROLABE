package io.astrolabe.campaign

import io.astrolabe.cell.SqliteCheckpoints
import io.astrolabe.context.Manifest
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.EventRecord
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Money
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.CallAccount
import kotlinx.serialization.json.Json
import java.math.MathContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.Locale

/**
 * One cell's economics: why its context was built, the compiled `[K]` it paid a cache write for, the largest input it
 * then carried, its rebuilds, its calls and their money — [boundaryMoney] is its first call, where the cache write lands.
 */
public data class CellEconomics(
    val cell: String,
    val increment: String,
    val boundaryReason: String?,
    val kTokens: Long,
    val liveTokens: Long?,
    val rebuilds: Int,
    val calls: Int,
    val money: Money,
    val boundaryMoney: Money,
)

/**
 * The §16.2 break-even of one boundary, a diagnostic only: continuing the previous cell at [liveTokens] would cost
 * ρ·C_live per turn, the fresh cell pays ≈ 1.25·|K| once and ρ·|K| after, so it pays back after
 * `N_be ≈ 1.25·|K| / (ρ·(C_live − |K|))` turns — `null` when the fresh context is not smaller (it never pays back).
 */
public data class BreakEven(val cell: String, val kTokens: Long, val liveTokens: Long, val rho: Double, val turns: Double?)

/**
 * The per-campaign economics report of §16.2/§19.4 (P2.7.3), from manifests, the usage table, checkpoints and — for
 * the `[A]` share — the cell events. A quantity nothing measured is `null`, never zero. The live comparison
 * `B2 ≥ B1` cannot be measured against the fake provider: [liveGate] records it deferred (D-28).
 */
public data class EconomicsReport(
    val work: WorkId,
    val cells: List<CellEconomics>,
    val money: Money,
    /** First-call money of every cell over all money: what the boundaries cost. */
    val boundaryCostShare: Double?,
    val continuationsPerIncrement: Map<String, Int>,
    val rebuildsPerCell: Double?,
    /** `[A]` tokens over estimated request tokens, over every request of the campaign's events. */
    val anchorShare: Double?,
    val tokensByCacheClass: Map<BillingDimension, Long?>,
    val breakEven: List<BreakEven>,
    val liveGate: String = LIVE_GATE,
) {
    /** The report as Markdown, for `exports/<work>/economics.md`; shares and ratios are fixed-point in `Locale.ROOT`. */
    public fun render(): String = buildString {
        appendLine("# Economics — ${work.value}")
        appendLine()
        appendLine("- money: ${money(money)}")
        appendLine("- boundary cost share: ${ratio(boundaryCostShare)}")
        appendLine("- rebuilds per cell: ${ratio(rebuildsPerCell)}")
        appendLine("- `[A]` share: ${ratio(anchorShare)}")
        appendLine("- continuations per increment: " + continuationsPerIncrement.entries.joinToString(", ") { "${it.key} ${it.value}" })
        appendLine("- tokens by cache class: " + tokensByCacheClass.entries.sortedBy { it.key.id }.joinToString(", ") { "${it.key.id} ${it.value ?: "unknown"}" })
        appendLine("- live gate: $liveGate")
        appendLine()
        appendLine("| cell | increment | boundary | [K] | live | rebuilds | calls | money | boundary money |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (c in cells) {
            appendLine("| ${c.cell} | ${c.increment} | ${c.boundaryReason ?: "initial"} | ${c.kTokens} | ${c.liveTokens ?: "unknown"} | ${c.rebuilds} | ${c.calls} | ${money(c.money)} | ${money(c.boundaryMoney)} |")
        }
        if (breakEven.isNotEmpty()) {
            appendLine()
            appendLine("Break-even per boundary (§16.2, diagnostic):")
            for (b in breakEven) {
                appendLine("- ${b.cell}: |K| ${b.kTokens}, C_live ${b.liveTokens}, ρ ${ratio(b.rho)} ⇒ N_be ${b.turns?.let { ratio(it) } ?: "never (the fresh context is not smaller)"}")
            }
        }
    }

    public companion object {
        public const val LIVE_GATE: String = "B2 ≥ B1: UNMEASURED — deferred to live evaluation (D-28)"

        private fun money(m: Money): String = if (m.unknown) "unknown" else "${m.amount.stripTrailingZeros().toPlainString()} ${m.currency}"

        private fun ratio(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "unmeasured"
    }
}

public object Economics {
    /** §16.2: cached-read price relative to uncached input, when the caller has no price table of its own. */
    public const val DEFAULT_RHO: Double = 0.1

    /** The cache-write premium of §16.2 relative to uncached input. */
    private const val CACHE_WRITE: Double = 1.25

    private val JSON = Json { ignoreUnknownKeys = true }

    /** The report of [c]'s campaign; [records] are its events (for the `[A]` share), [currency] its money unit. */
    @JvmStatic
    @JvmOverloads
    public fun report(c: OpenedCampaign, clock: Clock, records: List<EventRecord> = emptyList(), currency: String = "USD", rho: Double = DEFAULT_RHO): EconomicsReport {
        require(rho > 0 && rho < 1) { "ρ must be in (0, 1)" }
        val work = c.ids.work
        val state = checkNotNull(c.campaigns.load(work, c.ids.attempt)) { "no campaign state for ${work.value}" }
        val manifests = c.store.db.query("SELECT context_id, body FROM manifests WHERE work_id = ? ORDER BY rowid", work) {
            it.string("context_id") to JSON.decodeFromString(Manifest.serializer(), it.string("body"))
        }.toMap()
        val calls = Accounting(c.store, clock).calls(work)
        val byCell = calls.groupBy { it.ids.context?.value }
        val checkpoints = SqliteCheckpoints(c.store, clock)
        val cells = manifests.map { (cell, manifest) ->
            val own = byCell[cell].orEmpty()
            CellEconomics(
                cell = cell,
                increment = manifest.incrementId,
                boundaryReason = manifest.boundaryReason?.wire,
                kTokens = manifest.arithmetic.selected,
                liveTokens = own.mapNotNull { it.quantities.modelVisibleInput }.maxOrNull(),
                rebuilds = checkpoints.latest(ContextId(cell))?.rebuilds ?: 0,
                calls = own.size,
                money = sum(own, currency),
                boundaryMoney = own.firstOrNull()?.money ?: Money.zero(currency),
            )
        }
        val total = sum(calls, currency)
        val boundary = cells.fold(Money.zero(currency)) { acc, cell -> acc + cell.boundaryMoney }
        val implementing = state.graph.increments.filter { it.cells.isNotEmpty() }
        val requested = records.map { it.event }.filterIsInstance<AgentEvent.Cell.ModelRequested>().filter { it.ids.work == work }
        return EconomicsReport(
            work = work,
            cells = cells,
            money = total,
            boundaryCostShare = share(boundary, total),
            continuationsPerIncrement = implementing.associate { it.id to it.cells.size - 1 },
            rebuildsPerCell = if (cells.isEmpty()) null else cells.sumOf { it.rebuilds }.toDouble() / cells.size,
            anchorShare = requested.takeIf { r -> r.isNotEmpty() && r.all { it.anchorTokens != null } && r.sumOf { it.estimatedTokens } > 0 }
                ?.let { r -> r.sumOf { it.anchorTokens!! }.toDouble() / r.sumOf { it.estimatedTokens } },
            tokensByCacheClass = Accounting.totals(calls, state.graph.increments.count { it.status == IncrementStatus.Verified }, currency).quantities,
            breakEven = cells.zipWithNext().mapNotNull { (previous, next) ->
                val live = previous.liveTokens ?: return@mapNotNull null
                val saving = rho * (live - next.kTokens)
                BreakEven(next.cell, next.kTokens, live, rho, if (saving > 0) CACHE_WRITE * next.kTokens / saving else null)
            },
        )
    }

    /** Writes `exports/<work>/economics.md`, a derived view (§2.3: rebuildable, never canonical). */
    @JvmStatic
    public fun export(c: OpenedCampaign, report: EconomicsReport): Path {
        val dir = c.store.layout.exports.resolve(report.work.value)
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve("economics.md"), report.render())
    }

    private fun sum(calls: List<CallAccount>, currency: String): Money = calls.fold(Money.zero(currency)) { acc, call -> acc + call.money }

    private fun share(part: Money, whole: Money): Double? =
        if (part.unknown || whole.unknown || whole.amount.signum() == 0) null else part.amount.divide(whole.amount, MathContext.DECIMAL64).toDouble()
}
