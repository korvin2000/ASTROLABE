package io.astrolabe.recover

import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.RoleTexts
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Shape
import io.astrolabe.delegate.ChildBudget
import io.astrolabe.delegate.ChildCell
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Message
import io.astrolabe.provider.SegmentKind
import io.astrolabe.route.Router
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P4.4.6: the repair helper's Diagnosis packet validator and the real repair cell behind `RepairRunner` (D-163). */
class RepairPacketTest {
    @TempDir
    lateinit var stateRoot: Path

    private val estimator = HeuristicEstimator()
    private val capsule = Capsule(
        intendedOperation = "run.run",
        acceptanceCriterion = "tests/test_total.py passes",
        callArguments = """{"argv":["pytest","tests/test_total.py"]}""",
        environment = "python 3.12, trusted-local",
        errorOrExit = "exit 2: ModuleNotFoundError: No module named 'pay'",
        artifactVersions = emptyMap(),
        completedEffects = emptyList(),
        rawEvidenceRefs = listOf("#12"),
        previousAttempts = emptyList(),
        allowedFixes = listOf("environment and build configuration within scope"),
        remainingBudget = Tokens(60_000),
    )

    @Test
    fun `the repair packet parses at the boundary and a fix outside the capsule mask is a gap`() {
        val mask = capsule.mask
        assertEquals(RepairProposal.Diagnosis("pay is not on the path"), assertIs<RepairParsed.Proposal>(RepairPacket.parse("""{"outcome":"diagnosis","text":"pay is not on the path"}""", mask, estimator)).proposal)
        assertIs<RepairProposal.Escalate>(assertIs<RepairParsed.Proposal>(RepairPacket.parse("""done: {"outcome":"escalate","text":"needs a contract change"}""", mask, estimator)).proposal)
        val fixed = assertIs<RepairProposal.Fixed>(assertIs<RepairParsed.Proposal>(RepairPacket.parse("""{"outcome":"fixed","call":{"op":"run.run","arguments":"PYTHONPATH=src pytest"},"result":"1 passed"}""", mask, estimator)).proposal)
        assertEquals(CorrectedCall("run.run", "PYTHONPATH=src pytest"), fixed.call)

        assertTrue(assertIs<RepairParsed.Gaps>(RepairPacket.parse("""{"outcome":"fixed","call":{"op":"edit.delete"},"result":"deleted"}""", mask, estimator)).gaps.single().startsWith("edit.delete is outside"))
        assertIs<RepairParsed.Gaps>(RepairPacket.parse("""{"outcome":"fixed"}""", mask, estimator))
        assertIs<RepairParsed.Gaps>(RepairPacket.parse("""{"outcome":"done","text":"all good"}""", mask, estimator))
        assertIs<RepairParsed.Gaps>(RepairPacket.parse("""{"outcome":"diagnosis","text":"${"word ".repeat(200)}"}""", mask, estimator))
        assertIs<RepairParsed.Gaps>(RepairPacket.parse("no packet", mask, estimator))

        val brief = RepairPacket.brief(RepairRequest(capsule, 2, Roles.repair.copy(toolMask = mask), mask, FakeProfiles.main, listOf("attempt 1: claimed fixed")))
        assertTrue("No module named 'pay'" in brief && "attempt 1: claimed fixed" in brief && RepairPacket.OUTPUT in brief, brief)
    }

    @Test
    fun `the repair cell runs under the repair text, gaps continue it and its diagnosis reaches the owner`() = runTest {
        CellFixture(stateRoot).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("the module is missing"))),
                Scripted.Reply(listOf(say("""{"outcome":"diagnosis","text":"pay is not importable: set PYTHONPATH=src for the test run"}"""))),
            )
            val seats = ArrayList<RoutingFunction>()
            val cell = ChildCell { seat, role, completion, budget, brief ->
                seats += seat.function
                assertTrue("Failure capsule" in brief)
                f.cell(completion = completion).run(f.context(model, role = role), f.increment, CellBudget.of(budget.tokens, budget.turns, Reserves()))
            }
            val policy = RoutingPolicy(TierTable("t1", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"))), FakeProfiles.all.filterKeys { it in setOf("helper", "main") })
            val acceptance = OriginalAcceptance { AcceptanceCheck(false, "still failing") }
            val owner = Diagnoses()
            val outcome = Repair(Router(), CellRepairRunner(cell, f.idGen, Cancellation(), estimator, ChildBudget(6, Tokens(60_000))), acceptance, estimator)
                .repair(capsule, Shape.S2, RoutingPacket(null, 2_000, 1_000), policy, owner)

            val diagnosed = assertIs<RepairOutcome.Diagnosed>(outcome, outcome.diagnosis)
            assertEquals("repair run.run: pay is not importable: set PYTHONPATH=src for the test run", diagnosed.diagnosis)
            assertEquals(listOf(diagnosed.diagnosis), owner.lines())
            assertEquals(listOf(RoutingFunction.RepairHelper), seats)
            val system = f.adapter.calls.first().request.segment(SegmentKind.S)!!.items.joinToString { (it as Message).text }
            RoleTexts.repair.forEach { assertTrue(it in system, "repair text in [S]: $it") }
            assertFalse("This cell owns one increment" in system, "the repair cell is not under the implementing gate")
            assertEquals(2, f.adapter.calls.size, "the non-packet first answer was a gap, not an outcome")
        }
    }
}
