package io.astrolabe.campaign

import io.astrolabe.Flags
import io.astrolabe.atlas.IndexTiers
import io.astrolabe.atlas.OutlineIndex
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.kb.Retriever
import io.astrolabe.tool.Catalog
import io.astrolabe.tool.ToolRegistry
import io.astrolabe.tool.ToolSet
import java.time.Clock

/**
 * What a host plugs into the campaign's optional layers (D-251–D-253). Each one is read only while its frozen
 * attempt flag is on, so the default configuration runs exactly as without it:
 * - [outlines], under [Flags.treeSitterIndex]: a tier-1 index for the import graphs of `verify`, `look(impact)` and
 *   the pre-scan; a missing or failing one degrades to tier 0 and never blocks a cell (FX-46);
 * - [dense], under [Flags.denseRetrieval]: a second `kb(search)` candidate source; a cold or failing one degrades
 *   the search to lexical (FX-46). A Java host bridges its `JavaRetriever` with `Retrievers.fromJava`;
 * - [tools], under [Flags.generatedTools]: the generated-tool registry, frozen at the campaign's attempt boundary;
 * - [mounts]: the session's frozen MCP catalog (§15.3), always subject to the caller's ceiling (FX-39).
 */
public data class OptionalLayers @JvmOverloads constructor(
    val outlines: OutlineIndex? = null,
    val dense: Retriever? = null,
    val tools: ToolRegistry? = null,
    val mounts: Catalog = Catalog.EMPTY,
)

/** The layers one campaign runs with, resolved once at open (its attempt boundary) from the frozen flags. */
internal class PluggedLayers(val tiers: IndexTiers, val dense: Retriever?, val tools: ToolSet, val mounts: Catalog) {
    companion object {
        fun of(layers: OptionalLayers, flags: Flags, journal: Journal, ids: Identities, idGen: IdGen, clock: Clock): PluggedLayers {
            val tiers = layers.outlines?.takeIf { flags.treeSitterIndex }?.let { index ->
                IndexTiers(index) { line ->
                    journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, text = "index: $line", at = clock.instant()))
                }
            } ?: IndexTiers.TIER_0
            val tools = layers.tools?.boundary(ids.attempt, flags.generatedTools) ?: ToolSet.EMPTY
            return PluggedLayers(tiers, layers.dense?.takeIf { flags.denseRetrieval }, tools, layers.mounts)
        }
    }
}
