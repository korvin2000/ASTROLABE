package io.astrolabe.route

import io.astrolabe.provider.Effort
import java.util.Collections

/** The routed functions of §11.1, one per table row; review splits by what it reviews. */
public enum class RoutingFunction {
    Plan, Implementing, Continuation, Probe, ReviewCritical, ReviewRoutine, Qa, Curation, RepairHelper, Deterministic,
}

/** The §11.2 refusal options when nothing affordable serves the tier; the floor is never clamped (FX-32). */
public enum class Refusal { NarrowUnit, Checkpoint, AskForChangedConstraint }

/**
 * One §11.1 row. [effort] `null` means the configured effort of the call; [escalateWhen] is the row's vocabulary
 * for the P4.5.2 ladder; [neverBelow] is the function floor; [ifUnaffordable] the refusal options in preference order.
 */
public data class FunctionRow @JvmOverloads constructor(
    val function: RoutingFunction,
    val defaultTier: Tier,
    val effort: Effort?,
    val escalateWhen: String?,
    val neverBelow: Tier?,
    val ifUnaffordable: List<Refusal>,
    val maxAttempts: Int? = null,
) {
    init {
        require(neverBelow == null || neverBelow.model) { "a floor is a model tier" }
        require(defaultTier.model || (effort == null && neverBelow == null && ifUnaffordable.isEmpty())) { "a deterministic row routes to no model" }
        require(maxAttempts == null || maxAttempts > 0)
    }
}

/** The versioned function table (§11.1); one row per function. */
public class FunctionTable(public val version: String, rows: List<FunctionRow>) {
    public val rows: Map<RoutingFunction, FunctionRow> = Collections.unmodifiableMap(rows.associateByTo(LinkedHashMap()) { it.function })

    init {
        require(version.isNotBlank() && version == version.trim())
        require(rows.size == this.rows.size) { "one row per function" }
    }

    public fun row(function: RoutingFunction): FunctionRow =
        requireNotNull(rows[function]) { "no row for $function in function table $version" }

    public companion object {
        /** The §11.1 table as published; "medium/high" review effort splits by row, "same as the failing cell" is the packet's previous tier. */
        @JvmField
        public val DEFAULT: FunctionTable = FunctionTable("routing-11.1-v1", listOf(
            FunctionRow(RoutingFunction.Plan, Tier.High, null, null, Tier.High, listOf(Refusal.NarrowUnit, Refusal.AskForChangedConstraint)),
            FunctionRow(RoutingFunction.Implementing, Tier.High, null, "extra-high after two verified failures of the same increment with different hypotheses", Tier.Medium, listOf(Refusal.NarrowUnit, Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.Continuation, Tier.High, null, "extra-high after two verified failures of the same increment with different hypotheses", Tier.Medium, listOf(Refusal.NarrowUnit, Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.Probe, Tier.Medium, Effort.Medium, "high if findings insufficient twice", null, listOf(Refusal.NarrowUnit)),
            FunctionRow(RoutingFunction.ReviewCritical, Tier.High, Effort.High, "on escalate verdict", Tier.Medium, listOf(Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.ReviewRoutine, Tier.Medium, Effort.Medium, "on escalate verdict", Tier.Medium, listOf(Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.Qa, Tier.Medium, Effort.Medium, null, null, listOf(Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.Curation, Tier.Low, Effort.Low, "medium when lint finds contradictions", null, listOf(Refusal.Checkpoint)),
            FunctionRow(RoutingFunction.RepairHelper, Tier.Low, Effort.Low, "owner cell", null, listOf(Refusal.AskForChangedConstraint), maxAttempts = 2),
            FunctionRow(RoutingFunction.Deterministic, Tier.Deterministic, null, null, null, emptyList()),
        ))
    }
}
