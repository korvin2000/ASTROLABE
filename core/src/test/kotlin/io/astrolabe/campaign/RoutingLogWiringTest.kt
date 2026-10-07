package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.route.BindingKey
import io.astrolabe.route.BindingObservation
import io.astrolabe.route.BindingPhysics
import io.astrolabe.route.Routed
import io.astrolabe.route.RoutingDecisionKind
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.RoutingLog
import io.astrolabe.store.Store
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** E1 (§4.6, §11.1): the controller's routing writes `routing_log` over the attempt's frozen binding snapshot, through the real composition. */
class RoutingLogWiringTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-07T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-route"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))
    private val key = BindingKey.of(FakeProfiles.main)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("out.txt", "1 passed in 0.01s\n")
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type out.txt")) else Command(listOf("/bin/sh", "-c", "cat out.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness))))
            // A row of another attempt before this one starts: the snapshot of this attempt is as of it.
            BindingPhysics(store, clock).observe(BindingObservation(key, Identities(WorkId("W-earlier"), AttemptId("a1")), "i0", outputTokens = 10))
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun `an S0 run logs its routing decision with the binding key over a snapshot a reopen reads unchanged`() = runTest {
        val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)
        val frozenSeq = controller.open(repo.root, request, policy).use { c ->
            val run = controller.runS0(c, CellModel(FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(say("done"))))), FakeProfiles.main, HeuristicEstimator()))
            val decisions = RoutingLog(c.store, clock).decisions(request.work, request.attempt)
            assertTrue(decisions.isNotEmpty(), "the controller's routing writes routing_log; the run ended ${run.outcome}: ${run.state?.reason}")
            val selected = decisions.first { it.kind == RoutingDecisionKind.Selected }
            assertEquals(key.route, assertNotNull(selected.bindingKey).route)
            val keyed = c.store.db.query(
                "SELECT count(*) AS n FROM routing_log WHERE work_id = ? AND attempt_id = ? AND binding_key IS NOT NULL", request.work, request.attempt,
            ) { it.long("n") }.single()
            assertTrue(keyed >= 1, "a selected row carries its binding_key column")

            val snapshot = assertNotNull(BindingPhysics(c.store, clock).frozen(request.work, request.attempt), "binding_snapshots holds the attempt's snapshot")
            assertEquals(1L, snapshot.asOfSeq)
            assertEquals(listOf(key.route), snapshot.rows.map { it.key.route })
            assertTrue(decisions.all { it.snapshotSeq == snapshot.asOfSeq }, decisions.map { it.snapshotSeq }.toString())
            val snapshots = c.store.db.query("SELECT count(*) AS n FROM binding_snapshots WHERE work_id = ? AND attempt_id = ?", request.work, request.attempt) {
                it.long("n")
            }.single()
            assertEquals(1L, snapshots)
            snapshot.asOfSeq
        }

        Store.open(stateRoot, repo.git, clock).use { store ->
            // The live table moves on after the attempt froze its snapshot.
            val physics = BindingPhysics(store, clock)
            physics.observe(BindingObservation(key, Identities(WorkId("W-later"), AttemptId("a1")), "i1", outputTokens = 20))
            val ids = Identities(request.work, request.attempt)
            assertEquals(frozenSeq, physics.freeze(ids, listOf(FakeProfiles.main, FakeProfiles.helper)).asOfSeq, "a reopen reads the frozen snapshot")
            val later = RoutingLog(store, clock).decided(ids, Routed.Deterministic(RoutingFunction.entries.first()), listOf(FakeProfiles.main))
            assertEquals(frozenSeq, later.snapshotSeq)
            assertEquals(1L, store.db.query("SELECT count(*) AS n FROM binding_snapshots WHERE work_id = ? AND attempt_id = ?", request.work, request.attempt) { it.long("n") }.single())
        }
    }
}
