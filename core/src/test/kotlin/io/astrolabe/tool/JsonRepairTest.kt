package io.astrolabe.tool

import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.tool.state.PatchParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** D-373 item 1: the one bracket repair of tool-call arguments, on the calls a live run lost to an unbalanced tail. */
class JsonRepairTest {
    /** The live run's calls by provider id (`resources/live/d373-calls.jsonl`, copied verbatim from the run's events). */
    private val live: Map<String, ProviderCall> = javaClass.getResourceAsStream("/live/d373-calls.jsonl")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        .lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        .associate { o -> o.getValue("id").jsonPrimitive.content to ProviderCall(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content, o.getValue("argsJson").jsonPrimitive.content) }

    private fun parsed(call: ProviderCall): ToolCall = assertIs<ParsedCalls.Valid>(ToolCalls.parse(listOf(call)), call.argsJson).calls.single()

    private fun parse(tool: String, json: String): ParsedCalls = ToolCalls.parse(listOf(ProviderCall("c1", tool, json)))

    @Test
    fun `every live call refused for one surplus closing brace now parses and its note names the offset`() {
        val surplus = listOf(
            "call_00_e4j6e4k4li76tysk8mbizya2", "call_01_qqt47kqak9wwapfyepw6y1a5", "call_00_kvow714hvq7qiapa4rrovm9u", "call_01_gwdpvn7c10hkdaeobd5zzho6",
            "call_00_qjyh4pb5tlz08i5swt32jxrk", "call_01_tb9tnotiotnj0ed8d0elb4in", "call_01_3j8uykuc9erhmnwhyakof6df", "call_00_xh217fhlbicyy10dppfhyqqt",
        )
        for (id in surplus) {
            val call = live.getValue(id)
            val offset = call.argsJson.length - if (call.name == "edit") 1 else 3
            assertEquals("}", call.argsJson.substring(offset, offset + 1), id)
            assertEquals(listOf("arguments repaired: 1 surplus '}' dropped at offset $offset"), parsed(call).notes, id)
        }
        assertEquals(listOf("arguments repaired: 1 surplus '}' dropped at offset 273"), parsed(live.getValue("call_00_xh217fhlbicyy10dppfhyqqt")).notes)
    }

    @Test
    fun `an op object closed one brace late is closed before the next op and the surplus tail brace is dropped`() {
        for (id in listOf("call_00_3vh197hrppf7u5figs1709jd", "call_00_7nijyfllyjadlpsckks1dt4v")) {
            val call = parsed(live.getValue(id))
            val note = call.notes.single()
            assertTrue(note.startsWith("arguments repaired: 1 missing '}' inserted at offset "), note)
            val patch = (call.args as Args.State).args.patch!!
            val names = patch.map { PatchParser.nameOf(it) }
            val decision = names.indexOf("decision.add")
            assertEquals("open.add", names[decision + 1], "the op after decision.add is its own op again: $names")
            assertTrue(patch[decision].jsonObject.getValue("decision.add").jsonObject.containsKey("rejected"), patch[decision].toString())
        }
        val first = parsed(live.getValue("call_00_3vh197hrppf7u5figs1709jd"))
        assertTrue(first.notes.single().endsWith(", 1 surplus '}' dropped at offset 1336"), first.notes.single())
        assertEquals(11, (first.args as Args.State).args.patch!!.size)
    }

    @Test
    fun `a patch or hunks string with an unbalanced tail is repaired too and noted`() {
        val call = assertIs<ParsedCalls.Valid>(parse("state", """{"op":"patch","patch":"[{\"next\":\"go\"}}]"}""")).calls.single()
        assertEquals(listOf("patch string repaired: 1 surplus '}' dropped at offset 14"), call.notes)
        val edit = assertIs<ParsedCalls.Valid>(parse("edit", """{"ops":[{"path":"a.ts","hunks":"[{\"anchor\":\"a\",\"new\":\"b\"}"}],"why":"w"}""")).calls.single()
        assertEquals(listOf("hunks string repaired: 1 missing ']' appended"), edit.notes)
        assertEquals(listOf(HunkArgs("a", null, "b")), (edit.args as Args.Edit).args.ops.single().hunks)
    }

    @Test
    fun `only closing brackets change, braces inside strings are text, and anything else stays the parser's error`() {
        val quoted = assertIs<ParsedCalls.Valid>(parse("state", """{"op":"patch","patch":[{"next":"a}b]c"}]}}""")).calls.single()
        assertEquals("a}b]c", (quoted.args as Args.State).args.patch!!.single().jsonObject.getValue("next").jsonPrimitive.content)
        assertEquals(listOf("arguments repaired: 1 surplus '}' dropped at offset 41"), quoted.notes)
        val tooMany = assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch","patch":[{"next":"go"}}}}}]}""")).error
        assertTrue(tooMany.startsWith("state: arguments are not a JSON object"), tooMany)
        val comma = assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch" "patch":[{"next":"go"}]}""")).error
        assertTrue(comma.startsWith("state: arguments are not a JSON object"), comma)
        val open = assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch","patch":[{"next":"go""")).error
        assertTrue(open.startsWith("state: arguments are not a JSON object"), "an unterminated string is not a bracket problem: $open")
        assertEquals(emptyList(), parsed(ProviderCall("c1", "state", """{"op":"patch","patch":[{"next":"go"}]}""")).notes, "well-formed calls carry no note")
    }
}
