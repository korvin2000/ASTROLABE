package io.astrolabe.evallive

import io.astrolabe.Astrolabe
import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.InvalidConfig
import io.astrolabe.Mode
import io.astrolabe.ProfileRoles
import io.astrolabe.UnknownOutcomeReconciliation
import io.astrolabe.atlas.PackageCommands
import io.astrolabe.atlas.Sniffed
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.CellModel
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Origin
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
import io.astrolabe.provider.Effort
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a Studio task in `auto` mode runs with, reproduced minimally so the baseline reflects the product (ASTROUI
 * `TaskService.config`/`spec`, bridge `StudioHost.launch`, `AutoProfiles`, `Guidance`, `Verification`,
 * `DecisionService`). The Studio is not a dependency: these values are copied, and a change there is a change here.
 */
internal object StudioPolicy {
    /** `SettingsService.RUNTIME_DEFAULTS`: `maxCells` 12, `leaseMinutes` 480. */
    const val MAX_CELLS: Int = 12
    const val LEASE_MINUTES: Long = 480

    /** `TaskService.spec`: the automatic limit is twelve context windows of the bound model, no money limit. */
    const val BUDGET_WINDOWS: Long = 12

    /** `AutoProfiles.OPENROUTER_UPSTREAM_IGNORES`: upstreams whose tool-call parser corrupts nested arguments. */
    val OPENROUTER_UPSTREAM_IGNORES: Map<String, List<String>> = mapOf("z-ai/" to listOf("Together"))

    /** `AnswerPolicy.ASSUME` of the Studio's `DecisionService`: the auto-mode answer to every question. */
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

    /** `TaskService.config` over the library defaults: one model for every function, `auto` mode, D-class asks. */
    fun config(profile: Profile, stateRoot: Path): Config = Config(
        profiles = mapOf(profile.id to profile),
        profileRoles = ProfileRoles(main = profile.id, helper = null, escalation = null),
        mode = Mode.Autonomous,
        dClass = DClassPolicy.Ask,
        unknownOutcomeReconciliation = UnknownOutcomeReconciliation.Automatic,
        rulesFile = null,
        stateRoot = stateRoot.toString(),
    )

    fun budget(profile: Profile): Tokens = Tokens(profile.capabilities.contextLimitTokens.toLong() * BUDGET_WINDOWS)

    /** `AutoProfiles.outputHeadroom` without a user narrowing: a request reserves a quarter of the window at most. */
    fun outputHeadroom(profile: Profile): Int {
        val share = maxOf(profile.capabilities.contextLimitTokens / 4, 1)
        return minOf(profile.capabilities.outputLimitTokens, share).coerceAtLeast(1)
    }

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

/** One decision the auto policy made on the agent's behalf, as the Studio records it (`DecisionService.byPolicy`). */
@Serializable
internal data class PolicyDecision(val kind: String, val outcome: String)

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

    override suspend fun review(request: ReviewRequest): Verdict? = null

    override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
        if (request.items.any { it.status == ResultStatus.Failed }) {
            decisions += PolicyDecision("acceptance", "waiting")
            return null
        }
        val why = request.items.joinToString("; ") { it.reason.ifBlank { it.obligation } }
        decisions += PolicyDecision("acceptance", "accepted")
        return AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "not verified: $why")
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
)

/** Runs one attempt the way a Studio task does: open, verification setup, host notes, reopen, run through the [Controller]. */
internal class StudioAttempt(private val clock: Clock, private val idGen: IdGen, private val osName: String = System.getProperty("os.name")) {
    suspend fun run(workspace: Path, stateRoot: Path, prompt: String, binding: ModelBinding, events: Events, effort: Effort, maxCells: Int, deadline: Duration): AttemptOutcome {
        val config = StudioPolicy.config(binding.profile, stateRoot)
        val violations = config.violations()
        if (violations.isNotEmpty()) throw InvalidConfig(violations)
        val authority = StudioAutoAuthority()
        val work = WorkId(idGen.next("W"))
        // `Astrolabe.open` is the only way to a `Project`; the facade's own controller is not used (as in the Studio).
        Astrolabe(config, binding.adapter, authority, clock, idGen).use { sdk ->
            sdk.open(workspace).use { project ->
                val controller = Controller(
                    config, clock, idGen, events,
                    spans = Spans(idGen, events),
                    leaseDuration = Duration.ofMinutes(StudioPolicy.LEASE_MINUTES),
                    estimators = binding.estimators,
                )
                val policy = CampaignPolicy(StudioPolicy.budget(binding.profile), null, false)
                val request = CampaignRequest(work, AttemptId(Astrolabe.FIRST_ATTEMPT), prompt)
                var opened = controller.open(project, request, policy)
                val verification = if (opened.state == null) {
                    // Studio 2 §7.3: the core refuses a plan with nothing executable to accept against; supply it and open again.
                    StudioPolicy.choose(opened.sniffed).also { setup -> opened.contracts.amendByHost(work, "verification setup (${setup.kind})") { StudioPolicy.apply(it, setup) } }
                } else {
                    StudioPolicy.verificationOf(opened)
                }
                val notes = listOf(StudioPolicy.WORKING_NOTES, StudioPolicy.platform(osName), StudioPolicy.verificationText(verification))
                opened = controller.open(project, request, policy.copy(hostNotes = notes))
                val frozen = opened.attempt.config
                val main = config.profiles[frozen.profileRoles.main] ?: frozen.profiles[frozen.profileRoles.main] ?: binding.profile
                val model = CellModel(binding.adapter, main, binding.estimators.estimatorFor(main), effort, StudioPolicy.outputHeadroom(main))
                val campaign = opened
                return try {
                    val run = coroutineScope {
                        val watchdog = launch {
                            delay(deadline.toMillis())
                            campaign.cancellation.cancel("eval-live: the attempt passed its deadline of ${deadline.toMinutes()} minutes")
                        }
                        try {
                            controller.run(campaign, model, authority, maxCells = maxCells)
                        } finally {
                            watchdog.cancel()
                        }
                    }
                    AttemptOutcome(
                        workId = work.value,
                        fingerprint = campaign.attempt.fingerprint.hex,
                        shape = (campaign.shape as? io.astrolabe.campaign.ShapeDecision.Selected)?.shape?.name,
                        verification = verification,
                        outcome = run.outcome?.wire ?: campaign.stop?.outcome?.wire,
                        stopCode = (run.state?.stopCode ?: campaign.stop?.code)?.wire,
                        reason = run.state?.reason ?: campaign.stop?.reason,
                        failure = null,
                        cells = run.state?.cells?.size,
                        decisions = authority.decisions.toList(),
                    )
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    AttemptOutcome(
                        work.value, campaign.attempt.fingerprint.hex, null, verification, null, null, null,
                        "${failure::class.java.simpleName}: ${failure.message}", campaign.state?.cells?.size, authority.decisions.toList(),
                    )
                }
            }
        }
    }
}
