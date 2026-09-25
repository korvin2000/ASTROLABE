package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.event.Authority
import io.astrolabe.event.Replies
import io.astrolabe.event.ReplyValidity
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.CandidateId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.kb.Candidate
import io.astrolabe.kb.Derived
import io.astrolabe.register.Register
import io.astrolabe.route.Tier
import io.astrolabe.store.Migrations
import io.astrolabe.store.Store
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.ReviewScope
import io.astrolabe.verify.Severity
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock

/** The §8.8 review budgets `[ESTIMATE]`: at most 10 `look` calls and 30K tokens at increment scope, 60K at campaign scope. */
public data class ReviewBudget @JvmOverloads constructor(val lookCalls: Int = 10, val incrementTokens: Tokens = Tokens(30_000), val campaignTokens: Tokens = Tokens(60_000)) {
    init {
        require(lookCalls >= 1 && incrementTokens.value > 0 && campaignTokens.value > 0) { "review budgets are positive" }
    }

    /** The judge cell's turns and tokens (D-123): one look per turn plus the verdict turn and one test run. */
    public fun of(scope: ReviewScope): ChildBudget = ChildBudget(lookCalls + 2, if (scope == ReviewScope.Campaign) campaignTokens else incrementTokens)

    public companion object {
        @JvmField
        public val DEFAULT: ReviewBudget = ReviewBudget()
    }
}

/** One judge run: the verdict its cell published, or why none came, and what it spent. */
public data class JudgeRun(val verdict: Verdict?, val spend: Tokens, val reason: String? = null)

/** Runs the judge (a review cell, §8.8) over a packet at a tier; the review cell's [ChildCell] in the controller. */
public fun interface ReviewJudge {
    public suspend fun judge(packet: EvidencePacket, tier: Tier): JudgeRun
}

/** `verify(review, scope=increment)` as a cell reaches it (§8.8): the review cell over the cell's current work. */
public fun interface IncrementReview {
    public suspend fun review(why: String): ReviewOutcome
}

/**
 * Campaign scope through the review cell (§8.8): `review(request)` is answered by the [judge] over the packet
 * [packetOf] assembles from the request, up the tier ladder from [tier]; without a verdict the [host] authority
 * answers (the human path of D-23). Every other authority decision stays the host's.
 */
public class ReviewCellAuthority(
    private val host: Authority,
    private val judge: ReviewJudge,
    private val tier: Tier,
    private val packetOf: (ReviewRequest) -> EvidencePacket,
) : Authority by host {
    override suspend fun review(request: ReviewRequest): Verdict? =
        ReviewCell.ladder(judge, packetOf(request), tier).verdict ?: host.review(request)
}

/** How fresh a recorded assessment is against the tree now (§8.7): a changed dependency invalidates it; unassessed is unknown. */
public enum class Freshness { Current, Stale, Unknown }

/**
 * One review as recorded (§8.7, §8.8): the assessment binds the contract version, the reviewed candidate, the criteria
 * and the evidence versions; [verdict] is the signed verdict or [unavailable] says why none is usable.
 */
@Serializable
public data class ReviewRecord(
    val packetId: String,
    val scope: ReviewScope,
    val incrementId: String?,
    val contractVersion: Int,
    val candidate: CandidateId,
    val criteria: List<String>,
    val evidenceVersions: Map<String, FileVersion>,
    val verdict: Verdict? = null,
    val unavailable: String? = null,
    val reused: Boolean = false,
    /** The tiers the judge ran at, in order; `human` for the authority path. */
    val path: List<String> = emptyList(),
) {
    val approved: Boolean get() = verdict?.approved == true && unavailable == null

    /** Whether this assessment still speaks for [contractVersion] at [candidate] with the evidence as it is now. */
    public fun freshness(contractVersion: Int, candidate: CandidateId, current: (String) -> FileVersion?): Freshness = when {
        this.contractVersion != contractVersion || this.candidate != candidate -> Freshness.Stale
        evidenceVersions.isEmpty() -> Freshness.Unknown
        evidenceVersions.any { (path, version) -> current(path) != version } -> Freshness.Stale
        else -> Freshness.Current
    }
}

/** What an obtained review means for acceptance: approve, a verdict that does not, or no usable verdict. */
public sealed interface ReviewOutcome {
    public val record: ReviewRecord

    public data class Approved(override val record: ReviewRecord) : ReviewOutcome

    /**
     * A signed verdict that does not approve, or an approval that cannot stand over a failed required check. Its
     * findings at or above major steer the continuation: [steer] adds them as `Open` items; [pits] are `PIT` candidates.
     */
    public data class Declined(override val record: ReviewRecord, val reason: String, val pits: List<Candidate>) : ReviewOutcome {
        public fun steer(register: Register): Register {
            val findings = record.verdict?.findings.orEmpty()
            val fresh = Derived.openItems(findings, (register.open.maxOfOrNull { it.n } ?: 0) + 1).filter { item -> register.open.none { it.text == item.text } }
            return if (fresh.isEmpty()) register else register.copy(open = register.open + fresh)
        }
    }

    /** No usable verdict: the increment stays unaccepted and the campaign blocks — a required review is never skipped (FX-13). */
    public data class Unavailable(override val record: ReviewRecord, val reason: String) : ReviewOutcome
}

/**
 * The review cell's controller side (§8.8): `obtain_required_review_once` reuses a current approval, else runs the
 * [judge] at the routed tier; `escalate` goes one tier up and from the top tier to the human [authority] (D-23), as
 * does a judge that returned no verdict. Each path is tried once, so a review that cannot run ends `unavailable`
 * instead of gating forever. A verdict is usable only when signed for this packet, contract version and candidate;
 * an approval never stands over a failed required check. Records go to `packets` (`kind = increment-review`).
 */
public class ReviewCell @JvmOverloads constructor(
    private val judge: ReviewJudge,
    private val authority: Authority,
    private val store: Store,
    private val idGen: IdGen,
    private val clock: Clock,
    private val journal: Journal? = null,
) {
    /** Every review recorded for [ids]'s attempt, oldest first. */
    public fun records(ids: Identities): List<ReviewRecord> = store.db.query(
        "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid",
        ids.work, ids.attempt, KIND,
    ) { JSON.decodeFromString(ReviewRecord.serializer(), it.string("body")) }

    public suspend fun obtain(packet: EvidencePacket, tier: Tier, current: (String) -> FileVersion?): ReviewOutcome {
        val criteria = packet.criteria.map { it.id }
        records(packet.ids).lastOrNull { r ->
            r.approved && r.scope == packet.scope && r.incrementId == packet.incrementId && r.criteria.containsAll(criteria) &&
                r.freshness(packet.contractVersion, packet.candidate, current) == Freshness.Current
        }?.let { earlier ->
            val reused = earlier.copy(reused = true)
            record(packet.ids, reused)
            journal(packet, "reused: ${earlier.verdict!!.signedBy} approved @${packet.candidate.hash8} at contract v${packet.contractVersion}")
            return ReviewOutcome.Approved(reused)
        }
        val ladder = ladder(judge, packet, tier)
        val path = ArrayList(ladder.path)
        val why = ladder.reason
        var verdict = ladder.verdict
        if (verdict == null) {
            path += "human"
            verdict = authority.review(packet.request())
        }
        val base = ReviewRecord(packet.id, packet.scope, packet.incrementId, packet.contractVersion, packet.candidate, criteria, packet.evidenceVersions, verdict, path = path)
        val record = when {
            verdict == null -> base.copy(unavailable = "no review cell verdict (${why ?: "the judge published none"}) and no human reviewer: the increment stays unaccepted, the review is never skipped")
            verdict.requestId != packet.id -> base.copy(unavailable = "verdict answers ${verdict.requestId}, not ${packet.id}")
            Replies.check(verdict, packet.contractVersion) != ReplyValidity.Current -> base.copy(unavailable = "verdict signed for contract v${verdict.contractRevision}, not v${packet.contractVersion}")
            verdict.reviewedCandidate != packet.candidate -> base.copy(unavailable = "verdict reviewed @${verdict.reviewedCandidate.hash8}, not @${packet.candidate.hash8}")
            else -> base
        }
        record(packet.ids, record)
        val outcome = outcome(packet, record)
        journal(packet, when (outcome) {
            is ReviewOutcome.Approved -> "approve by ${record.verdict!!.signedBy} (${path.joinToString(" → ")})"
            is ReviewOutcome.Declined -> outcome.reason
            is ReviewOutcome.Unavailable -> "unavailable: ${outcome.reason}"
        })
        return outcome
    }

    private fun outcome(packet: EvidencePacket, record: ReviewRecord): ReviewOutcome {
        record.unavailable?.let { return ReviewOutcome.Unavailable(record, it) }
        val verdict = record.verdict!!
        val pits = Derived.fromFindings(verdict.findings, packet.candidate.hash8, packet.incrementId ?: "campaign")
        val failed = packet.failedRequired
        if (verdict.approved && failed.isEmpty()) return ReviewOutcome.Approved(record)
        val reason = if (verdict.approved) {
            // §8.8: review can never override a failed required check.
            "approved by ${verdict.signedBy}, but required checks failed: ${failed.joinToString(", ") { "${it.checkId} ${it.outcome.name.lowercase()}" }} — review cannot override them"
        } else {
            val findings = verdict.findings.filter { it.severity <= Severity.Major }.take(3).joinToString("; ") { "${it.severity.name.lowercase()} ${it.location}: ${it.issue}" }
            "review ${wire(verdict.outcome)} by ${verdict.signedBy}" + (if (findings.isEmpty()) "" else ": $findings") + (verdict.missingCriterion?.let { "; missing criterion: $it" } ?: "")
        }
        return ReviewOutcome.Declined(record, reason, pits)
    }

    private fun wire(outcome: VerdictOutcome): String = when (outcome) {
        VerdictOutcome.InsufficientEvidence -> "insufficient_evidence"
        else -> outcome.name.lowercase()
    }

    private fun record(ids: Identities, record: ReviewRecord) {
        store.db.tx { tx ->
            tx.execute(
                "INSERT INTO packets (id, work_id, attempt_id, candidate_id, context_id, kind, schema_version, created_at, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                idGen.next("irev"), ids.work, ids.attempt, record.candidate, ids.context, KIND, Migrations.SCHEMA_VERSION, clock.instant(),
                JSON.encodeToString(ReviewRecord.serializer(), record),
            )
        }
    }

    private fun journal(packet: EvidencePacket, text: String) {
        journal?.append(
            JournalEvent(
                idGen.next("ev"), packet.ids, null, JournalKind.Boundary, refs = listOfNotNull(packet.diffRef) + packet.receipts.map { it.receiptId },
                text = "${packet.scope.name.lowercase()} review ${packet.id}${packet.incrementId?.let { " of $it" } ?: ""}: $text", at = clock.instant(),
            ),
        )
    }

    /** The judge's ladder (§8.8): one run per tier while it answers `escalate`; `null` when the human path must answer. */
    internal class Ladder(val verdict: Verdict?, val path: List<String>, val reason: String?)

    public companion object {
        public const val KIND: String = "increment-review"

        internal suspend fun ladder(judge: ReviewJudge, packet: EvidencePacket, tier: Tier): Ladder {
            val path = ArrayList<String>()
            var at = tier
            var why: String? = null
            while (true) {
                path += at.name.lowercase()
                val run = judge.judge(packet, at)
                why = run.reason ?: why
                val verdict = run.verdict ?: return Ladder(null, path, why)
                if (verdict.outcome != VerdictOutcome.Escalate) return Ladder(verdict, path, why)
                when (val next = Judge.escalation(at)) {
                    is Escalation.HigherTier -> at = next.tier
                    Escalation.Human -> return Ladder(null, path, "escalated from the ${at.name.lowercase()} tier")
                }
            }
        }

        private val JSON = Json { encodeDefaults = true }

        /** The attempt's latest increment review, whose findings steer the next register (§8.8). */
        @JvmStatic
        public fun latest(store: Store, ids: Identities): ReviewRecord? = store.db.query(
            "SELECT body FROM packets WHERE work_id = ? AND attempt_id = ? AND kind = ? ORDER BY rowid DESC LIMIT 1",
            ids.work, ids.attempt, KIND,
        ) { JSON.decodeFromString(ReviewRecord.serializer(), it.string("body")) }.firstOrNull()
    }
}
