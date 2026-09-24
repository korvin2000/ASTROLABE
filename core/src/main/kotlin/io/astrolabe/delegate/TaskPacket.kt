package io.astrolabe.delegate

import io.astrolabe.auth.Ceiling
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.PacketBase
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkspaceId

/** What a parent may dispatch (§10, §3.4): a writer (S3), a probe (§10.2) or a review cell (§8.8). QA is P5.3. */
public enum class ChildKind(public val wire: String, public val role: String) {
    Writer("writer", "writer"),
    Probe("probe", "probe"),
    Review("review", "review"),
    ;

    public companion object {
        @JvmStatic
        public fun of(wire: String): ChildKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** `sync` runs the child before the dispatch returns; `async` returns a handle to `collect` later. */
public enum class DispatchMode(public val wire: String) {
    Sync("sync"),
    Async("async"),
    ;

    public companion object {
        @JvmStatic
        public fun of(wire: String): DispatchMode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One exact excerpt of the contract with its authority reference (D13): the requirement or constraint text as
 * the contract holds it, never a paraphrase and never a slice of the parent's transcript. [MAX_CHARS] bounds it so a
 * conversation cannot be smuggled in as an excerpt (D-107).
 */
public data class Excerpt(val id: String, val text: String, val authorityRef: String) {
    init {
        require(id.isNotBlank() && text.isNotBlank() && authorityRef.isNotBlank()) { "an excerpt names its id, exact text and authority" }
        require(text.length <= MAX_CHARS) { "excerpt $id is ${text.length} chars; an excerpt is at most $MAX_CHARS" }
    }

    public companion object {
        public const val MAX_CHARS: Int = 4_000
    }
}

/**
 * The Task Packet (§10.1, F09): everything a child cell gets. It carries the existing work/attempt ids and the
 * dispatching cell's context, the increment, the child's role, exact applicable requirements and constraints with
 * their authority refs, the contract version, the dispatch candidate and workspace, the required evidence and the
 * uncertainties, the read and write scope, the tool/capability ceiling, the reserved budget and the execution
 * generation. It holds no transcript: a child sees the parent's words only as excerpts (D13). [base] is what the
 * child's [io.astrolabe.cell.ResultPacket] preserves as its dispatch base (§5.9).
 */
public data class TaskPacket(
    /** The parent's work, attempt, candidate and cell; the child's own context id is assigned at dispatch. */
    val ids: Identities,
    val incrementId: String,
    val role: String,
    val requirements: List<Excerpt>,
    val constraints: List<Excerpt>,
    val contractVersion: Int,
    val dispatchCandidate: CandidateId,
    val workspace: WorkspaceId,
    val requiredEvidence: List<String>,
    val uncertainties: List<String>,
    val readScope: List<String>,
    val writeScope: List<String>,
    val capabilityCeiling: Ceiling,
    val reservedBudget: Tokens,
    val executionGeneration: ExecutionGeneration,
) {
    init {
        require(ids.context != null) { "a packet is dispatched from a cell" }
        require(incrementId.isNotBlank() && role.isNotBlank()) { "a packet names its increment and the child's role" }
        require(contractVersion >= 1) { "contract version starts at 1" }
        require(requirements.isNotEmpty() || uncertainties.isNotEmpty()) { "a packet carries a bounded deliverable: requirements or uncertainties" }
        require(reservedBudget.value > 0) { "a child runs on a reserved budget" }
        require((requirements + constraints).map { it.id }.toSet().size == requirements.size + constraints.size) { "excerpt ids are unique" }
    }

    val base: PacketBase get() = PacketBase(dispatchCandidate, workspace)
}
