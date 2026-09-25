package io.astrolabe.eval

import io.astrolabe.Config
import io.astrolabe.contract.Shape
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.id.Digest
import io.astrolabe.kb.KbInjection
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignTest {
    private val base = Config(profiles = FakeProfiles.all)

    @Test
    fun `FX-47 hidden final answers reachable through memory or a note reject the campaign as contaminated`(@TempDir dir: Path) {
        val solver = dir.resolve("solver").toString()
        val hiddenTest = Digest.ofUtf8("def test_hidden(): assert parse('x') == 1")
        val hidden = listOf(HiddenAcceptance("t1", dir.resolve("hidden/test_t1.py").toString(), hiddenTest))
        val answers = listOf(AnswerKey("t1", listOf("    return int(value.strip() or 0)", "}")))
        val clean = RunMemory("warm-1", MemoryMode.Warm, listOf(MemoryItem("n1", MemoryItemKind.Note, "prefer explicit parsers")))
        fun input(
            memories: List<RunMemory> = listOf(RunMemory("cold-1", MemoryMode.Cold, emptyList()), clean),
            files: Map<String, Digest> = mapOf("src/parse.py" to Digest.ofUtf8("old")),
            root: String = solver,
            looks: List<Consultation> = listOf(Consultation(WorkloadPartition.Selection, 1, "select"), Consultation(WorkloadPartition.Final, 5, "final")),
        ) = IntegrityInput(hidden, listOf(SolverWorkspace("run-1", root, files)), answers, memories, clean.digest, looks, freezeSequence = 3)

        val passing = CampaignIntegrity.check(input())
        assertFalse(passing.contaminated); assertEquals(EvidenceCheck.Pass, passing.evidence)

        // The FX-47 case: the answer line of the reference repair sits in a note the solver can read.
        val leaked = RunMemory("warm-2", MemoryMode.Warm, clean.items + MemoryItem("n2", MemoryItemKind.Note, "fix: return   int(value.strip() or 0)"))
        val rejected = CampaignIntegrity.check(input(memories = listOf(leaked)))
        assertTrue(rejected.contaminated); assertEquals(EvidenceCheck.Fail, rejected.evidence)
        assertEquals(setOf(ContaminationKind.AnswerInMemory, ContaminationKind.WarmMemoryNotFrozen), rejected.findings.map { it.kind }.toSet())
        assertTrue(rejected.findings.first { it.kind == ContaminationKind.AnswerInMemory }.detail.contains("Note n2 carries t1"))

        fun kinds(i: IntegrityInput) = CampaignIntegrity.check(i).findings.map { it.kind }
        assertEquals(listOf(ContaminationKind.HiddenAcceptanceInWorkspace), kinds(input(root = dir.toString())))
        assertEquals(listOf(ContaminationKind.HiddenAcceptanceCopied), kinds(input(files = mapOf("tests/copy.py" to hiddenTest))))
        assertEquals(listOf(ContaminationKind.ColdMemoryNotReset), kinds(input(memories = listOf(RunMemory("cold-2", MemoryMode.Cold, clean.items)))))
        assertEquals(listOf(ContaminationKind.FinalConsultedBeforeFreeze),
            kinds(input(looks = listOf(Consultation(WorkloadPartition.Final, 2, "peek")))))

        // A repeatedly consulted selection set is flagged as no longer a holdout; it rejects only evidence drawn from it.
        val reselected = input(looks = (1..3L).map { Consultation(WorkloadPartition.Selection, it, "select round $it") })
        val flagged = CampaignIntegrity.check(reselected)
        assertEquals(setOf(WorkloadPartition.Selection), flagged.consumed)
        assertFalse(flagged.contaminated)
        assertTrue(CampaignIntegrity.check(IntegrityInput(hidden, reselected.workspaces, answers, reselected.memories, clean.digest,
            reselected.consultations, 3, evidencePartition = WorkloadPartition.Selection)).contaminated)
    }

    @Test
    fun `every production flag is enumerated once in the one arms table and the README renders it`() {
        val flagged = EvalArm.entries.mapNotNull { it.flag }
        assertEquals(EvalArms.flagNames().sorted(), flagged.sorted(), "each Flags switch exactly once")
        val readme = Files.readString(Path.of("README.md")).replace("\r\n", "\n")
        val table = readme.substringAfter("<!-- arms-table:start -->\n").substringBefore("<!-- arms-table:end -->")
        assertEquals(EvalArms.table(), table)
        // Every flag level is expressible in a production attempt.
        EvalArm.entries.filter { it.kind == ArmKind.Flag }.forEach { arm ->
            arm.levels.forEach { assertTrue(EvalArms.configure(arm, it, base).attempt!!.production) }
        }
        assertEquals(KbInjection.Frozen, EvalArms.configure(EvalArm.KnowledgeInjection, "frozen", base).attempt!!.config.flags.kbInjection)
    }

    @Test
    fun `comparators and arms freeze as attempts, a disabled control is runnable but ineligible, live gates stay unmeasured`() {
        val reserveOff = EvalArms.configure(EvalArm.Reserve, "off", base)
        assertFalse(reserveOff.attempt!!.production); assertFalse(reserveOff.attempt.controls.reserve)
        assertFalse(reserveOff.promotionEligible)
        assertTrue(EvalArms.configure(EvalArm.Reserve, "on", base).promotionEligible)
        assertTrue(EvalArms.configure(EvalArm.FunctionRouting, "all-high", base).promotionEligible)
        assertFalse(EvalArms.configure(EvalArm.FunctionRouting, "clamped", base).promotionEligible)
        val research = EvalArms.configure(EvalArm.ImpactNudge, "off", base)
        assertNull(research.attempt); assertFalse(EvalArm.ImpactNudge.runnable)
        assertEquals(Shape.S1, EvalArms.configure(EvalArm.Shapes, "S1", base).maxShape)

        assertNull(Variants.configure(Variant.B0, base).attempt)
        assertNull(Variants.configure(Variant.BHelm, base).attempt)
        val b5 = Variants.configure(Variant.B5, base).attempt!!
        assertTrue(b5.production && b5.config.flags.denseRetrieval && b5.config.flags.s3Writers)
        assertEquals(Shape.S0, Variants.configure(Variant.B1, base).maxShape)
        assertFailsWith<IllegalArgumentException> { Variants.configure(Variant.Target, base) }

        val gates = LiveGates.all()
        assertTrue(gates.all { it.status == LiveGateStatus.UNMEASURED && it.prerequisites.isNotEmpty() && it.evidence.isNotEmpty() })
        assertEquals(EvalArms.flagNames().size, gates.count { it.id.startsWith("flag.") })
    }

    @Test
    fun `a campaign manifest freezes once over a validated workload partition`(@TempDir dir: Path) {
        val policy = WorkloadPolicy("w1", mapOf("hard" to true, "easy" to false), setOf(WorkloadGrouping.Repository), emptyList(),
            emptyMap(), emptyMap(), WorkloadPartition.entries.map { WorkloadQuota(it, null, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE) })
        val design = WorkloadDesign(policy, listOf(
            WorkloadTask("a", setOf(0, 1), "r1", null, "hard", BigDecimal.ONE, null),
            WorkloadTask("b", setOf(0), "r2", null, "easy", BigDecimal.ONE, null),
            WorkloadTask("c", setOf(0), "r3", null, "hard", BigDecimal.ONE, null),
        ))
        val assignment = mapOf(WorkloadTrialKey("a", 0) to WorkloadPartition.Development, WorkloadTrialKey("a", 1) to WorkloadPartition.Development,
            WorkloadTrialKey("b", 0) to WorkloadPartition.Selection, WorkloadTrialKey("c", 0) to WorkloadPartition.Final)
        val variants = listOf(Variants.configure(Variant.B1, base), Variants.configure(Variant.B0, base))
        val arms = listOf(EvalArms.configure(EvalArm.Reserve, "off", base), EvalArms.configure(EvalArm.Reserve, "on", base))
        val manifest = CampaignManifest("pilot-1", "0.1.0", variants, arms, design, assignment, MemoryMode.Cold)
        assertEquals(listOf("r1", "r2", "r3"), manifest.repositories)
        assertEquals(listOf(Variant.B0, Variant.B1), manifest.variants.map { it.variant })

        val file = CampaignManifests.freeze(dir, manifest)
        assertTrue(Files.readString(file).contains(manifest.fingerprint.hex))
        assertEquals(file, CampaignManifests.freeze(dir, manifest))
        val warm = CampaignManifest("pilot-1", "0.1.0", variants, arms, design, assignment, MemoryMode.Warm)
        assertFalse(CampaignManifests.matches(dir, warm))
        assertFailsWith<IllegalStateException> { CampaignManifests.freeze(dir, warm) }

        // A split repetition or a harness mismatch never freezes.
        assertFailsWith<IllegalArgumentException> {
            CampaignManifest("bad", "0.1.0", variants, arms, design, assignment + (WorkloadTrialKey("a", 1) to WorkloadPartition.Final), MemoryMode.Cold)
        }
        assertFailsWith<IllegalArgumentException> { CampaignManifest("bad", "9.9.9", variants, arms, design, assignment, MemoryMode.Cold) }
    }
}
