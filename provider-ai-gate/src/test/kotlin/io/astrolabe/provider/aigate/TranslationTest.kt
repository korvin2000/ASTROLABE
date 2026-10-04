package io.astrolabe.provider.aigate

import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.CallFacts
import io.astrolabe.provider.Message
import io.astrolabe.provider.Money
import io.astrolabe.provider.Opaque
import io.astrolabe.provider.PriceTable
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
import net.ai.gate.model.Prices
import net.ai.gate.testing.FakeProvider
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.util.Currency
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `a drafted price table prices a call as the SDK's tiered prices do`() {
        val prices = Prices.usd().input("3").output("15").cacheRead("0.3").cacheWrite("3.75").cacheWriteLong("6")
            .tier(1_000_000, Prices.usd().input("12").build())
            .tier(200_000, Prices.usd().input("6").output("22.5").cacheWrite("7.5").build())
            .build()
        val table = AiGateProfiles.priceTable(prices, LocalDate.of(2026, 9, 1))
        assertEquals(listOf(200_000L, 1_000_000L), table.tiers.map { it.inputTokensAbove })
        val binding = bind(GateTestKit.fakeProfile())
        val cases = listOf(
            longArrayOf(1_000, 0, 0, 0), longArrayOf(150_000, 50_000, 0, 0), longArrayOf(150_000, 50_001, 0, 0),
            longArrayOf(100_000, 50_000, 30_000, 40_000), longArrayOf(990_000, 5_000, 5_000, 1), longArrayOf(2_000_000, 0, 7, 9),
        )
        for ((input, read, short, long) in cases.map { it.toList() }) {
            val usage = Usage.builder().input(input).cacheRead(read).cacheWrite(CacheRetention.SHORT, short).cacheWrite(CacheRetention.LONG, long)
                .output(2_000).finalForCall(true).build()
            val ours = UsageMapper.billable(usage, null, binding).price(table)
            val sdk = prices.cost(usage).orElseThrow()
            assertFalse(ours.unknown)
            assertEquals(0, sdk.total().compareTo(ours.amount), "input $input, read $read, writes $short/$long: SDK ${sdk.total()} vs ${ours.amount}")
            assertEquals(prices.tier(usage).map { it.inputTokensAbove() }.orElse(null), table.tier(input + read + short + long)?.inputTokensAbove)
        }
        assertEquals(PriceTable(LocalDate.of(2026, 9, 1), "USD", emptyMap()), AiGateProfiles.priceTable(null, LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `a subscription model takes the official price of the same model at its paying provider, never a guess`() {
        val official = Prices.usd().input("5").output("30").cacheRead("0.5").build()
        val other = Prices.usd().input("6").output("31").build()
        fun model(provider: String, id: String, prices: Prices? = null) = net.ai.gate.model.Model.builder(provider, id).prices(prices).build()
        val catalog = listOf(model("openai-codex", "gpt-5.5"), model("openai", "gpt-5.5", official), model("azure", "gpt-5.5", other),
            model("openai-codex", "own", other), model("openai-codex", "lonely"), model("vendor", "lonely", official),
            model("openai-codex", "orphan"))
        assertSame(official, AiGateProfiles.officialPrices(catalog, "openai-codex", "gpt-5.5"), "the paying provider whose id prefixes the plan's")
        assertSame(other, AiGateProfiles.officialPrices(catalog, "openai-codex", "own"), "the model's own price wins")
        assertSame(official, AiGateProfiles.officialPrices(catalog, "openai-codex", "lonely"), "the only priced entry")
        assertNull(AiGateProfiles.officialPrices(catalog, "openai-codex", "orphan"))
        val strangers = catalog.filter { it.providerId() != "openai" } + model("bedrock", "gpt-5.5", official)
        assertNull(AiGateProfiles.officialPrices(strangers, "openai-codex", "gpt-5.5"), "two strangers: the user enters it")
        val table = AiGateProfiles.priceTable(official, LocalDate.of(2026, 9, 1)).copy(billing = io.astrolabe.provider.Billing.Plan)
        assertEquals(io.astrolabe.provider.Charge.Nominal, table.charge)
    }

    @Test
    fun `a runtime with only the subscription still prices it at the paying provider's bundled price`() {
        Llm.builder().provider(net.ai.gate.providers.Providers.openAiCodex()).environment(Environment.none())
            .credentials(net.ai.gate.auth.CredentialStore.inMemory()).catalog { it.offline() }.build().use { codex ->
            assertTrue(codex.models().all().none { it.providerId() == "openai" }, "the paying provider is not registered")
            val table = assertNotNull(AiGateProfiles.planPriceTable(codex, "openai-codex", "gpt-5.5", LocalDate.of(2026, 9, 1)))
            assertEquals(io.astrolabe.provider.Charge.Nominal, table.charge)
            assertEquals(0, BigDecimal("5").compareTo(table.perMillion.getValue(BillingDimension.UNCACHED_INPUT)))
            assertEquals(0, BigDecimal("30").compareTo(table.perMillion.getValue(BillingDimension.OUTPUT)))
        }
    }

    @Test
    fun `a spent plan quota is its own provider error, a rate limit stays a rate limit`() {
        fun failure(code: net.ai.gate.error.ErrorCode) = net.ai.gate.error.RateLimitedException(
            net.ai.gate.error.LlmException.Details.builder(code, "limit").retryAfter(Duration.ofHours(2)).build())
        val quota = ErrorMapper.error(failure(net.ai.gate.error.ErrorCode.QUOTA_EXHAUSTED))
        assertEquals(7_200L, (quota as io.astrolabe.provider.ProviderError.QuotaExhausted).retryAfterSeconds)
        assertTrue(ErrorMapper.error(failure(net.ai.gate.error.ErrorCode.RATE_LIMITED)) is io.astrolabe.provider.ProviderError.RateLimit)
    }

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
