package io.astrolabe.provider.aigate

import io.astrolabe.id.WorkId
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.ai.gate.Llm
import net.ai.gate.auth.Credential
import net.ai.gate.auth.CredentialStore
import net.ai.gate.auth.Environment
import net.ai.gate.auth.Secret
import net.ai.gate.auth.oauth.OAuthCredential
import net.ai.gate.json.JsonObject as GateObject
import net.ai.gate.spi.http.HttpCall
import net.ai.gate.vendors.openai.OpenAi
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * WD-31: the `openai-codex` prompt-cache routing of one work is a function of the request alone. A reopened work runs in a
 * fresh runtime and adapter; the cache key, the session headers and every byte of the cached prefix (instructions, tools,
 * the leading input) are the same as at the first open, and a follow-up call extends the previous input without
 * rewriting it. Only the bearer token, never part of the prefix, is left out of the comparison.
 */
class CodexCacheKeyTest {
    private val work = WorkId("W-codexcachekey000000")

    private val call = """[{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"},""" +
        """{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}]"""

    private fun script(): WireScript = WireScript().sse(
        """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5.5"}}""",
        """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Plan."}],"encrypted_content":"enc-1"}}""",
        """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"look","arguments":"{\"what\":\"tree\"}"}}""",
        """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.5","status":"completed","output":$call,""" +
            """"usage":{"input_tokens":5400,"input_tokens_details":{"cached_tokens":2560},"output_tokens":50}}}""",
    ).sse(
        """{"type":"response.created","response":{"id":"resp_2","model":"gpt-5.5"}}""",
        """{"type":"response.completed","response":{"id":"resp_2","model":"gpt-5.5","status":"completed","output":[],"usage":{"input_tokens":5900,"output_tokens":5}}}""",
    )

    /** One open of the work: its own credential store, runtime and adapter, two calls of one cell. */
    private fun open(): List<HttpCall> {
        val wire = script()
        val store = CredentialStore.inMemory()
        val token = OAuthCredential.builder(Secret.of("access-token-0123456789"), "https://auth.openai.com", "app_EMoamEEZ73f0CkXaXp7hrann")
            .account("acct-7").expiresAt(Instant.parse("2099-01-01T00:00:00Z")).build()
        store.update("openai-codex") { Optional.of<Credential>(token) }
        val llm = Llm.builder().provider(OpenAi.codex().toBuilder().transport(wire).build()).credentials(store)
            .environment(Environment.none()).catalog { it.offline() }.build()
        llm.use {
            val profile = AiGateProfiles.draft(llm, "openai-codex", "gpt-5.5", "auto.openai-codex.gpt-5.5", LocalDate.of(2026, 9, 1))
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val first = adapter.start(GateTestKit.request(profile, maxOutput = 2_000, sessionKey = work.sessionKey), InvocationId("inv-1")).await()
                        val transcript = first.items + ToolResult.text(first.toolCalls.single().id, "a.py b.py")
                        adapter.start(GateTestKit.request(profile, transcript, maxOutput = 2_000, sessionKey = work.sessionKey), InvocationId("inv-2")).await()
                    }
                }
            }
        }
        assertEquals(2, wire.calls.size)
        return wire.calls.toList()
    }

    private fun render(call: HttpCall): String = buildString {
        append(call.method()).append(' ').append(call.uri()).append('\n')
        call.headers().entries.filter { it.key.lowercase(Locale.ROOT) != "authorization" }.sortedBy { it.key.lowercase(Locale.ROOT) }
            .forEach { append(it.key).append(": ").append(it.value).append('\n') }
        append(String(call.bytes(), StandardCharsets.UTF_8))
    }

    private fun body(call: HttpCall): GateObject = call.body().orElseThrow() as GateObject

    @Test
    fun `a reopened work sends the cache key, session headers and prefix bytes of its first open`() {
        val firstOpen = open()
        val reopen = open()
        for (i in 0..1) assertEquals(render(firstOpen[i]), render(reopen[i]), "call ${i + 1}: a fresh runtime and adapter change no sent byte")

        for (call in firstOpen) {
            assertEquals(work.sessionKey, body(call).string("prompt_cache_key"), "the key is the work's, not the process's or the cell's")
            assertEquals(work.sessionKey, call.headers()["session-id"])
            assertEquals(work.sessionKey, call.headers()["x-client-request-id"])
        }
        assertEquals(work.sessionKey, WorkId(work.value).sessionKey, "derived from the work id alone")

        val (one, two) = firstOpen.map(::body)
        for (member in listOf("instructions", "tools", "model", "store", "include", "reasoning")) {
            assertEquals(one.get(member), two.get(member), "$member is part of the cached prefix and stays put within a cell")
        }
        val before = one.objects("input")
        val after = two.objects("input")
        // [R] [K] lead; [A] is last and moves, so the call before it is the shared prefix.
        assertEquals(before.dropLast(1), after.take(before.size - 1), "a follow-up call extends the input, never rewrites it")
        assertNotEquals(before.last(), after[before.size - 1], "the new turn sits where the previous anchor was")
    }
}
