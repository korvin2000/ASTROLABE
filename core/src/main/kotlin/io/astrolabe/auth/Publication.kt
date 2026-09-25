package io.astrolabe.auth

import io.astrolabe.DClassPolicy
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.id.CandidateId
import io.astrolabe.verify.Verdict
import kotlinx.serialization.Serializable

/** Changes a human approves by default whatever the ceiling says (§14.2). */
@Serializable
public enum class HumanAnchor(public val wire: String) {
    InterfaceContract("interface-contract"),
    DataMigration("data-migration"),
    ProductionDeploy("production-deploy"),
    NewNetworkAccess("new-network-access"),
    CeilingElevation("ceiling-elevation"),
    ;

    override fun toString(): String = wire
}

/**
 * What the harness knows about a candidate when a publication stage is asked for (§14.2). Every field comes
 * from harness records: the contract's declared [risk], the verifier's [greenStamp] (the stamp at which L0–L2
 * last ran all green, `null` when they are not green), the review cell's signed [judge] verdict, and the paths
 * the campaign changed. [modelMetadata] is whatever the model said about itself (a packet's "approved",
 * "low risk", "ceiling: deploy"): it is kept for the report and never read by [PublicationPolicy].
 */
@Serializable
public data class PublicationEvidence @JvmOverloads constructor(
    val shape: Shape,
    val risk: Risk?,
    val currentStamp: CandidateId,
    val greenStamp: CandidateId?,
    val judge: Verdict? = null,
    val changedPaths: List<String> = emptyList(),
    val anchors: Set<HumanAnchor> = emptySet(),
    val modelMetadata: Map<String, String> = emptyMap(),
) {
    init {
        require(changedPaths.none { it.isBlank() }) { "changed paths must not be blank" }
    }
}

/** The policy's answer for one stage: refused outright, or a D-class grant to ask `Authority.approve` for. */
@Serializable
public sealed interface PublicationDecision {
    public val stage: Stage

    @Serializable
    public data class Refused(override val stage: Stage, val refusal: Refusal) : PublicationDecision

    /**
     * Ask the authority. [autonomous] is the `contractAllowlisted` flag of the request: true only when the
     * autonomous predicate holds and no [anchors] apply; [unmet] names every clause that failed.
     */
    @Serializable
    public data class Approval(
        override val stage: Stage,
        val autonomous: Boolean,
        val anchors: List<HumanAnchor>,
        val unmet: List<String>,
    ) : PublicationDecision
}

/**
 * The permission-ladder policy (§14.2), a pure function of records. `patch → local commit → push → merge →
 * deploy` are separate grants and the contract sets the ceiling:
 * - a stage above the ceiling is refused: raising it is the ceiling-elevation anchor, an amendment only a human makes;
 * - L0–L2 green with the current stamp is mandatory verification for every publication (D-190), never waived by an approval;
 * - the autonomous predicate is ceiling ≥ stage ∧ low blast radius ∧ easy reversibility ∧ L0–L2 green with
 *   current stamps ∧ (S2+) a judge approval of the current stamp, and beyond `local commit` the contract's
 *   D-class allowlist must name the stage (D-190);
 * - any human anchor makes the grant a human decision.
 *
 * The model cannot move any of this: [PublicationEvidence.modelMetadata] is never read here.
 */
public object PublicationPolicy {
    /** Low blast radius: at most this many files (the S0 small-task bound, `ShapePolicy.smallMaxFiles`). */
    public const val LOW_BLAST_RADIUS_MAX_FILES: Int = 3

    /** The stage's wire name, as the contract's `dClassAllowlist` and the D-class request's action name it. */
    @JvmStatic
    public fun wire(stage: Stage): String = when (stage) {
        Stage.Patch -> "patch"
        Stage.LocalCommit -> "local-commit"
        Stage.Push -> "push"
        Stage.Merge -> "merge"
        Stage.Deploy -> "deploy"
    }

    /** Anchors derivable from the evidence alone: contract touch and data migrations (D-191). */
    @JvmStatic
    public fun anchors(evidence: PublicationEvidence): Set<HumanAnchor> = buildSet {
        addAll(evidence.anchors)
        if (evidence.risk?.contractTouch == true) add(HumanAnchor.InterfaceContract)
        if (evidence.changedPaths.any(::isMigration)) add(HumanAnchor.DataMigration)
    }

    @JvmStatic
    @JvmOverloads
    public fun decide(
        stage: Stage,
        authorization: Authorization,
        evidence: PublicationEvidence,
        lowBlastRadiusMaxFiles: Int = LOW_BLAST_RADIUS_MAX_FILES,
    ): PublicationDecision {
        val subject = wire(stage)
        if (stage.ordinal > authorization.ladderCeiling.ordinal) {
            return PublicationDecision.Refused(
                stage,
                Refusal(
                    subject, RefusalReason.AboveStageCeiling,
                    "the contract authorizes up to '${wire(authorization.ladderCeiling)}'; raising it is a ${HumanAnchor.CeilingElevation} amendment",
                ),
            )
        }
        if (stage == Stage.Patch) return PublicationDecision.Approval(stage, autonomous = true, anchors = emptyList(), unmet = emptyList())
        if (authorization.dClass == DClassPolicy.Deny) {
            return PublicationDecision.Refused(stage, Refusal(subject, RefusalReason.NotApproved, "the contract denies every D-class effect"))
        }
        val current = evidence.currentStamp
        if (evidence.greenStamp != current) {
            val why = if (evidence.greenStamp == null) "L0–L2 are not green" else "L0–L2 were green at ${evidence.greenStamp.hash8}, not the current stamp ${current.hash8}"
            return PublicationDecision.Refused(stage, Refusal(subject, RefusalReason.UnverifiedCandidate, why))
        }
        val unmet = buildList {
            val risk = evidence.risk
            when {
                risk == null -> add("blast radius not assessed")
                risk.blastRadius > lowBlastRadiusMaxFiles -> add("blast radius ${risk.blastRadius} files exceeds $lowBlastRadiusMaxFiles")
            }
            if (risk?.reversibility != Reversibility.Easy) add("reversibility is not easy")
            if (evidence.shape >= Shape.S2) {
                val judge = evidence.judge
                when {
                    judge == null -> add("no judge verdict")
                    !judge.approved -> add("judge verdict is ${judge.outcome.name.lowercase()}")
                    judge.reviewedCandidate != current -> add("judge approved ${judge.reviewedCandidate.hash8}, not the current stamp")
                }
            }
            if (stage > Stage.LocalCommit && subject !in authorization.dClassAllowlist) add("the contract does not allowlist '$subject'")
        }
        val anchors = anchors(evidence).sorted()
        return PublicationDecision.Approval(stage, autonomous = unmet.isEmpty() && anchors.isEmpty(), anchors = anchors, unmet = unmet)
    }

    private fun isMigration(path: String): Boolean {
        val segments = path.replace('\\', '/').lowercase().split('/')
        return segments.dropLast(1).any { it == "migrations" || it == "migration" || it == "migrate" } ||
            segments.last().endsWith(".sql")
    }
}
