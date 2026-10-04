package io.astrolabe.evallive

import io.astrolabe.BalanceProfile
import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.Mode
import io.astrolabe.ProfileRoles
import io.astrolabe.RunSpec
import io.astrolabe.UnknownOutcomeReconciliation
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.TaskLimits
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Money
import io.astrolabe.provider.Profile
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** WP-B7: one description of a run for the Studio and `eval-live`, the core's [RunSpec.defaults]. */
class RunSpecTest {
    @TempDir
    lateinit var dir: Path

    /**
     * The Studio's default launch of an `auto` task as it is today, transcribed from ASTROUI: `TaskService.config`
     * (one profile for every function, `dClass` Ask, reconciliation Automatic, no rules file), `TaskService.spec` (the
     * token guard of 10000 windows, `SettingsService.RUNTIME_DEFAULTS` 48 cells and 480 lease minutes, effort Medium
     * not named), `Limits.DEFAULTS` (50.00 USD, 480 minutes, 3000 requests), `StudioHost.corePolicy` (preset
     * `balanced`) and `AutoProfiles.outputHeadroom` (no narrowing). The oracle the core's factory must equal.
     */
    private fun studioLaunch(profile: Profile, stateRoot: String): RunSpec = RunSpec(
        config = Config(
            profiles = mapOf(profile.id to profile),
            profileRoles = ProfileRoles(main = profile.id, helper = null, escalation = null),
            mode = Mode.Autonomous,
            dClass = DClassPolicy.Ask,
            unknownOutcomeReconciliation = UnknownOutcomeReconciliation.Automatic,
            rulesFile = null,
            stateRoot = stateRoot,
        ),
        policy = CampaignPolicy(
            Tokens(profile.capabilities.contextLimitTokens.toLong() * 10_000), null, false,
            limits = TaskLimits(Money("USD", BigDecimal("50.00")), 480, 3_000), balance = BalanceProfile.Balanced,
        ),
        maxCells = 48,
        leaseMinutes = 480,
        effort = Effort.Medium,
        effortExplicit = false,
        maxOutputTokens = null,
    )

    @Test
    fun `the default arm of eval-live and the Studio's default launch give the same RunSpec`() {
        val task = BenchTask.select(Path.of(System.getProperty("evallive.tasks")), listOf("bugfix-pagination"))
        val plan = BenchPlan(tasks = task, models = listOf("fake-main"), provider = "fake", repeats = 1, seed = 0, out = dir.resolve("out"), temp = dir.resolve("tmp"))
        val state = dir.resolve("state")
        for (profile in listOf(FakeProfiles.main, FakeProfiles.tiny)) {
            val core = RunSpec.defaults(profile, state.toString())
            val arm = plan.spec(profile, state)
            assertEquals(core, arm, "eval-live's default arm is the core's default run")
            assertEquals(core, studioLaunch(profile, state.toString()), "the Studio's default launch is the core's default run")

            val studioHeadroom = minOf(profile.capabilities.outputLimitTokens, maxOf(profile.capabilities.contextLimitTokens / 4, 1))
            val model = arm.cellModel(FakeAdapter(ScriptedModel(emptyList())), profile, HeuristicEstimator())
            assertEquals(studioHeadroom, model.maxOutputTokens)
            assertEquals(Effort.Medium, model.effort)
            assertEquals(false, model.effortExplicit)
        }
    }
}
