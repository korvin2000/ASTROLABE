package io.astrolabe.tool.kb

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.kb.EmptyKb
import io.astrolabe.kb.Kb
import io.astrolabe.kb.KbEntry
import io.astrolabe.kb.KbHit
import io.astrolabe.kb.KbHits
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.6.10 `kb` contract: complete-empty results on the S0 base (never "absent"), stale hits labelled, propose masked. */
class KbToolTest {
    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "kb", json))) as ParsedCalls.Valid).calls.single()

    private suspend fun run(kb: Kb, json: String): ToolOutcome = KbTool(kb, HeuristicEstimator(), FixedIdGen()).execute(call(json), TurnContext(1, Workset().snapshot(), Reservations(Tokens(1_000))))

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `the empty base answers every search complete and empty and every lookup not found, never absent`() = runTest {
        val search = run(EmptyKb, """{"op":"search","query":"idempotency","kinds":["CON"],"why":"payments contract"}""")
        assertEquals("ok", status(search))
        assertEquals("0 notes match 'idempotency' in kb · complete", search.body)
        assertEquals("complete", search.header!!.runtime.completeness)
        assertTrue(search.header!!.runtime.captureComplete)
        assertFalse(search.header!!.truncated)
        val scoped = run(EmptyKb, """{"op":"search","query":"x","scope":"payments","why":"w"}""")
        assertEquals("payments", scoped.header!!.runtime.scope)
        val missing = run(EmptyKb, """{"op":"get","id":"CON-007"}""")
        assertEquals("not_found", status(missing))
        assertEquals("no note 'CON-007' in the knowledge base (complete: the base was searched)", missing.body)
        assertEquals("complete", missing.header!!.runtime.completeness)
        assertEquals("not_found", status(run(EmptyKb, """{"op":"skill","id":"SK-1"}""")))
        assertEquals("masked", status(run(EmptyKb, """{"op":"propose","note":{"kind":"LES"}}""")))
    }

    @Test
    fun `a base with notes renders hits, labels stale ones, and reports incompleteness and truncation honestly`() = runTest {
        val kb = object : Kb {
            override fun search(query: String, kinds: Set<String>?, scope: String?, why: String): KbHits = KbHits(
                listOf(KbHit("CON-007", "CON", "payments API contract", stale = false), KbHit("STATUS-3", "STATUS", "old checkpoint", stale = true)),
                scope ?: "kb", complete = false,
            )
            override fun get(id: String): KbEntry? = if (id == "CON-007") KbEntry("CON-007", "CON", "POST /payments is idempotent per merchant key", stale = true) else null
            override fun skill(id: String): KbEntry? = null
        }
        val hits = run(kb, """{"op":"search","query":"payments","why":"w"}""")
        assertTrue(hits.body.startsWith("2 notes match 'payments' in kb · incomplete: the scope was not fully searched"), hits.body)
        assertTrue(hits.body.contains("  STATUS-3 [STATUS] stale: old checkpoint"), hits.body)
        assertEquals("incomplete", hits.header!!.runtime.completeness)
        assertFalse(hits.header!!.runtime.captureComplete)
        val note = run(kb, """{"op":"get","id":"CON-007"}""")
        assertEquals("ok", status(note))
        assertTrue(note.body.startsWith("note CON-007 [CON] · stale: anchors moved, not current\nPOST /payments"), note.body)
        val capped = KbTool(kb, HeuristicEstimator(), FixedIdGen(), maxHits = 1).execute(call("""{"op":"search","query":"payments","why":"w"}"""), TurnContext(1, Workset().snapshot(), Reservations(Tokens(1_000))))
        assertTrue(capped.header!!.truncated)
        assertTrue(capped.body.contains("showing 1"), capped.body)
    }
}
