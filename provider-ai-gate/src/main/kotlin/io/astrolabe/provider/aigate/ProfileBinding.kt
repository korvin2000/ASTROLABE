package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.ai.gate.Llm
import net.ai.gate.cache.CacheMode
import net.ai.gate.cache.CacheRetention
import net.ai.gate.chat.Conversation
import net.ai.gate.error.LlmException
import net.ai.gate.chat.options.ChatOptions
import net.ai.gate.chat.options.HistoryPolicy
import net.ai.gate.chat.options.ReasoningHandoff
import net.ai.gate.event.LlmListener
import net.ai.gate.model.Capability
import net.ai.gate.model.Model
import net.ai.gate.model.ReasoningLevel
import net.ai.gate.model.SupportLevel
import net.ai.gate.spi.protocol.ApiFeatures
import java.util.Locale

/**
 * The `gate` block of `Profile.config` (§5.6 of the integration design, D-333), version 1. Every member is optional:
 *
 * ```json
 * "gate": {
 *   "v": 1,
 *   "api": "anthropic-messages",           // must match the wire API the SDK resolves for the model
 *   "options": { … },                      // ChatOptions JSON form: timeouts, retry, sessionId, strict, headers, …
 *   "reasoningHandoff": "reject",          // reject | drop — foreign reasoning in the transcript (AX-07)
 *   "outputCap": "enforced",               // enforced | unsupported — whether maxOutputTokens is sent (Codex: unsupported)
 *   "catalogCheck": "fail",                // fail | warn | off — profile limits against the SDK catalog
 *   "prefixRetention": "long",             // short | long — cache retention of the S/R/K breakpoints
 *   "tokenCount": "local",                 // local | endpoint — admission counts (endpoint: a provider call per estimate)
 *   "effort": "map"                        // map | off — Effort as the SDK reasoning level
 * }
 * ```
 */
internal data class GateSettings(
    val api: String? = null,
    val options: ChatOptions = ChatOptions.none(),
    val dropForeignReasoning: Boolean = false,
    val outputCap: String? = null,
    val catalogCheck: String = "fail",
    val prefixRetention: CacheRetention? = null,
    val endpointCounts: Boolean = false,
    val mapEffort: Boolean = true,
) {
    companion object {
        private val MEMBERS = setOf("v", "api", "options", "reasoningHandoff", "outputCap", "catalogCheck", "prefixRetention", "tokenCount", "effort")
        private const val OPTIONS_SCHEMA = "ai-gate.options/3"

        fun parse(config: JsonObject, problems: MutableList<String>): GateSettings {
            val gate = when (val element = config["gate"]) {
                null, is JsonNull -> return GateSettings()
                is JsonObject -> element
                else -> { problems += "gate: must be an object"; return GateSettings() }
            }
            gate.keys.filter { it !in MEMBERS }.forEach { problems += "gate.$it: unknown member" }
            gate["v"]?.let { if ((it as? JsonPrimitive)?.intOrNull != 1) problems += "gate.v: only version 1 is known" }
            fun choice(name: String, allowed: Set<String>): String? {
                val value = (gate[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return gate[name]?.let { problems += "gate.$name: must be a string"; null }
                return value.lowercase(Locale.ROOT).takeIf { it in allowed } ?: null.also { problems += "gate.$name: '$value' is not one of $allowed" }
            }
            val options = when (val o = gate["options"]) {
                null, is JsonNull -> ChatOptions.none()
                is JsonObject -> try {
                    val withSchema = if ("schema" in o) o else JsonObject(mapOf("schema" to JsonPrimitive(OPTIONS_SCHEMA)) + o)
                    ChatOptions.fromJson(JsonBridge.toGateObject(withSchema))
                } catch (e: IllegalArgumentException) {
                    problems += "gate.options: ${e.message}"
                    ChatOptions.none()
                }
                else -> { problems += "gate.options: must be an object"; ChatOptions.none() }
            }
            return GateSettings(
                api = (gate["api"] as? JsonPrimitive)?.content,
                options = options,
                dropForeignReasoning = choice("reasoningHandoff", setOf("reject", "drop")) == "drop",
                outputCap = choice("outputCap", setOf("enforced", "unsupported")),
                catalogCheck = choice("catalogCheck", setOf("fail", "warn", "off")) ?: "fail",
                prefixRetention = when (choice("prefixRetention", setOf("short", "long"))) {
                    "short" -> CacheRetention.SHORT
                    "long" -> CacheRetention.LONG
                    else -> null
                },
                endpointCounts = choice("tokenCount", setOf("local", "endpoint")) == "endpoint",
                mapEffort = choice("effort", setOf("map", "off")) != "off",
            )
        }
    }
}

/**
 * One profile frozen against the SDK once (G-02): the catalog model, the wire API and its facts, the options template
 * and the per-request policies. Built when the adapter first sees the profile; the catalog is never re-read for it.
 */
internal class ProfileBinding private constructor(
    val profile: Profile,
    val model: Model,
    val features: ApiFeatures,
    val settings: GateSettings,
    /** Warnings of a `catalogCheck = "warn"` profile; logged by the host through `AiGateAdapter.violations`. */
    val warnings: List<String>,
) {
    val api: String get() = features.api()

    /** `maxOutputTokens` is sent and enforced on the wire; otherwise it is ASTROLABE's reservation bound only (Codex, §5.5). */
    val outputCapEnforced: Boolean get() = settings.outputCap?.let { it == "enforced" } ?: (features.outputCap() != ApiFeatures.OutputCap.UNSUPPORTED)

    /** The cache retention of the markers ending `[S]`, `[R]` and `[K]`; `null` uses the call's retention. */
    val prefixRetention: CacheRetention? get() = settings.prefixRetention

    /** The retention classes a request's markers can write: what an undifferentiated cache-write count is attributed to. */
    val writeRetentions: Set<CacheRetention>
        get() = setOfNotNull(settings.options.cacheRetention().orElse(CacheRetention.SHORT), settings.prefixRetention)
            .filter { it != CacheRetention.NONE }.toSet()

    private val reasoningControl: Boolean = run {
        // Mirrors the SDK resolver: without a reasoning control the level would be dropped, an adaptation under strict.
        val support = model.capabilities().support(Capability.REASONING)
        !(support == SupportLevel.UNSUPPORTED || model.reasoningLevels().isEmpty() && support != SupportLevel.SUPPORTED && model.source() != Model.Source.UNLISTED)
    }

    /** The SDK level for [effort], already the nearest supported one, so no call is adapted; `null` sends none. */
    fun reasoning(effort: Effort): ReasoningLevel? {
        if (!settings.mapEffort || !reasoningControl) return null
        val level = when (effort) {
            Effort.Minimal -> ReasoningLevel.MINIMAL
            Effort.Low -> ReasoningLevel.LOW
            Effort.Medium -> ReasoningLevel.MEDIUM
            Effort.High -> ReasoningLevel.HIGH
        }
        return level.nearest(model.reasoningLevels())
    }

    /**
     * The options of one call: the template, then what ASTROLABE owns per request — output cap, effort, history
     * policy — and the transport rules the adapter never lets a template change: no response cache (a replay would
     * be charged again), strict unless the template says otherwise, and billing-relevant adaptations always fatal.
     */
    fun options(request: Request, id: InvocationId?, listener: LlmListener?): ChatOptions {
        val b = settings.options.toBuilder()
        if (outputCapEnforced) b.maxTokens(request.maxOutputTokens)
        // With `effort = off` the template's own reasoning level (if any) stands.
        if (settings.mapEffort) b.reasoning(reasoning(request.effort))
        b.responseCache(CacheMode.BYPASS)
        b.historyPolicy(if (settings.dropForeignReasoning) HistoryPolicy.ALLOW_ADAPTATION else HistoryPolicy.REJECT_LOSSY)
        b.reasoningHandoff(if (settings.dropForeignReasoning) ReasoningHandoff.DROP else ReasoningHandoff.KEEP)
        if (settings.options.strictSetting().isEmpty) b.strict(true)
        b.strictCodes(settings.options.strictCodes() + FATAL_CODES)
        b.tag("astrolabe.profile", profile.id)
        id?.let { b.tag("astrolabe.invocation", it.value) }
        listener?.let(b::listener)
        return b.build()
    }

    /**
     * Each effort prepared (no network) with the profile's full output limit: a thinking budget the codec would have to
     * raise `max_tokens` for fails here, once, instead of on every call of a cell that routes to this effort.
     */
    private fun probeEfforts(llm: Llm): List<String> {
        if (!outputCapEnforced) return emptyList()
        return Effort.entries.filter { reasoning(it) != null }.mapNotNull { effort ->
            val probe = Request(
                listOf(Segment(SegmentKind.A, listOf(Message.text(Role.User, "probe")))), emptyList(), profile, effort, profile.capabilities.outputLimitTokens,
            )
            try {
                llm.prepare(model, Conversation.of("probe"), options(probe, null, null), true)
                null
            } catch (e: LlmException) {
                "effort $effort does not fit outputLimitTokens ${profile.capabilities.outputLimitTokens} (${e.message}); raise the limit or set gate.effort = \"off\""
            }
        }
    }

    companion object {
        /** A raised or clamped output limit breaks the reservation the call was admitted with (S-08). */
        private val FATAL_CODES = setOf("option_adapted", "max_tokens_clamped")

        /** SDK usage-field names that report each billing dimension (`ApiFeatures.reportedUsageFields`). */
        private val REPORTED_AS: Map<BillingDimension, Set<String>> = mapOf(
            BillingDimension.UNCACHED_INPUT to setOf("input"),
            BillingDimension.CACHE_READ to setOf("cache_read"),
            BillingDimension.CACHE_WRITE_5M to setOf("cache_write_5m", "cache_write"),
            BillingDimension.CACHE_WRITE_1H to setOf("cache_write_1h", "cache_write"),
            BillingDimension.OUTPUT to setOf("output"),
        )

        /** Compiles [profile]; every contradiction is collected into [problems] and the binding is `null` if there is any. */
        fun compile(llm: Llm, profile: Profile, problems: MutableList<String>): ProfileBinding? {
            val start = problems.size
            fun problem(text: String) { problems += "profile ${profile.id}: $text" }
            val parseProblems = ArrayList<String>()
            val settings = GateSettings.parse(profile.config, parseProblems)
            parseProblems.forEach(::problem)
            val model = try {
                llm.model(profile.provider, profile.model)
            } catch (e: IllegalArgumentException) {
                problem("provider '${profile.provider}' is not configured in the SDK runtime: ${e.message}")
                return null
            }
            val features = llm.features(model)
            val warnings = ArrayList<String>()
            fun catalog(text: String) = when (settings.catalogCheck) {
                "fail" -> problem(text)
                "warn" -> warnings += "profile ${profile.id}: $text"
                else -> Unit
            }
            val caps = profile.capabilities
            if (model.source() == Model.Source.UNLISTED) catalog("model '${profile.model}' is not in the catalog; list it in the provider's models")
            settings.api?.let { if (it != features.api()) problem("gate.api '$it' differs from the model's wire API '${features.api()}'") }
            model.contextWindow().ifPresent { if (caps.contextLimitTokens > it) catalog("contextLimitTokens ${caps.contextLimitTokens} > catalog context window $it") }
            model.maxOutputTokens().ifPresent { if (caps.outputLimitTokens > it) catalog("outputLimitTokens ${caps.outputLimitTokens} > catalog max output $it") }
            // Capabilities a profile declares must be facts of the API (§15.1: probed, never inferred).
            if (caps.caching.breakpoints && features.promptCache() != ApiFeatures.PromptCache.EXPLICIT_MARKERS) {
                problem("caching.breakpoints is true but ${features.api()} caches ${features.promptCache().name.lowercase(Locale.ROOT)}; declare breakpoints = false")
            }
            caps.caching.maxBreakpoints?.let { if (caps.caching.breakpoints && it > features.maxCacheMarkers()) problem("caching.maxBreakpoints $it > ${features.maxCacheMarkers()} markers the API honours") }
            if (caps.continuation) problem("continuation is not supported by this adapter yet; declare continuation = false")
            if (caps.nativeCompaction) problem("nativeCompaction is not supported by this adapter yet; declare nativeCompaction = false")
            if (caps.hostedExecution) problem("hostedExecution is not supported by this adapter yet; declare hostedExecution = false")
            if (features.outputCap() == ApiFeatures.OutputCap.UNSUPPORTED && settings.outputCap != "unsupported") {
                problem("${features.api()} does not enforce an output cap; declare gate.outputCap = \"unsupported\" (the limit then only bounds reservations)")
            }
            if (settings.outputCap == "unsupported" && settings.options.maxTokens().isPresent) problem("gate.options.maxTokens contradicts gate.outputCap = \"unsupported\"")
            settings.options.responseCache().ifPresent { if (it != CacheMode.BYPASS) problem("gate.options.responseCache must be bypass: a replayed reply would be charged again") }
            if (settings.options.continuation().isPresent) problem("gate.options.continueFrom is not supported")
            val reported = features.reportedUsageFields()
            if (reported.isNotEmpty()) {
                caps.usageFields.filter { dim -> REPORTED_AS[dim]?.none { it in reported } ?: false }.forEach {
                    problem("usageFields declares $it but ${features.api()} reports only $reported")
                }
            }
            if (problems.size > start) return null
            val binding = ProfileBinding(profile, model, features, settings, warnings)
            binding.probeEfforts(llm).forEach(::problem)
            return if (problems.size > start) null else binding
        }
    }
}
