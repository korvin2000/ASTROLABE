package io.astrolabe.provider.aigate

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationProgress
import io.astrolabe.provider.InvocationState
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Role
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import io.astrolabe.provider.estimate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.chat.ToolResultMessage
import net.ai.gate.chat.UserMessage
import net.ai.gate.json.Json
import net.ai.gate.testing.FakeProvider
import net.ai.gate.testing.LlmErrors
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The adapter over a real `Llm` whose only simulated part is the wire endpoint (the SDK's `FakeProvider`). */
class AiGateAdapterTest {
    private val profile = GateTestKit.fakeProfile()

    private fun runtime(fake: FakeProvider, executor: java.util.concurrent.Executor? = null): Llm =
        Llm.builder().provider(fake.provider()).environment(Environment.none()).catalog { it.offline() }
            .apply { executor?.let(::executor) }.build()

    private fun <T> adapting(fake: FakeProvider, block: suspend (AiGateAdapter) -> T): T = runtime(fake).use { llm ->
        AiGateAdapter(llm, listOf(profile)).use { adapter -> runBlocking { withTimeout(20_000) { block(adapter) } } }
    }

    private fun estimateOf(adapter: AiGateAdapter, request: io.astrolabe.provider.Request) =
        adapter.estimators(HeuristicEstimator()).estimatorFor(request.profile).estimate(request)

    @Test
    fun `a text reply completes with its usage and settles one terminal`() {
        val fake = FakeProvider.create().reply { it.text("All done.").usage(120, 7) }
        adapting(fake) { adapter ->
            val request = GateTestKit.request(profile)
            assertEquals(Validation.Ok, adapter.validate(request, estimateOf(adapter, request)))
            val invocation = adapter.start(request, InvocationId("inv-1"))
            val response = invocation.await()
            assertEquals(StopReason.EndTurn, response.stop)
            assertEquals("All done.", response.text)
            val terminal = invocation.terminal()
            assertEquals(response, terminal.response)
            assertEquals(mapOf(BillingDimension.UNCACHED_INPUT to 120L, BillingDimension.CACHE_READ to 0L, BillingDimension.OUTPUT to 7L), terminal.usage!!.quantities)
            assertTrue(terminal.usage!!.isComplete)
            assertEquals("fake-chat", terminal.usage!!.provenance.protocol)
            assertEquals(InvocationState.TerminalReconciled, invocation.state)
        }
        fake.assertAllRepliesConsumed()
    }

    @Test
    fun `system, cached regions, tools and the transcript reach the SDK as one conversation`() {
        val fake = FakeProvider.create().reply { it.toolCall("look", Json.`object`("what", "tree")) }.reply("ok")
        adapting(fake) { adapter ->
            val first = adapter.start(GateTestKit.request(profile), InvocationId("inv-1")).await()
            assertEquals(StopReason.ToolUse, first.stop)
            val call = first.toolCalls.single()
            assertEquals("look", call.name)
            val transcript = first.items + ToolResult.text(call.id, "a.py b.py")
            adapter.start(GateTestKit.request(profile, transcript), InvocationId("inv-2")).await()
        }
        val sent = fake.requests()[1].conversation()
        assertEquals("You operate a coding harness.", sent.system().orElseThrow())
        assertEquals(listOf("look"), sent.tools().map { it.name() })
        val messages = sent.messages()
        assertEquals(listOf("repository prime", "contract slice"), messages.take(2).map { (it as UserMessage).text() })
        val assistant = assertIs<AssistantMessage>(messages[2])
        assertEquals(RequestTranslator.HISTORY, assistant.model(), "a turn without reasoning has the neutral origin")
        val results = assertIs<ToolResultMessage>(messages[3]).results()
        assertEquals("look", results.single().toolName())
        assertEquals("a.py b.py", results.single().text())
        assertEquals("anchor: turn 1", (messages[4] as UserMessage).text())
        assertTrue(sent.cacheBreakpoints().isEmpty(), "automatic caching: no markers")
    }

    @Test
    fun `AX-01 an interrupted stream is truncated text without the half-generated call`() {
        val fake = FakeProvider.create().reply { it.text("Let me look").toolCall("look", Json.`object`("what", "tree")).usage(90, 12).truncated() }
        adapting(fake) { adapter ->
            val invocation = adapter.start(GateTestKit.request(profile), InvocationId("inv-1"))
            val response = invocation.await()
            assertEquals(StopReason.Truncated, response.stop)
            assertTrue(response.toolCalls.isEmpty())
            assertEquals("Let me look", response.text)
            assertNotNull(response.facts?.latencyMillis, "the partial reply keeps its timings")
            val usage = invocation.terminal().usage!!
            assertEquals(90L, usage.quantities[BillingDimension.UNCACHED_INPUT], "input observed before the cut is kept")
            assertTrue(BillingDimension.OUTPUT in usage.unknown, "output may have grown after the last update: unknown, never zero (AX-09)")
        }
    }

    @Test
    fun `the billed amount, reasoning tokens and call facts reach the response`() {
        val wire = WireScript().sse(
            """{"id":"gen-1","provider":"Anthropic","model":"anthropic/claude-4.5-sonnet-20250929","choices":[{"index":0,"delta":{"content":"Done."},"finish_reason":"stop"}]}""",
            """{"id":"gen-1","provider":"Anthropic","choices":[],"usage":{"prompt_tokens":250000,"completion_tokens":9,"cost":0.75,""" +
                """"cost_details":{"upstream_inference_cost":0.7},"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":4}}}""",
            "[DONE]",
        )
        wire.runtime(GateTestKit.openRouter(), "OPENROUTER_API_KEY").use { llm ->
            val profile = AiGateProfiles.draft(llm, "openrouter", GateTestKit.SONNET_ON_OPENROUTER, "main", LocalDate.of(2026, 9, 1))
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val response = adapter.start(GateTestKit.request(profile), InvocationId("inv-1")).await()
                        val usage = response.usage!!
                        assertEquals(Money("USD", BigDecimal("0.75")), usage.billed)
                        assertEquals(Money("USD", BigDecimal("0.7")), usage.billedUpstream)
                        assertEquals(4L, usage.reasoningTokens)
                        val facts = response.facts!!
                        assertEquals("Anthropic", facts.upstream)
                        assertEquals("anthropic/claude-4.5-sonnet-20250929", facts.responseModel)
                        assertEquals(200_000L, facts.priceTierInputTokensAbove, "250 000 input tokens are priced by the long-context tier")
                        assertTrue(facts.firstOutputMillis!! <= facts.latencyMillis!!)
                    }
                }
            }
        }
    }

    private val chatReply = arrayOf(
        """{"id":"gen-1","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
        """{"id":"gen-1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":1}}""",
        "[DONE]",
    )
    private val responsesReply = arrayOf(
        """{"type":"response.created","response":{"id":"resp_1","model":"gpt-5.1"}}""",
        """{"type":"response.completed","response":{"id":"resp_1","model":"gpt-5.1","status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":1}}}""",
    )

    /** The one call [request] of a profile drafted for [providerId]/[model] sends, with [options] as the template's `gate.options`. */
    private fun sent(provider: net.ai.gate.Provider, keyVariable: String, providerId: String, model: String, reply: Array<String>, sessionKey: String?, options: JsonObject? = null): net.ai.gate.spi.http.HttpCall {
        val wire = WireScript().sse(*reply)
        wire.runtime(provider, keyVariable).use { llm ->
            val drafted = AiGateProfiles.draft(llm, providerId, model, "main", LocalDate.of(2026, 9, 1))
            val profile = options?.let { drafted.copy(config = JsonObject(mapOf("gate" to JsonObject((drafted.config["gate"] as JsonObject) + ("options" to it))))) } ?: drafted
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking { withTimeout(20_000) { adapter.start(GateTestKit.request(profile, sessionKey = sessionKey), InvocationId("inv-1")).await() } }
            }
        }
        return wire.calls.single()
    }

    @Test
    fun `the campaign's session key reaches both wire APIs and a template's own session id wins`() {
        val key = "astrolabe-0123456789abcdef0123456789abcdef"
        val openRouter = { sessionKey: String?, options: JsonObject? ->
            sent(GateTestKit.openRouter(), "OPENROUTER_API_KEY", "openrouter", GateTestKit.SONNET_ON_OPENROUTER, chatReply, sessionKey, options)
        }
        val responses = { sessionKey: String?, options: JsonObject? ->
            sent(net.ai.gate.vendors.openai.OpenAi.provider(), "OPENAI_API_KEY", "openai", "gpt-5.1", responsesReply, sessionKey, options)
        }
        val chat = openRouter(key, null)
        assertEquals(key, chat.headers()["x-session-id"])
        assertFalse(String(chat.bytes(), Charsets.UTF_8).contains(key), "chat completions carry the key as a header only")
        assertEquals(null, openRouter(null, null).headers()["x-session-id"])
        val operator = JsonObject(mapOf("sessionId" to JsonPrimitive("operator-session")))
        assertEquals("operator-session", openRouter(key, operator).headers()["x-session-id"])

        assertEquals(key, (responses(key, null).body().orElseThrow() as net.ai.gate.json.JsonObject).optString("prompt_cache_key").orElse(null))
        assertEquals(null, (responses(null, null).body().orElseThrow() as net.ai.gate.json.JsonObject).optString("prompt_cache_key").orElse(null))
        assertEquals("operator-session", (responses(key, operator).body().orElseThrow() as net.ai.gate.json.JsonObject).optString("prompt_cache_key").orElse(null))
    }

    @Test
    fun `a call is priced at the SDK's long-context tier when its input exceeds the threshold`() {
        val usage = { prompt: Long -> """{"id":"gen-1","choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":1000,"prompt_tokens_details":{"cached_tokens":0}}}""" }
        val wire = WireScript()
            .sse("""{"id":"gen-1","choices":[{"index":0,"delta":{"content":"a"},"finish_reason":"stop"}]}""", usage(250_000), "[DONE]")
            .sse("""{"id":"gen-2","choices":[{"index":0,"delta":{"content":"b"},"finish_reason":"stop"}]}""", usage(200_000), "[DONE]")
        wire.runtime(GateTestKit.openRouter(), "OPENROUTER_API_KEY").use { llm ->
            val profile = AiGateProfiles.draft(llm, "openrouter", GateTestKit.SONNET_ON_OPENROUTER, "main", LocalDate.of(2026, 9, 1))
            assertEquals(listOf(200_000L), profile.priceTable.tiers.map { it.inputTokensAbove })
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    withTimeout(20_000) {
                        val long = adapter.start(GateTestKit.request(profile), InvocationId("inv-1")).await()
                        val short = adapter.start(GateTestKit.request(profile), InvocationId("inv-2")).await()
                        // 250 000 × 6 + 1 000 × 22.5 per million; 200 000 does not exceed the threshold: 200 000 × 3 + 1 000 × 15
                        assertEquals(0, BigDecimal("1.5225").compareTo(long.usage!!.price(profile.priceTable).amount))
                        assertEquals(0, BigDecimal("0.615").compareTo(short.usage!!.price(profile.priceTable).amount))
                        assertEquals(200_000L, long.facts!!.priceTierInputTokensAbove)
                        assertEquals(null, short.facts!!.priceTierInputTokensAbove)
                    }
                }
            }
        }
    }

    @Test
    fun `reasoning fills the transcript only where the route's codec replays it`() {
        val reasoning = { origin: String -> ReasoningRef(origin, JsonObject(mapOf("text" to JsonPrimitive("a long chain of hidden thought ".repeat(50))))) }
        fun counted(provider: net.ai.gate.Provider, keyVariable: String, providerId: String, model: String, api: String): Long =
            WireScript().runtime(provider, keyVariable).use { llm ->
                val profile = AiGateProfiles.draft(llm, providerId, model, "main", LocalDate.of(2026, 9, 1))
                AiGateAdapter(llm, listOf(profile)).use { adapter ->
                    val estimator = adapter.estimators(HeuristicEstimator()).estimatorFor(profile)
                    val item = reasoning("$providerId/$model@$api")
                    assertEquals(estimator.estimate(item), item.estimate(estimator), "the cell's item count goes through the estimator")
                    estimator.estimate(item).tokens
                }
            }
        assertEquals(0L, counted(GateTestKit.openRouter(), "OPENROUTER_API_KEY", "openrouter", GateTestKit.SONNET_ON_OPENROUTER, "openai-completions"),
            "OpenRouter chat completions do not replay reasoning")
        assertTrue(counted(net.ai.gate.vendors.openai.OpenAi.provider(), "OPENAI_API_KEY", "openai", "gpt-5.1", "openai-responses") > 300,
            "Responses replay reasoning: it is counted as before")
    }

    @Test
    fun `AX-08 cancellation during the body ends cancelled, archives late text and settles exactly once`() {
        val fake = FakeProvider.create()
        val stall = fake.stall()
        adapting(fake) { adapter ->
            val invocation = adapter.start(GateTestKit.request(profile), InvocationId("inv-1"))
            while (fake.sends() == 0) kotlinx.coroutines.delay(5)
            invocation.cancel()
            invocation.cancel()
            val response = invocation.await()
            assertEquals(StopReason.Cancelled, response.stop)
            assertTrue(response.toolCalls.isEmpty())
            val terminal = invocation.terminal()
            assertTrue(terminal.cancelled)
            assertNotNull(terminal.usage, "a sent, cancelled call is accounted: observed or unknown usage")
            assertEquals(terminal, invocation.terminal(), "one terminal")
            assertFalse(stall.released())
        }
    }

    @Test
    fun `cancelling before the call runs sends nothing and bills nothing`() {
        val queued = CopyOnWriteArrayList<Runnable>()
        val fake = FakeProvider.create().reply("never")
        runtime(fake, queued::add).use { llm ->
            AiGateAdapter(llm, listOf(profile)).use { adapter ->
                runBlocking {
                    val invocation = adapter.start(GateTestKit.request(profile), InvocationId("inv-1"))
                    invocation.cancel()
                    queued.forEach(Runnable::run)
                    assertEquals(StopReason.Cancelled, invocation.await().stop)
                    val terminal = invocation.terminal()
                    assertTrue(terminal.cancelled)
                    assertTrue(terminal.usage!!.isComplete && terminal.usage!!.quantities.values.all { it == 0L }, "never sent: a known zero")
                    assertEquals(0, fake.sends())
                }
            }
        }
    }

    @Test
    fun `provider failures map to the error kinds the loop acts on`() {
        val fake = FakeProvider.create()
            .fail(LlmErrors.contextOverflow())
            .fail(LlmErrors.invalidCredentials())
            .fail(LlmErrors.connectionReset())
            .fail(LlmErrors.gatewayTimeout())
            .fail(LlmErrors.invalidRequest("bad tool schema"))
        adapting(fake) { adapter ->
            suspend fun failure(id: String): Pair<ProviderError, io.astrolabe.provider.Terminal> {
                val invocation = adapter.start(GateTestKit.request(profile), InvocationId(id))
                val error = assertFailsWith<ProviderError> { invocation.await() }
                return error to invocation.terminal()
            }
            val (overflow, overflowTerminal) = failure("inv-1")
            assertIs<ProviderError.ContextOverflow>(overflow)
            assertTrue(overflowTerminal.usage!!.isComplete && overflowTerminal.usage!!.quantities.values.all { it == 0L }, "a 400 was not processed")
            assertIs<ProviderError.Authentication>(failure("inv-2").first)
            val (reset, resetTerminal) = failure("inv-3")
            assertIs<ProviderError.Transport>(reset)
            assertTrue(reset.message!!.contains("outcome unknown"), reset.message)
            assertEquals(profile.capabilities.usageFields, resetTerminal.usage!!.unknown, "possibly billed: unknown, never zero")
            assertIs<ProviderError.Timeout>(failure("inv-4").first)
            assertIs<ProviderError.UnsupportedSchema>(failure("inv-5").first)
        }
    }

    @Test
    fun `rate limits surface after the SDK's own retries`() {
        val fake = FakeProvider.create().fail(LlmErrors.rateLimited(Duration.ofMillis(1))).fail(LlmErrors.rateLimited(Duration.ofSeconds(7)))
        val limited = GateTestKit.fakeProfile(config = GateTestKit.gate("""{"options":{"retry":{"maxAttempts":2}}}"""))
        runtime(fake).use { llm ->
            AiGateAdapter(llm, listOf(limited)).use { adapter ->
                runBlocking {
                    val error = assertFailsWith<ProviderError.RateLimit> { adapter.start(GateTestKit.request(limited), InvocationId("inv-1")).await() }
                    assertEquals(7L, error.retryAfterSeconds)
                    assertEquals(2, fake.sends(), "one retry, inside the SDK")
                }
            }
        }
    }

    @Test
    fun `progress reaches listeners without content`() {
        val fake = FakeProvider.create().reply { it.text("streamed").usage(10, 3) }
        val seen = CopyOnWriteArrayList<InvocationProgress>()
        adapting(fake) { adapter ->
            adapter.addListener { seen += it }.use {
                adapter.start(GateTestKit.request(profile), InvocationId("inv-1")).await()
            }
        }
        assertTrue(seen.any { it is InvocationProgress.Started && it.id == InvocationId("inv-1") }, "$seen")
    }

    @Test
    fun `AX-02 AX-05 broken pairing and a continuation are refused before dispatch`() {
        adapting(FakeProvider.create()) { adapter ->
            val orphan = GateTestKit.request(profile, listOf(ToolResult.text("nope", "orphan")))
            val broken = assertIs<Validation.Rejected>(adapter.validate(orphan, estimateOf(adapter, orphan)))
            assertTrue(broken.problems.any { it.kind == ProblemKind.BrokenToolPairing })
            val continued = GateTestKit.request(profile).copy(continuation = OpaqueContinuation("fake/fake@fake-chat", JsonPrimitive("x"), 10))
            val refused = assertIs<Validation.Rejected>(adapter.validate(continued, estimateOf(adapter, continued)))
            assertTrue(refused.problems.any { it.kind == ProblemKind.UnsupportedContinuation })
        }
    }

    @Test
    fun `AX-07 foreign reasoning is refused unless the profile drops it`() {
        val foreign = listOf(
            Message.text(Role.User, "q"),
            ReasoningRef("anthropic/claude-sonnet-4-5@anthropic-messages", JsonObject(mapOf("text" to JsonPrimitive("thinking"), "signature" to JsonPrimitive("sig")))),
            Message.text(Role.Assistant, "answer"),
        )
        adapting(FakeProvider.create()) { adapter ->
            val request = GateTestKit.request(profile, foreign)
            val rejected = assertIs<Validation.Rejected>(adapter.validate(request, estimateOf(adapter, request)))
            assertTrue(rejected.problems.single().detail.contains("REJECT_LOSSY"), rejected.problems.toString())
        }
        val dropping = GateTestKit.fakeProfile(config = GateTestKit.gate("""{"reasoningHandoff":"drop"}"""))
        val fake = FakeProvider.create().reply("ok")
        runtime(fake).use { llm ->
            AiGateAdapter(llm, listOf(dropping)).use { adapter ->
                runBlocking {
                    val request = GateTestKit.request(dropping, foreign)
                    assertEquals(Validation.Ok, adapter.validate(request, estimateOf(adapter, request)))
                    adapter.start(request, InvocationId("inv-1")).await()
                }
            }
        }
        val replayed = fake.requests().single().conversation().messages().filterIsInstance<AssistantMessage>().single()
        assertEquals(listOf("answer"), replayed.content().map { (it as net.ai.gate.chat.content.Content.Text).text() }, "the signature never crossed models")
    }

    @Test
    fun `admission counts the prepared wire form with the host's text estimator`() {
        adapting(FakeProvider.create()) { adapter ->
            val request = GateTestKit.request(profile, listOf(Message.text(Role.User, "x".repeat(4_000))))
            val estimate = estimateOf(adapter, request)
            assertEquals("ai-gate-fake-chat+heuristic-bytes", estimate.estimatorId)
            assertFalse(estimate.exact)
            assertFalse(estimate.unknownHistory)
            assertTrue(estimate.tokens > 4_000 / 3.6, "the transcript text is counted: ${estimate.tokens}")
            assertTrue(estimate.marginTokens > 0)
        }
    }

    @Test
    fun `profiles that contradict the SDK are refused with every problem named`() {
        FakeProvider.create().let { fake ->
            runtime(fake).use { llm ->
                val wrong = listOf(
                    GateTestKit.fakeProfile("marks").copy(capabilities = GateTestKit.capabilities(128_000, 4_000, breakpoints = true)),
                    GateTestKit.fakeProfile("big", output = 9_000, context = 256_000),
                    GateTestKit.fakeProfile("cont").copy(capabilities = GateTestKit.capabilities(128_000, 4_000, breakpoints = false, continuation = true)),
                    GateTestKit.fakeProfile("typo", config = GateTestKit.gate("""{"outputcap":"enforced"}""")),
                    GateTestKit.fakeProfile("cache", config = GateTestKit.gate("""{"options":{"responseCache":"read_write"}}""")),
                )
                val problems = AiGateAdapter.violations(llm, wrong)
                for (needle in listOf("marks: caching.breakpoints", "big: contextLimitTokens", "big: outputLimitTokens", "cont: continuation", "typo: gate.outputcap", "cache: gate.options.responseCache")) {
                    assertTrue(problems.any { it.contains(needle) }, "$needle not in $problems")
                }
                assertFailsWith<IllegalArgumentException> { AiGateAdapter(llm, wrong) }
                val warned = GateTestKit.fakeProfile("warned", config = GateTestKit.gate("""{"catalogCheck":"warn"}"""), output = 9_000, context = 256_000)
                AiGateAdapter(llm, listOf(warned)).use { assertEquals(2, it.warnings().size, it.warnings().toString()) }
            }
        }
    }

    @Test
    fun `gate body accepts an object and refuses other values and reserved members`() {
        fun parse(json: String) = ArrayList<String>().let { problems -> GateSettings.parse(GateTestKit.gate(json), problems) to problems }
        val (accepted, none) = parse("""{"body":{"provider":{"order":["Z.AI"],"allow_fallbacks":false}}}""")
        assertEquals(emptyList(), none)
        assertEquals("""{"provider":{"order":["Z.AI"],"allow_fallbacks":false}}""", accepted.body.toString())
        assertEquals(listOf("gate.body: must be an object"), parse("""{"body":["provider"]}""").second)
        assertEquals(listOf("gate.body.model: reserved", "gate.body.stream: reserved"), parse("""{"body":{"model":"x","stream":false,"top_k":3}}""").second)
        val wire = Json.parse("""{"model":"m","provider":{"order":["A"],"sort":"price"},"n":1}""") as net.ai.gate.json.JsonObject
        val extra = Json.parse("""{"provider":{"order":["B"],"ignore":["C"]},"n":{"x":2}}""") as net.ai.gate.json.JsonObject
        assertEquals("""{"model":"m","provider":{"order":["B"],"sort":"price","ignore":["C"]},"n":{"x":2}}""", ProfileBinding.merged(wire, extra).toJson())
    }

    @Test
    fun `an unknown provider is a configuration error, not a transport failure`() {
        runtime(FakeProvider.create()).use { llm ->
            val problems = AiGateAdapter.violations(llm, listOf(GateTestKit.fakeProfile().copy(provider = "nowhere")))
            assertTrue(problems.single().contains("provider 'nowhere' is not configured"), problems.toString())
        }
    }

    @Test
    fun `a reused invocation id is refused and close cancels what is in flight`() {
        val fake = FakeProvider.create()
        fake.stall()
        runtime(fake).use { llm ->
            val adapter = AiGateAdapter(llm, listOf(profile))
            runBlocking {
                val invocation = adapter.start(GateTestKit.request(profile), InvocationId("inv-1"))
                assertFailsWith<IllegalArgumentException> { adapter.start(GateTestKit.request(profile), InvocationId("inv-1")) }
                while (fake.sends() == 0) kotlinx.coroutines.delay(5)
                adapter.close()
                assertTrue(withTimeout(10_000) { invocation.terminal() }.cancelled)
            }
        }
    }

    @Test
    fun `tool calls are exposed only on a tool-use stop`() {
        val fake = FakeProvider.create().reply {
            it.toolCall("look", Json.`object`("what", "tree")).stopReason(net.ai.gate.chat.StopReason.LENGTH)
        }
        adapting(fake) { adapter ->
            val response = adapter.start(GateTestKit.request(profile), InvocationId("inv-1")).await()
            assertEquals(StopReason.OutputLimit, response.stop, "AX-03")
            assertTrue(response.items.none { it is ToolCall })
        }
    }
}
