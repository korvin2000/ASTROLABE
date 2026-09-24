package io.astrolabe.cell

import io.astrolabe.register.Register
import io.astrolabe.tool.state.BlockedRequest

/** Why a cell ended `partial` (§3.7, §5.9): each names the boundary it stopped at, so the controller can continue the same increment. */
public enum class PartialReason {
    /** The turn budget is spent (§5.9 turn budget). */
    TurnBudget,

    /** The working tokens are spent and the reserves are not spendable on generation (§8.1 reserve). */
    TokenBudget,

    /** The reserve was reached with required checks outstanding (FX-43). */
    Reserve,

    /** `tokens > α·C_max`: a P1 cell never summarises or rebuilds, it checkpoints and stops with a replan hint. */
    Pressure,

    /** The completion seam refused past its limit: gaps the cell cannot close (§3.7 `cannot_progress`). */
    CompletionStalled,
}

/**
 * How a cell ended (§3.7, invariant 11). Every exit carries the register in force, the checkpoint that was
 * persisted for it — there is no exit without one — the number of turns taken and the Result Packet (§5.9)
 * the controller verifies and commits from.
 */
public sealed interface CellExit {
    public val turns: Int
    public val register: Register
    public val checkpoint: CellCheckpoint
    public val packet: ResultPacket

    /**
     * The completion seam accepted the model's proposal against current evidence. `done` remains a proposal
     * (L7): the verifier accepts [packet]'s proposal and the controller commits the ledger (P1.9).
     */
    public data class Completed(
        override val turns: Int,
        override val register: Register,
        override val checkpoint: CellCheckpoint,
        override val packet: ResultPacket,
        /** The model's final text; a claim, never a packet field the runtime owns. */
        val text: String,
        val evidenceRefs: List<String>,
    ) : CellExit

    /** `state(blocked)` or `task.ask` without an answer: a success path that needs the authority (§5.9). */
    public data class Blocked(
        override val turns: Int,
        override val register: Register,
        override val checkpoint: CellCheckpoint,
        override val packet: ResultPacket,
        val request: BlockedRequest,
    ) : CellExit

    /** A coherent boundary with the work unfinished; [hint] tells the controller what to do with the same increment. */
    public data class Partial(
        override val turns: Int,
        override val register: Register,
        override val checkpoint: CellCheckpoint,
        override val packet: ResultPacket,
        val reason: PartialReason,
        val hint: String,
    ) : CellExit

    /** The provider, the admission or the harness failed; effects up to the checkpoint are recorded, nothing is retried here. */
    public data class Failed(
        override val turns: Int,
        override val register: Register,
        override val checkpoint: CellCheckpoint,
        override val packet: ResultPacket,
        val error: String,
    ) : CellExit

    /** Dispatch authority withdrew (cancellation, a superseded generation): distinct from every other outcome. */
    public data class Cancelled(
        override val turns: Int,
        override val register: Register,
        override val checkpoint: CellCheckpoint,
        override val packet: ResultPacket,
        val reason: String,
    ) : CellExit

    public val status: CellStatus
        get() = when (this) {
            is Completed -> CellStatus.Completed
            is Blocked -> CellStatus.Blocked
            is Partial -> CellStatus.Partial
            is Failed -> CellStatus.Failed
            is Cancelled -> CellStatus.Cancelled
        }
}
