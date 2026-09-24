package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.auth.Redaction
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.TouchKind
import io.astrolabe.cell.echo
import io.astrolabe.contract.Shape
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.SqliteAliases
import io.astrolabe.evidence.SqliteObservations
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.run.Run
import io.astrolabe.tool.run.SqliteHandles
import io.astrolabe.tool.run.TrustedLocalRunner
import io.astrolabe.verify.Checks
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString()), clock, idGen)

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
    fun `S1+ work is blocked honestly and a later open resumes the campaign`() {
        open(policy.copy(resumeExpected = true)).use { c ->
            val unavailable = assertIs<ShapeDecision.Unavailable>(c.shape)
            assertTrue(unavailable.reason.startsWith("shape S1+ unavailable"))
            assertEquals(CampaignOutcome.BlockedExternal, c.stop?.outcome)
            assertEquals(CampaignPhase.Ended, c.state!!.phase)
        }
        open().use { c ->
            assertIs<ShapeDecision.Selected>(c.shape)
            assertEquals(CampaignPhase.Running, c.state!!.phase, "blocked_external resumes: Resumed, then Reconciled")
            assertNull(c.stop)
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
}
