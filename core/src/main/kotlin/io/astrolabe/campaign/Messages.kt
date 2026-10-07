package io.astrolabe.campaign

import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.MessageKind
import io.astrolabe.contract.Narrowing
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import io.astrolabe.verify.StopCode
import java.time.Clock

/**
 * Messages to a work (task-workflow §2.1, §2.2, D-433): the host declares the kind; untyped text takes its kind from the
 * work's state, never from its words, and is never an amendment. The model never classifies a user message.
 */
public object Messages {
    /**
     * The kind of untyped text sent to a work in [state] (§2.2): `steering` short of a final outcome. Text that finds an
     * acceptance decision pending ([decisionPending]) is the card's note, never a message — it is refused here, and the
     * card's Send to agent sends it as `steering` (D-430, WF-8). A work with a final outcome takes a follow-up instead.
     */
    @JvmStatic
    public fun kindOf(state: CampaignState?, decisionPending: Boolean): MessageKind {
        finalOutcome(state)?.let { throw IllegalStateException(it) }
        check(!decisionPending) { "a decision is pending: untyped text is a note on its card; send it to the agent as steering, or decide" }
        return MessageKind.Steering
    }

    /**
     * Records [text] sent to [work] (§1.1) with [kind], or the kind its state gives untyped text ([kindOf]); a typed
     * kind is honoured where the state admits it (§2.2): every kind short of a final outcome, an answer only with the
     * question it [answers]. The work it gets is derived at its next run (§2.4).
     */
    @JvmStatic
    @JvmOverloads
    public fun record(
        contracts: Contracts,
        store: Store,
        clock: Clock,
        work: WorkId,
        kind: MessageKind?,
        text: String,
        hostRef: String? = null,
        answers: String? = null,
        changesRequirements: Boolean = false,
        changes: List<Narrowing> = emptyList(),
    ): Contract {
        val contract = contracts.current(work) ?: throw IllegalArgumentException("no work ${work.value} to send a message to")
        val state = SqliteCampaigns(store, clock).load(work, contract.attemptId)
        val resolved = kind ?: kindOf(state, decisionPending(store, clock, state))
        finalOutcome(state)?.let { throw IllegalStateException(it) }
        return contracts.message(work, resolved, text, hostRef, answers, changesRequirements, changes)
    }

    /** Why a message cannot continue a work in [state] (§1.3): it ended with a final outcome; `null` when it can go on. */
    private fun finalOutcome(state: CampaignState?): String? {
        if (state == null || state.phase != CampaignPhase.Ended || state.resumable) return null
        if (state.outcome == CampaignOutcome.BudgetExhausted && (state.budgetStop?.resumable == true || state.contractStop?.cause?.resumable == true)) return null
        return "work ${state.work.value} ended ${state.outcome?.wire}: a message to it is a follow-up — a new work naming it as parentWork"
    }

    private fun decisionPending(store: Store, clock: Clock, state: CampaignState?): Boolean =
        state?.outcome == CampaignOutcome.WaitingForInput && state.stopCode == StopCode.AcceptanceDecision &&
            Acceptances(store, clock).open(state.work, state.attempt) != null
}
