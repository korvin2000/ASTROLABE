package io.astrolabe.verify

import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.UserRequest
import io.astrolabe.id.CandidateId
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
}

/** One obligation an acceptance decision is asked about: unverified with its reason, or rejected by review with its findings. */
@Serializable
public data class DecisionItem(
    val obligation: String,
    val kind: ObligationKind,
    val status: ResultStatus,
    val reason: String,
    val findings: List<Finding> = emptyList(),
    val by: String? = null,
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
) {
    val missing: List<String> get() = gaps.map { it.text }

    /**
     * The same inputs once the rework round is spent (I4): a reviewer's standing rejection, and whatever the agent left
     * open, then await a decision.
     */
    public fun spent(): Resolved = Resolver.resolve(results, other, considered, reworkSpent = true, binding = binding).copy(leftOpen = leftOpen, knownRed = knownRed)

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
     * Plan §4.3 (C1b): a mandatory check — required by an acceptance item, or by the campaign gate (the full suite, a
     * quality gate) — keeps the I2 rule: red outside the increment's own items, it needs an `Open` item that names it.
     * Every other check is optional: a fast check on touched files, a step check without an item, the model's own.
     */
    @JvmStatic
    public fun mandatory(check: Check): Boolean = check.required || check.trigger == Trigger.CampaignEnd

    /** The runtime's record of an optional check whose latest receipt is red (plan §4.3, C1b): it replaces the agent's `Open` item. */
    @JvmStatic
    public fun knownRed(checkId: String, receiptId: String): String = "$checkId known red since receipt $receiptId (recorded by the runtime)"

    /** The obligation id prefix of a test-integrity flag; the path follows it. */
    public const val INTEGRITY: String = "integrity:"

    /** A test-integrity flag on a required check (§8.6): its reviewer's word, bound like any verdict; `null` when it needs none. */
    @JvmStatic
    public fun flag(flag: TestIntegrityFlag, contractVersion: Int, candidate: CandidateId?): ObligationResult? {
        if (flag.requiredChecks.isEmpty() || flag.kind == TestIntegrity.ADDITIONS_ONLY) return null
        return verdict("$INTEGRITY${flag.path}", ObligationKind.Integrity, "acceptance surface ${flag.path} touches ${flag.requiredChecks.joinToString(", ")}",
            flag.verdict, contractVersion, candidate, "no approving review of the change to a required check")
    }

    private fun wire(outcome: VerdictOutcome): String = if (outcome == VerdictOutcome.InsufficientEvidence) "insufficient_evidence" else outcome.name.lowercase()
}

/**
 * The one acceptance rule (§8.7, D-337): the cell's exit gate, the verifier, final acceptance and resume all resolve a
 * proposal here, so the same inputs always give the same answer. Order:
 * 1. an executed red check → rework; no decision covers it (§8.8);
 * 2. something the agent must close ([other]: an open plan step while acceptance is not proven, a red mandatory check
 *    without an `Open` item, a contract or stamp mismatch, an unresolved impact nudge, an unjustified acceptance-surface
 *    change) → rework; a red optional check is no gap: the runtime records it as known red ([Resolved.knownRed], C1b);
 * 3. a current `rework` decision → rework;
 * 4. a reviewer rejection not accepted by a decision → rework, or — once the rework round is spent — await
 *    ([StopCode.ReviewRejected]);
 * 5. an unverified obligation not accepted by a decision → await ([StopCode.AcceptanceDecision]);
 * 6. otherwise complete, with provenance per item: tested, reviewed, or accepted by whom and why (I7).
 * An `accept` decision covers exactly the obligations its request listed; a spent `rework` decision covers nothing.
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
        // D-338: a policy covers only what could not be verified; accepting over a reviewer's rejection is the user's word.
        fun covered(r: ObligationResult): Boolean = r.obligation in named && (r.status == ResultStatus.Unverified || accept?.decision?.decider == Decider.User)
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
        val (resolution, code) = when {
            all.any { it.executedFailure } || open.isNotEmpty() || rework != null -> Resolution.Rework to null
            rejected.isNotEmpty() -> if (reworkSpent) Resolution.Await to StopCode.ReviewRejected else Resolution.Rework to null
            unverified.isNotEmpty() -> Resolution.Await to StopCode.AcceptanceDecision
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
        for ((checkId, currency) in currencies) {
            if (checkId in requiredIds || currency.receiptId == null) continue
            // Plan §4.3 (C1b): an optional check (the model's own included, C1a) red at its latest receipt is recorded by
            // the runtime, not by the agent; the record goes once a later receipt of the check is not red.
            if (!currency.mandatory || checkId.startsWith(Checks.MODEL_PREFIX)) {
                if (currency.red) knownRed += Obligations.knownRed(checkId, currency.receiptId)
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
        return resolve(results, open, decision, reworkSpent, binding).copy(leftOpen = leftOpen, knownRed = knownRed)
    }
}
