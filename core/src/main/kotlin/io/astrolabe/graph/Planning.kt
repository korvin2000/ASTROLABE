package io.astrolabe.graph

import io.astrolabe.id.CandidateId
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Every increment produces an artifact or resolves a named uncertainty (§4.2). */
@Serializable
public sealed interface Production {
    @Serializable
    @SerialName("artifact")
    public data object Artifact : Production

    @Serializable
    @SerialName("resolves")
    public data class Resolves(val questionId: String) : Production {
        init { require(questionId.isNotBlank()) { "resolution must name an uncertainty" } }
    }
}

@Serializable
public enum class RedOkUntil { IncrementEnd }

/**
 * Observed counts, not scheduling estimates (§6.7 inputs, P2.1.4): updated by the controller at each cell end.
 * [continuations] counts cells after the first; [rebuilds] counts pressure stops, a decomposition failure
 * (§3.8); [touched] is the union of the paths the increment's cells touched, and [filesTouched] its size.
 * The expected count stays on the increment (`expectedFiles`).
 */
@Serializable
public data class Sizing @JvmOverloads constructor(
    val turns: Int = 0,
    val continuations: Int = 0,
    val rebuilds: Int = 0,
    val filesTouched: Int = 0,
    val touched: List<String> = emptyList(),
) {
    init {
        require(turns >= 0 && continuations >= 0 && rebuilds >= 0 && filesTouched >= 0) {
            "sizing counts must be non-negative"
        }
        require(touched.isEmpty() || filesTouched == touched.size) { "filesTouched counts the touched paths" }
    }

    /** One more cell of this increment ended after [cellTurns] turns, touching [paths]. */
    public fun afterCell(cellTurns: Int, paths: Collection<String>, rebuilt: Int): Sizing {
        val union = (touched + paths).distinct().sorted()
        return copy(turns = turns + cellTurns, rebuilds = rebuilds + rebuilt, filesTouched = union.size, touched = union)
    }
}

/** Verifier evidence retained by the controller; never accepted from a plan proposal. */
@Serializable
public data class IncrementEvidence(
    val workId: WorkId,
    val attemptId: AttemptId,
    val contractVersion: Int,
    val stamp: CandidateId,
    val contextId: ContextId,
    val definition: Digest,
    val evidenceRefs: List<String>,
    /** Per acceptance item: tested, reviewed, or accepted by a decider without verification (I7, D-342). */
    val provenance: List<io.astrolabe.verify.ItemProvenance> = emptyList(),
    /** Plan steps the agent left unticked on a proven acceptance (D-368); the finish receipt lists them (D-372). */
    val leftOpen: List<String> = emptyList(),
    /** P8.C.10: red receipts of a harness regression check the acceptance acknowledged with an `Open` item; later resolutions honour them. */
    val acknowledged: List<String> = emptyList(),
) {
    init { require(contractVersion >= 1) }

    /** The constructor before [acknowledged] (P8.C.10). Kept for Java callers. */
    public constructor(
        workId: WorkId, attemptId: AttemptId, contractVersion: Int, stamp: CandidateId, contextId: ContextId, definition: Digest, evidenceRefs: List<String>,
        provenance: List<io.astrolabe.verify.ItemProvenance>, leftOpen: List<String>,
    ) : this(workId, attemptId, contractVersion, stamp, contextId, definition, evidenceRefs, provenance, leftOpen, emptyList())
}

public enum class GraphIssueCode {
    UnknownRequirement, UncoveredRequirement, UnknownAcceptance, UncoveredAcceptance,
    MissingExecutableAcceptance, MissingProduction, UnknownDependency, CancelledDependency,
    DependencyCycle, InvalidOwnership, UnsupportedVerification,
    MissingRequirementDependency,
}

public data class GraphIssue(
    val code: GraphIssueCode,
    val incrementIds: List<String>,
    val detail: String,
)
