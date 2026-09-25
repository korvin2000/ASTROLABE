package io.astrolabe.campaign

import io.astrolabe.cell.Roles
import io.astrolabe.contract.Increment
import io.astrolabe.route.CacheKey
import io.astrolabe.route.CacheSchedule
import io.astrolabe.route.CellSlot
import io.astrolabe.route.Tier

/**
 * The controller's §11.4 ordering hint over the S1 ready frontier (P4.5.3). The profile a cell will run is known only
 * after routing, so the key stands in the tier it is expected to route at (D-153); a continuation of an increment with
 * cells never waits, and the frontier order governs whenever the keys agree.
 */
internal object CellOrder {
    fun key(tier: Tier): CacheKey = CacheKey(Roles.implementing.name, "tier:${tier.name}")

    fun next(frontier: List<Increment>, previous: CacheKey?, expected: (Increment) -> Tier): Increment? {
        frontier.firstOrNull { it.cells.isNotEmpty() }?.let { return it }
        val first = CacheSchedule.order(frontier.map { CellSlot(it.id, key(expected(it))) }, previous).firstOrNull() ?: return null
        return frontier.first { it.id == first.id }
    }
}
