package io.astrolabe.provider.aigate

import io.astrolabe.provider.Capabilities
import io.astrolabe.provider.Estimate
import io.astrolabe.provider.EstimatorFactory
import io.astrolabe.provider.Invocation
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationListener
import io.astrolabe.provider.InvocationProgress
import io.astrolabe.provider.ObservableAdapter
import io.astrolabe.provider.Problem
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderAdapter
import io.astrolabe.provider.Request
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.provider.UsageNormalizer
import io.astrolabe.provider.Validation
import io.astrolabe.provider.Validations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.diagnostics.PreparedCall
import net.ai.gate.event.LlmListener
import net.ai.gate.event.RequestEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** A request encoded once for the SDK, or why it cannot be. */
internal sealed interface Prepared {
    class Ready(val call: PreparedCall) : Prepared
    class Refused(val reason: String) : Prepared
}

/**
 * ASTROLABE's provider adapter over AI Gate (`llm-transport-sdk`): one [Llm] runtime serves every profile, each bound
 * once to its SDK model by `Profile.provider`/`Profile.model` and the `gate` block of `Profile.config`. The SDK owns
 * HTTP, credentials, codecs, retries, deadlines and cancellation plumbing; this adapter owns the translation between
 * ASTROLABE's item model and the SDK conversation, the D-51 invocation state machine, usage normalization and the
 * truthfulness checks of every profile's declared capabilities (§15.1).
 *
 * ```kotlin
 * val llm = Llm.builder().provider(...).credentials(CredentialStore.file(path)).catalog { it.offline() }.build()
 * val adapter = AiGateAdapter(llm, config.profiles.values)
 * val astrolabe = Astrolabe(config, adapter, authority, estimators = adapter.estimators(HeuristicEstimator()))
 * ```
 *
 * Profiles are compiled at construction, and a profile first seen later is compiled on first use; a contradiction
 * with the SDK (unknown provider, a limit above the catalog's, breakpoints on an API without cache markers, …) throws
 * `IllegalArgumentException` listing every problem ([violations] reports them without throwing).
 *
 * **Lifecycle.** [close] cancels in-flight invocations (their terminals still settle) and closes the runtime only when
 * [ownsLlm]. Close order for a host: campaigns → `Astrolabe.close()` → this adapter → the runtime it borrowed.
 */
public class AiGateAdapter @JvmOverloads public constructor(
    private val llm: Llm,
    profiles: Collection<Profile> = emptyList(),
    private val ownsLlm: Boolean = false,
) : ObservableAdapter, AutoCloseable {
    override val id: String = ID

    private val bindings = ConcurrentHashMap<Profile, ProfileBinding>()
    private val invocations = ConcurrentHashMap<InvocationId, AiGateInvocation>()
    private val listeners = CopyOnWriteArrayList<InvocationListener>()

    /** The last request prepared for estimate/validate: the cell asks both for the same request object, in turn. */
    private val lastPrepared = AtomicReference<Pair<Request, Prepared>?>(null)

    init {
        val problems = ArrayList<String>()
        for (profile in profiles) ProfileBinding.compile(llm, profile, problems)?.let { bindings[profile] = it }
        require(problems.isEmpty()) { "AI Gate profiles are inconsistent with the SDK: ${problems.joinToString("; ")}" }
    }

    override fun capabilities(profile: Profile): Capabilities = binding(profile).profile.capabilities

    /**
     * The shared rules ([Validations.standard]), then the SDK's own preparation of exactly this request: history
     * hand-off under the profile's reasoning policy (AX-07), strict adaptations and output-cap facts. Nothing is sent.
     */
    override fun validate(request: Request, estimate: Estimate): Validation {
        val binding = binding(request.profile)
        val problems = ArrayList<Problem>()
        (Validations.standard(request, estimate, binding.profile.capabilities) as? Validation.Rejected)?.let { problems += it.problems }
        if (problems.none { it.kind == ProblemKind.BrokenToolPairing }) {
            when (val prepared = prepared(request, binding)) {
                is Prepared.Refused -> problems += Problem(ProblemKind.InvalidRequest, prepared.reason)
                is Prepared.Ready -> {
                    val effective = prepared.call.effectiveOptions().maxTokens()
                    if (binding.outputCapEnforced && effective.isPresent && effective.asInt != request.maxOutputTokens) {
                        problems += Problem(ProblemKind.InvalidRequest, "the SDK would send max tokens ${effective.asInt}, not the reserved ${request.maxOutputTokens}")
                    }
                }
            }
        }
        return if (problems.isEmpty()) Validation.Ok else Validation.Rejected(problems)
    }

    override fun start(request: Request, id: InvocationId): Invocation {
        val binding = binding(request.profile)
        val invocation = AiGateInvocation(id, binding) { invocations.remove(it) }
        require(invocations.putIfAbsent(id, invocation) == null) { "invocation $id already registered" }
        return invocation.begin {
            val conversation = RequestTranslator.translate(request, binding)
            llm.start(binding.model, conversation, binding.options(request, id, progress(id)))
        }
    }

    /**
     * The native form this normalizer reads is an AI Gate reply archive (`AssistantMessage.toJson()`, `ai-gate.reply/…`),
     * whose usage the SDK has already decoded per wire API (S-04); see `UsageMapper` for the dimensions.
     */
    override val normalizer: UsageNormalizer = UsageNormalizer { native, profile ->
        val schema = ((native as? JsonObject)?.get("schema") as? JsonPrimitive)?.content
        require(schema != null && schema.startsWith("ai-gate.reply/")) { "expected an AI Gate reply archive (ai-gate.reply/…), got schema $schema" }
        val reply = AssistantMessage.fromJson(JsonBridge.toGateObject(native as JsonObject))
        UsageMapper.billable(reply.usage(), reply.responseModel().orElse(null), binding(profile))
    }

    /**
     * Per-profile admission estimators (D-06, A-01): each counts the request in its prepared wire form, measuring text
     * with [text] — typically the host's planning heuristic.
     */
    public fun estimators(text: TokenEstimator): EstimatorFactory = EstimatorFactory { profile -> AiGateEstimator(llm, this, binding(profile), text) }

    override fun addListener(listener: InvocationListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Warnings of profiles compiled with `gate.catalogCheck = "warn"`. */
    public fun warnings(): List<String> = bindings.values.flatMap { it.warnings }

    override fun close() {
        invocations.values.forEach(AiGateInvocation::cancel)
        if (ownsLlm) llm.close()
    }

    internal fun binding(profile: Profile): ProfileBinding = bindings[profile] ?: run {
        val problems = ArrayList<String>()
        val compiled = ProfileBinding.compile(llm, profile, problems)
        requireNotNull(compiled) { "AI Gate profile ${profile.id} is inconsistent with the SDK: ${problems.joinToString("; ")}" }
        bindings.putIfAbsent(profile, compiled) ?: compiled
    }

    /** Prepares [request] once for the estimate and the validation that follow it; any other request misses. */
    internal fun prepared(request: Request, binding: ProfileBinding): Prepared {
        lastPrepared.get()?.let { (cached, prepared) -> if (cached === request) return prepared }
        val prepared = try {
            val conversation = RequestTranslator.translate(request, binding)
            Prepared.Ready(llm.prepare(binding.model, conversation, binding.options(request, null, null), true))
        } catch (e: TranslationException) {
            Prepared.Refused(e.message ?: "untranslatable request")
        } catch (e: net.ai.gate.error.LlmException) {
            Prepared.Refused("${e.code().value()}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Prepared.Refused(e.message ?: e.toString())
        }
        lastPrepared.set(request to prepared)
        return prepared
    }

    /** SDK request events of one call as content-free [InvocationProgress] (A-08); `null` without listeners. */
    private fun progress(id: InvocationId): LlmListener? {
        if (listeners.isEmpty()) return null
        return LlmListener { event ->
            val progress = when (event) {
                is RequestEvent.Started -> InvocationProgress.Started(id, event.requestId())
                is RequestEvent.Progress -> InvocationProgress.Output(id, event.outputChars(), event.outputTokens().let { if (it.isPresent) it.asLong else null })
                is RequestEvent.Retrying -> InvocationProgress.Retrying(id, event.attempt(), event.errorCode().value())
                else -> null
            }
            if (progress != null) for (listener in listeners) runCatching { listener.onProgress(progress) }
        }
    }

    public companion object {
        public const val ID: String = "ai-gate"

        /** What would keep [profiles] from binding to [llm], without throwing: for a settings UI and `Config` checks. */
        @JvmStatic
        public fun violations(llm: Llm, profiles: Collection<Profile>): List<String> {
            val problems = ArrayList<String>()
            profiles.forEach { ProfileBinding.compile(llm, it, problems) }
            return problems
        }
    }
}
