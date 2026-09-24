package io.astrolabe.cell

import io.astrolabe.atlas.ChangedDefinition
import io.astrolabe.register.Register

/** One changed definition with `fanin > 0` whose references were not inspected since [turn] (§5.6 Impact). */
public data class ImpactNudge(val definition: ChangedDefinition, val references: Int, val turn: Int) {
    init { require(references > 0 && turn >= 1) }

    /** The `[A]` line (§5.6). */
    val line: String
        get() = "impact: `${definition.symbol}` (${definition.path}) ${definition.change.wire}; " +
            "$references reference${if (references == 1) "" else "s"} not inspected → look(refs) or scope the plan"

    /** What the exit gate lists while a changed public definition is unresolved (§5.6, §7.4). */
    val missing: String
        get() = "`${definition.symbol}` (${definition.path}) ${definition.change.wire}; $references references not inspected"
}

/**
 * The cell's impact-nudge ledger (§7.4, §5.6): after each edit batch the changed definitions with
 * `fanin > 0` become pending; `look(refs)` on the symbol or a plan step naming it (rescoping) resolves
 * them (D-87). A re-change of a still-pending symbol keeps its first turn, so the nudge fires once.
 */
public class ImpactNudges {
    private val pending = LinkedHashMap<Pair<String, String>, ImpactNudge>()

    public val unresolved: List<ImpactNudge> get() = pending.values.toList()

    /** Changed public definitions still unresolved: the exit gate's input. */
    public val unresolvedPublic: List<ImpactNudge> get() = pending.values.filter { it.definition.public }

    /** [fanIn] counts references outside the edited file (the index, or the literal fallback). */
    public fun changed(turn: Int, changes: List<ChangedDefinition>, fanIn: (ChangedDefinition) -> Int) {
        for (change in changes) {
            val references = fanIn(change)
            if (references <= 0) continue
            val key = change.path to change.symbol
            val earlier = pending[key]
            pending[key] = ImpactNudge(change, references, earlier?.turn ?: turn)
        }
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
}
