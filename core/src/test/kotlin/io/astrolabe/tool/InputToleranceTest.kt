package io.astrolabe.tool

import io.astrolabe.provider.ToolCall as ProviderCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InputToleranceTest {
    private val markupNote = " — the arguments carry tool-call markup (<arg_key>); send the arguments as one JSON object"

    private fun parse(tool: String, json: String): ParsedCalls = ToolCalls.parse(listOf(ProviderCall("c1", tool, json)))

    private fun state(json: String): StateArgs =
        (assertIs<ParsedCalls.Valid>(parse("state", json)).calls.single().args as Args.State).args

    private fun refusal(tool: String, json: String): String = assertIs<ParsedCalls.Invalid>(parse(tool, json)).error

    @Test
    fun `a state call without op takes the one form it carries`() {
        assertEquals("patch", state("""{"patch":[{"next":"go"}]}""").op)
        val blocked = state("""{"blocked":{"reason":"r","evidence":["e"],"question":"q"}}""")
        assertEquals(StateArgs(op = "blocked", blocked = BlockedArgs("r", listOf("e"), "q")), blocked)
        assertEquals(StateArgs(op = "retrieval_miss", retrievalMiss = RetrievalMissArgs("n", "w")), state("""{"retrieval_miss":{"need":"n","why":"w"}}"""))
        assertEquals("blocked", ToolCalls.parse(listOf(ProviderCall("c1", "state", """{"blocked":{"reason":"r"}}"""))).let { assertIs<ParsedCalls.Valid>(it).calls.single().op })
        // no op is guessed when the forms are ambiguous or absent, and an op the model gave is never replaced
        assertTrue(refusal("state", """{"patch":[{"next":"go"}],"blocked":{"reason":"r"}}""").contains("op"))
        assertTrue(refusal("state", """{}""").contains("'op'"))
        assertEquals("patch", state("""{"op":"patch","patch":[{"next":"go"}],"blocked":{"reason":"r"}}""").op)
        // A-D.4: `note` names the op only alone; beside a structured form the form is inferred as before D2
        assertEquals("patch", state("""{"patch":[{"next":"go"}],"note":{"kind":"open","text":"x"}}""").op)
        assertEquals("note", state("""{"note":{"kind":"open","text":"x"}}""").op)
    }

    @Test
    fun `the live glued blocked key decodes to the nested form`() {
        val live = """{"blocked<arg_key>evidence":["run(1): exit 1"],"question":"which file owns it?","reason":"no owner found"}"""
        assertEquals(
            StateArgs(op = "blocked", blocked = BlockedArgs("no owner found", listOf("run(1): exit 1"), "which file owns it?")),
            state(live),
        )
        val glued = """{"retrieval_miss<arg_key>need":"the router","why":"not in the workset"}"""
        assertEquals(StateArgs(op = "retrieval_miss", retrievalMiss = RetrievalMissArgs("the router", "not in the workset")), state(glued))
        val remnants = """{"op":"blocked","blocked</arg_key><arg_key>reason</arg_key><arg_value>":"r","evidence":[]}"""
        assertEquals(StateArgs(op = "blocked", blocked = BlockedArgs("r", emptyList())), state(remnants))
    }

    @Test
    fun `a glued key keeps every other top-level key where it is so the schema refuses it by name`() {
        val extra = refusal("state", """{"blocked<arg_key>reason":"r","colour":"red"}""")
        assertTrue(extra.startsWith("state: "), extra)
        assertTrue(extra.contains("colour"), extra)
        assertTrue(extra.endsWith(markupNote), "the call still fails and its text carries markup: $extra")
        val innerUnknown = refusal("state", """{"blocked<arg_key>colour":"red","reason":"r"}""")
        assertTrue(innerUnknown.contains("colour"), innerUnknown)
        val foreignOuter = refusal("state", """{"patch<arg_key>next":"go"}""")
        assertTrue(foreignOuter.contains("patch<arg_key>next"), foreignOuter)
        assertTrue(foreignOuter.endsWith(markupNote), foreignOuter)
    }

    @Test
    fun `a failing call that carries tool-call markup says so and an unmarked failure does not`() {
        val notObject = refusal("state", """{"op":"patch","patch":<arg_value>""")
        assertTrue(notObject.startsWith("state: arguments are not a JSON object"), notObject)
        assertTrue(notObject.endsWith(markupNote), notObject)
        val stillBad = refusal("state", """{"op":"patch","why<arg_key>x":1}""")
        assertTrue(stillBad.endsWith(markupNote), stillBad)
        val inPatch = refusal("state", """{"op":"patch","patch":"[{\"next\":\"<arg_value>go\"}"}""")
        assertTrue(inPatch.contains("patch is a string holding invalid JSON"), inPatch)
        assertTrue(inPatch.endsWith(markupNote), inPatch)
        val plain = refusal("state", """{"op":"patch","colour":"red"}""")
        assertFalse(plain.contains("markup"), plain)
    }

    @Test
    fun `a patch or ops string that is not valid JSON names the parse failure instead of Expected JsonArray`() {
        // D-375: a missing comma is repaired only between values that cannot be anything else; this one is refused
        val patch = refusal("state", """{"op":"patch","patch":"[{\"plan.add\":\"a\" \"b\"}]"}""")
        assertTrue(patch.startsWith("state: patch is a string holding invalid JSON: "), patch)
        assertTrue(patch.contains("offset"), "the parser's position is kept: $patch")
        assertFalse(patch.contains("JsonArray"), patch)
        assertFalse(patch.contains("\n"), patch)
        val ops = refusal("edit", """{"ops":"[{\"create\":","why":"w"}""")
        assertTrue(ops.startsWith("edit: ops is a string holding invalid JSON: "), ops)
        assertFalse(ops.contains("JsonArray"), ops)
        // valid JSON of another shape and plain text still go to the schema
        assertTrue(refusal("state", """{"op":"patch","patch":"next: go"}""").contains("JsonArray"))
        assertTrue(refusal("state", """{"op":"patch","patch":"{\"next\":\"go\"}"}""").contains("JsonArray"))
    }

    @Test
    fun `well-formed calls pass through the tolerance untouched`() {
        val calls = listOf(
            ToolFamily.State to """{"op":"patch","patch":[{"plan.add":"a"},{"next":"go","if":"green(op:1)"}]}""",
            ToolFamily.State to """{"op":"blocked","blocked":{"reason":"r","evidence":["e"],"question":"q"}}""",
            ToolFamily.State to """{"op":"retrieval_miss","retrieval_miss":{"need":"n","why":"w"}}""",
            ToolFamily.State to """{"op":"patch","patch":[{"next":"use <arg_key> literally"}]}""",
            ToolFamily.Edit to """{"ops":[{"path":"a","expect":"c02e","hunks":[{"anchor":"x","new":"y"}]}],"why":"w"}""",
            ToolFamily.Look to """{"what":"read","target":"src/a.py:1-40"}""",
        )
        for ((family, text) in calls) {
            val raw = Json.parseToJsonElement(text) as JsonObject
            assertSame(raw, InputTolerance.normalise(family, raw), text)
            val call = assertIs<ParsedCalls.Valid>(parse(family.wire, text)).calls.single()
            assertEquals(raw, call.raw)
        }
    }

    private fun edit(json: String): EditArgs = (assertIs<ParsedCalls.Valid>(parse("edit", json)).calls.single().args as Args.Edit).args

    @Test
    fun `edit op fields at the top level become the one op they belong to and any other mix names the form`() {
        val hunks = """[{"anchor":"a","new":"b"}]"""
        val quoted = hunks.replace("\"", "\\\"")
        val one = EditArgs(listOf(EditOpArgs(path = "a.ts", expect = "9d09", hunks = listOf(HunkArgs("a", null, "b")))), "w")
        assertEquals(one, edit("""{"expect":"9d09","ops":[{"path":"a.ts","hunks":$hunks}],"why":"w"}"""))
        assertEquals(one, edit("""{"path":"a.ts","expect":"9d09","hunks":$hunks,"why":"w"}"""))
        assertEquals(one, edit("""{"ops":[{"path":"a.ts","expect":"9d09","hunks":"$quoted"}],"why":"w"}"""))
        assertEquals(one, edit("""{"path":"a.ts","expect":"9d09","hunks":"$quoted","why":"w"}"""))
        assertEquals(EditArgs(listOf(EditOpArgs(create = "n.ts", content = "x")), "w"), edit("""{"create":"n.ts","content":"x","why":"w"}"""))
        assertEquals(EditArgs(listOf(EditOpArgs(delete = "n.ts", expect = "9d09")), "w"), edit("""{"delete":"n.ts","expect":"9d09","why":"w"}"""))
        assertEquals(EditArgs(listOf(EditOpArgs(rename = "a.ts", to = "b.ts")), "w"), edit("""{"rename":"a.ts","to":"b.ts","why":"w"}"""))

        val two = refusal("edit", """{"expect":"9d09","ops":[{"path":"a.ts","hunks":$hunks},{"delete":"b.ts"}],"why":"w"}""")
        assertTrue(two.contains("'expect'") && two.contains("(2 ops)") && two.contains("every op field inside its op"), two)
        val clash = refusal("edit", """{"expect":"9d09","ops":[{"path":"a.ts","expect":"1234","hunks":$hunks}],"why":"w"}""")
        assertTrue(clash.contains("every op field inside its op"), clash)
        val path = refusal("edit", """{"path":"b.ts","ops":[{"path":"a.ts","hunks":$hunks}],"why":"w"}""")
        assertTrue(path.contains("top-level 'path' \"b.ts\" and the op's 'path' \"a.ts\" differ") && path.contains("every op field inside its op"), path)
        val broken = refusal("edit", """{"ops":[{"path":"a.ts","hunks":"[{\"anchor\":"}],"why":"w"}""")
        assertTrue(broken.contains("hunks is a string holding invalid JSON"), broken)
    }

    @Test
    fun `every top-level op field the single op lacks moves into it, the live path beside ops included`() {
        val live = javaClass.getResourceAsStream("/live/d373-calls.jsonl")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }.lines()
            .first { "call_00_dvm3t1db0r3bfjk8k5gs9803" in it }
            .let { (Json.parseToJsonElement(it) as JsonObject).getValue("argsJson").let { a -> (a as kotlinx.serialization.json.JsonPrimitive).content } }
        val op = edit(live).ops.single()
        assertEquals("scripts/smoke.js", op.path)
        assertEquals("6f67", op.expect)
        assertEquals(3, op.hunks!!.size)

        val hunks = """[{"anchor":"a","new":"b"}]"""
        val one = EditArgs(listOf(EditOpArgs(path = "a.ts", expect = "9d09", hunks = listOf(HunkArgs("a", null, "b")), condition = "green(op:1)")), "w")
        assertEquals(one, edit("""{"path":"a.ts","hunks":$hunks,"if":"green(op:1)","ops":[{"expect":"9d09"}],"why":"w"}"""))
        assertEquals(one, edit("""{"path":"a.ts","expect":"9d09","ops":[{"path":"a.ts","hunks":$hunks,"if":"green(op:1)"}],"why":"w"}"""), "the same value on both sides is one value")
        val clash = refusal("edit", """{"hunks":$hunks,"ops":[{"path":"a.ts","hunks":[{"anchor":"x","new":"y"}]}],"why":"w"}""")
        assertTrue(clash.contains("top-level 'hunks'") && clash.contains("differ"), clash)
    }
}
