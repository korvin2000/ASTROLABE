package io.astrolabe.provider.aigate

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.Provider
import net.ai.gate.auth.Environment
import net.ai.gate.model.Capability
import net.ai.gate.model.Model
import net.ai.gate.model.Prices
import net.ai.gate.vendors.google.Gemini
import net.ai.gate.vendors.openai.OpenAi
import net.ai.gate.vendors.openai.OpenAiCompatible
import java.net.URI
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Doc phase 8 offline: an OpenAI-compatible gateway qualified by probes, Gemini thought signatures, the Codex output-cap policy. */
class QualificationTest {
    private val date = LocalDate.of(2026, 9, 1)

    private fun runtime(provider: Provider, route: RouteScript, key: String?): Llm = Llm.builder().provider(provider.toBuilder().transport(route).build())
        .environment(key?.let { Environment.of(mapOf(it to "test-key-0123456789")) } ?: Environment.none()).catalog { it.offline() }.build()

    private fun gateway(): Provider = OpenAiCompatible.custom("corp-gw", URI.create("https://gw.example/v1")).toBuilder()
        .model(
            Model.builder("corp-gw", "llama-70b").contextWindow(128_000).maxOutputTokens(8_000)
                .supports(Capability.TOOLS, Capability.STREAMING).prices(Prices.usd().input("0.5").cacheRead("0.1").output("1.5").build()).build(),
        ).build()

    private fun completion(content: String?, calls: String? = null, usage: String = """{"prompt_tokens":20,"completion_tokens":3}"""): String {
        val message = if (calls != null) """{"role":"assistant","content":null,"tool_calls":$calls}""" else """{"role":"assistant","content":"$content"}"""
        return """{"id":"c1","object":"chat.completion","model":"llama-70b","choices":[{"index":0,"message":$message,"finish_reason":"${if (calls != null) "tool_calls" else "stop"}"}],"usage":$usage}"""
    }

    @Test
    fun `a gateway that strips cache counters is qualified without cache_read and then runs cells`() {
        val route = RouteScript()
            .json("GET", "/v1", "{}")
            .json("GET", "/v1/models", """{"object":"list","data":[{"id":"llama-70b","object":"model"}]}""")
            .json("POST", "/chat/completions", completion(null, """[{"id":"call_1","type":"function","function":{"name":"echo","arguments":"{\"text\":\"ok\"}"}}]"""))
            .json("POST", "/chat/completions", completion("ok"))
            .json("POST", "/chat/completions", completion("OK", usage = """{"prompt_tokens":12,"completion_tokens":1}"""))
            .sse(
                "POST", "/chat/completions",
                """{"id":"c2","object":"chat.completion.chunk","model":"llama-70b","choices":[{"index":0,"delta":{"role":"assistant","content":"Done."},"finish_reason":null}]}""",
                """{"id":"c2","object":"chat.completion.chunk","model":"llama-70b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"id":"c2","object":"chat.completion.chunk","model":"llama-70b","choices":[],"usage":{"prompt_tokens":30,"completion_tokens":2}}""",
                "[DONE]",
            )
        runtime(gateway(), route, "CORP_GW_API_KEY").use { llm ->
            val draft = AiGateProfiles.draft(llm, "corp-gw", "llama-70b", "gw", date)
            assertTrue(BillingDimension.CACHE_READ in draft.capabilities.usageFields, "the API family could report cache reads")
            val qualification = AiGateProfiles.qualify(llm, draft)
            assertTrue(qualification.qualified, qualification.problems.toString())
            assertEquals(setOf(BillingDimension.UNCACHED_INPUT, BillingDimension.OUTPUT), qualification.profile.capabilities.usageFields)
            assertTrue(qualification.notes.single().contains("cache_read"), qualification.notes.toString())

            val profile = qualification.profile
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val request = GateTestKit.request(profile, maxOutput = 1_000)
                        assertEquals(Validation.Ok, adapter.validate(request, adapter.estimators(HeuristicEstimator()).estimatorFor(profile).estimate(request)))
                        val invocation = adapter.start(request, InvocationId("inv-1"))
                        assertEquals("Done.", invocation.await().text)
                        val usage = invocation.terminal().usage!!
                        assertEquals(mapOf(BillingDimension.UNCACHED_INPUT to 30L, BillingDimension.OUTPUT to 2L), usage.quantities)
                        assertTrue(usage.isComplete, "nothing the qualified profile expects is missing")
                    }
                }
            }
        }
        assertEquals(1_000L, route.posts().last().optLong("max_tokens").asLong, "the gateway's own max-tokens field")
    }

    @Test
    fun `a gateway without a tool round trip is not qualified`() {
        val route = RouteScript()
            .json("GET", "/v1", "{}")
            .json("GET", "/v1/models", """{"object":"list","data":[{"id":"llama-70b","object":"model"}]}""")
            .json("POST", "/chat/completions", completion("I cannot call tools."))
        runtime(gateway(), route, "CORP_GW_API_KEY").use { llm ->
            val qualification = AiGateProfiles.qualify(llm, AiGateProfiles.draft(llm, "corp-gw", "llama-70b", "gw", date))
            assertFalse(qualification.qualified)
            assertTrue(qualification.problems.single().startsWith("TOOLS"), qualification.problems.toString())
        }
    }

    @Test
    fun `Gemini thought signatures replay on the call they signed`() {
        val usage = """"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":5,"thoughtsTokenCount":7,"cachedContentTokenCount":40}"""
        val route = RouteScript()
            .sse(
                "POST", ":streamGenerateContent",
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"look","args":{"what":"tree"}},"thoughtSignature":"gsig-1"}]},"index":0}],$usage,"modelVersion":"gemini-2.5-flash","responseId":"r1"}""",
                """{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"STOP","index":0}],$usage,"modelVersion":"gemini-2.5-flash","responseId":"r1"}""",
            )
            .sse(
                "POST", ":streamGenerateContent",
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"a.py"}]},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":2},"modelVersion":"gemini-2.5-flash","responseId":"r2"}""",
            )
        runtime(Gemini.provider(), route, "GEMINI_API_KEY").use { llm ->
            val profile = AiGateProfiles.draft(llm, "google", "gemini-2.5-flash", "flash", date)
            assertFalse(profile.capabilities.caching.breakpoints, "implicit caching: no markers")
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val invocation = adapter.start(GateTestKit.request(profile, maxOutput = 2_000), InvocationId("inv-1"))
                        val response = invocation.await()
                        assertEquals(StopReason.ToolUse, response.stop)
                        val signed = assertIs<ReasoningRef>(response.items[0])
                        assertEquals("google/gemini-2.5-flash@google-generate-content", signed.providerTag)
                        assertEquals(JsonPrimitive("gsig-1"), (signed.opaque as JsonObject)["signature"])
                        val call = assertIs<ToolCall>(response.items[1])
                        assertEquals("""{"what":"tree"}""", call.argsJson)
                        assertEquals(
                            mapOf(BillingDimension.UNCACHED_INPUT to 60L, BillingDimension.CACHE_READ to 40L, BillingDimension.OUTPUT to 12L),
                            invocation.terminal().usage!!.quantities, "input − cached, thoughts inside output",
                        )
                        adapter.start(GateTestKit.request(profile, response.items + ToolResult.text(call.id, "a.py"), maxOutput = 2_000), InvocationId("inv-2")).await()
                    }
                }
            }
        }
        val contents = route.posts()[1].objects("contents")
        val modelTurn = contents.first { it.optString("role").orElse("") == "model" }.objects("parts").single()
        assertEquals("gsig-1", modelTurn.string("thoughtSignature"), "the signature travels with its function call")
        assertTrue(modelTurn.get("functionCall").isPresent)
        assertTrue(contents.any { c -> c.objects("parts").any { it.`object`("functionResponse").optString("name").orElse("") == "look" } })
    }

    @Test
    fun `a Codex profile reserves its output bound but never sends it`() {
        val route = RouteScript()
        runtime(OpenAi.codex(), route, null).use { llm ->
            val profile = AiGateProfiles.draft(llm, "openai-codex", "gpt-5.5", "codex", date)
            assertEquals(JsonPrimitive("unsupported"), (profile.config["gate"] as JsonObject)["outputCap"])
            assertTrue(profile.priceTable.perMillion.isEmpty(), "a subscription plan has no token prices: charges stay unknown")
            val undeclared = profile.copy(id = "codex-capped", config = GateTestKit.gate("""{"v":1}"""))
            assertTrue(AiGateAdapter.violations(llm, listOf(undeclared)).single().contains("outputCap"), "the missing policy is named")
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                val request = GateTestKit.request(profile, maxOutput = 4_000)
                val estimate = adapter.estimators(HeuristicEstimator()).estimatorFor(profile).estimate(request)
                assertEquals(Validation.Ok, adapter.validate(request, estimate))
                val body = (adapter.prepared(request, adapter.binding(profile)) as Prepared.Ready).call.request().body() as net.ai.gate.json.JsonObject
                assertFalse(body.get("max_output_tokens").isPresent)
                assertTrue(body.bool("stream"), "streaming-only dialect")
            }
        }
        assertTrue(route.calls.isEmpty(), "binding and validation send nothing")
    }
}
