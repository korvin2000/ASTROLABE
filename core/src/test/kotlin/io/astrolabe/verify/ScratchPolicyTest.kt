package io.astrolabe.verify

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Counts
import io.astrolabe.evidence.InMemoryAliases
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
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The output policy at the scheduler (W3, §8.4, D-45): untracked output under an anchored root is outside the candidate
 * and outside a check's enumerated inputs, but a path a known closure declares or a tracked one stays an input (Codex
 * P1-1 to P1-4, P2-1 of the W3 specification review).
 */
class ScratchPolicyTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var stamper: Stamper
    private lateinit var checks: Checks
    private lateinit var scheduler: Scheduler
    private val clock = FakeClock.at("2026-10-05T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()
    private val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        // No host excludes: what is untracked is decided by this repository alone.
        val exclude = repo.root.resolve(".git/info/exclude")
        Files.createDirectories(exclude.parent)
        Files.write(exclude, ByteArray(0))
        repo.config("core.excludesFile", exclude.toString().replace('\\', '/'))
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("build/keep.json", "[1]\n")
        repo.commit("initial")
        repo.write("build/generated.json", "{\"v\": 1}\n")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        stamper = Stamper(workspace, env, scratch = ScratchPolicy.BUILT_IN)
        checks = Checks.empty()
        checks.register(Check("CHK-gen", CheckKind.Acceptance, Selector.Named(Command(listOf("pytest"))), Closure.Known(setOf("src/a.py", "build/generated.json")), CostClass.Fast, Trigger.IncrementEnd, acceptanceIds = listOf("AC-1"), command = Command(listOf("pytest"))))
        checks.register(Check("CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Fast, Trigger.CampaignEnd, command = Command(listOf("pytest"))))
        scheduler = scheduler(stamper)
    }

    @AfterTest
    fun tearDown() {
        store.close()
        repo.close()
    }

    private fun scheduler(stamper: Stamper) =
        Scheduler(checks, workspace, VersionRegistry(workspace), stamper, SqliteReceipts(store, clock), InMemoryAliases(), idGen, ids, clock)

    private fun executed(outcome: Outcome = Outcome.Passed) = Executed(
        listOf("pytest"), null, false, if (outcome == Outcome.Passed) 0 else 1, outcome,
        if (outcome == Outcome.Passed) Counts(passed = 1, discovered = 1) else Counts(failed = 1, discovered = 1),
        store.blobs.put("log\n".toByteArray(), BlobKind.LOG, ids),
    )

    private fun write(path: String, text: String) = Files.writeString(repo.resolve(path).also { Files.createDirectories(it.parent) }, text)

    @Test
    fun `output a check writes under a root leaves the candidate and its receipt certifying, rewritten or new`() = runTest {
        val full = checks["CHK-full"]!!
        repeat(2) { run ->
            val receipt = scheduler.runCheck(full, 1, inputs = listOf("src/a.py")) { executed().also { write("build/report.txt", "run $run\n") } }
            assertEquals(receipt.stampBefore, receipt.stampAfter, "run $run moved the candidate")
            assertTrue(receipt.testedInputs.eligible, receipt.limits.toString())
            assertEquals(ScratchPolicy.BUILT_IN.id, receipt.inputPolicy)
        }
        assertTrue(scheduler.currency(full, stamper.stamp(fresh = true).id).certifies)
    }

    @Test
    fun `a declared input under an output root is pinned, and its later change makes the evidence ineligible though the candidate stands`() = runTest {
        val gen = checks["CHK-gen"]!!
        val receipt = scheduler.runCheck(gen, 1) { executed() }
        assertTrue("build/generated.json" in receipt.testedInputs.versions, "P1-2: the declared closure path is pinned: ${receipt.testedInputs.versions.keys}")
        val stamp = stamper.stamp(fresh = true).id
        assertTrue(scheduler.currency(gen, stamp).certifies)

        write("build/generated.json", "{\"v\": 2}\n")
        assertEquals(stamp, stamper.stamp(fresh = true).id, "the output is outside the candidate")
        val moved = scheduler.currency(gen, stamp)
        assertFalse(moved.certifies)
        assertTrue(moved.reasons.any { "inputs outside the candidate moved since the check: build/generated.json" in it }, moved.reasons.toString())
    }

    @Test
    fun `a check that rewrites a tracked file under a root moves the candidate, names the input, and a red stays red`() = runTest {
        val full = checks["CHK-full"]!!
        val rewrote = scheduler.runCheck(full, 1, inputs = listOf("src/a.py")) { executed().also { write("build/keep.json", "[2]\n") } }
        assertNotEquals(rewrote.stampBefore, rewrote.stampAfter, "P1-1: a tracked file under a root is in identity")
        assertEquals(setOf("build/keep.json"), rewrote.testedInputs.mutatedDuringCheck, "P1-3: and an input of the check")
        val green = scheduler.currency(full, stamper.stamp(fresh = true).id)
        assertEquals(listOf("build/keep.json"), green.rewrittenInputs)
        val unverified = Obligations.run(Checks.FULL, "full suite", green)
        assertEquals(ResultStatus.Unverified to listOf("build/keep.json"), unverified.status to unverified.rewrittenInputs)

        scheduler.runCheck(full, 1, inputs = listOf("src/a.py")) { executed(Outcome.Failed).also { write("build/keep.json", "[3]\n") } }
        val red = scheduler.currency(full, stamper.stamp(fresh = true).id)
        assertFalse(red.eligible)
        assertEquals(ResultStatus.Failed, Obligations.run(Checks.FULL, "full suite", red).status, "P1-4: an input mutation never turns a red into a gap")
    }

    @Test
    fun `a receipt recorded under another output policy is stale under the attempt's`() = runTest {
        val gen = checks["CHK-gen"]!!
        val none = Stamper(workspace, env)
        val legacy = scheduler(none).runCheck(gen, 1) { executed() }
        assertEquals(null, legacy.inputPolicy)
        val now = scheduler.currency(gen, stamper.stamp(fresh = true).id)
        assertEquals(Applicability.Stale, now.applicability)
        assertTrue(now.reasons.any { "recorded under output policy none" in it }, now.reasons.toString())
    }
}
