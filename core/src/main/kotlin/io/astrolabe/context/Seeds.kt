package io.astrolabe.context

import io.astrolabe.atlas.decodeLines
import io.astrolabe.cell.Checkpoints
import io.astrolabe.evidence.Aliases
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
import io.astrolabe.workspace.FileContent

/** Seeds as rendered into `[K]`: [shown] at their recorded hash, [notSeen] when the bytes moved in between. */
public data class SeedRender(val text: String, val shown: List<Entry>, val notSeen: List<NotSeen>)

/**
 * The Workset export/seed round trip (§6.2, P2.4.4). A cell's end export is the one checkpointed with its last turn;
 * [CarryForward] picks the seeds from it; [render] displays each seed at its hash — bytes read at render time must
 * still be at the seed's version, else it is announced NOT SEEN, never shown as KNOWN. `#n` aliases are
 * campaign-global (D-46), so a carried `v … [#17]` fact and `recall #17` name the same evidence in every later cell.
 */
public object Seeds {
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
        val text = buildString {
            for (seed in seeds) {
                val content = read(seed.path)
                if (content == null || content.version != seed.version) {
                    notSeen += NotSeen(seed.path, seed.range, seed.version, content?.version, "changed")
                    continue
                }
                val lines = decodeLines(content.bytes)
                append("SEED ").append(seed.path).append(':').append(seed.range).append(" @").append(seed.version.hash8).append('\n')
                for (range in seed.range.ranges) {
                    for (n in range.from..minOf(range.to, lines.size)) {
                        val hidden = seed.hidden.ranges.any { n in it.from..it.to }
                        append(n).append(" | ").append(if (hidden) "⟨redacted⟩" else lines[n - 1]).append('\n')
                    }
                }
                shown += seed
            }
        }
        return SeedRender(text, shown, notSeen)
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
