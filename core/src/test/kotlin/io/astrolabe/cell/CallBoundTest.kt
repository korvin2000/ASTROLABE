package io.astrolabe.cell

import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Response
import io.astrolabe.provider.Role
import io.astrolabe.provider.StopReason
import io.astrolabe.provider.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CallBoundTest {
    private fun call(id: Int, args: String, name: String = "look") = ToolCall("c$id", name, args)
    private fun reasoning(tag: String) = ReasoningRef("fake/$tag@fake")
    private fun response(vararg items: Item) = Response(items.toList(), StopReason.ToolUse)
    private fun bound(response: Response, max: Int = 24) = CallBound.of(response, max, sameMax = 3)

    @Test
    fun `calls within the limit pass through untouched, a call made twice included`() {
        // A check before and after a change is the same call twice and both must run.
        val response = response(Message.text(Role.Assistant, "checking"), call(1, """{"what":"acceptance"}""", "verify"), call(2, """{"cmd":"make gen"}""", "run"), call(3, """{"what":"acceptance"}""", "verify"))
        val bound = bound(response)
        assertSame(response, bound.response)
        assertEquals(0, bound.dropped)
        assertNull(bound.line)
    }

    @Test
    fun `only the tail is cut, so every kept call keeps its position`() {
        val a = call(1, """{"target":"a"}""")
        val b = call(2, """{"target":"b"}""")
        val text = Message.text(Role.Assistant, "plan")
        val lead = reasoning("lead")
        val response = response(lead, text, a, b, reasoning("into-the-cut"), call(3, """{"target":"c"}"""), Message.text(Role.Assistant, "after"), call(4, """{"target":"d"}"""))
        val bound = bound(response, max = 2)
        assertEquals(listOf(lead, text, a, b), bound.response.items, "the call past the limit goes with the reasoning that led into it and everything after it")
        assertEquals(listOf(a, b), bound.response.toolCalls)
        assertEquals(4, bound.received)
        assertEquals(2, bound.dropped)
        assertEquals("calls: the response held 4 tool calls; the first 2 were kept and the other 2 were dropped unrun — a response runs at most 2 calls; send the rest in the next response", bound.line)
    }

    @Test
    fun `the third identical call cuts the response there, a different call in between does not reset it`() {
        val same = """{"target":"a"}"""
        val first = call(1, same)
        val other = call(2, """{"target":"b"}""")
        val second = call(3, same)
        val response = response(first, other, second, call(4, same), call(5, """{"target":"z"}"""))
        val bound = bound(response)
        assertEquals(listOf(first, other, second), bound.response.toolCalls, "positions 1 to 3 are unchanged: op:2 still names the same call")
        assertEquals(2, bound.dropped)
        assertTrue(bound.line!!.contains("call 4 made the same look call for the third time in one response"), bound.line)
    }

    @Test
    fun `the same arguments under another tool name are different calls`() {
        val response = response(call(1, "{}", name = "look"), call(2, "{}", name = "state"), call(3, "{}", name = "run"))
        assertEquals(0, bound(response).dropped)
    }

    /** The recorded failure: 3575 calls in one response, ten distinct ones in a cycle (diags W-wzzpswxif6dzrqdxkoiq, turn 23). */
    @Test
    fun `a runaway batch leaves a unit that cannot outgrow the limit`() {
        val cycle = listOf(
            "state" to """{"op":"patch","patch":[{"next":"re-run"}]}""", "run" to """{"argv":["npm","test"]}""",
            "run" to """{"cmd":"gradle.bat test"}""", "verify" to """{"what":"acceptance"}""",
        )
        val items = (1..3575).map { call(it, cycle[(it - 1) % cycle.size].second, cycle[(it - 1) % cycle.size].first) }
        val bound = bound(Response(items, StopReason.ToolUse))
        assertEquals(items.take(8), bound.response.toolCalls, "two rounds of the cycle run; its third round is the loop")
        assertEquals(3567, bound.dropped)

        val distinct = (1..555).map { call(it, """{"target":"f$it"}""") }
        val cut = bound(Response(distinct, StopReason.ToolUse))
        assertEquals(distinct.take(24), cut.response.toolCalls)
        assertEquals(531, cut.dropped)
    }
}
