package io.astrolabe.provider

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemsTest {
    private val json = Json

    private val items: List<Item> = listOf(
        Message.text(Role.User, "fix the bug"),
        ReasoningRef("openai", JsonPrimitive("rs_1"), native = buildJsonObject { put("id", JsonPrimitive("rs_1")) }),
        ToolCall("c1", "look", """{"what":"tree"}"""),
        ToolResult.text("c1", "src/"),
        UsageItem(BillableUsage(mapOf(BillingDimension.OUTPUT to 5L), Fixtures.provenance)),
        OpaqueContinuation("anthropic", JsonObject(mapOf("k" to JsonPrimitive("v"))), effectiveHistoryTokens = 120),
        Message(Role.Assistant, listOf(Text("done"), Opaque("image", JsonPrimitive("…")))),
    )

    @Test
    fun `every item kind round trips through json with its native passthrough`() {
        val serializer = ListSerializer(Item.serializer())
        val text = json.encodeToString(serializer, items)
        assertEquals(items, json.decodeFromString(serializer, text))
        assertTrue(text.contains("\"type\":\"tool_call\""), text)
        assertTrue(text.contains("\"native\":{\"id\":\"rs_1\"}"), text)
    }

    @Test
    fun `pairs detects intact histories`() {
        val pairing = Items.pairs(listOf(ToolCall("a", "look", "{}"), ToolCall("b", "run", "{}"), ToolResult.text("a", "x"), ToolResult.text("b", "y")))
        assertFalse(pairing.broken)
        assertEquals(listOf("a", "b"), pairing.pairs.map { it.call.id })
        assertTrue(pairing.pairs.all { it.result != null })
    }

    @Test
    fun `pairs flags an evicted result, an orphan, a duplicate and a misordered result`() {
        val evicted = Items.pairs(listOf(ToolCall("a", "look", "{}")))
        assertTrue(evicted.broken)
        assertEquals(listOf("a"), evicted.unmatchedCalls.map { it.id })

        val orphan = Items.pairs(listOf(ToolResult.text("zz", "x")))
        assertTrue(orphan.broken)
        assertEquals(listOf("zz"), orphan.orphanResults.map { it.callId })

        val duplicate = Items.pairs(listOf(ToolCall("a", "look", "{}"), ToolResult.text("a", "1"), ToolResult.text("a", "2")))
        assertTrue(duplicate.broken)
        assertEquals(1, duplicate.duplicateResults.size)

        val misordered = Items.pairs(listOf(ToolResult.text("a", "1"), ToolCall("a", "look", "{}")))
        assertTrue(misordered.broken)
        assertEquals(1, misordered.orphanResults.size)
        assertEquals(1, misordered.unmatchedCalls.size)
    }

    @Test
    fun `message text concatenates text parts only`() {
        assertEquals("done", (items.last() as Message).text)
    }
}
