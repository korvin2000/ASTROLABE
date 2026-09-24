package io.astrolabe.context

import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.KbExport
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteOrigin
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Notes
import kotlinx.serialization.json.Json
import java.nio.file.Path

/** The boundaries that write a STATUS revision (§4.5, §5.8). */
public enum class StatusBoundary(public val wire: String) { RoleSwitch("role_switch"), CellEnd("cell_end") }

/**
 * The campaign checkpoint note `STATUS-<work>` (§4.5, P2.4.3): harness-origin (`admitted_by: harness`, D-36), one
 * revision per boundary through the serialized [KbWriter], exported to `kb/notes/`. Archived register records
 * accumulate across revisions as one JSON object per line, so a resume reads them back; the note is scoped to its
 * task and never indexed or injected elsewhere.
 */
public class StatusNotes(private val writer: KbWriter, private val notes: Notes, private val kbRoot: Path) {
    public fun id(work: WorkId): String = "STATUS-${work.value}"

    public fun checkpoint(
        ids: Identities,
        boundary: StatusBoundary,
        archived: List<ArchivedRecord>,
        verification: List<CarriedReceipt>,
        openHandles: List<String>,
    ): Note {
        val all = (archived(ids.work) + archived).distinctBy { Triple(it.cell, it.kind, it.n) }
        val body = buildString {
            append("boundary: ").append(boundary.wire).append(" · cell: ").append(ids.context?.value ?: "campaign").append('\n')
            append("verification: ").append(if (verification.isEmpty()) "(none)" else verification.joinToString(" · ") { "${it.checkId} ${it.receiptId} (${it.validity})" }).append('\n')
            append("open handles: ").append(if (openHandles.isEmpty()) "(none)" else openHandles.joinToString(", ")).append('\n')
            append(ARCHIVED).append(all.size).append('\n')
            all.forEach { append(JSON.encodeToString(ArchivedRecord.serializer(), it)).append('\n') }
        }.trimEnd()
        val note = Note(
            id(ids.work), NoteKind.STATUS, NoteStatus.Admitted,
            "campaign checkpoint of ${ids.work.value} at ${boundary.wire}", body, "task:${ids.work.value}",
            origin = NoteOrigin(work = ids.work.value, cell = ids.context?.value, admittedBy = "harness"),
        )
        writer.write(note, ids)
        KbExport.write(kbRoot, note)
        return note
    }

    /** The archived register records of [work], oldest first; empty before the first checkpoint. */
    public fun archived(work: WorkId): List<ArchivedRecord> {
        val body = notes.get(id(work))?.body ?: return emptyList()
        return body.lineSequence().dropWhile { !it.startsWith(ARCHIVED) }.drop(1)
            .filter { it.startsWith("{") }.map { JSON.decodeFromString(ArchivedRecord.serializer(), it) }.toList()
    }

    private companion object {
        const val ARCHIVED = "archived records: "
        val JSON = Json { encodeDefaults = true }
    }
}
