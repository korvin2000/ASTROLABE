package io.astrolabe.event

import io.astrolabe.id.Generation
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.StringWrapperSerializer
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.StopReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Phase tags of §15.5, one per event. */
@Serializable
public enum class Phase { Understand, Locate, Edit, Verify, Recover, Retrieve, Compact, Delegate, Plan, Review, Integrate }

@Serializable(with = SpanId.Serializer::class)
public data class SpanId(val value: String) {
    init {
        require(value.isNotBlank()) { "SpanId must not be blank" }
    }

    override fun toString(): String = value

    public object Serializer : StringWrapperSerializer<SpanId>("SpanId", ::SpanId, SpanId::value)
}

/**
 * Outbound host events (TODO P0.4.1). Events carry ids and references, never bodies; the journal and [Views]
 * remain the authoritative state. The bus wraps every event in an [EventRecord] with a per-campaign sequence
 * number, so a consumer can detect gaps and resynchronize.
 */
@Serializable
public sealed interface AgentEvent {
    public val ids: Identities
    public val phase: Phase
    public val span: SpanId?
    public val parent: SpanId?

    @Serializable
    public sealed interface Campaign : AgentEvent {
        @Serializable
        @SerialName("campaign.opened")
        public data class Opened(override val ids: Identities, val requestId: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Campaign

        @Serializable
        @SerialName("campaign.shape_selected")
        public data class ShapeSelected(override val ids: Identities, val shape: String, val inputsRef: String, override val phase: Phase = Phase.Plan, override val span: SpanId? = null, override val parent: SpanId? = null) : Campaign

        @Serializable
        @SerialName("campaign.increment_selected")
        public data class IncrementSelected(override val ids: Identities, val incrementId: String, override val phase: Phase = Phase.Plan, override val span: SpanId? = null, override val parent: SpanId? = null) : Campaign

        @Serializable
        @SerialName("campaign.increment_closed")
        public data class IncrementClosed(override val ids: Identities, val incrementId: String, val status: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Campaign

        @Serializable
        @SerialName("campaign.finished")
        public data class Finished(override val ids: Identities, val outcome: String, val finishReceiptRef: String?, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Campaign
    }

    @Serializable
    public sealed interface Contract : AgentEvent {
        @Serializable
        @SerialName("contract.amended")
        public data class Amended(override val ids: Identities, val version: Int, val by: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Contract

        @Serializable
        @SerialName("contract.amendment_proposed")
        public data class AmendmentProposed(override val ids: Identities, val proposalId: String, val weakening: Boolean, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Contract

        @Serializable
        @SerialName("contract.amendment_resolved")
        public data class AmendmentResolved(override val ids: Identities, val proposalId: String, val outcome: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Contract
    }

    @Serializable
    public sealed interface Cell : AgentEvent {
        @Serializable
        @SerialName("cell.started")
        public data class Started(override val ids: Identities, val incrementId: String?, val role: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.turn_started")
        public data class TurnStarted(override val ids: Identities, val turn: Int, val turnsMax: Int, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.model_requested")
        public data class ModelRequested(override val ids: Identities, val invocationId: String, val estimatedTokens: Long, val profileId: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.model_responded")
        public data class ModelResponded(override val ids: Identities, val invocationId: String, val stop: StopReason, val usage: BillableUsage?, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.tool_called")
        public data class ToolCalled(override val ids: Identities, val opId: Int, val family: String, val op: String, override val phase: Phase, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.tool_resulted")
        public data class ToolResulted(override val ids: Identities, val opId: Int, val resultAlias: String?, val header: String, override val phase: Phase, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.gate_fired")
        public data class GateFired(override val ids: Identities, val gate: String, val text: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.register_patched")
        public data class RegisterPatched(override val ids: Identities, val version: Int, val ops: Int, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.workset_changed")
        public data class WorksetChanged(override val ids: Identities, val known: Int, val dropped: List<String>, override val phase: Phase = Phase.Locate, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.rebuilt")
        public data class Rebuilt(override val ids: Identities, val reason: String, val generation: Generation, override val phase: Phase = Phase.Compact, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell

        @Serializable
        @SerialName("cell.ended")
        public data class Ended(override val ids: Identities, val status: String, val packetRef: String?, val manifestRef: String? = null, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Cell
    }

    @Serializable
    public sealed interface Edit : AgentEvent {
        @Serializable
        @SerialName("edit.applied")
        public data class Applied(override val ids: Identities, val editId: String, val paths: List<String>, override val phase: Phase = Phase.Edit, override val span: SpanId? = null, override val parent: SpanId? = null) : Edit

        @Serializable
        @SerialName("edit.rejected")
        public data class Rejected(override val ids: Identities, val reason: String, val paths: List<String>, override val phase: Phase = Phase.Edit, override val span: SpanId? = null, override val parent: SpanId? = null) : Edit

        @Serializable
        @SerialName("edit.reverted")
        public data class Reverted(override val ids: Identities, val target: String, val outcome: String, override val phase: Phase = Phase.Edit, override val span: SpanId? = null, override val parent: SpanId? = null) : Edit

        @Serializable
        @SerialName("edit.transformed")
        public data class Transformed(override val ids: Identities, val diffRef: String, val filesChanged: Int, override val phase: Phase = Phase.Edit, override val span: SpanId? = null, override val parent: SpanId? = null) : Edit
    }

    @Serializable
    public sealed interface Run : AgentEvent {
        @Serializable
        @SerialName("run.started")
        public data class Started(override val ids: Identities, val actionId: String, val argv: List<String>, val effectClass: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Run

        @Serializable
        @SerialName("run.output")
        public data class Output(override val ids: Identities, val handle: String, val cursor: Long, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Run

        @Serializable
        @SerialName("run.finished")
        public data class Finished(override val ids: Identities, val actionId: String, val status: String, val exitCode: Int?, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Run

        @Serializable
        @SerialName("run.reconciled")
        public data class Reconciled(override val ids: Identities, val actionId: String, val outcome: String, override val phase: Phase = Phase.Recover, override val span: SpanId? = null, override val parent: SpanId? = null) : Run
    }

    @Serializable
    public sealed interface Check : AgentEvent {
        @Serializable
        @SerialName("check.scheduled")
        public data class Scheduled(override val ids: Identities, val checkId: String, val trigger: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Check

        @Serializable
        @SerialName("check.started")
        public data class Started(override val ids: Identities, val checkId: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Check

        @Serializable
        @SerialName("check.finished")
        public data class Finished(override val ids: Identities, val checkId: String, val receiptRef: String, val outcome: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Check

        @Serializable
        @SerialName("check.stale")
        public data class Stale(override val ids: Identities, val checkId: String, val reason: String, override val phase: Phase = Phase.Verify, override val span: SpanId? = null, override val parent: SpanId? = null) : Check
    }

    @Serializable
    public sealed interface Ask : AgentEvent {
        @Serializable
        @SerialName("ask.question")
        public data class Question(override val ids: Identities, val questionId: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Ask

        @Serializable
        @SerialName("ask.answered")
        public data class Answered(override val ids: Identities, val questionId: String, val changesRequirements: Boolean, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : Ask
    }

    @Serializable
    @SerialName("blocked")
    public data class Blocked(override val ids: Identities, val reason: String, val questionId: String? = null, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : AgentEvent

    @Serializable
    @SerialName("warning")
    public data class Warning(override val ids: Identities, val kind: String, val text: String, override val phase: Phase = Phase.Understand, override val span: SpanId? = null, override val parent: SpanId? = null) : AgentEvent

    @Serializable
    public sealed interface Budget : AgentEvent {
        @Serializable
        @SerialName("budget.reserved")
        public data class Reserved(override val ids: Identities, val reservationId: Int, val tokens: Long, val purpose: String, override val phase: Phase, override val span: SpanId? = null, override val parent: SpanId? = null) : Budget

        @Serializable
        @SerialName("budget.reconciled")
        public data class Reconciled(override val ids: Identities, val reservationId: Int, val actualTokens: Long?, override val phase: Phase, override val span: SpanId? = null, override val parent: SpanId? = null) : Budget

        @Serializable
        @SerialName("budget.exhausted")
        public data class Exhausted(override val ids: Identities, val scope: String, override val phase: Phase = Phase.Recover, override val span: SpanId? = null, override val parent: SpanId? = null) : Budget
    }

    @Serializable
    public sealed interface Routing : AgentEvent {
        @Serializable
        @SerialName("routing.decided")
        public data class Decided(override val ids: Identities, val function: String, val tier: String, val profileId: String, val reason: String, override val phase: Phase = Phase.Plan, override val span: SpanId? = null, override val parent: SpanId? = null) : Routing
    }

    @Serializable
    public sealed interface Delegation : AgentEvent {
        @Serializable
        @SerialName("delegation.dispatched")
        public data class Dispatched(override val ids: Identities, val handle: String, val kind: String, override val phase: Phase = Phase.Delegate, override val span: SpanId? = null, override val parent: SpanId? = null) : Delegation

        @Serializable
        @SerialName("delegation.collected")
        public data class Collected(override val ids: Identities, val handle: String, val status: String, override val phase: Phase = Phase.Delegate, override val span: SpanId? = null, override val parent: SpanId? = null) : Delegation

        @Serializable
        @SerialName("delegation.rejected")
        public data class Rejected(override val ids: Identities, val handle: String, val reason: String, override val phase: Phase = Phase.Integrate, override val span: SpanId? = null, override val parent: SpanId? = null) : Delegation
    }

    @Serializable
    public sealed interface Recovery : AgentEvent {
        @Serializable
        @SerialName("recovery.classified")
        public data class Classified(override val ids: Identities, val failureClass: String, val fingerprint: String?, override val phase: Phase = Phase.Recover, override val span: SpanId? = null, override val parent: SpanId? = null) : Recovery

        @Serializable
        @SerialName("recovery.repaired")
        public data class Repaired(override val ids: Identities, val capsuleId: String, val outcome: String, override val phase: Phase = Phase.Recover, override val span: SpanId? = null, override val parent: SpanId? = null) : Recovery

        @Serializable
        @SerialName("recovery.escalated")
        public data class Escalated(override val ids: Identities, val to: String, val reason: String, override val phase: Phase = Phase.Recover, override val span: SpanId? = null, override val parent: SpanId? = null) : Recovery
    }

    @Serializable
    public sealed interface Kb : AgentEvent {
        @Serializable
        @SerialName("kb.proposed")
        public data class Proposed(override val ids: Identities, val noteId: String, val kind: String, override val phase: Phase = Phase.Retrieve, override val span: SpanId? = null, override val parent: SpanId? = null) : Kb

        @Serializable
        @SerialName("kb.admitted")
        public data class Admitted(override val ids: Identities, val noteId: String, override val phase: Phase = Phase.Retrieve, override val span: SpanId? = null, override val parent: SpanId? = null) : Kb

        @Serializable
        @SerialName("kb.invalidated")
        public data class Invalidated(override val ids: Identities, val noteId: String, val reason: String, override val phase: Phase = Phase.Retrieve, override val span: SpanId? = null, override val parent: SpanId? = null) : Kb
    }

    /** Runtime spans (§15.5): one `span.started` and one `span.ended` per span; cost is recorded once, at the end. */
    @Serializable
    public sealed interface Telemetry : AgentEvent {
        @Serializable
        @SerialName("span.started")
        public data class SpanStarted(override val ids: Identities, override val phase: Phase, override val span: SpanId?, override val parent: SpanId? = null) : Telemetry

        /** [cost] is the exclusive cost as `<currency> <amount>`, or `null` when it is unknown (never zero). */
        @Serializable
        @SerialName("span.ended")
        public data class SpanEnded(override val ids: Identities, val status: String, val cost: String?, val durationNanos: Long?, override val phase: Phase, override val span: SpanId?, override val parent: SpanId? = null) : Telemetry
    }
}

/** An emitted event with its bus-assigned sequence number and time; the bus, never the producer, sets these. */
@Serializable
public data class EventRecord(
    val seq: Long,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val event: AgentEvent,
)
