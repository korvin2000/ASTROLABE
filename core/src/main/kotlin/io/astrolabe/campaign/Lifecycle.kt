package io.astrolabe.campaign

import io.astrolabe.cell.CellCheckpoint
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.PartialReason
import io.astrolabe.contract.Contract
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.verify.CompletionResult
import kotlinx.serialization.Serializable

/** Campaign-level outcomes (§5.9, invariant 11): distinct from each other, and only [Completed] is a supported state. */
@Serializable
public enum class CampaignOutcome(public val wire: String) {
    Completed("completed"),
    WaitingForProcess("waiting_for_process"),
    WaitingForInput("waiting_for_input"),
    BlockedExternal("blocked_external"),
    BudgetExhausted("budget_exhausted"),
    Cancelled("cancelled"),
    Failed("failed"),
    ;

    /** The campaign stopped on something outside itself — a process, the user, a third party — so a reopen may resume it. */
    public val resumable: Boolean get() = this == WaitingForProcess || this == WaitingForInput || this == BlockedExternal
}

/** Where a campaign stands in `campaign()` (§3.7). */
@Serializable
public enum class CampaignPhase {
    /** Contract, workspace and store are open; pending actions are unreconciled, so nothing may dispatch (§13.1). */
    Opened,

    /** Cells are dispatched and verified outcomes committed. */
    Running,

    /** Every requirement is verified at one stamp; final acceptance runs (§3.7 `finish`). */
    Finishing,

    /** An outcome is recorded. */
    Ended,
}

/** The controller's standing of one dispatched cell; the run itself belongs to the cell runtime. */
@Serializable
public data class CellState(val cell: ContextId, val increment: String, val status: CellStatus)

/**
 * The one state machine of the controller (§3.2) over typed records, persisted with the ledger. A state is made
 * only by [Lifecycle.open] and advanced only by [Lifecycle.apply]: every transition names the receipt or packet
 * that supports it, so an increment is verified only by an accepted completion of its latest completed cell —
 * never by a partial, blocked or failed one — and `completed` only by final acceptance over a fully verified
 * ledger (invariant 11, L7).
 */
@Serializable
@ConsistentCopyVisibility
public data class CampaignState private constructor(
    val work: WorkId,
    val attempt: AttemptId,
    val contractVersion: Int,
    val phase: CampaignPhase,
    val graph: RequirementGraph,
    val ledger: Ledger,
    /** Dispatched cells in dispatch order. */
    val cells: List<CellState>,
    val outcome: CampaignOutcome?,
    val reason: String?,
    /** The number of applied transitions; a store save must extend the stored state by exactly one. */
    val seq: Long,
) {
    init {
        require(seq >= 0 && contractVersion >= 1) { "seq ≥ 0 and a committed contract version" }
        require((phase == CampaignPhase.Ended) == (outcome != null)) { "an outcome exactly when the campaign ended" }
        require(outcome != CampaignOutcome.Completed || ledger.unfinished().isEmpty()) { "completed needs a verified ledger" }
        require(cells.count { it.status == CellStatus.Running } <= 1) { "one running cell per campaign (S0 single writer)" }
        require(cells.map { it.cell }.toSet().size == cells.size) { "a cell is dispatched once" }
    }

    /** The cell in flight, if any; no terminal transition is legal while one runs. */
    val running: CellState? get() = cells.firstOrNull { it.status == CellStatus.Running }

    internal fun next(
        phase: CampaignPhase = this.phase,
        graph: RequirementGraph = this.graph,
        ledger: Ledger = this.ledger,
        cells: List<CellState> = this.cells,
        outcome: CampaignOutcome? = this.outcome,
        reason: String? = this.reason,
        contractVersion: Int = this.contractVersion,
    ): CampaignState = CampaignState(work, attempt, contractVersion, phase, graph, ledger, cells, outcome, reason, seq + 1)

    internal companion object {
        fun opened(contract: Contract, graph: RequirementGraph): CampaignState = CampaignState(
            contract.workId, contract.attemptId, contract.version, CampaignPhase.Opened, graph,
            Ledger.initial(contract), emptyList(), null, null, 0,
        )
    }
}

/** A typed transition of [CampaignState]: each carries the record that supports it. */
public sealed interface Transition {
    /** Open-time reconciliation finished (§13.1): [unknownOutcomes] are intents a retry must not duplicate. */
    public data class Reconciled(val unknownOutcomes: List<String> = emptyList()) : Transition

    /** A cell was dispatched on a ready increment. */
    public data class Dispatched(val increment: String, val cell: ContextId) : Transition

    /** The running cell handed back its Result Packet (§5.9). */
    public data class Returned(val exit: CellExit) : Transition

    /**
     * The running cell was cancelled mid-call and settled [checkpoint] without handing back a packet (§3.7
     * cancellation, D-26): the checkpoint is the record, and the cell is `cancelled`, never completed.
     */
    public data class Interrupted(val checkpoint: CellCheckpoint) : Transition {
        init {
            require(checkpoint.status == CellStatus.Cancelled) { "only a cancelled checkpoint interrupts a cell" }
        }
    }

    /**
     * The controller that ran [cell] stopped while it ran (a process death): a reopen records the cell as failed
     * from its last persisted [checkpoint] (or none), after reconciling the tree and the open intents (§13.1). The
     * increment stays open for a new cell; nothing of the lost cell is verified.
     */
    public data class Lost(val cell: ContextId, val checkpoint: CellCheckpoint?) : Transition {
        init {
            require(checkpoint == null || checkpoint.cell == cell) { "the checkpoint belongs to ${checkpoint?.cell}, not $cell" }
        }
    }

    /** The verifier accepted a completion and the tree is still [stampNow] (§3.7 `commit_outcome_if_current`). */
    public data class Committed(val result: CompletionResult.Accepted, val stampNow: CandidateId) : Transition

    /** The authority answered or amended; the blocked increment resumes. */
    public data class Unblocked(val increment: String, val authorityRef: String) : Transition {
        init {
            require(authorityRef.isNotBlank()) { "an unblock names its authority" }
        }
    }

    /** The authority withdrew an increment; its history stays. */
    public data class IncrementCancelled(val increment: String, val reason: String) : Transition

    /** Every requirement is verified at [stamp]: final acceptance begins. */
    public data class Finishing(val stamp: CandidateId) : Transition

    /** Final acceptance held at [stamp] with [receipts] (§3.7 `finish`); the only way to `completed`. */
    public data class Finished(val stamp: CandidateId, val receipts: List<String>) : Transition {
        init {
            require(receipts.isNotEmpty()) { "completion is a receipt about a stamped candidate (L7)" }
        }
    }

    /** An honest non-completed outcome, never disguised as completion (invariant 11). */
    public data class Stopped(val outcome: CampaignOutcome, val reason: String) : Transition {
        init {
            require(outcome != CampaignOutcome.Completed) { "completed is reached only through Finished" }
            require(reason.isNotBlank()) { "a stop records why" }
        }
    }

    /** A reopened campaign that stopped on something outside it starts over at reconciliation. */
    public data class Resumed(val reason: String) : Transition
}

/** What the controller does with a returned cell (§3.7 `dispatch_outcome`). */
public sealed interface Disposition {
    /** Commit [accepted] if current, which closes the increment. */
    public data class Close(val accepted: CompletionResult.Accepted) : Disposition

    /**
     * The same increment continues in a new cell with its register and seeds, without a budget reset. [fallback]
     * is the honest outcome where the shape has no continuation cell: S0 in P1 (continuation cells are P2).
     */
    public data class Continue(val reason: String, val fallback: CampaignOutcome) : Disposition

    /** The campaign ends with [outcome]. */
    public data class Stop(val outcome: CampaignOutcome, val reason: String) : Disposition
}

/** Pure transition and disposition functions of the controller's state machine (§3.2, §3.7, §5.9). */
public object Lifecycle {
    /** A fresh campaign over a validated graph; an invalid plan never becomes a campaign. */
    @JvmStatic
    public fun open(contract: Contract, graph: RequirementGraph): CampaignState {
        val issues = graph.validate(contract)
        require(issues.isEmpty()) { "cannot open a campaign over an invalid graph: ${issues.joinToString { it.detail }}" }
        return CampaignState.opened(contract, graph)
    }

    /** Applies [transition] or throws [IllegalStateException]: an unsupported transition never yields a state. */
    @JvmStatic
    public fun apply(state: CampaignState, contract: Contract, transition: Transition): CampaignState {
        require(contract.workId == state.work && contract.attemptId == state.attempt) { "the contract belongs to another campaign" }
        require(contract.version >= state.contractVersion) { "contract v${contract.version} is older than v${state.contractVersion}" }
        val s = state
        val v = contract.version
        return when (transition) {
            is Transition.Reconciled -> {
                expect(s, CampaignPhase.Opened)
                s.next(phase = CampaignPhase.Running, contractVersion = v)
            }
            is Transition.Dispatched -> {
                expect(s, CampaignPhase.Running)
                check(s.running == null) { "cell ${s.running?.cell} is still running" }
                val graph = s.graph.continueIncrement(contract, transition.increment, transition.cell)
                s.next(graph = graph, cells = s.cells + CellState(transition.cell, transition.increment, CellStatus.Running), contractVersion = v)
            }
            is Transition.Returned -> {
                expect(s, CampaignPhase.Running)
                val exit = transition.exit
                val cell = checkNotNull(exit.packet.ids.context)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == cell && running.increment == exit.packet.increment) { "packet of $cell is not the running cell ${running.cell}" }
                val graph = if (exit is CellExit.Blocked) s.graph.block(running.increment, cell) else s.graph
                s.next(graph = graph, cells = s.cells.map { if (it.cell == cell) it.copy(status = exit.status) else it }, contractVersion = v)
            }
            is Transition.Lost -> {
                expect(s, CampaignPhase.Running)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == transition.cell) { "${transition.cell} is not the running cell ${running.cell}" }
                s.next(cells = s.cells.map { if (it.cell == running.cell) it.copy(status = CellStatus.Failed) else it }, contractVersion = v)
            }
            is Transition.Interrupted -> {
                expect(s, CampaignPhase.Running)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == transition.checkpoint.cell) { "checkpoint of ${transition.checkpoint.cell} is not the running cell ${running.cell}" }
                s.next(cells = s.cells.map { if (it.cell == running.cell) it.copy(status = CellStatus.Cancelled) else it }, contractVersion = v)
            }
            is Transition.Committed -> {
                expect(s, CampaignPhase.Running)
                val result = transition.result
                check(result.resultingStamp == transition.stampNow) { "completion is about @${result.resultingStamp.hash8}, the tree is @${transition.stampNow.hash8}" }
                val cell = s.cells.lastOrNull { it.increment == result.incrementId }
                check(cell != null && cell.cell == result.contextId && cell.status == CellStatus.Completed) {
                    "only a completed latest cell's proposal commits; ${result.incrementId}'s latest is ${cell?.status}"
                }
                val graph = s.graph.recordAccepted(contract, result)
                s.next(graph = graph, ledger = graph.ledger(contract, transition.stampNow), contractVersion = v)
            }
            is Transition.Unblocked -> {
                expect(s, CampaignPhase.Opened, CampaignPhase.Running)
                s.next(graph = s.graph.unblock(transition.increment), contractVersion = v)
            }
            is Transition.IncrementCancelled -> {
                expect(s, CampaignPhase.Opened, CampaignPhase.Running)
                check(s.running?.increment != transition.increment) { "${transition.increment} has a running cell; cancel the cell first" }
                s.next(graph = s.graph.cancel(transition.increment, transition.reason), contractVersion = v)
            }
            is Transition.Finishing -> {
                expect(s, CampaignPhase.Running)
                check(s.running == null) { "cell ${s.running?.cell} is still running" }
                val ledger = verifiedLedger(s, contract, transition.stamp)
                s.next(phase = CampaignPhase.Finishing, ledger = ledger, contractVersion = v)
            }
            is Transition.Finished -> {
                expect(s, CampaignPhase.Finishing)
                val ledger = verifiedLedger(s, contract, transition.stamp)
                s.next(phase = CampaignPhase.Ended, ledger = ledger, outcome = CampaignOutcome.Completed, reason = null, contractVersion = v)
            }
            is Transition.Stopped -> {
                expect(s, CampaignPhase.Opened, CampaignPhase.Running, CampaignPhase.Finishing)
                check(s.running == null) { "reconcile the running cell ${s.running?.cell} before a terminal outcome" }
                s.next(phase = CampaignPhase.Ended, outcome = transition.outcome, reason = transition.reason, contractVersion = v)
            }
            is Transition.Resumed -> {
                expect(s, CampaignPhase.Ended)
                check(s.outcome?.resumable == true) { "a ${s.outcome?.wire} campaign does not resume" }
                s.next(phase = CampaignPhase.Opened, outcome = null, reason = null, contractVersion = v)
            }
        }
    }

    /**
     * The §3.7 `dispatch_outcome` table. Only a completed cell whose proposal the verifier accepted closes its
     * increment; `partial` is never verified. A cell budget stop continues the same increment where the shape
     * can, and otherwise ends `budget_exhausted` (D-64).
     */
    @JvmStatic
    public fun disposition(exit: CellExit, completion: CompletionResult?): Disposition {
        if (completion is CompletionResult.Accepted) {
            require(exit is CellExit.Completed) { "only a completed cell's proposal can be accepted; this one is ${exit.status}" }
            require(completion.incrementId == exit.packet.increment) { "the completion is for another increment" }
        }
        return when (exit) {
            is CellExit.Completed -> when (completion) {
                is CompletionResult.Accepted -> Disposition.Close(completion)
                is CompletionResult.Refused ->
                    if (completion.recoveryDirected) {
                        Disposition.Stop(CampaignOutcome.Failed, "completion unsupported after ${completion.attempts} finalizations: ${completion.missing.joinToString("; ")}")
                    } else {
                        Disposition.Continue("completion refused: ${completion.missing.joinToString("; ")}", CampaignOutcome.Failed)
                    }
                is CompletionResult.NotCompleted, null -> throw IllegalArgumentException("a completed cell's done proposal must be verified first")
            }
            is CellExit.Blocked ->
                if (exit.request.question != null) Disposition.Stop(CampaignOutcome.WaitingForInput, exit.request.reason)
                else Disposition.Stop(CampaignOutcome.BlockedExternal, exit.request.reason)
            is CellExit.Partial -> Disposition.Continue(
                "${exit.reason}: ${exit.hint}",
                when (exit.reason) {
                    PartialReason.TurnBudget, PartialReason.TokenBudget, PartialReason.Reserve -> CampaignOutcome.BudgetExhausted
                    PartialReason.Pressure, PartialReason.CompletionStalled -> CampaignOutcome.Failed
                },
            )
            is CellExit.Failed -> Disposition.Stop(CampaignOutcome.Failed, exit.error)
            is CellExit.Cancelled -> Disposition.Stop(CampaignOutcome.Cancelled, exit.reason)
        }
    }

    private fun expect(state: CampaignState, vararg phases: CampaignPhase) =
        check(state.phase in phases) { "illegal in ${state.phase}; expected ${phases.joinToString()}" }

    private fun verifiedLedger(state: CampaignState, contract: Contract, stamp: CandidateId): Ledger {
        check(contract.requirements.isNotEmpty()) { "an empty contract is never completed" }
        val ledger = state.graph.ledger(contract, stamp)
        val unfinished = ledger.entries.values.filter { !it.stampValid }.map { it.requirementId }
        check(unfinished.isEmpty()) { "requirements not verified at @${stamp.hash8}: $unfinished" }
        check(state.graph.increments.none { it.status == IncrementStatus.Blocked || it.status == IncrementStatus.InProgress }) {
            "an increment is still open"
        }
        return ledger
    }
}
