package io.astrolabe.provider.aigate

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationProgress
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.Validation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import net.ai.gate.Llm
import net.ai.gate.Provider
import net.ai.gate.auth.Environment
import net.ai.gate.vendors.anthropic.Anthropic
import net.ai.gate.vendors.google.Gemini
import net.ai.gate.vendors.openai.OpenAi
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Doc phase 7: authorised, capped live smoke through the real adapter — **network and billable**. Runs only through
 * `./gradlew :provider-ai-gate:liveTest` (system property `astrolabe.live=true`), each provider only when its key is in
 * the environment: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`. Models default to small ones and are
 * overridden with `ASTROLABE_LIVE_ANTHROPIC_MODEL`, `ASTROLABE_LIVE_OPENAI_MODEL`, `ASTROLABE_LIVE_GEMINI_MODEL`.
 * Per provider: the SDK's non-billable staged check, a profile drafted from the catalog, one capped completion and one
 * cancelled call whose terminal must still settle with usage. Evidence is printed; gates stay `UNMEASURED` (I-19).
 */
@EnabledIfSystemProperty(named = "astrolabe.live", matches = "true")
class LiveSmokeTest {
    private fun smoke(provider: Provider, providerId: String, modelVariable: String, defaultModel: String, gate: String) {
        val modelId = System.getenv(modelVariable)?.takeIf { it.isNotBlank() } ?: defaultModel
        Llm.builder().provider(provider).environment(Environment.system()).catalog { it.offline() }.build().use { llm ->
            val model = llm.model(providerId, modelId)
            val report = llm.test(model)
            println("[$providerId/$modelId] staged check: $report")
            assertTrue(report.ok(), report.toString())
            val draft = AiGateProfiles.draft(llm, providerId, modelId, "live", LocalDate.now())
            val merged = (draft.config["gate"] as JsonObject) + (GateTestKit.gate(gate)["gate"] as JsonObject)
            val profile = draft.copy(config = JsonObject(mapOf("gate" to JsonObject(merged))))
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    val ok = request(profile, "Reply with the single word OK.", 256)
                    val estimate = adapter.estimators(HeuristicEstimator()).estimatorFor(profile).estimate(ok)
                    assertEquals(Validation.Ok, adapter.validate(ok, estimate))
                    val invocation = adapter.start(ok, InvocationId("live-1"))
                    val response = withTimeout(120_000) { invocation.await() }
                    val usage = withTimeout(120_000) { invocation.terminal() }.usage!!
                    println("[$providerId/$modelId] ${response.stop}: '${response.text.take(80)}' usage=${usage.quantities} unknown=${usage.unknown} estimate=${estimate.tokens}±${estimate.marginTokens}")
                    assertTrue(response.stop == StopReason.EndTurn || response.stop == StopReason.OutputLimit, "stop ${response.stop}")
                    assertTrue(BillingDimension.UNCACHED_INPUT in usage.quantities && BillingDimension.OUTPUT in usage.quantities, usage.toString())

                    val first = CompletableDeferred<Unit>()
                    adapter.addListener { if (it is InvocationProgress.Output && it.id == InvocationId("live-2")) first.complete(Unit) }.use {
                        val long = adapter.start(request(profile, "Count from 1 to 400, one number per line.", 1_024), InvocationId("live-2"))
                        withTimeoutOrNull(60_000) { first.await() }
                        long.cancel()
                        val terminal = withTimeout(120_000) { long.terminal() }
                        println("[$providerId/$modelId] cancelled=${terminal.cancelled} stop=${terminal.response?.stop} usage=${terminal.usage?.quantities} unknown=${terminal.usage?.unknown} late=${terminal.lateItems.size}")
                        assertTrue(terminal.usage != null, "a cancelled call is still accounted")
                    }
                }
            }
        }
    }

    private fun request(profile: Profile, ask: String, maxOutput: Int) = Request(
        listOf(
            Segment(SegmentKind.S, listOf(Message.text(Role.System, "You are a terse assistant.")), profile.capabilities.caching.breakpoints),
            Segment(SegmentKind.A, listOf(Message.text(Role.User, ask))),
        ),
        emptyList(), profile, Effort.Minimal, maxOutput,
    )

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    fun anthropic() = smoke(Anthropic.provider(), "anthropic", "ASTROLABE_LIVE_ANTHROPIC_MODEL", "claude-haiku-4-5", """{"effort":"off","options":{"retry":{"maxAttempts":1}}}""")

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun openai() = smoke(OpenAi.provider(), "openai", "ASTROLABE_LIVE_OPENAI_MODEL", "gpt-5.1", """{"options":{"retry":{"maxAttempts":1}}}""")

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun gemini() = smoke(Gemini.provider(), "google", "ASTROLABE_LIVE_GEMINI_MODEL", "gemini-2.5-flash-lite", """{"options":{"retry":{"maxAttempts":1}}}""")
}
