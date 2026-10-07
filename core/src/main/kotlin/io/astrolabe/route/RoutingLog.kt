package io.astrolabe.route

import io.astrolabe.id.AttemptId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Profile
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/** What a routing decision chose: a profile, a refusal at the floor, or no model at all. */
public enum class RoutingDecisionKind { Selected, Refused, Deterministic }

/**
 * One row of `routing_log` (§11.1, §4.6): the [seq]-th routing decision of an attempt — its function and tier, the
 * selected profile and that profile's binding route ([bindingKey], upstream unknown until a call reports it), the
 * binding snapshot the attempt reads (as of [snapshotSeq]) and the reason. The verified outcome of a selection stays the
 * calibration's quadruple ([CalibrationLog]); this row is the decision itself.
 */
@Serializable
public data class RoutingDecision @JvmOverloads constructor(
    val ids: Identities,
    val seq: Long,
    val function: RoutingFunction,
    val tier: Tier,
    val kind: RoutingDecisionKind,
    val reason: String,
    val profileId: String? = null,
    val effort: Effort? = null,
    val bindingKey: BindingKey? = null,
    val snapshotSeq: Long? = null,
    val excluded: Map<String, String> = emptyMap(),
    val featureClass: String? = null,
)

/**
 * The durable routing log of a store (`routing_log`, the router's table, L9). [decided] writes each decision with the
 * attempt's next `seq`, after freezing the attempt's binding snapshot over the decision's candidates (once per attempt,
 * [BindingPhysics.freeze]); [decisions] reads an attempt's decisions in order, for Studio and the auditor.
 */
public class RoutingLog(private val store: Store, private val clock: Clock) {
    private val physics = BindingPhysics(store, clock)

    public fun decided(ids: Identities, routed: Routed, candidates: Collection<Profile>): RoutingDecision {
        val snapshot = physics.freeze(ids, candidates)
        return store.db.tx { tx ->
            val seq = tx.query("SELECT coalesce(max(seq), 0) AS seq FROM routing_log WHERE work_id = ? AND attempt_id = ?", ids.work, ids.attempt) {
                it.long("seq")
            }.first() + 1
            val decision = decision(ids, seq, routed, snapshot.asOfSeq)
            tx.execute(
                "INSERT INTO routing_log (id, work_id, attempt_id, candidate_id, context_id, function, tier, outcome, seq, binding_key, schema_version, created_at, body) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "${ids.work.value}/${ids.attempt.value}/$seq", ids.work, ids.attempt, ids.candidate, ids.context,
                decision.function.name, decision.tier.name, decision.kind.name, seq, decision.bindingKey?.canonical,
                Migrations.SCHEMA_VERSION, clock.instant(), JSON.encodeToString(RoutingDecision.serializer(), decision),
            )
            decision
        }
    }

    /** The decisions of [attempt] in `seq` order. */
    public fun decisions(work: WorkId, attempt: AttemptId): List<RoutingDecision> = store.db.query(
        "SELECT body FROM routing_log WHERE work_id = ? AND attempt_id = ? AND seq IS NOT NULL ORDER BY seq", work, attempt,
    ) { JSON.decodeFromString(RoutingDecision.serializer(), it.string("body")) }

    private fun decision(ids: Identities, seq: Long, routed: Routed, snapshotSeq: Long): RoutingDecision = when (routed) {
        is Routed.Selected -> RoutingDecision(
            ids, seq, routed.function, routed.tier, RoutingDecisionKind.Selected,
            "selected ${routed.profile.id} at tier ${routed.tier} (risk floor ${routed.trace.riskFloor}, requested ${routed.trace.requested}, " +
                "calibrated ${routed.trace.calibrated}); expected cost ${routed.expectedCost?.takeUnless { it.unknown }?.let { "${it.amount.toPlainString()} ${it.currency}" } ?: "unknown"}; " +
                "${routed.excluded.size} excluded",
            routed.profile.id, routed.effort, BindingKey.of(routed.profile), snapshotSeq, routed.excluded, routed.featureClass,
        )
        is Routed.Refused -> RoutingDecision(ids, seq, routed.function, routed.tier, RoutingDecisionKind.Refused, routed.reason,
            snapshotSeq = snapshotSeq, excluded = routed.excluded)
        is Routed.Deterministic -> RoutingDecision(ids, seq, routed.function, Tier.Deterministic, RoutingDecisionKind.Deterministic,
            "a deterministic function routes to no model", snapshotSeq = snapshotSeq)
    }

    private companion object {
        val JSON = Json { encodeDefaults = true }
    }
}
