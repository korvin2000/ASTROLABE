package io.astrolabe.provider.aigate

import io.astrolabe.Astrolabe
import io.astrolabe.Config
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Profile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.testing.FakeProvider
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole campaign through `Astrolabe` with the real adapter and a real `Llm` (A-01, A-07, A-08): only the wire endpoint
 * is simulated. The model only ever claims to be done, so the campaign must not complete; what is checked is the
 * transport path — admission with the adapter's estimator, usage with SDK provenance, progress events, shutdown.
 */
class CampaignThroughGateTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo

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

    @Test
    fun `a campaign runs its model calls through AI Gate and ends with an honest outcome`() = runBlocking<Unit> {
        val fake = FakeProvider.create()
        repeat(2_000) { fake.reply { it.text("done, trust me").usage(900, 12) } }
        val main = GateTestKit.fakeProfile("main")
        val helper = Profile("helper", "fake", "fake-thinker", GateTestKit.capabilities(200_000, 8_000, breakpoints = false), GateTestKit.prices)
        val config = Config(stateRoot = stateRoot.toString(), profiles = mapOf("main" to main, "helper" to helper))
        val events = CopyOnWriteArrayList<AgentEvent>()
        Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog { it.offline() }.build().use { llm ->
            val adapter = AiGateAdapter(llm, config.profiles.values)
            Astrolabe(config, adapter, AutonomousAuthority(), estimators = adapter.estimators(HeuristicEstimator()), ownsAdapter = true).use { sdk ->
                sdk.events.subscribe { events += it.event }.use {
                    sdk.open(repo.root).use { project ->
                        val handle = sdk.campaign(project, "make a return 10")
                        val outcome = withTimeout(120_000) { handle.await() }
                        assertTrue(outcome != CampaignOutcome.Completed, "a done claim without receipts never completes: $outcome")
                    }
                }
            }
        }
        val requested = events.filterIsInstance<AgentEvent.Cell.ModelRequested>()
        assertTrue(requested.isNotEmpty(), "the campaign called the model")
        assertEquals("main", requested.first().profileId)
        assertTrue(requested.first().estimatedTokens > 0)
        val responded = events.filterIsInstance<AgentEvent.Cell.ModelResponded>()
        assertTrue(responded.isNotEmpty())
        val usage = responded.first().usage!!
        assertEquals("fake-chat", usage.provenance.protocol)
        assertEquals(12L, usage.quantities[BillingDimension.OUTPUT])
        val progress = events.filterIsInstance<AgentEvent.Cell.ModelProgress>()
        assertTrue(progress.any { it.stage == "started" && it.invocationId == requested.first().invocationId }, "progress of the first call: $progress")
        assertTrue(fake.requests().first().conversation().system().orElseThrow().startsWith("astrolabe · role "), "[S] is the system prompt")
    }
}
