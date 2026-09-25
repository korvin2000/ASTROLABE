package io.astrolabe.route

import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.provider.Profile
import java.util.Collections

/** One fixed case of a shadow sample: what the router would be asked. */
public data class ShadowCase @JvmOverloads constructor(val function: RoutingFunction, val packet: RoutingPacket, val impact: RiskFloorInput? = null)

/** A frozen offline sample (§11.4): fixed cases an evaluation replays; never a live task. */
public class ShadowSample(public val id: String, cases: List<ShadowCase>) {
    public val cases: List<ShadowCase> = Collections.unmodifiableList(ArrayList(cases))

    init {
        require(id.isNotBlank()) { "a shadow sample is frozen under an id" }
        require(this.cases.isNotEmpty()) { "a shadow sample has cases" }
    }
}

/** The evaluation hook (P6, `eval`) that runs one case offline on one profile and reports the verified outcome. */
public fun interface ShadowTrial {
    public fun run(sample: ShadowSample, case: ShadowCase, profile: Profile): RoutingOutcome
}

/** One cell of a shadow comparison: case index, profile, and its outcome (`Refused` when the router would not route it there). */
public data class ShadowRow(val case: Int, val profile: String, val outcome: RoutingOutcome, val tier: Tier?)

/**
 * The shadow-routing seam (§11.4): routing comparisons run offline on a frozen sample, one profile at a time, through
 * a router with its own calibration log — the live router's log and live tasks are never touched, and nothing here is
 * reachable from the controller (live tasks never fan out to several expensive profiles, D-153).
 */
public object ShadowRouting {
    @JvmStatic
    public fun compare(sample: ShadowSample, policy: RoutingPolicy, profiles: List<String>, trial: ShadowTrial): List<ShadowRow> {
        require(profiles.isNotEmpty() && profiles.all { it in policy.candidates }) { "shadow profiles are candidates of the policy" }
        val rows = ArrayList<ShadowRow>()
        for ((i, case) in sample.cases.withIndex()) {
            for (id in profiles) {
                val routed = Router(CalibrationLog()).selectProfile(case.function, case.packet, case.impact, policy.copy(pins = setOf(id)))
                rows += when (routed) {
                    is Routed.Selected -> ShadowRow(i, id, trial.run(sample, case, routed.profile), routed.tier)
                    is Routed.Refused -> ShadowRow(i, id, RoutingOutcome.Refused, routed.tier)
                    is Routed.Deterministic -> ShadowRow(i, id, RoutingOutcome.Refused, null)
                }
            }
        }
        return Collections.unmodifiableList(rows)
    }
}
