package io.astrolabe.kb

import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock

/** Read side of the canonical note store (`notes` = latest revision, `note_revisions` = every revision). */
public class Notes(private val store: Store) {
    public fun get(id: String): Note? =
        store.db.query("SELECT body FROM notes WHERE note_id = ?", id) { decode(it.string("body")) }.firstOrNull()

    /** Every revision of [id], oldest first. */
    public fun revisions(id: String): List<Note> =
        store.db.query("SELECT body FROM note_revisions WHERE note_id = ? ORDER BY revision", id) { decode(it.string("body")) }

    /** Every note at its latest revision, ordered by id. */
    public fun all(): List<Note> = store.db.query("SELECT body FROM notes ORDER BY note_id") { decode(it.string("body")) }

    private fun decode(body: String): Note = NOTE_JSON.decodeFromString(Note.serializer(), body)
}

/** Why the writer refused a note: the record would break a §4.5 rule. */
public class NoteRefused(message: String) : IllegalArgumentException(message)

/**
 * The one serialized write path of the knowledge base (§4.5, P2.6.1): the curator (P4.1.1) and the harness
 * (STATUS/CAL, `admitted_by: harness`, D-36) write only through it. Every write appends a revision; an admitted
 * note's body is never rewritten in place — it is superseded — except the harness-origin STATUS and CAL notes,
 * which are revised per boundary.
 */
public class KbWriter(private val store: Store, private val estimator: TokenEstimator, private val clock: Clock) {
    private val notes = Notes(store)

    /** Stores [note] as a new note or its next revision; returns the revision number (1-based). */
    @Synchronized
    public fun write(note: Note, ids: Identities): Int {
        val bodyTokens = estimator.estimate(note.body).tokens
        // D-36: a harness STATUS checkpoint is lint-exempt; every other note is a compact unit.
        if (note.kind != NoteKind.STATUS && bodyTokens > Note.MAX_BODY_TOKENS) throw NoteRefused("${note.id}: body is $bodyTokens tokens > ${Note.MAX_BODY_TOKENS}; link a module")
        val existing = notes.get(note.id)
        if (existing != null) {
            if (existing.kind != note.kind) throw NoteRefused("${note.id}: kind ${existing.kind} cannot become ${note.kind}")
            if (existing.status == NoteStatus.Admitted && existing.body != note.body && note.kind !in REVISED) {
                throw NoteRefused("${note.id}: an admitted note's body is never rewritten in place; supersede it")
            }
        }
        return store.db.tx { tx ->
            val revision = tx.query("SELECT coalesce(max(revision), 0) AS r FROM note_revisions WHERE note_id = ?", note.id) { it.long("r").toInt() }.first() + 1
            val body = NOTE_JSON.encodeToString(Note.serializer(), note)
            val anchors = note.anchors.joinToString(" ") { it.path + (it.symbol?.let { s -> "#$s" } ?: "") }
            val now = clock.instant()
            tx.execute(
                "INSERT OR REPLACE INTO notes (note_id, work_id, attempt_id, candidate_id, context_id, kind, status, summary, anchors, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                note.id, ids.work, ids.attempt, ids.candidate, ids.context, note.kind.name, note.status.wire, note.summary, anchors, Migrations.SCHEMA_VERSION, now, body,
            )
            tx.execute(
                "INSERT INTO note_revisions (note_id, revision, work_id, attempt_id, candidate_id, context_id, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                note.id, revision, ids.work, ids.attempt, ids.candidate, ids.context, Migrations.SCHEMA_VERSION, now, body,
            )
            tx.execute("DELETE FROM notes_fts WHERE note_id = ?", note.id)
            tx.execute("INSERT INTO notes_fts (note_id, summary, anchors) VALUES (?, ?, ?)", note.id, note.summary, anchors)
            revision
        }
    }

    /** Replaces [oldId] by [replacement] (which names it in `supersedes`); the old note stays, marked superseded. */
    @Synchronized
    public fun supersede(oldId: String, replacement: Note, ids: Identities): Int {
        val old = notes.get(oldId) ?: throw NoteRefused("no note $oldId to supersede")
        if (replacement.supersedes != oldId) throw NoteRefused("${replacement.id} must name $oldId in supersedes")
        if (replacement.id == oldId) throw NoteRefused("a replacement is a new note, not a rewrite of $oldId")
        write(old.copy(status = NoteStatus.Superseded), ids)
        return write(replacement, ids)
    }

    /** A status change (admit, deprecate, mark stale, reject) is a revision with the same body. */
    @Synchronized
    public fun setStatus(id: String, status: NoteStatus, ids: Identities): Int {
        val note = notes.get(id) ?: throw NoteRefused("no note $id")
        return write(note.copy(status = status), ids)
    }

    private companion object {
        val REVISED = setOf(NoteKind.STATUS, NoteKind.CAL)
    }
}

/**
 * Deterministic index regeneration (§4.5): `index/global.md` (≤ 1.5K tokens: global-scope notes, active ADRs and
 * CONs, one line each), `index/contracts.md` (every active CON, uncapped: always visible) and `index/subsystem-<s>.md`
 * (≤ 1K tokens per `subsystem:<s>` scope). Only admitted notes are listed; STATUS notes never are (same task only).
 */
public object KbIndex {
    public const val GLOBAL_CAP_TOKENS: Int = 1_500
    public const val SUBSYSTEM_CAP_TOKENS: Int = 1_000

    /** The index files by name for [notes]; a pure function of the notes. */
    @JvmStatic
    public fun render(notes: List<Note>, estimator: TokenEstimator): Map<String, String> {
        val active = notes.filter { it.status == NoteStatus.Admitted && it.kind != NoteKind.STATUS }.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val out = LinkedHashMap<String, String>()
        out["global.md"] = capped(
            "# Global index (regenerated, never edited)",
            active.filter { it.scope == "global" || it.kind == NoteKind.ADR || it.kind == NoteKind.CON },
            GLOBAL_CAP_TOKENS, estimator,
        )
        out["contracts.md"] = (listOf("# Contracts (every active CON; regenerated)") + active.filter { it.kind == NoteKind.CON }.map { it.line }).joinToString("\n") + "\n"
        val subsystems = active.mapNotNull { n -> n.scope.takeIf { it.startsWith("subsystem:") }?.removePrefix("subsystem:") }.distinct().sorted()
        for (s in subsystems) {
            out["subsystem-$s.md"] = capped("# Subsystem $s (regenerated)", active.filter { it.scope == "subsystem:$s" }, SUBSYSTEM_CAP_TOKENS, estimator)
        }
        return out
    }

    /**
     * Writes the index under [dir] (`kb/index/`), removing index files no longer produced. Idempotent: an unchanged file
     * is not rewritten. Returns the names written.
     */
    @JvmStatic
    public fun regenerate(notes: List<Note>, estimator: TokenEstimator, dir: Path): List<String> {
        Files.createDirectories(dir)
        val files = render(notes, estimator)
        val written = ArrayList<String>()
        for ((name, text) in files) {
            val path = dir.resolve(name)
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (Files.exists(path) && Files.readAllBytes(path).contentEquals(bytes)) continue
            Files.write(path, bytes)
            written += name
        }
        Files.list(dir).use { listing -> listing.filter { it.fileName.toString().endsWith(".md") && it.fileName.toString() !in files }.forEach(Files::delete) }
        return written
    }

    private fun capped(header: String, notes: List<Note>, cap: Int, estimator: TokenEstimator): String {
        val lines = arrayListOf(header)
        for (note in notes) {
            if (estimator.estimate((lines + note.line).joinToString("\n")).tokens > cap) break
            lines += note.line
        }
        // The omission marker must fit too: drop listed lines until it does, so the cap is never exceeded.
        while (lines.size - 1 < notes.size) {
            val marker = "- +${notes.size - (lines.size - 1)} more (kb.search)"
            if (estimator.estimate((lines + marker).joinToString("\n")).tokens <= cap || lines.size == 1) {
                lines += marker
                break
            }
            lines.removeAt(lines.size - 1)
        }
        return lines.joinToString("\n") + "\n"
    }
}

/** Markdown views of notes (D-24): exported, immutable, never read back as authority. */
public object KbExport {
    @JvmStatic
    public fun markdown(note: Note): String = buildString {
        append("---\n")
        append("id: ").append(note.id).append('\n')
        append("kind: ").append(note.kind.name).append('\n')
        append("status: ").append(note.status.wire).append('\n')
        append("summary: ").append(note.summary).append('\n')
        append("scope: ").append(note.scope).append('\n')
        if (note.anchors.isNotEmpty()) append("anchors: ").append(note.anchors.joinToString(", ") { it.path + (it.version?.let { v -> "@$v" } ?: "") + (it.symbol?.let { s -> "#$s" } ?: "") }).append('\n')
        note.confidence?.let { append("confidence: ").append(it).append('\n') }
        if (note.basis.evidenceRefs.isNotEmpty()) append("evidence: ").append(note.basis.evidenceRefs.joinToString(", ")).append('\n')
        if (note.validity.dependsOn.isNotEmpty()) append("depends_on: ").append(note.validity.dependsOn.joinToString(", ")).append('\n')
        note.supersedes?.let { append("supersedes: ").append(it).append('\n') }
        note.signedBy?.let { append("signed_by: ").append(it).append('\n') }
        note.origin.admittedBy?.let { append("admitted_by: ").append(it).append('\n') }
        if (note.modules.isNotEmpty()) append("modules: ").append(note.modules.joinToString(", ") { "${it.id}@v${it.version}" }).append('\n')
        append("---\n")
        append(note.body).append('\n')
    }

    /** Writes `kb/notes/<id>.md` under [kbRoot]; the file is a view, overwritten from the store on every export. */
    @JvmStatic
    public fun write(kbRoot: Path, note: Note): Path {
        val dir = kbRoot.resolve("notes")
        Files.createDirectories(dir)
        return Files.write(dir.resolve("${note.id}.md"), markdown(note).toByteArray(Charsets.UTF_8))
    }

    /** Appends a raw trace, packet or manifest under `kb/raw/<work>/<cell>/`; an existing name is never overwritten. */
    @JvmStatic
    public fun appendRaw(kbRoot: Path, ids: Identities, name: String, bytes: ByteArray): Path {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "a raw record name is one path segment" }
        val dir = kbRoot.resolve("raw").resolve(ids.work.value).resolve(ids.context?.value ?: "campaign")
        Files.createDirectories(dir)
        return Files.write(dir.resolve(name), bytes, StandardOpenOption.CREATE_NEW)
    }
}

internal val NOTE_JSON: Json = Json { encodeDefaults = true }
