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
import net.ai.gate.vendors.openai.OpenAi
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Recorded OpenAI Responses frames through the SDK's real codec: automatic caching, cached-token normalization, encrypted reasoning. */
class ResponsesProtocolTest {
    private val output = """[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"},
        {"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}]""".replace("\n", "")

    private val frames = arrayOf(
        """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5.1-2025-11-13"}}""",
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"}}""",
        """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"what\":\"tree\"}"}""",
        """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}}""",
        """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.1-2025-11-13","status":"completed","output":$output,"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":1000},"output_tokens":50,"output_tokens_details":{"reasoning_tokens":20}}}}""",
    )

    private val done = arrayOf(
        """{"type":"response.created","response":{"id":"resp_2","model":"gpt-5.1-2025-11-13"}}""",
        """{"type":"response.completed","response":{"id":"resp_2","model":"gpt-5.1-2025-11-13","status":"completed","output":[],"usage":{"input_tokens":1300,"output_tokens":5}}}""",
    )

    @Test
    fun `a Responses call normalizes cached input once and replays its encrypted reasoning with the call`() {
        val wire = WireScript().sse(*frames).sse(*done)
        wire.runtime(OpenAi.provider(), "OPENAI_API_KEY").use { llm ->
            val profile = AiGateProfiles.draft(llm, "openai", "gpt-5.1", "gpt", LocalDate.of(2026, 9, 1))
            assertEquals(false, profile.capabilities.caching.breakpoints, "automatic prefix caching: no markers")
            assertEquals(setOf(BillingDimension.UNCACHED_INPUT, BillingDimension.CACHE_READ, BillingDimension.OUTPUT), profile.capabilities.usageFields)
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val first = GateTestKit.request(profile, maxOutput = 2_000)
                        assertTrue(first.segments.none { it.breakpoint })
                        val estimate = adapter.estimators(HeuristicEstimator()).estimatorFor(profile).estimate(first)
                        assertEquals(Validation.Ok, adapter.validate(first, estimate))
                        val invocation = adapter.start(first, InvocationId("inv-1"))
                        val response = invocation.await()
                        assertEquals(StopReason.ToolUse, response.stop)
                        val reasoning = assertIs<ReasoningRef>(response.items[0])
                        assertEquals("openai/gpt-5.1@openai-responses", reasoning.providerTag)
                        assertEquals(ToolCall("call_1", "look", """{"what":"tree"}"""), response.items[1])
                        val usage = invocation.terminal().usage!!
                        assertEquals(
                            mapOf(BillingDimension.UNCACHED_INPUT to 200L, BillingDimension.CACHE_READ to 1000L, BillingDimension.OUTPUT to 50L),
                            usage.quantities, "AX-10: uncached = input − cached, cached once",
                        )
                        assertEquals("openai-responses", usage.provenance.protocol)

                        val second = GateTestKit.request(profile, response.items + ToolResult.text("call_1", "a.py"), maxOutput = 2_000)
                        val next = adapter.start(second, InvocationId("inv-2"))
                        next.await()
                        val missing = next.terminal().usage!!
                        assertEquals(1300L, missing.quantities[BillingDimension.UNCACHED_INPUT], "no cached_tokens reported: all input uncached")
                        assertTrue(BillingDimension.CACHE_READ in missing.unknown, "an expected dimension the reply omitted is unknown, never zero")
                    }
                }
            }
        }
        val body = wire.body(0)
        assertEquals(2_000L, body.optLong("max_output_tokens").asLong)
        assertEquals("medium", body.`object`("reasoning").string("effort"))
        val input = wire.body(1).objects("input")
        val types = input.map { it.optString("type").orElse(it.optString("role").orElse("")) }
        val reasoning = input[types.indexOf("reasoning")]
        assertEquals("enc-1", reasoning.string("encrypted_content"), "same-origin encrypted reasoning is replayed")
        assertTrue(types.indexOf("reasoning") < types.indexOf("function_call") && types.indexOf("function_call") < types.indexOf("function_call_output"), "$types")
    }
}
