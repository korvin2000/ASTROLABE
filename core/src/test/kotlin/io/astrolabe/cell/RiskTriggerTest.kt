package io.astrolabe.cell

import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The `risk > θ` row of the §8.1 layer table (§7.4 verification depth), wired in P4.5.2. */
class RiskTriggerTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `an edit batch whose risk exceeds theta runs the blast layer at once and a small edit waits for the step boundary`() = runTest {
        val pass = javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        val big = (0 until 30).joinToString("") { "X$it = 0\n" }
        CellFixture(stateRoot, files = CellFixture.DEFAULT_FILES + ("pytest_pass.txt" to pass) + ("src/big.py" to big)).use { f ->
            val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
            f.checks.register(Check(Checks.FULL, CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = printing))
            val a = f.version("src/a.py")
            val b = f.version("src/big.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading"), read("c0", "src/a.py"), read("c1", "src/big.py"))),
                Scripted.Reply(listOf(say("small edit"), anchored("c2", "src/a.py", a, "    return 1", "    return 10"))),
                // 30 lines out, 30 in: the estimate is at least 60 > θ = 40 whatever the fan-in.
                Scripted.Reply(listOf(say("big edit"), anchored("c3", "src/big.py", b, big.trimEnd('\n'), big.trimEnd('\n').replace(" = 0", " = 1")))),
                Scripted.Reply(listOf(say("stopping here"))),
            )

            f.run(model)

            val lines = f.journal.events(JournalScope(f.ids.work, kinds = setOf(JournalKind.Check))).filter { it.text.startsWith("risk > θ") }
            assertEquals(listOf(3), lines.map { it.turn }, lines.map { it.text }.toString())
            assertEquals("risk > θ ${Checks.TESTS_BLAST}: passed", lines.single().text)
        }
    }
}
