package io.astrolabe.context

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.KbIndex
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.Notes
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P2.4.3: the STATUS note is a harness checkpoint revised per boundary; resume reads its archived records. */
class StatusNotesTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val work = WorkId("W-42")

    @Test
    fun `each boundary adds a revision, archived records accumulate and a reopened store reads them back`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a")
            repo.commit("initial")
            val first = ArchivedRecord(ContextId("cell-1"), "fact", 1, "v race reproduced  tests/t.py @0c28", "#3", "stale for 2 consecutive cells, unreferenced")
            val second = ArchivedRecord(ContextId("cell-2"), "fact", 4, "x the cache causes the race  (refuted #6)", "#6", "inactive: refuted")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val status = StatusNotes(KbWriter(store, HeuristicEstimator(), clock), Notes(store), store.layout.kb)
                status.checkpoint(Identities(work, AttemptId("a1"), context = ContextId("cell-1")), StatusBoundary.CellEnd, listOf(first), listOf(CarriedReceipt("CHK-1", "rcpt-3", "current")), emptyList())
                status.checkpoint(Identities(work, AttemptId("a1"), context = ContextId("cell-2")), StatusBoundary.RoleSwitch, listOf(second, first), emptyList(), listOf("handle-1"))

                val revisions = Notes(store).revisions(status.id(work))
                assertEquals(2, revisions.size, "one revision per boundary, versioned in SQLite")
                assertTrue(revisions.all { it.origin.admittedBy == "harness" && it.scope == "task:W-42" })
                assertTrue("boundary: role_switch · cell: cell-2" in revisions.last().body && "open handles: handle-1" in revisions.last().body)
                assertTrue(Files.readString(store.layout.kb.resolve("notes/STATUS-W-42.md")).contains("admitted_by: harness"))
                assertTrue("STATUS" !in KbIndex.render(Notes(store).all(), HeuristicEstimator()).values.joinToString(), "never indexed for other tasks")
            }
            Store.open(stateRoot, repo.git, clock).use { store ->
                val resumed = StatusNotes(KbWriter(store, HeuristicEstimator(), clock), Notes(store), store.layout.kb)
                assertEquals(listOf(first, second), resumed.archived(work), "resume reads the archived facts, oldest first, without duplicates")
                assertEquals(emptyList(), resumed.archived(WorkId("W-other")))
            }
        }
    }
}
