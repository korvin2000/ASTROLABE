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

/** D-373, D-375: the syntax repair of tool-call arguments a model ended malformed, and the refusal of a cut output. */
class JsonRepairTest {
    /** The live run's calls by provider id (`resources/live/d373-calls.jsonl`, copied verbatim from the run's events). */
    private val live: Map<String, ProviderCall> = javaClass.getResourceAsStream("/live/d373-calls.jsonl")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
        .lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        .associate { o -> o.getValue("id").jsonPrimitive.content to ProviderCall(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content, o.getValue("argsJson").jsonPrimitive.content) }

    private fun parsed(call: ProviderCall): ToolCall = assertIs<ParsedCalls.Valid>(ToolCalls.parse(listOf(call)), call.argsJson).calls.single()

    private fun parse(tool: String, json: String, truncated: Boolean = false): ParsedCalls = ToolCalls.parse(listOf(ProviderCall("c1", tool, json)), truncated)

    private fun repaired(text: String): JsonRepair.Repaired = assertIs<JsonRepair.Repaired>(JsonRepair.repair(Json, text, truncated = false), text)

    private val cut = "the output was cut; send the call again in full"

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
            assertEquals(listOf("arguments repaired: dropped '}' at $offset"), parsed(call).notes, id)
            // A cut response is never repaired, whatever the defect.
            val refused = assertIs<ParsedCalls.Invalid>(ToolCalls.parse(listOf(call), truncated = true), id).error
            assertTrue(refused.endsWith(cut), refused)
        }
        assertEquals(listOf("arguments repaired: dropped '}' at 273"), parsed(live.getValue("call_00_xh217fhlbicyy10dppfhyqqt")).notes)
    }

    @Test
    fun `an op object closed one brace late is closed before the next op and the surplus tail brace is dropped`() {
        for (id in listOf("call_00_3vh197hrppf7u5figs1709jd", "call_00_7nijyfllyjadlpsckks1dt4v")) {
            val call = parsed(live.getValue(id))
            val note = call.notes.single()
            assertTrue(note.startsWith("arguments repaired: inserted '}' at "), note)
            val patch = (call.args as Args.State).args.patch!!
            val names = patch.map { PatchParser.nameOf(it) }
            val decision = names.indexOf("decision.add")
            assertEquals("open.add", names[decision + 1], "the op after decision.add is its own op again: $names")
            assertTrue(patch[decision].jsonObject.getValue("decision.add").jsonObject.containsKey("rejected"), patch[decision].toString())
        }
        val first = parsed(live.getValue("call_00_3vh197hrppf7u5figs1709jd"))
        assertTrue(first.notes.single().endsWith("; dropped '}' at 1336"), first.notes.single())
        assertEquals(11, (first.args as Args.State).args.patch!!.size)
    }

    @Test
    fun `a cut edit or run is refused with the schema error, and the same text ended normally is repaired`() {
        val edit = """{"why":"replace","ops":[{"delete":"a.ts"}"""
        val refused = assertIs<ParsedCalls.Invalid>(parse("edit", edit, truncated = true)).error
        assertTrue(refused.startsWith("edit: arguments are not a JSON object (") && refused.endsWith(cut), refused)
        val ended = parse("edit", edit).let { assertIs<ParsedCalls.Valid>(it, it.toString()) }.calls.single()
        assertEquals(listOf("arguments repaired: appended ']' at 41; appended '}' at 41"), ended.notes)
        assertEquals("a.ts", (ended.args as Args.Edit).args.ops.single().delete)

        val run = """{"cmd":"npm test","cwd":"packages/a""""
        assertTrue(assertIs<ParsedCalls.Invalid>(parse("run", run, truncated = true)).error.endsWith(cut))
        assertEquals("packages/a", ((assertIs<ParsedCalls.Valid>(parse("run", run)).calls.single().args) as Args.Run).args.cwd)
        // Whatever the stop reason: the text ends inside a string, or right after ':' or ',' (a value is missing).
        for (text in listOf("""{"cmd":"npm test","cwd":"pack""", """{"cmd":"npm test",""", """{"cmd":"npm test","cwd": """, """{"cmd":"npm te\""")) {
            val error = assertIs<ParsedCalls.Invalid>(parse("run", text)).error
            assertTrue(error.startsWith("run: arguments are not a JSON object") && error.endsWith(cut), error)
        }
        val hunks = assertIs<ParsedCalls.Invalid>(parse("edit", """{"ops":[{"path":"a.ts","hunks":"[{\"anchor\":\"a\",\"new\":\"b\"}"}],"why":"w"}""", truncated = true)).error
        assertTrue(hunks.startsWith("edit: hunks is a string holding invalid JSON: ") && hunks.endsWith(cut), hunks)
    }

    @Test
    fun `each repair class applies once and a string's content is never touched`() {
        // (a) a surplus closer, also inside the text
        assertEquals(listOf("dropped '}' at 14"), repaired("""[{"next":"go"}}]""").changes)
        // (b) missing closers at the end
        assertEquals(listOf("appended ']' at 25"), repaired("""[{"anchor":"a","new":"b"}""").changes)
        // (c) a container closed before a comma whose next token cannot continue it
        assertEquals(listOf("inserted ']' at 9"), repaired("""{"a":[1,2, "b":3}""").changes)
        assertEquals(Json.parseToJsonElement("""{"a":[1,2],"b":3}"""), repaired("""{"a":[1,2, "b":3}""").element)
        // (d) a trailing comma
        assertEquals(listOf("removed ',' at 36"), repaired("""{"op":"patch","patch":[{"next":"go"},]}""").changes)
        // (e) a missing comma between adjacent values
        assertEquals(listOf("inserted ',' at 14"), repaired("""[{"next":"go"}{"next":"stop"}]""").changes)
        assertEquals(listOf("inserted ',' at 14"), repaired("""{"op":"patch" "patch":[{"next":"go"}]}""").changes)
        assertEquals(listOf("inserted ',' at 5"), repaired("""["a" "b"]""").changes)

        val quoted = assertIs<ParsedCalls.Valid>(parse("state", """{"op":"patch","patch":[{"next":"a}b]c"}]}}""")).calls.single()
        assertEquals("a}b]c", (quoted.args as Args.State).args.patch!!.single().jsonObject.getValue("next").jsonPrimitive.content)
        assertEquals(listOf("arguments repaired: dropped '}' at 41"), quoted.notes)
        val patch = assertIs<ParsedCalls.Valid>(parse("state", """{"op":"patch","patch":"[{\"next\":\"go\"}}]"}""")).calls.single()
        assertEquals(listOf("patch string repaired: dropped '}' at 14"), patch.notes)
        val edit = assertIs<ParsedCalls.Valid>(parse("edit", """{"ops":[{"path":"a.ts","hunks":"[{\"anchor\":\"a\",\"new\":\"b\"}"}],"why":"w"}""")).calls.single()
        assertEquals(listOf("hunks string repaired: appended ']' at 25"), edit.notes)
        assertEquals(listOf(HunkArgs("a", null, "b")), (edit.args as Args.Edit).args.ops.single().hunks)
    }

    @Test
    fun `more than four fixes, quotes, comments and other defects stay the parser's error`() {
        val tooMany = assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch","patch":[{"next":"go"}}}}}}]}""")).error
        assertTrue(tooMany.startsWith("state: arguments are not a JSON object") && tooMany.endsWith("not repaired: more than 4 syntax fixes would be needed"), tooMany)
        val single = assertIs<ParsedCalls.Invalid>(parse("state", """{'op':'patch'}""")).error
        assertTrue(single.endsWith("not repaired: single-quoted strings"), single)
        val comment = assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch" /* x */}""")).error
        assertTrue(comment.endsWith("not repaired: comments"), comment)
        for (text in listOf("""{"op" "patch"}""", """{"op":"patch","patch":[{"next":"say "hi" now"}]}""", """{"op":"patch",,"x":1}""", """{"a":1 "b":2}""")) {
            val error = assertIs<ParsedCalls.Invalid>(parse("state", text), text).error
            assertTrue(error.startsWith("state: arguments are not a JSON object") && !error.endsWith(cut), error)
        }
        assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch","patch":<arg_value>[{"next":"go"}]"""))
        assertEquals(emptyList(), parsed(ProviderCall("c1", "state", """{"op":"patch","patch":[{"next":"go"}]}""")).notes, "well-formed calls carry no note")
    }
}
