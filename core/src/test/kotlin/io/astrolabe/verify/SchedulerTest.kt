package io.astrolabe.verify

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.ClosureCompleteness
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InMemoryAliases
import io.astrolabe.evidence.InputStability
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Store
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.7.4 receipts and currency: FX-08 (a wrapper's exit 0 is never green), IX-04 (tested inputs and stability), scratch policy, §8.4 applicability. */
class SchedulerTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var registry: VersionRegistry
    private lateinit var coherence: Coherence
    private lateinit var stamper: Stamper
    private lateinit var checks: Checks
    private lateinit var scheduler: Scheduler
    private lateinit var receipts: SqliteReceipts
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/pkg/b.py", "x = 1\n")
        repo.write("src/pkg/c.py", "y = 2\n")
        repo.write("tests/test_a.py", "def test_a():\n    assert True\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(Workset()) }
        stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
        checks = Checks.empty()
        checks.register(Check("CHK-accept-AC-1", CheckKind.Acceptance, Selector.Named(Command(listOf("pytest", "-q"))), Closure.Known(setOf("src/a.py", "tests/test_a.py")), CostClass.Slow, Trigger.IncrementEnd, acceptanceIds = listOf("AC-1"), command = Command(listOf("pytest", "-q"))))
        checks.register(Check("CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = Command(listOf("pytest"))))
        checks.register(Check("CHK-pkg", CheckKind.Unit, Selector.Touched, Closure.Package("src/pkg"), CostClass.Fast, Trigger.EndOfTurn, command = Command(listOf("pytest", "src/pkg"))))
        receipts = SqliteReceipts(store, clock)
        scheduler = Scheduler(checks, workspace, registry, stamper, receipts, InMemoryAliases(), idGen, ids, clock)
        coherence.register(checks)
    }

    @AfterTest
    fun tearDown() {
        coherence.close()
        store.close()
        repo.close()
    }

    /** The raw log is published before the receipt references it (§4.3: artifact publication precedes the transaction). */
    private fun passed(counts: Counts? = Counts(passed = 3, discovered = 3), exit: Int? = 0, outcome: Outcome = Outcome.Passed) =
        Executed(listOf("pytest", "-q"), null, false, exit, outcome, counts, store.blobs.put("3 passed\n".toByteArray(), BlobKind.LOG, ids))

    private fun accept() = checks["CHK-accept-AC-1"]!!

    @Test
    fun `a clean check yields an exclusive, eligible, green receipt that certifies the current stamp and goes stale when the tree moves`() = runTest {
        val receipt = scheduler.runCheck(accept(), contractVersion = 1) { passed() }
        assertEquals("rcpt-1", receipt.receiptId)
        assertEquals(Outcome.Passed, receipt.outcome)
        assertEquals(setOf("src/a.py", "tests/test_a.py"), receipt.testedInputs.versions.keys)
        assertEquals(registry.version("src/a.py"), receipt.testedInputs.versions["src/a.py"])
        assertEquals(InputStability.Exclusive, receipt.testedInputs.stability)
        assertTrue(receipt.testedInputs.eligible && receipt.greenForFinalTree)
        assertEquals(receipt.stampBefore, receipt.stampAfter)
        assertEquals(accept().definitionVersion, receipt.checkDefinitionVersion)
        assertEquals(listOf("AC-1"), receipt.acceptanceIds)
        assertEquals("#1", scheduler.aliasOf("rcpt-1"))
        assertEquals(receipt, receipts.get("rcpt-1"), "the receipt round-trips through the store")
        assertEquals(listOf("rcpt-1"), receipts.forCheck("CHK-accept-AC-1").map { it.receiptId })
        assertEquals("rcpt-1", accept().last!!.receiptId)

        val now = scheduler.currency(accept(), stamper.stamp().id)
        assertTrue(now.certifies, now.reasons.toString())

        repo.write("src/a.py", "def a():\n    return 2\n")
        val moved = scheduler.currency(accept(), stamper.stamp().id)
        assertEquals(Applicability.Stale, moved.applicability)
        assertFalse(moved.certifies)
        assertTrue(moved.reasons.any { it.contains("closure moved: src/a.py") }, moved.reasons.toString())
    }

    @Test
    fun `an unrelated edit keeps a complete unchanged closure current with a reuse proof, one moved closure path makes it stale (FX-54)`() = runTest {
        val receipt = scheduler.runCheck(accept(), 1) { passed() }
        assertEquals(ClosureCompleteness.Complete, receipt.closureManifest!!.completeness)

        repo.write("src/pkg/b.py", "x = 3\n")
        val stampNow = stamper.stamp().id
        val reused = scheduler.currency(accept(), stampNow)
        assertTrue(reused.certifies, reused.reasons.toString())
        val proof = accept().last!!.reuseProof!!
        assertEquals("rcpt-1", proof.reuseOf)
        assertEquals("rcpt-1", accept().last!!.reuseOf)
        assertEquals(stampNow, proof.stamp)
        assertEquals(mapOf("src/a.py" to registry.version("src/a.py")!!.digest.hex, "tests/test_a.py" to registry.version("tests/test_a.py")!!.digest.hex), proof.closureUnchanged)
        assertEquals(receipt, receipts.get("rcpt-1"), "reuse never rewrites the historical receipt")

        // One changed file inside a larger closure: a non-empty intersection is enough, containment is not required.
        repo.write("tests/test_a.py", "def test_a():\n    assert 1\n")
        val moved = scheduler.currency(accept(), stamper.stamp().id)
        assertEquals(Applicability.Stale, moved.applicability)
        assertTrue(moved.reasons.single().endsWith("closure moved: tests/test_a.py"), moved.reasons.toString())
        assertEquals(null, accept().last!!.reuseProof)
        assertEquals(Outcome.Passed, receipts.get("rcpt-1")!!.outcome)
    }

    @Test
    fun `an old green receipt is stale after the definition, verifier or environment changed, whatever the closure (FX-16)`() = runTest {
        val receipt = scheduler.runCheck(accept(), 1) { passed() }
        repo.write("src/pkg/b.py", "x = 3\n")
        val report = stamper.report()
        val repinned = scheduler.manifestOf(receipt.inputClosure)
        val same = CandidateNow(report.candidateId, accept().definitionVersion, receipt.verifierVersion, report.env.envId, report.env.envKnown, repinned)
        assertEquals(Applicability.Current, Applicability.of(receipt, same).applicability)

        val changedArgv = accept().copy(command = Command(listOf("pytest", "-x"))).definitionVersion
        val variants = mapOf(
            "check definition changed" to same.copy(definitionVersion = changedArgv),
            "verifier version" to same.copy(verifierVersion = "other"),
            "environment moved" to same.copy(envId = io.astrolabe.id.Digest.ofUtf8("lockfile moved")),
            "environment unknown" to same.copy(envKnown = false),
        )
        for ((reason, now) in variants) {
            val verdict = Applicability.of(receipt, now)
            assertEquals(Applicability.Stale, verdict.applicability, reason)
            assertTrue(verdict.reason!!.contains(reason), verdict.reason)
            assertEquals(null, verdict.reuse)
        }
        assertEquals(Applicability.Unknown, Applicability.of(receipt, same.copy(stamp = null)).applicability)
    }

    @Test
    fun `a package closure pins membership, so an added test file invalidates it while scratch output does not`() = runTest {
        val pkg = checks["CHK-pkg"]!!
        val receipt = scheduler.runCheck(pkg, 1) { passed() }
        assertEquals(mapOf("src/pkg" to listOf("src/pkg/b.py", "src/pkg/c.py")), receipt.closureManifest!!.directoryMembership)

        repo.write("src/a.py", "def a():\n    return 2\n")
        repo.write("src/pkg/__pycache__/b.cpython-312.pyc", "cache")
        assertTrue(scheduler.currency(pkg, stamper.stamp().id).certifies)
        assertEquals("rcpt-1", checks["CHK-pkg"]!!.last!!.reuseProof!!.reuseOf)

        // Same paths, same bytes: a path list alone would call this unchanged.
        repo.write("src/pkg/test_new.py", "def test_new():\n    assert False\n")
        val grown = scheduler.currency(pkg, stamper.stamp().id)
        assertEquals(Applicability.Stale, grown.applicability)
        assertTrue(grown.reasons.single().contains("src/pkg/ (membership)"), grown.reasons.toString())
    }

    @Test
    fun `an unknown closure never backs a reuse proof`() = runTest {
        val full = checks["CHK-full"]!!
        scheduler.runCheck(full, 1, inputs = listOf("src/a.py", "tests/test_a.py")) { passed() }
        repo.write("src/pkg/b.py", "x = 3\n")
        val moved = scheduler.currency(full, stamper.stamp().id)
        assertEquals(Applicability.Stale, moved.applicability)
        assertTrue(moved.reasons.single().contains("closure unknown: rerun at the containing scope"), moved.reasons.toString())
    }

    private fun isolated() = Scheduler(checks, workspace, registry, stamper, receipts, InMemoryAliases(), idGen, ids, clock, candidates = stateRoot.resolve("candidates"))

    @Test
    fun `a slow check runs on an isolated candidate that a concurrent workspace writer cannot touch, scratch output allowed (FX-17)`() = runTest {
        val exported = stamper.stamp().id
        var root: Path? = null
        val receipt = isolated().runCheck(accept(), 1) { dir ->
            root = dir
            assertEquals("def a():\n    return 1\n", Files.readString(dir.resolve("src/a.py")))
            repo.write("src/a.py", "def a():\n    return 5\n")
            repo.write("src/a.py", "def a():\n    return 1\n")
            Files.createDirectories(dir.resolve(".pytest_cache"))
            Files.writeString(dir.resolve(".pytest_cache/lastfailed"), "{}")
            passed()
        }
        assertTrue(root != repo.root && !Files.exists(root!!), "the candidate is a disposable copy: $root")
        assertEquals(InputStability.Isolated, receipt.testedInputs.stability)
        assertTrue(receipt.greenForFinalTree, receipt.limits.toString())
        assertEquals(exported, receipt.stampBefore)
        assertEquals(exported, receipt.stampAfter)
        assertEquals(setOf("src/a.py", "tests/test_a.py"), receipt.testedInputs.versions.keys)
        assertTrue(receipt.limits.any { it.kind == "external_services" }, receipt.limits.toString())
        assertTrue(scheduler.currency(accept(), stamper.stamp().id).certifies)
    }

    @Test
    fun `a write inside the isolated candidate, even restored to the old bytes, cannot certify it`() = runTest {
        val receipt = isolated().runCheck(accept(), 1) { dir ->
            val file = dir.resolve("src/a.py")
            val old = Files.readAllBytes(file)
            Files.writeString(file, "def a():\n    return 7\n")
            Files.write(file, old)
            Files.setLastModifiedTime(file, FileTime.fromMillis(0))
            Files.writeString(dir.resolve("src/generated.py"), "x = 1\n")
            passed()
        }
        assertEquals(Outcome.Passed, receipt.outcome, "the factual outcome stands")
        assertEquals(setOf("src/a.py", "src/generated.py"), receipt.testedInputs.mutatedDuringCheck)
        assertFalse(receipt.greenForFinalTree)
        assertFalse(scheduler.currency(accept(), stamper.stamp().id).certifies)
    }

    @Test
    fun `a wrapper's exit 0 never becomes green and a pass without counts is inconclusive (FX-08)`() = runTest {
        val wrapped = scheduler.runCheck(accept(), 1) { passed(counts = Counts(passed = 11, failed = 1, discovered = 12), exit = 0, outcome = Outcome.Failed) }
        assertEquals(Outcome.Failed, wrapped.outcome)
        assertEquals(0, wrapped.exitCode)
        assertFalse(wrapped.greenForFinalTree)
        assertFalse(scheduler.currency(accept(), stamper.stamp().id).certifies)

        val uncounted = scheduler.runCheck(accept(), 1) { passed(counts = null) }
        assertEquals(Outcome.Inconclusive, uncounted.outcome)
        assertTrue(uncounted.limits.any { it.kind == "evidence" }, uncounted.limits.toString())
        assertEquals(Outcome.Inconclusive, accept().last!!.outcome)
    }

    @Test
    fun `inputs written during the check, even when restored, make the receipt ineligible while the outcome stands (IX-04)`() = runTest {
        val mutated = scheduler.runCheck(accept(), 1) {
            repo.write("src/a.py", "def a():\n    return 99\n")
            passed()
        }
        assertEquals(Outcome.Passed, mutated.outcome, "the factual invocation outcome is kept")
        assertEquals(setOf("src/a.py"), mutated.testedInputs.mutatedDuringCheck)
        assertFalse(mutated.testedInputs.eligible)
        assertFalse(mutated.greenForFinalTree)
        assertTrue(mutated.limits.any { it.kind == "input_mutation" })
        val currency = scheduler.currency(accept(), stamper.stamp().id)
        assertFalse(currency.certifies)
        assertTrue(currency.reasons.any { it.contains("inputs moved during the check: src/a.py") }, currency.reasons.toString())
        assertEquals(registry.version("src/a.py"), registry.recorded("src/a.py"), "the write was announced like any run")

        val original = Files.readAllBytes(repo.resolve("tests/test_a.py"))
        val restored = scheduler.runCheck(accept(), 1) {
            repo.write("tests/test_a.py", "def test_a():\n    assert False\n")
            repo.write("tests/test_a.py", original)
            passed()
        }
        assertEquals(Outcome.Passed, restored.outcome)
        assertEquals(setOf("tests/test_a.py"), restored.testedInputs.mutatedDuringCheck, "a restore-after-write moves the metadata even though the bytes match")
        assertFalse(restored.testedInputs.eligible)
    }

    @Test
    fun `an unknown closure is unknown stability unless the caller enumerates the inputs, and scratch writes never invalidate`() = runTest {
        val full = checks["CHK-full"]!!
        val blind = scheduler.runCheck(full, 1) { passed() }
        assertEquals(InputStability.Unknown, blind.testedInputs.stability)
        assertFalse(blind.testedInputs.eligible)
        assertTrue(blind.limits.any { it.kind == "input_stability" })
        assertFalse(scheduler.currency(full, stamper.stamp().id).certifies)
        assertTrue(scheduler.currency(full, stamper.stamp().id).reasons.any { it.contains("input stability unknown") })

        val enumerated = scheduler.runCheck(full, 1, inputs = listOf("src/a.py", "src/pkg/b.py", "src/pkg/c.py", "tests/test_a.py", ".pytest_cache/v/cache")) {
            repo.write(".pytest_cache/v/cache", "cache\n")
            repo.write("build/report.xml", "<testsuite/>")
            passed()
        }
        assertEquals(InputStability.Exclusive, enumerated.testedInputs.stability)
        assertEquals(setOf("src/a.py", "src/pkg/b.py", "src/pkg/c.py", "tests/test_a.py"), enumerated.testedInputs.versions.keys, "scratch paths are not inputs")
        assertTrue(enumerated.testedInputs.eligible, "cache and report writes are declared scratch")
        assertTrue(enumerated.stampBefore != enumerated.stampAfter, "the untracked scratch still moved the stamp")
        assertTrue(scheduler.currency(full, stamper.stamp().id).certifies, "current at the stamp that includes the scratch")
    }

    @Test
    fun `a package closure enumerates its files`() {
        assertEquals(listOf("src/pkg/b.py", "src/pkg/c.py"), scheduler.testedInputsFor(checks["CHK-pkg"]!!, emptyList()))
        assertEquals(listOf("src/a.py", "tests/test_a.py"), scheduler.testedInputsFor(accept(), listOf("ignored/for/known")))
        assertTrue(ScratchPolicy().isScratch("src/__pycache__/a.cpython-314.pyc"))
        assertFalse(ScratchPolicy().isScratch("src/builder.py"))
    }
}
