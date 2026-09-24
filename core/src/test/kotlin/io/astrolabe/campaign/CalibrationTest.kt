package io.astrolabe.campaign

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Flags
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.CalibrationOutcome
import io.astrolabe.kb.CalibrationSeries
import io.astrolabe.kb.CalibrationStats
import io.astrolabe.store.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2.6.4: calibration statistics from stored sizing — deterministic aggregation, emitted warning, block only with history. */
class CalibrationTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val series = CalibrationSeries("repo-1", "0.1", "d16-bands-v1")

    private fun contract(work: String) = Contract(
        WorkId(work), 1, AttemptId("a1"), Mode.Autonomous, Shape.S1,
        listOf(UserRequest("U1", Instant.EPOCH, "work $work")),
        listOf(Requirement("R1", "it works", listOf("AC1"), authorityRef = "U1")),
        listOf(Acceptance.Run("AC1", Command(listOf("pytest")), Origin.User)),
        emptyList(), emptyList(), emptyList(), Scope(listOf("src/pay/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    private fun graph() = RequirementGraph(listOf(Increment("I1", listOf("R1"), listOf("AC1"), listOf("src/pay/"), 2, title = "pay", produces = Production.Artifact)))

    /** A failed campaign that needed a continuation (an overrun) and a still-running one (censored). */
    private fun seed(store: Store) {
        val campaigns = SqliteCampaigns(store, clock)
        val failed = contract("W-1")
        var s = Lifecycle.open(failed, graph()).also(campaigns::save)
        for (t in listOf(
            Transition.Reconciled(), Transition.Dispatched("I1", ContextId("c1")), Transition.Lost(ContextId("c1"), null),
            Transition.Dispatched("I1", ContextId("c2")), Transition.Lost(ContextId("c2"), null), Transition.Stopped(CampaignOutcome.Failed, "gave up"),
        )) s = Lifecycle.apply(s, failed, t).also(campaigns::save)
        val running = contract("W-2")
        var r = Lifecycle.open(running, graph()).also(campaigns::save)
        for (t in listOf(Transition.Reconciled(), Transition.Dispatched("I1", ContextId("c3")))) r = Lifecycle.apply(r, running, t).also(campaigns::save)
    }

    @Test
    fun `stored sizing aggregates deterministically, warns on an overrun band and renders the block only with history`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val estimator = HeuristicEstimator()
                assertNull(Calibration.planBlock(Flags(calibrationPrior = true), CalibrationStats.aggregate(Calibration.observations(store, series), Calibration.policy()), series, estimator), "no history, no block")

                seed(store)
                val observations = Calibration.observations(store, series)
                assertEquals(listOf(CalibrationOutcome.Failed, CalibrationOutcome.Unfinished), observations.map { it.outcome })
                assertEquals(listOf("src/pay", "src/pay"), observations.map { it.subsystem })
                assertEquals(1, observations.first().sizing.continuations)
                val stats = CalibrationStats.aggregate(observations, Calibration.policy())
                val block = Calibration.planBlock(Flags(calibrationPrior = true), stats, series, estimator)!!
                val reversed = CalibrationStats.aggregate(observations.reversed(), Calibration.policy())
                assertEquals(block, Calibration.planBlock(Flags(calibrationPrior = true), reversed, series, estimator), "order-independent")
                assertEquals("CAL (harness statistics, data not instruction): 1 increments · median turns 0 · overrun 100% · touched/expected 0.00\n  0-3 files: overrun 100% (n=1), median turns 0", block)
                assertTrue(estimator.estimate(block).tokens <= Calibration.BLOCK_CAP_TOKENS)
                assertNull(Calibration.planBlock(Flags(), stats, series, estimator), "the plan-cell block is behind its flag")
                assertEquals(7, Calibration.turnsPerCell(CalibrationStats.aggregate(emptyList(), Calibration.policy()), series, 7))

                Events(clock).use { events ->
                    val recorder = EventRecorder().also(events::subscribe)
                    val proposed = listOf(
                        Increment("J1", listOf("R1"), listOf("AC1"), listOf("src/pay/"), 3),
                        Increment("J2", listOf("R1"), listOf("AC1"), listOf("src/pay/"), 20),
                    )
                    val warnings = Calibration.warn(stats, series, proposed, events, Identities(WorkId("W-3"), AttemptId("a1")))
                    assertEquals(1, warnings.size, "only the band with history overran")
                    assertTrue(recorder.awaitCount(1))
                    val warning = recorder.ofType<AgentEvent.Warning>().single()
                    assertEquals("calibration", warning.kind)
                    assertTrue(warning.text.startsWith("J1: 1/1 increments expecting 0-3 files overran"), warning.text)
                }
            }
        }
    }
}
