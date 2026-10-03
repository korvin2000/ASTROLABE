package io.astrolabe.campaign

import io.astrolabe.budget.LimitDecision
import io.astrolabe.budget.LimitGate
import io.astrolabe.budget.LimitKind
import io.astrolabe.budget.LimitRule
import io.astrolabe.budget.LimitSpend
import io.astrolabe.budget.LimitStatus
import io.astrolabe.budget.Spend
import io.astrolabe.budget.TaskLimits
import io.astrolabe.cell.CellModel
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Authority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.id.CandidateId
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Money
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.telemetry.CallAccount
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.ReviewRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * How a task limit ended a campaign (plan §4.6, C3): the limit, the spend against every limit, the next call's price the
 * stop was decided on ([status]'s `nextCallCost`), and the best verified candidate — the latest tree stamp the ordinary
 * acceptance verified on the main line. It is named, never swapped into the user's tree: [workingTree] says whether it
 * is the tree as left, otherwise the tree at [workingStamp] holds unverified changes after it.
 */
@Serializable
public data class LimitStop(
    val limit: LimitKind,
    val reason: String,
    val status: LimitStatus,
    /** `null` when nothing is verified at any candidate. */
    val bestCandidate: CandidateId?,
    /** Requirements tested or reviewed at [bestCandidate]. */
    val verified: List<String>,
    val workingTree: Boolean,
    val workingStamp: CandidateId,
    /** Requirements verified at an earlier candidate of the line and not re-checked at [bestCandidate]. */
    val verifiedEarlier: List<String> = emptyList(),
    /** Requirements a decider accepted without verification (I7): never shown as verified. */
    val accepted: List<String> = emptyList(),
)

/** A refusal that ended a cell on a task limit: the stop the next boundary takes, with the price it was refused at. */
internal class LimitBlock(val decision: LimitDecision, val price: Money?)

/** A run of the controller over a campaign (C3 minutes): its start, the active time before it and its paused waits. */
internal class LimitSession(val startEventId: String, val start: Instant, val priorMillis: Long) {
    var pausedMillis: Long = 0
    var pauseDepth: Int = 0
    var pauseStart: Instant? = null
}

/** The limits' running state of one opened campaign: the session, the latched reserve, the stop, the counter, a spend cache. */
internal class LimitState {
    @Volatile var session: LimitSession? = null
    @Volatile var reserve: LimitDecision.Reserve? = null
    @Volatile var reserveAnnounced: Boolean = false
    @Volatile var block: LimitBlock? = null
    @Volatile var lastEstimate: Money? = null
    @Volatile var requestsSeen: Int = -1
    @Volatile var callsKey: Pair<Long, Long>? = null
    @Volatile var calls: List<CallAccount> = emptyList()
}

/**
 * Active time from the journal (C3 minutes): every run writes a session start and end, and pauses around the host's
 * answers (a person's wait is not active time). A run that died without its end is closed at reopen at its last
 * journal event, before the reopen writes anything, so a reopen never counts the time the task stood stopped.
 */
internal object LimitSessions {
    const val STARTED: String = "limits: session started"
    const val ENDED: String = "limits: session ended"
    const val PAUSED: String = "limits: session paused"
    const val RESUMED: String = "limits: session resumed"
    const val RESERVE: String = "limits: reserve reached"
    const val REACHED: String = "limits: reached"
    const val STILL: String = "limits: still reached"
    const val RAISED: String = "limits: raised by the host"
    const val SET: String = "limits: set by the host"

    /** The active time of the closed sessions among [events] — one work's journal in seq order. */
    fun closedMillis(events: List<JournalEvent>): Long {
        var total = 0L
        var open: JournalEvent? = null
        var pausedAt: Instant? = null
        var paused = 0L
        var last: Instant? = null
        fun close(end: Instant) {
            val start = open ?: return
            pausedAt?.let { paused += span(it, end) }
            total += (span(start.at, end) - paused).coerceAtLeast(0)
            open = null
            pausedAt = null
            paused = 0
        }
        for (event in events) {
            val text = event.text
            when {
                text.startsWith(STARTED) -> { open?.let { close(last ?: it.at) }; open = event }
                text.startsWith(ENDED) && open?.eventId in event.refs -> close(event.at)
                text.startsWith(PAUSED) && open != null && pausedAt == null -> pausedAt = event.at
                text.startsWith(RESUMED) && open != null -> pausedAt?.let { paused += span(it, event.at); pausedAt = null }
            }
            last = event.at
        }
        open?.let { close(last ?: it.at) }
        return total
    }

    /** Closes a run that stopped without its end record, at its last journal event (before a reopen writes its own). */
    fun closeDangling(journal: Journal, ids: Identities, idGen: IdGen) {
        val events = journal.events(JournalScope(ids.work))
        val start = events.lastOrNull { it.text.startsWith(STARTED) } ?: return
        if (events.any { it.text.startsWith(ENDED) && start.eventId in it.refs }) return
        journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Boundary, refs = listOf(start.eventId),
            text = "$ENDED · the run stopped without its record; closed at its last event", at = events.last().at))
    }

    fun span(from: Instant, to: Instant): Long = Duration.between(from, to).toMillis().coerceAtLeast(0)
}

/**
 * The controller's side of the task limits (C3): the spend from durable records, the run sessions, the gate and the
 * transactional admission each cell asks before each model call, the latched reserve, the stop a refusal leaves for the
 * next boundary, and what the host sees of it (`budget.spent`, `budget.limit_reached`).
 */
internal class TaskLimitControl(private val idGen: IdGen, private val clock: Clock, private val events: Events?) {
    /** [c]'s spend now: its calls (cached by the `usage` rows' count and size), the closed sessions and the running one. */
    fun spend(c: OpenedCampaign): LimitSpend = LimitSpend.of(calls(c), elapsed(c), currency(c.limits))

    fun spend(store: Store, journal: Journal, work: WorkId, limits: TaskLimits): LimitSpend =
        LimitSpend.of(Accounting(store, clock).calls(work), LimitSessions.closedMillis(journal.events(JournalScope(work))), currency(limits))

    private fun calls(c: OpenedCampaign): List<CallAccount> {
        val st = c.limitState
        val key = c.store.db.query("SELECT count(*) AS n, coalesce(sum(length(body)), 0) AS b FROM usage WHERE work_id = ?", c.ids.work) {
            it.long("n") to it.long("b")
        }.first()
        if (key != st.callsKey) {
            st.calls = Accounting(c.store, clock).calls(c.ids.work)
            st.callsKey = key
        }
        return st.calls
    }

    private fun elapsed(c: OpenedCampaign): Long {
        val session = c.limitState.session ?: return LimitSessions.closedMillis(c.journal.events(JournalScope(c.ids.work)))
        synchronized(session) {
            val now = clock.instant()
            val pausing = session.pauseStart?.let { LimitSessions.span(it, now) } ?: 0L
            return session.priorMillis + (LimitSessions.span(session.start, now) - session.pausedMillis - pausing).coerceAtLeast(0)
        }
    }

    /** Starts [c]'s run session when the campaign may dispatch; `null` when none starts here. Written with or without limits. */
    fun begin(c: OpenedCampaign): LimitSession? {
        if (c.limitState.session != null || c.stop != null) return null
        val prior = LimitSessions.closedMillis(c.journal.events(JournalScope(c.ids.work)))
        val event = c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary,
            text = "${LimitSessions.STARTED} · limits ${c.limits} · $prior ms active before", at = clock.instant()))
        return LimitSession(event.eventId, event.at, prior).also { c.limitState.session = it }
    }

    /** Ends the [session] [begin] started. */
    fun end(c: OpenedCampaign, session: LimitSession?) {
        if (session == null || c.limitState.session !== session) return
        val now = clock.instant()
        c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = listOf(session.startEventId),
            text = "${LimitSessions.ENDED} · ${LimitSessions.span(session.start, now)} ms", at = now))
        c.limitState.session = null
    }

    /** [authority] with the run's session paused while the host answers: a person's wait is not active time. */
    fun pausing(c: OpenedCampaign, authority: Authority): Authority = object : Authority by authority {
        override suspend fun ask(question: Question) = paused(c) { authority.ask(question) }
        override suspend fun approve(request: DClassRequest) = paused(c) { authority.approve(request) }
        override suspend fun resolve(proposal: AmendmentProposal) = paused(c) { authority.resolve(proposal) }
        override suspend fun review(request: ReviewRequest) = paused(c) { authority.review(request) }
        override suspend fun decide(request: AcceptanceDecisionRequest) = paused(c) { authority.decide(request) }
    }

    private suspend fun <T> paused(c: OpenedCampaign, block: suspend () -> T): T {
        val session = c.limitState.session ?: return block()
        mark(c, session, pause = true)
        try {
            return block()
        } finally {
            mark(c, session, pause = false)
        }
    }

    private fun mark(c: OpenedCampaign, session: LimitSession, pause: Boolean) {
        val now = clock.instant()
        val edge = synchronized(session) {
            if (pause) {
                session.pauseDepth += 1
                (session.pauseDepth == 1).also { if (it) session.pauseStart = now }
            } else {
                session.pauseDepth -= 1
                (session.pauseDepth == 0).also { if (it) { session.pausedMillis += session.pauseStart?.let { s -> LimitSessions.span(s, now) } ?: 0; session.pauseStart = null } }
            }
        }
        if (edge) c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, refs = listOf(session.startEventId),
            text = if (pause) LimitSessions.PAUSED + " · waiting for the host" else LimitSessions.RESUMED, at = now))
    }

    /**
     * One cell's view of the limits on [model]: the gate its budget asks before every turn and admission, and the
     * admission its accounting asks inside the transaction that writes the call's hold.
     */
    fun cell(c: OpenedCampaign, model: CellModel): CellLimits = CellLimits(c, model)

    inner class CellLimits(private val c: OpenedCampaign, private val model: CellModel) {
        @Volatile private var admitting: Spend = Spend.Generation

        /** At a turn's start the next call is priced at the last admitted request's estimate; at admission at its own. */
        val gate: LimitGate = LimitGate { spend, estimate ->
            val now = spend(c)
            val e = if (estimate.value == 0L) c.limitState.lastEstimate else {
                admitting = spend
                Accounting.estimateCost(model.profile, (estimate.value - model.maxOutputTokens).coerceAtLeast(0), model.maxOutputTokens.toLong())
                    .also { c.limitState.lastEstimate = it }
            }
            val price = LimitRule.nextCost(now, e)
            val decision = latch(c, LimitRule.decide(c.limits, now, price))
            observe(c, now, decision, price)
            // A refusal that ends the cell: a spent limit, or generation refused at admission. A turn that merely falls back to
            // verify-and-report is not one.
            if (decision is LimitDecision.Exhausted || (decision is LimitDecision.Reserve && estimate.value > 0 && !spend.reportOrVerify)) block(c, decision, price)
            decision
        }

        /** The transactional check: the calls on record and holds of cells in flight, plus this call at its estimate. */
        val admission: ((List<CallAccount>, Money) -> Boolean)? = if (!c.limits.any) null else { calls, money ->
            val now = LimitSpend.of(calls, elapsed(c), currency(c.limits))
            val price = LimitRule.nextCost(now, money)
            val decision = latch(c, LimitRule.decide(c.limits, now, price))
            val admitted = decision == LimitDecision.Within || (decision is LimitDecision.Reserve && admitting.reportOrVerify)
            if (!admitted) block(c, decision, price)
            admitted
        }
    }

    /** The extractor's admission at finish: a report spend, refused when a limit would be crossed or its price is unknown. */
    fun reportAdmission(c: OpenedCampaign): ((List<CallAccount>, Money) -> Boolean)? = if (!c.limits.any) null else { calls, money ->
        val now = LimitSpend.of(calls, elapsed(c), currency(c.limits))
        LimitRule.decide(c.limits, now, LimitRule.nextCost(now, money)) !is LimitDecision.Exhausted
    }

    /** The decision at a cell boundary, before anything is dispatched: a cell's limit stop first, else the latched rule. */
    fun boundary(c: OpenedCampaign): Triple<LimitSpend, LimitDecision, Money?> {
        val spend = spend(c)
        val price = LimitRule.nextCost(spend, c.limitState.lastEstimate)
        val block = c.limitState.block
        val decision = block?.decision ?: latch(c, LimitRule.decide(c.limits, spend, price))
        observe(c, spend, decision, block?.price ?: price)
        return Triple(spend, decision, block?.price ?: price)
    }

    /**
     * [config] for a cell of [c]: under a minutes limit, the default `run` deadline and the check time boxes are cut to the
     * active time left (at least a second); an explicit `run` timeout and a model call are not (a recorded bound).
     */
    fun bounded(c: OpenedCampaign, config: io.astrolabe.Config): io.astrolabe.Config {
        val max = c.limits.maxMillis ?: return config
        val left = ((max - spend(c).elapsedMillis) / 1_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val d = config.defaults
        if (left >= maxOf(d.runTimeoutSeconds, d.checkerTimeBoxSeconds, d.checkerFallbackTimeBoxSeconds)) return config
        return config.copy(defaults = d.copy(runTimeoutSeconds = minOf(d.runTimeoutSeconds, left), checkerTimeBoxSeconds = minOf(d.checkerTimeBoxSeconds, left),
            checkerFallbackTimeBoxSeconds = minOf(d.checkerFallbackTimeBoxSeconds, left)))
    }

    /** `budget.spent` now, whatever changed: the counter's last word at finish. */
    fun report(c: OpenedCampaign) {
        val spend = spend(c)
        events?.emit(AgentEvent.Budget.Spent(c.ids, LimitRule.status(c.limits, spend, LimitRule.nextCost(spend, c.limitState.lastEstimate))))
    }

    /** The working part stays spent until the host changes the limits: a falling mean call time never reopens it (latch). */
    private fun latch(c: OpenedCampaign, decision: LimitDecision): LimitDecision = when (decision) {
        is LimitDecision.Reserve -> decision.also { if (c.limitState.reserve == null) c.limitState.reserve = it }
        LimitDecision.Within -> c.limitState.reserve ?: decision
        is LimitDecision.Exhausted -> decision
    }

    private fun block(c: OpenedCampaign, decision: LimitDecision, price: Money?) {
        if (c.limitState.block == null) c.limitState.block = LimitBlock(decision, price)
    }

    /** One `budget.spent` per call counted, with or without limits; the first time the working part is spent, a journal line and `budget.limit_reached` (reserve). */
    private fun observe(c: OpenedCampaign, spend: LimitSpend, decision: LimitDecision, price: Money?) {
        if (spend.requests != c.limitState.requestsSeen) {
            c.limitState.requestsSeen = spend.requests
            events?.emit(AgentEvent.Budget.Spent(c.ids, LimitRule.status(c.limits, spend, price)))
        }
        if (decision is LimitDecision.Reserve && !c.limitState.reserveAnnounced) {
            c.limitState.reserveAnnounced = true
            c.journal.append(JournalEvent(idGen.next("ev"), c.ids, null, JournalKind.Boundary, text = "${LimitSessions.RESERVE} (${decision.kind.wire}): ${decision.reason} · verify and report only", at = clock.instant()))
            events?.emit(AgentEvent.Budget.LimitReached(c.ids, decision.kind.wire, RESERVE_STAGE, decision.reason, LimitRule.status(c.limits, spend, price)))
        }
    }

    internal companion object {
        const val USD: String = "USD"
        const val RESERVE_STAGE: String = "reserve"
        const val STOPPED_STAGE: String = "stopped"

        /** The reason's prefix of a task limit stop; decisions read [BudgetStop], never this text. */
        const val LIMIT_REACHED: String = "task limit reached"

        /** The host's action on every limit stop: no limit is a hidden ceiling (owner, 2026-10-03). */
        const val RAISE_TO_CONTINUE: String = "raise the limit and reopen the task to continue this attempt"

        val JSON: Json = Json { encodeDefaults = true }

        fun currency(limits: TaskLimits): String = limits.maxCost?.currency ?: USD

        /** The latest limit stop recorded in [journal] for [work]. */
        fun recorded(journal: Journal, work: WorkId): LimitStop? =
            journal.events(JournalScope(work, kinds = setOf(JournalKind.Boundary))).lastOrNull { it.text.startsWith(LimitSessions.REACHED) && it.payload != null }
                ?.let { runCatching { JSON.decodeFromJsonElement(LimitStop.serializer(), it.payload!!) }.getOrNull() }

        /**
         * The limits in force at an open (K): [requested] when the host names them — [TaskLimits.NONE] lifts every limit —
         * else the ones kept with the campaign; a change is journaled with its limits, the record a later open reads.
         */
        fun atOpen(journal: Journal, ids: Identities, idGen: IdGen, clock: Clock, requested: TaskLimits?): TaskLimits {
            val stored = journal.events(JournalScope(ids.work, kinds = setOf(JournalKind.Reconcile))).lastOrNull { it.text.startsWith(LimitSessions.SET) && it.payload != null }
                ?.let { runCatching { JSON.decodeFromJsonElement(TaskLimits.serializer(), it.payload!!) }.getOrNull() }
            val effective = requested ?: stored ?: TaskLimits.NONE
            if (requested != null && requested != (stored ?: TaskLimits.NONE)) {
                journal.append(JournalEvent(idGen.next("ev"), ids, null, JournalKind.Reconcile, text = "${LimitSessions.SET}: $requested",
                    payload = JSON.encodeToJsonElement(TaskLimits.serializer(), requested), at = clock.instant()))
            }
            return effective
        }
    }
}
