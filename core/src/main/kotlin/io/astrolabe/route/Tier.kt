package io.astrolabe.route

import io.astrolabe.provider.SerializableLocalDate
import kotlinx.serialization.Serializable

/**
 * Routing tiers (§11.1): execution-policy classes, never vendor labels. The model tiers are ordered
 * `Low < Medium < High < ExtraHigh`, so `max` over them is a floor; [Deterministic] routes to no model at all.
 */
@Serializable
public enum class Tier {
    Low, Medium, High, ExtraHigh, Deterministic;

    /** True for a tier a model profile can serve. */
    public val model: Boolean get() = this != Deterministic

    /** The next model tier up, or this one at the top (calibration promotes one step, D-35). */
    public fun promoted(): Tier = if (this == ExtraHigh || !model) this else entries[ordinal + 1]
}

/**
 * Versioned tier data (§11.1): the profiles serving each model tier and the date of the harness calibration
 * suite that seeded it (`null` while unmeasured). Re-seeded when catalogs change; [version] names the seed.
 */
@Serializable
public data class TierTable(
    val version: String,
    val calibrationDate: SerializableLocalDate? = null,
    val profiles: Map<Tier, Set<String>> = emptyMap(),
) {
    init {
        require(version.isNotBlank() && version == version.trim()) { "a tier table needs a version" }
        require(Tier.Deterministic !in profiles) { "deterministic functions route to no profile" }
        profiles.values.flatten().forEach { require(it.isNotBlank()) { "profile ids must not be blank" } }
    }

    /** Every profile id the table tiers. */
    public val profileIds: Set<String> get() = profiles.values.flatten().toSet()

    /** Profiles able to serve [tier]: listed there or at a higher model tier (a tier is a floor; cost decides among them). */
    public fun serving(tier: Tier): Set<String> =
        Tier.entries.filter { it.model && it >= tier }.flatMap { profiles[it].orEmpty() }.toSet()

    public companion object {
        /** No profile tiered: the host has not seeded a table. */
        @JvmField
        public val UNTIERED: TierTable = TierTable("untiered")

        /** One profile serving every model tier: the controller's fallback when the configuration tiers nothing (D-108). */
        @JvmStatic
        public fun single(profileId: String): TierTable =
            TierTable("single:$profileId", null, Tier.entries.filter { it.model }.associateWith { setOf(profileId) })
    }
}
