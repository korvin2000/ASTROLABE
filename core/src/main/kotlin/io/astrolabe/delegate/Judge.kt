package io.astrolabe.delegate

import io.astrolabe.atlas.RiskFloorInput
import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.route.Router
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.Tier
import io.astrolabe.verify.Finding
import io.astrolabe.verify.FindingKind
import io.astrolabe.verify.ReviewCoverage
import io.astrolabe.verify.Severity
import io.astrolabe.verify.TestIntegrityFlag
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What an increment-scope review is decided from (§8.8): the increment, what the cell produced, and at what tier. */
public data class IncrementReviewInput @JvmOverloads constructor(
    val contract: Contract,
    val increment: Increment,
    /** The role of the cell whose output is reviewed; a review cell's own verdict is never reviewed again. */
    val role: Role,
    /** The tier the cell ran at; `null` when unrouted. */
    val tier: Tier?,
    val flags: List<TestIntegrityFlag>,
    /** Paths the cell changed. */
    val touched: List<String>,
    /** `CON`/`ADR` note id → anchored paths ([io.astrolabe.kb.Kb.contractAnchors] and ADR anchors). */
    val anchors: Map<String, Set<String>>,
    val impact: RiskFloorInput? = null,
)

/**
 * The two review scopes' predicates (§8.8): increment scope fires on the risk floor, a contract/ADR touch, cheap-tier
 * output, a test-integrity flag or a `review:` item; campaign scope is `(shape ≥ S2 ∧ increments ≥ 3) ∨ refactor
 * mode ∨ explicitly required` ([io.astrolabe.campaign.CampaignFinish.reviewRequired]). Pure functions of records.
 */
public object ReviewTriggers {
    /** Why an increment-scope review is owed (D-122), in the §8.8 order; empty when none is. */
    @JvmStatic
    public fun increment(input: IncrementReviewInput): List<String> {
        // §8.8: the reviewer never recursively demands a review of its own verdict.
        if (input.role.name == Roles.review.name) return emptyList()
        val reasons = ArrayList<String>()
        val floor = Router.riskFloor(input.increment.risk ?: input.contract.risk, input.impact)
        if (floor >= Tier.High) reasons += "risk floor ${floor.name.lowercase()}"
        val touched = input.anchors.filterValues { anchored -> input.touched.any { it in anchored } }.keys.sorted()
        if (touched.isNotEmpty() || input.contract.contractsTouched.isNotEmpty()) {
            reasons += "contract/ADR touch: " + (touched + input.contract.contractsTouched).distinct().joinToString(", ")
        }
        if (input.tier == Tier.Low) reasons += "cheap-tier output (${input.tier.name.lowercase()})"
        val flagged = input.flags.filter { it.blocksCompletion }.map { "${it.path} ${it.kind}" }
        if (flagged.isNotEmpty()) reasons += "test-integrity flag: ${flagged.joinToString(", ")}"
        val items = input.increment.accept.filter { input.contract.acceptance(it) is Acceptance.Review }
        if (items.isNotEmpty()) reasons += "review: item ${items.joinToString(", ")}"
        return reasons
    }

    /** The campaign-scope trigger line; a campaign review is structural, so it routes as critical. */
    public const val CAMPAIGN: String = "campaign scope"

    /** §11.1: a review owed for risk, a contract touch, test integrity or the campaign scope is critical; any other is routine. */
    @JvmStatic
    public fun function(reasons: List<String>): RoutingFunction =
        if (reasons.any { it.startsWith("risk floor") || it.startsWith("contract/ADR touch") || it.startsWith("test-integrity") || it.startsWith(CAMPAIGN) }) RoutingFunction.ReviewCritical
        else RoutingFunction.ReviewRoutine
}

/** The judge's final text parsed at the boundary, or what keeps it from being a verdict. */
public sealed interface JudgeOutput {
    public data class Parsed(val verdict: Verdict) : JudgeOutput

    public data class Gaps(val gaps: List<String>) : JudgeOutput
}

/** Where an `escalate` verdict or a tie goes (§8.8): the next tier up, else a human. */
public sealed interface Escalation {
    public data class HigherTier(val tier: Tier) : Escalation

    public data object Human : Escalation
}

/** One competing proposal (§8.8) and how the judge is shown it. */
public data class Proposal(val id: String, val text: String)

public data class Presented(val label: String, val proposalId: String, val text: String)

/**
 * The judge protocol of §8.8: the verdict form, its validator, symmetric presentation of competing proposals and the
 * escalation path. The judge's coverage is telemetry (what its cell was shown), never its word (F14); its signature
 * is its cell id; the verdict binds the packet's contract version and candidate.
 */
public object Judge {
    private val JSON = Json { ignoreUnknownKeys = true }
    private val LOCATION = Regex("""^[^\s:@]+:\d+(-\d+)?(@[0-9a-f]+)?$""")

    /** The verdict form the judge is told to end with. */
    public const val OUTPUT: String = "end with the verdict as JSON: {\"verdict\": \"approve|revise|reject|insufficient_evidence|escalate\", " +
        "\"findings\": [{\"severity\": \"blocker|major|minor|nit\", \"location\": \"path:line\", \"issue\": …, \"suggestedFix\": …, \"kind\": \"correctness|contract|quality|test-integrity\"}], " +
        "\"contractViolations\": […], \"confidence\": 0.0-1.0, \"missingCriterion\": \"the criterion you could not assess (insufficient_evidence only)\"}"

    /**
     * Parses [text] into a [Verdict] for [packet]: signed by [signer], coverage from [telemetry] — the files the judge's
     * cell was shown, the ranges it displayed, and the reviewed paths it never looked at.
     */
    @JvmStatic
    public fun parse(text: String, packet: EvidencePacket, telemetry: ResultPacket, signer: String): JudgeOutput {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val root = if (start < 0 || end <= start) null else try {
            JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        }
        if (root == null) return JudgeOutput.Gaps(listOf(OUTPUT))
        val gaps = ArrayList<String>()
        val outcome = when (root.text("verdict")) {
            "approve" -> VerdictOutcome.Approve
            "revise" -> VerdictOutcome.Revise
            "reject" -> VerdictOutcome.Reject
            "insufficient_evidence" -> VerdictOutcome.InsufficientEvidence
            "escalate" -> VerdictOutcome.Escalate
            else -> null
        }
        if (outcome == null) gaps += "verdict must be approve|revise|reject|insufficient_evidence|escalate"
        val confidence = (root["confidence"] as? JsonPrimitive)?.doubleOrNull
        if (confidence == null || confidence !in 0.0..1.0) gaps += "confidence is a number in 0.0–1.0"
        val missing = root.text("missingCriterion")
        if (outcome == VerdictOutcome.InsufficientEvidence && missing == null) gaps += "insufficient_evidence names the missing criterion"
        val findings = ArrayList<Finding>()
        (root["findings"] as? JsonArray ?: JsonArray(emptyList())).forEachIndexed { i, element ->
            val f = element as? JsonObject
            val severity = f?.text("severity")?.let { s -> Severity.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } }
            val kind = f?.text("kind")?.let { k -> FindingKind.entries.firstOrNull { it.name.equals(k.replace("-", ""), ignoreCase = true) } }
            val location = f?.text("location")
            val issue = f?.text("issue")
            when {
                severity == null || kind == null || issue == null -> gaps += "finding ${i + 1}: needs severity, kind and issue"
                location == null || !LOCATION.matches(location) -> gaps += "finding ${i + 1}: location is path:line"
                else -> findings += Finding(severity, located(location, packet, telemetry), issue, f.text("suggestedFix"), kind)
            }
        }
        if (outcome == VerdictOutcome.Approve && findings.any { it.severity <= Severity.Major }) gaps += "an approval carries no blocker or major finding: revise or reject instead"
        if (gaps.isNotEmpty()) return JudgeOutput.Gaps(gaps)
        val shown = telemetry.readVersions.keys.sorted()
        val coverage = ReviewCoverage(
            filesReviewed = shown,
            ranges = telemetry.worksetExport.map { "${it.path}:${it.range}@${it.version.hash8}" },
            unread = packet.evidenceVersions.keys.filter { it !in telemetry.readVersions }.sorted(),
        )
        return JudgeOutput.Parsed(
            Verdict(
                requestId = packet.id, contractRevision = packet.contractVersion, reviewedCandidate = packet.candidate, outcome = outcome!!,
                findings = findings, coverage = coverage, contractViolations = root.texts("contractViolations"), confidence = confidence!!,
                signedBy = signer, missingCriterion = missing,
            ),
        )
    }

    /**
     * The review role's [RoleCompletion] (§3.7 `validate_role_output` for the Verdict packet): accepted once the text
     * parses into a verdict for [packet]; [onVerdict] receives it. A review cell has no exit gate of its own, so it is
     * never asked to review its own verdict.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(packet: EvidencePacket, onVerdict: (Verdict) -> Unit, maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            val signer = "review-cell:${output.packet.ids.context!!.value}"
            when (val parsed = parse(output.text, packet, output.packet, signer)) {
                is JudgeOutput.Gaps -> if (output.refusals + 1 >= maxFinalizations) CompletionDecision.CannotProgress(parsed.gaps) else CompletionDecision.Continue(parsed.gaps)
                is JudgeOutput.Parsed -> {
                    onVerdict(parsed.verdict)
                    CompletionDecision.Accepted(listOf(packet.id))
                }
            }
        }
    }

    /**
     * Competing proposals presented symmetrically (§8.8): an order drawn from [seed] (so it is random with respect to
     * the proposers and reproducible from the record) and one equal token budget per proposal, labelled only by
     * position; a proposal over the budget is cut at the same bound as every other.
     */
    @JvmStatic
    public fun present(proposals: List<Proposal>, seed: Long, budgetTokens: Int, estimator: TokenEstimator): List<Presented> {
        require(budgetTokens > 0) { "each proposal gets a positive budget" }
        require(proposals.map { it.id }.toSet().size == proposals.size) { "proposal ids are unique" }
        val order = proposals.shuffled(java.util.Random(seed))
        return order.mapIndexed { i, p -> Presented("Proposal ${'A' + i}", p.id, cut(p.text, budgetTokens, estimator)) }
    }

    /** §8.8: `escalate` or a tie goes one tier up, or to a human from the top tier. */
    @JvmStatic
    public fun escalation(tier: Tier): Escalation = if (tier.model && tier != Tier.ExtraHigh) Escalation.HigherTier(tier.promoted()) else Escalation.Human

    private fun cut(text: String, budgetTokens: Int, estimator: TokenEstimator): String {
        if (estimator.estimate(text).upperBoundTokens <= budgetTokens) return text
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (estimator.estimate(text.substring(0, mid) + CUT).upperBoundTokens <= budgetTokens) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + CUT
    }

    private const val CUT = " … [cut at the shared budget]"

    /** A location on a path the judge was shown, or one the packet's diff covers, carries that version's hash. */
    private fun located(location: String, packet: EvidencePacket, telemetry: ResultPacket): String {
        if ('@' in location) return location
        val path = location.substringBefore(':')
        val version = telemetry.readVersions[path] ?: packet.evidenceVersions[path] ?: return location
        return "$location@${version.hash8}"
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()
}
