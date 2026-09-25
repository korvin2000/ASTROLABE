package io.astrolabe.campaign

import io.astrolabe.auth.HumanAnchor
import io.astrolabe.auth.PermissionLadder
import io.astrolabe.auth.PublicationDecision
import io.astrolabe.auth.PublicationEvidence
import io.astrolabe.auth.PublicationPolicy
import io.astrolabe.auth.Refusal
import io.astrolabe.auth.RefusalReason
import io.astrolabe.auth.Stage
import io.astrolabe.contract.Authorization
import io.astrolabe.event.Authority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Replies
import io.astrolabe.event.ReplyValidity
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.os.Git
import io.astrolabe.os.GitError
import io.astrolabe.os.Identity
import io.astrolabe.os.ObjectId
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.SnapshotRecord

/** Where a deploy goes; [production] makes it a human anchor (§14.2). */
public data class DeployTarget(val name: String, val production: Boolean) {
    init {
        require(name.isNotBlank()) { "a deploy target needs a name" }
    }
}

/** What the host's [Deployer] is asked to deploy: the merged harness commit, already approved. */
public data class DeployRequest(val ids: Identities, val commit: String, val target: DeployTarget)

/** The host's account of a deploy. */
public data class DeployReceipt(val deployed: Boolean, val detail: String)

/**
 * Host SPI for the `deploy` stage (§14.2). Synchronous, so Java implements it directly; it is called only
 * after the stage passed [PublicationPolicy] and `Authority.approve`, on the caller's thread. An exception
 * counts as a failed deploy and never raises the reached stage.
 */
public fun interface Deployer {
    public fun deploy(request: DeployRequest): DeployReceipt
}

/** One stage outcome. Only [Published] moves the ladder. */
public sealed interface PublicationResult {
    public val stage: Stage

    /** [target] is the ref or deploy target written; [commit] the harness commit it now holds. */
    public data class Published(override val stage: Stage, val commit: String, val target: String, val requestId: String) : PublicationResult

    public data class Refused(override val stage: Stage, val refusal: Refusal) : PublicationResult

    /** Approved, attempted, and failed (the remote refused, the deployer failed): nothing was authorized-and-reached. */
    public data class Failed(override val stage: Stage, val requestId: String, val detail: String) : PublicationResult
}

/**
 * Performs the stages beyond `patch` (§14.2) for one attempt, each a separate D-class grant through
 * [Authority.approve]:
 * - `local commit` writes a harness commit of a shadow snapshot's tree onto [branch] (`refs/heads/astrolabe/<work>/<attempt>`)
 *   with a compare-and-swap ref update: never the user's branch, never their index or worktree (§20.1 TRACE rejection);
 * - `push` publishes that exact commit to the same ref on a remote;
 * - `merge` fast-forwards a target branch on the remote to it (a non-force push; D-192);
 * - `deploy` hands the merged commit to the host's [Deployer].
 *
 * A stage needs the one below it reached by this publisher; the policy decides refusal and the autonomous flag;
 * a decision that answers another request or an older contract revision is not an approval. [ladder] records
 * every refusal, and its highest authorized stage goes into the finish receipt ([report]).
 */
public class Publisher @JvmOverloads constructor(
    private val git: Git,
    public val ids: Identities,
    private val authorization: Authorization,
    private val contractRevision: Int,
    private val authority: Authority,
    private val idGen: IdGen,
    private val deployer: Deployer? = null,
    /** Remotes the contract already reaches; any other is the new-network-access anchor. */
    knownRemotes: Set<String> = emptySet(),
    private val identity: Identity = ShadowRef.HARNESS_IDENTITY,
) {
    private val known = knownRemotes.toSet()
    private val published = ArrayList<PublicationResult.Published>()
    private var commit: ObjectId? = null
    private var pushedTo: String? = null
    private var mergedTo: String? = null

    public val ladder: PermissionLadder = PermissionLadder(authorization.ladderCeiling, PermissionLadder.PUBLISHABLE)

    /** The harness branch; the only local ref a `local commit` writes. */
    public val branch: String = "refs/heads/astrolabe/${ids.work.value}/${ids.attempt.value}"

    /** Every stage actually published, in order. */
    public val publications: List<PublicationResult.Published> get() = published.toList()

    /** Commits [snapshot]'s tree onto [branch] with parent [base] (normally the user's HEAD at campaign start). */
    public suspend fun commit(snapshot: SnapshotRecord, base: ObjectId?, message: String, evidence: PublicationEvidence): PublicationResult {
        val stage = Stage.LocalCommit
        if (snapshot.stampId != evidence.currentStamp) {
            return refuse(stage, RefusalReason.UnverifiedCandidate, "the snapshot is ${snapshot.stampId.hash8}, not the verified stamp ${evidence.currentStamp.hash8}")
        }
        if (git.symbolicRef() == branch) {
            return refuse(stage, RefusalReason.UserBranch, "'$branch' is checked out: the harness never commits on the user's branch")
        }
        val source = ObjectId.parse(snapshot.commit)
        val argv = listOf("commit-tree", "${source.hex}^{tree}") + (base?.let { listOf("-p", it.hex) } ?: emptyList()) + listOf("update-ref", branch)
        return publish(stage, evidence, emptySet(), argv, "harness commit of stamp ${snapshot.stampId.hash8} on $branch") {
            val tree = git.revParse("${source.hex}^{tree}")
            val created = git.commitTree(tree, listOfNotNull(base), message, identity)
            git.updateRef(branch, created, git.readRef(branch))
            commit = created
            created.hex to branch
        }
    }

    /** Pushes the local commit to [branch] on [remote]. */
    public suspend fun push(remote: String, evidence: PublicationEvidence): PublicationResult {
        val stage = Stage.Push
        val local = commit ?: return outOfOrder(stage)
        return publish(stage, evidence, network(remote), listOf("push", "--porcelain", remote, "${local.hex}:$branch"), "publish $branch to $remote") {
            git.push(remote, local.hex, branch)
            pushedTo = remote
            local.hex to "$remote $branch"
        }
    }

    /** Fast-forwards [target] (a branch name or `refs/heads/…`) on the remote the commit was pushed to. */
    public suspend fun merge(target: String, evidence: PublicationEvidence): PublicationResult {
        val stage = Stage.Merge
        val local = commit
        val remote = pushedTo
        if (local == null || remote == null) return outOfOrder(stage)
        val ref = if (target.startsWith("refs/heads/")) target else "refs/heads/$target"
        return publish(stage, evidence, network(remote), listOf("push", "--porcelain", remote, "${local.hex}:$ref"), "fast-forward $ref on $remote to the harness commit") {
            git.push(remote, local.hex, ref)
            mergedTo = "$remote $ref"
            local.hex to "$remote $ref"
        }
    }

    /** Deploys the merged commit to [target] through the host's [Deployer]. */
    public suspend fun deploy(target: DeployTarget, evidence: PublicationEvidence): PublicationResult {
        val stage = Stage.Deploy
        val local = commit
        if (local == null || mergedTo == null) return outOfOrder(stage)
        val host = deployer ?: return refuse(stage, RefusalReason.StageNotImplemented, "the host supplied no deployer")
        val anchors = if (target.production) setOf(HumanAnchor.ProductionDeploy) else emptySet()
        return publish(stage, evidence, anchors, listOf("deploy", target.name, local.hex), "deploy ${local.hex} to ${target.name}") {
            val receipt = host.deploy(DeployRequest(ids, local.hex, target))
            if (!receipt.deployed) throw DeployFailed(receipt.detail)
            local.hex to target.name
        }
    }

    /** [receipt] with the highest authorized stage this publisher reached; never lowered, never a delivery claim. */
    public fun report(receipt: FinishReceipt): FinishReceipt {
        val reached = ladder.highestAuthorizedStage ?: return receipt
        return if (reached > receipt.highestAuthorizedStage) receipt.copy(highestAuthorizedStage = reached) else receipt
    }

    private suspend fun publish(
        stage: Stage,
        evidence: PublicationEvidence,
        anchors: Set<HumanAnchor>,
        argv: List<String>,
        expectedEffect: String,
        perform: () -> Pair<String, String>,
    ): PublicationResult {
        val decision = PublicationPolicy.decide(stage, authorization, evidence.copy(anchors = evidence.anchors + anchors))
        val approval = when (decision) {
            is PublicationDecision.Refused -> {
                ladder.refuse(stage, decision.refusal)
                return PublicationResult.Refused(stage, decision.refusal)
            }
            is PublicationDecision.Approval -> decision
        }
        val action = "publish.${PublicationPolicy.wire(stage)}"
        val reason = buildList {
            if (approval.anchors.isNotEmpty()) add("human anchors: ${approval.anchors.joinToString()}")
            addAll(approval.unmet)
        }.joinToString("; ").ifEmpty { "autonomous predicate holds" }
        val request = DClassRequest(
            id = idGen.next("publish"),
            contractRevision = contractRevision,
            ids = ids.withCandidate(evidence.currentStamp),
            action = action,
            argv = argv,
            cwd = git.repo.toString(),
            expectedEffect = expectedEffect,
            reason = reason,
            contractAllowlisted = approval.autonomous,
        )
        val decided = authority.approve(request)
        if (decided.requestId != request.id || Replies.check(decided, contractRevision) != ReplyValidity.Current || !decided.approved) {
            val why = when {
                decided.requestId != request.id -> "the decision answers ${decided.requestId}, not ${request.id}"
                decided.contractRevision != contractRevision -> "the decision answers contract revision ${decided.contractRevision}, not $contractRevision"
                else -> decided.reason ?: "denied"
            }
            return refuse(stage, RefusalReason.NotApproved, why)
        }
        val (written, target) = try {
            perform()
        } catch (e: GitError) {
            return PublicationResult.Failed(stage, request.id, e.stderr.trim().ifEmpty { e.message ?: "git failed" })
        } catch (e: DeployFailed) {
            return PublicationResult.Failed(stage, request.id, e.message ?: "deploy failed")
        } catch (e: RuntimeException) {
            if (stage != Stage.Deploy) throw e
            return PublicationResult.Failed(stage, request.id, "deployer failed: ${e.message}")
        }
        ladder.record(stage)
        return PublicationResult.Published(stage, written, target, request.id).also { published += it }
    }

    private fun network(remote: String): Set<HumanAnchor> =
        if (remote in known) emptySet() else setOf(HumanAnchor.NewNetworkAccess)

    private fun outOfOrder(stage: Stage): PublicationResult {
        val below = Stage.entries[stage.ordinal - 1]
        return refuse(stage, RefusalReason.StageOutOfOrder, "'${PublicationPolicy.wire(below)}' has not been reached by this attempt")
    }

    private fun refuse(stage: Stage, reason: RefusalReason, detail: String): PublicationResult {
        val refusal = Refusal(PublicationPolicy.wire(stage), reason, detail)
        ladder.refuse(stage, refusal)
        return PublicationResult.Refused(stage, refusal)
    }

    private class DeployFailed(detail: String) : RuntimeException(detail)
}
