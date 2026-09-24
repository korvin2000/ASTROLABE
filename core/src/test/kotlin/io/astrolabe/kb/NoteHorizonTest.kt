package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.evidence.Coherence
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.store.Store
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P2.6.3: the project horizon marks notes stale when their anchors or dependencies move (FX-35 pre-condition). */
class NoteHorizonTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val ids = Identities(WorkId("W-42"), AttemptId("a1"))

    @Test
    fun `a moved anchor, a moved path dependency or a newer contract marks the note stale, nothing else`() {
        TempRepo.create().use { repo ->
            repo.write("src/api.py", "def charge(): pass\n")
            repo.write("src/ledger.py", "LEDGER = []\n")
            repo.write("src/other.py", "x = 1\n")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val registry = VersionRegistry(Workspace(WorkspaceId("ws-1"), repo.root, repo.git))
                val api = registry.version("src/api.py")!!.digest.hex
                val ledger = registry.version("src/ledger.py")!!.digest.hex
                val writer = KbWriter(store, HeuristicEstimator(), clock)
                val notes = Notes(store)
                fun note(id: String, anchors: List<NoteAnchor> = emptyList(), depends: List<String> = emptyList()) =
                    writer.write(Note(id, NoteKind.CON, NoteStatus.Admitted, "contract $id", "b", "src/**", anchors, validity = NoteValidity(depends)), ids)
                note("CON-1", anchors = listOf(NoteAnchor("src/api.py", api.take(8), "charge")))
                note("CON-2", depends = listOf("src/ledger.py@${ledger.take(12)}"))
                note("CON-3", depends = listOf("CON-payments@v2"))
                note("CON-4", anchors = listOf(NoteAnchor("src/other.py", registry.version("src/other.py")!!.digest.hex.take(8))))
                writer.write(Note("LES-1", NoteKind.LES, NoteStatus.Candidate, "a candidate", "b", "src/**", listOf(NoteAnchor("src/api.py", api.take(8)))), ids)

                val horizon = NoteHorizon(writer, notes, ids)
                Coherence(registry).use { coherence ->
                    coherence.register(horizon)
                    for ((path, text) in listOf("src/api.py" to "def charge(amount): pass\n", "src/ledger.py" to "LEDGER = [1]\n")) {
                        val before = registry.version(path)
                        repo.write(path, text)
                        registry.change(path, before, registry.version(path), "edit")
                    }
                }
                horizon.contractChanged("CON-payments", 3)

                assertEquals(
                    mapOf("CON-1" to NoteStatus.Stale, "CON-2" to NoteStatus.Stale, "CON-3" to NoteStatus.Stale, "CON-4" to NoteStatus.Admitted, "LES-1" to NoteStatus.Candidate),
                    notes.all().associate { it.id to it.status },
                )
                assertEquals(listOf(NoteStatus.Admitted, NoteStatus.Stale), notes.revisions("CON-1").map { it.status }, "marked by revision, never deleted")
                assertTrue(horizon.failures.isEmpty())
            }
        }
    }
}
