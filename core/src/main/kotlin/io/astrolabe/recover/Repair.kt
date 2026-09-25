package io.astrolabe.recover

import io.astrolabe.Defaults
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Role
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Shape
import io.astrolabe.provider.Profile
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.ToolMask
import io.astrolabe.route.Routed
import io.astrolabe.route.Router
import io.astrolabe.route.RoutingFunction
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy

/** A corrected call the helper proposes: an op inside the capsule mask and its arguments. */
public data class CorrectedCall(val op: String, val arguments: String)

/** What one helper attempt returns (§3.4 repair row). `Fixed` is a claim until the original acceptance re-verifies. */
public sealed interface RepairProposal {
    public data class Fixed(val call: CorrectedCall, val result: String) : RepairProposal

    public data class Diagnosis(val text: String) : RepairProposal

    public data class Escalate(val text: String) : RepairProposal
}

/** One attempt's input: the capsule only, a fresh small context under the repair role, its mask and the routed profile. */
public data class RepairRequest(
    val capsule: Capsule,
    val attempt: Int,
    val role: Role,
    val mask: ToolMask,
    val profile: Profile,
    /** What earlier attempts of this repair claimed and why they were not accepted. */
    val previous: List<String>,
)

/** Runs one helper attempt (the fresh repair cell; a fake in tests). */
public fun interface RepairRunner {
    public suspend fun attempt(request: RepairRequest): RepairProposal
}

/** The original acceptance criterion re-checked by the caller's verifier, never by the helper. */
public data class AcceptanceCheck(val holds: Boolean, val evidence: String)

public fun interface OriginalAcceptance {
    public suspend fun check(capsule: Capsule): AcceptanceCheck
}

/** The repair's outcome; [diagnosis] is the ≤ 100-token line the owning cell's `[A]` always receives. */
public sealed interface RepairOutcome {
    public val diagnosis: String
    public val attempts: Int

    public data class Fixed(val call: CorrectedCall, val evidence: String, override val diagnosis: String, override val attempts: Int) : RepairOutcome

    public data class Diagnosed(override val diagnosis: String, override val attempts: Int) : RepairOutcome

    public data class Escalated(override val diagnosis: String, val reason: String, override val attempts: Int) : RepairOutcome
}

/**
 * The diagnosis lines addressed to one owning cell (§13.2 invariant: the caller's `[A]` always receives the
 * diagnosis). The cell renders the latest [MAX_LINES] every turn beside the fired trips, never reduced (D-137).
 */
public class Diagnoses {
    private val posted = ArrayList<String>()

    public fun post(line: String) {
        require(line.isNotBlank()) { "a diagnosis line is not empty" }
        synchronized(posted) { posted += line }
    }

    public fun lines(): List<String> = synchronized(posted) { posted.takeLast(MAX_LINES) }

    public companion object {
        public const val MAX_LINES: Int = 2
    }
}

/**
 * The capsule repair helper (§13.2 step 4, §3.4, D14): S2+ only, routed as `RepairHelper` (low tier), at most
 * [Defaults.repairCalls] (≤ 2) attempts with tools masked to the failing family + `look` + `run`. A `fixed` claim
 * counts only when the caller's [OriginalAcceptance] holds afterwards, so a repair can never rewrite a failure
 * into apparent success (FX-34: deleting the failing test leaves the original acceptance failing). Every outcome
 * posts its diagnosis line to the owner.
 */
public class Repair @JvmOverloads constructor(
    private val router: Router,
    private val runner: RepairRunner,
    private val acceptance: OriginalAcceptance,
    private val estimator: TokenEstimator,
    private val defaults: Defaults = Defaults(),
    /** The repair role as the attempt's configuration words it (`RoleTexts.worded`); the capsule mask replaces its mask. */
    private val role: Role = Roles.repair,
) {
    public suspend fun repair(capsule: Capsule, shape: Shape, packet: RoutingPacket, policy: RoutingPolicy, owner: Diagnoses? = null): RepairOutcome {
        val outcome = run(capsule.frozen(), shape, packet, policy)
        owner?.post(outcome.diagnosis)
        return outcome
    }

    private suspend fun run(capsule: Capsule, shape: Shape, packet: RoutingPacket, policy: RoutingPolicy): RepairOutcome {
        val op = capsule.intendedOperation
        if (shape < Shape.S2) return escalated(op, "capsule repair is S2+ only (shape $shape)", "not available in $shape", 0)
        if (capsule.remainingBudget == Tokens.ZERO) return escalated(op, "no remaining budget for a repair", "budget", 0)
        val profile = when (val routed = router.selectProfile(RoutingFunction.RepairHelper, packet, null, policy)) {
            is Routed.Selected -> routed.profile
            is Routed.Refused -> return escalated(op, routed.reason, "no affordable helper profile", 0)
            is Routed.Deterministic -> return escalated(op, "the repair helper routes to a model", "routing", 0)
        }
        val maxAttempts = minOf(defaults.repairCalls, policy.functions.row(RoutingFunction.RepairHelper).maxAttempts ?: MAX_ATTEMPTS, MAX_ATTEMPTS)
        val mask = capsule.mask
        val role = role.copy(toolMask = mask)
        val previous = ArrayList<String>()
        for (attempt in 1..maxAttempts) {
            when (val proposal = runner.attempt(RepairRequest(capsule, attempt, role, mask, profile, previous.toList()))) {
                is RepairProposal.Diagnosis -> return RepairOutcome.Diagnosed(line(op, proposal.text), attempt)
                is RepairProposal.Escalate -> return escalated(op, proposal.text, "the helper escalated", attempt)
                is RepairProposal.Fixed -> {
                    if (!mask.allows(proposal.call.op)) {
                        previous += "attempt $attempt: ${proposal.call.op} is outside the capsule mask"
                        continue
                    }
                    val check = acceptance.check(capsule)
                    if (check.holds) {
                        return RepairOutcome.Fixed(proposal.call, check.evidence, line(op, "fixed by ${proposal.call.op}: ${proposal.result}; verified: ${check.evidence}"), attempt)
                    }
                    previous += "attempt $attempt: claimed fixed but the original acceptance still fails: ${check.evidence}"
                }
            }
        }
        return escalated(op, previous.lastOrNull() ?: "no verified fix", "no verified fix in $maxAttempts attempts", maxAttempts)
    }

    private fun escalated(op: String, text: String, reason: String, attempts: Int): RepairOutcome =
        RepairOutcome.Escalated(line(op, "escalate ($reason): $text"), reason, attempts)

    /** `repair <op>: <text>`, whole words only, at most [MAX_DIAGNOSIS_TOKENS] tokens. */
    private fun line(op: String, text: String): String {
        val full = "repair $op: " + text.replace(Regex("\\s+"), " ").trim()
        if (estimator.estimate(full).tokens <= MAX_DIAGNOSIS_TOKENS) return full
        val words = full.split(' ')
        var keep = words.size
        var out = full
        while (keep > 1 && estimator.estimate(out).tokens > MAX_DIAGNOSIS_TOKENS) {
            keep--
            out = words.take(keep).joinToString(" ") + " …"
        }
        return out
    }

    public companion object {
        public const val MAX_ATTEMPTS: Int = 2
        public const val MAX_DIAGNOSIS_TOKENS: Int = 100
    }
}
