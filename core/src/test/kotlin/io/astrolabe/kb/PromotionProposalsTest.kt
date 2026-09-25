package io.astrolabe.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P5.6.2: executable promotion rendered as proposed tasks, generalized only on held-out evidence, exported, never committed. */
class PromotionProposalsTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val ids = Identities(WorkId("W-7"), AttemptId("a1"), context = ContextId("cell-1"))

    @Test
    fun `promotions become proposal records in exports and a rule generalizes only on clean held-out evidence`() {
        TempRepo.create().use { repo ->
            repo.write("src/pay/refund.py", "def refund(): pass\n")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val estimator = HeuristicEstimator()
                val writer = KbWriter(store, estimator, clock)
                val usage = Usage(store, clock)
                writer.write(Note("PIT-3", NoteKind.PIT, NoteStatus.Admitted, "float rounding breaks refunds when amounts are floats", "Seen twice.", "subsystem:pay", basis = NoteBasis(evidenceRefs = listOf("#1", "#2"))), ids)
                writer.write(Note("LES-4", NoteKind.LES, NoteStatus.Admitted, "the refund config field currency is required", "Missing currency broke refunds.", "src/pay/**", basis = NoteBasis(evidenceRefs = listOf("#3"))), ids)
                for (cell in 1..3) listOf("PIT-3", "LES-4").forEach { usage.record(it, ids.copy(context = ContextId("cell-$cell")), UsageEvent.Cited) }
                val before = repo.git.status()
                val originals = Notes(store).all().associateBy { it.id }

                val promotions = Curator(store, estimator, idGen, clock).promote(ids).associateBy { it.noteId }
                val pit = PromotionProposals.of(promotions.getValue("PIT-3"), originals.getValue("PIT-3"), HeldOutEvidence("heldout-1", tasks = 12, falsePositives = 0))
                val les = PromotionProposals.of(promotions.getValue("LES-4"), originals.getValue("LES-4"), HeldOutEvidence("heldout-2", tasks = 8, falsePositives = 2))
                val local = PromotionProposals.of(promotions.getValue("LES-4"), originals.getValue("LES-4"))

                assertEquals("test", pit.check)
                assertEquals("generalizable", pit.generalization)
                assertEquals("global", pit.scope)
                assertEquals("schema", les.check)
                assertTrue(les.generalization.startsWith("blocked: 2 of 8 held-out tasks"), les.generalization)
                assertEquals("src/pay/**", les.scope, "without clean held-out evidence the rule keeps the note's scope")
                assertEquals("local", local.generalization)
                assertEquals(listOf("#1", "#2"), pit.evidence)
                assertTrue(listOf(pit, les, local).all { it.status == PromotionProposal.PROPOSED })

                val file = PromotionProposals.export(store.layout, ids.work, listOf(pit, les))
                assertEquals(store.layout.exports.resolve("W-7").resolve(PromotionProposals.FILE), file)
                val read = Json.decodeFromString(ListSerializer(PromotionProposal.serializer()), Files.readString(file))
                assertEquals(listOf(pit, les), read)
                assertEquals(before, repo.git.status(),"a proposal is never committed or applied to the workspace")
            }
        }
    }
}
