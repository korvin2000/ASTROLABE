package io.astrolabe.verify

import io.astrolabe.contract.Contract
import io.astrolabe.event.Authority
import io.astrolabe.event.Replies
import io.astrolabe.event.ReplyValidity
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.Receipts
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.store.BlobKind
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.tool.edit.LineDiff
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.ShadowRef
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.relativeTo

/**
 * One campaign-scope review as it happened (§8.8, §8.9 item 6, D-23): the request, the signed verdict or the
 * reason none arrived, and the equivalence evidence the reviewer saw. Stored once per request in `packets`.
 */
@Serializable
public data class CampaignReviewRecord(
    val request: ReviewRequest,
    val verdict: Verdict? = null,
    /** Why no verdict arrived (no reviewer, superseded reply, wrong candidate); `null` beside a usable verdict. */
    val unavailable: String? = null,
    val equivalence: EquivalenceReport? = null,
    val reused: Boolean = false,
) {
    val approved: Boolean get() = verdict?.approved == true && unavailable == null
}

/** What the campaign gate does with a review: proceed, stop, or block (never skip). */
public sealed interface CampaignReviewOutcome {
    public val record: CampaignReviewRecord

    public data class Approved(override val record: CampaignReviewRecord) : CampaignReviewOutcome

    /** A signed verdict that does not approve; [terminal] when the reviewer rejected outright. */
    public data class Declined(override val record: CampaignReviewRecord, val reason: String, val terminal: Boolean) : CampaignReviewOutcome

    /** No usable verdict: the campaign ends `blocked`, the review is never skipped (I-03, D-23). */
    public data class Unavailable(override val record: CampaignReviewRecord, val reason: String) : CampaignReviewOutcome
}

/**
 * The human review path of D-23 at campaign scope: `Authority.review` receives the full diff `s0 → stamp` as a
 * blob, the contract's criteria, the receipts current at the stamp and a rubric; it substitutes the review cell
 * (P4.4.3) and is recorded as such. A verdict is usable only when signed for the current contract revision and
 * the reviewed candidate; an approving one at the same stamp is reused rather than asked for twice.
 */
public class CampaignReview(
    private val authority: Authority,
    private val shadow: ShadowRef,
    private val workspace: Workspace,
    private val stamper: Stamper,
    private val store: Store,
    private val journal: Journal,
    private val receipts: Receipts,
    private val checks: Checks,
    private val idGen: IdGen,
    private val ids: Identities,
    private val clock: Clock,
    private val scratch: ScratchPolicy = ScratchPolicy(),
    /** The admitted plan's §8.9 checklist, read when a request is built; `null` outside refactor mode. */
    private val checklist: () -> RefactorChecklist? = { null },
    /** The `CON` notes the plan references (superseded ids, `new: <summary>` for candidates), read when a request is built. */
    private val conReferences: () -> List<String> = { emptyList() },
) {
    /** The attempt's latest review record, or `null` when none was requested. */
    public fun latest(): CampaignReviewRecord? = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
        ids.work, ids.attempt, KIND,
    ) { JSON.decodeFromString(CampaignReviewRecord.serializer(), it.string("body")) }.firstOrNull()

    /**
     * §8.9 item 5 at [stamp]: the attempt's behaviour snapshot against the receipts that certify each affected
     * suite now ([currencies] first, else the check's last receipt); `null` without a snapshot.
     */
    public fun equivalence(stamp: CandidateId, currencies: Map<String, Currency> = emptyMap()): EquivalenceReport? {
        val snapshot = BehaviourSnapshots(null, shadow, store.layout, store.blobs, store, journal, ids, idGen, clock).latest() ?: return null
        return Equivalence.report(
            snapshot, stamp, store.blobs, receipts::get,
            snReceipt = { checkId -> (currencies[checkId]?.receiptId ?: checks[checkId]?.last?.receiptId)?.let(receipts::get) },
            finalBytes = { path -> readFinal(path) },
        )
    }

    /**
     * Requests the campaign-scope review of [contract] at the current stamp. [receiptIds] are the receipts current
     * at that stamp; [equivalence] travels in the rubric when present; [why] names what made the review owed.
     */
    public suspend fun review(
        contract: Contract,
        s0: CandidateId,
        receiptIds: List<String>,
        equivalence: EquivalenceReport?,
        why: String = "campaign review required",
    ): CampaignReviewOutcome {
        val stamp = stamper.report().candidateId
        latest()?.takeIf { it.approved && it.verdict!!.reviewedCandidate == stamp && it.verdict.contractRevision == contract.version }?.let { earlier ->
            val record = earlier.copy(reused = true, equivalence = equivalence ?: earlier.equivalence)
            record(record)
            journal(record, "reused: signed by ${record.verdict!!.signedBy} at @${stamp.hash8}")
            return CampaignReviewOutcome.Approved(record)
        }
        val (diff, diffLimits) = diffBlob(s0, stamp)
        val rubric = rubric(contract, equivalence, checklist(), conReferences(), receiptIds, diffLimits)
        val request = ReviewRequest(
            id = idGen.next("review"), contractRevision = contract.version, ids = ids, scope = ReviewScope.Campaign, candidate = stamp,
            packetRef = diff.hex, criteria = contract.acceptance.map { "${it.id}: ${it.criterion}" },
            originalObligations = contract.requirements.map { "${it.id}: ${it.text}" },
            diffRef = diff.hex, receipts = receiptIds, rubric = rubric,
        )
        val verdict = authority.review(request)
        val record = when {
            verdict == null -> CampaignReviewRecord(request, null, "no reviewer answered $why: the campaign is blocked, the review is never skipped (D-23)", equivalence)
            verdict.requestId != request.id -> CampaignReviewRecord(request, verdict, "verdict answers ${verdict.requestId}, not ${request.id}", equivalence)
            Replies.check(verdict, contract.version) != ReplyValidity.Current -> CampaignReviewRecord(request, verdict, "verdict signed for contract v${verdict.contractRevision}, not v${contract.version}", equivalence)
            verdict.reviewedCandidate != stamp -> CampaignReviewRecord(request, verdict, "verdict reviewed @${verdict.reviewedCandidate.hash8}, not the final @${stamp.hash8}", equivalence)
            else -> CampaignReviewRecord(request, verdict, null, equivalence)
        }
        record(record)
        val outcome = when {
            record.unavailable != null -> CampaignReviewOutcome.Unavailable(record, record.unavailable)
            record.approved -> CampaignReviewOutcome.Approved(record)
            else -> {
                val v = record.verdict!!
                val findings = v.findings.take(3).joinToString("; ") { "${it.severity.name.lowercase()} ${it.location}: ${it.issue}" }
                CampaignReviewOutcome.Declined(record, "campaign review ${v.outcome.name.lowercase()} by ${v.signedBy}" + (if (findings.isEmpty()) "" else ": $findings") + (v.missingCriterion?.let { "; missing: $it" } ?: ""), terminal = v.outcome == VerdictOutcome.Reject)
            }
        }
        journal(record, when (outcome) {
            is CampaignReviewOutcome.Approved -> "approve by ${record.verdict!!.signedBy}"
            is CampaignReviewOutcome.Declined -> outcome.reason
            is CampaignReviewOutcome.Unavailable -> "unavailable: ${outcome.reason}"
        })
        return outcome
    }

    /** The reviewer's rubric (§8.8, §8.9 items 4–6): one line per assessment, evidence named, never the transcript. */
    public fun rubric(
        contract: Contract,
        equivalence: EquivalenceReport?,
        checklist: RefactorChecklist?,
        conReferences: List<String>,
        receiptIds: List<String>,
        diffLimits: List<String> = emptyList(),
    ): List<String> {
        val lines = ArrayList<String>()
        lines += "the full diff s0 → candidate is the evidence, never the proposer's transcript (§8.8)" + (if (diffLimits.isEmpty()) "" else "; limits: ${diffLimits.joinToString("; ")}")
        lines += "every requirement is met as stated at contract v${contract.version}: ${contract.requirements.joinToString("; ") { "${it.id} ${it.text}" }}"
        lines += "receipts current at the candidate: " + receiptIds.ifEmpty { listOf("none") }.joinToString(", ")
        if (equivalence != null) {
            lines += "behaviour preserved (§8.9 item 5): " + equivalence.render(maxLines = 6).replace("\n", " · ")
            lines += if (equivalence.newBehaviour.isEmpty()) "no new test: no silent extension" else "new behaviour needs a requirement: " + equivalence.newBehaviour.joinToString("; ")
        }
        if (checklist != null) {
            lines += "interfaces changed as declared (§8.9 item 4): ${checklist.interfacesToChange}; CON note(s): " + conReferences.ifEmpty { listOf("none referenced") }.joinToString(", ")
            lines += "callers/consumers updated: ${checklist.callersConsumers}; compatibility duration honoured: ${checklist.compatibilityDuration}"
            lines += "data/configuration dependencies: ${checklist.dataConfigurationDependencies}; independent acceptance checks: ${checklist.independentAcceptanceChecks}"
        }
        return lines
    }

    /** The unified diff of every path that differs between the materialized `s0` and the tree at [stamp], published as one blob. */
    internal fun diffBlob(s0: CandidateId, stamp: CandidateId): Pair<Digest, List<String>> {
        val limits = ArrayList<String>()
        val base = materialize(limits)
        val paths = java.util.TreeSet(Stamper.PATH_ORDER)
        if (base != null) {
            try {
                Files.walk(base).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { paths += it.relativeTo(base).joinToString("/") { p -> p.toString() } }
                }
            } catch (failure: IOException) {
                limits += "s0 tree partially listed: ${failure.message}"
            }
        }
        paths += stamper.report().members.keys
        val text = StringBuilder("diff s0 @${s0.hash8} → candidate @${stamp.hash8} (${ids.work.value}/${ids.attempt.value})\n")
        var changed = 0
        for (path in paths.filterNot { scratch.isScratch(it) }) {
            val old = base?.let { readAt(it.resolve(path)) }
            val new = readFinal(path)
            if (old != null && new != null && old.contentEquals(new)) continue
            changed += 1
            text.append("--- a/").append(path).append(if (old == null) " (absent)" else "").append('\n')
            text.append("+++ b/").append(path).append(if (new == null) " (deleted)" else "").append('\n')
            if (isBinary(old) || isBinary(new)) {
                text.append("Binary files differ (").append(old?.size ?: 0).append(" → ").append(new?.size ?: 0).append(" bytes)\n")
                continue
            }
            val hunks = LineDiff.unified(old?.toString(Charsets.UTF_8) ?: "", new?.toString(Charsets.UTF_8) ?: "", context = 3, maxOutputLines = MAX_HUNK_LINES)
            text.append(hunks ?: "@@ too large to render: ${old?.size ?: 0} → ${new?.size ?: 0} bytes @@").append('\n')
        }
        text.append("$changed paths differ\n")
        limits.forEach { text.append("limit: ").append(it).append('\n') }
        return store.blobs.put(text.toString().toByteArray(Charsets.UTF_8), BlobKind.DIFF, ids) to limits
    }

    private fun materialize(limits: MutableList<String>): Path? {
        val dir = store.layout.candidates.resolve("${ids.work.value}-${ids.attempt.value}-s0-review")
        if (Files.exists(dir)) Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        val materialized = try {
            shadow.materialize(0, dir)
        } catch (failure: RuntimeException) {
            limits += "s0 not materialized: ${failure.message}"
            return null
        }
        materialized.limitations.forEach { limits += "materialize: $it" }
        if (!materialized.ok) {
            limits += "exported s0 differs from its manifest: ${materialized.mismatches.joinToString(", ")} (D-53); the diff has no base"
            return null
        }
        return dir
    }

    private fun readFinal(path: String): ByteArray? {
        val resolved = workspace.resolve(path, Intent.Read) as? PathResolution.Resolved ?: return null
        return readAt(resolved.real)
    }

    private fun readAt(file: Path): ByteArray? = try {
        if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    } catch (ignored: IOException) {
        null
    }

    private fun isBinary(bytes: ByteArray?): Boolean = bytes != null && bytes.take(8_000).any { it == 0.toByte() }

    private fun record(record: CampaignReviewRecord) {
        store.db.tx { tx ->
            tx.execute(
                "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                idGen.next("crev"), ids.work, ids.attempt, ids.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
                JSON.encodeToString(CampaignReviewRecord.serializer(), record),
            )
        }
    }

    private fun journal(record: CampaignReviewRecord, text: String) {
        journal.append(
            JournalEvent(
                idGen.next("ev"), ids, null, JournalKind.Boundary,
                refs = listOfNotNull(record.request.diffRef) + record.request.receipts,
                text = "campaign review ${record.request.id} (human path substitutes the review cell, D-23): $text", at = clock.instant(),
            ),
        )
    }

    public companion object {
        public const val KIND: String = "campaign-review"
        public const val MAX_HUNK_LINES: Int = 2_000

        private val JSON = Json { encodeDefaults = true }
    }
}
