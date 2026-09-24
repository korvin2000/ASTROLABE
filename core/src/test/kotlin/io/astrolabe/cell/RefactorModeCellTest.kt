package io.astrolabe.cell

import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Outcome
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.register.Mark
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.RefactorMode
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P3.5.1 FX-12: a multi-file interface migration is temporarily red; `red_ok_until: increment_end` lets the steps advance, the final gate still refuses. */
class RefactorModeCellTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `a red check between steps does not block the cursor advance in refactor mode, while a red completion is still refused`() = runTest {
        val failing = javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        val request = "rename a to alpha and migrate the API of its callers"
        CellFixture(stateRoot, files = CellFixture.DEFAULT_FILES + ("pytest_fail.txt" to failing), request = request).use { f ->
            assertTrue(RefactorMode.isActive(f.contract), "the contract's requirement is behaviour-preserving")
            val red = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_fail.txt&exit /b 1")) else Command(listOf("/bin/sh", "-c", "cat pytest_fail.txt; exit 1"))
            f.checks.register(Check("CHK-step", CheckKind.Unit, Selector.Named(red), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.StepBoundary, acceptanceIds = listOf("AC-S"), command = red))
            val v = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("planning"), patch("c1", """{"plan.add":{"text":"change the signature","accept":"AC-S"}},{"plan.add":{"text":"update the callers"}},{"plan.cursor":1},{"next":"edit a"}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
                // Leaving step 1 runs CHK-step at the step boundary: red, the interface changed before its callers.
                Scripted.Reply(listOf(say("signature done"), patch("c3", """{"plan.tick":{"n":1,"evidence":"#1"}},{"plan.cursor":2},{"next":"callers"}"""))),
                // The advance out of step 2 with CHK-step red and no Open item: rejected outside refactor mode, accepted here.
                Scripted.Reply(listOf(say("callers done"), patch("c4", """{"plan.tick":{"n":2,"evidence":"#1"}},{"next":"finish"}"""))),
                Scripted.Reply(listOf(say("done: renamed"))),
                Scripted.Reply(listOf(say("done: renamed, really"))),
            )

            val exit = f.run(model)

            val boundaries = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Check))).map { it.text }
            assertTrue(boundaries.contains("step boundary CHK-step: failed"), boundaries.toString())
            assertEquals(Outcome.Failed, f.checks["CHK-step"]!!.last?.outcome)
            val register = f.state.register
            assertEquals(listOf(Mark.Done, Mark.Done), register.plan.map { it.mark }, "both steps ticked: no red-not-recorded rejection, no per-file rollback")
            assertTrue(register.open.isEmpty(), "no Open item was needed for the temporary red")
            assertEquals(1, f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Boundary))).count { it.text.startsWith("refactor mode (R1: behaviour-preserving requirement") && it.text.endsWith("red_ok_until increment_end") })

            val partial = assertIs<CellExit.Partial>(exit)
            assertEquals(PartialReason.CompletionStalled, partial.reason)
            assertTrue(partial.hint.contains("CHK-step is red without an Open item naming it"), partial.hint)
        }
    }
}
