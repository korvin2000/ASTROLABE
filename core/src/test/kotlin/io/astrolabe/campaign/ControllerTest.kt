package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Stage
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.TouchKind
import io.astrolabe.cell.WINDOWS
import io.astrolabe.cell.echo
import io.astrolabe.context.Compiled
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.provider.BillingDimension
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.Export
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.run.Run
import io.astrolabe.tool.run.SqliteHandles
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.verify.Checks
import io.astrolabe.verify.CompletionResult
import io.astrolabe.workset.Workset
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

/** P1.9.2 campaign open and reconciliation: capture, contract, reconcile before dispatch (FX-23 open-time), shape. */
class ControllerTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun controller(config: Config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)) = Controller(config, clock, idGen)

    private fun open(policy: CampaignPolicy = this.policy): OpenedCampaign = controller().open(repo.root, request, policy)

    @Test
    fun `a fresh open captures the dirty state, stores the derived contract and reconciles before running`() {
        repo.write("src/a.py", "def a():\n    return 2\n")
        open().use { c ->
            assertEquals(setOf("src/a.py"), c.s0.presentPaths, "snapshot 0 holds the user's pre-existing change")
            assertEquals(1, c.contract.version)
            assertEquals(listOf("make", "test"), c.commands.test?.argv)
            assertNotNull(c.checks.get(Checks.acceptId("AC-1")), "the run: acceptance is seeded as a check")
            val state = assertNotNull(c.state)
            assertEquals(CampaignPhase.Running, state.phase)
            assertEquals(listOf(ShapeSelector.SINGLE), state.graph.increments.map { it.id })
            assertTrue(c.reconciliation.unknownOutcomes.isEmpty() && c.reconciliation.external.isEmpty())
            val shape = assertIs<ShapeDecision.Selected>(c.shape)
            assertEquals(Shape.S0, shape.shape)
            assertTrue(shape.limitations.any { "risk unknown" in it }, "an unassessed risk is reported, not read as low")
            assertNull(c.stop)
            assertEquals(state, c.campaigns.load(request.work, request.attempt), "the reconciled state is persisted")
        }
    }

    @Test
    fun `FX-23 open-time - an open intent becomes unknown_outcome and its relaunch is refused`() = runTest {
        val argv = echo("hi").argv
        open().use { c ->
            val intent = Intent("intent-crash", c.ids, "act-crash", argv, null, "W", at = clock.instant())
            c.intents.record(intent)
            c.intents.update(intent.intentId, IntentStatus.Dispatched)
            // The process dies here: no observation, no commit.
        }
        open().use { c ->
            assertEquals(listOf("intent-crash"), c.reconciliation.unknownOutcomes)
            assertEquals(IntentStatus.Unknown, c.intents.get("intent-crash")!!.status)
            assertEquals(CampaignPhase.Running, c.state!!.phase)
            val reconciled = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Reconcile)))
            assertTrue(reconciled.any { "intent-crash" in it.refs && "unknown_outcome" in it.text })

            val run = Run(
                c.workspace, c.registry, c.stamper, TrustedLocalRunner(c.os), c.os, c.intents, SqliteHandles(c.store, clock),
                SqliteObservations(c.store, clock), SqliteAliases(c.store, clock), c.store.blobs, Redaction(), HeuristicEstimator(),
                idGen, c.ids.copy(context = ContextId("cell-1")), c.contracts, AutonomousAuthority(), Config(), clock, stateRoot.resolve("logs"),
            )
            val json = """{"argv":[${argv.joinToString(",") { "\"$it\"" }}]}"""
            val call = (ToolCalls.parse(listOf(ProviderCall("c1", "run", json))) as ParsedCalls.Valid).calls.single()
            val outcome = run.execute(call, TurnContext(1, Workset().snapshot(), Reservations(Tokens(10_000))))
            assertEquals("unknown_outcome", outcome.header!!.runtime.status)
            assertEquals(listOf("intent-crash"), c.intents.open().map { it.intentId }, "nothing was relaunched")
        }
    }

    @Test
    fun `a member that moved while closed is reconciled as an external touch`() {
        open().use { }
        repo.write("src/a.py", "def a():\n    return 3\n")
        open().use { c ->
            val touched = c.reconciliation.external.single()
            assertEquals("src/a.py", touched.path)
            assertEquals(TouchKind.Modified, touched.kind)
            assertEquals("external", touched.note)
            assertTrue(c.s0.presentPaths.isEmpty(), "snapshot 0 stays the first open's capture")
        }
        open().use { c -> assertTrue(c.reconciliation.external.isEmpty(), "the drift was recorded; nothing moved since") }
    }

    @Test
    fun `multi-session work selects S1 and opens running`() {
        open(policy.copy(resumeExpected = true)).use { c ->
            assertEquals(io.astrolabe.contract.Shape.S1, assertIs<ShapeDecision.Selected>(c.shape).shape, "multi-session work is S1 (P2.2.2)")
            assertEquals(CampaignPhase.Running, c.state!!.phase)
            assertNull(c.stop)
        }
    }

    @Test
    fun `S2 work without a reviewer is blocked honestly and stays blocked on reopen`() {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = derived.acceptance + Acceptance.Review("AC-R", "a maintainer approves the API", Origin.User)))
        }
        open().use { c ->
            val unavailable = assertIs<ShapeDecision.Unavailable>(c.shape)
            assertTrue(unavailable.reason.startsWith("shape S1+ unavailable: capability unavailable: required review"), unavailable.reason)
            assertEquals(CampaignOutcome.BlockedExternal, c.stop?.outcome)
            assertEquals(CampaignPhase.Ended, c.state!!.phase)
        }
        open().use { c ->
            assertIs<ShapeDecision.Unavailable>(c.shape)
            assertEquals(CampaignOutcome.BlockedExternal, c.state!!.outcome, "blocked_external resumes, then blocks again on the same missing capability")
        }
    }

    @Test
    fun `a repository without a declared suite opens no campaign and says why`() {
        java.nio.file.Files.delete(repo.root.resolve("Makefile"))
        repo.commit("no suite")
        open().use { c ->
            assertNull(c.state)
            val stop = assertNotNull(c.stop)
            assertEquals(CampaignOutcome.WaitingForInput, stop.outcome)
            assertTrue("run: acceptance" in stop.reason)
            assertNull(c.campaigns.load(request.work, request.attempt))
        }
    }

    private fun recorded(name: String) = javaClass.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** Stores the contract before the first open, with a `run:` item whose output the pytest shaper counts (D-50). */
    private fun seedContract() {
        repo.write("pytest_pass.txt", recorded("pytest-pass.txt"))
        repo.commit("fixture output")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
        }
    }

    private fun model(vararg replies: Scripted): CellModel =
        CellModel(FakeAdapter(ScriptedModel.of(*replies)), FakeProfiles.main, HeuristicEstimator())

    @Test
    fun `S0 end to end - a scripted cell edits, runs acceptance and the campaign completes on receipts`() = runTest {
        seedContract()
        repo.write("NOTES.md", "the user's own draft\n")
        open().use { c ->
            val v = c.registry.version("src/a.py")!!
            val adapter = FakeAdapter(ScriptedModel.of(
                Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py"))),
                Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
                Scripted.Reply(listOf(say("done: a returns 10"))),
            ))
            val run = controller().runS0(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertIs<CellExit.Completed>(run.exit, run.state?.reason)
            assertIs<CompletionResult.Accepted>(run.completion)
            assertIs<Compiled.Ready>(run.compiled)
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertEquals(RequirementStatus.Verified, state.ledger.entries.getValue("R1").status)
            assertEquals(IncrementStatus.Verified, state.graph.increments.single().status)
            assertEquals("def a():\n    return 10\n", Files.readString(repo.root.resolve("src/a.py")))

            // P1.11.2: every call is priced and stored, and the fake provider's invoices reconcile with it.
            val calls = Accounting(c.store, clock).calls(request.work)
            assertEquals(adapter.calls.map { it.invocation.value }, calls.map { it.invocationId })
            for ((billed, account) in adapter.calls.zip(calls)) {
                assertEquals(billed.cacheReadTokens, account.usage!!.quantities[BillingDimension.CACHE_READ])
                assertEquals(billed.cacheWriteTokens, account.usage!!.totalCacheWrite)
                assertTrue(!account.money.unknown && account.quantities.bytesTransmitted!! > 0)
            }
            // P2.3.2: the compiled context left a manifest, and the first response filled its actual usage.
            val manifestIds = c.store.db.query("SELECT id FROM manifests") { it.string("id") }
            val manifest = io.astrolabe.context.SqliteManifests(c.store, clock).get(manifestIds.single())!!
            assertEquals("ready", manifest.outcome)
            assertEquals(listOf("k-mandatory"), manifest.selectedUnits)
            assertEquals(Accounting(c.store, clock).calls(request.work).first().usage!!.totalInput, manifest.actualUsage)
            assertTrue(manifest.estimatedTokens > 0 && manifest.arithmetic.available!! >= manifest.arithmetic.selected)
            // P1.9.5: the finish receipt separates the agent's change from the user's pre-existing one.
            val finish = assertNotNull(run.finish)
            assertEquals("completed", finish.status)
            assertEquals(listOf("src/a.py"), finish.changes.agent)
            assertEquals(listOf("NOTES.md"), finish.changes.preExistingUserChanges)
            val ac1 = finish.acceptance.single()
            assertEquals("green", ac1.status)
            assertTrue(ac1.logIds.isNotEmpty())
            assertTrue(finish.notVerified.isEmpty())
            assertEquals(Stage.Patch, finish.highestAuthorizedStage)
            assertTrue(finish.checksRun.any { it.receiptId.isNotBlank() && it.verifierVersion.isNotBlank() })
            assertTrue(Files.exists(c.store.layout.exports.resolve(request.work.value).resolve("finish-receipt.json")))

            val files = Export(c.store).write(request.work, acceptedTasks = 1, currency = "USD")
            assertEquals(listOf("usage.json", "accounting.json"), files.map { it.fileName.toString() })
            assertTrue("\"costPerAcceptedTask\"" in Files.readString(files[1]))
        }
        open().use { c -> assertTrue(c.reconciliation.external.isEmpty(), "the cell's own edit is snapshotted, not external at reopen") }
    }

    @Test
    fun `decision packets reach the finish receipt as ADR candidates and private reasoning does not`() = runTest {
        seedContract()
        open().use { c ->
            val v = c.registry.version("src/a.py")!!
            val decisions = """[{"decision.add":{"text":"return a constant","because":"the contract names 10","rejected":"read it from config","probe":"grep -n CONFIG src","adrCandidate":true}},""" +
                """{"decision.add":{"text":"keep the name a","because":"callers use it","rejected":null}},{"next":"edit a"}]"""
            val run = controller().runS0(c, model(
                Scripted.Reply(listOf(say("private musing: maybe the config loader is haunted"), read("c1", "src/a.py"), call("c2", "state", """{"op":"patch","patch":$decisions}"""))),
                Scripted.Reply(listOf(say("editing"), anchored("c3", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf(say("verifying"), call("c4", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
                Scripted.Reply(listOf(say("done: a returns 10"))),
            ))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val finish = assertNotNull(run.finish)
            assertEquals(listOf("return a constant because the contract names 10; rejected: read it from config; probe: grep -n CONFIG src"), finish.adrCandidates)
            assertEquals(2, finish.decisions.size)
            val exported = Files.readString(c.store.layout.exports.resolve(request.work.value).resolve("finish-receipt.json"))
            assertTrue("return a constant" in exported)
            assertFalse("haunted" in exported, "the model's private text is never serialized into the receipt")
        }
    }

    @Test
    fun `S0 without a green receipt never moves the ledger - the cell stops honestly instead`() = runTest {
        seedContract()
        // The acceptance output is gone: verify-on-stop runs AC-1 on the done claim, and its receipt is not green.
        Files.delete(repo.root.resolve("pytest_pass.txt"))
        open().use { c ->
            val run = controller().runS0(c, model(Scripted.Reply(listOf(say("done, trust me")))))
            assertTrue(run.exit !is CellExit.Completed, "a done claim without a current green receipt is not a completion")
            assertFalse(SqliteReceipts(c.store, clock).forCheck("CHK-accept-AC-1").single().outcome.green)
            assertTrue(run.completion !is CompletionResult.Accepted)
            val outcome = assertNotNull(run.outcome)
            assertTrue(outcome != CampaignOutcome.Completed, run.state?.reason)
            assertEquals(RequirementStatus.Pending, c.campaigns.load(request.work, request.attempt)!!.ledger.entries.getValue("R1").status)
        }
    }
}
