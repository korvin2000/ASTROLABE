package io.astrolabe.route

import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * P4.8.2: the routing table with fake profiles and budgets (D-174). Every §11.1 function, then the budget rows: what is
 * selected — tier, effort, profile — or refused with the row's options, never clamped to a cheaper tier (FX-32).
 */
class RoutingTableTest {
    private val tiers = TierTable("fixture-tiers", null, mapOf(Tier.Low to setOf("helper"), Tier.High to setOf("main"), Tier.ExtraHigh to setOf("escalation")))
    private val candidates = FakeProfiles.all.filterKeys { it in setOf("helper", "main", "escalation") }
    private val unlimited = RoutingBudget()
    private val tokens = RoutingBudget(remainingTokens = Tokens(12_000), reservedTokens = Tokens(2_000))
    private val dollars = RoutingBudget(remainingCost = Money("USD", BigDecimal("0.05")), reservedCost = Money("USD", BigDecimal("0.01")))

    /** Expected: `tier effort profile`, `refused tier options…` or `deterministic`. */
    private data class Row(val function: RoutingFunction, val budget: RoutingBudget, val expected: String, val risk: Risk? = null, val previous: Tier? = null)

    private val rows = listOf(
        Row(RoutingFunction.Plan, unlimited, "High Medium main"),
        Row(RoutingFunction.Implementing, unlimited, "High Medium main"),
        Row(RoutingFunction.Continuation, unlimited, "ExtraHigh Medium escalation", previous = Tier.ExtraHigh),
        Row(RoutingFunction.Probe, unlimited, "Medium Medium main"),
        Row(RoutingFunction.ReviewCritical, unlimited, "High High main"),
        Row(RoutingFunction.ReviewRoutine, unlimited, "Medium Medium main"),
        Row(RoutingFunction.Qa, unlimited, "Medium Medium main"),
        Row(RoutingFunction.Curation, unlimited, "Low Low helper"),
        Row(RoutingFunction.Curation, unlimited, "High Low main", risk = Risk(3, Reversibility.Easy, false)),
        Row(RoutingFunction.RepairHelper, unlimited, "Low Low helper"),
        Row(RoutingFunction.Deterministic, unlimited, "deterministic"),
        // Budgets: 10K context + 4K output per call; 10K tokens or 0.04 USD remain after reserves.
        Row(RoutingFunction.Implementing, tokens, "refused High NarrowUnit Checkpoint"),
        Row(RoutingFunction.Curation, tokens, "refused Low Checkpoint"),
        Row(RoutingFunction.Plan, dollars, "refused High NarrowUnit AskForChangedConstraint"),
        Row(RoutingFunction.ReviewRoutine, dollars, "refused Medium Checkpoint"),
        Row(RoutingFunction.RepairHelper, dollars, "Low Low helper"),
        Row(RoutingFunction.Curation, dollars, "refused High Checkpoint", risk = Risk(1, Reversibility.Hard, false)),
    )

    @Test
    fun `the routing table selects or refuses every function under fake profiles and budgets`() {
        for (row in rows) {
            val routed = Router().selectProfile(row.function, RoutingPacket(row.risk, 10_000, 4_000, previousTier = row.previous), null, RoutingPolicy(tiers, candidates, row.budget))
            val actual = when (routed) {
                is Routed.Selected -> "${routed.tier} ${routed.effort} ${routed.profile.id}"
                is Routed.Refused -> "refused ${routed.tier} ${routed.options.joinToString(" ")}"
                is Routed.Deterministic -> "deterministic"
            }
            assertEquals(row.expected, actual, row.toString())
        }
        // Every function has at least one row.
        assertEquals(RoutingFunction.entries.toSet(), rows.map { it.function }.toSet())
        assertEquals(Effort.High, assertIs<Routed.Selected>(Router().selectProfile(RoutingFunction.ReviewCritical, RoutingPacket(null, 1_000, 1_000), null, RoutingPolicy(tiers, candidates))).effort)
    }
}
