package io.astrolabe.atlas

import io.astrolabe.os.search.Hits
import io.astrolabe.os.search.Search
import io.astrolabe.os.search.SearchMode
import io.astrolabe.os.search.SearchOutcome
import io.astrolabe.os.search.SearchRequest
import io.astrolabe.os.search.SearchScope
import io.astrolabe.os.search.Searches

/** Where a name is declared. [kind] is the declaration's own kind, never a guess. */
public data class Location(
    val path: String,
    val line: Int,
    val kind: DeclarationKind,
    val name: String,
) {
    init {
        require(line >= 1) { "line numbers are 1-based; got $line" }
    }
}

/** One textual occurrence of a name. [text] is the matched line with its terminator removed. */
public data class Reference(val path: String, val line: Int, val text: String) {
    init {
        require(line >= 1) { "line numbers are 1-based; got $line" }
    }
}

/**
 * References to a name, with the honesty flags §7.2 requires: at [IndexTier.Lexical], [complete] is
 * always `false`, so an empty [references] means "tier 0 found none", never "there are none" (L8).
 *
 * [truncated] says the search stopped at its budget, which is a *different* fact from tier
 * incompleteness and is reported separately.
 */
public data class Refs(
    val name: String,
    val references: List<Reference>,
    val tier: IndexTier,
    val complete: Boolean,
    val truncated: Boolean = false,
) {
    init {
        require(tier != IndexTier.Lexical || !complete) { "tier 0 references are never complete (§7.2)" }
    }
}

/** Files whose resolved imports name a path. Tier 0, so never [complete]. */
public data class Importers(
    val path: String,
    val paths: List<String>,
    val tier: IndexTier,
    val complete: Boolean,
) {
    init {
        require(tier != IndexTier.Lexical || !complete) { "tier 0 importers are never complete (§7.2)" }
    }
}

/**
 * Definitions and references over an [Atlas] at tier 0 (§7.2: regex / ctags-grade).
 *
 * [def] reads the atlas outlines, so it answers from declarations the parsers actually found.
 * [refs] is a lexical word-boundary search over the same candidate files the
 * [io.astrolabe.os.search.Search] backends use, with the declaring lines removed, so a definition
 * is not also reported as a use. Neither is ever complete: that is the whole point of the tier
 * flags — an incomplete index must never be mistaken for absence.
 */
public class SymbolIndex @JvmOverloads constructor(
    private val atlas: Atlas,
    private val search: Search = Searches.available(),
) {
    /** The tier every answer from this index carries. */
    public val tier: IndexTier = IndexTier.Lexical

    /**
     * Declarations of [name], ordered by path then line. Outlines are parsed on demand and
     * memoized on the atlas, so a fresh [Atlas.build] has already paid for them.
     */
    public fun def(name: String): List<Location> {
        if (name.isEmpty()) return emptyList()
        val found = ArrayList<Location>()
        for (row in atlas.rows) {
            for (entry in atlas.outline(row.path).entries) {
                if (entry.name == name && entry.kind != DeclarationKind.Import) {
                    found += Location(row.path, entry.from, entry.kind, entry.name)
                }
            }
        }
        return found.sortedWith(compareBy({ it.path }, { it.line }))
    }

    /**
     * Lexical occurrences of [name], excluding the lines [def] already reports.
     *
     * An identifier is matched on word boundaries, so `Router` does not report `RouterTest`; a name
     * that is not an identifier is matched as a fixed string, with no boundary claim.
     *
     * [budgetBytes] bounds the total size of the returned lines and [maxHits] their number; a
     * search that stops at either returns `truncated = true`. A backend failure yields no
     * references rather than an exception — with `complete = false` already set, an empty answer is
     * never a claim of absence.
     */
    @JvmOverloads
    public fun refs(
        name: String,
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
        maxHits: Int = DEFAULT_MAX_HITS,
    ): Refs {
        if (name.isEmpty()) return Refs(name, emptyList(), tier, complete = false)
        val definitions = def(name).mapTo(HashSet()) { it.path to it.line }
        val request = SearchRequest(
            pattern = pattern(name),
            mode = if (isIdentifier(name)) SearchMode.Regex else SearchMode.Literal,
            scope = SearchScope.All(atlas.root),
            budgetBytes = budgetBytes,
            maxHits = maxHits,
        )
        val outcome = search.find(request)
        val hits: Hits? = when (outcome) {
            is SearchOutcome.Found -> outcome.hits
            is SearchOutcome.Incomplete -> outcome.hits
            is SearchOutcome.Failed, is SearchOutcome.Denied, is SearchOutcome.Unsupported -> null
        }
        val truncated = outcome is SearchOutcome.Incomplete
        val references = hits?.hits.orEmpty()
            .filter { atlas.row(it.path) != null && (it.path to it.line) !in definitions }
            .map { Reference(it.path, it.line, it.text.trim()) }
            .sortedWith(compareBy({ it.path }, { it.line }))
        return Refs(name, references, tier, complete = false, truncated = truncated)
    }

    /** Files whose resolved imports name [path] (§7.3 inbound edges of the import graph). */
    public fun importers(path: String): Importers =
        Importers(path, atlas.importers(normalizeRelative(path)), tier, complete = false)

    private fun pattern(name: String): String =
        if (isIdentifier(name)) "\\b" + name + "\\b" else name

    private fun isIdentifier(name: String): Boolean =
        name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' }

    public companion object {
        /** Enough for a tier-0 sweep of a small repository; the caller may raise or lower it. */
        public const val DEFAULT_BUDGET_BYTES: Long = 256L * 1024
        public const val DEFAULT_MAX_HITS: Int = 500
    }
}
