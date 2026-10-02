package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.ProfileRoles
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.context.Compiled
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
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Profile
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import io.astrolabe.store.Store
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Admission and routing (§6.1, §11.2): small windows compile, routed profiles recompile, capacity falls back to larger windows. */
class CapacityRoutingTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))

    private fun windowed(id: String, window: Int, output: Int): Profile =
        FakeProfiles.main.copy(id = id, capabilities = FakeProfiles.main.capabilities.copy(contextLimitTokens = window, outputLimitTokens = output))

    /** Too small for the mandatory context at any output headroom. */
    private val small = windowed("small", 4_000, 1_000)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("out.txt", "1 passed in 0.01s\n")
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type out.txt")) else Command(listOf("/bin/sh", "-c", "cat out.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness))))
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun controller(config: Config) = Controller(config.copy(stateRoot = stateRoot.toString()), clock, idGen)

    private fun done() = FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(say("done")))))

    @Test
    fun `a 16K window with a quarter of it as output compiles and runs`() = runTest {
        val config = Config(profiles = FakeProfiles.all)
        val sixteen = windowed("main", 16_384, 16_000)
        controller(config).open(repo.root, request, policy).use { c ->
            val adapter = done()
            val run = controller(config).runS0(c, CellModel(adapter, sixteen, HeuristicEstimator(), maxOutputTokens = 4_096))
            val ready = assertIs<Compiled.Ready>(run.compiled, run.state?.reason)
            assertEquals(2_250L, ready.selection.arithmetic.reserveTokens.toLong())
            assertEquals(4_096, adapter.calls.first().request.maxOutputTokens)
        }
    }

    @Test
    fun `a routed profile recompiles with its own output headroom`() = runTest {
        val config = Config(profiles = FakeProfiles.all, tierTable = TierTable("t", null, mapOf(Tier.High to setOf("main"))))
        controller(config).open(repo.root, request, policy).use { c ->
            val adapter = done()
            val run = controller(config).runS0(c, CellModel(adapter, FakeProfiles.helper, HeuristicEstimator()))
            val ready = assertIs<Compiled.Ready>(run.compiled, run.state?.reason)
            assertEquals(16_000L, ready.selection.arithmetic.outputTokens.toLong(), "main's headroom, not the supplied helper's 8,000")
            assertEquals("main", adapter.calls.first().request.profile.id)
            assertEquals(16_000, adapter.calls.first().request.maxOutputTokens)
        }
    }

    @Test
    fun `a context too large for the supplied window falls back to a larger candidate`() = runTest {
        val config = Config(profiles = FakeProfiles.all + ("small" to small), tierTable = TierTable("t", null, mapOf(Tier.High to setOf("small", "main"))))
        controller(config).open(repo.root, request, policy).use { c ->
            val adapter = done()
            val run = controller(config).runS0(c, CellModel(adapter, small, HeuristicEstimator()))
            assertIs<Compiled.Ready>(run.compiled, run.state?.reason)
            assertIs<CellExit.Completed>(run.exit, run.state?.reason)
            assertEquals("main", adapter.calls.first().request.profile.id)
        }
    }

    @Test
    fun `a fallback base that is refused gives way to the next larger window with its own headroom`() = runTest {
        // mid holds the context but does not serve the implementing tier; its 8,192 headroom would exclude wide's 4,096 limit.
        val mid = windowed("mid", 32_768, 8_192)
        val wide = windowed("wide", 65_536, 4_096)
        val config = Config(profiles = mapOf("small" to small, "mid" to mid, "wide" to wide), profileRoles = ProfileRoles(main = "wide", helper = null),
            tierTable = TierTable("t", null, mapOf(Tier.Medium to setOf("mid"), Tier.High to setOf("small", "wide"))))
        controller(config).open(repo.root, request, policy).use { c ->
            val adapter = done()
            val run = controller(config).runS0(c, CellModel(adapter, small, HeuristicEstimator()))
            assertIs<Compiled.Ready>(run.compiled, run.state?.reason)
            assertEquals("wide", adapter.calls.first().request.profile.id)
            assertEquals(4_096, adapter.calls.first().request.maxOutputTokens)
        }
    }

    @Test
    fun `when no candidate window holds the context the campaign blocks without a model call`() = runTest {
        val larger = windowed("larger", 5_000, 1_000)
        val config = Config(profiles = mapOf("small" to small, "larger" to larger), profileRoles = ProfileRoles(main = "small", helper = null),
            tierTable = TierTable("t", null, mapOf(Tier.High to setOf("small", "larger"))))
        controller(config).open(repo.root, request, policy).use { c ->
            val adapter = done()
            val run = controller(config).runS0(c, CellModel(adapter, small, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
            val rescoping = assertIs<Compiled.NeedsRescoping>(run.compiled)
            assertTrue("no candidate window fits" in rescoping.reason && "larger:" in rescoping.reason, rescoping.reason)
            assertTrue(adapter.calls.isEmpty())
        }
    }
}
