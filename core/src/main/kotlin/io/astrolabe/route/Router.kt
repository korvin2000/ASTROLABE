package io.astrolabe.route

import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Money
import io.astrolabe.provider.Profile
import java.math.BigDecimal
import java.util.Collections

/**
 * What the router reads of a task packet (§11.2). Until P4.4.1's `TaskPacket` exists this is the router's own
 * record; [risk] is the packet's declared risk, [planSuggestion] the plan cell's tier (informs, never decides),
 * [previousTier] the failing cell's tier a continuation never drops below, [featureClass] the calibration key.
 */
public data class RoutingPacket @JvmOverloads constructor(
    val risk: Risk?,
    val contextTokens: Long,
    val outputTokens: Int,
    val planSuggestion: Tier? = null,
    val previousTier: Tier? = null,
    val featureClass: String? = null,
) {
    init {
        require(contextTokens >= 0 && outputTokens > 0) { "context tokens are ≥ 0 and output headroom positive" }
        require(planSuggestion?.model != false && previousTier?.model != false) { "tier suggestions are model tiers" }
    }
}

/**
 * The remaining budget and its reserves. `null` tokens or cost means that dimension is not capped here (the cell's
 * own admission still is, D-06); a cost cap needs a priced estimate, so an unpriced profile is unaffordable under it.
 */
public data class RoutingBudget @JvmOverloads constructor(
    val remainingTokens: Tokens? = null,
    val reservedTokens: Tokens = Tokens.ZERO,
    val remainingCost: Money? = null,
    val reservedCost: Money? = null,
) {
    init {
        require(remainingCost == null || reservedCost == null || reservedCost.currency == remainingCost.currency)
    }
}

/** The policy inputs of one selection: tables, candidates, budget, eligibility constraints and supplied attempt policies. */
public data class RoutingPolicy @JvmOverloads constructor(
    val tiers: TierTable,
    val candidates: Map<String, Profile>,
    val budget: RoutingBudget = RoutingBudget(),
    val functions: FunctionTable = FunctionTable.DEFAULT,
    val configuredEffort: Effort = Effort.Medium,
    /** Profiles the host authorized; `null` authorizes every candidate. */
    val authorized: Set<String>? = null,
    /** Profiles currently available; `null` means every candidate. */
    val available: Set<String>? = null,
    /** User pins restrict the eligible set; they never lower a floor. */
    val pins: Set<String> = emptySet(),
    /** Minimum calibrated acceptance rate over a profile's stratum outcomes; unmeasured profiles fail a set floor. */
    val qualityFloor: BigDecimal? = null,
    /** Supplied finite attempt policies per profile id (P4.5.4): expected total cost incl. retries, reviews, integration. */
    val attemptPolicies: Map<String, AttemptPolicy> = emptyMap(),
    val remainingAttempts: Int = 1,
    val limits: AttemptLimits = AttemptLimits(100_000),
) {
    init {
        candidates.forEach { (id, profile) -> require(id == profile.id) { "candidate key differs from profile id '${profile.id}'" } }
        require(qualityFloor == null || qualityFloor >= BigDecimal.ZERO && qualityFloor <= BigDecimal.ONE)
        require(remainingAttempts >= 0)
    }
}

/** How the tier was reached: the risk floor, the max with the function default and suggestions, calibration, and the re-applied floors (FX-55). */
public data class RoutingTrace(val riskFloor: Tier, val requested: Tier, val calibrated: Tier, val final: Tier)

public sealed interface Routed {
    public val function: RoutingFunction

    public class Selected internal constructor(
        override val function: RoutingFunction,
        public val tier: Tier,
        public val effort: Effort,
        public val profile: Profile,
        public val conservativeTokens: Tokens,
        public val conservativeCost: Money?,
        /** The argmin's expected total cost: the supplied attempt policy's when known, else the conservative estimate. */
        public val expectedCost: Money?,
        excluded: Map<String, String>,
        public val trace: RoutingTrace,
        public val featureClass: String?,
    ) : Routed {
        public val excluded: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(excluded))
    }

    /** Nothing eligible at the tier is affordable: the floor stands and the caller picks among [options] (FX-32). */
    public class Refused internal constructor(
        override val function: RoutingFunction,
        public val tier: Tier,
        public val options: List<Refusal>,
        excluded: Map<String, String>,
        public val trace: RoutingTrace,
    ) : Routed {
        public val excluded: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(excluded))
        public val reason: String
            get() = "no affordable profile at tier $tier for $function: " +
                excluded.entries.joinToString("; ") { (id, why) -> "$id $why" } + " — ${options.joinToString("|")}"
    }

    /** A deterministic function: routing itself, parsing, stamps, hashes. No model. */
    public data class Deterministic(override val function: RoutingFunction) : Routed
}

/**
 * `select_profile` (§11.2, N7; D6): eligible → tier = max(default, suggestion, risk floor) → calibration (promotion
 * only, D-35) → floors re-applied (FX-55) → affordable, else refuse without clamping (FX-32) → argmin expected total
 * cost. Helper calls are the only second model in S0/S1 and never run inside a worker's loop, so the router is asked
 * once per cell by the controller. Provider reasoning artifacts stay opaque: the router moves profiles, not transcripts.
 */
public class Router @JvmOverloads constructor(public val calibration: CalibrationLog = CalibrationLog()) {
    public fun selectProfile(function: RoutingFunction, packet: RoutingPacket, impact: RiskFloorInput?, policy: RoutingPolicy): Routed {
        val row = policy.functions.row(function)
        if (!row.defaultTier.model) return Routed.Deterministic(function)
        val effort = row.effort ?: policy.configuredEffort
        val floor = riskFloor(packet.risk, impact)
        val requested = listOfNotNull(row.defaultTier, packet.planSuggestion, packet.previousTier, floor).max()
        val calibrated = if (calibration.promotes(function, requested, packet.featureClass)) requested.promoted() else requested
        // FX-55: calibration can never lower either floor.
        val tier = listOfNotNull(calibrated, row.neverBelow, floor).max()
        val trace = RoutingTrace(floor, requested, calibrated, tier)

        val excluded = LinkedHashMap<String, String>()
        val ranked = ArrayList<Ranked>()
        val serving = policy.tiers.serving(tier)
        for ((id, profile) in policy.candidates.toSortedMap()) {
            val ineligible = when {
                id !in serving -> "does not serve tier $tier"
                policy.pins.isNotEmpty() && id !in policy.pins -> "not pinned"
                policy.authorized?.contains(id) == false -> "not authorized"
                policy.available?.contains(id) == false -> "unavailable"
                packet.outputTokens > profile.capabilities.outputLimitTokens -> "output headroom ${packet.outputTokens} exceeds its ${profile.capabilities.outputLimitTokens}"
                packet.contextTokens + packet.outputTokens > profile.capabilities.contextLimitTokens -> "context ${packet.contextTokens}+${packet.outputTokens} does not fit ${profile.capabilities.contextLimitTokens}"
                policy.qualityFloor != null && !meetsQualityFloor(profile, policy.qualityFloor) -> "below the calibrated quality floor"
                else -> null
            }
            if (ineligible != null) { excluded[id] = ineligible; continue }
            val tokens = Tokens(packet.contextTokens + packet.outputTokens)
            val cost = conservativeCost(profile, packet)
            val budget = policy.budget
            val unaffordable = when {
                budget.remainingTokens != null && tokens.value > budget.remainingTokens.value - budget.reservedTokens.value ->
                    "needs ${tokens.value} tokens; ${budget.remainingTokens.value - budget.reservedTokens.value} remain after reserves"
                budget.remainingCost == null -> null
                budget.remainingCost.unknown || budget.reservedCost?.unknown == true -> "remaining cost unknown"
                cost == null || cost.unknown || cost.currency != budget.remainingCost.currency -> "cost unknown at its price table"
                cost.amount > budget.remainingCost.amount.subtract(budget.reservedCost?.amount ?: BigDecimal.ZERO) ->
                    "costs ${cost.amount} ${cost.currency}; ${budget.remainingCost.amount.subtract(budget.reservedCost?.amount ?: BigDecimal.ZERO)} remain after reserves"
                else -> null
            }
            if (unaffordable != null) { excluded[id] = unaffordable; continue }
            ranked += Ranked(profile, tokens, cost, expectedCost(profile, cost, policy))
        }
        if (ranked.isEmpty()) {
            calibration.append(CalibrationEntry(function, tier, effort, RoutingOutcome.Refused, packet.featureClass))
            return Routed.Refused(function, tier, row.ifUnaffordable, excluded, trace)
        }
        val best = ranked.minWith(compareBy<Ranked> { it.expected == null }.thenBy { it.expected?.amount }.thenBy { it.profile.id })
        return Routed.Selected(function, tier, effort, best.profile, best.tokens, best.cost, best.expected, excluded, trace, packet.featureClass)
    }

    /** Logs the verified outcome of a selection as its `(function, tier, effort, outcome)` quadruple. */
    public fun record(selected: Routed.Selected, outcome: RoutingOutcome) {
        calibration.append(CalibrationEntry(selected.function, selected.tier, selected.effort, outcome, selected.featureClass, selected.profile.id))
    }

    private class Ranked(val profile: Profile, val tokens: Tokens, val cost: Money?, val expected: Money?)

    private fun expectedCost(profile: Profile, conservative: Money?, policy: RoutingPolicy): Money? {
        val attempt = policy.attemptPolicies[profile.id] ?: return conservative
        val estimate = AttemptCost.evaluate(attempt, policy.remainingAttempts, policy.limits)
        return estimate.expectedCost?.takeIf { estimate.status == AttemptEstimateStatus.Known } ?: conservative
    }

    private fun meetsQualityFloor(profile: Profile, floor: BigDecimal): Boolean {
        val trials = profile.stratumOutcomes.sumOf { it.trials.toLong() }
        if (trials == 0L) return false
        val accepted = profile.stratumOutcomes.sumOf { it.accepted.toLong() }
        return BigDecimal.valueOf(accepted) >= floor.multiply(BigDecimal.valueOf(trials))
    }

    public companion object {
        /**
         * The D-34 risk floor. The declared risk maps explicitly; the impact's contract touch and fan-in supplement it
         * and, being a `max`, can never erase a declared high-risk obligation whose dependencies were not discovered.
         */
        @JvmStatic
        public fun riskFloor(risk: Risk?, impact: RiskFloorInput?): Tier {
            val declared = when {
                risk == null -> Tier.Low
                risk.blastRadius >= 3 || risk.reversibility == Reversibility.Hard || risk.contractTouch -> Tier.High
                risk.blastRadius == 2 -> Tier.Medium
                else -> Tier.Low
            }
            val fanIn = impact?.maxFanIn ?: 0L
            val supplement = when {
                impact == null -> Tier.Low
                impact.contractsTouched > 0 || fanIn >= 20 -> Tier.High
                fanIn >= 5 -> Tier.Medium
                else -> Tier.Low
            }
            return maxOf(declared, supplement)
        }

        /** One full-context turn at the profile's dated prices: uncached input plus the output headroom (D-109). */
        @JvmStatic
        public fun conservativeCost(profile: Profile, packet: RoutingPacket): Money? {
            val input = profile.priceTable.price(BillingDimension.UNCACHED_INPUT, packet.contextTokens) ?: return null
            val output = profile.priceTable.price(BillingDimension.OUTPUT, packet.outputTokens.toLong()) ?: return null
            return input + output
        }
    }
}
