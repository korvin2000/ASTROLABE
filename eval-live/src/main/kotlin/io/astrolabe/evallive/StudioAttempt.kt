package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.InvalidConfig
import io.astrolabe.RunSpec
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.atlas.Sniffed
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.campaign.S0Run
import io.astrolabe.campaign.ShapeDecision
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Origin
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.id.AttemptId
import io.astrolabe.id.IdGen
import io.astrolabe.id.WorkId
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.telemetry.Spans
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import io.astrolabe.verify.ResultStatus
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Studio host behaviour around a run that is not part of its [RunSpec], reproduced minimally so the baseline
 * reflects the product (ASTROUI `AutoProfiles`, `Guidance`, `Verification`, `DecisionService`, `TaskService.recap`).
 * The launch itself — configuration, policy, cell cap, lease, effort, headroom — is [RunSpec.defaults] of the core,
 * which the Studio reads too; the texts below are copied, and a change there is a change here.
 */
internal object StudioPolicy {
    /** `AutoProfiles.OPENROUTER_UPSTREAM_IGNORES`: upstreams whose tool-call parser corrupts nested arguments. */
    val OPENROUTER_UPSTREAM_IGNORES: Map<String, List<String>> = mapOf("z-ai/" to listOf("Together"))

    /** `DecisionService.ASSUME` of the Studio: the auto-mode answer to every question. */
    const val ASSUME: String = "Use the most reasonable assumption and list your assumptions in the summary."

    /** `Guidance.NOTES`, verbatim. */
    const val WORKING_NOTES: String = "Working notes: if the request is a question or a greeting and needs no change to the files, write your answer " +
        "and end the task with the task tool, op \"answer\", {\"text\": your answer}: do not make a plan and do not call other tools without need. " +
        "Otherwise keep your notes with the state tool, op \"patch\". " +
        "Every item of \"patch\" is an object with exactly one of these keys and nothing else: " +
        "\"plan.add\" {\"text\"}, \"plan.tick\" {\"n\", \"evidence\"}, \"plan.cancel\" {\"n\", \"reason\"}, \"plan.cursor\" n, " +
        "\"fact.add\" {\"kind\", \"text\", \"evidence\"} where kind is \"v\" for what a tool result showed (evidence names that result, " +
        "\"op:1\" for the first call of the same turn or an alias such as \"#2\") and \"h\" for an assumption, " +
        "\"decision.add\" {\"text\", \"because\", \"rejected\"}, \"open.add\" {\"text\"}, \"focus.set\" {\"dir\"}, \"next\" \"text\". " +
        "Example: [{\"plan.add\":{\"text\":\"write the file\"}},{\"plan.tick\":{\"n\":1,\"evidence\":\"op:1\"}},{\"next\":\"run the checks\"}]. " +
        "When the work is done and checked, reply with a short summary and no tool call: that proposes completion " +
        "(op \"answer\" is only for a request that changes no file). " +
        "If one part cannot be done here (for example opening a browser), finish the rest and say so in that summary instead of stopping as blocked. " +
        "The run tool starts a program directly from \"argv\" (program, then its arguments); for shell syntax such as pipes, && or " +
        "setting a variable use its \"cmd\" form with one command line instead. " +
        "To keep a fact about this project for later tasks (where a tool lives, how the app is started), add a line to AGENTS.md " +
        "in the project root: every later task is given that file. " +
        "The user reads what you write in messages: use plain words, say what you changed and how you checked it, " +
        "and leave the ids of requirements, acceptance items and notes out of them."

    /** `Verification.REVIEW_TEXT`: the `check:` item of a project without a test command. */
    const val REVIEW_TEXT: String = "The change fulfils the request"

    /** `AutoProfiles.idOf`. */
    fun profileId(providerId: String, modelId: String): String = ("auto.$providerId." + modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")).take(128)

    /** `AutoProfiles.routed`: `gate.body.provider.ignore` with the OpenRouter upstreams skipped for [modelId]. */
    fun routed(providerId: String, modelId: String, config: JsonObject): JsonObject {
        if (providerId != "openrouter") return config
        val ignore = OPENROUTER_UPSTREAM_IGNORES.filterKeys { modelId.startsWith(it) }.values.flatten().distinct()
        if (ignore.isEmpty()) return config
        val gate = config["gate"] as? JsonObject ?: JsonObject(emptyMap())
        val body = JsonObject(mapOf("provider" to JsonObject(mapOf("ignore" to JsonArray(ignore.map(::JsonPrimitive))))))
        return JsonObject(config + ("gate" to JsonObject(gate + ("body" to body))))
    }

    /** `Guidance.platform`. */
    fun platform(os: String): String = "Commands run on $os" +
        (if (os.startsWith("Windows")) ": the shell of the \"cmd\" form is cmd.exe, and Unix programs such as python3, ls, which or xdg-open may be missing." else ".")

    /** `Verification.text`: what the agent is told about how its result is checked. */
    fun verificationText(setup: VerificationSetup): String {
        fun list(commands: List<List<String>>) = commands.joinToString(", ") { "`${Command(it).text}`" }
        val steps = if (setup.hints.isEmpty()) "" else " Run ${list(setup.hints)} before finishing and fix what it reports."
        return if (setup.commands.isEmpty()) {
            "Studio note: this project has no test command.$steps The Studio takes care of accepting the result. When the work is done, write a short summary of what you changed and stop. Do not call verify for acceptance: there is nothing to run."
        } else {
            "Studio note: the result is checked with ${list(setup.commands)}.$steps"
        }
    }

    /** `Verification.choose` without saved checks: a declared type check, build or lint becomes a step, never acceptance. */
    fun choose(sniffed: Sniffed): VerificationSetup {
        val root = sniffed.packages.firstOrNull { it.dir == PackageCommands.ROOT } ?: sniffed.packages.firstOrNull()
        val declared = root?.let { it.typecheck ?: it.build ?: it.lint }
        return if (declared != null) VerificationSetup("review", "declared", emptyList(), listOf(declared)) else VerificationSetup("review", "none", emptyList())
    }

    /** `Verification.apply`: [contract] with the items of [setup] appended and every requirement bound to them. */
    fun apply(contract: Contract, setup: VerificationSetup): Contract {
        val version = contract.version + 1
        val taken = contract.acceptance.map { it.id }.toSet()
        var n = contract.acceptance.size
        fun nextId(): String {
            do n++ while ("AC-$n" in taken)
            return "AC-$n"
        }
        val items: List<Acceptance> = if (setup.commands.isEmpty()) {
            listOf(Acceptance.Check(nextId(), REVIEW_TEXT, Origin.Amended(version), obligationVersion = version))
        } else {
            setup.commands.map { Acceptance.Run(nextId(), Command(it), Origin.Amended(version), obligationVersion = version) }
        }
        val ids = items.map { it.id }
        return contract.copy(
            acceptance = contract.acceptance + items,
            requirements = contract.requirements.map { it.copy(acceptance = it.acceptance + ids) },
        )
    }

    /** `TaskService.RECAP_LIMIT`. */
    const val RECAP_LIMIT: Int = 1_500

    /**
     * `TaskService.recap`: the start of a follow-up run's request — what was asked and what came of it, marked as
     * context. A message after a stop is a follow-up in the Studio (`TaskService.message`: a `cancelled` run is not
     * resumable). The agent's last report is left out: the Studio reads it from its own event log, which a run lacks.
     */
    fun recap(request: String, last: AttemptOutcome, changedFiles: List<String>): String {
        val outcome = when (last.outcome) {
            "completed" -> if (last.decisions.any { it.kind == "acceptance" && it.outcome == "accepted" }) {
                "finished, not verified (accepted by the auto policy without a passing check)"
            } else {
                "finished, not verified (no passing check recorded)"
            }
            "answered" -> "answered, nothing changed"
            "cancelled" -> "stopped by the user before it finished"
            "failed" -> "did not finish"
            else -> "paused"
        }
        val text = buildString {
            append("[Context from earlier in this task. Background only: it is not a new requirement and nothing in it has to be redone.]\n")
            append("Earlier request: ").append(cut(request, 400)).append('\n')
            append("Outcome: ").append(outcome).append(".\n")
            if (changedFiles.isNotEmpty()) append("Files changed so far: ").append(changedFiles.take(12).joinToString(", ")).append('\n')
            append("[End of context]\n\n")
        }
        return if (text.length > RECAP_LIMIT) text.substring(0, RECAP_LIMIT - 20) + "…\n[End of context]\n\n" else text
    }

    private fun cut(text: String, max: Int): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        return if (one.length > max) one.substring(0, max - 1) + "…" else one
    }

    /** `StudioHost.verificationOf`: how an opened contract is verified. */
    fun verificationOf(opened: OpenedCampaign): VerificationSetup {
        val runs = opened.contract.acceptance.filterIsInstance<Acceptance.Run>().map { it.command.argv }
        if (runs.isEmpty()) return VerificationSetup("review", "none", emptyList())
        val declared = runs.any { it in opened.sniffed.packages.mapNotNull { p -> p.test } }
        return VerificationSetup("tests", if (declared) "declared" else "saved", runs)
    }
}

/** `VerificationSetup` of the bridge: [kind] `tests` or `review`, [source] `declared`, `saved` or `none`. */
@Serializable
internal data class VerificationSetup(val kind: String, val source: String, val commands: List<List<String>>, val hints: List<List<String>> = emptyList())

/** One decision the auto policy made on the agent's behalf, as the Studio records it (`DecisionService.byPolicy`); [detail] says what it was about. */
@Serializable
internal data class PolicyDecision(val kind: String, val outcome: String, val detail: String? = null)

/**
 * The Studio's `auto` host policy (`DecisionService` with `HostPolicy.auto()`): questions get the standard assumption;
 * effects run only when the contract allowlists them; plan and contract changes are accepted unless they weaken the
 * task; knowledge stays queued; unverified work is accepted on the policy's word, a review rejection never is. The
 * Studio's model review pass is not reproduced: [review] has no reviewer.
 */
internal class StudioAutoAuthority : Authority {
    val decisions: MutableList<PolicyDecision> = CopyOnWriteArrayList()

    override suspend fun ask(question: Question): Answer {
        decisions += PolicyDecision("question", "answered")
        return Answer(question.id, question.contractRevision, StudioPolicy.ASSUME, null, false)
    }

    override suspend fun approve(request: DClassRequest): Decision {
        val allowed = request.contractAllowlisted
        decisions += PolicyDecision(if (request.action.startsWith("publish.")) "publication" else "effect", if (allowed) "allowed" else "skipped")
        return Decision(request.id, request.contractRevision, allowed, if (allowed) "always allowed in this project" else "auto mode: this action needs your approval")
    }

    override suspend fun resolve(proposal: AmendmentProposal): Resolution {
        val reason = proposal.reason.lowercase()
        // G-21 of the Studio: only the reason text distinguishes the kinds; the conservative default is a contract amendment.
        val kind = if (reason.startsWith("plan proposal")) "plan_acceptance" else if (reason == "knowledge admission") "kb_admission" else "amendment"
        val outcome = when {
            kind == "kb_admission" -> ResolutionOutcome.Pending
            proposal.weakening -> ResolutionOutcome.Rejected
            else -> ResolutionOutcome.Accepted
        }
        decisions += PolicyDecision(kind, outcome.name.lowercase())
        val why = if (proposal.weakening) "a change that makes the task easier to pass needs the user" else "host policy"
        return Resolution(proposal.id, proposal.contractRevision, outcome, "policy:studio", why)
    }

    override suspend fun review(request: ReviewRequest): Verdict? {
        decisions += PolicyDecision("review", "no reviewer")
        return null
    }

    override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
        val why = request.items.joinToString("; ") { it.reason.ifBlank { it.obligation } }
        val detail = request.items.joinToString("; ") { "${it.obligation} ${it.kind} ${it.status}: ${it.reason}" }.take(DETAIL_CHARS)
        if (request.items.any { it.status == ResultStatus.Failed }) {
            decisions += PolicyDecision("acceptance", "waiting", detail)
            return null
        }
        decisions += PolicyDecision("acceptance", "accepted", detail)
        return AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "not verified: $why")
    }

    private companion object {
        const val DETAIL_CHARS = 2_000
    }
}

/** A provider and the profile drafted for one model; [close] releases what the binding owns (the adapter). */
internal class ModelBinding(val adapter: ProviderAdapter, val profile: Profile, val estimators: EstimatorFactory, private val onClose: () -> Unit = {}) : AutoCloseable {
    override fun close(): Unit = onClose()
}

/** How one attempt ended, as the Studio's run listener sees it (`StudioHost.launch`). */
internal data class AttemptOutcome(
    val workId: String,
    val fingerprint: String?,
    val shape: String?,
    val verification: VerificationSetup?,
    val outcome: String?,
    val stopCode: String?,
    val reason: String?,
    val failure: String?,
    val cells: Int?,
    val decisions: List<PolicyDecision>,
    /** The response count after which the runner stopped the attempt (WP-B2 `interrupt`), or null when it did not. */
    val interruptedAt: Int? = null,
    /** The response count after which the runner closed the session (`reopen`), or null when it did not. */
    val closedAt: Int? = null,
    /** The response count after which the user's message reached the contract (`message`), or null when it did not. */
    val messageAt: Int? = null,
    /** The contract version the user's message made, or null when it was not delivered. */
    val messageContractVersion: Int? = null,
    /** The contract version this session's first open found: 1 for a new work, the stored one for a reopened work. */
    val openedContractVersion: Int? = null,
)

/**
 * Calls [cancel] once the bus has carried [afterResponses] `ModelResponded` emitted after this subscription began.
 * Delivery is asynchronous, so a call already in flight may still answer: it is counted in the segment, not here.
 */
internal class Interrupter(events: Events, private val afterResponses: Int, private val cancel: () -> Unit) : AutoCloseable {
    private val from = events.lastSeq
    private val seen = AtomicInteger()
    private val subscription = events.subscribe({ record ->
        if (record.seq > from && record.event is AgentEvent.Cell.ModelResponded && seen.incrementAndGet() == afterResponses) cancel()
    }, Recorder.BUFFER)

    override fun close(): Unit = subscription.close()
}

/** What the runner does to one session of an attempt, each after a count of `ModelResponded` of that session. */
internal data class SessionScript(
    /** Cancel through the attempt's token, as the Studio's stop button does (WP-B2 `interrupt`). */
    val interruptAfterResponses: Int? = null,
    /** Close the session as the Studio backend does when it stops: the run job is cancelled and stays resumable. */
    val closeAfterResponses: Int? = null,
    /** Deliver the user's message to the running campaign, as the Studio does for a message sent while it works. */
    val message: MessageSpec? = null,
)

/**
 * Runs one attempt the way a Studio task does: open, verification setup, host notes, reopen, run through the
 * [Controller], all as the [RunSpec] says. A [work] given reopens that work in the same state root (the Studio's resume).
 */
internal class StudioAttempt(private val clock: Clock, private val idGen: IdGen, private val osName: String = System.getProperty("os.name")) {
    suspend fun run(
        workspace: Path,
        prompt: String,
        binding: ModelBinding,
        events: Events,
        spec: RunSpec,
        deadline: Duration,
        script: SessionScript = SessionScript(),
        work: WorkId = WorkId(idGen.next("W")),
    ): AttemptOutcome {
        val config = spec.config
        val violations = config.violations()
        if (violations.isNotEmpty()) throw InvalidConfig(violations)
        val authority = StudioAutoAuthority()
        // `Astrolabe.open` is the only way to a `Project`; the facade's own controller is not used (as in the Studio).
        Astrolabe(config, binding.adapter, authority, clock, idGen).use { sdk ->
            sdk.open(workspace).use { project ->
                val controller = Controller(
                    config, clock, idGen, events,
                    spans = Spans(idGen, events),
                    leaseDuration = spec.leaseDuration,
                    estimators = binding.estimators,
                )
                val policy = spec.policy
                val request = CampaignRequest(work, AttemptId(Astrolabe.FIRST_ATTEMPT), prompt)
                var opened = controller.open(project, request, policy)
                val openedVersion = opened.contract.version
                val verification = if (opened.state == null) {
                    // Studio 2 §7.3: the core refuses a plan with nothing executable to accept against; supply it and open again.
                    StudioPolicy.choose(opened.sniffed).also { setup -> opened.contracts.amendByHost(work, "verification setup (${setup.kind})") { StudioPolicy.apply(it, setup) } }
                } else {
                    StudioPolicy.verificationOf(opened)
                }
                val notes = listOf(StudioPolicy.WORKING_NOTES, StudioPolicy.platform(osName), StudioPolicy.verificationText(verification))
                // As `StudioHost.launch`: a campaign this open could not free from a limit stays stopped, one open per action.
                if (opened.limitHold == null) opened = controller.open(project, request, policy.copy(hostNotes = notes))
                val frozen = opened.attempt.config
                val main = config.profiles[frozen.profileRoles.main] ?: frozen.profiles[frozen.profileRoles.main] ?: binding.profile
                val model = spec.cellModel(binding.adapter, main, binding.estimators.estimatorFor(main))
                val campaign = opened
                val interrupter = script.interruptAfterResponses?.let { k -> Interrupter(events, k) { campaign.cancellation.cancel(INTERRUPTED) } }
                val closed = AtomicBoolean()
                val delivered = AtomicInteger()
                fun outcome(failure: String?, run: S0Run?): AttemptOutcome {
                    val ended = failure == null && !closed.get()
                    return AttemptOutcome(
                        workId = work.value,
                        fingerprint = campaign.attempt.fingerprint.hex,
                        shape = (campaign.shape as? ShapeDecision.Selected)?.shape?.name.takeIf { failure == null },
                        verification = verification,
                        outcome = if (ended) run?.outcome?.wire ?: campaign.stop?.outcome?.wire else null,
                        stopCode = if (ended) (run?.state?.stopCode ?: campaign.stop?.code)?.wire else null,
                        reason = if (ended) run?.state?.reason ?: campaign.stop?.reason else if (failure == null) CLOSED else null,
                        failure = failure,
                        cells = (run?.state ?: campaign.state)?.cells?.size,
                        decisions = authority.decisions.toList(),
                        interruptedAt = script.interruptAfterResponses.takeIf { campaign.cancellation.reason == INTERRUPTED },
                        closedAt = script.closeAfterResponses.takeIf { closed.get() },
                        messageAt = script.message?.afterResponses.takeIf { delivered.get() > 0 },
                        messageContractVersion = delivered.get().takeIf { it > 0 },
                        openedContractVersion = openedVersion,
                    )
                }
                return try {
                    val run = coroutineScope {
                        val job = async { controller.run(campaign, model, authority, maxCells = spec.maxCells) }
                        val watchdog = launch {
                            delay(deadline.toMillis())
                            campaign.cancellation.cancel("eval-live: the attempt passed its deadline of ${deadline.toMinutes()} minutes")
                        }
                        val closer = script.closeAfterResponses?.let { k -> Interrupter(events, k) { closed.set(true); job.cancel(CancellationException(CLOSED)) } }
                        val due = CompletableDeferred<Unit>()
                        val messenger = script.message?.let { m -> Interrupter(events, m.afterResponses) { due.complete(Unit) } }
                        val delivery = script.message?.let { m ->
                            launch {
                                due.await()
                                // `StudioHost.amend` of a live campaign: the user's words become a contract request at once.
                                delivered.set(withContext(Dispatchers.IO) { campaign.contracts.amendByUser(work, m.text).version })
                            }
                        }
                        try {
                            job.await()
                        } catch (cancelled: CancellationException) {
                            if (closed.get() && job.isCancelled) null else throw cancelled
                        } finally {
                            watchdog.cancel()
                            delivery?.cancel()
                            interrupter?.close()
                            closer?.close()
                            messenger?.close()
                        }
                    }
                    outcome(null, run)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    outcome("${failure::class.java.simpleName}: ${failure.message}", null)
                }
            }
        }
    }

    companion object {
        /** The Studio's stop reason (`TaskService.stop`); the interruption stands in for the user's stop. */
        const val INTERRUPTED: String = "stopped by the user"

        /** Why a closed session has no outcome: the Studio backend stopped, and the work stays resumable. */
        const val CLOSED: String = "eval-live: the session was closed (the Studio backend stopping); the work is resumable"
    }
}
