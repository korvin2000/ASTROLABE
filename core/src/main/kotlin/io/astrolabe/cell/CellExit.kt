package io.astrolabe.cell

import io.astrolabe.register.Register
import io.astrolabe.tool.state.BlockedRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a cell ended `partial` (§3.7, §5.9): each names the boundary it stopped at, so the controller can continue the same
 * increment. [wire] is the `partialReason` of the `cell.ended` event (A-D.6).
 */
public enum class PartialReason(public val wire: String) {
    /** The turn budget is spent (§5.9 turn budget). */
    TurnBudget("turn_budget"),

    /** The working tokens are spent and the reserves are not spendable on generation (§8.1 reserve). */
    TokenBudget("token_budget"),

    /** The reserve was reached with required checks outstanding (FX-43). */
    Reserve("reserve"),

    /** `tokens > α·C_max`: a P1 cell never summarises or rebuilds, it checkpoints and stops with a replan hint. */
    Pressure("pressure"),

    /** The completion seam refused past its limit: gaps the cell cannot close (§3.7 `cannot_progress`). */
    CompletionStalled("completion_stalled"),

    /**
     * A-D.6: a neutral end of a direct main-line cell — the same increment continues in a fresh cell, an epoch. Its
     * cause is [CellExit.Partial.handoffCause], never read from the hint.
     */
    Handoff("handoff"),
}

/** What ended a cell with [PartialReason.Handoff] (A-D.6). */
@Serializable
public enum class HandoffCause(public val wire: String) {
    /** Window pressure after one rebuild. */
    @SerialName("pressure")
    Pressure("pressure"),

    /** The turn budget was spent with work done in the epoch, in a loop that has no continuation of its own (`runS0`). */
    @SerialName("turn_budget")
    TurnBudget("turn_budget"),
}

/**
 * A completion whose acceptance waits for an authority (D-339): the work is done, [code] says what decision is
 * needed and [gaps] what it is about. The controller keeps the proposal as a pending completion.
 */
public data class PendingAcceptance(val code: io.astrolabe.verify.StopCode, val gaps: List<String>)

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
        /** Set when the proposal's acceptance awaits a decision (D-339); `null` when the completion seam accepted it. */
        val pending: PendingAcceptance? = null,
        /** Set when the model ended the task with an answer and the harness confirmed nothing changed (D-344). */
        val answer: String? = null,
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
        /** A-D.6: why the cell handed off; set exactly when [reason] is [PartialReason.Handoff]. */
        val handoffCause: HandoffCause? = null,
    ) : CellExit {
        /** The v1.0 constructor: no handoff cause. Kept for Java callers. */
        public constructor(turns: Int, register: Register, checkpoint: CellCheckpoint, packet: ResultPacket, reason: PartialReason, hint: String) :
            this(turns, register, checkpoint, packet, reason, hint, null)

        init {
            require((reason == PartialReason.Handoff) == (handoffCause != null)) { "a handoff cause is set exactly for a handoff" }
        }
    }

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
