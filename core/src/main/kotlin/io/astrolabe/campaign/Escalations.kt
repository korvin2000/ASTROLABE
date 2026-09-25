package io.astrolabe.campaign

import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PartialReason
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.id.AttemptId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.recover.FailureClass
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.Escalation
import io.astrolabe.route.EscalationChange
import io.astrolabe.route.EscalationStep
import io.astrolabe.route.Routed
import io.astrolabe.route.SubstantiveAttempt
import io.astrolabe.route.Tier
import io.astrolabe.route.VerifiedFailure
import io.astrolabe.verify.CompletionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Clock

/**
 * The controller's side of the §11.3 ladder (P4.5.2): each verified failure of an increment is one substantive attempt,
 * recorded in the journal keyed by work and increment — so neither a resume nor a new attempt id replenishes
 * `budget.attempts` — and the next step is [Escalation.next] over the durable allowance (D-151).
 */
internal class IncrementAttempts(private val journal: Journal, private val idGen: IdGen, private val clock: Clock) {
    private val changes = HashMap<String, EscalationChange>()
    private val lines = HashMap<String, String>()
    private val tiers = HashMap<String, Tier>()

    /** The escalation line the increment's next cell pins: the failure evidence and the stated change (§11.3). */
    fun line(increment: String): String? = lines[increment]

    /** The tier the increment's next attempt was escalated to; routing never drops below it. */
    fun tier(increment: String): Tier? = tiers[increment]

    /** Why [increment] may not be dispatched again: its substantive attempts are spent (a resume never replenishes them). */
    fun exhausted(work: WorkId, increment: String, budget: Int): String? = allowance(work, increment, budget).takeIf { it.remaining <= 0 }?.let {
        "budget.attempts ${it.budget} spent on $increment (${it.attempts.joinToString(", ") { a -> "${a.attempt.value}@${a.tier}" }}): blocked"
    }

    fun allowance(work: WorkId, increment: String, budget: Int): AttemptAllowance =
        AttemptAllowance(increment, budget, journal.events(JournalScope(work, kinds = setOf(JournalKind.Boundary))).mapNotNull { decode(it.payload as? JsonObject, increment) }.take(budget))

    /**
     * The completion of [increment] was refused by the verifier: record the attempt that ran at [selected] and decide.
     * An [EscalationStep.Escalate] is remembered as the stated change of the increment's next attempt.
     */
    fun refused(ids: Identities, increment: String, budget: Int, selected: Routed.Selected?, register: Register, missing: List<String>, evidenceRefs: List<String>): EscalationStep {
        val before = allowance(ids.work, increment, budget)
        val attempt = SubstantiveAttempt(
            ids.attempt,
            selected?.tier ?: Tier.High,
            selected?.profile?.id ?: "unrouted",
            register.decisions.lastOrNull()?.text ?: register.focus ?: "unstated",
            changes[increment],
            evidenceRefs,
        )
        if (before.remaining > 0) record(ids, increment, before.used + 1, attempt)
        val after = allowance(ids.work, increment, budget)
        val failure = VerifiedFailure(FailureClass.BehaviouralTestFailure, "completion refused: ${missing.joinToString("; ")}", evidenceRefs.ifEmpty { listOf(increment) })
        return Escalation.next(after, failure).also { step ->
            if (step is EscalationStep.Escalate) {
                changes[increment] = step.change
                lines[increment] = step.line
                tiers[increment] = step.tier
            }
        }
    }

    private fun record(ids: Identities, increment: String, n: Int, attempt: SubstantiveAttempt) {
        val payload = buildJsonObject {
            put("type", TYPE)
            put("increment", increment)
            put("attempt", attempt.attempt.value)
            put("tier", attempt.tier.name)
            put("profile", attempt.profile)
            put("hypothesis", attempt.hypothesis)
            attempt.change?.let { put("change", it.name) }
        }
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(increment) + attempt.evidenceRefs,
            text = "substantive attempt $n of $increment failed verification at ${attempt.profile}@${attempt.tier}", payload = payload, at = clock.instant()))
    }

    private fun decode(payload: JsonObject?, increment: String): SubstantiveAttempt? {
        if (payload == null || payload.text("type") != TYPE || payload.text("increment") != increment) return null
        return SubstantiveAttempt(
            AttemptId(payload.text("attempt")!!),
            Tier.valueOf(payload.text("tier")!!),
            payload.text("profile")!!,
            payload.text("hypothesis")!!,
            payload.text("change")?.let(EscalationChange::valueOf),
        )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TYPE = "substantive-attempt"

        /** What a verified failure of the attempt left unmet: a refused completion, or one the cell could not get past its gate. */
        fun verifiedFailure(exit: CellExit, completion: CompletionResult?): List<String>? = when {
            completion is CompletionResult.Refused -> completion.missing
            exit is CellExit.Partial && exit.reason == PartialReason.CompletionStalled -> listOf(exit.hint)
            else -> null
        }
    }
}
