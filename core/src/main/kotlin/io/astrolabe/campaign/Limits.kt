package io.astrolabe.campaign

import io.astrolabe.budget.LimitDecision
import io.astrolabe.budget.LimitGate
import io.astrolabe.budget.LimitKind
import io.astrolabe.budget.LimitRule
import io.astrolabe.budget.LimitSpend
import io.astrolabe.budget.LimitStatus
import io.astrolabe.budget.TaskLimits
import io.astrolabe.cell.CellModel
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.id.CandidateId
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How a task limit ended a campaign (plan §4.6, C3): the limit, the spend against every limit, and the best verified
 * candidate — the tree stamp at which the ordinary acceptance (the ledger over the verifier's evidence) verifies the
 * most requirements. It is named, never swapped into the user's tree: [workingTree] says whether it is the tree as
 * left, otherwise the tree at [workingStamp] holds unverified changes after it.
 */
@Serializable
public data class LimitStop(
    val limit: LimitKind,
    val reason: String,
    val status: LimitStatus,
    /** `null` when no requirement is verified at any candidate. */
    val bestCandidate: CandidateId?,
    /** The requirements verified at [bestCandidate]. */
    val verified: List<String>,
    val workingTree: Boolean,
    val workingStamp: CandidateId,
)

/** One candidate a limit stop weighs: its stamp, the requirements verified at it, whether it is the tree as left, and how recently it was verified. */
internal data class CandidateScore(val stamp: CandidateId, val verified: List<String>, val current: Boolean, val recency: Int)

/** The best verified candidate (C3): most requirements verified; on a tie the tree as left, then the most recently verified. */
internal object BestCandidate {
    fun select(scores: List<CandidateScore>): CandidateScore? = scores.filter { it.verified.isNotEmpty() }
        .maxWithOrNull(compareBy<CandidateScore>({ it.verified.size }, { it.current }, { it.recency }))
}

/** A run of the controller over a campaign (C3 minutes): its start and the active time of the runs before it. */
internal class LimitSession(val startEventId: String, val start: Instant, val priorMillis: Long)

/**
 * Active time from the journal (C3 minutes): each run writes a session start and end; a run that died without its end
 * counts up to its last journal event, so a reopen never counts a minute twice nor the time the task stood stopped.
 */
internal object LimitSessions {
    const val STARTED: String = "limits: session started"
    const val ENDED: String = "limits: session ended"
    const val RESERVE: String = "limits: reserve reached"
    const val REACHED: String = "limits: reached"
    const val RAISED: String = "limits: raised by the host"

    /** The active time of the closed sessions among [events] — one work's journal in seq order. */
    fun closedMillis(events: List<JournalEvent>): Long {
        var total = 0L
        var open: JournalEvent? = null
        var last: Instant? = null
        for (event in events) {
            if (event.text.startsWith(STARTED)) {
                open?.let { start -> total += span(start.at, last ?: start.at) }
                open = event
            } else if (event.text.startsWith(ENDED) && open != null && open.eventId in event.refs) {
                total += span(open.at, event.at)
                open = null
            }
            last = event.at
        }
        open?.let { start -> total += span(start.at, last ?: start.at) }
        return total
    }

    private fun span(from: Instant, to: Instant): Long = Duration.between(from, to).toMillis().coerceAtLeast(0)
}

/**
 * The controller's side of the task limits (C3): the spend from durable records, the run sessions, the gate every cell
 * asks before each model call, and what the host sees of it (`budget.spent`, `budget.limit_reached`).
 */
internal class TaskLimitControl(private val idGen: IdGen, private val clock: Clock, private val events: Events?) {
    /** [c]'s spend now: its calls, the closed sessions' time and the running session's. */
    fun spend(c: OpenedCampaign): LimitSpend = spend(c.store, c.journal, c.ids.work, c.limits, c.limitSession)

    fun spend(store: Store, journal: Journal, work: WorkId, limits: TaskLimits, session: LimitSession?): LimitSpend {
        val elapsed = session?.let { it.priorMillis + Duration.between(it.start, clock.instant()).toMillis().coerceAtLeast(0) }
            ?: LimitSessions.closedMillis(journal.events(JournalScope(work)))
        return LimitSpend.of(Accounting(store, clock).calls(work), elapsed, limits.maxCost?.currency ?: USD)
    }

    /** Starts [c]'s run session when a limit is set and the campaign may dispatch; `null` when none starts here. */
    fun begin(c: OpenedCampaign): LimitSession? {
        if (!c.limits.any || c.limitSession != null || c.stop != null) return null
        val prior = LimitSessions.closedMillis(c.journal.events(JournalScope(c.ids.work)))
        val event = c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary,
            text = "${LimitSessions.STARTED} · limits ${c.limits} · ${prior} ms active before", at = clock.instant()))
        return LimitSession(event.eventId, event.at, prior).also { c.limitSession = it }
    }

    /** Ends the [session] [begin] started. */
    fun end(c: OpenedCampaign, session: LimitSession?) {
        if (session == null || c.limitSession !== session) return
        val now = clock.instant()
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = listOf(session.startEventId),
            text = "${LimitSessions.ENDED} · ${Duration.between(session.start, now).toMillis().coerceAtLeast(0)} ms", at = now))
        c.limitSession = null
    }

    /**
     * The gate a cell of [c] on [model] asks before every turn and admission (C3). At a turn's start the next call is
     * priced as the dearest call so far and the working part is decided there: a turn begun as generation stays one.
     * At admission the request's conservative estimate — its input upper bound at the dearest input rate plus its full
     * output headroom, or the dearest call so far when that is dearer — is checked against the hard limits only.
     */
    fun gate(c: OpenedCampaign, model: CellModel): LimitGate? {
        if (!c.limits.any) return null
        val reserves = c.contract.budget.reserves
        return LimitGate { _, estimate ->
            val spend = spend(c)
            if (estimate.value == 0L) {
                LimitRule.decide(c.limits, spend, reserves, spend.largestCallCost).also { observe(c, spend, it) }
            } else {
                val estimated = Accounting.estimateCost(model.profile, (estimate.value - model.maxOutputTokens).coerceAtLeast(0), model.maxOutputTokens.toLong())
                when (val decision = LimitRule.decide(c.limits, spend, reserves, dearer(estimated, spend.largestCallCost))) {
                    is LimitDecision.Reserve -> LimitDecision.Within
                    else -> decision
                }
            }
        }
    }

    /** The dearer of two call prices; an unknown or foreign one wins, so the rule refuses what it cannot compare. */
    private fun dearer(a: io.astrolabe.provider.Money, b: io.astrolabe.provider.Money?): io.astrolabe.provider.Money = when {
        b == null || a.unknown -> a
        b.unknown || a.currency != b.currency -> b.takeIf { it.unknown } ?: a
        else -> if (b.amount > a.amount) b else a
    }

    /** The decision at a cell boundary, before anything is dispatched; `null` without limits. */
    fun boundary(c: OpenedCampaign): Pair<LimitSpend, LimitDecision>? {
        if (!c.limits.any) return null
        val spend = spend(c)
        return (spend to LimitRule.decide(c.limits, spend, c.contract.budget.reserves)).also { observe(c, spend, it.second) }
    }

    fun status(c: OpenedCampaign, spend: LimitSpend): LimitStatus = LimitRule.status(c.limits, spend, c.contract.budget.reserves)

    /** One `budget.spent` per call counted; the first time the working part is spent, a journal line and `budget.limit_reached` (reserve). */
    private fun observe(c: OpenedCampaign, spend: LimitSpend, decision: LimitDecision) {
        if (spend.requests != c.limitRequestsSeen) {
            c.limitRequestsSeen = spend.requests
            events?.emit(AgentEvent.Budget.Spent(c.ids, status(c, spend)))
        }
        if (decision is LimitDecision.Reserve && !c.limitReserveSeen) {
            c.limitReserveSeen = true
            c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, text = "${LimitSessions.RESERVE} (${decision.kind.wire}): ${decision.reason} · verify and report only", at = clock.instant()))
            events?.emit(AgentEvent.Budget.LimitReached(c.ids, decision.kind.wire, RESERVE_STAGE, decision.reason, status(c, spend)))
        }
    }

    internal companion object {
        const val USD: String = "USD"
        const val RESERVE_STAGE: String = "reserve"
        const val STOPPED_STAGE: String = "stopped"

        /** The stop reason prefix of a campaign a task limit ended: a reopen with raised limits resumes only these. */
        const val LIMIT_REACHED: String = "task limit reached"

        /** The host's action on every limit stop: no limit is a hidden ceiling (owner, 2026-10-03). */
        const val RAISE_TO_CONTINUE: String = "raise the limit and reopen the task to continue this attempt"

        val JSON: Json = Json { encodeDefaults = true }

        /** The latest limit stop recorded in [journal] for [work]. */
        fun recorded(journal: Journal, work: WorkId): LimitStop? =
            journal.events(JournalScope(work, kinds = setOf(JournalKind.Boundary))).lastOrNull { it.text.startsWith(LimitSessions.REACHED) && it.payload != null }
                ?.let { runCatching { JSON.decodeFromJsonElement(LimitStop.serializer(), it.payload!!) }.getOrNull() }
    }
}
