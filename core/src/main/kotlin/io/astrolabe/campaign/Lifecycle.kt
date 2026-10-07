package io.astrolabe.campaign

import io.astrolabe.cell.CellCheckpoint
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.PartialReason
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Ledger
import io.astrolabe.contract.LedgerEntry
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.WorkId
import io.astrolabe.verify.CompletionResult
import io.astrolabe.verify.StopCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Campaign-level outcomes (§5.9, invariant 11): distinct from each other, and only [Completed] is a supported state. */
@Serializable
public enum class CampaignOutcome(public val wire: String) {
    Completed("completed"),

    /** The request needed no change and the model answered it; the harness confirmed the tree unchanged (D-344). Not `completed`. */
    Answered("answered"),
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

/**
 * Why a campaign ended `budget_exhausted`, machine-readable (C3): which ceiling stopped it and whether a reopen may
 * continue the same attempt — a task limit the host raises, or the per-run cell cap; a hidden technical ceiling is never a
 * dead end without saying so. JSON carries [wire] (C14); a state written before reads back by the constant's name.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public enum class BudgetStop(public val wire: String) {
    @SerialName("task_limit_money") @JsonNames("TaskLimitMoney")
    TaskLimitMoney("task_limit_money"),

    @SerialName("task_limit_minutes") @JsonNames("TaskLimitMinutes")
    TaskLimitMinutes("task_limit_minutes"),

    @SerialName("task_limit_requests") @JsonNames("TaskLimitRequests")
    TaskLimitRequests("task_limit_requests"),

    /** `Controller.run(maxCells)`: counted per run, so a reopen runs with a fresh cap; also a spent handoff grant (A-D.6), renewed by the reopen. */
    @SerialName("cell_cap") @JsonNames("CellCap")
    CellCap("cell_cap"),

    /**
     * The contract's own budget — tokens, turns per cell, its money — kept with the contract (§4.1). What spent it is
     * [CampaignState.contractStop] (C14), and only that cause says whether a reopen may continue ([ContractBudgetCause.resumable]).
     */
    @SerialName("contract_budget") @JsonNames("ContractBudget")
    ContractBudget("contract_budget"),
    ;

    /** A task limit stop: a reopen continues it once the host's limits leave room. */
    public val taskLimit: Boolean get() = this == TaskLimitMoney || this == TaskLimitMinutes || this == TaskLimitRequests

    /** The task limit this stop names (the inverse of [of]); `null` for the cell cap and the contract budget (C14). */
    public val limit: io.astrolabe.budget.LimitKind?
        get() = when (this) {
            TaskLimitMoney -> io.astrolabe.budget.LimitKind.Cost
            TaskLimitMinutes -> io.astrolabe.budget.LimitKind.Minutes
            TaskLimitRequests -> io.astrolabe.budget.LimitKind.Requests
            CellCap, ContractBudget -> null
        }

    /**
     * A reopen may continue the attempt whatever stopped it: a raised task limit, or a fresh per-run cell cap. A
     * [ContractBudget] stop is resumable only by its cause ([ContractBudgetCause.resumable], C14), so it is not here.
     */
    public val resumable: Boolean get() = this != ContractBudget

    public companion object {
        @JvmStatic
        public fun of(kind: io.astrolabe.budget.LimitKind): BudgetStop = when (kind) {
            io.astrolabe.budget.LimitKind.Cost -> TaskLimitMoney
            io.astrolabe.budget.LimitKind.Minutes -> TaskLimitMinutes
            io.astrolabe.budget.LimitKind.Requests -> TaskLimitRequests
        }
    }
}

/** What spent the contract's budget in a [BudgetStop.ContractBudget] stop (C14), and whether a reopen may continue it. */
@Serializable
public enum class ContractBudgetCause(public val wire: String) {
    /** The contract's tokens: a reopen whose `CampaignPolicy.tokens` raised them so they leave room continues. */
    @SerialName("tokens")
    Tokens("tokens"),

    /** A cell's turns (`turnsPerCell`): a reopen continues in a new cell with a fresh turn budget, while tokens are left. */
    @SerialName("turns")
    Turns("turns"),

    /** The contract's money: it does not follow the policy on a reopen, so the stop holds and the contract is kept. */
    @SerialName("cost")
    Cost("cost"),

    /** A call whose tokens are unknown counts as the whole budget, so no raise leaves room: the stop holds. */
    @SerialName("unknown_usage")
    UnknownUsage("unknown_usage"),
    ;

    /** A reopen may continue a stop of this cause: tokens (once raised) and turns. */
    public val resumable: Boolean get() = this == Tokens || this == Turns
}

/** A [BudgetStop.ContractBudget] stop (C14): its [cause] and the contract's [tokens] when it stopped. */
@Serializable
public data class ContractBudgetStop(val cause: ContractBudgetCause, val tokens: Long)

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
    /** Why an ended campaign waits for a person, machine-readable (D-339); `null` for every other stop. */
    val stopCode: StopCode? = null,
    /** Which ceiling ended a `budget_exhausted` campaign and whether a reopen continues it (C3); `null` otherwise. */
    val budgetStop: BudgetStop? = null,
    /** What spent the contract's budget in a [BudgetStop.ContractBudget] stop (C14); `null` otherwise, and for a stop before C14. */
    val contractStop: ContractBudgetStop? = null,
    /**
     * How many of the contract's messages the last dispatched cell was compiled with (task-workflow §2.4 A): a later
     * `continuation` or `steering` message that finds every increment closed gets a response increment of its own.
     */
    val messagesSeen: Int = 0,
    /**
     * A `failed` stop a reopen continues (WF-10, task-workflow §1.3): a cell failed on an exception — the cell is
     * `failed`, the work is not, and the next open resumes its increment from its carry.
     */
    val failedResumably: Boolean = false,
) {
    init {
        require(!failedResumably || outcome == CampaignOutcome.Failed) { "only a failed stop is resumed as a failure" }
        require(budgetStop == null || outcome == CampaignOutcome.BudgetExhausted) { "a budget stop code marks budget_exhausted only" }
        require(contractStop == null || budgetStop == BudgetStop.ContractBudget) { "a contract budget cause marks a contract budget stop only" }
        require(seq >= 0 && contractVersion >= 1) { "seq ≥ 0 and a committed contract version" }
        require((phase == CampaignPhase.Ended) == (outcome != null)) { "an outcome exactly when the campaign ended" }
        require(outcome != CampaignOutcome.Completed || ledger.unfinished().isEmpty()) { "completed needs a verified ledger" }
        require(cells.count { it.status == CellStatus.Running } <= 1) { "one running cell per campaign (S0 single writer)" }
        require(cells.map { it.cell }.toSet().size == cells.size) { "a cell is dispatched once" }
        require(stopCode == null || outcome == CampaignOutcome.WaitingForInput) { "a stop code marks a campaign waiting for input" }
    }

    /** The cell in flight, if any; no terminal transition is legal while one runs. */
    val running: CellState? get() = cells.firstOrNull { it.status == CellStatus.Running }

    /** The campaign stopped on something a reopen may get past: a resumable outcome or a cell's failure (WF-10). */
    val resumable: Boolean get() = outcome?.resumable == true || failedResumably

    internal fun next(
        phase: CampaignPhase = this.phase,
        graph: RequirementGraph = this.graph,
        ledger: Ledger = this.ledger,
        cells: List<CellState> = this.cells,
        outcome: CampaignOutcome? = this.outcome,
        reason: String? = this.reason,
        contractVersion: Int = this.contractVersion,
        stopCode: StopCode? = this.stopCode,
        budgetStop: BudgetStop? = this.budgetStop,
        contractStop: ContractBudgetStop? = this.contractStop,
        messagesSeen: Int = this.messagesSeen,
        failedResumably: Boolean = this.failedResumably,
    ): CampaignState = CampaignState(work, attempt, contractVersion, phase, graph, ledger, cells, outcome, reason, seq + 1, stopCode, budgetStop, contractStop, messagesSeen, failedResumably)

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

    /**
     * A cell was dispatched on a ready increment. [epoch]: it continues a handoff (A-D.6), counted in `Sizing.handoffs`
     * instead of `Sizing.continuations`.
     */
    public data class Dispatched(val increment: String, val cell: ContextId, val epoch: Boolean = false) : Transition {
        /** The v1.0 constructor: not an epoch. Kept for Java callers. */
        public constructor(increment: String, cell: ContextId) : this(increment, cell, false)
    }

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

    /**
     * The controller installs a validated plan (§4.2, P2.2.2): the plan cell's graph, or a replan that keeps every
     * increment already dispatched, verified or cancelled exactly as it was (authorized coverage preserved).
     */
    public data class Planned(val graph: RequirementGraph, val replaced: Set<String> = emptySet()) : Transition

    /** The authority answered or amended; the blocked increment resumes. */
    public data class Unblocked(val increment: String, val authorityRef: String) : Transition {
        init {
            require(authorityRef.isNotBlank()) { "an unblock names its authority" }
        }
    }

    /** The authority withdrew an increment; its history stays. */
    public data class IncrementCancelled(val increment: String, val reason: String) : Transition

    /**
     * An explicit amendment by [requestId] raised the contract to [version] (task-workflow §2.4 B): its requirements get
     * [increments] — each depending on every verified increment — added to the graph, which still validates. Verified
     * increments are never reopened; their receipts stay regression obligations.
     */
    public data class Amended(val version: Int, val requestId: String, val increments: List<Increment>) : Transition {
        init {
            require(requestId.isNotBlank() && increments.isNotEmpty()) { "an amendment names its message and the work it derives" }
        }
    }

    /**
     * A `continuation` or `steering` message [requestId] found every increment closed (task-workflow §2.4 A): it gets
     * [increment], which resolves it — no revision and no requirement of its own.
     */
    public data class ResponseOpened(val requestId: String, val increment: Increment) : Transition {
        init {
            require(requestId.isNotBlank()) { "a response increment names its message" }
        }
    }

    /** Every requirement is verified at [stamp]: final acceptance begins. */
    public data class Finishing(val stamp: CandidateId) : Transition

    /**
     * The model answered a request that needed no change (D-344): the tree is still the campaign's snapshot 0 at
     * [stamp] and no effect ran. The only way to `answered`; the requirements stay unverified.
     */
    public data class Answered(val stamp: CandidateId, val text: String) : Transition {
        init {
            require(text.isNotBlank()) { "an answer has text" }
        }
    }

    /** Final acceptance held at [stamp] with [receipts] (§3.7 `finish`); the only way to `completed`. */
    public data class Finished(val stamp: CandidateId, val receipts: List<String>) : Transition {
        init {
            require(receipts.isNotEmpty()) { "completion is a receipt about a stamped candidate (L7)" }
        }
    }

    /**
     * An honest non-completed outcome, never disguised as completion (invariant 11). [code] says, for a host, why a
     * campaign waits for a person (D-339); it marks `waiting_for_input` only.
     */
    public data class Stopped @JvmOverloads constructor(
        val outcome: CampaignOutcome,
        val reason: String,
        val code: StopCode? = null,
        /** C3: the ceiling a `budget_exhausted` stop hit. */
        val budget: BudgetStop? = null,
        /** C14: what spent the contract's budget, for a [BudgetStop.ContractBudget] stop. */
        val contract: ContractBudgetStop? = null,
        /** WF-10: a `failed` stop on a cell's exception, which a reopen continues (task-workflow §1.3). */
        val resumable: Boolean = false,
    ) : Transition {
        init {
            require(!resumable || outcome == CampaignOutcome.Failed) { "only a failed stop is marked resumable; the other outcomes say it themselves" }
            require(budget == null || outcome == CampaignOutcome.BudgetExhausted) { "a budget stop code marks budget_exhausted only" }
            require(contract == null || budget == BudgetStop.ContractBudget) { "a contract budget cause marks a contract budget stop only" }
            require(outcome != CampaignOutcome.Completed) { "completed is reached only through Finished" }
            require(outcome != CampaignOutcome.Answered) { "answered is reached only through Answered" }
            require(reason.isNotBlank()) { "a stop records why" }
            require(code == null || outcome == CampaignOutcome.WaitingForInput) { "a stop code marks waiting_for_input only" }
        }
    }

    /** A reopened campaign that stopped on something outside it starts over at reconciliation. */
    public data class Resumed(val reason: String) : Transition

    /**
     * C3 (plan §4.6): a campaign stopped `budget_exhausted` on a resumable [BudgetStop] — a task limit the host has raised
     * so it leaves room again, the per-run cell cap — or on a contract budget of a resumable cause (C14: tokens the host
     * raised, a cell's turns) is reopened and continues the same attempt from reconciliation, its verified ledger kept.
     * Only the host raises a limit: the controller applies this at open, on the host's limits and policy.
     */
    public data class LimitRaised(val reason: String) : Transition {
        init {
            require(reason.isNotBlank()) { "a raised limit records why" }
        }
    }
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

    /** The campaign ends with [outcome]; [code] is the machine-readable reason a person is waited for (D-339). */
    public data class Stop @JvmOverloads constructor(val outcome: CampaignOutcome, val reason: String, val code: StopCode? = null) : Disposition
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

    /**
     * WD-05: how a run stopped by an unreadable input settles the [cell] still running. A cancelled [checkpoint] is an
     * interruption; any other — the cell returned but its exit was never applied, or it settled nothing — is a lost
     * cell, the same record a reopen writes, so the stop that follows finds no running cell.
     */
    internal fun unreadableSettlement(cell: ContextId, checkpoint: CellCheckpoint?): Transition =
        if (checkpoint?.status == CellStatus.Cancelled) Transition.Interrupted(checkpoint) else Transition.Lost(cell, checkpoint)

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
                val graph = s.graph.continueIncrement(contract, transition.increment, transition.cell, transition.epoch)
                s.next(graph = graph, cells = s.cells + CellState(transition.cell, transition.increment, CellStatus.Running), contractVersion = v,
                    messagesSeen = contract.requests.size)
            }
            is Transition.Returned -> {
                expect(s, CampaignPhase.Running)
                val exit = transition.exit
                val cell = checkNotNull(exit.packet.ids.context)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == cell && running.increment == exit.packet.increment) { "packet of $cell is not the running cell ${running.cell}" }
                // §3.8: every pressure rebuild in the cell and a terminating pressure stop are decomposition failures.
                val rebuilt = exit.checkpoint.rebuilds + if (exit is CellExit.Partial && exit.reason == PartialReason.Pressure) 1 else 0
                val sized = s.graph.recordCell(running.increment, cell, exit.turns, exit.checkpoint.touched, rebuilt)
                val graph = if (exit is CellExit.Blocked) sized.block(running.increment, cell) else sized
                s.next(graph = graph, cells = s.cells.map { if (it.cell == cell) it.copy(status = exit.status) else it }, contractVersion = v)
            }
            is Transition.Lost -> {
                expect(s, CampaignPhase.Running)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == transition.cell) { "${transition.cell} is not the running cell ${running.cell}" }
                val graph = transition.checkpoint?.let { s.graph.recordCell(running.increment, running.cell, it.turn, it.touched, 0) } ?: s.graph
                s.next(graph = graph, cells = s.cells.map { if (it.cell == running.cell) it.copy(status = CellStatus.Failed) else it }, contractVersion = v)
            }
            is Transition.Interrupted -> {
                expect(s, CampaignPhase.Running)
                val running = checkNotNull(s.running) { "no cell is running" }
                check(running.cell == transition.checkpoint.cell) { "checkpoint of ${transition.checkpoint.cell} is not the running cell ${running.cell}" }
                val graph = s.graph.recordCell(running.increment, running.cell, transition.checkpoint.turn, transition.checkpoint.touched, 0)
                s.next(graph = graph, cells = s.cells.map { if (it.cell == running.cell) it.copy(status = CellStatus.Cancelled) else it }, contractVersion = v)
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
            is Transition.Planned -> {
                expect(s, CampaignPhase.Running)
                check(s.running == null) { "cell ${s.running?.cell} is still running" }
                val issues = transition.graph.validate(contract)
                check(issues.isEmpty()) { "an invalid plan is never installed: ${issues.joinToString { it.detail }}" }
                val next = transition.graph.increments.associateBy { it.id }
                for (kept in s.graph.increments.filter { it.cells.isNotEmpty() || it.status != IncrementStatus.Pending }) {
                    val retired = next[kept.id]
                    val split = kept.id in transition.replaced && kept.status !in setOf(IncrementStatus.Verified, IncrementStatus.Cancelled) &&
                        retired?.status == IncrementStatus.Cancelled && retired.copy(status = kept.status, cancelledReason = kept.cancelledReason) == kept
                    check(next[kept.id] == kept || split) { "a replan keeps ${kept.id} (${kept.status}) as it was" }
                }
                val ledger = if (s.graph.increments.any { it.status == IncrementStatus.Verified }) s.ledger else Ledger.initial(contract)
                s.next(graph = transition.graph, ledger = ledger, contractVersion = v)
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
            is Transition.Amended -> {
                check(v == transition.version) { "amendment to v${transition.version} applied to contract v$v" }
                extended(s, contract, transition.increments)
            }
            is Transition.ResponseOpened -> {
                check(contract.requests.any { it.id == transition.requestId }) { "no message ${transition.requestId} to respond to" }
                extended(s, contract, listOf(transition.increment))
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
                s.next(phase = CampaignPhase.Ended, ledger = ledger, outcome = CampaignOutcome.Completed, reason = null, contractVersion = v, stopCode = null)
            }
            is Transition.Answered -> {
                expect(s, CampaignPhase.Running)
                check(s.running == null) { "reconcile the running cell ${s.running?.cell} before the answer ends the campaign" }
                s.next(phase = CampaignPhase.Ended, outcome = CampaignOutcome.Answered, reason = null, contractVersion = v, stopCode = null)
            }
            is Transition.Stopped -> {
                expect(s, CampaignPhase.Opened, CampaignPhase.Running, CampaignPhase.Finishing)
                check(s.running == null) { "reconcile the running cell ${s.running?.cell} before a terminal outcome" }
                s.next(phase = CampaignPhase.Ended, outcome = transition.outcome, reason = transition.reason, contractVersion = v, stopCode = transition.code,
                    budgetStop = transition.budget, contractStop = transition.contract, failedResumably = transition.resumable)
            }
            is Transition.Resumed -> {
                expect(s, CampaignPhase.Ended, CampaignPhase.Finishing)
                val interrupted = s.phase == CampaignPhase.Finishing
                check(interrupted || s.resumable) { "a ${s.outcome?.wire} campaign does not resume" }
                s.next(phase = CampaignPhase.Opened, outcome = null, reason = null, contractVersion = v, stopCode = null, budgetStop = null, contractStop = null,
                    ledger = if (interrupted) Ledger.initial(contract) else s.ledger, failedResumably = false)
            }
            is Transition.LimitRaised -> {
                expect(s, CampaignPhase.Ended)
                val resumable = s.budgetStop?.resumable == true || s.contractStop?.cause?.resumable == true
                check(s.outcome == CampaignOutcome.BudgetExhausted && resumable) {
                    "only a resumable budget stop reopens; this one is ${s.outcome?.wire} ${s.budgetStop?.wire} ${s.contractStop?.cause?.wire.orEmpty()}"
                }
                s.next(phase = CampaignPhase.Opened, outcome = null, reason = null, contractVersion = v, stopCode = null, budgetStop = null, contractStop = null)
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
            is CellExit.Completed -> completed(completion)
            is CellExit.Blocked ->
                if (exit.request.question != null) Disposition.Stop(CampaignOutcome.WaitingForInput, exit.request.reason)
                else Disposition.Stop(CampaignOutcome.BlockedExternal, exit.request.reason)
            is CellExit.Partial -> Disposition.Continue(
                "${exit.reason}: ${exit.hint}",
                when (exit.reason) {
                    // A-D.6: a handoff continues in an epoch; its fallback is the stop when the handoffs are spent.
                    PartialReason.TurnBudget, PartialReason.TokenBudget, PartialReason.Reserve, PartialReason.Handoff -> CampaignOutcome.BudgetExhausted
                    PartialReason.Pressure, PartialReason.CompletionStalled -> CampaignOutcome.Failed
                },
            )
            is CellExit.Failed -> Disposition.Stop(CampaignOutcome.Failed, exit.error)
            is CellExit.Cancelled -> Disposition.Stop(CampaignOutcome.Cancelled, exit.reason)
        }
    }

    /** A completed cell's disposition by its verified proposal; also a kept return verified again on open (P8.C.8). */
    internal fun completed(completion: CompletionResult?): Disposition = when (completion) {
        is CompletionResult.Accepted -> Disposition.Close(completion)
        is CompletionResult.Refused ->
            if (completion.recoveryDirected) {
                Disposition.Stop(CampaignOutcome.Failed, "completion unsupported after ${completion.attempts} finalizations: ${completion.missing.joinToString("; ")}")
            } else {
                Disposition.Continue("completion refused: ${completion.missing.joinToString("; ")}", CampaignOutcome.Failed)
            }
        // D-339: done, awaiting an authority's decision — neither blocked nor failed (I1).
        is CompletionResult.Pending -> Disposition.Stop(CampaignOutcome.WaitingForInput, pendingReason(completion), completion.code)
        is CompletionResult.NotCompleted, null -> throw IllegalArgumentException("a completed cell's done proposal must be verified first")
    }

    /** The stop reason of a pending completion: what the authority is asked, one line per gap. */
    @JvmStatic
    public fun pendingReason(pending: CompletionResult.Pending): String = when (pending.code) {
        StopCode.AcceptanceDecision -> "acceptance needs a decision: "
        StopCode.ReviewRejected -> "review rejected the change after its rework round: "
        StopCode.IntegrityReview -> "a test-integrity change waits for a person's review: "
    } + pending.missing.joinToString("; ")

    private fun expect(state: CampaignState, vararg phases: CampaignPhase) =
        check(state.phase in phases) { "illegal in ${state.phase}; expected ${phases.joinToString()}" }

    /**
     * Task-workflow §2.4 A, B: [added] joins the graph — every existing increment exactly as it was — and the result still
     * validates. The requirements they cover hold the campaign open until they are verified; a lapsed one holds nothing.
     */
    private fun extended(s: CampaignState, contract: Contract, added: List<Increment>): CampaignState {
        expect(s, CampaignPhase.Opened, CampaignPhase.Running)
        check(s.running == null) { "cell ${s.running?.cell} is still running" }
        val known = s.graph.increments.map { it.id }.toSet()
        check(added.none { it.id in known } && added.all { it.status == IncrementStatus.Pending && it.cells.isEmpty() }) { "added increments are new and pending" }
        val graph = s.graph.copy(increments = s.graph.increments + added)
        val issues = graph.validate(contract)
        check(issues.isEmpty()) { "an amended graph that does not validate is never installed: ${issues.joinToString { it.detail }}" }
        val open = added.flatMap { it.requirementIds }.toSet()
        val entries = contract.requirements.associate { r ->
            val kept = s.ledger[r.id]
            r.id to when {
                r.lapsed -> LedgerEntry(r.id, RequirementStatus.Cancelled)
                r.id in open || kept == null -> LedgerEntry(r.id, RequirementStatus.Pending, kept?.evidence.orEmpty())
                else -> kept
            }
        }
        return s.next(graph = graph, ledger = Ledger(entries), contractVersion = contract.version)
    }

    private fun verifiedLedger(state: CampaignState, contract: Contract, stamp: CandidateId): Ledger {
        check(contract.requirements.isNotEmpty()) { "an empty contract is never completed" }
        val ledger = state.graph.ledger(contract, stamp)
        val unfinished = ledger.entries.values.filter { !it.stampValid && it.status != RequirementStatus.Cancelled }.map { it.requirementId }
        check(unfinished.isEmpty()) { "requirements not verified at @${stamp.hash8}: $unfinished" }
        check(state.graph.increments.none { it.status == IncrementStatus.Blocked || it.status == IncrementStatus.InProgress }) {
            "an increment is still open"
        }
        return ledger
    }
}
