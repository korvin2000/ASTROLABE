package io.astrolabe.context

import io.astrolabe.atlas.decodeLines
import io.astrolabe.cell.Checkpoints
import io.astrolabe.evidence.Aliases
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.FileContent

/** Seeds as rendered into `[K]`: [shown] at their recorded hash, [notSeen] when the bytes moved in between. */
public data class SeedRender(val text: String, val shown: List<Entry>, val notSeen: List<NotSeen>, val blocks: List<String> = emptyList())

/** The seeds a carry re-serves and the candidates it announces NOT SEEN (§6.2), after [Seeds.fit]. */
internal data class SeedFit(val seeds: List<Entry>, val notSeen: List<NotSeen>)

/**
 * The Workset export/seed round trip (§6.2, P2.4.4). A cell's end export is the one checkpointed with its last turn;
 * [CarryForward] picks the seeds from it; [render] displays each seed at its hash — bytes read at render time must
 * still be at the seed's version, else it is announced NOT SEEN, never shown as KNOWN. `#n` aliases are
 * campaign-global (D-46), so a carried `v … [#17]` fact and `recall #17` name the same evidence in every later cell.
 */
public object Seeds {
    /**
     * The one seed budget over a [SeedSelector]'s order (§6.2), the same for every rule: candidates are taken in order; one
     * whose file moved since it was displayed is not re-served, nor one larger than what is left of [capTokens] — first
     * fit, so a later, smaller candidate may still use the rest. Re-served entries become [EntrySource.Seed] and never
     * total more than [capTokens]. A candidate left out is announced NOT SEEN with its reason only when
     * [SeedReason.announced].
     */
    internal fun fit(candidates: List<SeedCandidate>, currentVersion: (String) -> FileVersion?, capTokens: Long): SeedFit {
        val seeds = ArrayList<Entry>()
        val notSeen = ArrayList<NotSeen>()
        var budget = capTokens
        for ((entry, reason) in candidates) {
            val now = currentVersion(entry.path)
            when {
                now != entry.version -> if (reason.announced) notSeen += NotSeen(entry.path, entry.range, entry.version, now, "changed")
                entry.tokens > budget -> if (reason.announced) notSeen += NotSeen(entry.path, entry.range, entry.version, now, "over the ${capTokens}-token seed budget")
                else -> {
                    seeds += entry.copy(source = EntrySource.Seed)
                    budget -= entry.tokens
                }
            }
        }
        return SeedFit(seeds, notSeen)
    }

    internal fun selected(seeds: List<Entry>, compiled: Compiled.Ready): List<Entry> = seeds.filterIndexed { index, _ ->
        ContextUnitId("seed-$index") in compiled.selection.selectedIds && compiled.k.sections.any { it.id == "seed-$index" }
    }

    /** The Workset export at [cell]'s end: the export saved with its latest checkpoint, or none. */
    @JvmStatic
    public fun cellEnd(checkpoints: Checkpoints, cell: ContextId): List<Entry> {
        val last = checkpoints.latest(cell) ?: return emptyList()
        return checkpoints.export(cell, last.turn).orEmpty()
    }

    @JvmStatic
    public fun render(seeds: List<Entry>, read: (String) -> FileContent?): SeedRender {
        val shown = ArrayList<Entry>()
        val notSeen = ArrayList<NotSeen>()
        val blocks = ArrayList<String>()
        for (seed in seeds) {
            val content = read(seed.path)
            if (content == null || content.version != seed.version) {
                notSeen += NotSeen(seed.path, seed.range, seed.version, content?.version, "changed")
                continue
            }
            val lines = decodeLines(content.bytes)
            blocks += buildString {
                append("SEED ").append(seed.path).append(':').append(seed.range).append(" @").append(seed.version.hash8).append('\n')
                for (range in seed.range.ranges) {
                    for (n in range.from..minOf(range.to, lines.size)) {
                        val hidden = seed.hidden.ranges.any { n in it.from..it.to }
                        append(n).append(" | ").append(if (hidden) "⟨redacted⟩" else lines[n - 1]).append('\n')
                    }
                }
            }
            shown += seed
        }
        return SeedRender(blocks.joinToString(""), shown, notSeen, blocks)
    }

    /**
     * The stub index of the ids the register's facts reference (§6.2): rendered on request, not by default. Each id
     * names its kind, canonical record and producing cell, or says that it does not resolve.
     */
    @JvmStatic
    public fun stubIndex(register: Register, work: WorkId, aliases: Aliases): String {
        val ids = register.facts.flatMap { listOfNotNull(it.evidenceId, it.refutedBy) }.filter { it.startsWith("#") }.distinct()
            .sortedBy { Aliases.parse(it) ?: Int.MAX_VALUE }
        return ids.joinToString("\n") { id ->
            val alias = Aliases.parse(id)?.let { aliases.resolve(work, it) }
            if (alias == null) "$id → unresolved" else "$id → ${alias.kind} ${alias.canonicalId}" + (alias.context?.let { " (from ${it.value})" } ?: "")
        }
    }
}
