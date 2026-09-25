package io.astrolabe.campaign

import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Shape
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.evidence.Outcome
import io.astrolabe.id.CandidateId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.recover.Alternative
import io.astrolabe.recover.AlternativeDecision
import io.astrolabe.recover.Capsule
import io.astrolabe.recover.ErrorSignature
import io.astrolabe.recover.ExecutionState
import io.astrolabe.recover.Failure
import io.astrolabe.recover.FailureClass
import io.astrolabe.recover.Fingerprint
import io.astrolabe.recover.GuardLimits
import io.astrolabe.recover.GuardVerdict
import io.astrolabe.recover.Guards
import io.astrolabe.recover.Ladder
import io.astrolabe.recover.Recovery
import io.astrolabe.recover.Repair
import io.astrolabe.recover.RepairOutcome
import io.astrolabe.register.Register
import io.astrolabe.route.AttemptAllowance
import io.astrolabe.route.RoutingPacket
import io.astrolabe.route.RoutingPolicy
import io.astrolabe.route.VerifiedFailure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock

/** One verified failure after the ladder and the guards: what `recover` decided and what the guards said. */
internal data class RoutedFailure(val failure: Failure, val fingerprint: Fingerprint, val recovery: Recovery, val verdict: GuardVerdict)

/**
 * The controller's side of §13.1–§13.3 for verified failures (D-171, D-254): each one is classified, routed through
 * [Ladder.recover] and the campaign's [Guards], and journaled with its fingerprint, so a resumed controller rebuilds
 * the same guard state by replaying the journal (the guards' budgets are never replenished by a resume). A guard
 * nudge, a repair diagnosis and an opened alternative become lines pinned in the increment's next cell; a trip stops
 * the campaign to ask. A repair only ever runs the capsule helper, whose `fixed` claim needs the original acceptance
 * to hold again, and the increment still closes only through the verifier: a repair never rewrites a failure.
 */
internal class CampaignRecovery(
    private val journal: Journal,
    private val idGen: IdGen,
    private val clock: Clock,
    work: WorkId,
    limits: GuardLimits,
    private val ladder: Ladder = Ladder(),
) {
    private val guards = Guards(limits)
    private val pinned = HashMap<String, ArrayList<String>>()
    private val repairs = HashMap<String, Int>()
    private val failures = HashMap<String, ArrayList<Pair<String, VerifiedFailure>>>()

    init {
        for (event in journal.events(JournalScope(work, kinds = setOf(JournalKind.Boundary)))) {
            val payload = event.payload as? JsonObject ?: continue
            when (payload.text("type")) {
                FAILURE -> {
                    val increment = payload.text("increment") ?: continue
                    val fingerprint = Fingerprint(ErrorSignature(payload.text("operation")!!, payload.text("signature")!!), payload.text("fix")!!, payload.text("state")!!, increment)
                    guards.failure(payload.text("cell")!!, fingerprint)
                    remember(increment, fingerprint.attemptedFix, VerifiedFailure(FailureClass.valueOf(payload.text("class")!!), payload.text("detail")!!, event.refs.ifEmpty { listOf(increment) }))
                }
                REPAIR -> payload.text("key")?.let { repairs.merge(it, 1, Int::plus) }
            }
        }
    }

    /** Equivalent no-progress failures across the campaign, the ones before a resume included. */
    val noProgressEvents: List<String> get() = guards.noProgressEvents

    /** The lines [increment]'s next cell pins: the latest guard nudges, repair diagnoses and alternative (at most two). */
    fun lines(increment: String): List<String> = pinned[increment].orEmpty().takeLast(PINNED)

    /** Routes one verified failure of [increment] in the cell [ids] names: ladder, guards, journal. */
    fun failed(ids: Identities, increment: String, failureClass: FailureClass, detail: String, evidenceRefs: List<String>, hypothesis: String, stamp: CandidateId): RoutedFailure {
        val cell = ids.context?.value ?: "campaign"
        val signature = ErrorSignature.of(OPERATION, detail)
        val fingerprint = Fingerprint(signature, hypothesis, stamp.digest.hex, increment)
        val failure = Failure(failureClass, ExecutionState.DurablyCompleted, OPERATION, detail, evidenceRefs, repairs = repairs[key(increment, signature)] ?: 0)
        val recovery = ladder.recover(failure)
        val verdict = guards.failure(cell, fingerprint)
        remember(increment, hypothesis, VerifiedFailure(failureClass, detail, evidenceRefs.ifEmpty { listOf(increment) }))
        val said = when (verdict) {
            GuardVerdict.Pass -> "pass"
            is GuardVerdict.Nudge -> verdict.line.also { pin(increment, it) }
            is GuardVerdict.Trip -> verdict.line
        }
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = evidenceRefs,
            text = "recover ${failureClass.name} of $increment: ${recovery::class.simpleName!!.lowercase()} · guards: $said",
            payload = buildJsonObject {
                put("type", FAILURE)
                put("increment", increment)
                put("cell", cell)
                put("operation", signature.operation)
                put("signature", signature.text)
                put("fix", hypothesis)
                put("state", fingerprint.relevantState)
                put("class", failureClass.name)
                put("detail", detail)
                put("recovery", recovery::class.simpleName!!.lowercase())
            }, at = clock.instant()))
        return RoutedFailure(failure, fingerprint, recovery, verdict)
    }

    /**
     * The one scoped capsule repair the ladder granted (§13.2 step 4): the helper sees the capsule only, [repair]
     * re-checks the original acceptance before it believes a fix, and the outcome's diagnosis is pinned for the
     * increment's next cell. The repair is spent whatever the outcome, durably.
     */
    suspend fun repair(
        ids: Identities,
        increment: String,
        routed: RoutedFailure,
        acceptance: List<String>,
        artifactVersions: Map<String, FileVersion>,
        remaining: Tokens,
        shape: Shape,
        packet: RoutingPacket,
        policy: RoutingPolicy,
        repair: Repair,
    ): RepairOutcome {
        val failure = routed.failure
        val capsule = Capsule(
            intendedOperation = OPERATION,
            acceptanceCriterion = "the increment's acceptance ${acceptance.joinToString(", ")} is green at the current stamp",
            callArguments = buildJsonObject { put("what", "acceptance"); putJsonArray("ids") { acceptance.forEach { add(JsonPrimitive(it)) } } }.toString(),
            environment = "trusted-local",
            errorOrExit = failure.detail,
            artifactVersions = artifactVersions,
            completedEffects = failure.completedEffects,
            rawEvidenceRefs = failure.evidenceRefs,
            previousAttempts = emptyList(),
            allowedFixes = listOf("setup and environment inside the increment's write scope", "never the acceptance, its tests or its checks"),
            remainingBudget = remaining,
        )
        val outcome = repair.repair(capsule, shape, packet, policy)
        val key = key(increment, routed.fingerprint.signature)
        repairs.merge(key, 1, Int::plus)
        pin(increment, outcome.diagnosis)
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = failure.evidenceRefs, text = outcome.diagnosis,
            payload = buildJsonObject {
                put("type", REPAIR)
                put("key", key)
                put("increment", increment)
                put("outcome", outcome::class.simpleName!!.lowercase())
                put("attempts", outcome.attempts)
            }, at = clock.instant()))
        return outcome
    }

    /**
     * §13.3: after the ladder, an alternative attempt of [allowance]'s increment when its current hypothesis failed at
     * least twice (D-154); an opened one is journaled and its reason, dead ends and kept receipts pinned. Once the
     * hypothesis has failed twice a refusal is journaled with its reason (a spent allowance, the same label again);
     * before that, refining in place is the simpler recovery and nothing is recorded.
     */
    fun alternative(ids: Identities, allowance: AttemptAllowance, register: Register, keptReceipts: List<String>, contractVersion: Int, hypothesis: String): AlternativeDecision {
        val same = failures[allowance.increment].orEmpty().filter { it.first == hypothesis }.map { it.second }
        val decision = Alternative.open(allowance, same, register, keptReceipts, contractVersion, hypothesis, idGen)
        if (decision is AlternativeDecision.Refused && same.size >= Alternative.SAME_HYPOTHESIS_FAILURES) {
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(allowance.increment), text = "alternative attempt of ${allowance.increment} refused: ${decision.reason}", at = clock.instant()))
        }
        if (decision is AlternativeDecision.Opened) {
            val alt = decision.alternative
            val line = "alternative attempt ${alt.attempt.value} replaces ${alt.previous.value}: ${alt.reason}" +
                (alt.deadEnds.takeIf { it.isNotEmpty() }?.let { d -> " · dead ends: " + d.joinToString("; ") { it.text } } ?: "") +
                " · change the hypothesis, not the label"
            pin(allowance.increment, line)
            journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = alt.keptReceipts, text = line,
                payload = buildJsonObject {
                    put("type", ALTERNATIVE)
                    put("increment", allowance.increment)
                    put("attempt", alt.attempt.value)
                    put("previous", alt.previous.value)
                    put("tier", alt.substantive.tier.name)
                    put("kept", JsonArray(alt.keptReceipts.map(::JsonPrimitive)))
                }, at = clock.instant()))
        }
        return decision
    }

    private fun remember(increment: String, hypothesis: String, failure: VerifiedFailure) {
        failures.getOrPut(increment) { ArrayList() } += hypothesis to failure
    }

    private fun pin(increment: String, line: String) {
        pinned.getOrPut(increment) { ArrayList() } += line
    }

    private fun key(increment: String, signature: ErrorSignature): String = "$increment\u0000${signature.operation}\u0000${signature.text}"

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** A verified failure is the increment's completion against its acceptance (§8.7). */
        const val OPERATION: String = "verify.acceptance"
        const val FAILURE: String = "recovery-failure"
        const val REPAIR: String = "recovery-repair"
        const val ALTERNATIVE: String = "alternative-attempt"
        private const val PINNED = 2

        /**
         * D-254: a verified failure is `Build / environment` when a check of the increment's acceptance last ended as
         * infrastructure — the runner reported a usage or internal error, or could not run (§13.2 "failing setup ≠
         * failing implementation"); every other refused or stalled completion is a behavioural test failure.
         */
        fun classify(outcomes: List<Outcome>): FailureClass =
            if (outcomes.any { it == Outcome.InfraError || it == Outcome.Unavailable }) FailureClass.BuildEnvironment else FailureClass.BehaviouralTestFailure

        /** The attempt's stated hypothesis as the register records it (D-151). */
        fun hypothesis(register: Register): String = register.decisions.lastOrNull()?.text ?: register.focus ?: "unstated"
    }
}
