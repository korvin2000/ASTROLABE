package io.astrolabe

import io.astrolabe.budget.TaskLimits
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.cell.CellModel
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Money
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.TokenEstimator
import java.math.BigDecimal
import java.time.Duration

/**
 * One description of a run (plan §6 B7): everything a host launches a campaign with — the configuration (flags and
 * shape thresholds included), the campaign policy (token guard, limits, balance profile), the cell model's effort and
 * output headroom, the run's cell cap and the controller's lease. [defaults] is the one source of the launch defaults
 * the Studio and `eval-live` share; a host changes a field with `copy`, never with a second copy of a number.
 */
public data class RunSpec @JvmOverloads constructor(
    val config: Config,
    val policy: CampaignPolicy,
    /** The run's cell cap: a technical guard that must not stop a task before the user's limits do. */
    val maxCells: Int = MAX_CELLS,
    /** The controller's lease on the workspace, in minutes. */
    val leaseMinutes: Long = LEASE_MINUTES,
    val effort: Effort = EFFORT,
    /** The host chose [effort] (C14, [CellModel.effortExplicit]): the balance profile never steps it. */
    val effortExplicit: Boolean = false,
    /** The host's narrowing of every request's output; `null` leaves [outputHeadroom] to its rule. */
    val maxOutputTokens: Int? = null,
) {
    init {
        require(maxCells >= 1) { "maxCells must be ≥ 1, got $maxCells" }
        require(leaseMinutes >= 1) { "leaseMinutes must be ≥ 1, got $leaseMinutes" }
        require(maxOutputTokens == null || maxOutputTokens >= 1) { "maxOutputTokens must be ≥ 1, got $maxOutputTokens" }
    }

    /** [leaseMinutes] as the controller takes it. */
    public val leaseDuration: Duration get() = Duration.ofMinutes(leaseMinutes)

    /** The output headroom of every request on [profile]: its limit, a quarter of its window and [maxOutputTokens], whichever is least. */
    public fun outputHeadroom(profile: Profile): Int {
        val share = maxOf(profile.capabilities.contextLimitTokens / 4, 1)
        return minOf(maxOutputTokens ?: profile.capabilities.outputLimitTokens, profile.capabilities.outputLimitTokens, share).coerceAtLeast(1)
    }

    /** The model side of the run's cells on [profile]. */
    public fun cellModel(adapter: ProviderAdapter, profile: Profile, estimator: TokenEstimator): CellModel =
        CellModel(adapter, profile, estimator, effort, outputHeadroom(profile), effortExplicit = effortExplicit)

    public companion object {
        /** The run's cell cap (Studio runtime `maxCells`); the library's `Defaults.campaignCells` stays the facade's. */
        public const val MAX_CELLS: Int = 48

        /** The controller's lease (Studio runtime `leaseMinutes`). */
        public const val LEASE_MINUTES: Long = 480

        /** The effort of a task whose user named none (Studio `defaultEffort`); it is left to the balance profile. */
        @JvmField
        public val EFFORT: Effort = Effort.Medium

        /** The token guard in context windows: far above any limit a user sets, so it never stops a task first. */
        public const val TOKEN_GUARD_WINDOWS: Long = 10_000

        /** The user's default limits of a new task: 50.00 USD, 480 active minutes, 3000 model requests. */
        @JvmField
        public val LIMITS: TaskLimits = TaskLimits(Money("USD", BigDecimal("50.00")), 480, 3_000)

        /** [TOKEN_GUARD_WINDOWS] windows of [contextLimitTokens]. */
        @JvmStatic
        public fun tokenGuard(contextLimitTokens: Int): Tokens = Tokens(contextLimitTokens.toLong() * TOKEN_GUARD_WINDOWS)

        /**
         * The default run on [profile]: that one profile serves every function; [mode] (the Studio's `auto` task is
         * [Mode.Autonomous], its `ask` task [Mode.Interactive]), D-class actions asked, unknown outcomes reconciled
         * automatically, no rules file; the token guard, [LIMITS] and the balanced profile; [MAX_CELLS],
         * [LEASE_MINUTES], medium effort left to the approach and the rule's output headroom.
         */
        @JvmStatic
        @JvmOverloads
        public fun defaults(profile: Profile, stateRoot: String? = null, mode: Mode = Mode.Autonomous): RunSpec = RunSpec(
            config = Config(
                profiles = mapOf(profile.id to profile),
                profileRoles = ProfileRoles(main = profile.id, helper = null, escalation = null),
                mode = mode,
                dClass = DClassPolicy.Ask,
                unknownOutcomeReconciliation = UnknownOutcomeReconciliation.Automatic,
                rulesFile = null,
                stateRoot = stateRoot,
            ),
            policy = CampaignPolicy(tokenGuard(profile.capabilities.contextLimitTokens), cost = null, resumeExpected = false, limits = LIMITS, balance = BalanceProfile.Balanced),
        )
    }
}
