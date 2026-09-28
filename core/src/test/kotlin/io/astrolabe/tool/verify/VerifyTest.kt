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
    fun `JVM verification captures new reports and cannot reuse stale success`() = runTest {
        repo.write(".gitignore", "build/\ntarget/\n")
        var writeReport = true
        val runner = object : io.astrolabe.tool.run.Runner {
            override val mode = io.astrolabe.auth.ExecutionMode.TrustedLocal
            override fun start(spec: io.astrolabe.os.SpawnSpec): io.astrolabe.os.Proc {
                if (writeReport) {
                    val file = spec.workingDirectory.resolve("build/test-results/test/TEST-demo.xml")
                    java.nio.file.Files.createDirectories(file.parent)
                    java.nio.file.Files.writeString(file, """<testsuite tests="1"><testcase classname="Demo" name="works"/></testsuite>""")
                }
                return os.spawn(spec.copy(command = io.astrolabe.os.Command.Argv(if (windows) listOf("cmd.exe", "/d", "/c", "exit 0") else listOf("/bin/sh", "-c", "exit 0"))))
            }
        }
        checks.register(Check("CHK-jvm", CheckKind.Full, Selector.All, Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, command = Command(listOf("gradle", "test"))))
        verify = Verify(checks, scheduler, null, null, null, workspace, runner, os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val first = run("""{"what":"tests","selection":"ids","ids":["CHK-jvm"]}""")
        assertTrue(first.green, first.body)
        writeReport = false
        val stale = run("""{"what":"tests","selection":"ids","ids":["CHK-jvm"]}""")
        assertFalse(stale.green, stale.body)
        assertTrue(stale.body.contains("no fresh JUnit XML"), stale.body)
    }

    @Test
    fun `model acceptance commands and escaping working directories are denied before launch`() = runTest {
        var launches = 0
        val recording = object : io.astrolabe.tool.run.Runner {
            override val mode = io.astrolabe.auth.ExecutionMode.TrustedLocal
            override fun start(spec: io.astrolabe.os.SpawnSpec): io.astrolabe.os.Proc {
                launches++
                throw java.io.IOException("unexpected launch")
            }
        }
        verify = Verify(checks, scheduler, null, null, null, workspace, recording, os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val command = Command(listOf("git", "push", "origin", "main"))
        contracts.strengthen(ids.work, Acceptance.Run("AC-model", command, Origin.Model("R1")))
        checks.register(Check("CHK-model", CheckKind.Acceptance, Selector.Named(command), Closure.Unknown, CostClass.Fast, Trigger.OnDemand, acceptanceIds = listOf("AC-model"), command = command))
        val model = run("""{"what":"acceptance","ids":["AC-model"]}""")
        assertEquals("denied", status(model))
        for (cwd in listOf("../", stateRoot.toString().replace('\\', '/'))) {
            checks.replace(Check("CHK-outside", CheckKind.Unit, Selector.All, Closure.Unknown, CostClass.Fast, Trigger.OnDemand, command = printing("pytest_pass.txt", 0).copy(cwd = cwd)))
            assertEquals("denied", status(run("""{"what":"tests","selection":"ids","ids":["CHK-outside"]}""")))
        }
        assertEquals(0, launches)
    }

    @Test
    fun `model acceptance can reuse an exact command already authorized by the contract`() = runTest {
        val command = printing("pytest_pass.txt", 0)
        contracts.strengthen(ids.work, Acceptance.Run("AC-model", command, Origin.Model("R1")))
        checks.register(Check("CHK-model", CheckKind.Acceptance, Selector.Named(command), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, acceptanceIds = listOf("AC-model"), command = command))
        val out = run("""{"what":"acceptance","ids":["AC-model"]}""")
        assertEquals("passed", status(out), out.body)
        assertTrue(out.green)
    }

    @Test
    fun `a passing batch cannot certify an earlier check invalidated by a later check`() = runTest {
        val first = Check("CHK-first", CheckKind.Unit, Selector.All, Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, command = printing("pytest_pass.txt", 0))
        val change = Command(if (windows) listOf("cmd.exe", "/d", "/s", "/c", "echo changed>src/a.py&type pytest_pass.txt") else listOf("/bin/sh", "-c", "echo changed > src/a.py; cat pytest_pass.txt"))
        checks.register(first)
        checks.register(first.copy(id = "CHK-second", inputClosure = Closure.Known(setOf("pytest_pass.txt")), command = change))
        val out = run("""{"what":"tests","selection":"ids","ids":["CHK-first","CHK-second"]}""")
        assertFalse(out.green, out.body)
        assertTrue(out.body.contains("stale"), out.body)
    }

    @Test
    fun `a requested acceptance without a registered check cannot disappear from a green result`() = runTest {
        contracts.amendByUser(ids.work, "add acceptance") { contract ->
            contract.copy(acceptance = contract.acceptance + Acceptance.Run("AC-4", printing("pytest_pass.txt", 0), Origin.User))
        }
        val out = run("""{"what":"acceptance","ids":["AC-1","AC-4"]}""")
        assertFalse(out.green, out.body)
        assertEquals("unavailable", status(out))
        assertTrue(out.body.contains("AC-4"), out.body)
        val selected = run("""{"what":"tests","selection":"accept"}""")
        assertFalse(selected.green, selected.body)
        assertEquals("unavailable", status(selected))
        assertTrue(selected.body.contains("AC-4"), selected.body)
    }

    @Test
    fun `verification output redacts fixture secrets`() = runTest {
        val secret = "AKIA" + "IOSFODNN7EXAMPLE"
        repo.write("pytest_fail.txt", recorded("pytest-fail-param.txt") + "\nE   $secret\n")
        val out = run("""{"what":"acceptance","ids":["AC-2"]}""")
        assertFalse(out.body.contains(secret))
        assertTrue(out.header!!.runtime.redactionApplied)
        out.header!!.runtime.artifactRefs.forEach {
            assertFalse(String(store.blobs.get(io.astrolabe.id.Digest(it))).contains(secret))
        }
    }

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

        val first = stop.onStop(listOf("AC-1")).receipts
        assertEquals(listOf("CHK-accept-AC-1"), first.map { it.checkId }, "the missing check runs, the full suite never does")
        assertEquals(Outcome.Passed, first.single().outcome)

        repo.write("diag.txt", "unrelated\n")
        assertEquals(emptyList(), stop.onStop(listOf("AC-1")).receipts, "the unchanged complete closure is reused, not rerun")
        val currency = stopScheduler.currency(known["CHK-accept-AC-1"]!!, stamper.stamp().id)
        assertTrue(currency.certifies, currency.reasons.toString())
        assertEquals(first.single().receiptId, known["CHK-accept-AC-1"]!!.last!!.reuseProof!!.reuseOf)

        repo.write("src/a.py", "def a():\n    return 2\n")
        assertEquals(listOf("CHK-accept-AC-1"), stop.onStop(listOf("AC-1")).receipts.map { it.checkId }, "a moved closure path reruns the check")
        assertEquals(2, receipts.forCheck("CHK-accept-AC-1").size)
        assertEquals(emptyList(), receipts.forCheck(Checks.FULL))
    }

    @Test
    fun `a disagreeing retry uses a disposable copy of the original candidate`() = runTest {
        scheduler = Scheduler(checks, workspace, registry, stamper, SqliteReceipts(store, clock), InMemoryAliases(), idGen, ids, clock, candidates = stateRoot.resolve("candidates"))
        val roots = ArrayList<Path>()
        val scripted = object : io.astrolabe.tool.run.Runner {
            override val mode = io.astrolabe.auth.ExecutionMode.TrustedLocal
            override fun start(spec: io.astrolabe.os.SpawnSpec): io.astrolabe.os.Proc {
                roots.add(spec.workingDirectory)
                val command = if (roots.size == 1) printing("pytest_fail.txt", 1) else printing("pytest_pass.txt", 0)
                return TrustedLocalRunner(os).start(spec.copy(command = io.astrolabe.os.Command.Argv(command.argv)))
            }
        }
        verify = Verify(checks, scheduler, null, null, null, workspace, scripted, os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val command = printing("pytest_fail.txt", 1)
        checks.register(Check("CHK-flaky", CheckKind.Unit, Selector.Named(command), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, command = command))
        val out = run("""{"what":"tests","selection":"ids","ids":["CHK-flaky"]}""")
        val attempts = SqliteReceipts(store, clock).forCheck("CHK-flaky")
        assertEquals("inconclusive", status(out), out.body)
        assertEquals(listOf(Outcome.Failed, Outcome.Passed, Outcome.Inconclusive), attempts.map { it.outcome })
        assertEquals(InputStability.Isolated, attempts[1].testedInputs.stability)
        assertEquals(attempts[0].stampBefore, attempts[1].stampBefore)
        assertEquals(repo.root.toRealPath(), roots[0].toRealPath())
        assertTrue(roots[1].startsWith(stateRoot.resolve("candidates")))
        assertFalse(java.nio.file.Files.exists(roots[1]))
        assertTrue(attempts.last().limits.any { it.kind == "flaky" && it.detail.contains(attempts[0].receiptId) && it.detail.contains(attempts[1].receiptId) })
    }

    @Test
    fun `without candidate isolation a failed slow check still gets its isolated retry from the default export root`() = runTest {
        // The controller's default wiring: no `candidates` (first runs stay exclusive), only the retry export root.
        scheduler = Scheduler(checks, workspace, registry, stamper, SqliteReceipts(store, clock), InMemoryAliases(), idGen, ids, clock, retryCandidates = stateRoot.resolve("candidates"))
        val roots = ArrayList<Path>()
        val scripted = object : io.astrolabe.tool.run.Runner {
            override val mode = io.astrolabe.auth.ExecutionMode.TrustedLocal
            override fun start(spec: io.astrolabe.os.SpawnSpec): io.astrolabe.os.Proc {
                roots.add(spec.workingDirectory)
                val command = if (roots.size % 2 == 1) printing("pytest_fail.txt", 1) else printing("pytest_pass.txt", 0)
                return TrustedLocalRunner(os).start(spec.copy(command = io.astrolabe.os.Command.Argv(command.argv)))
            }
        }
        verify = Verify(checks, scheduler, null, null, null, workspace, scripted, os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val command = printing("pytest_fail.txt", 1)
        checks.register(Check("CHK-slow", CheckKind.Unit, Selector.Named(command), Closure.Known(setOf("src/a.py")), CostClass.Slow, Trigger.OnDemand, command = command))
        val out = run("""{"what":"tests","selection":"ids","ids":["CHK-slow"]}""")
        val attempts = SqliteReceipts(store, clock).forCheck("CHK-slow")
        assertEquals(listOf(Outcome.Failed, Outcome.Passed, Outcome.Inconclusive), attempts.map { it.outcome }, out.body)
        assertEquals(repo.root.toRealPath(), roots[0].toRealPath(), "the first run is not isolated")
        assertTrue(roots[1].startsWith(stateRoot.resolve("candidates")))
        assertEquals(InputStability.Isolated, attempts[1].testedInputs.stability)

        // A fast check keeps the previous default: no retry without configured candidate isolation.
        checks.register(Check("CHK-fast", CheckKind.Unit, Selector.Named(command), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, command = command))
        val fast = run("""{"what":"tests","selection":"ids","ids":["CHK-fast"]}""")
        assertTrue(fast.body.contains("isolated retry unavailable"), fast.body)
        assertEquals(listOf(Outcome.Failed), SqliteReceipts(store, clock).forCheck("CHK-fast").map { it.outcome })
    }

    @Test
    fun `a failed check that changes the original candidate is not rerun`() = runTest {
        scheduler = Scheduler(checks, workspace, registry, stamper, SqliteReceipts(store, clock), InMemoryAliases(), idGen, ids, clock, candidates = stateRoot.resolve("candidates"))
        verify = Verify(checks, scheduler, null, null, null, workspace, TrustedLocalRunner(os), os, stamper, store.blobs, Redaction(), HeuristicEstimator(), idGen, ids, contracts, stateRoot.resolve("logs"))
        val flaky = if (windows) {
            Command(listOf("cmd.exe", "/d", "/s", "/c", "if exist flaky.flag (type pytest_pass.txt) else (type nul > flaky.flag & type pytest_fail.txt & exit /b 1)"))
        } else {
            Command(listOf("/bin/sh", "-c", "if [ -f flaky.flag ]; then cat pytest_pass.txt; else touch flaky.flag; cat pytest_fail.txt; exit 1; fi"))
        }
        checks.register(Check("CHK-flaky", CheckKind.Unit, Selector.Named(flaky), Closure.Known(setOf("src/a.py")), CostClass.Fast, Trigger.OnDemand, command = flaky))
        val out = run("""{"what":"tests","selection":"ids","ids":["CHK-flaky"]}""")
        assertEquals("failed", status(out), out.body)
        assertTrue(out.body.contains("isolated retry unavailable"), out.body)
        val attempts = SqliteReceipts(store, clock).forCheck("CHK-flaky")
        assertEquals(listOf(Outcome.Failed), attempts.map { it.outcome })
        assertEquals(attempts.last().receiptId, checks["CHK-flaky"]!!.last!!.receiptId)
        assertFalse(scheduler.currency(checks["CHK-flaky"]!!, stamper.stamp().id).certifies)
    }

    @Test
    fun `a failed suite is red with parsed counts and a missing runner is an explicit unavailable receipt (FX-13 partial)`() = runTest {
        val failed = run("""{"what":"tests","selection":"ids","ids":["CHK-accept-AC-2"]}""")
        assertEquals("failed", status(failed), failed.body)
        assertFalse(failed.green)
        assertTrue(failed.body.contains("accept AC-2: now 1 5 pass 1 fail 1 skip @"), failed.body)
        assertEquals(listOf(Outcome.Failed), SqliteReceipts(store, clock).forCheck("CHK-accept-AC-2").map { it.outcome }, "without candidate isolation the first failure stays red")
        assertTrue(failed.body.contains("isolated retry unavailable"), failed.body)

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
        assertTrue(blast.body.contains("blast radius: no atlas for this candidate"), blast.body)
        assertEquals("denied", status(run("""{"what":"tests","selection":"ids","ids":["CHK-nope"]}""")))
        assertEquals("denied", status(run("""{"what":"acceptance","ids":["AC-9"]}""")))
        // P3.5.2: review(scope=campaign) is the human path; without a wired reviewer it is unavailable, never a pass. So is the review cell (increment scope, P4.4.3).
        val review = run("""{"what":"review"}""")
        assertEquals("unavailable", status(review))
        assertTrue(review.body.contains("no campaign review path is wired"), review.body)
        assertEquals("unavailable", status(run("""{"what":"review","scope":"increment"}""")))
        assertEquals("unavailable", status(run("""{"what":"baseline"}""")))
        assertTrue(SqliteReceipts(store, clock).forCheck("CHK-full").isEmpty(), "refusals record no receipt")
    }
}
