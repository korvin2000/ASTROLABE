package io.astrolabe.campaign

import io.astrolabe.auth.PublicationEvidence
import io.astrolabe.auth.PublicationPolicy
import io.astrolabe.auth.Refusal
import io.astrolabe.auth.RefusalReason
import io.astrolabe.auth.Stage
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.event.Authority
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.CandidateId
import io.astrolabe.id.IdGen
import io.astrolabe.os.ObjectId
import io.astrolabe.verify.CampaignReview
import io.astrolabe.verify.CampaignReviewRecord
import io.astrolabe.verify.Verdict
import io.astrolabe.workspace.SnapshotRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock

/**
 * What the host asks to publish once a campaign has finished (§14.2). Nothing is published without one: the harness
 * default stops at `patch`. [through] is the highest stage asked for; every stage below it is asked for first, in
 * order, each its own D-class grant. [remote] is needed from `push`, [mergeTarget] from `merge`, [deployTarget] for
 * `deploy`; [knownRemotes] are the remotes the contract already reaches (any other is the new-network-access anchor).
 */
public data class PublicationRequest @JvmOverloads constructor(
    val through: Stage,
    val remote: String? = null,
    val mergeTarget: String? = null,
    val deployTarget: DeployTarget? = null,
    val knownRemotes: Set<String> = emptySet(),
    /** The harness commit's message; `null` names the work and attempt. */
    val message: String? = null,
) {
    init {
        require(through > Stage.Patch) { "a publication request asks for a stage beyond patch, got $through" }
        require(through < Stage.Push || !remote.isNullOrBlank()) { "push and merge need a remote" }
        require(through < Stage.Merge || !mergeTarget.isNullOrBlank()) { "merge needs a target branch" }
        require(through < Stage.Deploy || deployTarget != null) { "deploy needs a target" }
    }

    /** The stages asked for, lowest first. */
    public val stages: List<Stage> get() = Stage.entries.filter { it > Stage.Patch && it <= through }
}

/** The stages a finished campaign's publisher tried, in order, and the finish receipt it re-exported. */
public data class PublicationRun(
    val request: PublicationRequest,
    val results: List<PublicationResult>,
    /** The finish receipt with the highest stage actually reached; never lowered, never a delivery claim for a patch. */
    val receipt: FinishReceipt,
) {
    public val reached: Stage get() = receipt.highestAuthorizedStage
}

/**
 * Publication after finish (§14.2, D-192, D-250): the controller builds one [Publisher] for the attempt from harness
 * records only — the contract's authorization and risk, the verifier's final stamp, the judge's signed verdict, the
 * paths the campaign changed — and walks the requested stages in order, stopping at the first that is not published.
 * Each request is journaled before the grant is asked for and its outcome after, so the order of consequential
 * actions survives a crash; the finish receipt is re-exported with the stage reached.
 */
internal class Publications(private val idGen: IdGen, private val clock: Clock) {
    suspend fun publish(c: OpenedCampaign, finish: FinishReceipt, request: PublicationRequest, authority: Authority, deployer: Deployer?): PublicationRun {
        // §13.1 publication fence: a cancelled campaign or a lost lease publishes nothing.
        c.refusal()?.let { fenced ->
            val stage = request.stages.first()
            journal(c, "publication ${PublicationPolicy.wire(stage)}: refused (fenced): $fenced", buildJsonObject { put("type", OUTCOME); put("stage", PublicationPolicy.wire(stage)); put("result", "refused") })
            return PublicationRun(request, listOf(PublicationResult.Refused(stage, Refusal(PublicationPolicy.wire(stage), RefusalReason.NotApproved, "publication fenced: $fenced"))), finish)
        }
        val contract = c.contract
        val publisher =Publisher(c.workspace.git, c.ids, contract.authorization, contract.version, authority, idGen, deployer, request.knownRemotes)
        val current = c.stamper.report().candidateId
        // D-250: L0–L2 are green at the final stamp only for a completed campaign with nothing left unverified.
        val green = finish.stamp.takeIf { finish.status == "completed" && finish.notVerified.isEmpty() }
        val evidence = PublicationEvidence(
            shape = contract.shape,
            risk = contract.risk,
            currentStamp = current,
            greenStamp = green,
            judge = judge(c),
            changedPaths = (finish.changes.agent + finish.changes.byRun).distinct(),
        )
        val results = ArrayList<PublicationResult>()
        for (stage in request.stages) {
            val wire = PublicationPolicy.wire(stage)
            journal(c, "publication requested: $wire", buildJsonObject {
                put("type", REQUESTED)
                put("stage", wire)
                request.remote?.let { put("remote", it) }
                request.mergeTarget?.let { put("target", it) }
                request.deployTarget?.let { put("deploy", it.name) }
                put("stamp", current.digest.hex)
            })
            val result = when (stage) {
                Stage.LocalCommit -> publisher.commit(snapshot(c, current), base(c), request.message ?: "astrolabe: ${c.ids.work.value}/${c.ids.attempt.value}", evidence)
                Stage.Push -> publisher.push(checkNotNull(request.remote), evidence)
                Stage.Merge -> publisher.merge(checkNotNull(request.mergeTarget), evidence)
                Stage.Deploy -> publisher.deploy(checkNotNull(request.deployTarget), evidence)
                Stage.Patch -> error("patch is not published")
            }
            results += result
            val outcome = when (result) {
                is PublicationResult.Published -> "published ${result.target} @${result.commit.take(8)}"
                is PublicationResult.Refused -> "refused (${result.refusal.reason.wire}): ${result.refusal.detail}"
                is PublicationResult.Failed -> "failed: ${result.detail}"
            }
            journal(c, "publication $wire: $outcome", buildJsonObject {
                put("type", OUTCOME)
                put("stage", wire)
                put("result", result::class.simpleName!!.lowercase())
                (result as? PublicationResult.Published)?.let { put("commit", it.commit); put("request", it.requestId) }
            })
            if (result !is PublicationResult.Published) break
        }
        val receipt = publisher.report(finish)
        if (receipt != finish) FinishReceipts.export(c, receipt)
        return PublicationRun(request, results, receipt)
    }

    /** The judge's signed verdict: the campaign review's, else the latest increment review's (§8.8); `null` when none. */
    private fun judge(c: OpenedCampaign): Verdict? = c.store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
        c.ids.work, c.ids.attempt, CampaignReview.KIND,
    ) { JSON.decodeFromString(CampaignReviewRecord.serializer(), it.string("body")) }.firstOrNull()?.verdict
        ?: ReviewCell.latest(c.store, c.ids)?.verdict

    /** The shadow snapshot of [stamp]: the latest recorded one, else the tree recorded now as the next turn. */
    private fun snapshot(c: OpenedCampaign, stamp: CandidateId): SnapshotRecord {
        c.shadow.records().lastOrNull { it.stampId == stamp }?.let { return it }
        val next = c.shadow.records().maxOf { it.turn } + 1
        return c.shadow.snapshot(c.dirty.capture(next))
    }

    /** The user's HEAD when the campaign started (snapshot 0), the harness commit's parent; `null` on an unborn branch. */
    private fun base(c: OpenedCampaign): ObjectId? = c.s0.baseCommit.takeIf { it.isNotBlank() }?.let { runCatching { ObjectId.parse(it) }.getOrNull() }

    private fun journal(c: OpenedCampaign, text: String, payload: JsonObject) {
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, text = text, payload = payload, at = clock.instant()))
    }

    companion object {
        const val REQUESTED: String = "publication-request"
        const val OUTCOME: String = "publication-outcome"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
