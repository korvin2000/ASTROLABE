package io.astrolabe.provider.aigate

import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ai.gate.Provider
import net.ai.gate.spi.http.HttpCall
import net.ai.gate.vendors.openai.OpenAi
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Telemetry never changes what is sent: two calls per wire API — the second replays the first reply (reasoning, a
 * tool call and its result) — are compared byte for byte with goldens recorded from `main` before the call facts
 * existed. The scripted replies carry a gateway's charge and route, so decoding them is exercised too. On a mismatch
 * the actual bytes are written to `build/request-bytes/` for review.
 */
class RequestBytesTest {
    @Test
    fun `chat completions requests keep their bytes`() {
        val wire = WireScript().sse(
            """{"id":"gen-1","provider":"Anthropic","model":"anthropic/claude-4.5-sonnet-20250929","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"Plan."}}]}""",
            """{"id":"gen-1","provider":"Anthropic","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"look","arguments":"{\"what\":\"tree\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"id":"gen-1","provider":"Anthropic","choices":[],"usage":{"prompt_tokens":40,"completion_tokens":9,"total_tokens":49,"cost":0.0002,""" +
                """"cost_details":{"upstream_inference_cost":0.00019},"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":3}}}""",
            "[DONE]",
        ).sse(
            """{"id":"gen-2","provider":"Anthropic","choices":[{"index":0,"delta":{"content":"Done."},"finish_reason":"stop"}]}""",
            """{"id":"gen-2","provider":"Anthropic","choices":[],"usage":{"prompt_tokens":60,"completion_tokens":2,"cost":0.0001}}""",
            "[DONE]",
        )
        val openRouter = GateTestKit.openRouter(contextWindow = 200_000)
        assertGolden("openrouter-chat-completions", exchange(wire, openRouter, "OPENROUTER_API_KEY", "openrouter", GateTestKit.SONNET_ON_OPENROUTER))
    }

    @Test
    fun `responses requests keep their bytes`() {
        val output = """[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"},""" +
            """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}]"""
        val wire = WireScript().sse(
            """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5.1-2025-11-13"}}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"}}""",
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}}""",
            """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.1-2025-11-13","status":"completed","output":$output,""" +
                """"usage":{"input_tokens":1200,"input_tokens_details":{"cached_tokens":1000},"output_tokens":50,"output_tokens_details":{"reasoning_tokens":20}}}}""",
        ).sse(
            """{"type":"response.created","response":{"id":"resp_2","model":"gpt-5.1-2025-11-13"}}""",
            """{"type":"response.completed","response":{"id":"resp_2","model":"gpt-5.1-2025-11-13","status":"completed","output":[],"usage":{"input_tokens":1300,"output_tokens":5}}}""",
        )
        assertGolden("openai-responses", exchange(wire, OpenAi.provider(), "OPENAI_API_KEY", "openai", "gpt-5.1"))
    }

    /** Both calls as sent: method, URI, non-secret headers in order, body bytes. */
    private fun exchange(wire: WireScript, provider: Provider, keyVariable: String, providerId: String, model: String): String {
        wire.runtime(provider, keyVariable).use { llm ->
            val profile = AiGateProfiles.draft(llm, providerId, model, "main", LocalDate.of(2026, 9, 1))
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val first = adapter.start(GateTestKit.request(profile, maxOutput = 2_000), InvocationId("inv-1")).await()
                        val call = first.toolCalls.single()
                        val transcript = first.items + ToolResult.text(call.id, "a.py b.py")
                        adapter.start(GateTestKit.request(profile, transcript, maxOutput = 2_000), InvocationId("inv-2")).await()
                    }
                }
            }
        }
        assertEquals(2, wire.calls.size)
        return wire.calls.joinToString("\n") { render(it) }
    }

    private fun render(call: HttpCall): String = buildString {
        append(call.method()).append(' ').append(call.uri()).append('\n')
        call.headers().entries.filter { !secret(it.key) }.sortedBy { it.key.lowercase(Locale.ROOT) }
            .forEach { append(it.key).append(": ").append(it.value).append('\n') }
        append(String(call.bytes(), StandardCharsets.UTF_8)).append('\n')
    }

    private fun secret(header: String): Boolean = header.lowercase(Locale.ROOT).let { "authorization" in it || "api-key" in it }

    private fun assertGolden(name: String, actual: String) {
        val expected = javaClass.getResourceAsStream("/request-bytes/$name.txt")?.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        if (expected == actual) return
        val out = Path.of("build", "request-bytes", "$name.txt").toAbsolutePath()
        Files.createDirectories(out.parent)
        Files.writeString(out, actual)
        assertEquals(expected, actual, "the request bytes of $name changed; the actual bytes are in $out")
    }
}
