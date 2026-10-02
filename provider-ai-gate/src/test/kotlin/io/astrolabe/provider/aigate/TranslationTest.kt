package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CallFacts
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.Opaque
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Role
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import kotlinx.serialization.json.JsonPrimitive
import net.ai.gate.Llm
import net.ai.gate.auth.Environment
import net.ai.gate.cache.CacheRetention
import net.ai.gate.chat.AssistantMessage
import net.ai.gate.metadata.Charge
import net.ai.gate.metadata.ResponseInfo
import net.ai.gate.metadata.Usage
import net.ai.gate.model.ModelRef
import net.ai.gate.testing.FakeProvider
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure translation and usage rules, over a binding to the SDK's fake model. */
class TranslationTest {
    private val llm: Llm = Llm.builder().provider(FakeProvider.create().provider()).environment(Environment.none()).catalog { it.offline() }.build()

    @AfterTest
    fun close() = llm.close()

    private fun bind(profile: Profile): ProfileBinding = ProfileBinding.compile(llm, profile, ArrayList())!!

    private val expectingWrites = GateTestKit.fakeProfile().copy(
        capabilities = GateTestKit.capabilities(128_000, 4_000, breakpoints = false, usage = GateTestKit.sonnetUsage.minus(BillingDimension.CACHE_READ)),
    )

    @Test
    fun `assistant turns split where the reasoning origin changes and fall back to the neutral origin`() {
        val a = ReasoningRef("anthropic/claude-sonnet-4-5@anthropic-messages", JsonPrimitive("x"))
        val b = ReasoningRef("openai/gpt-5.1@openai-responses")
        val items = listOf(Message.text(Role.User, "q"), a, Message.text(Role.Assistant, "one"), b, ToolCall("c1", "look", "{}"), ToolResult.text("c1", "r"), Message.text(Role.Assistant, "plain"), ReasoningRef("fake"))
        val messages = RequestTranslator.translate(GateTestKit.request(GateTestKit.fakeProfile(), items), bind(GateTestKit.fakeProfile())).messages()
        val turns = messages.filterIsInstance<AssistantMessage>()
        assertEquals(
            listOf(ModelRef("anthropic", "claude-sonnet-4-5"), ModelRef("openai", "gpt-5.1"), ModelRef("astrolabe", "unknown-origin")),
            turns.map { it.model() },
        )
        assertEquals(listOf("anthropic-messages", "openai-responses", "unknown"), turns.map { it.api() })
        assertTrue(turns[1].hasToolCalls())
        assertEquals("plain", turns[2].text(), "the text before an unparseable tag shares its (unknown) origin")
    }

    @Test
    fun `parts without a portable form and misplaced system text are refused`() {
        val profile = GateTestKit.fakeProfile()
        for (items in listOf(
            listOf(Message(Role.User, listOf(Opaque("image", JsonPrimitive("ref"))))),
            listOf(Message.text(Role.System, "late system text")),
            listOf(ToolCall("c1", "look", "{}"), ToolResult("c1", listOf(Opaque("image", JsonPrimitive("ref"))))),
        )) assertFailsWith<TranslationException> { RequestTranslator.translate(GateTestKit.request(profile, items), bind(profile)) }
    }

    @Test
    fun `an undifferentiated cache write belongs to the only class the request could write`() {
        val usage = Usage.builder().input(10).cacheRead(0).cacheWrite(40).output(3).build()
        val short = UsageMapper.billable(usage, null, bind(expectingWrites))
        assertEquals(40L, short.quantities[BillingDimension.CACHE_WRITE_5M])
        assertEquals(0L, short.quantities[BillingDimension.CACHE_WRITE_1H], "no 1-hour marker was sent")
        assertTrue(short.isComplete)
        val mixed = UsageMapper.billable(usage, null, bind(expectingWrites.copy(config = GateTestKit.gate("""{"prefixRetention":"long"}"""))))
        assertEquals(setOf(BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H), mixed.unknown, "both classes were requested: the split is unknown")
        val classes = Usage.builder().input(10).cacheRead(0).cacheWrite(CacheRetention.SHORT, 7).cacheWrite(CacheRetention.LONG, 9).output(3).build()
        val split = UsageMapper.billable(classes, null, bind(expectingWrites))
        assertEquals(7L, split.quantities[BillingDimension.CACHE_WRITE_5M])
        assertEquals(9L, split.quantities[BillingDimension.CACHE_WRITE_1H])
        assertEquals(16L, split.totalCacheWrite, "each class once (I-16)")
    }

    @Test
    fun `a reported charge and reasoning tokens stay apart from the priced dimensions and unknown when absent`() {
        val binding = bind(GateTestKit.fakeProfile())
        val charge = Charge(Currency.getInstance("USD"), BigDecimal("0.0021"), BigDecimal("0.002"))
        val reported = Usage.builder().input(10).cacheRead(0).output(5).reasoning(3).charge(charge).build()
        val usage = UsageMapper.billable(reported, null, binding)
        assertEquals(Money("USD", BigDecimal("0.0021")), usage.billed)
        assertEquals(Money("USD", BigDecimal("0.002")), usage.billedUpstream)
        assertEquals(3L, usage.reasoningTokens)
        assertEquals(
            mapOf(BillingDimension.UNCACHED_INPUT to 10L, BillingDimension.CACHE_READ to 0L, BillingDimension.OUTPUT to 5L), usage.quantities,
            "reasoning is inside output, never a priced dimension of its own",
        )
        val silent = UsageMapper.billable(Usage.builder().input(10).cacheRead(0).output(5).build(), null, binding)
        assertNull(silent.billed, "no charge reported: unknown, never zero (AX-09)")
        assertNull(silent.billedUpstream)
        assertNull(silent.reasoningTokens)
        val observed = UsageMapper.billable(reported.toBuilder().finalForCall(false).build(), null, binding)
        assertNull(observed.billed, "a call that did not end has no final charge")
        assertNull(observed.reasoningTokens)
    }

    @Test
    fun `call facts come from the reply's call and are absent for a reply no call produced`() {
        val binding = bind(GateTestKit.fakeProfile())
        val info = ResponseInfo.builder("req_1", "fake").latency(Duration.ofMillis(1_500)).timeToFirstOutput(Duration.ofMillis(400)).route("Groq").build()
        val reply = AssistantMessage.builder(ModelRef("fake", "fake"), "fake-chat").text("hi").responseModel("fake-2026").info(info).build()
        assertEquals(CallFacts(1_500, 400, "Groq", "fake-2026", null), ResponseTranslator.facts(reply, binding))
        val archived = reply.toBuilder().info(ResponseInfo.empty()).build()
        assertEquals(CallFacts(responseModel = "fake-2026"), ResponseTranslator.facts(archived, binding), "no call, no timings: unknown, not zero")
    }

    @Test
    fun `usage observed before the call ended never claims a final output count`() {
        val observed = Usage.builder().input(10).output(2).finalForCall(false).build()
        val usage = UsageMapper.billable(observed, null, bind(GateTestKit.fakeProfile()))
        assertEquals(10L, usage.quantities[BillingDimension.UNCACHED_INPUT])
        assertEquals(setOf(BillingDimension.CACHE_READ, BillingDimension.OUTPUT), usage.unknown)
    }
}
