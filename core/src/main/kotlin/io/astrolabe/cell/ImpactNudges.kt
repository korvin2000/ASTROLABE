package io.astrolabe.cell

import io.astrolabe.atlas.ChangedDefinition
import io.astrolabe.atlas.Language
import io.astrolabe.register.Register

/** One changed definition with `fanin > 0` whose references were not inspected since [turn] (§5.6 Impact). */
public data class ImpactNudge(val definition: ChangedDefinition, val references: Int, val turn: Int) {
    init { require(references > 0 && turn >= 1) }

    /** The `[A]` line (§5.6). */
    val line: String
        get() = line(Protocol.Structured)

    /** The `[A]` line in [protocol]'s words (G9): a direct cell has no plan to scope. */
    public fun line(protocol: Protocol): String = "impact: `${definition.symbol}` (${definition.path}) ${definition.change.wire}; " +
        "$references reference${if (references == 1) "" else "s"} not inspected → look(refs)" + if (protocol == Protocol.Direct) "" else " or scope the plan"

    /** What the exit gate lists while a changed public definition is unresolved (§5.6, §7.4). */
    val missing: String
        get() = "`${definition.symbol}` (${definition.path}) ${definition.change.wire}; $references references not inspected"
}

/**
 * The cell's impact-nudge ledger (§7.4, §5.6): after each edit batch the changed definitions with
 * `fanin > 0` become pending; `look(refs)` on the symbol or a plan step naming it (rescoping) resolves
 * them (D-92). A re-change of a still-pending symbol keeps its first turn, so the nudge fires once.
 *
 * D-366: definitions of a file the cell created itself (absent at its base) never become pending — nothing
 * outside the cell can reference them yet. At most [MAX_PER_TURN] new nudges a turn become pending; the rest
 * are [overflow], summarised in one line and never an exit obligation.
 *
 * D-373: only files of a language the outline parsers model ([Language.hasOutlineParser]) raise nudges — a stylesheet,
 * markup, Markdown or data file's generic outline has no symbols that code references — and never a "symbol" without a
 * letter or digit (a lone brace or fence).
 */
public class ImpactNudges {
    private val pending = LinkedHashMap<Pair<String, String>, ImpactNudge>()

    public val unresolved: List<ImpactNudge> get() = pending.values.toList()

    /** Changed public definitions still unresolved: the exit gate's input. */
    public val unresolvedPublic: List<ImpactNudge> get() = pending.values.filter { it.definition.public }

    /** The last [changed] call's new nudges beyond [MAX_PER_TURN]: summarised, not pending. */
    public var overflow: List<ImpactNudge> = emptyList()
        private set

    /**
     * [fanIn] counts references outside the edited file (the index, or the literal fallback); [created] are the paths
     * the cell created, whose definitions are skipped.
     */
    @JvmOverloads
    public fun changed(turn: Int, changes: List<ChangedDefinition>, created: Set<String> = emptySet(), fanIn: (ChangedDefinition) -> Int) {
        val fresh = ArrayList<ImpactNudge>()
        for (change in changes) {
            if (change.path in created || !Language.of(change.path).hasOutlineParser || change.symbol.none(Char::isLetterOrDigit)) continue
            val references = fanIn(change)
            if (references <= 0) continue
            val key = change.path to change.symbol
            val earlier = pending[key]
            if (earlier != null) pending[key] = ImpactNudge(change, references, earlier.turn)
            else if (fresh.none { it.definition.path == change.path && it.definition.symbol == change.symbol }) fresh += ImpactNudge(change, references, turn)
        }
        // The kept nudges are the ones that bind the exit gate first (public), then the widest.
        val kept = fresh.sortedWith(compareByDescending<ImpactNudge> { it.definition.public }.thenByDescending { it.references }).take(MAX_PER_TURN).toSet()
        fresh.filter { it in kept }.forEach { pending[it.definition.path to it.definition.symbol] = it }
        overflow = fresh.filter { it !in kept }
    }

    /** An executed `look(refs)` whose target names the symbol (`name`, `Owner.name`, `path::name`). */
    public fun inspected(target: String) {
        pending.keys.removeIf { (_, symbol) -> names(target, symbol) }
    }

    /** Plan rescoping: a plan step added or rewritten this turn names the symbol. */
    public fun rescoped(before: Register, after: Register) {
        val old = before.plan.map { it.text }.toSet()
        val texts = after.plan.map { it.text }.filter { it !in old }
        if (texts.isEmpty()) return
        pending.keys.removeIf { (_, symbol) -> texts.any { Regex("(?<![\\w$])" + Regex.escape(symbol) + "(?![\\w$])").containsMatchIn(it) } }
    }

    private fun names(target: String, symbol: String): Boolean =
        target == symbol || target.endsWith(".$symbol") || target.endsWith("::$symbol") || target.endsWith("#$symbol")

    public companion object {
        /** D-366: new impact nudges per turn before the rest are summarised. */
        public const val MAX_PER_TURN: Int = 3

        /** How many paths the summary line names. */
        public const val SUMMARY_PATHS: Int = 5

        /** The one `[A]` line for [overflow]: how many more and where to look. */
        @JvmStatic
        public fun summary(overflow: List<ImpactNudge>): String = summary(overflow, Protocol.Structured)

        /** [summary] in [protocol]'s words (G9): `look(impact)` is hidden in a direct role, so the line points at `look(refs)`. */
        @JvmStatic
        public fun summary(overflow: List<ImpactNudge>, protocol: Protocol): String {
            val paths = overflow.map { it.definition.path }.distinct().sorted()
            val shown = paths.take(SUMMARY_PATHS).joinToString(", ") + if (paths.size > SUMMARY_PATHS) ", … +${paths.size - SUMMARY_PATHS}" else ""
            if (protocol == Protocol.Direct) return "impact: … and ${overflow.size} more in $shown → look(refs)"
            return "impact: … and ${overflow.size} more: look(impact, $shown)"
        }
    }
}
