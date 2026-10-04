package io.astrolabe.context

import io.astrolabe.cell.Protocol
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.Receipt
import io.astrolabe.register.Mark
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which rule picks the Workset seeds a carry re-serves (§6.2): attempt configuration (`Defaults.seedRule`), frozen with
 * the attempt (invariant 12). [V1] is the structured protocol's rule and the default; [V2] is plan §4.3 "Seeds v2", for
 * a protocol without `Next`. Cell boundaries and pressure rebuilds take their seeds from the same rule.
 */
@Serializable
public enum class SeedRule(public val wire: String) {
    /** Entries the next plan step (`[>]`, else the first `[ ]`), its `accept`, `Next` or `Focus` refer to. */
    @SerialName("v1")
    V1("v1"),

    /** Touched ∪ red ∪ noted ∪ recent, in that priority under the one seed budget. */
    @SerialName("v2")
    V2("v2"),
    ;

    public val selector: SeedSelector
        get() = when (this) {
            V1 -> SeedSelector.V1
            V2 -> SeedSelector.V2
        }

    public companion object {
        /** A-D.7 K1: the rule a cell of [protocol] takes — [configured] in the structured protocol, always [V2] in the direct one. */
        @JvmStatic
        public fun of(protocol: Protocol, configured: SeedRule): SeedRule = if (protocol == Protocol.Direct) V2 else configured
    }
}

/**
 * Why an entry is a seed candidate. The declaration order is the Seeds v2 priority order. A candidate that is not
 * re-served is announced NOT SEEN unless it is only [Recent]: the recency filler is never announced.
 */
public enum class SeedReason(public val wire: String) {
    /** Seeds v1: named by the next step, `Next` or `Focus`. */
    Referenced("referenced"),

    /** Its path was changed in the cell (the packet's `changes`). */
    Touched("touched"),

    /** Its path is an input of a check whose latest receipt failed. */
    Red("red"),

    /** Its path is named by an open item that is not closed. */
    Noted("noted"),

    /** Displayed in the cell and nothing above: fills what is left of the budget, latest display first. */
    Recent("recent"),
    ;

    public val announced: Boolean get() = this != Recent
}

/** One seed candidate in carry priority order. */
public data class SeedCandidate(val entry: Entry, val reason: SeedReason)

/**
 * The records a [SeedSelector] reads (§6.2), all of them already persisted: [register] is the previous STATE,
 * [export] the Workset export (what was displayed, at which version and turn: the cell's read journal), [touched] the
 * paths the cell changed and [receipts] the latest receipt of each check.
 */
public data class SeedInputs @JvmOverloads constructor(
    val register: Register,
    val export: List<Entry>,
    val touched: Set<String> = emptySet(),
    val receipts: List<Receipt> = emptyList(),
)

/**
 * Picks the Workset entries a carry may re-serve, in priority order (§6.2). A selector is a pure function of its
 * [SeedInputs]: it reads neither the clock nor the tree. [Seeds.fit] then applies the one seed budget to that order and
 * drops entries whose files moved, so a selector never decides what is KNOWN on its own.
 */
public fun interface SeedSelector {
    public fun candidates(inputs: SeedInputs): List<SeedCandidate>

    public companion object {
        /** Seeds v1 (§6.2): entries referenced by the next step's text or `accept`, by `Next` or under `Focus`; path order. */
        @JvmField
        public val V1: SeedSelector = SeedSelector { inputs ->
            val previous = inputs.register
            val next = previous.cursor ?: previous.plan.firstOrNull { it.mark == Mark.Todo }
            val texts = listOfNotNull(next?.text, next?.accept, previous.next)
            inputs.export.filter { entry -> mentions(texts, entry.path) || inFocus(previous.focus, entry.path) }
                .sortedWith(compareBy({ it.path }, { it.range.ranges.first().from }))
                .map { SeedCandidate(it, SeedReason.Referenced) }
        }

        /**
         * Seeds v2 (plan §4.3): every exported entry with visible lines, ranked by [SeedReason] (touched, red, noted,
         * recent) and then by [SEED_V2_ORDER]. An entry takes the first reason that holds for its path. An entry whose
         * lines are all hidden (a transform's record) has nothing to show and is no candidate; of entries showing the
         * same lines of the same version only the first in that order is one, so the budget never pays twice and the
         * same lines are never both re-served and announced NOT SEEN.
         */
        @JvmField
        public val V2: SeedSelector = SeedSelector { inputs ->
            val red = redInputs(inputs.receipts)
            val notes = inputs.register.open.filter { !it.closed }.flatMap { listOfNotNull(it.text, it.needs) }
            inputs.export.filter { !it.coverage.isEmpty }.distinct().map { entry ->
                val reason = when {
                    entry.path in inputs.touched -> SeedReason.Touched
                    red(entry.path) -> SeedReason.Red
                    mentions(notes, entry.path) -> SeedReason.Noted
                    else -> SeedReason.Recent
                }
                SeedCandidate(entry, reason)
            }.sortedWith(SEED_V2_ORDER).distinctBy { Triple(it.entry.path, it.entry.version, it.entry.range) }
        }
    }
}

/**
 * The Seeds v2 order, total over distinct entries so the result never depends on the export's order: reason
 * (declaration order); an entry displayed in this cell before one carried in as a seed (a seed's turn counts in
 * the cell it came from); the later display first; then path, first line, ranges, version, result id (`null` first,
 * apart from `""`), tokens, source and hidden lines.
 */
internal val SEED_V2_ORDER: Comparator<SeedCandidate> =
    compareBy<SeedCandidate>({ it.reason.ordinal }, { if (it.entry.source == EntrySource.Seed) 1 else 0 })
        .thenByDescending { it.entry.turn }
        .then(
            compareBy(
                { it.entry.path },
                { it.entry.range.ranges.first().from },
                { it.entry.range.toString() },
                { it.entry.version.digest.hex },
                { it.entry.resultId },
                { it.entry.tokens },
                { it.entry.source.ordinal },
                { it.entry.hidden.toString() },
            ),
        )

/**
 * The input files of the red receipts — those whose outcome is `failed` (§8.7; a timeout or an unavailable run is
 * missing evidence, not red): a known closure's paths, the files under a package closure and the paths the
 * command names. An unknown closure adds nothing beyond the command, so red never widens to the whole workspace.
 */
private fun redInputs(receipts: List<Receipt>): (String) -> Boolean {
    val red = receipts.filter { it.outcome == Outcome.Failed }
    if (red.isEmpty()) return { false }
    val known = red.flatMap { (it.inputClosure as? Closure.Known)?.paths.orEmpty() }.toSet()
    val packages = red.mapNotNull { (it.inputClosure as? Closure.Package)?.path?.trimEnd('/')?.takeIf { dir -> dir.isNotEmpty() && dir != "." } }
    val commands = red.map { it.command.joinToString(" ") }
    return { path -> path in known || packages.any { path == it || path.startsWith("$it/") } || mentions(commands, path) }
}

/** A text names [path] when it contains the path, or its file name as a whole token (not a suffix of another name). */
internal fun mentions(texts: List<String>, path: String): Boolean {
    val name = path.substringAfterLast('/')
    return texts.any { text -> path in text || Regex("(^|[^\\w.])${Regex.escape(name)}($|[^\\w])").containsMatchIn(text) }
}

private fun inFocus(focus: String?, path: String): Boolean {
    val dir = focus?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return false
    return path == dir || path.startsWith("$dir/")
}
