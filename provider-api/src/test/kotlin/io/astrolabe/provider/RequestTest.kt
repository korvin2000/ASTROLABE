package io.astrolabe.provider

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestTest {
    private val s = Segment(SegmentKind.S, listOf(Message.text(Role.System, "kernel")), breakpoint = true)
    private val t = Segment(SegmentKind.T, listOf(Message.text(Role.User, "fix it")))
    private val a = Segment(SegmentKind.A, listOf(Message.text(Role.User, "anchor")))

    @Test
    fun `segments must follow the layout order`() {
        Fixtures.request(s, t, a)
        assertFailsWith<IllegalArgumentException> { Fixtures.request(t, s) }
        assertFailsWith<IllegalArgumentException> { Fixtures.request(s, s) }
        assertFailsWith<IllegalArgumentException> { Fixtures.request(s, maxOutput = 0) }
    }

    @Test
    fun `truncated and cancelled responses never expose tool calls`() {
        assertFailsWith<IllegalArgumentException> { Response(listOf(ToolCall("c", "look", "{}")), StopReason.Truncated) }
        assertFailsWith<IllegalArgumentException> { Response(listOf(ToolCall("c", "look", "{}")), StopReason.Cancelled) }
        val ok = Response(listOf(ToolCall("c", "look", "{}")), StopReason.ToolUse)
        assertEquals(listOf("c"), ok.toolCalls.map { it.id })
    }

    @Test
    fun `estimate charges tools and every item once and is never exact with a heuristic estimator`() {
        val request = Fixtures.request(s, t, a)
        val estimate = request.estimate(Fixtures.CharEstimator)
        val expected = listOf("look", "observe", Fixtures.schema.jsonSchema.toString(), "kernel", "fix it", "anchor")
            .sumOf { (it.length + 3) / 4L }
        assertEquals(expected, estimate.tokens)
        assertFalse(estimate.exact)
        assertFalse(estimate.unknownHistory)
        assertEquals("chars4", estimate.estimatorId)
    }

    @Test
    fun `an unknown continuation size yields unknownHistory and a reported size is charged exactly`() {
        val unknown = Fixtures.request(s, continuation = OpaqueContinuation("p", JsonPrimitive("x")))
        val e1 = unknown.estimate(Fixtures.CharEstimator)
        assertTrue(e1.unknownHistory)
        assertFalse(e1.exact)

        val reported = Fixtures.request(s, continuation = OpaqueContinuation("p", JsonPrimitive("x"), effectiveHistoryTokens = 5_000))
        val e2 = reported.estimate(Fixtures.CharEstimator)
        assertFalse(e2.unknownHistory)
        assertEquals(Fixtures.request(s).estimate(Fixtures.CharEstimator).tokens + 5_000, e2.tokens)
    }

    @Test
    fun `standard validation rejects broken pairing, breakpoints, dialects, overflow and unknown history`() {
        val broken = Fixtures.request(s, Segment(SegmentKind.T, listOf(ToolCall("c", "look", "{}"))))
        val v1 = Validations.standard(broken, broken.estimate(Fixtures.CharEstimator), Fixtures.capabilities)
        assertIs<Validation.Rejected>(v1)
        assertEquals(listOf(ProblemKind.BrokenToolPairing), v1.problems.map { it.kind })

        val noCache = Fixtures.capabilities.copy(caching = CacheCapability(breakpoints = false))
        val v2 = Validations.standard(Fixtures.request(s), Fixtures.request(s).estimate(Fixtures.CharEstimator), noCache)
        assertEquals(listOf(ProblemKind.UnsupportedBreakpoints), (v2 as Validation.Rejected).problems.map { it.kind })

        val strict = Fixtures.request(t, tools = listOf(Fixtures.schema.copy(dialect = SchemaDialect.OPENAI_STRICT)))
        val v3 = Validations.standard(strict, strict.estimate(Fixtures.CharEstimator), Fixtures.capabilities)
        assertEquals(listOf(ProblemKind.UnsupportedSchemaDialect), (v3 as Validation.Rejected).problems.map { it.kind })

        val big = Fixtures.request(Segment(SegmentKind.T, listOf(Message.text(Role.User, "x".repeat(400_000)))))
        val v4 = Validations.standard(big, big.estimate(Fixtures.CharEstimator), Fixtures.capabilities)
        assertEquals(listOf(ProblemKind.ContextOverflow), (v4 as Validation.Rejected).problems.map { it.kind })

        val margin = Fixtures.request(t, maxOutput = 8_000)
        val nearLimit = Estimate(92_000, exact = false, "chars4", "1", marginTokens = 1)
        val v5 = Validations.standard(margin, nearLimit, Fixtures.capabilities)
        assertEquals(listOf(ProblemKind.ContextOverflow), (v5 as Validation.Rejected).problems.map { it.kind })
        assertEquals(Validation.Ok, Validations.standard(margin, nearLimit.copy(marginTokens = 0), Fixtures.capabilities))

        val unknown = Fixtures.request(t, continuation = OpaqueContinuation("p", JsonPrimitive("x")))
        val v6 = Validations.standard(unknown, unknown.estimate(Fixtures.CharEstimator), Fixtures.capabilities)
        assertEquals(listOf(ProblemKind.UnknownHistorySize), (v6 as Validation.Rejected).problems.map { it.kind })

        val ok = Fixtures.request(s, t, a)
        assertEquals(Validation.Ok, Validations.standard(ok, ok.estimate(Fixtures.CharEstimator), Fixtures.capabilities))
    }
}
