package io.astrolabe.verify

import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
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
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.store.Store
import io.astrolabe.tool.run.TestIdentity
import io.astrolabe.tool.run.TestOutcome
import io.astrolabe.tool.run.TestResult
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.7.5 baseline receipt on the captured initial candidate (D-53), pre-existing ledger by identity + signature + environment (IX-13). */
class BaselineTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var os: LocalOs
    private lateinit var shadowRef: ShadowRef
    private lateinit var s0: io.astrolabe.id.CandidateId
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val env = EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1"))
    private val windows = ChildCommands.isWindows
    private val recorded: String = javaClass.getResourceAsStream("/shaper/pytest-fail-param.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("tests/test_discount.py", "def test_tier():\n    assert discount(cart, tier) == 5\n")
        repo.write("pytest_output.txt", recorded)
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
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

    private fun baseline() = Baseline(shadowRef, store.layout, TrustedLocalRunner(os), os, SqliteReceipts(store, clock), InMemoryAliases(), store.blobs, Redaction(), HeuristicEstimator(), FixedIdGen(), ids, clock, env)

    private fun suite(windowsLine: String, posixLine: String) = Check(
        "CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd,
        command = Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", windowsLine) else listOf("/bin/sh", "-c", posixLine)),
    )

    private fun failing(name: String = "test_tier", file: String = "tests/test_discount.py", suite: String? = "TestDiscount", parameterization: String? = "3", message: String = "AssertionError: assert 4 == 5\n +  where 4 = discount(...)") =
        TestResult(TestIdentity(check = "CHK-full", file = file, suite = suite, name = name, parameterization = parameterization), TestOutcome.Failed, message)

    @Test
    fun `the baseline runs on the captured initial candidate even after edits and its ledger matches identity and signature, not counts or names (IX-13)`() = runTest {
        // Edits after the capture: the working tree now shows a passing run, the captured tree still fails.
        repo.write("pytest_output.txt", recorded.replace("tests/test_discount.py .F.", "tests/test_discount.py ...").replace("5 passed, 1 failed, 1 skipped", "7 passed"))
        repo.write("tests/test_discount.py", "def test_tier():\n    assert True\n")

        val result = baseline().run(suite("type pytest_output.txt&exit /b 1", "cat pytest_output.txt; exit 1"), contractVersion = 1, s0 = s0)
        assertTrue(result.materialized.ok, result.materialized.mismatches.toString())
        assertEquals(recorded, Files.readString(result.candidateDir.resolve("pytest_output.txt")), "the exported candidate is s0, not the edited tree")
        val receipt = result.receipt
        assertEquals(Outcome.Failed, receipt.outcome)
        assertEquals(Counts(passed = 5, failed = 1, skipped = 1, discovered = 7), receipt.parsed)
        assertEquals(s0, receipt.stampBefore)
        assertEquals(s0, receipt.stampAfter)
        assertEquals(InputStability.Isolated, receipt.testedInputs.stability)
        assertTrue(receipt.testedInputs.eligible)
        assertEquals(FileVersion.of("def test_tier():\n    assert discount(cart, tier) == 5\n".toByteArray()), receipt.testedInputs.versions["tests/test_discount.py"], "tested inputs are the manifest's bytes")
        assertEquals(env.envId, receipt.envId)
        assertEquals(1, receipt.exitCode)
        assertTrue(Files.exists(store.blobs.path(receipt.raw!!)))
        assertEquals(receipt, SqliteReceipts(store, clock).get(receipt.receiptId))

        val ledger = assertNotNull(result.ledger)
        assertEquals("#1", ledger.alias)
        val entry = ledger.entries.single()
        assertEquals("tests/test_discount.py::TestDiscount::test_tier[3]", entry.identity.display)
        assertEquals("AssertionError: assert 4 == 5", entry.signature)
        assertEquals(1, entry.multiplicity)
        assertTrue(ledger.render().startsWith("── Pre-existing failures (baseline #1 @${s0.hash8.take(4)}, 1) ──\n  tests/test_discount.py::TestDiscount::test_tier[3]: AssertionError: assert 4 == 5"), ledger.render())

        assertEquals(BaselineMatch.PreExisting, ledger.classify(failing()))
        assertEquals(BaselineMatch.New, ledger.classify(failing(file = "other/test_discount.py")), "same name in another module is a different test")
        assertEquals(BaselineMatch.New, ledger.classify(failing(parameterization = "5")), "another parameterized instance is a different test")
        assertEquals(BaselineMatch.Changed("AssertionError: assert 4 == 5"), ledger.classify(failing(message = "AssertionError: assert 3 == 5")))
        assertIs<BaselineMatch.Ambiguous>(ledger.classify(failing(), envId = Digest.ofUtf8("other-env")), "an incomparable environment never matches")
        assertEquals(BaselineMatch.New, ledger.classify(failing(name = "test_other")))
    }

    @Test
    fun `duplicate identities never match, signatures normalize volatile data, and no identities means nothing pre-existing`() {
        val duplicate = PreexistingLedger("rcpt-x", "#9", s0, env.envId, listOf(PreexistingFailure(failing().identity, "AssertionError", 2)), ambiguous = setOf(failing().identity.canonical))
        assertIs<BaselineMatch.Ambiguous>(duplicate.classify(failing()))
        assertTrue(duplicate.render().contains("(×2)"))
        assertEquals("Timeout at 0x… after <time> in <tmp>", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Error, "Timeout at 0x7f3a1c2b4d10 after 2026-09-20T10:00:00Z in /tmp/pytest-of-dev/run1\nsecond line")))
        assertEquals("src/pay/total.py:41 AssertionError E1234", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Failed, "src/pay/total.py:41 AssertionError E1234")), "paths, error codes and literals stay")
        assertEquals("failed", PreexistingLedger.signatureOf(TestResult(failing().identity, TestOutcome.Failed, null)))
        val empty = PreexistingLedger("rcpt-y", null, s0, env.envId, emptyList(), emptySet(), listOf("no test identities parsed by generic: nothing can be called pre-existing"))
        assertEquals(BaselineMatch.New, empty.classify(failing()))
        assertTrue(empty.render().endsWith("none · no test identities parsed by generic: nothing can be called pre-existing"), empty.render())
    }

    @Test
    fun `a suite that rewrites its inputs in the candidate cannot certify them, and a timed-out baseline yields no ledger`() = runTest {
        val shared = baseline()
        val mutating = shared.run(
            suite("type pytest_output.txt&echo changed> tests\\test_discount.py&exit /b 1", "cat pytest_output.txt; echo changed > tests/test_discount.py; exit 1"),
            contractVersion = 1, s0 = s0,
        )
        assertEquals(Outcome.Failed, mutating.receipt.outcome, "the factual outcome stands")
        assertEquals(setOf("tests/test_discount.py"), mutating.receipt.testedInputs.mutatedDuringCheck)
        assertFalse(mutating.receipt.testedInputs.eligible)
        assertTrue(mutating.receipt.limits.any { it.kind == "input_mutation" })
        assertEquals("def test_tier():\n    assert discount(cart, tier) == 5\n", Files.readString(repo.resolve("tests/test_discount.py")), "the working tree is untouched by the candidate run")
        assertNotNull(mutating.ledger)

        val slow = shared.run(suite("ping -n 61 127.0.0.1 >NUL", "sleep 60"), contractVersion = 1, s0 = s0, timeoutSeconds = 1)
        assertEquals(Outcome.Timeout, slow.receipt.outcome)
        assertNull(slow.ledger, "nothing may be called pre-existing without a usable baseline receipt")

        val missing = shared.run(Check("CHK-full", CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, command = Command(listOf("no-such-runner-xyz"))), 1, s0)
        assertEquals(Outcome.Unavailable, missing.receipt.outcome)
        assertNull(missing.ledger)
    }
}
