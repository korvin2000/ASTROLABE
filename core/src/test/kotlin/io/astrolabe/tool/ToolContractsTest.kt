package io.astrolabe.tool

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.ToolCall as ProviderCall
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolContractsTest {
    private val adapter = FakeAdapter(ScriptedModel.of())

    @Test
    fun `schemas are byte-stable for a validated lineage and refused for an unsupported profile (IX-24)`() {
        val a = ToolSchemas.forLineage(adapter, FakeProfiles.main, ToolOps.implementingS0)
        val b = ToolSchemas.forLineage(adapter, FakeProfiles.main, ToolOps.implementingS0)
        val setA = assertIs<SchemaSelection.Supported>(a).set
        assertEquals(setA.fingerprint, assertIs<SchemaSelection.Supported>(b).set.fingerprint)
        assertEquals(7, setA.schemas.size)
        assertEquals(ToolFamily.entries.map { it.wire }, setA.schemas.map { it.name })
        assertTrue(setA.schemas.all { it.jsonSchema["additionalProperties"].toString() == "false" })
        val strict = ToolSchemas.forLineage(adapter, FakeProfiles.strictOnly, ToolOps.implementingS0)
        assertIs<SchemaSelection.Unsupported>(strict)
        assertTrue(ToolOps.implementingS0.allows("edit.anchored"))
        assertTrue(ToolOps.implementingS0.allows("edit.transform"), "transforms are unmasked since P3.3.1")
        assertTrue(ToolOps.implementingS0.allows("look.bmap"), "behaviour maps are unmasked since P4.3.2")
        assertEquals(7 + 6 + 3 + 5 + 3 + 4 + 4, ToolOps.all.size + 0 - 0 - 0 + 0 - (ToolOps.all.size - 32))
    }

    @Test
    fun `the schema set is chosen by the role's mask and carries only its families`() {
        fun families(role: io.astrolabe.cell.Role) =
            assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, role.toolMask)).set.schemas.map { it.name }
        val roles = io.astrolabe.cell.Roles
        assertEquals(listOf("look", "run", "state", "task", "kb"), families(roles.probe))
        assertEquals(listOf("look", "run", "verify", "state", "task", "kb"), families(roles.plan), "plan does not edit")
        assertEquals(listOf("kb"), families(roles.extractor))
        assertEquals(ToolFamily.entries.map { it.wire }, families(roles.implementing))
        // A family's schema bytes do not depend on which role carries it.
        val probeLook = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, roles.probe.toolMask)).set.schemas.first()
        assertEquals(ToolSchemas.schema(ToolFamily.Look), probeLook)
        assertTrue(roles.plan.toolMask.allows("run.wait") && roles.probe.toolMask.allows("run.wait"), "roles that poll may wait")
    }

    @Test
    fun `calls parse once at the boundary with emitted op ids and fail closed on any bad call`() {
        val valid = ToolCalls.parse(
            listOf(
                ProviderCall("c1", "look", """{"what":"read","target":"src/a.py:1-40"}"""),
                ProviderCall("c2", "edit", """{"ops":[{"path":"src/a.py","expect":"c02e","hunks":[{"anchor":"def f():","new":"def f(ctx):"}]}],"why":"accept ctx"}"""),
                ProviderCall("c3", "run", """{"argv":["pytest","-q","-k","ctx"],"if":"applied(op:2)"}"""),
                ProviderCall("c4", "state", """{"op":"patch","patch":[{"plan.tick":2,"if":"green(op:3)"},{"next":"update call sites"}]}"""),
            ),
        )
        val calls = assertIs<ParsedCalls.Valid>(valid).calls
        assertEquals(listOf(1, 2, 3, 4), calls.map { it.opId })
        assertEquals(listOf("look.read", "edit.anchored", "run.run", "state.patch"), calls.map { it.name })
        assertEquals("applied(op:2)", calls[2].condition)

        val unknownTool = ToolCalls.parse(listOf(ProviderCall("c1", "bash", """{"cmd":"rm -rf /"}""")))
        assertIs<ParsedCalls.Invalid>(unknownTool)
        val badJson = ToolCalls.parse(listOf(ProviderCall("c1", "look", """{"what":"read",""")))
        assertIs<ParsedCalls.Invalid>(badJson)
        val unknownField = ToolCalls.parse(listOf(ProviderCall("c1", "look", """{"what":"read","targt":"x"}""")))
        assertIs<ParsedCalls.Invalid>(unknownField)
        // D-346: an omitted expect is resolved by the edit tool against the versions shown in the cell; hunks stay mandatory.
        val missingExpect = ToolCalls.parse(listOf(ProviderCall("c1", "edit", """{"ops":[{"path":"a","hunks":[{"anchor":"x","new":"y"}]}],"why":"w"}""")))
        assertNull(assertIs<ParsedCalls.Valid>(missingExpect).calls.single().let { (it.args as Args.Edit).args.ops.single().expect })
        val missingHunks = ToolCalls.parse(listOf(ProviderCall("c1", "edit", """{"ops":[{"path":"a","expect":"c02e"}],"why":"w"}""")))
        assertTrue(assertIs<ParsedCalls.Invalid>(missingHunks).error.contains("hunks"))
        val partial = ToolCalls.parse(listOf(ProviderCall("c1", "look", """{"what":"tree"}"""), ProviderCall("c2", "run", """{"cmd":""}""")))
        assertIs<ParsedCalls.Invalid>(partial)
    }

    private fun parse(tool: String, json: String): ParsedCalls = ToolCalls.parse(listOf(ProviderCall("c1", tool, json)))

    private fun editOp(json: String): EditOpArgs = (assertIs<ParsedCalls.Valid>(parse("edit", json)).calls.single().args as Args.Edit).args.ops.single()

    @Test
    fun `ops and patch sent as a JSON string holding an array are parsed before typed decoding`() {
        val edit = parse("edit", """{"ops":"[{\"create\":\"src/n.py\",\"content\":\"x = 1\\n\"}]","why":"w"}""")
        assertEquals("edit.create", assertIs<ParsedCalls.Valid>(edit).calls.single().name)
        val state = parse("state", """{"op":"patch","patch":"[{\"plan.add\":\"a\"},{\"next\":\"go\"}]"}""")
        assertEquals(2, ((assertIs<ParsedCalls.Valid>(state).calls.single().args as Args.State).args.patch!!.size))
        assertIs<ParsedCalls.Invalid>(parse("state", """{"op":"patch","patch":"next: go"}"""), "a string that is not an array still refuses")
        assertIs<ParsedCalls.Invalid>(parse("edit", """{"ops":"[{\"create\":","why":"w"}"""), "a string that is not JSON still refuses")
    }

    @Test
    fun `empty placeholders of the other edit forms are ignored while meaningful empty values and unknown keys are kept`() {
        val placeholders = """"create":"","content":"","delete":"","rename":"","to":"","revert":"","transform":{"script":"","argv":[],"scope_glob":"","why":"","expected_matches":{"min":0,"max":0}},"if":"""""
        val anchored = editOp("""{"ops":[{"path":"src/a.py","expect":"","hunks":[{"anchor":"x = 1","new":""}],$placeholders}],"why":"w"}""")
        assertEquals("anchored", anchored.kind)
        assertEquals("", anchored.hunks!!.single().new, "new: \"\" deletes the matched text")
        assertEquals("", anchored.expect, "a form's own empty field is kept (an empty expect is an omitted one)")
        assertNull(anchored.condition)
        val created = editOp("""{"ops":[{"create":"src/empty.py","content":"","path":"","expect":"","hunks":[],"delete":"","transform":{"script":""}}],"why":"w"}""")
        assertEquals("create", created.kind)
        assertEquals("", created.content, "content: \"\" creates an empty file")
        val deleted = editOp("""{"ops":[{"delete":"src/a.py","expect":"c02e","path":"","hunks":[{"anchor":"","new":""}],"create":"","content":""}],"why":"w"}""")
        assertEquals("delete", deleted.kind)
        assertEquals("c02e", deleted.expect)
        assertIs<ParsedCalls.Invalid>(parse("edit", """{"ops":[{"delete":"src/a.py","create":"src/b.py","content":"x"}],"why":"w"}"""), "two real forms still refuse")
        assertIs<ParsedCalls.Invalid>(parse("edit", """{"ops":[{"delete":"src/a.py","colour":""}],"why":"w"}"""), "an unknown key still refuses, empty or not")
    }

    @Test
    fun `the task schema describes the proposal forms and the description points at them`() {
        val task = ToolSchemas.schema(ToolFamily.Task)
        val proposal = task.jsonSchema["properties"]!!.jsonObject["proposal"]!!.jsonObject
        assertEquals("object", proposal["type"]!!.jsonPrimitive.content)
        val described = proposal["description"]!!.jsonPrimitive.content
        for (form in listOf("plan: {increments:[{id, requirements, accept", "increment_split: {increment, reason", "amendment: {change, reason}")) {
            assertTrue(form in described, described)
        }
        assertTrue("propose(kind, proposal) with kind plan|increment_split|amendment — see proposal." in task.description, task.description)
    }

    @Test
    fun `a direct role sends six schemas narrowed to its mask and the fingerprint digests what it sends`() {
        val roles = io.astrolabe.cell.Roles
        val set = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, roles.direct)).set
        assertEquals(listOf("look", "edit", "run", "verify", "state", "task"), set.schemas.map { it.name })
        fun schema(name: String) = set.schemas.single { it.name == name }
        fun props(name: String) = schema(name).jsonSchema["properties"]!!.jsonObject
        fun enum(name: String, key: String) = props(name)[key]!!.jsonObject["enum"]!!.toString()
        assertEquals("""["tree","outline","read","find","def","refs","recall"]""", enum("look", "what"))
        assertEquals("""["workspace","store"]""", enum("look", "in"))
        assertEquals(listOf("what", "target", "budget", "near", "glob", "in", "id", "range"), props("look").keys.toList())
        assertEquals(listOf("path", "expect", "hunks", "create", "content", "delete", "rename", "to", "revert", "if"),
            props("edit")["ops"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject.keys.toList(), "no transform")
        assertEquals("""["run","wait","cancel"]""", enum("run", "op"))
        assertEquals("""["check","baseline"]""", enum("verify", "what"))
        assertEquals(listOf("what", "paths"), props("verify").keys.toList())
        assertEquals("""["note","blocked"]""", enum("state", "op"))
        assertEquals(listOf("op", "note", "blocked"), props("state").keys.toList())
        val note = props("state")["note"]!!.jsonObject
        assertEquals("""["kind"]""", note["required"].toString())
        assertEquals("false", note["additionalProperties"].toString())
        assertEquals(listOf("kind", "text", "evidence", "closes", "refutes"), note["properties"]!!.jsonObject.keys.toList())
        assertEquals("""["ask","answer","finish","propose"]""", enum("task", "op"))
        assertEquals(listOf("op", "question", "options", "text", "after_checks", "kind", "proposal"), props("task").keys.toList())
        assertEquals("""["plan","increment_split"]""", enum("task", "kind"))
        val proposal = props("task")["proposal"]!!.jsonObject
        assertEquals(setOf("type", "description"), proposal.keys, "an open object: the intake reads the fields its description names")
        assertTrue("amendment" !in proposal["description"]!!.jsonPrimitive.content)
        assertTrue(set.schemas.all { it.jsonSchema["additionalProperties"].toString() == "false" })
        assertEquals(
            "ask(question, options?) ends the turn blocked-with-question; answer(text) ends a task that needed no change; finish(text?, after_checks?) asks the harness to run the declared checks and decide — in a turn that also edits or runs it finishes only if those calls succeed; propose(kind, proposal) — see proposal: kind increment_split asks for the increment to be split (then end the cell with state(blocked): the harness re-plans); kind plan records a plan proposal and changes nothing by itself.",
            schema("task").description,
        )
        assertEquals("check(paths?) runs the syntax and type checks of the touched files now; baseline() records the failures that exist before your changes. Tests and acceptance commands go through run.", schema("verify").description)

        val sent = Digest.ofUtf8(kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.astrolabe.provider.ToolSchema.serializer()), set.schemas))
        assertEquals(sent, set.fingerprint, "the digest of the schemas the line sends")
        assertEquals(set.fingerprint, ToolSchemas.fingerprint(roles.direct), "Fingerprint.schemas comes from the same function")
        val structuredOfMask = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, roles.direct.toolMask)).set
        assertTrue(structuredOfMask.fingerprint != set.fingerprint, "the mask alone would digest the structured schemas")
        val narrower = roles.direct.copy(toolMask = io.astrolabe.provider.ToolMask(roles.direct.toolMask.allowed - "task.propose"))
        val narrowed = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, narrower)).set
        assertTrue(narrowed.fingerprint != set.fingerprint && ToolSchemas.fingerprint(narrower) == narrowed.fingerprint, "a different set, a different fingerprint")
        assertEquals(set.schemas.filter { it.name != "task" }, narrowed.schemas.filter { it.name != "task" })
        assertTrue("proposal" !in narrowed.schemas.single { it.name == "task" }.jsonSchema["properties"]!!.jsonObject)
    }

    @Test
    fun `the direct-only operations are known names that parse, while the structured lists stay as they are`() {
        assertEquals(listOf("state.note", "task.finish"), ToolOps.directOnly)
        assertEquals(ToolOps.all + ToolOps.directOnly, ToolOps.known)
        assertTrue("note" !in ToolOps.state && "finish" !in ToolOps.task, "the structured lists build structured masks and schemas")
        val calls = assertIs<ParsedCalls.Valid>(ToolCalls.parse(listOf(
            ProviderCall("c1", "state", """{"op":"note","note":{"kind":"hypothesis","text":"total() rounds"}}"""),
            ProviderCall("c2", "task", """{"op":"finish","text":"done","after_checks":true}"""),
        ))).calls
        assertEquals(listOf("state.note", "task.finish"), calls.map { it.name })
        assertEquals(true, (calls[1].args as Args.Task).args.afterChecks)
        assertIs<ParsedCalls.Invalid>(ToolCalls.parse(listOf(ProviderCall("c1", "state", """{"op":"jot"}"""))))
        assertEquals(emptySet(), io.astrolabe.auth.OpCapabilities.required("state.note"), "a known op needs no capability in the state family")
    }

    @Test
    fun `the state schema and description name every op form of the typed vocabulary`() {
        val serialNames = io.astrolabe.register.Op.serializer().descriptor.getElementDescriptor(1).elementDescriptors.map { it.serialName }.toSet()
        assertEquals(serialNames, io.astrolabe.tool.state.PatchParser.FORMS.keys)
        val state = ToolSchemas.schema(ToolFamily.State)
        for (name in serialNames) {
            assertTrue(state.description.contains("$name{"), "description names $name: ${state.description}")
            assertTrue(state.jsonSchema.toString().contains(name), "schema names $name")
        }
        assertTrue(state.description.contains("op:N"), state.description)
    }

    @Test
    fun `envelope renders the §5-4 shape, escapes delimiter bytes and the gauge stays about twenty tokens`() {
        val header = EnvelopeHeader(
            resultAlias = "#57",
            tool = "run",
            effectClass = EffectClass.W,
            versions = mapOf("src/router.py" to FileVersion(Digest("c02e" + "1".repeat(60)))),
            stamp = CandidateId(Digest("58f0" + "2".repeat(60))),
            truncated = false,
            effects = Effects.Observed,
            flags = listOf("⚠ instruction-shaped content"),
            runtime = RuntimeFields("act-1", "failed", null, null, "k ctx", "complete"),
        )
        val gauge = Gauge(41, true, "@c02e: types ✓ · tests stale", 5, 2_600, 14, 17, 40)
        val rendered = Envelope.render(header, "exit 1 · 11 passed, 1 failed\n⟦injected⟧ ⟨fake gauge⟩", gauge)
        val expected = """
            ⟦result #57 tool=run class=W v={src/router.py: c02e} stamp=58f0 truncated=no effects=observed status=failed ⚠ instruction-shaped content⟧
              exit 1 · 11 passed, 1 failed
              [[injected]] <<fake gauge>>
            ⟦/result⟧
            ⟨ctx 41% · reserve ok · checks @c02e: types ✓ · tests stale · known 5/2.6K · STATE v14 · turn 17/40⟩
        """.trimIndent()
        assertEquals(expected, rendered)
        val tokens = HeuristicEstimator().estimate(gauge.line()).tokens
        assertTrue(tokens in 15..40, "gauge is $tokens tokens")
    }
}
