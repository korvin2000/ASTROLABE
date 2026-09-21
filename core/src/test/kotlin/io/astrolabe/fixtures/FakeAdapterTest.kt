package io.astrolabe.fixtures

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.Effort
import io.astrolabe.provider.InvocationId
import io.astrolabe.provider.InvocationState
import io.astrolabe.provider.Message
import io.astrolabe.provider.OpaqueContinuation
import io.astrolabe.provider.ProblemKind
import io.astrolabe.provider.Profile
import io.astrolabe.provider.ProviderError
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Request
import io.astrolabe.provider.Role
import io.astrolabe.provider.SchemaDialect
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.ToolSchema
import io.astrolabe.provider.Validation
import io.astrolabe.provider.estimate
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AX-01..AX-10 (§15.4) and IX-15/16/17/24 at contract level against the fake adapter (TODO P0.3.5). */
class FakeAdapterTest {
    private val estimator = HeuristicEstimator()
    private val schema = ToolSchema("look", "observe the repository", JsonObject(mapOf("type" to JsonPrimitive("object"))), SchemaDialect.JSON_SCHEMA_2020_12)
    private val kernel = Segment(SegmentKind.S, listOf(Message.text(Role.System, "kernel contract " + "rule ".repeat(30))), breakpoint = true)
    private val prime = Segment(SegmentKind.R, listOf(Message.text(Role.User, "repository prime " + "path/file.kt ".repeat(20))), breakpoint = true)

    private fun request(vararg tail: Segment, profile: Profile = FakeProfiles.main, continuation: OpaqueContinuation? = null, maxOutput: Int = 1_000) =
        Request(listOf(kernel, prime) + tail, listOf(schema), profile, Effort.Medium, maxOutput, continuation = continuation)

    private fun transcript(vararg items: io.astrolabe.provider.Item) = Segment(SegmentKind.T, items.toList())

    private var ids = 0
    private fun id() = InvocationId("inv-${++ids}")

    @Test
    fun `AX-01 an interrupted stream yields Truncated with no executable tool call`() = runTest {
        val adapter = FakeAdapter(ScriptedModel.build { fault(FaultKind.InterruptedStream, Message.text(Role.Assistant, "let me look"), ToolCall("c1", "look", "{")) })
        val response = adapter.start(request(transcript(Message.text(Role.User, "go"))), id()).await()
        assertEquals(StopReason.Truncated, response.stop)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals("let me look", response.text)
    }

    @Test
    fun `AX-02 broken tool pairing is rejected before dispatch`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val broken = request(transcript(Message.text(Role.User, "go"), ToolCall("c1", "look", "{}")))
        val result = adapter.validate(broken, broken.estimate(estimator))
        assertIs<Validation.Rejected>(result)
        assertTrue(result.problems.any { it.kind == ProblemKind.BrokenToolPairing })
        val intact = request(transcript(Message.text(Role.User, "go"), ToolCall("c1", "look", "{}"), ToolResult.text("c1", "src/")))
        assertEquals(Validation.Ok, adapter.validate(intact, intact.estimate(estimator)))
    }

    @Test
    fun `AX-03 output-limit stop and AX-04 refusal as stop or error`() = runTest {
        val adapter = FakeAdapter(
            ScriptedModel.build {
                fault(FaultKind.OutputLimit, Message.text(Role.Assistant, "partial"), ToolCall("c", "edit", "{}"))
                fault(FaultKind.RefusalStop)
                fault(FaultKind.RefusalError)
            },
        )
        val r1 = adapter.start(request(transcript(Message.text(Role.User, "1"))), id()).await()
        assertEquals(StopReason.OutputLimit, r1.stop)
        assertTrue(r1.toolCalls.isEmpty())
        val r2 = adapter.start(request(transcript(Message.text(Role.User, "2"))), id()).await()
        assertEquals(StopReason.Refusal, r2.stop)
        val inv3 = adapter.start(request(transcript(Message.text(Role.User, "3"))), id())
        assertFailsWith<ProviderError.Refusal> { inv3.await() }
        val terminal = inv3.terminal()
        assertNotNull(terminal.usage)
        assertNull(terminal.response)
    }

    @Test
    fun `AX-05 expired continuation is an error and AX-06 compaction reports an admitted effective history`() = runTest {
        val compacted = OpaqueContinuation("fake", JsonPrimitive("state-1"), effectiveHistoryTokens = 300)
        val adapter = FakeAdapter(
            ScriptedModel.build {
                reply(Scripted.Reply(listOf(Message.text(Role.Assistant, "compacted")), continuation = compacted))
                fault(FaultKind.ExpiredContinuation)
            },
        )
        val first = adapter.start(request(transcript(Message.text(Role.User, "long history"))), id()).await()
        val continuation = assertNotNull(first.continuation)
        val next = request(transcript(Message.text(Role.User, "more")), continuation = continuation)
        val estimate = next.estimate(estimator)
        assertFalse(estimate.unknownHistory)
        assertEquals(Validation.Ok, adapter.validate(next, estimate))
        assertFailsWith<ProviderError.ExpiredContinuation> { adapter.start(next, id()).await() }

        val unknown = request(transcript(Message.text(Role.User, "more")), continuation = continuation.copy(effectiveHistoryTokens = null))
        val rejected = adapter.validate(unknown, unknown.estimate(estimator))
        assertTrue((rejected as Validation.Rejected).problems.any { it.kind == ProblemKind.UnknownHistorySize })
    }

    @Test
    fun `AX-07 opaque reasoning from another family is refused at a packet boundary`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val foreign = request(transcript(Message.text(Role.User, "go"), ReasoningRef("openai", JsonPrimitive("rs_1"))))
        val result = adapter.validate(foreign, foreign.estimate(estimator))
        assertTrue((result as Validation.Rejected).problems.any { it.kind == ProblemKind.InvalidRequest && it.detail.contains("openai") })
        val own = request(transcript(Message.text(Role.User, "go"), ReasoningRef("fake", JsonPrimitive("r"))))
        assertEquals(Validation.Ok, adapter.validate(own, own.estimate(estimator)))
    }

    @Test
    fun `AX-08 and IX-15 cancellation races settle once with no tool dispatch`() = runTest {
        val late = listOf(ToolCall("late", "run", """{"argv":["rm","-rf"]}"""), Message.text(Role.Assistant, "late text"))
        val adapter = FakeAdapter(ScriptedModel.build { reply(ToolCall("c1", "look", "{}")) }, lateOutputOnCancel = late, holdResponses = true)

        // cancel before the provider acknowledged anything
        val inv = adapter.start(request(transcript(Message.text(Role.User, "go"))), id())
        assertEquals(InvocationState.Requested, inv.state)
        inv.cancel()
        assertEquals(InvocationState.CancelRequested, inv.state)
        val response = inv.await()
        assertEquals(StopReason.Cancelled, response.stop)
        assertTrue(response.toolCalls.isEmpty())
        assertNull(response.usage)
        val terminal = inv.terminal()
        assertTrue(terminal.cancelled)
        assertEquals(late, terminal.lateItems)
        assertNotNull(terminal.usage)
        assertEquals(terminal, inv.terminal(), "terminal is observed exactly once as the same record")
        assertEquals(InvocationState.TerminalReconciled, inv.state)
        assertEquals(0, adapter.calls.count { it.outcome.startsWith("reply") }, "no script consumed")

        // cancel racing a completed response: the completed response stands, nothing is cancelled twice
        val inv2 = adapter.start(request(transcript(Message.text(Role.User, "again"))), id())
        val waiter = async { inv2.await() }
        yield()
        adapter.release(inv2.id)
        val done = waiter.await()
        inv2.cancel()
        assertEquals(StopReason.ToolUse, done.stop)
        assertFalse(inv2.terminal().cancelled)
        assertEquals(done.usage, inv2.terminal().usage)
        assertEquals(InvocationState.TerminalReconciled, inv2.state)
    }

    @Test
    fun `AX-09 missing usage stays unknown and a cancelled call without usage reports none`() = runTest {
        val adapter = FakeAdapter(ScriptedModel.build { reply(Scripted.Reply(listOf(Message.text(Role.Assistant, "ok")), missingUsage = true)) }, usageOnCancel = false)
        val response = adapter.start(request(transcript(Message.text(Role.User, "go"))), id()).await()
        val usage = assertNotNull(response.usage)
        assertFalse(usage.isComplete)
        assertTrue(usage.price(FakeProfiles.main.priceTable).unknown)
        assertEquals(0L, usage.totalInput)

        val inv = adapter.start(request(transcript(Message.text(Role.User, "go"))), id())
        inv.cancel()
        inv.await()
        assertNull(inv.terminal().usage, "unknown usage after cancel keeps the caller's hold")
    }

    @Test
    fun `AX-10 and IX-16 cache classes are billed by segment stability and priced once each`() = runTest {
        val adapter = FakeAdapter(
            ScriptedModel.of(
                Scripted.Reply(listOf(Message.text(Role.Assistant, "one"))),
                Scripted.Reply(listOf(Message.text(Role.Assistant, "two"))),
                Scripted.Reply(listOf(Message.text(Role.Assistant, "three"))),
            ),
            cachePolicy = FakeCachePolicy(listOf(BillingDimension.CACHE_WRITE_5M, BillingDimension.CACHE_WRITE_1H)),
        )
        val first = adapter.start(request(transcript(Message.text(Role.User, "turn 1"))), id()).await().usage!!
        assertEquals(0L, first.quantities.getValue(BillingDimension.CACHE_READ))
        assertTrue(first.quantities.getValue(BillingDimension.CACHE_WRITE_5M) > 0)
        assertTrue(first.quantities.getValue(BillingDimension.CACHE_WRITE_1H) > 0, "two breakpoints, alternating write classes")

        val second = adapter.start(request(transcript(Message.text(Role.User, "turn 2"))), id()).await().usage!!
        val cachedTokens = FakeTokenizer.count(kernel.items) + FakeTokenizer.count(prime.items)
        assertEquals(cachedTokens, second.quantities.getValue(BillingDimension.CACHE_READ))
        assertEquals(0L, second.totalCacheWrite)

        val changedPrime = Request(listOf(kernel, prime.copy(items = listOf(Message.text(Role.User, "changed prime"))), transcript(Message.text(Role.User, "turn 3"))), listOf(schema), FakeProfiles.main, Effort.Medium, 1_000)
        val third = adapter.start(changedPrime, id()).await().usage!!
        assertEquals(FakeTokenizer.count(kernel.items), third.quantities.getValue(BillingDimension.CACHE_READ))
        assertTrue(third.totalCacheWrite > 0)

        val money = first.price(FakeProfiles.main.priceTable)
        assertFalse(money.unknown)
        val expected = FakeProfiles.main.priceTable.price(BillingDimension.UNCACHED_INPUT, first.quantities.getValue(BillingDimension.UNCACHED_INPUT))!! +
            FakeProfiles.main.priceTable.price(BillingDimension.CACHE_WRITE_5M, first.quantities.getValue(BillingDimension.CACHE_WRITE_5M))!! +
            FakeProfiles.main.priceTable.price(BillingDimension.CACHE_WRITE_1H, first.quantities.getValue(BillingDimension.CACHE_WRITE_1H))!! +
            FakeProfiles.main.priceTable.price(BillingDimension.OUTPUT, first.quantities.getValue(BillingDimension.OUTPUT))!! +
            FakeProfiles.main.priceTable.price(BillingDimension.CACHE_READ, 0)!!
        assertEquals(0, expected.amount.compareTo(money.amount))
    }

    @Test
    fun `an appended breakpoint segment reads its previous prefix from cache and pays only for the tail`() = runTest {
        val adapter = FakeAdapter(ScriptedModel.of(), cachePolicy = FakeCachePolicy(listOf(BillingDimension.CACHE_WRITE_5M)))
        val opening = Message.text(Role.User, "turn 1 " + "word ".repeat(40))
        val appended = Message.text(Role.Assistant, "turn 2 reply " + "word ".repeat(10))
        fun history(vararg items: io.astrolabe.provider.Item) = Segment(SegmentKind.T, items.toList(), breakpoint = true)
        adapter.start(request(history(opening)), id()).await()
        val prefix = FakeTokenizer.count(kernel.items) + FakeTokenizer.count(prime.items)

        val append = adapter.start(request(history(opening, appended)), id()).await().usage!!
        assertEquals(prefix + FakeTokenizer.count(opening), append.quantities.getValue(BillingDimension.CACHE_READ), "the prefix the previous breakpoint closed is still cached")
        assertEquals(FakeTokenizer.count(appended), append.quantities.getValue(BillingDimension.CACHE_WRITE_5M), "only the tail is written")

        val rewrite = adapter.start(request(history(Message.text(Role.User, "turn 1 rewritten"), appended)), id()).await().usage!!
        assertEquals(prefix, rewrite.quantities.getValue(BillingDimension.CACHE_READ), "a rewrite before the previous end misses the whole segment (F26)")
        assertEquals(FakeTokenizer.count(listOf(Message.text(Role.User, "turn 1 rewritten"), appended)), rewrite.quantities.getValue(BillingDimension.CACHE_WRITE_5M))
    }

    @Test
    fun `IX-17 the provider counts with its own tokenizer so drift is recorded and admission is not fooled`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val adversarial = "a ".repeat(1_500) // 3000 bytes ≈ 834 heuristic tokens, 1500 fake tokens
        val req = request(transcript(Message.text(Role.User, adversarial)), profile = FakeProfiles.tiny, maxOutput = 400)
        val estimate = req.estimate(estimator)
        assertFalse(estimate.exact)
        val result = adapter.validate(req, estimate)
        assertIs<Validation.Rejected>(result)
        assertTrue(result.problems.any { it.kind == ProblemKind.ContextOverflow && it.detail.startsWith("provider count") }, result.toString())
        val drift = adapter.validations.last().driftTokens
        assertTrue(drift > 0, "fake count exceeds the heuristic: drift=$drift")
    }

    @Test
    fun `IX-24 a profile that rejects the schema dialect is refused before dispatch`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val req = request(transcript(Message.text(Role.User, "go")), profile = FakeProfiles.strictOnly)
        val result = adapter.validate(req, req.estimate(estimator))
        assertTrue((result as Validation.Rejected).problems.any { it.kind == ProblemKind.UnsupportedSchemaDialect })
        assertTrue(adapter.calls.isEmpty())
    }

    @Test
    fun `scripted turns are consumed in order and the fallback answers afterwards`() = runTest {
        val model = ScriptedModel.build {
            whenTextContains("special", Scripted.Reply(listOf(Message.text(Role.Assistant, "special reply"))))
            reply(Message.text(Role.Assistant, "first"))
        }
        val adapter = FakeAdapter(model)
        assertEquals("first", adapter.start(request(transcript(Message.text(Role.User, "plain"))), id()).await().text)
        assertEquals("special reply", adapter.start(request(transcript(Message.text(Role.User, "something special"))), id()).await().text)
        assertEquals("script exhausted", adapter.start(request(transcript(Message.text(Role.User, "plain"))), id()).await().text)
        assertEquals(0, model.remaining)
    }
}
