package io.astrolabe.recover

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CompletionDecision
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.delegate.ChildBudget
import io.astrolabe.delegate.ChildCell
import io.astrolabe.delegate.ChildSeat
import io.astrolabe.id.ContextId
import io.astrolabe.id.IdGen
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.route.RoutingFunction
import io.astrolabe.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The repair helper's final no-call text parsed at the boundary (§3.4 repair row), or what keeps it from being a packet. */
public sealed interface RepairParsed {
    public data class Proposal(val proposal: RepairProposal) : RepairParsed

    public data class Gaps(val gaps: List<String>) : RepairParsed
}

/**
 * The repair helper's Diagnosis packet (§3.4, §13.2 step 4, D-163): the cell ends with
 * `{"outcome": "fixed|diagnosis|escalate", "text": …, "call": {"op": …, "arguments": …}, "result": …}` as its no-call
 * text. A `fixed` names the corrected call — an op inside the capsule mask — and its observed result; its text stays a
 * claim until the caller's original acceptance re-verifies ([Repair]). Every text is at most
 * [Repair.MAX_DIAGNOSIS_TOKENS] tokens.
 */
public object RepairPacket {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** The packet form the helper is told to end with. */
    public const val OUTPUT: String = "end with the repair packet as JSON: {\"outcome\": \"fixed|diagnosis|escalate\", \"text\": \"≤ 100 tokens\", " +
        "\"call\": {\"op\": \"family.op\", \"arguments\": \"…\"}, \"result\": \"what the corrected call returned\"} (call and result only for fixed)"

    @JvmStatic
    public fun parse(text: String, mask: ToolMask, estimator: TokenEstimator): RepairParsed {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return RepairParsed.Gaps(listOf(OUTPUT))
        val root = try {
            JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject
        } catch (malformed: IllegalArgumentException) {
            null
        } ?: return RepairParsed.Gaps(listOf("the packet is not a JSON object: $OUTPUT"))
        val gaps = ArrayList<String>()
        val body = root.text("text")
        if (body != null && estimator.estimate(body).upperBoundTokens > Repair.MAX_DIAGNOSIS_TOKENS) gaps += "text is over ${Repair.MAX_DIAGNOSIS_TOKENS} tokens: shorten the diagnosis"
        val proposal = when (root.text("outcome")) {
            "diagnosis" -> body?.let { RepairProposal.Diagnosis(it) }.also { if (it == null) gaps += "a diagnosis needs its text" }
            "escalate" -> body?.let { RepairProposal.Escalate(it) }.also { if (it == null) gaps += "an escalation names why in its text" }
            "fixed" -> {
                val call = root["call"] as? JsonObject
                val op = call?.text("op")
                val result = root.text("result")
                when {
                    op == null || result == null -> null.also { gaps += "fixed names the corrected call {op, arguments} and its result" }
                    !mask.allows(op) -> null.also { gaps += "$op is outside the capsule's tools (${mask.allowed.sorted().joinToString(", ")})" }
                    else -> RepairProposal.Fixed(CorrectedCall(op, call.text("arguments") ?: ""), result)
                }
            }
            else -> null.also { gaps += "outcome is fixed, diagnosis or escalate" }
        }
        return if (gaps.isEmpty() && proposal != null) RepairParsed.Proposal(proposal) else RepairParsed.Gaps(gaps)
    }

    /**
     * The repair role's [RoleCompletion] (§3.7 `validate_role_output` for the Diagnosis packet): accepted once the text
     * parses into a proposal inside [mask]; [onProposal] receives it. Accepting it asserts nothing about the failure:
     * the caller re-checks the original acceptance. Gaps continue the cell, at most [maxFinalizations] times.
     */
    @JvmStatic
    @JvmOverloads
    public fun completion(mask: ToolMask, estimator: TokenEstimator, onProposal: (RepairProposal) -> Unit, maxFinalizations: Int = Verifier().maxFinalizations): RoleCompletion {
        require(maxFinalizations >= 1) { "maxFinalizations must be ≥ 1" }
        return RoleCompletion { output, _ ->
            when (val parsed = parse(output.text, mask, estimator)) {
                is RepairParsed.Gaps ->
                    if (output.refusals + 1 >= maxFinalizations) CompletionDecision.CannotProgress(parsed.gaps) else CompletionDecision.Continue(parsed.gaps)
                is RepairParsed.Proposal -> {
                    onProposal(parsed.proposal)
                    CompletionDecision.Accepted(emptyList())
                }
            }
        }
    }

    /** The helper's brief: the capsule only (§13.2 step 4, no transcript), the earlier attempts and the required output. */
    @JvmStatic
    public fun brief(request: RepairRequest): String = buildString {
        val c = request.capsule
        append("Failure capsule — attempt ").append(request.attempt).append('\n')
        append("Intended operation: ").append(c.intendedOperation).append(" ").append(c.callArguments).append('\n')
        append("Acceptance it must meet: ").append(c.acceptanceCriterion).append('\n')
        append("Environment: ").append(c.environment).append('\n')
        append("Error or exit: ").append(c.errorOrExit).append('\n')
        if (c.artifactVersions.isNotEmpty()) append("Artifact versions: ").append(c.artifactVersions.entries.joinToString(", ") { "${it.key}@${it.value.hash8}" }).append('\n')
        if (c.completedEffects.isNotEmpty()) append("Completed effects: ").append(c.completedEffects.joinToString("; ")).append('\n')
        if (c.rawEvidenceRefs.isNotEmpty()) append("Evidence: ").append(c.rawEvidenceRefs.joinToString(", ")).append('\n')
        (c.previousAttempts + request.previous).takeIf { it.isNotEmpty() }?.let { append("Previous attempts: ").append(it.joinToString("; ")).append('\n') }
        if (c.allowedFixes.isNotEmpty()) append("Allowed fixes: ").append(c.allowedFixes.joinToString("; ")).append('\n')
        append("Tools: ").append(request.mask.allowed.sorted().joinToString(", ")).append('\n')
        append("Required output: ").append(OUTPUT)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * The real repair cell behind [RepairRunner] (§3.4 repair row, D-163): each attempt is a fresh child cell under a new
 * context id, routed by the `RepairHelper` row, with the request's role and capsule mask, the capsule as its only brief
 * and [RepairPacket.completion] as its validator. A cell that ends without a packet is an escalation, never a fix.
 */
public class CellRepairRunner @JvmOverloads constructor(
    private val cell: ChildCell,
    private val idGen: IdGen,
    private val cancellation: Cancellation,
    private val estimator: TokenEstimator,
    private val budget: ChildBudget = DEFAULT_BUDGET,
) : RepairRunner {
    override suspend fun attempt(request: RepairRequest): RepairProposal {
        var proposal: RepairProposal? = null
        val tokens = Tokens(minOf(budget.tokens.value, request.capsule.remainingBudget.value).coerceAtLeast(1))
        val seat = ChildSeat(ContextId(idGen.next("repair")), cancellation, RoutingFunction.RepairHelper)
        val exit = cell.run(seat, request.role, RepairPacket.completion(request.mask, estimator, { proposal = it }), budget.copy(tokens = tokens), RepairPacket.brief(request))
            ?: return RepairProposal.Escalate("the repair cell was cancelled before it ended")
        return proposal.takeIf { exit is CellExit.Completed }
            ?: RepairProposal.Escalate("the repair cell ended ${exit.packet.status.wire}: ${exit.packet.reason ?: "no repair packet"}")
    }

    public companion object {
        /** A fresh small context (§3.4): 6 turns on 12K tokens `[ESTIMATE]`, never above the capsule's remaining budget. */
        @JvmField
        public val DEFAULT_BUDGET: ChildBudget = ChildBudget(6, Tokens(12_000))
    }
}
