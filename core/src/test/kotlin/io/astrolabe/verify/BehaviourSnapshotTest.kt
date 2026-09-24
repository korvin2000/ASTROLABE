package io.astrolabe.verify

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Redaction
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.InMemoryAliases
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Outcome
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.store.Store
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.workspace.DirtyState
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3.5.1: the behaviour snapshot records the affected suites' baseline receipts and the characterization outputs at `s0` (§8.9 item 1). */
class BehaviourSnapshotTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var os: LocalOs
    private lateinit var shadowRef: ShadowRef
    private lateinit var s0: CandidateId
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val idGen = FixedIdGen()
    private val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))
    private val windows = ChildCommands.isWindows
    private val recorded: String = javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private val contract = Contract(
        ids.work, 1, ids.attempt, Mode.Autonomous, Shape.S1, listOf(UserRequest("U1", Instant.EPOCH, "refactor the discount rules")),
        listOf(Requirement("R1", "refactor the discount rules", emptyList(), authorityRef = "U1")),
        emptyList(), emptyList(), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/discount.py", "def discount(cart, tier):\n    return 4\n")
        repo.write("tests/golden/cli.txt", "total: 4\n")
        repo.write("tests/__snapshots__/api.snap", "{\"total\": 4}\n")
        repo.write("tests/fixtures/api/cart.json", "{\"items\": 1}\n")
        repo.write("tests/expected.golden", "4\n")
        repo.write("tests/test_discount.py", "def test_tier():\n    assert discount(cart, tier) == 5\n")
        repo.write("pytest_output.txt", recorded)
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        os = LocalOs(clock)
        val stamper = Stamper(workspace, env)
        val dirtyState = DirtyState(workspace, store.blobs, stamper, ids, clock)
        shadowRef = ShadowRef(ids.work, ids.attempt, workspace, store, dirtyState, os, clock)
        shadowRef.open(dirtyState.capture(0))
        s0 = stamper.stamp().id
    }

    @AfterTest
    fun tearDown() {
        os.close()
        store.close()
        repo.close()
    }

    private fun snapshots(withBaseline: Boolean = true): BehaviourSnapshots {
        val baseline = if (withBaseline) Baseline(shadowRef, store.layout, TrustedLocalRunner(os), os, SqliteReceipts(store, clock), InMemoryAliases(), store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, clock, env) else null
        return BehaviourSnapshots(baseline, shadowRef, store.layout, store.blobs, store, Journal(store, clock), ids, idGen, clock)
    }

    private fun checks(): Checks = Checks.empty().also {
        it.register(
            Check(
                Checks.FULL, CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd,
                command = Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "type pytest_output.txt&exit /b 1") else listOf("/bin/sh", "-c", "cat pytest_output.txt; exit 1")),
            ),
        )
    }

    @Test
    fun `the snapshot holds the baseline receipt of the full suite and the characterization outputs as they were at s0`() = runTest {
        // Edits after the capture: the snapshot must come from the captured candidate, not from the live tree.
        repo.write("tests/golden/cli.txt", "total: 5\n")
        repo.write("tests/__snapshots__/api.snap", "{\"total\": 5}\n")

        val snapshots = snapshots()
        assertNull(snapshots.latest())
        val snapshot = snapshots.capture(contract, checks(), s0)

        val suite = snapshot.suites.single()
        assertEquals(Checks.FULL, suite.checkId)
        assertEquals(Outcome.Failed, suite.outcome)
        val receipt = assertNotNull(SqliteReceipts(store, clock).get(suite.receiptId))
        assertEquals(s0, receipt.stampAfter)
        assertEquals(listOf("tests/__snapshots__/api.snap", "tests/expected.golden", "tests/fixtures/api/cart.json", "tests/golden/cli.txt"), snapshot.characterization.map { it.path })
        val golden = snapshot.characterization.first { it.path == "tests/golden/cli.txt" }
        assertEquals("total: 4\n", Files.readString(store.blobs.path(golden.blob)), "the blob holds the s0 bytes, not the edited ones")
        assertEquals(9L, golden.sizeBytes)
        assertEquals(snapshot, snapshots.latest(), "recorded durably for the equivalence comparison")
        val boundary = Journal(store, clock).events(JournalScope(ids.work, kinds = setOf(JournalKind.Boundary))).single()
        assertTrue(boundary.text.startsWith("behaviour snapshot @${s0.hash8}: CHK-full failed (${suite.receiptId}) · 4 characterization outputs"), boundary.text)
        assertTrue(boundary.refs.contains(suite.receiptId) && boundary.refs.contains(golden.blob.hex))
    }

    @Test
    fun `without a baseline runner the outputs are still recorded at s0 and the missing suite is a limitation`() = runTest {
        val snapshot = snapshots(withBaseline = false).capture(contract, checks(), s0)
        assertTrue(snapshot.suites.isEmpty())
        assertEquals(4, snapshot.characterization.size)
        assertEquals("no baseline runner: the affected suites (CHK-full) did not run", snapshot.limitations.first(), snapshot.limitations.toString())
        assertFalse(BehaviourSnapshots.isCharacterization("src/discount.py"))
        assertTrue(BehaviourSnapshots.isCharacterization("cli/goldens/help.txt"))
    }
}
