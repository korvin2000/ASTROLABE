package io.astrolabe.eval.audit

import io.astrolabe.Defaults
import io.astrolabe.provider.SerializableBigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** One run's log to audit: [arm] labels the input it came from (a results directory, a journal folder); [result] is eval-live's `result.json`. */
public data class AuditInput(val source: String, val arm: String, val journal: Journal, val result: JsonObject?)

/** Which run an audit is of. */
@Serializable
public data class RunInfo(
    val source: String,
    val arm: String,
    val group: String,
    val format: JournalFormat,
    val task: String?,
    val model: String?,
    val repeat: Int?,
    val turns: Int,
    val unreadableLines: Int,
)

/** Everything the auditor concludes about one run. */
@Serializable
public data class RunAudit(
    val run: RunInfo,
    val anatomy: Anatomy,
    val cache: CacheReport,
    val wastes: List<WasteShare>,
    val residency: ResidencyWhatIf,
    val provenance: Provenance,
)

/**
 * A group's sums (model × arm): categories and losses as shares of the summed totals; a loss unmeasured in any run is
 * unmeasured, and [complete] is false when any run's totals are partial.
 */
@Serializable
public data class GroupAudit(
    val group: String,
    val runs: Int,
    val calls: Int,
    val externallyAccepted: Int?,
    val acceptUnverified: Int,
    val billed: SerializableBigDecimal?,
    val estimate: SerializableBigDecimal?,
    val total: SerializableBigDecimal?,
    val complete: Boolean,
    val uncachedInput: Category,
    val cacheRead: Category,
    val cacheWrite: Category,
    val output: Category,
    val reasoning: Category,
    val hitShare: Double?,
    val qHat: Double?,
    val wastes: List<WasteShare>,
)

@Serializable
public data class AuditReport(val schema: Int, val prices: List<PriceFit>, val runs: List<RunAudit>, val groups: List<GroupAudit>) {
    public companion object {
        public const val SCHEMA: Int = 1
    }
}

/** The offline auditor (§4.7, B1): reads run logs, never runs anything; every result is a pure function of the logs and prices. */
public object Audit {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Audits [inputs] together: prices are fitted over all their billed calls, per route. */
    @JvmStatic
    @JvmOverloads
    public fun run(inputs: List<AuditInput>, catalog: Catalog = Catalog.EMPTY, defaults: Defaults = Defaults()): AuditReport {
        val traces = inputs.map { RunTrace.of(it.journal) }
        val book = PriceBook.of(traces.flatMap { it.calls }, catalog)
        val runs = inputs.zip(traces).map { (input, trace) -> audit(input, trace, book, defaults) }
        val groups = runs.groupBy { it.run.group }.map { (group, members) -> group(group, members) }
        return AuditReport(AuditReport.SCHEMA, book.fits, runs, groups)
    }

    private fun audit(input: AuditInput, trace: RunTrace, book: PriceBook, defaults: Defaults): RunAudit {
        val model = input.result?.let { r -> r.str("model")?.let { m -> r.str("provider")?.let { "$it/$m" } ?: m } }
            ?: trace.calls.firstNotNullOfOrNull { it.binding }?.let { "${it.provider}/${it.model}" }
        val info = RunInfo(
            source = input.source, arm = input.arm, group = "${input.arm} · ${model ?: "?"}", format = input.journal.format,
            task = input.result?.str("task"), model = model, repeat = input.result?.int("repeat"),
            turns = trace.activity.size, unreadableLines = input.journal.unreadableLines,
        )
        val anatomy = Anatomy.of(trace.calls, book)
        val losses = Losses(trace, input.journal.format, book, defaults.k)
        val cache = losses.cache()
        return RunAudit(
            info, anatomy, cache, losses.wastes(anatomy, cache),
            ResidencyReplay.of(trace, losses, book, input.journal.format, defaults),
            Provenance.of(input.journal, trace, input.result),
        )
    }

    private fun group(name: String, runs: List<RunAudit>): GroupAudit {
        fun sum(values: List<BigDecimal?>): BigDecimal? = if (values.any { it == null }) null else values.fold(BigDecimal.ZERO) { s, v -> s + v!! }
        val total = sum(runs.map { it.anatomy.total })
        fun category(pick: (Anatomy) -> Category): Category {
            val categories = runs.map { pick(it.anatomy) }
            val tokens = if (categories.any { it.tokens == null }) null else categories.sumOf { it.tokens!! }
            val money = sum(categories.map { it.money })
            return Category(tokens, money?.stripTrailingZeros(), AuditMath.share(money, total), categories.any { it.reported })
        }
        val read = runs.sumOf { it.cache.cacheReadTokens }
        val input = runs.sumOf { it.cache.inputTokens }
        val wastes = Waste.entries.map { waste ->
            val shares = runs.map { r -> r.wastes.first { it.waste == waste } }
            val unmeasured = shares.count { it.unmeasured != null }
            if (unmeasured > 0) {
                WasteShare(waste, null, null, null, null, null, "unmeasured in $unmeasured of ${runs.size} runs: ${shares.first { it.unmeasured != null }.unmeasured}")
            } else {
                val money = sum(shares.map { it.money })
                WasteShare(waste, money?.stripTrailingZeros(), AuditMath.share(money, total), shares.sumOf { it.calls ?: 0 }, shares.sumOf { it.tokens ?: 0 }, null, null)
            }
        }
        val accepted = runs.map { it.provenance.external }
        return GroupAudit(
            group = name,
            runs = runs.size,
            calls = runs.sumOf { it.anatomy.calls },
            externallyAccepted = if (accepted.any { it == null }) null else accepted.count { it!!.startsWith("passed") },
            acceptUnverified = runs.count { it.provenance.acceptUnverified == true },
            billed = sum(runs.map { it.anatomy.billed })?.stripTrailingZeros(),
            estimate = sum(runs.map { it.anatomy.estimate })?.stripTrailingZeros(),
            total = total?.stripTrailingZeros(),
            complete = runs.all { it.anatomy.complete },
            uncachedInput = category { it.uncachedInput },
            cacheRead = category { it.cacheRead },
            cacheWrite = category { it.cacheWrite },
            output = category { it.output },
            reasoning = category { it.reasoning },
            hitShare = AuditMath.hitShare(read, input),
            qHat = runs.sumOf { it.cache.cacheableTokens }.takeIf { it > 0 }?.let { b -> runs.sumOf { it.cache.cachedTokens }.toDouble() / b },
            wastes = wastes,
        )
    }

    /**
     * The runs under [path]: a journal file, or every `*.jsonl` below a directory (eval-live's `runs/<task>/<model>/r<n>/events.jsonl`
     * with its sibling `result.json`; Studio's `W-*.jsonl`). The arm is the file's folder name, or the directory's name.
     */
    @JvmStatic
    public fun discover(path: Path): List<AuditInput> {
        val files = when {
            path.isRegularFile() -> listOf(path)
            path.isDirectory() -> Files.walk(path).use { s -> s.filter { it.isRegularFile() && it.extension == "jsonl" }.sorted().toList() }
            else -> throw IllegalArgumentException("no journal or directory at $path")
        }
        val arm = (if (path.isDirectory()) path else path.toAbsolutePath().parent)?.name ?: path.toString()
        return files.map { file ->
            val result = file.resolveSibling("result.json").takeIf { it.isRegularFile() }
                ?.let { Json.parseToJsonElement(Files.readString(it, StandardCharsets.UTF_8)) as? JsonObject }
            val source = if (path.isDirectory()) path.relativize(file).toString().replace('\\', '/') else file.name
            AuditInput(source, arm, Journal.read(file), result)
        }
    }

    /** List prices: [extra] when given, else the catalog snapshot at the root of the first results directory among [paths] that has one. */
    @JvmStatic
    public fun catalog(paths: List<Path>, extra: Path?): Catalog {
        val files = listOfNotNull(extra) + paths.map { it.resolve("catalog-snapshot.json") }.filter { it.isRegularFile() }
        return files.firstOrNull()?.let(Catalog::read) ?: Catalog.EMPTY
    }

    @JvmStatic
    public fun toJson(report: AuditReport): String = json.encodeToString(AuditReport.serializer(), report)
}
