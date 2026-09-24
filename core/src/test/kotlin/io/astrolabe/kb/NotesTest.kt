package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P2.6.1: the note model, the one serialized writer, deterministic index regeneration and Markdown export (§4.5). */
class NotesTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val estimator = HeuristicEstimator()
    private val ids = Identities(WorkId("W-42"), AttemptId("a1"), context = ContextId("cell-7"))
    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var writer: KbWriter
    private lateinit var notes: Notes

    @BeforeTest
    fun open() {
        repo = TempRepo.create()
        repo.write("a.txt", "a")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        writer = KbWriter(store, estimator, clock)
        notes = Notes(store)
    }

    @AfterTest
    fun close() {
        store.close()
        repo.close()
    }

    private fun con(id: String, summary: String = "payments API returns cents as integers") =
        Note(id, NoteKind.CON, NoteStatus.Admitted, summary, "Amounts cross the boundary as integer cents.", "src/payments/**", listOf(NoteAnchor("src/payments/api.py", "abcd1234", "charge")))

    @Test
    fun `every write is a revision and an admitted body is superseded, never rewritten`() {
        val candidate = Note("LES-1", NoteKind.LES, NoteStatus.Candidate, "retry tests need a frozen clock", "Freeze the clock before retry tests.", "subsystem:payments")
        assertEquals(1, writer.write(candidate, ids))
        assertEquals(2, writer.setStatus("LES-1", NoteStatus.Admitted, ids))
        assertEquals(listOf(NoteStatus.Candidate, NoteStatus.Admitted), notes.revisions("LES-1").map { it.status })
        assertFailsWith<NoteRefused> { writer.write(notes.get("LES-1")!!.copy(body = "a rewritten lesson"), ids) }

        val replacement = candidate.copy(id = "LES-2", status = NoteStatus.Admitted, body = "Freeze the clock and the RNG.", supersedes = "LES-1")
        writer.supersede("LES-1", replacement, ids)
        assertEquals(NoteStatus.Superseded, notes.get("LES-1")!!.status)
        assertEquals("Freeze the clock before retry tests.", notes.get("LES-1")!!.body, "the old body stays")
        assertEquals("LES-1", notes.get("LES-2")!!.supersedes)

        val status = Note("STATUS-W-42", NoteKind.STATUS, NoteStatus.Admitted, "campaign checkpoint", "I1 verified.", "task:W-42", origin = NoteOrigin(work = "W-42", admittedBy = "harness"))
        writer.write(status, ids)
        assertEquals(2, writer.write(status.copy(body = "I1 verified; I2 in progress."), ids), "harness STATUS notes are revised per boundary")
    }

    @Test
    fun `the note model and writer enforce the front-matter limits`() {
        assertFailsWith<IllegalArgumentException> { Note("ADR-1", NoteKind.CON, NoteStatus.Candidate, "s", "b", "global") }
        assertFailsWith<IllegalArgumentException> { Note("PIT-1", NoteKind.PIT, NoteStatus.Candidate, "x".repeat(201), "b", "global") }
        assertFailsWith<IllegalArgumentException> { Note("PIT-1", NoteKind.PIT, NoteStatus.Candidate, "s", "```\ncode\n```", "global") }
        assertFailsWith<IllegalArgumentException> { Note("LES-1", NoteKind.LES, NoteStatus.Candidate, "s", "b", "global", modules = listOf(NoteModule("m", 1, "d"))) }
        val long = Note("LES-9", NoteKind.LES, NoteStatus.Candidate, "long body", (1..200).joinToString(" ") { "word$it" }, "global")
        assertTrue(assertFailsWith<NoteRefused> { writer.write(long, ids) }.message!!.contains("> 120"))
    }

    @Test
    fun `index regeneration is deterministic and idempotent and lists only admitted notes`() {
        writer.write(con("CON-7"), ids)
        writer.write(con("CON-2", "ledger events are append-only"), ids)
        writer.write(Note("ADR-12", NoteKind.ADR, NoteStatus.Admitted, "use integer cents everywhere", "Decided with finance.", "global", signedBy = "alice"), ids)
        writer.write(Note("PIT-3", NoteKind.PIT, NoteStatus.Admitted, "float rounding breaks refunds", "Seen when amounts are floats.", "subsystem:payments"), ids)
        writer.write(Note("LES-5", NoteKind.LES, NoteStatus.Candidate, "not yet admitted", "b", "subsystem:payments"), ids)
        writer.write(Note("STATUS-W-42", NoteKind.STATUS, NoteStatus.Admitted, "checkpoint", "b", "task:W-42"), ids)

        val dir = store.layout.kb.resolve("index")
        assertEquals(listOf("global.md", "contracts.md", "subsystem-payments.md"), KbIndex.regenerate(notes.all(), estimator, dir))
        assertEquals(emptyList(), KbIndex.regenerate(notes.all(), estimator, dir), "regenerate is idempotent")
        assertEquals("# Contracts (every active CON; regenerated)\n- CON-2: ledger events are append-only\n- CON-7: payments API returns cents as integers\n", Files.readString(dir.resolve("contracts.md")))
        val global = Files.readString(dir.resolve("global.md"))
        assertTrue("- ADR-12: use integer cents everywhere" in global && "- CON-7:" in global)
        assertTrue("STATUS" !in global && "LES-5" !in Files.readString(dir.resolve("subsystem-payments.md")))

        val many = (1..200).map { con("CON-$it", "contract number $it keeps the boundary stable for its consumers") }
        val rendered = KbIndex.render(many, estimator)
        assertTrue(estimator.estimate(rendered.getValue("global.md")).tokens <= KbIndex.GLOBAL_CAP_TOKENS)
        assertTrue(rendered.getValue("global.md").trimEnd().lines().last().matches(Regex("- \\+\\d+ more \\(kb.search\\)")))
        assertEquals(201, rendered.getValue("contracts.md").trimEnd().lines().size, "every active CON is always visible")
    }

    @Test
    fun `markdown exports are views that are never read back`() {
        writer.write(con("CON-7"), ids)
        val file = KbExport.write(store.layout.kb, notes.get("CON-7")!!)
        val text = Files.readString(file)
        assertTrue(text.startsWith("---\nid: CON-7\nkind: CON\nstatus: admitted\nsummary: payments API returns cents as integers\nscope: src/payments/**\nanchors: src/payments/api.py@abcd1234#charge\n---\n"), text)
        Files.writeString(file, text.replace("integer cents", "floats"))
        assertEquals("Amounts cross the boundary as integer cents.", notes.get("CON-7")!!.body, "the store stays the authority")

        KbExport.appendRaw(store.layout.kb, ids, "packet-1.json", "{}".toByteArray())
        assertFailsWith<FileAlreadyExistsException> { KbExport.appendRaw(store.layout.kb, ids, "packet-1.json", "{\"x\":1}".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { KbExport.appendRaw(store.layout.kb, ids, "../escape", "{}".toByteArray()) }
    }
}
