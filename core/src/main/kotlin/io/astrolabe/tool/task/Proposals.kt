package io.astrolabe.tool.task

import io.astrolabe.id.Identities
import kotlinx.serialization.json.JsonElement

/**
 * Where `task.propose(plan | increment_split)` hands its proposal (§5.4, P2.1.5): the controller's intake, which
 * records it for validation and never changes the contract or the graph directly.
 */
public interface Proposals {
    public fun plan(ids: Identities, proposal: JsonElement): ProposalOutcome

    public fun incrementSplit(ids: Identities, proposal: JsonElement): ProposalOutcome
}

/** What the intake made of a proposal: recorded under [Recorded.id], or refused with the reason the model can fix. */
public sealed interface ProposalOutcome {
    public data class Recorded(val id: String, val summary: String) : ProposalOutcome

    public data class Refused(val reason: String) : ProposalOutcome
}
