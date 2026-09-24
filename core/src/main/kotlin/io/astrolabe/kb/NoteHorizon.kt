package io.astrolabe.kb

import io.astrolabe.id.Identities
import io.astrolabe.workspace.ChangeListener
import io.astrolabe.workspace.VersionChange

/**
 * The project horizon of the coherence protocol (§4.4, P2.6.3): an admitted note whose anchor or `path@hash`
 * dependency no longer matches the tree, or whose `name@v` contract dependency moved, is marked `stale` through the
 * [KbWriter] — excluded from injection (P4.1.3), labelled on explicit search, rechecked by the curator (P4.1.2).
 * Marking is a revision, never a deletion, and a horizon never throws: failures are kept in [failures].
 */
public class NoteHorizon(private val writer: KbWriter, private val notes: Notes, private val ids: Identities) : ChangeListener {
    private val failed = ArrayList<String>()

    public val failures: List<String> @Synchronized get() = failed.toList()

    @Synchronized
    override fun onChange(change: VersionChange) {
        val now = change.to?.digest?.hex
        mark("${change.path} changed (${change.cause})") { note ->
            note.anchors.any { it.path == change.path && (now == null || it.version == null || !now.startsWith(it.version)) } ||
                note.validity.dependsOn.any { dep -> pathOf(dep) == change.path && (now == null || !now.startsWith(dep.substringAfterLast('@'))) }
        }
    }

    /** A contract the notes may depend on (`name@v`) is now at [version]. */
    @Synchronized
    public fun contractChanged(name: String, version: Int) {
        mark("$name is now v$version") { note ->
            note.validity.dependsOn.any { dep -> dep.substringBeforeLast('@') == name && dep.substringAfterLast('@').removePrefix("v") != version.toString() }
        }
    }

    private fun mark(reason: String, invalid: (Note) -> Boolean) {
        for (note in notes.all()) {
            if (note.status != NoteStatus.Admitted || !invalid(note)) continue
            try {
                writer.setStatus(note.id, NoteStatus.Stale, ids)
            } catch (e: RuntimeException) {
                failed += "${note.id}: $reason: ${e.message}"
            }
        }
    }

    private fun pathOf(dep: String): String? = dep.substringBeforeLast('@', "").takeIf { '/' in it || '.' in it }
}
