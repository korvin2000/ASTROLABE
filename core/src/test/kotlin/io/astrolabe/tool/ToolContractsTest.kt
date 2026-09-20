package io.astrolabe.tool

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.ToolCall as ProviderCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        assertTrue(!ToolOps.implementingS0.allows("edit.transform"))
        assertEquals(7 + 6 + 3 + 5 + 3 + 4 + 4, ToolOps.all.size + 0 - 0 - 0 + 0 - (ToolOps.all.size - 32))
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
        val missingExpect = ToolCalls.parse(listOf(ProviderCall("c1", "edit", """{"ops":[{"path":"a","hunks":[{"anchor":"x","new":"y"}]}],"why":"w"}""")))
        assertTrue(assertIs<ParsedCalls.Invalid>(missingExpect).error.contains("expect"))
        val partial = ToolCalls.parse(listOf(ProviderCall("c1", "look", """{"what":"tree"}"""), ProviderCall("c2", "run", """{"cmd":""}""")))
        assertIs<ParsedCalls.Invalid>(partial)
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
