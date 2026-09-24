package io.astrolabe.cell

import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkspaceId
import io.astrolabe.provider.BillableUsage
import io.astrolabe.provider.BillingDimension
import io.astrolabe.register.Register
import io.astrolabe.tool.state.BlockedRequest
import io.astrolabe.verify.CompletionProposal
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.workset.Entry
import kotlinx.serialization.Serializable

/**
 * The status a packet proposes (§5.9). It is derived from how the cell ended, never read from the model's
 * text, and `Done` stays a proposal until the verifier accepts it (L7). [wire] is the verifier's vocabulary.
 */
public enum class PacketStatus(public val wire: String) {
    Done("done"),
    Blocked("blocked"),
    Partial("partial"),

    /** A plan cell's replacement proposal (P2.1.2); a P1 cell's pressure exit is `Partial` with a replan hint. */
    Replan("replan"),

    /** A verified wait on a live handle (P2 background handles); distinct from stalled work. */
    Waiting("waiting"),
    Failed("failed"),
    Cancelled("cancelled"),
    ;

    public companion object {
        @JvmStatic
        public fun of(status: CellStatus): PacketStatus = when (status) {
            CellStatus.Completed -> Done
            CellStatus.Blocked -> Blocked
            CellStatus.Partial -> Partial
            CellStatus.Failed -> Failed
            CellStatus.Cancelled -> Cancelled
            CellStatus.Running -> throw IllegalArgumentException("a running cell has no packet")
        }
    }
}

/** The recorded dispatch base (§5.9 `base`): the candidate the cell started from, not its final stamp. */
public data class PacketBase(val stamp: CandidateId, val workspace: WorkspaceId)

/** A verified wait (§5.9 `waiting`): the handle and why the work is waiting on it. */
public data class Waiting(val handle: String, val reason: String)

/** Who moved a path, as the runtime observed it; the finish receipt splits `agent | by_run | pre_existing` on it (P1.9.5). */
public enum class ChangeOrigin { Edit, Run, External }

/** One net change of the cell (§5.9 `changes`): the registry's versions at the cell's first and last transition. */
public data class Change(
    val path: String,
    val kind: TouchKind,
    val before: FileVersion?,
    val after: FileVersion?,
    val origin: ChangeOrigin,
) {
    init {
        require(path.isNotBlank() && before != after) { "a change names a path whose version moved" }
    }
}

/** One declared transform (§9.2, P3.3); a P1 cell has none. */
public data class TransformRecord(val scriptHash: Digest, val files: Int, val diffRef: String, val inventoryOk: Boolean)

/** What the cell was shown and what it changed unseen (§5.9 `coverage`), from the Workset — never from the model. */
public data class PacketCoverage @JvmOverloads constructor(
    /** Distinct displayed line ranges over the cell, per path and version. */
    val rangesDisplayed: Int,
    /** Paths the cell's own edits or runs changed that it was never shown at any version. */
    val filesTouchedUnread: List<String>,
    /** Probe findings the cell consumed (P4.4.2). */
    val probeFindingsUsed: List<String> = emptyList(),
)

/** §5.9 `flags`: what the exit gate and the reviewer must see, collected by the runtime. */
public data class PacketFlags @JvmOverloads constructor(
    /** Changed paths inside the contract but outside the increment's write scope (§8.6 scope warning). */
    val outsideScope: List<String>,
    val testIntegrity: List<TestIntegrityFlag>,
    /** Impact nudges on changed public definitions still unresolved (P3.2.4). */
    val impactNudgesUnresolved: List<String> = emptyList(),
) {
    val scopeWarnings: Int get() = outsideScope.size
}

/** A note the cell proposes (§4.5); the curator decides admission (P4.1). */
@Serializable
public data class NoteCandidate(val kind: String, val summary: String, val scope: String, val evidenceRefs: List<String>, val anchors: List<String>)

/** Informs routing calibration (P2.1.4, P4.5.1); never decides anything. */
public data class SelfAssessment(val complexityObserved: String, val confidence: String, val riskFlags: List<String>)

/**
 * The packet's model-authored part: claims, labelled as such, that inform later work and decide nothing.
 * In S0 the open questions come from STATE's unclosed `Open` items and the unanswered `task.ask` questions;
 * `not_tested` (P3.1), note candidates (P4.2) and the self-assessment (P2.1.4) have no S0 producer yet.
 */
public data class PacketClaims @JvmOverloads constructor(
    val notTested: List<String> = emptyList(),
    val notesToPersist: List<NoteCandidate> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val selfAssessment: SelfAssessment? = null,
)

/**
 * §5.9 `cost`: the cell's provider usage by billable dimension, summed as reported (§15.2) — raw usage, never
 * priced here. A call that reported no usage is counted, not estimated, so a gap in accounting stays visible.
 */
public data class PacketCost @JvmOverloads constructor(
    val quantities: Map<BillingDimension, Long> = emptyMap(),
    val calls: Int = 0,
    val callsWithoutUsage: Int = 0,
    /** Dimensions some call should have reported and did not. */
    val unreported: Set<BillingDimension> = emptySet(),
    val toolSeconds: Double = 0.0,
    /** Tokens of helpers charged to this cell (probes, review, extraction; P4). */
    val helperTokens: Long = 0,
) {
    val uncachedInputTokens: Long get() = quantities[BillingDimension.UNCACHED_INPUT] ?: 0
    val cacheReadTokens: Long get() = quantities[BillingDimension.CACHE_READ] ?: 0
    val cacheWriteTokens: Long get() = quantities.entries.filter { it.key.isCacheWrite }.sumOf { it.value }
    val outputTokens: Long get() = quantities[BillingDimension.OUTPUT] ?: 0

    /** Adds one provider call; `null` usage, or a record with no known quantity, is a call without usage. */
    public operator fun plus(usage: BillableUsage?): PacketCost {
        if (usage == null) return copy(calls = calls + 1, callsWithoutUsage = callsWithoutUsage + 1)
        val summed = quantities.toMutableMap()
        usage.quantities.forEach { (dimension, n) -> summed.merge(dimension, n, Long::plus) }
        val without = if (usage.quantities.isEmpty()) 1 else 0
        return copy(quantities = summed, calls = calls + 1, callsWithoutUsage = callsWithoutUsage + without, unreported = unreported + usage.unknown)
    }

    public fun plusToolSeconds(seconds: Double): PacketCost = copy(toolSeconds = toolSeconds + seconds)
}

/**
 * The Result Packet (§5.9): what a cell hands the controller on every exit. Every field the specification
 * calls runtime-owned — identities, versions, stamps, changes, receipts, coverage, flags, cost, and the status
 * itself — is collected by the cell loop from records; the model's words reach only [claims]. [status] is a
 * proposal: the verifier accepts `done` against current evidence ([proposal]), the controller commits.
 */
public data class ResultPacket(
    /** Work, attempt and the cell's context id; the candidate is [stamp]. */
    val ids: Identities,
    val increment: String,
    val role: String,
    val contractVersion: Int,
    val executionGeneration: ExecutionGeneration,
    /** `null` only when the cell failed before its first stamp. */
    val base: PacketBase?,
    /** The version of every path the cell was shown, at its last display: runtime-observed dependencies. */
    val readVersions: Map<String, FileVersion>,
    val status: PacketStatus,
    /** Why a non-`done` cell ended; `null` for `done`. */
    val reason: String?,
    val waiting: Waiting?,
    val register: Register,
    val worksetExport: List<Entry>,
    val changes: List<Change>,
    val transforms: List<TransformRecord>,
    /** The latest receipt of each check, produced by the scheduler: referenced, never restated. */
    val receipts: List<String>,
    val stamp: CandidateId?,
    /** The environment the final stamp was taken in. */
    val envId: Digest?,
    val coverage: PacketCoverage,
    val flags: PacketFlags,
    val claims: PacketClaims,
    val blocked: BlockedRequest?,
    /** Completion gaps recorded against refused proposals, in order. */
    val gaps: List<String>,
    /** Evidence the completion seam accepted; empty unless `done`. */
    val evidenceRefs: List<String>,
    val cost: PacketCost,
) {
    init {
        require(increment.isNotBlank() && role.isNotBlank()) { "a packet names its increment and role" }
        require(ids.context != null) { "a packet belongs to a cell" }
        require((status == PacketStatus.Blocked) == (blocked != null)) { "a blocked request exactly when the status is blocked" }
        require((status == PacketStatus.Waiting) == (waiting != null)) { "a waiting handle exactly when the status is waiting" }
        require(status != PacketStatus.Done || (stamp != null && reason == null)) { "a done proposal is about a stamped candidate" }
        require(status == PacketStatus.Done || evidenceRefs.isEmpty()) { "only an accepted proposal carries accepted evidence" }
    }

    /**
     * The verifier's input (§8.7): the claimed status bound to this contract version, the dispatch base and the
     * final stamp. A failed cell is recovered, never verified.
     */
    @JvmOverloads
    public fun proposal(patchHash: Digest? = null): CompletionProposal {
        check(status != PacketStatus.Failed) { "a failed cell goes to recovery, not to the verifier" }
        val base = checkNotNull(base) { "no dispatch base was recorded" }
        val stamp = checkNotNull(stamp) { "no final stamp was recorded" }
        val env = checkNotNull(envId) { "no environment was recorded" }
        return CompletionProposal(increment, status.wire, contractVersion, base.stamp, stamp, patchHash, env, reason)
    }
}
