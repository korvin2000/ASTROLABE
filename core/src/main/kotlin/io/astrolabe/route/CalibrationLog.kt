package io.astrolabe.route

import io.astrolabe.provider.Effort

/** How a routed call ended, as the harness verified it — never the model's word. */
public enum class RoutingOutcome { Accepted, VerifiedFailure, Unverified, Refused }

/** One `(function, tier, effort, outcome)` quadruple (§11.1), keyed for calibration by its feature class. */
public data class CalibrationEntry @JvmOverloads constructor(
    val function: RoutingFunction,
    val tier: Tier,
    val effort: Effort?,
    val outcome: RoutingOutcome,
    val featureClass: String? = null,
    val profile: String? = null,
)

/**
 * The append-only routing log (§11.1) the D-35 calibration reads. In memory for one controller; durable
 * persistence is the calibration owner's (D-131). Promotion needs [MIN_VERIFIED] verified quadruples of the
 * feature class at a tier with more than [FAILURE_RATE_PERCENT] % verified failures; demotion is never derived here.
 */
public class CalibrationLog {
    private val lock = Any()
    private val rows = ArrayList<CalibrationEntry>()

    public fun append(entry: CalibrationEntry) {
        synchronized(lock) { rows += entry }
    }

    public fun entries(): List<CalibrationEntry> = synchronized(lock) { rows.toList() }

    /** D-35: the logged verified outcomes of [function] at [tier] for [featureClass] justify one tier up. */
    public fun promotes(function: RoutingFunction, tier: Tier, featureClass: String?): Boolean {
        val verified = entries().filter {
            it.function == function && it.tier == tier && it.featureClass == featureClass &&
                (it.outcome == RoutingOutcome.Accepted || it.outcome == RoutingOutcome.VerifiedFailure)
        }
        if (verified.size < MIN_VERIFIED) return false
        val failures = verified.count { it.outcome == RoutingOutcome.VerifiedFailure }
        return failures * 100L > FAILURE_RATE_PERCENT * verified.size.toLong()
    }

    public companion object {
        public const val MIN_VERIFIED: Int = 20
        public const val FAILURE_RATE_PERCENT: Int = 30
    }
}
