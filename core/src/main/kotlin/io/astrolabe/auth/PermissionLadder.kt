package io.astrolabe.auth

import io.astrolabe.contract.Authorization
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The outcome of asking for a publication stage (§14.2). */
@Serializable
public sealed interface StageGrant {
    @Serializable
    @SerialName("granted")
    public data class Granted(val stage: Stage) : StageGrant

    @Serializable
    @SerialName("refused")
    public data class Refused(val stage: Stage, val refusal: Refusal) : StageGrant
}

/**
 * The permission ladder (§14.2): `patch → local commit → push → merge → deploy` are separate grants and the
 * contract sets the ceiling. The harness reports the highest **authorized** stage reached — never "delivered"
 * for a patch — and every attempt above the ceiling is refused and recorded.
 *
 * Without a publication backend only [Stage.Patch] is reachable ([REACHABLE]): a stage inside the ceiling that
 * nothing can perform is refused as [RefusalReason.StageNotImplemented] rather than silently granted. The
 * `Publisher` (P5.2) widens [reachable] to [PUBLISHABLE] and moves the ladder only through [record] after a stage
 * passed `PublicationPolicy` and `Authority.approve`, recording every other outcome with [refuse].
 *
 * Model-generated metadata can never raise any of this: the ceiling comes from the contract, and [request] is
 * the only way to move [highestAuthorizedStage], which is monotone.
 */
public class PermissionLadder @JvmOverloads constructor(
    public val ceiling: Stage,
    /** Stages this release can actually perform. */
    public val reachable: Set<Stage> = REACHABLE,
) {
    private val recorded = ArrayList<Refusal>()
    private var highest: Stage? = null

    /** The highest authorized stage reached, or `null` while nothing has been authorized. */
    public val highestAuthorizedStage: Stage? get() = highest

    /** Refusals recorded for the finish receipt, in order. */
    public val refusals: List<Refusal> get() = recorded.toList()

    /** Asks for [stage]. A grant raises [highestAuthorizedStage]; a refusal is recorded and returned. */
    public fun request(stage: Stage): StageGrant {
        if (stage.ordinal > ceiling.ordinal) {
            return refuse(
                stage,
                Refusal(
                    stage.name.lowercase(),
                    RefusalReason.AboveStageCeiling,
                    "the contract authorizes up to '${ceiling.name.lowercase()}'",
                ),
            )
        }
        if (stage !in reachable) {
            return refuse(
                stage,
                Refusal(
                    stage.name.lowercase(),
                    RefusalReason.StageNotImplemented,
                    "stage '${stage.name.lowercase()}' is inside the ceiling but no publication backend performs it (P5.2 Publisher)",
                ),
            )
        }
        record(stage)
        return StageGrant.Granted(stage)
    }

    /** Records an authorized stage that was actually reached; monotone, never lowered. */
    public fun record(stage: Stage) {
        val current = highest
        if (current == null || stage.ordinal > current.ordinal) highest = stage
    }

    /** The `[S]` and finish-receipt line: the highest authorized stage, never a delivery claim. */
    public fun report(): String {
        val reached = highest?.name?.lowercase() ?: "none"
        val refused = if (recorded.isEmpty()) "" else ", ${recorded.size} refused"
        return "highest authorized stage: $reached (ceiling ${ceiling.name.lowercase()}$refused)"
    }

    /** Records a refusal decided elsewhere (policy, authority, stage order) for the finish receipt. */
    public fun refuse(stage: Stage, refusal: Refusal): StageGrant.Refused {
        recorded += refusal
        return StageGrant.Refused(stage, refusal)
    }

    public companion object {
        /** Stages reachable without a publication backend (§14.2). */
        @JvmField
        public val REACHABLE: Set<Stage> = setOf(Stage.Patch)

        /** Stages the P5.2 `Publisher` can perform: every stage, each still a separate grant. */
        @JvmField
        public val PUBLISHABLE: Set<Stage> = Stage.entries.toSet()

        @JvmStatic
        @JvmOverloads
        public fun of(authorization: Authorization, reachable: Set<Stage> = REACHABLE): PermissionLadder =
            PermissionLadder(authorization.ladderCeiling, reachable)
    }
}
