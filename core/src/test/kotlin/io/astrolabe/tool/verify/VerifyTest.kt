package io.astrolabe.tool.verify

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Origin
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.Coherence
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
import io.astrolabe.os.ChildCommands
import io.astrolabe.os.LocalOs
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.Checker
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.RunnerCommands
import io.astrolabe.verify.Scheduler
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.EnvFingerprint
import io.astrolabe.workspace.EnvInputs
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.Workspace
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.6.7 `verify`: every executed check yields a receipt (an explicit `unavailable` one when the runner cannot start, FX-13), rendered as the Checks block. */
class VerifyTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var workspace: Workspace
    private lateinit var registry: VersionRegistry
    private lateinit var coherence: Coherence
    private lateinit var os: LocalOs
    private lateinit var stamper: Stamper
    private lateinit var checks: Checks
    private lateinit var scheduler: Scheduler
    private lateinit var contracts: Contracts
    private lateinit var verify: Verify
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val idGen = FixedIdGen()
    private val windows = ChildCommands.isWindows
    private fun recorded(name: String) = javaClass.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun printing(file: String, exit: Int) =
        Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "type $file&exit /b $exit") else listOf("/bin/sh", "-c", "cat $file; exit $exit"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        repo.write("pytest_fail.txt", recorded("pytest-fail-param.txt"))
        repo.write("diag.txt", "src/a.py:1:5: error: bad\nsrc/a.py:2:1: error: worse\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
        registry = VersionRegistry(workspace)
        coherence = Coherence(registry).also { it.register(Workset()) }
        os = LocalOs(clock)
        stamper = Stamper(workspace, EnvFingerprint.compute(EnvInputs(osName = "test-os", osArch = "test-arch", runnerPolicyId = "trusted-local/v1")))
        contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        val derived = contracts.deriveS0(ids.work, ids.attempt, "fix a", Atlas.build(repo.root), Config(), Tokens(10_000)).contract
        val contract = derived.copy(
            acceptance = listOf(
                Acceptance.Run("AC-1", printing("pytest_pass.txt", 0), Origin.Harness, scope = "touched"),
                Acceptance.Run("AC-2", printing("pytest_fail.txt", 1), Origin.User),
                Acceptance.Run("AC-3", Command(listOf("no-such-runner-xyz", "-q")), Origin.User),
            ),
            requirements = derived.requirements.map { it.copy(acceptance = listOf("AC-1", "AC-2", "AC-3")) },
        )
        contracts.open(contract)
        checks = Checks.seed(contract, RunnerCommands(test = printing("pytest_pass.txt", 0)))
        checks.register(Check("CHK-types-touched", CheckKind.Type, Selector.All, Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.EndOfTurn, command = printing("diag.txt", 1)))
        coherence.register(checks)
        scheduler = Scheduler(checks, workspace, registry, stamper, SqliteReceipts(store, clock), InMemoryAliases(), idGen, ids, clock)
        val checker = Checker(checks, TrustedLocalRunner(os), os, stamper, registry, workspace, store.blobs, Redaction(), idGen, ids, stateRoot.resolve("logs"))
        verify = Verify(checks, scheduler, checker, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        verify.inputs = listOf("src/a.py", "pytest_pass.txt", "pytest_fail.txt", "diag.txt")
    }

    @AfterTest
    fun tearDown() {
        coherence.close()
        os.close()
        store.close()
        repo.close()
    }

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "verify", json))) as ParsedCalls.Valid).calls.single()

    private suspend fun run(json: String): ToolOutcome = verify.execute(call(json), TurnContext(1, Workset().snapshot(), Reservations(Tokens(10_000))))

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `acceptance runs record receipts with stamps and currency, rendered as the Checks block`() = runTest {
        val out = run("""{"what":"acceptance","ids":["AC-1"]}""")
        assertEquals("passed", status(out), out.body)
        assertTrue(out.green, out.body)
        assertTrue(out.body.startsWith("── Checks @"), out.body)
        assertTrue(out.body.contains("accept AC-1: ✓"), out.body)
        assertTrue(out.body.contains("(#1)"), out.body)
        val receipt = SqliteReceipts(store, clock).forCheck("CHK-accept-AC-1").single()
        assertEquals(Outcome.Passed, receipt.outcome)
        assertEquals(InputStability.Exclusive, receipt.testedInputs.stability)
        assertTrue(receipt.testedInputs.versions.containsKey("src/a.py"))
        assertEquals(receipt.receiptId, checks["CHK-accept-AC-1"]!!.last!!.receiptId)
        assertTrue(scheduler.currency(checks["CHK-accept-AC-1"]!!, stamper.stamp().id).certifies)
        assertEquals("verify", out.header!!.tool)
        assertEquals(listOf(receipt.raw!!.hex), out.header!!.runtime.artifactRefs)
    }

    @Test
    fun `verify-on-stop runs only missing or stale checks and a second proposal after an unrelated edit reuses the receipt`() = runTest {
        val known = Checks.empty()
        known.register(Check("CHK-accept-AC-1", CheckKind.Acceptance, Selector.Named(printing("pytest_pass.txt", 0)), Closure.Known(setOf("src/a.py", "pytest_pass.txt")), CostClass.Slow, Trigger.IncrementEnd, acceptanceIds = listOf("AC-1"), command = printing("pytest_pass.txt", 0)))
        known.register(Check(Checks.FULL, CheckKind.Full, Selector.All, Closure.Unknown, CostClass.Expensive, Trigger.CampaignEnd, acceptanceIds = listOf("AC-1"), command = printing("pytest_pass.txt", 0)))
        coherence.register(known)
        val receipts = SqliteReceipts(store, clock)
        val stopScheduler = Scheduler(known, workspace, registry, stamper, receipts, InMemoryAliases(), idGen, ids, clock)
        val stop = Verify(known, stopScheduler, null, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))

        val first = stop.onStop(listOf("AC-1"))
        assertEquals(listOf("CHK-accept-AC-1"), first.map { it.checkId }, "the missing check runs, the full suite never does")
        assertEquals(Outcome.Passed, first.single().outcome)

        repo.write("diag.txt", "unrelated\n")
        assertEquals(emptyList(), stop.onStop(listOf("AC-1")), "the unchanged complete closure is reused, not rerun")
        val currency = stopScheduler.currency(known["CHK-accept-AC-1"]!!, stamper.stamp().id)
        assertTrue(currency.certifies, currency.reasons.toString())
        assertEquals(first.single().receiptId, known["CHK-accept-AC-1"]!!.last!!.reuseProof!!.reuseOf)

        repo.write("src/a.py", "def a():\n    return 2\n")
        assertEquals(listOf("CHK-accept-AC-1"), stop.onStop(listOf("AC-1")).map { it.checkId }, "a moved closure path reruns the check")
        assertEquals(2, receipts.forCheck("CHK-accept-AC-1").size)
        assertEquals(emptyList(), receipts.forCheck(Checks.FULL))
    }

    @Test
    fun `a failed suite is red with parsed counts and a missing runner is an explicit unavailable receipt (FX-13 partial)`() = runTest {
        val failed = run("""{"what":"tests","selection":"ids","ids":["CHK-accept-AC-2"]}""")
        assertEquals("failed", status(failed), failed.body)
        assertFalse(failed.green)
        assertTrue(failed.body.contains("accept AC-2: now 1 5 pass 1 fail 1 skip @"), failed.body)
        assertEquals(Outcome.Failed, SqliteReceipts(store, clock).forCheck("CHK-accept-AC-2").single().outcome)

        val missing = run("""{"what":"acceptance","ids":["AC-3"]}""")
        assertEquals("unavailable", status(missing), missing.body)
        assertFalse(missing.green)
        assertTrue(missing.body.contains("accept AC-3: unavailable (cannot start no-such-runner-xyz"), missing.body)
        val receipt = SqliteReceipts(store, clock).forCheck("CHK-accept-AC-3").single()
        assertEquals(Outcome.Unavailable, receipt.outcome)
        assertEquals(listOf("no-such-runner-xyz", "-q"), receipt.command)
        assertFalse(scheduler.currency(checks["CHK-accept-AC-3"]!!, stamper.stamp().id).certifies)

        val all = run("""{"what":"tests","selection":"accept"}""")
        assertEquals(3, Regex("accept AC-\\d").findAll(all.body.substringBefore("\n  CHK-")).count(), "every selected check has a line in the Checks block: ${all.body}")
        assertEquals("unavailable", status(all), "the worst outcome names the status, and a runner that cannot start outranks a red suite: ${all.body}")
    }

    @Test
    fun `check runs the checker now and records its results as receipts of unknown stability`() = runTest {
        val out = run("""{"what":"check","paths":["src/a.py"]}""")
        assertEquals("ok", status(out), out.body)
        assertTrue(out.body.contains("types: now 2 @"), out.body)
        assertTrue(out.body.contains("CHK-types-touched: src/a.py:1:5: error: bad · src/a.py:2:1: error: worse"), out.body)
        val receipt = SqliteReceipts(store, clock).forCheck("CHK-types-touched").single()
        assertEquals(Outcome.Failed, receipt.outcome)
        assertEquals(2, receipt.parsed!!.errors)
        assertEquals(InputStability.Unknown, receipt.testedInputs.stability)
        assertEquals(receipt.receiptId, checks["CHK-types-touched"]!!.last!!.receiptId)
        assertFalse(out.green)
        verify.touched = emptyList()
        assertEquals("ok", status(run("""{"what":"check"}""")))
        assertTrue(run("""{"what":"check"}""").body.startsWith("nothing touched"))
    }

    @Test
    fun `unsupported selections, unknown ids, masked ops and a missing baseline are explicit`() = runTest {
        val blast = run("""{"what":"tests","selection":"blast"}""")
        assertEquals("unavailable", status(blast))
        assertTrue(blast.body.contains("P3.2.5"), blast.body)
        assertEquals("denied", status(run("""{"what":"tests","selection":"ids","ids":["CHK-nope"]}""")))
        assertEquals("denied", status(run("""{"what":"acceptance","ids":["AC-9"]}""")))
        assertEquals("masked", status(run("""{"what":"review"}""")))
        assertEquals("unavailable", status(run("""{"what":"baseline"}""")))
        assertTrue(SqliteReceipts(store, clock).forCheck("CHK-full").isEmpty(), "refusals record no receipt")
    }
}
