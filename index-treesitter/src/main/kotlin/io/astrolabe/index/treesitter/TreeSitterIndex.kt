package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.ImportGraph
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.Language
import io.astrolabe.atlas.Location
import io.astrolabe.atlas.Outline
import io.astrolabe.atlas.OutlineSource
import io.astrolabe.atlas.Refs
import io.astrolabe.atlas.SymbolIndex
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.search.Search
import io.astrolabe.os.search.Searches
import io.astrolabe.tool.edit.SyntaxCheck
import io.astrolabe.tool.edit.SyntaxResult
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.WorkspacePath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The tier-1 symbol index (§7.2): tree-sitter syntax trees cached per `(path, version)` and parsed
 * incrementally, giving precise outlines, declaration spans and imports for the D-09 languages
 * ([Grammar]). It claims no cross-module resolution: [refs] stays a lexical search that is never
 * complete, and import edges are resolved by the atlas' path rules, not by a compiler.
 *
 * It is an optional module: `core` never depends on it; the host plugs it in as the [OutlineSource]
 * of [ImportGraph.of] and as the edit tools' [SyntaxCheck]. A grammar whose native library cannot be
 * loaded degrades to the atlas' tier-0 outline and says so in [degradation] (FX-46); nothing throws.
 *
 * Every file is read as raw bytes through [workspace] (D-47); a syntax verdict concerns exactly the
 * bytes it parsed and is never behavioural acceptance (D-10).
 */
public class TreeSitterIndex internal constructor(
    public val atlas: Atlas,
    private val workspace: WorkspacePath,
    private val search: Search,
    loader: GrammarLoader,
    capacity: Int,
) : OutlineSource, SyntaxCheck, AutoCloseable {

    @JvmOverloads
    public constructor(
        atlas: Atlas,
        workspace: WorkspacePath = WorkspacePath.of(atlas.root),
        search: Search = Searches.available(),
    ) : this(atlas, workspace, search, GrammarLoader(Grammar::load), SyntaxTrees.DEFAULT_CAPACITY)

    internal val trees: SyntaxTrees = SyntaxTrees(loader, capacity)

    /** The tier of outlines and definitions from a loaded grammar; a degraded grammar answers at tier 0. */
    public val tier: IndexTier = IndexTier.Syntax

    /** File extensions whose syntax verdict this index issues: grammars with [Grammar.syntaxComplete] (D-10). */
    public val acceptedExtensions: Set<String> =
        Grammar.entries.filter { it.syntaxComplete }.flatMapTo(sortedSetOf()) { it.extensions }

    /** Grammars that failed to load and fell back to tier 0, as one line; null while none has failed (FX-46). */
    public val degradation: String?
        get() {
            val failures = trees.failures()
            if (failures.isEmpty()) return null
            return failures.entries.joinToString("; ") { (grammar, detail) ->
                "tree-sitter ${grammar.name.lowercase()} unavailable ($detail); tier 0 outlines"
            }
        }

    /**
     * The outline of [path]: tier 1 for a file with a loaded grammar, otherwise the atlas' tier-0
     * outline. A path outside the atlas is an empty outline, as at tier 0.
     */
    override fun outline(path: String): Outline {
        val grammar = Grammar.of(path) ?: return atlas.outline(path)
        if (atlas.row(path) == null) return Outline.empty(path)
        val bytes = read(path) ?: return atlas.outline(path)
        if (isBinary(bytes)) return Outline.empty(path, grammar.language)
        return trees.outline(path, bytes, grammar) ?: atlas.outline(path)
    }

    /** Declarations of [name] from the outlines, ordered by path then line (the tier-0 contract). */
    public fun def(name: String): List<Location> {
        if (name.isEmpty()) return emptyList()
        val found = ArrayList<Location>()
        for (row in atlas.rows) {
            for (entry in outline(row.path).entries) {
                if (entry.name == name && entry.kind != DeclarationKind.Import) {
                    found += Location(row.path, entry.from, entry.kind, entry.name)
                }
            }
        }
        return found.sortedWith(compareBy({ it.path }, { it.line }))
    }

    /**
     * Lexical occurrences of [name] without the lines [def] reports. Tier 1 resolves nothing across
     * modules, so the answer keeps the lexical tier and `complete = false` (D-212).
     */
    @JvmOverloads
    public fun refs(
        name: String,
        budgetBytes: Long = SymbolIndex.DEFAULT_BUDGET_BYTES,
        maxHits: Int = SymbolIndex.DEFAULT_MAX_HITS,
    ): Refs {
        val lexical = SymbolIndex(atlas, search).refs(name, budgetBytes, maxHits)
        val definitions = def(name).mapTo(HashSet()) { it.path to it.line }
        return lexical.copy(references = lexical.references.filter { (it.path to it.line) !in definitions })
    }

    /** The import graph over tier-1 outlines; [IndexTier.Syntax] when every D-09 file parsed at tier 1 (§7.3). */
    public fun importGraph(workspace: WorkspaceId): ImportGraph = ImportGraph.of(atlas, workspace, this)

    /**
     * The D-10 inline syntax verdict from ERROR and MISSING nodes, for [acceptedExtensions] only;
     * any other file, an incomplete grammar or an unloadable one is `not_run` with the reason.
     */
    override fun check(relative: String, real: Path, language: Language): SyntaxResult {
        val grammar = Grammar.of(relative)
            ?: return SyntaxResult.NotRun("tree-sitter accepts ${acceptedExtensions.joinToString(",") { ".$it" }}; not ${language.id}")
        if (!grammar.syntaxComplete) {
            return SyntaxResult.NotRun("tree-sitter ${grammar.name.lowercase()} grammar is incomplete; no syntax verdict (D-211)")
        }
        val bytes = try {
            Files.readAllBytes(real)
        } catch (failure: IOException) {
            return SyntaxResult.NotRun("cannot read $relative: ${failure.message}")
        }
        return trees.syntax(relative, bytes, grammar)
            ?: SyntaxResult.NotRun(degradation ?: "tree-sitter ${grammar.name.lowercase()} unavailable")
    }

    override fun close() {
        trees.close()
    }

    private fun read(path: String): ByteArray? {
        val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return null
        return try {
            Files.readAllBytes(resolved.real)
        } catch (_: IOException) {
            null
        }
    }

    private fun isBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, BINARY_PROBE_BYTES)
        for (i in 0 until limit) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private companion object {
        const val BINARY_PROBE_BYTES = 8 * 1024
    }
}
