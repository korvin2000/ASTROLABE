package io.astrolabe.verify

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.CandidateId
import io.astrolabe.id.CanonicalEncoding
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.register.Mark
import io.astrolabe.register.Register
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What checking one obligation established about a candidate (§8.7, D-337). Only [Failed] means "not done":
 * a check that could not run, did not finish, or whose reviewer could not tell is [Unverified], never a failure.
 */
@Serializable
public enum class ResultStatus { Passed, Failed, Unverified }

/**
 * What kind of evidence an obligation needs. [Run] is an executable check the harness runs (a `run:` item, the full
 * suite, a quality gate, the behaviour snapshot): its failure is a red check no decision overrides (§8.8). [Check],
 * [Review] and [Integrity] are a reviewer's word: their rejection is overridable by an acceptance decision.
 */
@Serializable
public enum class ObligationKind { Run, Check, Review, Integrity }

/**
 * The result of one obligation at one candidate; [detail] names the obligation and says why, [findings] a rejection's
 * substance, [origin] the contract origin of its criterion — `null` for an obligation that is no acceptance item.
 * [humanOnly]: only a person settles it — a test-integrity change under [io.astrolabe.IntegrityApproval.Human] (C11):
 * a person's approving verdict passes it, and an acceptance decision covers it only when the user made it.
 */
@Serializable
public data class ObligationResult @JvmOverloads constructor(
    val obligation: String,
    val kind: ObligationKind,
    val status: ResultStatus,
    val detail: String,
    val evidenceRef: String? = null,
    val by: String? = null,
    val findings: List<Finding> = emptyList(),
    val origin: Origin? = null,
    val humanOnly: Boolean = false,
) {
    init {
        require(obligation.isNotBlank() && detail.isNotBlank()) { "a result names its obligation and says why" }
    }

    /** An executed check that ran red: stands whatever anyone decides (§8.8). */
    val executedFailure: Boolean get() = status == ResultStatus.Failed && kind == ObligationKind.Run

    /** A reviewer's substantive rejection: the agent reworks it, or an authority accepts over it (D-338). */
    val reviewFailure: Boolean get() = status == ResultStatus.Failed && kind != ObligationKind.Run
}

@Serializable
public enum class DecisionKind { Accept, Rework }

/** Who decided: the user, or a host policy acting without them (`auto` mode). Recorded so provenance never blurs the two (I7). */
@Serializable
public enum class Decider { User, Policy }

/** Why a campaign stopped for a person (D-339): machine-readable, so a host never parses the reason text. */
@Serializable
public enum class StopCode(public val wire: String) {
    /** Something could not be verified; the authority decides whether the work is done. */
    AcceptanceDecision("acceptance_decision"),

    /** A reviewer rejected the work and the one rework round is spent; the authority decides: rework or accept as is. */
    ReviewRejected("review_rejected"),

    /**
     * A test-integrity change waits for a person (C11, [io.astrolabe.IntegrityApproval.Human]): a person's approving
     * review of it (the host's next review request says `humanOnly`), or the user's decision — never a policy's.
     */
    IntegrityReview("integrity_review"),
}

/**
 * One obligation an acceptance decision is asked about: unverified with its reason, or rejected by review with its findings.
 * [by] signed the verdict the item carries, if any. [humanOnly] (C11): only a person settles it — a test-integrity change
 * under [io.astrolabe.IntegrityApproval.Human]; a policy's `accept` does not cover it, a user's does, and a person's
 * approving verdict on the next review request ([ReviewRequest.humanOnly]) passes it.
 */
@Serializable
public data class DecisionItem @JvmOverloads constructor(
    val obligation: String,
    val kind: ObligationKind,
    val status: ResultStatus,
    val reason: String,
    val findings: List<Finding> = emptyList(),
    val by: String? = null,
    val humanOnly: Boolean = false,
)

/**
 * The acceptance-decision call (§8.7, D-338): the obligations that need an authority's word — unverified ones and
 * reviewer rejections, never an executed red check — with the references to judge them by. Distinct from
 * [ReviewRequest], which asks for a verification; this asks what to do with its result.
 */
@Serializable
public data class AcceptanceDecisionRequest(
    val id: String,
    val contractRevision: Int,
    val ids: Identities,
    /** `null` for a campaign-scope decision (§8.7 campaign gate). */
    val incrementId: String?,
    val candidate: CandidateId,
    val code: StopCode,
    val items: List<DecisionItem>,
    val diffRef: String? = null,
    val receipts: List<String> = emptyList(),
    /** The agent's final text, a claim shown to the decider, never evidence. */
    val summary: String? = null,
) {
    init {
        require(id.isNotBlank()) { "a decision request has an id" }
        require(contractRevision >= 1) { "contractRevision must be ≥ 1" }
        require(items.isNotEmpty()) { "a decision request names what needs deciding" }
        require(items.none { it.status == ResultStatus.Failed && it.kind == ObligationKind.Run }) { "an executed red check is never put to a decision (§8.8)" }
    }

    /**
     * What this request asks, whatever its [id] ([DecisionKey], WD-10): the same key is the same question, which the
     * controller reissues under the same id, so a host may keep a decision by either.
     */
    val key: String get() = DecisionKey.of(incrementId, candidate, contractRevision, items.map { it.obligation })
}

/**
 * The key of an acceptance decision request (WD-10): its scope — the increment, or the campaign gate — the candidate, the
 * contract revision and the obligations put to the decider, in no particular order. A host finds a decision it kept for
 * a repeated request by it; the controller reuses a pending completion and its request id under it.
 */
public object DecisionKey {
    @JvmStatic
    public fun of(incrementId: String?, candidate: CandidateId, contractRevision: Int, obligations: Collection<String>): String {
        val fields = listOf(
            "scope" to (incrementId?.let { "increment:$it" } ?: "campaign"),
            "candidate" to candidate.digest.hex,
            "contract" to contractRevision.toString(),
            "obligations" to obligations.distinct().sorted().joinToString("\n"),
        )
        return "dk-" + Digest.ofUtf8(CanonicalEncoding.encode("decision-key", 1, fields)).hex
    }
}

/**
 * An authority's acceptance decision (D-338): `accept` (who and why) or `rework` (who and what to change), bound to
 * the request, the contract revision and the candidate it answers. It is stronger than a review result, never
 * stronger than an executed red check.
 */
@Serializable
public data class AcceptanceDecision(
    val requestId: String,
    val contractRevision: Int,
    val candidate: CandidateId,
    val kind: DecisionKind,
    val decider: Decider,
    val by: String,
    val reason: String,
) {
    init {
        require(requestId.isNotBlank() && by.isNotBlank()) { "a decision names its request and who decided" }
        require(kind != DecisionKind.Rework || reason.isNotBlank()) { "a rework decision says what to change" }
    }
}

/**
 * A decision as the controller recorded it: the [obligations] it answers (those the request listed), whether a
 * `rework` was already [spent] on a continuation (one run per decision, D-340).
 */
@Serializable
public data class DecisionRecord(
    val id: String,
    val incrementId: String?,
    val decision: AcceptanceDecision,
    val obligations: List<String>,
    val spent: Boolean = false,
) {
    /** Whether this record speaks for [candidate] at [contractVersion]; a moved tree or contract makes it history. */
    public fun appliesTo(candidate: CandidateId, contractVersion: Int): Boolean =
        decision.candidate == candidate && decision.contractRevision == contractVersion
}

/** The three resolutions of a completion proposal (§8.7, D-337). */
public enum class Resolution {
    /** Every obligation passed or was accepted by a decision: acceptance is recorded with per-item provenance. */
    Complete,

    /** An executed check is red, a reviewer rejected, a decision asked for rework, or something the agent must fix is open. */
    Rework,

    /** Obligations remain unverified without a decision: the campaign waits for one; it neither blocks nor fails (I1). */
    Await,
}

@Serializable
public enum class GapKind { Failed, Unverified, Other }

/** One reason a proposal is not complete: a failed or unverified obligation, or something else the agent must close. */
@Serializable
public data class Gap(val kind: GapKind, val obligation: String?, val text: String)

@Serializable
public enum class ProvenanceKind { Tested, Reviewed, Accepted }

/**
 * Who stands behind a link of the provenance axis (§4.4 C2): the user, the host — the harness that derives a project's
 * own suite and the authorized amendments it applies included — or the model. Only `model(strengthens …)` is the
 * model's: the split D-262 draws for launching commands.
 */
@Serializable
public enum class Author(public val wire: String) {
    @SerialName("user") User("user"),
    @SerialName("host") Host("host"),
    @SerialName("model") Model("model"),
    ;

    public companion object {
        /** Who created an acceptance item, from its contract [origin]. */
        @JvmStatic
        public fun of(origin: Origin): Author = when (origin) {
            is Origin.User -> User
            is Origin.Harness, is Origin.Amended -> Host
            is Origin.Model -> Model
        }

        /** Who set [requirement]: the user when its authority is one of the contract's verbatim [requests], else the host. */
        @JvmStatic
        public fun of(requirement: Requirement, requests: List<UserRequest>): Author =
            if (requests.any { it.id == requirement.authorityRef }) User else Host
    }
}

/**
 * Who took an item's residual risk (§4.4 C2): the runtime, whose verification accepted it, or the decider who accepted
 * it without one — the user, or a host policy (accept-unverified).
 */
@Serializable
public enum class RiskAcceptor(public val wire: String) {
    @SerialName("runtime") Runtime("runtime"),
    @SerialName("user") User("user"),
    @SerialName("policy") Policy("policy"),
}

/**
 * The summary class of the provenance axis (§4.4 C2), worst first. [Independent]: the checks the host or the user
 * declared passed at the final tree; [AgentTest]: only the model's own checks did; [Unverified]: neither — a decider's
 * acceptance is never a verification (I7). A `completed` outcome stays `completed` whatever its class.
 */
@Serializable
public enum class ProvenanceClass(public val wire: String) {
    @SerialName("unverified") Unverified("unverified"),
    @SerialName("agent_test") AgentTest("agent_test"),
    @SerialName("independent") Independent("independent"),
    ;

    public companion object {
        /** One check whose evidence is [by]'s: passed at the final tree on the host's or the user's ⇒ independent, the model's ⇒ agent test. */
        @JvmStatic
        public fun item(by: Author, result: ResultStatus): ProvenanceClass = when {
            result != ResultStatus.Passed -> Unverified
            by == Author.Model -> AgentTest
            else -> Independent
        }

        /**
         * One requirement from the results at the final tree of its [declared] checks — the host's and the user's — and
         * of the [agent]'s own (its items and the checks it registered, C1a): independent when it has a declared check
         * and every one passed; otherwise agent test when none failed and one of the agent's passed; otherwise
         * unverified. The agent's checks never make a requirement independent nor lower a declared verification.
         */
        @JvmStatic
        public fun requirement(declared: Collection<ResultStatus>, agent: Collection<ResultStatus>): ProvenanceClass = when {
            declared.isNotEmpty() && declared.all { it == ResultStatus.Passed } -> Independent
            ResultStatus.Failed !in declared && ResultStatus.Failed !in agent && ResultStatus.Passed in agent -> AgentTest
            else -> Unverified
        }

        /** The campaign: the worst of its [requirements]; none ⇒ unverified. */
        @JvmStatic
        public fun campaign(requirements: Collection<ProvenanceClass>): ProvenanceClass = requirements.minOrNull() ?: Unverified
    }
}

/**
 * How one acceptance item came to be accepted (I7): tested, reviewed, or accepted by a decider without verification.
 * [origin] and [result] are its criterion's provenance (§4.4 C2) — who created the check and what checking it gave
 * before any decision — `null` on records made without them.
 */
@Serializable
public data class ItemProvenance @JvmOverloads constructor(
    val item: String,
    val how: ProvenanceKind,
    val by: String? = null,
    val decider: Decider? = null,
    val reason: String? = null,
    val evidenceRef: String? = null,
    val origin: Origin? = null,
    val result: ResultStatus? = null,
) {
    /** Who created the check; `null` while [origin] is unknown. */
    val checkBy: Author? get() = origin?.let(Author::of)

    val riskAcceptedBy: RiskAcceptor
        get() = when {
            how != ProvenanceKind.Accepted -> RiskAcceptor.Runtime
            decider == Decider.User -> RiskAcceptor.User
            else -> RiskAcceptor.Policy
        }
}

/** What the resolver concluded, with everything it was concluded from. */
public data class Resolved(
    val resolution: Resolution,
    val gaps: List<Gap>,
    val results: List<ObligationResult>,
    /** Set for [Resolution.Complete]: one line per result. */
    val provenance: List<ItemProvenance>,
    /** Set for [Resolution.Await]. */
    val code: StopCode?,
    val receiptIds: List<String>,
    val evidenceRefs: List<String>,
    /** The decision the resolution applied, if any. */
    val decision: DecisionRecord? = null,
    /** The inputs beside [results] it was resolved from, so a spent rework round can be re-resolved ([spent]). */
    val other: List<String> = emptyList(),
    val considered: DecisionRecord? = null,
    val binding: List<String> = emptyList(),
    /** Plan steps the agent left unticked on an increment whose acceptance is proven (D-368): shown, never a gap. */
    val leftOpen: List<String> = emptyList(),
    /** Optional checks red at their latest receipt, as the runtime records them ([Obligations.knownRed], C1b): shown, never a gap. */
    val knownRed: List<String> = emptyList(),
    /**
     * P8.C.10: this tree's red receipts of a harness regression check whose unknown failures an `Open` item (or an earlier
     * acknowledgment) covered; an accepted increment keeps them, so a later resolution of the same receipt asks no `Open` item.
     */
    val acknowledged: List<String> = emptyList(),
) {
    /** The constructor before [acknowledged] (P8.C.10). Kept for Java callers. */
    public constructor(
        resolution: Resolution,
        gaps: List<Gap>,
        results: List<ObligationResult>,
        provenance: List<ItemProvenance>,
        code: StopCode?,
        receiptIds: List<String>,
        evidenceRefs: List<String>,
        decision: DecisionRecord?,
        other: List<String>,
        considered: DecisionRecord?,
        binding: List<String>,
        leftOpen: List<String>,
        knownRed: List<String>,
    ) : this(resolution, gaps, results, provenance, code, receiptIds, evidenceRefs, decision, other, considered, binding, leftOpen, knownRed, emptyList())

    /** The constructor before [knownRed] (C1b). Kept for Java callers. */
    public constructor(
        resolution: Resolution,
        gaps: List<Gap>,
        results: List<ObligationResult>,
        provenance: List<ItemProvenance>,
        code: StopCode?,
        receiptIds: List<String>,
        evidenceRefs: List<String>,
        decision: DecisionRecord?,
        other: List<String>,
        considered: DecisionRecord?,
        binding: List<String>,
        leftOpen: List<String>,
    ) : this(resolution, gaps, results, provenance, code, receiptIds, evidenceRefs, decision, other, considered, binding, leftOpen, emptyList())

    val missing: List<String> get() = gaps.map { it.text }

    /**
     * The same inputs once the rework round is spent (I4): a reviewer's standing rejection, and whatever the agent left
     * open, then await a decision.
     */
    public fun spent(): Resolved = Resolver.resolve(results, other, considered, reworkSpent = true, binding = binding).copy(leftOpen = leftOpen, knownRed = knownRed, acknowledged = acknowledged)

    /** Results the authority is asked about when this resolution awaits: uncovered unverified ones and reviewer rejections. */
    val undecided: List<ObligationResult>
        get() = results.filter { r -> gaps.any { it.obligation == r.obligation && it.kind != GapKind.Other } && !r.executedFailure }

    /** Reviewer rejections behind a [Resolution.Rework]: their findings reach the agent in a pinned block (D-341). */
    val rejections: List<ObligationResult> get() = results.filter { r -> r.reviewFailure && gaps.any { it.obligation == r.obligation } }
}

/** Results of obligations from records (§8.7, D-337): applicability first, then outcome. */
public object Obligations {
    /**
     * A `run:` item or campaign check from its receipt's [currency]: passed only when current, eligible and green;
     * failed only when current, eligible and red; everything else — no receipt, stale, ineligible, timed out,
     * unavailable, inconclusive, not run — is unverified.
     */
    @JvmStatic
    public fun run(id: String, criterion: String, currency: Currency?): ObligationResult = when {
        currency?.receiptId == null -> ObligationResult(id, ObligationKind.Run, ResultStatus.Unverified, "$id: $criterion — no receipt")
        currency.certifies -> ObligationResult(id, ObligationKind.Run, ResultStatus.Passed, "$id: $criterion — green", currency.receiptId)
        currency.applicability == Applicability.Current && currency.eligible && currency.red ->
            ObligationResult(id, ObligationKind.Run, ResultStatus.Failed, "$id: $criterion — " + currency.reasons.ifEmpty { listOf("outcome failed") }.joinToString("; "), currency.receiptId)
        else -> ObligationResult(id, ObligationKind.Run, ResultStatus.Unverified,
            "$id: $criterion — " + currency.reasons.ifEmpty { listOf("receipt ${currency.receiptId} does not certify the current tree") }.joinToString("; "), currency.receiptId)
    }

    /**
     * A reviewer's word on [id]: an approval signed for [contractVersion] and [candidate] passes; a rejection with at
     * least one blocker or major finding that names a location and an issue fails; anything else — no verdict
     * ([missing] says why), insufficient evidence, a rejection without substance, a verdict for another revision or
     * candidate — is unverified.
     */
    @JvmStatic
    @JvmOverloads
    public fun verdict(id: String, kind: ObligationKind, criterion: String, verdict: Verdict?, contractVersion: Int, candidate: CandidateId?, missing: String? = null): ObligationResult {
        val head = "$id: $criterion"
        if (verdict == null) return ObligationResult(id, kind, ResultStatus.Unverified, "$head — ${missing ?: "no verdict recorded"}")
        val ref = verdict.requestId
        return when {
            verdict.contractRevision != contractVersion ->
                ObligationResult(id, kind, ResultStatus.Unverified, "$head — verdict signed for contract v${verdict.contractRevision}, not v$contractVersion", ref, verdict.signedBy)
            candidate != null && verdict.reviewedCandidate != candidate ->
                ObligationResult(id, kind, ResultStatus.Unverified, "$head — verdict reviewed @${verdict.reviewedCandidate.hash8}, not the current candidate @${candidate.hash8}", ref, verdict.signedBy)
            verdict.approved -> ObligationResult(id, kind, ResultStatus.Passed, "$head — approved by ${verdict.signedBy}", ref, verdict.signedBy)
            else -> {
                val substantive = verdict.findings.filter { it.severity <= Severity.Major && it.location.isNotBlank() && it.issue.isNotBlank() }
                if (substantive.isNotEmpty()) {
                    ObligationResult(id, kind, ResultStatus.Failed,
                        "$head — ${wire(verdict.outcome)} by ${verdict.signedBy}: " + substantive.take(3).joinToString("; ") { "${it.severity.name.lowercase()} ${it.location}: ${it.issue}" },
                        ref, verdict.signedBy, substantive)
                } else {
                    val why = verdict.missingCriterion?.let { "could not assess: $it" } ?: "${wire(verdict.outcome)} without a blocker or major finding"
                    ObligationResult(id, kind, ResultStatus.Unverified, "$head — ${verdict.signedBy} $why", ref, verdict.signedBy, verdict.findings)
                }
            }
        }
    }

    /**
     * Plan §4.3 (C1b): whether a check keeps the I2 rule — red on this tree outside the increment's own items, it needs
     * an `Open` item that names it, and it holds `[>]` back. Only these checks are optional, their red recorded by the
     * runtime instead: the model's own (`CHK-model-*`), lint, and a check the host or the user declared that no acceptance
     * item requires and that is neither the full suite nor a quality gate. Everything else the harness runs itself — the
     * blast radius, the types of touched files, the campaign gate's checks, any check of unknown origin — is mandatory.
     */
    @JvmStatic
    public fun mandatory(check: Check): Boolean = when {
        check.required || check.kind == CheckKind.Full || check.kind == CheckKind.Quality -> true
        check.origin is Origin.Model || check.kind == CheckKind.Lint -> false
        else -> check.origin !is Origin.User && check.origin !is Origin.Amended
    }

    /**
     * The runtime's record of an optional check that is known red (plan §4.3, C1b), in place of the agent's `Open` item:
     * since the red receipt that began it ([Currency.knownRed]); `null` for a mandatory check or one that is not known red.
     */
    @JvmStatic
    public fun knownRed(checkId: String, currency: Currency): String? {
        if (currency.mandatory) return null
        val since = currency.knownRed ?: currency.receiptId?.takeIf { currency.red } ?: return null
        return "$checkId known red since receipt $since (recorded by the runtime)"
    }

    /**
     * P8.C.10: the hold of a harness regression check ([Regressions.CHECKS]) — the scheduler's [Currency.hold], or, from a
     * caller that did not compute it, a current, eligible red read as unknown (no baseline); `null` otherwise.
     */
    @JvmStatic
    public fun hold(checkId: String, currency: Currency): RegressionHold? {
        if (checkId !in Regressions.CHECKS || !currency.mandatory) return null
        currency.hold?.let { return it }
        val receipt = currency.receiptId?.takeIf { currency.red && currency.applicability == Applicability.Current && currency.eligible } ?: return null
        return RegressionHold(listOf(receipt), listOf(receipt), checkId, unknown = listOf(Regressions.NO_BASELINE))
    }

    /**
     * P8.C.10: what the finish receipt discloses of a [hold]: each failure that failed before the change too, each unknown
     * one with why, and each new one (a gap while the work is open; listed when the campaign ended otherwise).
     */
    @JvmStatic
    public fun disclosure(checkId: String, hold: RegressionHold): List<String> =
        hold.regressions.map { "$checkId: new failure against the baseline at s0: $it" } +
            hold.failedBefore.map { "$checkId: $it" } +
            hold.unknown.map { "$checkId: failure not classified ($it)" }

    /** The obligation id prefix of a test-integrity flag; the path follows it. */
    public const val INTEGRITY: String = "integrity:"

    /** Why a test-integrity obligation under [io.astrolabe.IntegrityApproval.Human] waits (C11). */
    public const val HUMAN_REVIEW: String = "integrity change needs a human review"

    /**
     * A test-integrity flag on a required check (§8.6): its reviewer's word, bound like any verdict; `null` when it needs
     * none. A [TestIntegrityFlag.humanOnly] flag (C11) gives a [ObligationResult.humanOnly] result that only a person's
     * approval passes: a model's approval, or none, leaves it unverified with the model's word attached for the person; a
     * model's rejection with substance stays a rejection (rework, then the user's word, D-338).
     */
    @JvmStatic
    public fun flag(flag: TestIntegrityFlag, contractVersion: Int, candidate: CandidateId?): ObligationResult? {
        if (!flag.requiresReview) return null
        val id = "$INTEGRITY${flag.path}"
        val criterion = "acceptance surface ${flag.path} touches ${flag.requiredChecks.joinToString(", ")}"
        if (!flag.humanOnly) return verdict(id, ObligationKind.Integrity, criterion, flag.verdict, contractVersion, candidate, "no approving review of the change to a required check")
        val result = verdict(id, ObligationKind.Integrity, criterion, flag.verdict, contractVersion, candidate, HUMAN_REVIEW).copy(humanOnly = true)
        val model = flag.verdict?.takeIf { it.reviewer != ReviewerKind.Human }
        if (model == null || result.status == ResultStatus.Failed) return result
        return result.copy(status = ResultStatus.Unverified, detail = "$id: $criterion — $HUMAN_REVIEW; ${wire(model.outcome)} by ${model.signedBy} (model) attached")
    }

    private fun wire(outcome: VerdictOutcome): String = if (outcome == VerdictOutcome.InsufficientEvidence) "insufficient_evidence" else outcome.name.lowercase()
}

/**
 * The one acceptance rule (§8.7, D-337): the cell's exit gate, the verifier, final acceptance and resume all resolve a
 * proposal here, so the same inputs always give the same answer. Order:
 * 1. an executed red check → rework; no decision covers it (§8.8) — a failure of the blast radius or the types of touched
 *    files that is new against the baseline at `s0` is one, whatever `Open` says, until it is shown fixed on the tree at
 *    hand: reported once, passed, by an eligible run that finished with a complete record (P8.C.10);
 * 2. something the agent must close ([other]: an open plan step while acceptance is not proven, a red mandatory check
 *    without an `Open` item, a contract or stamp mismatch, an unresolved impact nudge, an unjustified acceptance-surface
 *    change) → rework; a red optional check is no gap: the runtime records it as known red ([Resolved.knownRed], C1b);
 * 3. a current `rework` decision → rework;
 * 4. a reviewer rejection not accepted by a decision → rework, or — once the rework round is spent — await
 *    ([StopCode.ReviewRejected]);
 * 5. an unverified obligation not accepted by a decision → await ([StopCode.AcceptanceDecision];
 *    [StopCode.IntegrityReview] while one only a person settles waits, C11);
 * 6. otherwise complete, with provenance per item: tested, reviewed, or accepted by whom and why (I7).
 * An `accept` decision covers exactly the obligations its request listed; a spent `rework` decision covers nothing. A
 * policy's `accept` covers only unverified obligations that are not [ObligationResult.humanOnly]; the user's covers any
 * it names — a reviewer's rejection (D-338) and, under [io.astrolabe.IntegrityApproval.Human], a test-integrity change
 * with no person's review (C11: the user is a person; the item is accepted, never verified, and its run checks stay the
 * agent's evidence).
 */
public object Resolver {
    @JvmStatic
    @JvmOverloads
    public fun resolve(
        results: List<ObligationResult>,
        other: List<String> = emptyList(),
        decision: DecisionRecord? = null,
        reworkSpent: Boolean = false,
        /** Conditions that bind the proposal to its tree and contract; never put to a decider. */
        binding: List<String> = emptyList(),
    ): Resolved {
        val active = decision?.takeUnless { it.spent }
        val accept = active?.takeIf { it.decision.kind == DecisionKind.Accept }
        val named = accept?.obligations?.toSet().orEmpty()
        // I2: once the rework round is spent, what the agent left open goes to the decider — never a failure by itself.
        val all = results + if (reworkSpent) other.mapIndexed { i, text -> ObligationResult("open:${i + 1}", ObligationKind.Check, ResultStatus.Unverified, text) } else emptyList()
        val open = binding + if (reworkSpent) emptyList() else other
        // D-338: a policy covers only what could not be verified; accepting over a reviewer's rejection — or what only a
        // person settles (C11) — is the user's word.
        fun covered(r: ObligationResult): Boolean = r.obligation in named && (r.status == ResultStatus.Unverified && !r.humanOnly || accept?.decision?.decider == Decider.User)
        val accepted = all.filter(::covered).map { it.obligation }.toSet()
        val gaps = ArrayList<Gap>()
        all.filter { it.executedFailure }.forEach { gaps += Gap(GapKind.Failed, it.obligation, it.detail) }
        open.forEach { gaps += Gap(GapKind.Other, null, it) }
        val rejected = all.filter { it.reviewFailure && it.obligation !in accepted }
        rejected.forEach { gaps += Gap(GapKind.Failed, it.obligation, it.detail) }
        val unverified = all.filter { it.status == ResultStatus.Unverified && it.obligation !in accepted }
        unverified.forEach { gaps += Gap(GapKind.Unverified, it.obligation, it.detail) }
        val rework = active?.takeIf { it.decision.kind == DecisionKind.Rework }
        if (rework != null) gaps += Gap(GapKind.Other, null, "rework requested by ${rework.decision.by}: ${rework.decision.reason}")
        val passed = all.filter { it.status == ResultStatus.Passed }
        val receipts = passed.filter { it.kind == ObligationKind.Run }.mapNotNull { it.evidenceRef }.distinct()
        // C11: what waits for a person's review says so, whatever else waits beside it.
        val person = unverified.any { it.humanOnly }
        val (resolution, code) = when {
            all.any { it.executedFailure } || open.isNotEmpty() || rework != null -> Resolution.Rework to null
            rejected.isNotEmpty() -> if (reworkSpent) Resolution.Await to (if (person) StopCode.IntegrityReview else StopCode.ReviewRejected) else Resolution.Rework to null
            unverified.isNotEmpty() -> Resolution.Await to (if (person) StopCode.IntegrityReview else StopCode.AcceptanceDecision)
            else -> Resolution.Complete to null
        }
        val applied = active?.takeIf { resolution == Resolution.Complete && accepted.isNotEmpty() || rework != null }
        val provenance = if (resolution != Resolution.Complete) emptyList() else all.map { r ->
            when {
                r.status == ResultStatus.Passed && r.kind == ObligationKind.Run -> ItemProvenance(r.obligation, ProvenanceKind.Tested, evidenceRef = r.evidenceRef, origin = r.origin, result = r.status)
                r.status == ResultStatus.Passed -> ItemProvenance(r.obligation, ProvenanceKind.Reviewed, r.by, evidenceRef = r.evidenceRef, origin = r.origin, result = r.status)
                else -> ItemProvenance(r.obligation, ProvenanceKind.Accepted, active!!.decision.by, active.decision.decider, active.decision.reason, active.decision.requestId, r.origin, r.status)
            }
        }
        val evidence = (passed.mapNotNull { it.evidenceRef } + listOfNotNull(applied?.decision?.requestId?.takeIf { resolution == Resolution.Complete })).distinct()
        return Resolved(resolution, gaps, all, provenance, code, receipts, evidence, applied, other.toList(), decision, binding.toList())
    }

    /**
     * An increment's completion proposal (§8.7): the results of its acceptance items from [currencies] and the
     * reviewers' [verdicts] (with [unavailable] saying why an item has none), its test-integrity [flags], and the
     * register and impact conditions the agent must close, resolved with [decision].
     */
    @JvmStatic
    @JvmOverloads
    public fun increment(
        register: Register,
        contract: Contract,
        increment: Increment,
        currencies: Map<String, Currency>,
        verdicts: Map<String, Verdict> = emptyMap(),
        unavailable: Map<String, String> = emptyMap(),
        flags: List<TestIntegrityFlag> = emptyList(),
        unresolvedImpactNudges: List<String> = emptyList(),
        candidate: CandidateId? = null,
        decision: DecisionRecord? = null,
        reworkSpent: Boolean = false,
        /** Conditions binding the proposal to the tree and contract now (a moved stamp, another version): never decided. */
        binding: List<String> = emptyList(),
        /** Obligations beside the increment's items: an owed increment review without a `review:` item (§8.8). */
        extra: List<ObligationResult> = emptyList(),
        /**
         * P8.C.10: red receipts of a harness regression check an earlier accepted increment acknowledged with an `Open` item
         * ([Resolved.acknowledged], kept with the increment's evidence): the same receipt is not refused again for want of one.
         */
        acknowledged: Collection<String> = emptyList(),
    ): Resolved {
        val results = ArrayList<ObligationResult>(extra)
        val open = ArrayList<String>()
        for (id in increment.accept) {
            when (val item = contract.acceptance(id)) {
                null -> open += "$id: not an acceptance item of contract v${contract.version}"
                is Acceptance.Run -> results += Obligations.run(id, item.criterion, currencies[id] ?: currencies[Checks.acceptId(id)]).copy(origin = item.origin)
                is Acceptance.Check -> results += Obligations.verdict(id, ObligationKind.Check, item.criterion, verdicts[id], contract.version, candidate, unavailable[id]).copy(origin = item.origin)
                is Acceptance.Review -> results += Obligations.verdict(id, ObligationKind.Review, item.criterion, verdicts[id], contract.version, candidate, unavailable[id]).copy(origin = item.origin)
            }
        }
        // A red check outside the increment's required set: a mandatory one needs an Open item that names it; a required red is a result above.
        val requiredIds = increment.accept.map { Checks.acceptId(it) }.toSet() + increment.accept.toSet()
        val openTexts = register.open.filter { !it.closed }.map { it.text }
        val knownRed = ArrayList<String>()
        val acknowledging = ArrayList<String>()
        for ((checkId, currency) in currencies) {
            if (checkId in requiredIds || currency.receiptId == null) continue
            // Plan §4.3 (C1b): an optional check's red is the runtime's record, not the agent's, until a later `passed`
            // receipt of it on the tree now; a mandatory one keeps the rule below.
            if (!currency.mandatory) {
                Obligations.knownRed(checkId, currency)?.let { knownRed += it }
                continue
            }
            // P8.C.10: the failures the harness found itself (blast radius, types of touched files), held until they pass on
            // this tree: a new one against the baseline at s0 is never covered by an Open item; one that failed before the
            // change too the runtime acknowledges (disclosed, no gap); an unknown one keeps the D-400 rule while a red run is
            // current — an Open item, or an earlier acceptance that acknowledged that same red receipt.
            if (checkId in Regressions.CHECKS) {
                val hold = Obligations.hold(checkId, currency) ?: continue
                when (hold.kind) {
                    RedClass.FailedBefore -> Unit
                    RedClass.New -> results += ObligationResult("red:$checkId", ObligationKind.Run, ResultStatus.Failed,
                        "$checkId: fix and rerun `${hold.command}` — new failures against the baseline at s0, which no Open item clears: " +
                            hold.regressions.take(MAX_NAMED).joinToString("; ") + (if (hold.regressions.size > MAX_NAMED) "; +${hold.regressions.size - MAX_NAMED} more" else ""),
                        currency.receiptId)
                    RedClass.Unknown -> when {
                        hold.current.isEmpty() -> Unit
                        hold.current.all { it in acknowledged } -> acknowledging += hold.current
                        openTexts.any { it.contains(checkId) } -> acknowledging += hold.current
                        else -> results += ObligationResult("red:$checkId", ObligationKind.Run, ResultStatus.Failed, "$checkId is red without an Open item naming it", currency.receiptId)
                    }
                }
                continue
            }
            // Only a red receipt of this very tree is a red line; a stale red one is history (D-337).
            if (!(currency.red && currency.applicability == Applicability.Current && currency.eligible)) continue
            // A red test is "not done" (I2) until the agent records it in Open: never put to a decider.
            if (openTexts.none { it.contains(checkId) }) results += ObligationResult("red:$checkId", ObligationKind.Run, ResultStatus.Failed, "$checkId is red without an Open item naming it", currency.receiptId)
        }
        val flagged = flags.mapNotNull { flag -> Obligations.flag(flag, contract.version, candidate)?.let { flag to it } }
        // D-368: the plan is the agent's note, the acceptance its oracle — an unticked step blocks only an unproven exit.
        val proven = results.isNotEmpty() && results.all { it.status == ResultStatus.Passed } && open.isEmpty() &&
            flagged.all { (flag, result) -> !flag.reason.isNullOrBlank() && result.status == ResultStatus.Passed }
        val steps = register.plan.filter { it.mark == Mark.Todo || it.mark == Mark.Cursor }
        val leftOpen = if (proven) steps.map { "step ${it.n} '${it.text}' left open by the agent" } else emptyList()
        if (!proven) steps.forEach { step ->
            open += "step ${step.n} ${step.mark.text} '${step.text}' has no disposition (done, cancelled or an explicit non-completed exit)"
        }
        for ((flag, result) in flagged) {
            if (flag.reason.isNullOrBlank()) open += "acceptance surface ${flag.path} touches ${flag.requiredChecks.joinToString(", ")} without a recorded justification"
            results += result
        }
        unresolvedImpactNudges.forEach { open += "unresolved impact nudge: $it" }
        return resolve(results, open, decision, reworkSpent, binding).copy(leftOpen = leftOpen, knownRed = knownRed, acknowledged = acknowledging.distinct())
    }

    /** How many failures a regression gap names before it counts the rest. */
    private const val MAX_NAMED = 5
}
