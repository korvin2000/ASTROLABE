package io.astrolabe.context

import io.astrolabe.auth.Redaction
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellCheckpoint
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellStatus
import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.os.search.Searches
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.register.Fact
import io.astrolabe.register.Register
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.look.Look
import io.astrolabe.workset.Workset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P2.4.4: the export/seed round trip and cross-cell recall over campaign-global aliases (IX-06, P2 form). */
class SeedsTest {
    @TempDir
    lateinit var stateRoot: Path

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "look", json))) as ParsedCalls.Valid).calls.single()

    private suspend fun Look.run(json: String, workset: Workset, turn: Int = 1): ToolOutcome =
        execute(call(json), io.astrolabe.tool.TurnContext(turn, workset.snapshot(), Reservations(Tokens(100_000))))

    @Test
    fun `a later cell seeds, recalls and resolves the earlier cell's references unambiguously (IX-06)`() = runBlocking<Unit> {
        CellFixture(stateRoot).use { f ->
            val cell1 = f.ids.context!!
            val read = f.look.run("""{"what":"read","target":"src/a.py"}""", f.workset)
            assertEquals("ok", read.header!!.runtime.status, read.body)
            val version = f.version("src/a.py")
            f.checkpoints.save(f.ids, CellCheckpoint(cell1, "inc-1", 1, CellStatus.Completed, 1, null, 1, 0, emptyList(), emptyList(), emptyList(), 0), f.workset.export())

            // Cell end → seeds for the next cell, displayed at their hash.
            val export = Seeds.cellEnd(f.checkpoints, cell1)
            assertEquals(listOf("src/a.py"), export.map { it.path })
            val previous = Register.empty(cell1, "inc-1", "make a return 10").copy(
                next = "edit a.py",
                facts = listOf(Fact(1, ClaimKind.Verified, "a returns 1", Anchor("src/a.py", version), "#1")),
            )
            val carry = CarryForward.carry(previous, export, null, { f.registry.version(it) }, { true }, emptyList(), emptyList())
            val seeds = Seeds.render(carry.seeds, f.registry::read)
            assertTrue(seeds.text.startsWith("SEED src/a.py:1-6 @${version.hash8}\n1 | def a():\n2 |     return 1\n"), seeds.text)

            // Cell 2 shares the campaign store: #1 still names cell 1's observation and new results continue the numbering.
            val cell2 = f.ids.copy(context = ContextId("cell-2"))
            val workset2 = Workset(immediateStubTokens = f.defaults.immediateStubTokens).also { it.seed(seeds.shown) }
            val look2 = Look(f.workspace, f.registry, workset2, f.atlas, Searches.jvm(), f.journal, f.observations, f.aliases, f.store.blobs, Redaction(), f.estimator, f.idGen, cell2)
            val recalled = look2.run("""{"what":"recall","id":"#1"}""", workset2, turn = 1)
            assertEquals("ok", recalled.header!!.runtime.status, recalled.body)
            assertEquals(cell1, f.aliases.resolve(f.ids.work, 1)!!.context)
            assertEquals(ContextId("cell-2"), f.aliases.resolve(f.ids.work, 2)!!.context, "the recall is cell 2's own #2")
            assertEquals("#1 → result ${f.aliases.resolve(f.ids.work, 1)!!.canonicalId} (from cell-1)", Seeds.stubIndex(carry.register, f.ids.work, f.aliases))

            // The file moves: the same #1 is recalled as historical and the seed is announced NOT SEEN.
            f.repo.write("src/a.py", "def a():\n    return 2\n")
            val historical = look2.run("""{"what":"recall","id":"#1"}""", workset2, turn = 2)
            assertEquals("historical", historical.header!!.runtime.status, historical.body)
            val moved = Seeds.render(carry.seeds, f.registry::read)
            assertEquals(emptyList(), moved.shown)
            assertEquals(listOf("src/a.py"), moved.notSeen.map { it.path })
        }
    }
}
