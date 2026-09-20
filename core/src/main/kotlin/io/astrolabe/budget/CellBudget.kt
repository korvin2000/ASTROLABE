package io.astrolabe.budget

import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.event.Phase
import io.astrolabe.id.Identities

/** What a spend is for (§8.1 reserve): each reserve is spendable only on its purposes. */
public enum class Spend {
    /** Model output and the prompt it needs: never from a reserve. */
    Generation,

    /** An edit batch: never from a reserve. */
    Edit,

    /** A check run: working budget, then the verification reserve. */
    Check,

    /** The final register patch: working budget, then the verification reserve. */
    RegisterPatch,

    /** The Result Packet: working budget, then the recovery/persist reserve. */
    ResultPacket,

    /** A receipt: working budget, then the recovery/persist reserve. */
    Receipt,

    /** The STATUS note: working budget, then the recovery/persist reserve. */
    StatusNote,
    ;

    /** True for the spends the reserves exist for: they may run on reserve turns after the gate fired. */
    public val reportOrVerify: Boolean get() = this != Generation && this != Edit
}

/** The answer to "may this spend proceed?" */
public sealed interface Admission {
    /** Reserved across one or more partitions, in draw order; reconcile or release exactly once. */
    public class Admitted internal constructor(
        public val spend: Spend,
        public val estimate: Tokens,
        private val parts: List<Pair<CellBudget.Partition, Reservations.Reservation>>,
    ) : Admission {
        public val partitions: List<CellBudget.Partition> get() = parts.map { it.first }

        /** Commits [actual] against the reservations in draw order; an overrun lands on the last one. */
        public fun reconcile(actual: Tokens) {
            var remaining = actual.value
            parts.forEachIndexed { index, (_, reservation) ->
                val share = if (index == parts.lastIndex) remaining else minOf(remaining, reservation.amount.value)
                reservation.reconcile(Tokens(maxOf(0L, share)))
                remaining -= share
            }
        }

        public fun release() {
            parts.forEach { it.second.release() }
        }
    }

    public data class Refused(val spend: Spend, val estimate: Tokens, val reason: String) : Admission
}

/** What the cell must do when the working budget is gone (§5.9 reserve gate, FX-43). */
public data class ReserveVerdict(
    val reached: Boolean,
    /** The gate line for `[A]`, when the gate fired. */
    val gate: String?,
    /** The `partial` disposition naming the unverified scope, when checks are outstanding at the reserve. */
    val partial: String?,
)

/**
 * One cell's token and turn budget with its reserves held from cell start (§8.1 reserve, TODO P1.7.6):
 * `verification` (15 %, raised to known check costs) is spendable only on checks and the final register
 * patch; `recovery_and_persist` (5 %) only on the Result Packet, receipts and the STATUS note; generation
 * and edits draw on the working partition alone, so reserve tokens are never spent on generation. When
 * the working partition — tokens or turns — is exhausted the `reserve reached` gate blocks new edits and
 * generation ("verify and report"); reaching it with checks outstanding ends the cell `partial`, naming the
 * unverified scope (FX-43). Reservations are enforced across concurrent calls and reconciled to actual usage.
 */
public class CellBudget(
    public val tokens: Tokens,
    public val turns: Int,
    public val reserve: CellReserve,
    private val events: Events? = null,
    private val ids: Identities? = null,
) {
    public enum class Partition { Working, Verification, Recovery }

    init {
        require(turns > 0) { "turns must be positive" }
        require(tokens.value > reserve.tokens.value) { "the reserves (${reserve.tokens}) leave no working budget in $tokens" }
        require(turns > reserve.turns) { "the reserve turns (${reserve.turns}) leave no working turns in $turns" }
    }

    public val working: Reservations = Reservations(Tokens(tokens.value - reserve.tokens.value))
    public val verification: Reservations = Reservations(reserve.verificationTokens)
    public val recovery: Reservations = Reservations(reserve.recoveryTokens)

    private val lock = Any()
    private var turnsUsed = 0

    /** Turns spent so far, generation and reserve turns alike. */
    public val turnsTaken: Int get() = synchronized(lock) { turnsUsed }

    /** Generation turns left before the gate: the total minus the turns held in reserve. */
    public val generationTurnsLeft: Int get() = synchronized(lock) { maxOf(0, turns - reserve.turns - turnsUsed) }

    public val turnsLeft: Int get() = synchronized(lock) { maxOf(0, turns - turnsUsed) }

    /** The gate condition: no working tokens or no generation turns remain. */
    public val reserveReached: Boolean get() = working.available.value <= 0 || generationTurnsLeft <= 0

    /** Admits [estimate] tokens for [spend] from the partitions its purpose allows, splitting across them in order. */
    public fun admit(spend: Spend, estimate: Tokens): Admission {
        require(estimate.value >= 0) { "an estimate is never negative" }
        val order = when (spend) {
            Spend.Generation, Spend.Edit -> listOf(Partition.Working)
            Spend.Check, Spend.RegisterPatch -> listOf(Partition.Working, Partition.Verification)
            Spend.ResultPacket, Spend.Receipt, Spend.StatusNote -> listOf(Partition.Working, Partition.Recovery)
        }
        val available = order.sumOf { of(it).available.value }
        if (estimate.value > available) {
            val reason = when {
                spend == Spend.Generation || spend == Spend.Edit -> "reserve reached: ${estimate.value} tokens of ${spend.name.lowercase()} exceed the ${working.available.value} working tokens left; the reserves are not spendable on it"
                else -> "${estimate.value} tokens of ${spend.name.lowercase()} exceed the $available tokens left in ${order.joinToString("+") { it.name.lowercase() }}"
            }
            events?.let { e -> ids?.let { e.emit(AgentEvent.Budget.Exhausted(it, "cell ${spend.name.lowercase()}")) } }
            return Admission.Refused(spend, estimate, reason)
        }
        val parts = ArrayList<Pair<Partition, Reservations.Reservation>>()
        var remaining = estimate.value
        for (partition in order) {
            if (remaining <= 0 && parts.isNotEmpty()) break
            val pool = of(partition)
            val take = minOf(remaining, pool.available.value)
            if (take <= 0) continue // an exhausted partition contributes nothing, never an empty reservation
            val reservation = pool.reserve(Tokens(take), spend.name.lowercase())
                ?: continue // raced by a concurrent admission; the next partition or a refusal below
            parts += partition to reservation
            remaining -= take
            events?.let { e -> ids?.let { e.emit(AgentEvent.Budget.Reserved(it, reservation.id, take, "${spend.name.lowercase()} from ${partition.name.lowercase()}", Phase.Verify)) } }
        }
        if (remaining > 0) {
            parts.forEach { it.second.release() }
            return Admission.Refused(spend, estimate, "concurrent admissions took the tokens ${spend.name.lowercase()} needed")
        }
        return Admission.Admitted(spend, estimate, parts)
    }

    /**
     * Takes one turn for [spend]. Generation and edit turns come from the working turns; once those are gone,
     * only verify-and-report turns proceed, on the reserve turns, until the cell's turns are spent.
     */
    public fun startTurn(spend: Spend): Admission {
        synchronized(lock) {
            val generationLeft = turns - reserve.turns - turnsUsed
            val anyLeft = turns - turnsUsed
            return when {
                anyLeft <= 0 -> Admission.Refused(spend, Tokens.ZERO, "turn budget exhausted: $turns turns spent")
                generationLeft > 0 -> {
                    turnsUsed++
                    Admission.Admitted(spend, Tokens.ZERO, emptyList())
                }
                spend.reportOrVerify -> {
                    turnsUsed++
                    Admission.Admitted(spend, Tokens.ZERO, emptyList())
                }
                else -> Admission.Refused(spend, Tokens.ZERO, "reserve reached: the remaining $anyLeft turns are held for verification and reporting")
            }
        }
    }

    /** The gate and, with [outstandingChecks], the `partial` disposition (FX-43). */
    public fun verdict(outstandingChecks: Collection<String> = emptyList()): ReserveVerdict {
        if (!reserveReached) return ReserveVerdict(false, null, null)
        val partial = if (outstandingChecks.isEmpty()) null else "partial: reserve reached with checks outstanding — unverified: ${outstandingChecks.joinToString(", ")}"
        return ReserveVerdict(true, GATE, partial)
    }

    /** Gauge inputs (§5.7): percent of the cell's tokens committed and whether the working budget still holds. */
    public fun snapshot(): Snapshot = Snapshot(
        spentTokens = Tokens(working.spent.value + verification.spent.value + recovery.spent.value),
        percentUsed = (((working.spent.value + verification.spent.value + recovery.spent.value) * 100) / tokens.value).toInt(),
        reserveOk = !reserveReached,
        turnsTaken = turnsTaken,
    )

    public data class Snapshot(val spentTokens: Tokens, val percentUsed: Int, val reserveOk: Boolean, val turnsTaken: Int)

    private fun of(partition: Partition): Reservations = when (partition) {
        Partition.Working -> working
        Partition.Verification -> verification
        Partition.Recovery -> recovery
    }

    public companion object {
        /** The §5.9 gate line. */
        public const val GATE: String = "reserve reached: verify and report; no new edits"

        /** A cell budget from the cell's share of tokens and turns, with the reserves of [reserves] (raised to [knownCheckCostTokens]). */
        @JvmStatic
        @JvmOverloads
        public fun of(cellTokens: Tokens, cellTurns: Int, reserves: Reserves, knownCheckCostTokens: Tokens = Tokens.ZERO, events: Events? = null, ids: Identities? = null): CellBudget =
            CellBudget(cellTokens, cellTurns, Reserve.cell(cellTokens, cellTurns, reserves, knownCheckCostTokens), events, ids)
    }
}
