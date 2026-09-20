package io.astrolabe.tool.run

import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.StampReport
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry

/**
 * §9.4: any process whose stamp-after differs from stamp-before is a mutation. Every stamped member that
 * moved is announced to the version registry — with `from` = the version the harness knew (the stamped
 * member, else the last recorded transition, else unknown) — so the coherence horizons drop reads, facts
 * and receipts (FX-07). Returns the moved paths in stamp order.
 */
internal fun announceMoved(registry: VersionRegistry, before: StampReport, after: StampReport, cause: String): List<String> {
    val changed = Stamper.diff(before, after).toList()
    for (path in changed) {
        val from = before.members[path]?.digest?.let(::FileVersion) ?: registry.recorded(path)
        val to = after.members[path]?.digest?.let(::FileVersion) ?: registry.version(path)
        if (from != to) registry.change(path, from, to, cause)
    }
    return changed
}
