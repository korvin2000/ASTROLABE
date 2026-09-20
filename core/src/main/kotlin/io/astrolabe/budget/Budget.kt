package io.astrolabe.budget

import io.astrolabe.Defaults
import io.astrolabe.provider.Money
import kotlinx.serialization.Serializable

/** Reserve fractions of a cell (§8.1): verification 15 %, recovery/persist 5 % of tokens and turns. */
@Serializable
public data class Reserves(
    val verification: Double = 0.15,
    val recoveryAndPersist: Double = 0.05,
) {
    init {
        require(verification > 0.0 && recoveryAndPersist > 0.0 && verification + recoveryAndPersist < 1.0) {
            "reserves must be positive fractions leaving room for work"
        }
    }
}

/** Contract budget (§4.1): every child, retry, rebuild, review and verification consumes it (invariant 10). */
@Serializable
public data class Budget(
    val cells: Int,
    val turnsPerCell: Int,
    val tokens: Tokens,
    val attempts: Int,
    val reserves: Reserves = Reserves(),
    val cost: Money? = null,
) {
    init {
        require(cells > 0 && turnsPerCell > 0 && attempts > 0) { "cells, turnsPerCell and attempts must be positive" }
    }

    public companion object {
        @JvmStatic
        public fun of(defaults: Defaults, tokens: Tokens, cost: Money? = null): Budget = Budget(
            cells = defaults.campaignCells,
            turnsPerCell = defaults.turnsPerCell,
            tokens = tokens,
            attempts = defaults.attemptsPerIncrement,
            reserves = Reserves(defaults.reserveVerification, defaults.reserveRecoveryAndPersist),
            cost = cost,
        )
    }
}

/** What a cell holds back (§8.1); computed once at cell start and raised to known check costs. */
@Serializable
public data class CellReserve(
    val verificationTokens: Tokens,
    val verificationTurns: Int,
    val recoveryTokens: Tokens,
    val recoveryTurns: Int,
) {
    val tokens: Tokens get() = verificationTokens + recoveryTokens
    val turns: Int get() = verificationTurns + recoveryTurns
}

public object Reserve {
    /**
     * Cell reserve: `verification` and `recoveryAndPersist` fractions of the cell's tokens and turns, with the
     * verification part raised to [knownCheckCostTokens] when the scheduler already knows what checks cost.
     */
    @JvmStatic
    public fun cell(cellTokens: Tokens, cellTurns: Int, reserves: Reserves, knownCheckCostTokens: Tokens = Tokens.ZERO): CellReserve {
        require(cellTurns > 0) { "cellTurns must be positive" }
        val verification = Tokens.max(cellTokens.fraction(reserves.verification), knownCheckCostTokens)
        return CellReserve(
            verificationTokens = verification,
            verificationTurns = turns(cellTurns, reserves.verification),
            recoveryTokens = cellTokens.fraction(reserves.recoveryAndPersist),
            recoveryTurns = turns(cellTurns, reserves.recoveryAndPersist),
        )
    }

    /** Campaign recovery reserve (§17): a fraction of the campaign tokens held for recovery. */
    @JvmStatic
    public fun campaign(campaignTokens: Tokens, fraction: Double): Tokens = campaignTokens.fraction(fraction)

    private fun turns(total: Int, fraction: Double): Int = maxOf(1, kotlin.math.ceil(total * fraction).toInt())
}
