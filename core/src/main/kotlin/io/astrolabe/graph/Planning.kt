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
) {
    init { require(contractVersion >= 1) }
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
