package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.InvalidConfig
import io.astrolabe.RunSpec
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.atlas.Sniff
import io.astrolabe.atlas.Sniffed
import io.astrolabe.campaign.Attempts
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.campaign.S0Run
import io.astrolabe.campaign.ShapeDecision
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap
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

    /** `Guidance.DIRECT_NOTES`, verbatim: [WORKING_NOTES] for a main line of the direct protocol (`state(note)`, `task(finish)`). */
    const val DIRECT_WORKING_NOTES: String = "Working notes: if the request is a question or a greeting and needs no change to the files, write your answer " +
        "and end the task with the task tool, op \"answer\", {\"text\": your answer}: do not make a plan and do not call other tools without need. " +
        "Otherwise keep short notes with the state tool, op \"note\", {\"note\": {\"kind\", \"text\", \"evidence\"}} where kind is \"decision\" for a choice made, " +
        "\"hypothesis\" for an assumption, \"open\" for a question still open and \"deadend\" for an approach that failed; evidence names a stored result " +
        "(\"#2\") or a run or verify call of the same turn (\"op:1\"). " +
        "When the work is done and checked, end the task with the task tool, op \"finish\", {\"text\": a short summary}: the harness runs the declared " +
        "checks and decides (op \"answer\" is only for a request that changes no file). " +
        "If one part cannot be done here (for example opening a browser), finish the rest and say so in that summary instead of stopping as blocked. " +
        "The run tool starts a program directly from \"argv\" (program, then its arguments); for shell syntax such as pipes, && or " +
        "setting a variable use its \"cmd\" form with one command line instead. " +
        "To keep a fact about this project for later tasks (where a tool lives, how the app is started), add a line to AGENTS.md " +
        "in the project root: every later task is given that file. " +
        "The user reads what you write in messages: use plain words, say what you changed and how you checked it, " +
        "and leave the ids of requirements, acceptance items and notes out of them."

    /** `Guidance.notes`: the working notes for a main line of [protocol]. */
    fun workingNotes(protocol: Protocol): String = when (protocol) {
        Protocol.Structured -> WORKING_NOTES
        Protocol.Direct -> DIRECT_WORKING_NOTES
    }

    /** `StudioHost.protocolOf`: the protocol the opened campaign's main line speaks — the frozen attempt's, in the contract's shape. */
    fun protocolOf(opened: OpenedCampaign): Protocol = Roles.mainLine(opened.attempt.config.protocol, opened.contract.shape).protocol

    /** `StudioHost.expectedProtocol`: [protocolOf] before the open — the frozen [attempt]'s protocol, else [config]'s; the [stored] shape, else S0. */
    fun expectedProtocol(attempt: AttemptConfig?, config: Config, stored: Contract?): Protocol =
        Roles.mainLine(attempt?.config?.protocol ?: config.protocol, stored?.shape ?: Shape.S0).protocol

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

    /**
     * `StudioHost.expectedVerification` of a new work, without saved checks: the suites the manifests under [root] declare
     * (the sniffed command of every package that has one), else the setup a contract without any gets.
     */
    fun expected(root: Path): VerificationSetup {
        val sniffed = Sniff.commands(root, listedPaths(root))
        val suites = sniffed.packages.mapNotNull { it.test }
        return if (suites.isEmpty()) choose(sniffed) else VerificationSetup("tests", "declared", suites)
    }

    /** The repository's tracked and unignored files, `/`-separated, by one `git ls-files`; empty when git cannot say. */
    private fun listedPaths(root: Path): Set<String> {
        val result = runCatching { Proc.run(listOf("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"), root, Duration.ofSeconds(30)) }.getOrNull()
        if (result == null || result.exitCode != 0) return emptySet()
        return result.output.split('\u0000').filterTo(HashSet()) { it.isNotEmpty() }
    }

    /**
     * `Verification.items` (T-01, WF-1; T-12): the items the core's `CampaignPolicy.declaredChecks` takes for a new contract
     * — the review item of a project with no test command — so the work opens once, with no host amendment.
     */
    fun declaredChecks(setup: VerificationSetup): List<Acceptance> =
        if (setup.commands.isEmpty()) listOf(Acceptance.Check("AC-review", REVIEW_TEXT, Origin.User)) else emptyList()

    /** `Verification.of`: how a stored [contract] is verified, read by origin: `saved` for the user's own `run:` items, else `declared`. */
    fun of(contract: Contract): VerificationSetup {
        val runs = contract.acceptance.filterIsInstance<Acceptance.Run>()
        if (runs.isEmpty()) return VerificationSetup("review", "none", emptyList())
        val users = runs.filter { it.origin is Origin.User || it.origin is Origin.Amended }
        return if (users.isNotEmpty()) VerificationSetup("tests", "saved", users.map { it.command.argv }) else VerificationSetup("tests", "declared", runs.map { it.command.argv })
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

    /**
     * `StudioHost.verificationOf`: how an opened contract is verified — by origin, as [of] reads the stored one before
     * the open (WD-23), never by matching sniffed suites: the two never disagree over one contract (WF-1, review 5 P2 #1).
     */
    fun verificationOf(opened: OpenedCampaign): VerificationSetup = of(opened.contract)
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
 * Studio's model review pass is not reproduced: [review] has no reviewer. With a [user] it plays the `ask` mode's
 * acceptance (`DecisionService.decide` without `auto`): the policy settles no acceptance request, [user] answers it.
 */
internal class StudioAuthority(private val user: ScriptedUser? = null) : Authority {
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
        if (user != null) {
            val answer = user.decide(request)
            decisions += PolicyDecision("acceptance", if (answer == null) "waiting" else "answered", detail)
            return answer
        }
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

/**
 * The user of the Studio's `ask` mode, scripted (WP-WG): an acceptance request stays open and the campaign stops
 * waiting; [accept] answers the open request as the user does on its card, and the request the reopened campaign
 * issues again is matched by its [AcceptanceDecisionRequest.key] (D-428: the same key is the same question).
 */
internal class ScriptedUser {
    private val accepted = ConcurrentHashMap.newKeySet<String>()

    /** The request the last session left waiting for the user, or `null`. */
    @Volatile
    var open: AcceptanceDecisionRequest? = null
        private set

    /** How many requests [accept] answered. */
    @Volatile
    var answers: Int = 0
        private set

    /** The user's answer to [request] if they gave one, else `null`: the request is kept [open]. */
    fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
        if (request.key !in accepted) {
            open = request
            return null
        }
        open = null
        return AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, BY, REASON)
    }

    /** Accepts the [open] request (`TaskService.decideAcceptance` with `accept`); false when none is open. */
    fun accept(): Boolean {
        val request = open ?: return false
        accepted += request.key
        answers++
        open = null
        return true
    }

    companion object {
        /** Answers one attempt may give; a request still open after them ends the run as [EXHAUSTED]. */
        const val MAX_ANSWERS: Int = 3

        /** The `ask` outcome of a run whose requests outlasted [MAX_ANSWERS]. */
        const val EXHAUSTED: String = "ask-exhausted"

        /** `TaskService.decideAcceptance`: the local user, and the reason of an `accept` without text. */
        const val BY: String = "user:local"
        const val REASON: String = "the user confirmed the task is done"
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
    /** The cells this session started: a reopen of the same work does not count the earlier sessions' cells again. */
    val cells: Int?,
    val decisions: List<PolicyDecision>,
    /** The response count after which the runner stopped the attempt (WP-B2 `interrupt`), or null when it did not. */
    val interruptedAt: Int? = null,
    /** The session's responses, measured, when the runner's close ended it (`reopen`); null when the run ended first. */
    val closedAt: Int? = null,
    /** The session's responses seen, measured, when the user's message reached the agent (`message`); null when it did not. */
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

/**
 * Counts the `ModelResponded` the bus carries after this subscription began (B7 review P2-2: what a session reports is
 * measured, not the script's number). Delivery is asynchronous: [settled] waits until every event emitted so far is seen.
 */
internal class ResponseCount(private val events: Events) : AutoCloseable {
    private val from = events.lastSeq
    private val seen = AtomicInteger()

    @Volatile
    private var lastSeen = from
    private val subscription = events.subscribe({ record ->
        if (record.seq > from && record.event is AgentEvent.Cell.ModelResponded) seen.incrementAndGet()
        if (record.seq > lastSeen) lastSeen = record.seq
    }, Recorder.BUFFER)

    /** The responses seen so far, which may lag the bus. */
    val now: Int get() = seen.get()

    /** The responses of everything emitted until now, waiting up to [waitMillis] for delivery. */
    fun settled(waitMillis: Long = 10_000): Int {
        val target = events.lastSeq
        val until = System.nanoTime() + waitMillis * 1_000_000
        while (lastSeen < target && System.nanoTime() < until) Thread.sleep(5)
        return seen.get()
    }

    override fun close(): Unit = subscription.close()
}

/**
 * The close of a session (B7 review P2-1): it counts as closed only when the close cancelled the run. A run that ended
 * before the close reached it keeps its outcome, and no second session opens a finished work.
 */
internal class SessionClose {
    private val requested = AtomicBoolean()

    @Volatile
    var closed: Boolean = false
        private set

    fun request(job: Job) {
        requested.set(true)
        job.cancel(CancellationException(StudioAttempt.CLOSED))
    }

    /** [job]'s value, or `null` when the close cancelled it; any other cancellation is rethrown. */
    suspend fun <T> await(job: Deferred<T>): T? = try {
        job.await()
    } catch (cancelled: CancellationException) {
        if (!requested.get() || !job.isCancelled) throw cancelled
        closed = true
        null
    }
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
        user: ScriptedUser? = null,
    ): AttemptOutcome {
        val config = spec.config
        val violations = config.violations()
        if (violations.isNotEmpty()) throw InvalidConfig(violations)
        val authority = StudioAuthority(user)
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
                // T-12 (WF-1), as `StudioHost.launch`: the notes and the declared checks are worked out before the open from what
                // it will find — the stored contract, or the suites a new one is derived from — so one open serves the start.
                val stored = Contracts(SqliteContractRepository(project.store, clock), idGen, clock).current(work)
                val expected = if (stored != null) StudioPolicy.of(stored) else StudioPolicy.expected(workspace)
                val declared = if (stored == null) StudioPolicy.declaredChecks(expected) else emptyList()
                fun notesOf(setup: VerificationSetup, protocol: Protocol) = listOf(StudioPolicy.workingNotes(protocol), StudioPolicy.platform(osName), StudioPolicy.verificationText(setup))
                val notes = notesOf(expected, StudioPolicy.expectedProtocol(Attempts(project.store, clock).load(work, request.attempt), config, stored))
                var opened = controller.open(project, request, policy.copy(hostNotes = notes, declaredChecks = declared))
                val openedVersion = opened.contract.version
                // Review 5 P2 #4: a segment counts the cells it started, not the work's cells so far — a reopen continues them.
                val earlierCells = opened.state?.cells.orEmpty().mapTo(HashSet()) { it.cell }
                var amended = false
                val verification = when {
                    declared.isNotEmpty() && opened.state != null -> expected
                    opened.state == null -> {
                        // Studio 2 §7.3: the core still refuses a plan with nothing executable to accept against; supply it and open again.
                        amended = true
                        StudioPolicy.choose(opened.sniffed).also { setup -> opened.contracts.amendByHost(work, "verification setup (${setup.kind})") { StudioPolicy.apply(it, setup) } }
                    }
                    else -> StudioPolicy.verificationOf(opened)
                }
                val actual = notesOf(verification, StudioPolicy.protocolOf(opened))
                // A second open only when the first could not run or found other notes than expected (the preflight guessed wrong,
                // never silently); a campaign this open could not free from a limit stays stopped, one open per action.
                if ((amended || actual != notes) && opened.limitHold == null) {
                    if (!amended) events.emit(AgentEvent.Warning(opened.ids, "preflight-diverged", "the host notes the preflight expected differ from the opened contract's; the campaign opens again with them (WF-1)"))
                    opened = controller.open(project, request, policy.copy(hostNotes = actual))
                }
                val frozen = opened.attempt.config
                val main = config.profiles[frozen.profileRoles.main] ?: frozen.profiles[frozen.profileRoles.main] ?: binding.profile
                val model = spec.cellModel(binding.adapter, main, binding.estimators.estimatorFor(main))
                val campaign = opened
                val interrupter = script.interruptAfterResponses?.let { k -> Interrupter(events, k) { campaign.cancellation.cancel(INTERRUPTED) } }
                val close = SessionClose()
                val counter = ResponseCount(events)
                val delivered = AtomicInteger()
                val deliveredAt = AtomicInteger()
                fun outcome(failure: String?, run: S0Run?): AttemptOutcome {
                    val closedAt = if (close.closed) counter.settled() else null
                    counter.close()
                    val ended = failure == null && !close.closed
                    return AttemptOutcome(
                        workId = work.value,
                        fingerprint = campaign.attempt.fingerprint.hex,
                        shape = (campaign.shape as? ShapeDecision.Selected)?.shape?.name.takeIf { failure == null },
                        verification = verification,
                        outcome = if (ended) run?.outcome?.wire ?: campaign.stop?.outcome?.wire else null,
                        stopCode = if (ended) (run?.state?.stopCode ?: campaign.stop?.code)?.wire else null,
                        reason = if (ended) run?.state?.reason ?: campaign.stop?.reason else if (failure == null) CLOSED else null,
                        failure = failure,
                        cells = (run?.state ?: campaign.state)?.cells?.map { it.cell }?.toSet()?.minus(earlierCells)?.size,
                        decisions = authority.decisions.toList(),
                        interruptedAt = script.interruptAfterResponses.takeIf { campaign.cancellation.reason == INTERRUPTED },
                        closedAt = closedAt,
                        messageAt = deliveredAt.get().takeIf { delivered.get() > 0 },
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
                        val closer = script.closeAfterResponses?.let { k -> Interrupter(events, k) { close.request(job) } }
                        val due = CompletableDeferred<Unit>()
                        val messenger = script.message?.let { m -> Interrupter(events, m.afterResponses) { due.complete(Unit) } }
                        val delivery = script.message?.let { m ->
                            launch {
                                due.await()
                                // `StudioHost.amend` of a live campaign: the user's words become a contract request at once.
                                val version = withContext(Dispatchers.IO) { campaign.contracts.amendByUser(work, m.text).version }
                                deliveredAt.set(counter.now)
                                delivered.set(version)
                            }
                        }
                        try {
                            close.await(job)
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
