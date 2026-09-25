package io.astrolabe.recover

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellFixture.Companion.tree
import io.astrolabe.contract.Shape
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.route.Router
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RepairTest {
    @TempDir
    lateinit var dir: Path

    private val estimator = HeuristicEstimator()
    private val policy = RoutingPolicy(
        TierTable("t1", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"))),
        FakeProfiles.all.filterKeys { it in setOf("helper", "main") },
    )
    private val packet = RoutingPacket(null, 2_000, 1_000)

    private fun capsule(operation: String = "run.run") = Capsule(
        intendedOperation = operation,
        acceptanceCriterion = "tests/test_total.py passes",
        callArguments = """{"argv":["pytest","tests/test_total.py"]}""",
        environment = "python 3.12, trusted-local",
        errorOrExit = "exit 1: tests/test_total.py:2 AssertionError assert 9 == 10",
        artifactVersions = emptyMap(),
        completedEffects = emptyList(),
        rawEvidenceRefs = listOf("#12"),
        previousAttempts = emptyList(),
        allowedFixes = listOf("environment and build configuration within scope"),
        remainingBudget = Tokens(20_000),
    )

    /** The original acceptance, re-checked on the tree: the named test exists and the implementation satisfies it. */
    private val original = OriginalAcceptance {
        val test = dir.resolve("tests/test_total.py")
        val src = dir.resolve("src/total.py")
        when {
            !Files.exists(test) -> AcceptanceCheck(false, "tests/test_total.py not found: the criterion has no passing test")
            !Files.readString(src).contains("return 10") -> AcceptanceCheck(false, "tests/test_total.py failed: assert 9 == 10")
            else -> AcceptanceCheck(true, "tests/test_total.py passed")
        }
    }

    private fun tree() {
        Files.createDirectories(dir.resolve("tests"))
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("tests/test_total.py"), "def test_total():\n    assert total() == 10\n")
        Files.writeString(dir.resolve("src/total.py"), "def total():\n    return 9\n")
    }

    @Test fun `FX-34 deleting the failing test never becomes a fix and the original acceptance still fails`() = runTest {
        tree()
        val requests = ArrayList<RepairRequest>()
        val cheat = RepairRunner { request ->
            requests += request
            Files.deleteIfExists(dir.resolve("tests/test_total.py"))
            RepairProposal.Fixed(CorrectedCall("run.run", """{"argv":["pytest"]}"""), "0 failed")
        }
        val owner = Diagnoses()
        val outcome = Repair(Router(), cheat, original, estimator).repair(capsule(), Shape.S2, packet, policy, owner)

        val escalated = assertIs<RepairOutcome.Escalated>(outcome)
        assertEquals(2, escalated.attempts, "at most two attempts")
        assertTrue("original acceptance still fails" in escalated.diagnosis, escalated.diagnosis)
        assertFalse(original.check(capsule()).holds, "the original acceptance still fails after the deletion")
        assertEquals(listOf(escalated.diagnosis), owner.lines(), "the owner always receives the diagnosis line")
        // A fresh small context: the helper saw the capsule, the low-tier profile and the masked tools only.
        assertEquals("helper", requests.first().profile.id)
        assertEquals(Tier.Low, requests.first().role.tierPrior)
        assertTrue(requests.all { r -> r.mask.allowed.all { it.startsWith("look.") || it.startsWith("run.") } })
        assertTrue(requests[1].previous.single().startsWith("attempt 1: claimed fixed"))
    }

    @Test fun `a verified fix is fixed and every outcome carries a bounded diagnosis`() = runTest {
        tree()
        val honest = RepairRunner {
            Files.writeString(dir.resolve("src/total.py"), "def total():\n    return 10\n")
            RepairProposal.Fixed(CorrectedCall("run.run", """{"argv":["pytest","tests/test_total.py"]}"""), "1 passed")
        }
        val fixed = assertIs<RepairOutcome.Fixed>(Repair(Router(), honest, original, estimator).repair(capsule(), Shape.S2, packet, policy))
        assertEquals("tests/test_total.py passed", fixed.evidence)
        assertEquals(1, fixed.attempts)

        val verbose = RepairRunner { RepairProposal.Diagnosis("the build needs the pay module on the path; " + "detail ".repeat(400)) }
        val diagnosed = assertIs<RepairOutcome.Diagnosed>(Repair(Router(), verbose, original, estimator).repair(capsule(), Shape.S3, packet, policy))
        assertTrue(estimator.estimate(diagnosed.diagnosis).tokens <= Repair.MAX_DIAGNOSIS_TOKENS, diagnosed.diagnosis)

        val outsideMask = RepairRunner { RepairProposal.Fixed(CorrectedCall("edit.delete", "{}"), "deleted") }
        assertIs<RepairOutcome.Escalated>(Repair(Router(), outsideMask, original, estimator).repair(capsule(), Shape.S2, packet, policy))

        var called = false
        val never = RepairRunner { called = true; RepairProposal.Escalate("unused") }
        val s1 = assertIs<RepairOutcome.Escalated>(Repair(Router(), never, original, estimator).repair(capsule(), Shape.S1, packet, policy))
        assertFalse(called, "S2+ only")
        assertTrue("S2+" in s1.diagnosis)
    }

    @Test fun `the diagnosis line is present in the owning cell's next anchor`(@TempDir stateRoot: Path) = runTest {
        tree()
        val line = Repair(Router(), { RepairProposal.Diagnosis("pip cache is read-only; set PIP_CACHE_DIR inside the workspace") }, original, estimator)
            .repair(capsule(), Shape.S2, packet, policy).diagnosis
        CellFixture(stateRoot).use { f ->
            val owner = Diagnoses()
            val model = ScriptedModel.build {
                // The repair returns while the owning cell's first turn is in flight.
                on({ true }) { owner.post(line); Scripted.Reply(listOf(say("looking"), tree("c1"))) }
                fallback(Scripted.Reply(listOf(say("done"))))
            }
            f.cell().run(f.context(model, diagnoses = owner), f.increment, f.budget())
            assertFalse(line in f.anchorText(1))
            assertTrue(line in f.anchorText(2), f.anchorText(2))
        }
    }
}
