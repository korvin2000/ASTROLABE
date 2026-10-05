package io.astrolabe.budget

import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C3r 3 at the cell: how a turn reacts when the task limits' answer changes between its start and its admission. */
class TaskLimitTurnTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `a generation turn whose rendered request first meets the reserve is rendered again as verify and report`() = runBlocking<Unit> {
        CellFixture(stateRoot).use { f ->
            // The turn starts within the limits (priced at the last request); the rendered request is dearer and reaches the reserve.
            val asked = ArrayList<Pair<Spend, Long>>()
            val gate = LimitGate { spend, estimate ->
                val admitted = asked.any { it.second > 0 }
                asked += spend to estimate.value
                if (estimate.value == 0L && !admitted) LimitDecision.Within else LimitDecision.Reserve(LimitKind.Cost, "task limit: the next call reaches the reserve")
            }
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("an edit after all"), anchored("e1", "src/a.py", f.version("src/a.py"), "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("done"))),
            )
            val exit = f.cell().run(f.context(model), f.increment, CellBudget.of(Tokens(400_000), 12, Reserves(), limits = gate))

            assertTrue(f.adapter.calls.isNotEmpty(), "the cell did not end at the admission: $exit")
            val admissions = asked.filter { it.second > 0 }.map { it.first }
            assertEquals(listOf(Spend.Generation, Spend.Check), admissions.take(2), "refused as generation, admitted as verify-and-report")
            assertFalse(f.request(1).mask!!.allows("edit.anchored"), "the re-rendered turn masks edits")
            assertTrue(CellBudget.GATE in f.anchorText(1), "and says why")
            assertEquals(CellFixture.A_PY, Files.readString(f.repo.resolve("src/a.py")))
            assertFalse(exit is CellExit.Partial && f.adapter.calls.isEmpty())
        }
    }

    @Test
    fun `a bounded child whose working tokens cannot hold the least output still takes the reserve turn the task limit opens`() = runBlocking<Unit> {
        CellFixture(stateRoot).use { f ->
            // WD-16 with C3r: 1000 working tokens leave no output after the input, the verification reserve does; the limit's
            // reserve at admission re-renders the turn as verify-and-report instead of ending the cell on the shortfall.
            val asked = ArrayList<Pair<Spend, Long>>()
            val gate = LimitGate { spend, estimate ->
                val admitted = asked.any { it.second > 0 }
                asked += spend to estimate.value
                if (estimate.value == 0L && !admitted) LimitDecision.Within else LimitDecision.Reserve(LimitKind.Cost, "task limit: the next call reaches the reserve")
            }
            val model = ScriptedModel.of(Scripted.Reply(listOf(say("verified, done"))))
            val ctx = f.context(model).also { it.boundedOutput = true }
            val budget = CellBudget(Tokens(31_000), 12, CellReserve(Tokens(30_000), 2, Tokens.ZERO, 0), limits = gate)
            val exit = f.cell().run(ctx, f.increment, budget)

            assertTrue(f.adapter.calls.isNotEmpty(), "the shortfall ended the cell before the reserve turn: $exit")
            assertEquals(listOf(Spend.Generation, Spend.Check), asked.filter { it.second > 0 }.map { it.first }.take(2), "refused as generation, admitted as verify-and-report")
            assertFalse(f.request(1).mask!!.allows("edit.anchored"), "the re-rendered turn masks edits")
            assertTrue(f.request(1).maxOutputTokens <= 16_000, "the bounded output never exceeds the model's")
        }
    }
}
