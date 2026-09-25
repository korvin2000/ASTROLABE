package io.astrolabe.atlas

import io.astrolabe.id.WorkspaceId

/**
 * The host's plug point for a tier-1 index (§7.2, P5.4.1, D-251): given the campaign's current atlas, the
 * [OutlineSource] its import graph reads. `core` never depends on `index-treesitter`; a host that has it passes
 * `OutlineIndex { atlas -> TreeSitterIndex(atlas) }`. Synchronous, so Java implements it directly.
 */
public fun interface OutlineIndex {
    public fun source(atlas: Atlas): OutlineSource
}

/** Told once per distinct line when a tier-1 answer degraded to tier 0 (FX-46). */
public fun interface IndexDegradation {
    public fun report(line: String)
}

/**
 * Builds import graphs over an optional tier-1 [OutlineIndex] that can never block a cell (FX-46): a missing
 * index, one that fails to build, or a file whose tier-1 outline throws falls back to the atlas' tier-0 outline,
 * which keeps the whole graph at tier 0 (D-213), and [degraded] is told why once per kind of failure.
 */
public class IndexTiers @JvmOverloads constructor(
    private val index: OutlineIndex?,
    private val degraded: IndexDegradation = IndexDegradation {},
) {
    private val reported = HashSet<String>()

    /** The import graph of [atlas]: tier 1 when the index answers for every file, else tier 0. */
    public fun graph(atlas: Atlas, workspace: WorkspaceId): ImportGraph {
        val host = index ?: return ImportGraph.of(atlas, workspace)
        val source = try {
            host.source(atlas)
        } catch (failure: Exception) {
            report(UNAVAILABLE, "tier-1 index unavailable (${failure.message ?: failure::class.simpleName}); tier 0 outlines")
            return ImportGraph.of(atlas, workspace)
        } catch (failure: LinkageError) {
            report(UNAVAILABLE, "tier-1 index unavailable (${failure.message ?: failure::class.simpleName}); tier 0 outlines")
            return ImportGraph.of(atlas, workspace)
        }
        return ImportGraph.of(atlas, workspace) { path ->
            try {
                source.outline(path)
            } catch (failure: Exception) {
                report(OUTLINE, "tier-1 outline failed (${failure.message ?: failure::class.simpleName}); tier 0 outlines")
                atlas.outline(path)
            } catch (failure: LinkageError) {
                report(OUTLINE, "tier-1 outline failed (${failure.message ?: failure::class.simpleName}); tier 0 outlines")
                atlas.outline(path)
            }
        }
    }

    /** One line per kind of degradation, the first cause named. */
    private fun report(kind: String, line: String) {
        if (synchronized(reported) { reported.add(kind) }) degraded.report(line)
    }

    public companion object {
        private const val UNAVAILABLE = "unavailable"
        private const val OUTLINE = "outline"

        /** No tier-1 index: every graph is the atlas' tier 0, exactly as `ImportGraph.of(atlas, workspace)`. */
        @JvmField
        public val TIER_0: IndexTiers = IndexTiers(null)
    }
}
