package io.astrolabe.provider.aigate

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.Message
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.vendors.anthropic.Anthropic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Recorded Anthropic Messages frames through the SDK's real codec and core: no network (§15.4 protocol fixtures). */
class AnthropicProtocolTest {
    private fun frames(stop: String, vararg blocks: String, cut: Boolean = false): Array<String> = buildList {
        add("""{"type":"message_start","message":{"id":"msg_1","model":"claude-sonnet-4-5-20250929","usage":{"input_tokens":10,"cache_read_input_tokens":100,"cache_creation_input_tokens":5,"cache_creation":{"ephemeral_5m_input_tokens":5,"ephemeral_1h_input_tokens":0},"output_tokens":1}}}""")
        addAll(blocks)
        if (!cut) {
            add("""{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":30}}""")
            add("""{"type":"message_stop"}""")
        }
    }.toTypedArray()

    private val thinking = arrayOf(
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need the tree."}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
        """{"type":"content_block_stop","index":0}""",
    )
    private val text = arrayOf(
        """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Checking."}}""",
        """{"type":"content_block_stop","index":1}""",
    )
    private val toolStart = arrayOf(
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"look","input":{}}}""",
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"what\":"}}""",
    )
    private val toolEnd = arrayOf(
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\"tree\"}"}}""",
        """{"type":"content_block_stop","index":2}""",
    )

    private fun <T> run(wire: WireScript, profile: io.astrolabe.provider.Profile = GateTestKit.sonnetProfile(), block: suspend (AiGateAdapter) -> T): T =
        wire.runtime(Anthropic.provider(), "ANTHROPIC_API_KEY").use { llm ->
            AiGateAdapter(llm, listOf(profile)).use { adapter -> runBlocking { withTimeout(20_000) { block(adapter) } } }
        }

    private fun validate(adapter: AiGateAdapter, request: Request) =
        adapter.validate(request, adapter.estimators(HeuristicEstimator()).estimatorFor(request.profile).estimate(request))

    @Test
    fun `thinking, text and a call stream into items with provenance, cache classes and signed replay`() {
        val wire = WireScript().sse(*frames("tool_use", *thinking, *text, *toolStart, *toolEnd)).sse(*frames("end_turn", *text))
        val profile = GateTestKit.sonnetProfile()
        run(wire) { adapter ->
            val first = GateTestKit.request(profile, maxOutput = 16_000)
            assertEquals(Validation.Ok, validate(adapter, first))
            val invocation = adapter.start(first, InvocationId("inv-1"))
            val response = invocation.await()
            assertEquals(StopReason.ToolUse, response.stop)
            val reasoning = assertIs<ReasoningRef>(response.items[0])
            assertEquals("anthropic/claude-sonnet-4-5@anthropic-messages", reasoning.providerTag, "the requested model, not the dated response model")
            assertEquals(JsonPrimitive("sig-1"), (reasoning.opaque as JsonObject)["signature"])
            assertEquals(null, reasoning.native)
            assertEquals("Checking.", (response.items[1] as Message).text)
            assertEquals(ToolCall("toolu_1", "look", """{"what":"tree"}"""), response.items[2])
            val usage = invocation.terminal().usage!!
            assertEquals(
                mapOf(
                    BillingDimension.UNCACHED_INPUT to 10L, BillingDimension.CACHE_READ to 100L, BillingDimension.CACHE_WRITE_5M to 5L,
                    BillingDimension.CACHE_WRITE_1H to 0L, BillingDimension.OUTPUT to 30L,
                ),
                usage.quantities, "AX-10: each class once, no double-counted write",
            )
            assertTrue(usage.isComplete)
            assertEquals("claude-sonnet-4-5-20250929", usage.provenance.model)

            val second = GateTestKit.request(profile, response.items + ToolResult.text("toolu_1", "a.py"), maxOutput = 16_000)
            assertEquals(Validation.Ok, validate(adapter, second))
            adapter.start(second, InvocationId("inv-2")).await()
        }
        val body = wire.body(0)
        assertEquals(16_000L, body.optLong("max_tokens").asLong)
        assertEquals("enabled", body.`object`("thinking").string("type"), "Effort.Medium is the SDK's medium reasoning")
        assertEquals("ephemeral", body.objects("system").last().`object`("cache_control").string("type"), "[S] ends in a marker")
        val replay = wire.body(1).objects("messages")
        val assistant = replay.first { it.string("role") == "assistant" }.objects("content")
        assertEquals(listOf("thinking", "text", "tool_use"), assistant.map { it.string("type") })
        assertEquals("sig-1", assistant[0].string("signature"), "same-origin thinking replays natively with its signature")
        val markers = replay.flatMap { m -> m.objects("content").filter { it.get("cache_control").isPresent } } + wire.body(1).objects("system").filter { it.get("cache_control").isPresent }
        assertEquals(4, markers.size, "S, R, K and T each end in one marker")
        val lastOfT = replay.dropLast(1).last().objects("content").last()
        assertEquals("tool_result", lastOfT.string("type"))
        assertTrue(lastOfT.get("cache_control").isPresent, "[T]'s marker closes its last message")
    }

    @Test
    fun `a long prefix retention marks S R K for an hour and leaves T on the default`() {
        val wire = WireScript().sse(*frames("end_turn", *text))
        val profile = GateTestKit.sonnetProfile(GateTestKit.gate("""{"prefixRetention":"long"}"""))
        run(wire, profile) { adapter -> adapter.start(GateTestKit.request(profile, listOf(Message.text(io.astrolabe.provider.Role.User, "pinned")), maxOutput = 16_000), InvocationId("inv-1")).await() }
        val body = wire.body(0)
        val controls = body.objects("system").mapNotNull { it.`object`("cache_control").takeIf { c -> !c.isEmpty } } +
            body.objects("messages").flatMap { m -> m.objects("content").mapNotNull { it.`object`("cache_control").takeIf { c -> !c.isEmpty } } }
        assertEquals(listOf("1h", "1h", "1h", null), controls.map { it.optString("ttl").orElse(null) })
    }

    @Test
    fun `AX-03 an output-limit stop exposes no call even when it looks complete`() {
        val wire = WireScript().sse(*frames("max_tokens", *text, *toolStart, *toolEnd))
        run(wire) { adapter ->
            val response = adapter.start(GateTestKit.request(GateTestKit.sonnetProfile(), maxOutput = 16_000), InvocationId("inv-1")).await()
            assertEquals(StopReason.OutputLimit, response.stop)
            assertTrue(response.items.none { it is ToolCall })
            assertEquals("Checking.", response.text)
        }
    }

    @Test
    fun `AX-01 AX-09 a stream cut inside the tool input keeps text and observed input, never the call`() {
        val wire = WireScript().sse(*frames("tool_use", *thinking, *text, *toolStart, cut = true))
        run(wire) { adapter ->
            val invocation = adapter.start(GateTestKit.request(GateTestKit.sonnetProfile(), maxOutput = 16_000), InvocationId("inv-1"))
            val response = invocation.await()
            assertEquals(StopReason.Truncated, response.stop)
            assertTrue(response.items.none { it is ToolCall })
            assertEquals("Checking.", response.text)
            val usage = invocation.terminal().usage!!
            assertEquals(10L, usage.quantities[BillingDimension.UNCACHED_INPUT])
            assertEquals(100L, usage.quantities[BillingDimension.CACHE_READ])
            assertTrue(BillingDimension.OUTPUT in usage.unknown)
        }
    }

    @Test
    fun `a thinking budget above the reserved output is refused before sending, never silently raised`() {
        run(WireScript()) { adapter ->
            val request = GateTestKit.request(GateTestKit.sonnetProfile(), maxOutput = 8_000, effort = Effort.High)
            val rejected = assertIs<Validation.Rejected>(validate(adapter, request))
            assertEquals(ProblemKind.InvalidRequest, rejected.problems.single().kind)
            assertTrue(rejected.problems.single().detail.contains("max_tokens"), rejected.problems.toString())
        }
    }

    @Test
    fun `a profile whose output limit cannot hold an effort's thinking budget is refused when it binds`() {
        WireScript().runtime(Anthropic.provider(), "ANTHROPIC_API_KEY").use { llm ->
            val problems = AiGateAdapter.violations(llm, listOf(GateTestKit.sonnetProfile(output = 8_000)))
            assertTrue(problems.any { it.contains("effort High does not fit outputLimitTokens 8000") }, problems.toString())
            assertEquals(emptyList(), AiGateAdapter.violations(llm, listOf(GateTestKit.sonnetProfile(GateTestKit.gate("""{"effort":"off"}"""), output = 8_000))))
        }
    }

    @Test
    fun `provider refusals of size and credentials become the kinds the cell acts on`() {
        val wire = WireScript()
            .json(400, """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens > 200000 maximum"}}""")
            .json(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        run(wire) { adapter ->
            val request = GateTestKit.request(GateTestKit.sonnetProfile(), maxOutput = 16_000)
            assertFailsWith<ProviderError.ContextOverflow> { adapter.start(request, InvocationId("inv-1")).await() }
            val auth = assertFailsWith<ProviderError.Authentication> { adapter.start(request, InvocationId("inv-2")).await() }
            assertTrue("test-key" !in auth.message!!, "no secret in the message")
        }
    }

    @Test
    fun `profile drafts from the catalog carry the API's cache and usage facts`() {
        WireScript().runtime(Anthropic.provider(), "ANTHROPIC_API_KEY").use { llm ->
            val draft = AiGateProfiles.draft(llm, "anthropic", "claude-sonnet-4-5", "sonnet", java.time.LocalDate.of(2026, 9, 1))
            assertTrue(draft.capabilities.caching.breakpoints)
            assertEquals(4, draft.capabilities.caching.maxBreakpoints)
            assertEquals(GateTestKit.sonnetUsage, draft.capabilities.usageFields)
            assertEquals(64_000, draft.capabilities.outputLimitTokens)
            assertEquals(emptyList(), AiGateAdapter.violations(llm, listOf(draft)))
        }
    }

}
