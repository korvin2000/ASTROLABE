package io.astrolabe.evallive

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.aigate.AiGateAdapter
import io.astrolabe.provider.aigate.AiGateProfiles
import net.ai.gate.Llm
import net.ai.gate.auth.AuthStatus
import net.ai.gate.auth.CredentialStore
import net.ai.gate.auth.Environment
import net.ai.gate.vendors.openai.OpenAiCompatible
import java.nio.file.Path
import java.time.LocalDate

/** Binds a model id to a provider adapter and profile, once per run. */
internal fun interface ModelSource {
    fun bind(modelId: String): ModelBinding
}

/** No usable credential for [provider]; the message names where the SDK looks for one, never a value. */
internal class MissingKey(provider: String, variable: String?) : IllegalStateException(
    "no API key for $provider: set ${variable ?: "the provider's API key variable"} in the environment, " +
        "or pass --credentials <SDK credential store file>",
)

/**
 * Models of a live provider through AI Gate, profiled as the Studio's `AutoProfiles.make` does: drafted from the
 * catalog, OpenRouter upstream ignores applied, validated without a billable call. Keys are resolved by the SDK
 * (environment, or the credential store file given); this class never reads them.
 */
internal class LiveModels(private val llm: Llm, private val providerId: String, private val priceDate: LocalDate) : ModelSource {
    fun requireKey() {
        val state = llm.auth().status(providerId).state()
        if (state == AuthStatus.State.NOT_CONFIGURED || state == AuthStatus.State.EXPIRED || state == AuthStatus.State.REFRESH_FAILED) {
            throw MissingKey(providerId, KEY_VARIABLES[providerId])
        }
    }

    override fun bind(modelId: String): ModelBinding {
        // The catalog snapshot may predate the model: one refresh from the provider's feeds and listing.
        if (llm.models().all(providerId).none { it.id() == modelId }) llm.models().refresh(providerId)
        val id = StudioPolicy.profileId(providerId, modelId)
        val drafted = AiGateProfiles.draft(llm, providerId, modelId, id, priceDate)
        val profile = drafted.copy(config = StudioPolicy.routed(providerId, modelId, drafted.config))
        val violations = AiGateAdapter.violations(llm, listOf(profile))
        require(violations.isEmpty()) { "profile $id is not usable: ${violations.joinToString("; ")}" }
        val adapter = AiGateAdapter(llm, listOf(profile), false)
        return ModelBinding(adapter, profile, adapter.estimators(HeuristicEstimator())) { adapter.close() }
    }

    companion object {
        /** Where the SDK's presets look for a key, for the error message only. */
        val KEY_VARIABLES: Map<String, String> = mapOf(
            "openrouter" to "OPENROUTER_API_KEY", "anthropic" to "ANTHROPIC_API_KEY", "openai" to "OPENAI_API_KEY", "google" to "GEMINI_API_KEY",
        )

        /** The SDK runtime: discovered presets plus OpenRouter, the system environment, a persisted catalog snapshot. */
        fun runtime(catalog: Path, credentials: Path?): Llm {
            val builder = Llm.builder().discoverProviders().provider(OpenAiCompatible.openRouter()).environment(Environment.system())
                .catalog { it.snapshotFile(catalog).manualRefresh() }
            if (credentials != null) builder.credentials(CredentialStore.file(credentials))
            return builder.build()
        }
    }
}
