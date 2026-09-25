package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.cell.Roles
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import java.sql.SQLException

/** One `kb.search` as logged for retrieval-miss analysis (§4.5): the model's stated need and what came back. */
public data class KbSearchLog(val query: String, val kinds: Set<String>?, val scope: String?, val why: String, val hits: Int, val complete: Boolean)

/**
 * The knowledge base over the canonical store (§4.5, §5.4, P2.6.2): FTS5 over summaries and anchors; only admitted
 * and stale notes answer — candidates wait in the queue — and a `STATUS` note answers only its own [work]. A note
 * whose anchor moved on the current tree is labelled stale, never served as current. A cold or failing FTS index
 * degrades to a direct scan of the notes, reported as a degradation line, so a cell is never blocked (FX-46).
 */
public class StoreKb @JvmOverloads constructor(
    private val store: Store,
    private val work: WorkId,
    private val currentVersion: (String) -> FileVersion?,
    /** The role whose skill view `kb.skill` renders (P4.3.1). */
    private val role: String = Roles.implementing.name,
    private val skillViews: SkillViews = SkillViews(HeuristicEstimator()),
    private val onSearch: (KbSearchLog) -> Unit = {},
) : Kb {
    private val notes = Notes(store)
    private val skills = SkillStore(store)

    override fun contractAnchors(): Map<String, Set<String>> =
        notes.all().filter { it.kind == NoteKind.CON && (it.status == NoteStatus.Admitted || it.status == NoteStatus.Stale) }
            .associate { note -> note.id to note.anchors.map { it.path }.toSet() }

    override fun search(query: String, kinds: Set<String>?, scope: String?, why: String): KbHits {
        val tokens = TOKEN.findAll(query.lowercase()).map { it.value }.distinct().toList()
        var degradation: String? = null
        val ranked: List<Pair<Note, Double?>> = if (tokens.isEmpty()) {
            emptyList()
        } else {
            val indexed = store.db.query("SELECT count(*) AS n FROM notes_fts") { it.long("n") }.first()
            val stored = store.db.query("SELECT count(*) AS n FROM notes") { it.long("n") }.first()
            try {
                if (indexed == 0L && stored > 0L) throw SQLException("the FTS index is empty")
                store.db.query(
                    "SELECT n.body AS body, bm25(notes_fts) AS score FROM notes_fts JOIN notes n ON n.note_id = notes_fts.note_id " +
                        "WHERE notes_fts MATCH ? ORDER BY score, n.note_id",
                    tokens.joinToString(" OR ") { "\"$it\"" },
                ) { NOTE_JSON.decodeFromString(Note.serializer(), it.string("body")) to it.double("score") }
            } catch (unavailable: SQLException) {
                degradation = "full-text index unavailable (${unavailable.message}); scanned summaries and anchors directly"
                notes.all().filter { n -> tokens.any { t -> t in n.summary.lowercase() || n.anchors.any { a -> t in a.path.lowercase() } } }.map { it to null }
            }
        }
        val hits = ranked.filter { (note, _) -> visible(note) && (kinds == null || note.kind.name in kinds) && (scope == null || note.scope == scope) }
            .map { (note, score) -> KbHit(note.id, note.kind.name, note.summary, stale(note), score) }
        onSearch(KbSearchLog(query, kinds, scope, why, hits.size, complete = true))
        return KbHits(hits, scope ?: "kb", complete = true, degradation = degradation)
    }

    override fun get(id: String): KbEntry? {
        val note = notes.get(id)?.takeIf(::visible) ?: return null
        return KbEntry(note.id, note.kind.name, KbExport.markdown(note), stale(note))
    }

    /** The note's front matter and the role's view of its procedure: mandatory modules always, omissions named. */
    override fun skill(id: String): KbEntry? {
        val note = notes.get(id)?.takeIf { visible(it) && it.kind == NoteKind.SKILL } ?: return null
        val view = skills.load(note)?.let { skillViews.view(it, role).render() }.orEmpty()
        return KbEntry(note.id, note.kind.name, KbExport.markdown(note) + view, stale(note))
    }

    private fun visible(note: Note): Boolean =
        (note.status == NoteStatus.Admitted || note.status == NoteStatus.Stale) && (note.kind != NoteKind.STATUS || note.scope == "task:${work.value}")

    private fun stale(note: Note): Boolean = note.status == NoteStatus.Stale || note.anchors.any { anchor ->
        val recorded = anchor.version ?: return@any false
        val now = currentVersion(anchor.path) ?: return@any true
        !now.digest.hex.startsWith(recorded)
    }

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}_]+")
    }
}
