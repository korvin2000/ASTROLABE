package io.astrolabe.tool.state

import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.register.Mark
import io.astrolabe.register.Op
import io.astrolabe.register.Register
import io.astrolabe.register.SqliteRegisterVersions
import io.astrolabe.register.ValidationContext
import io.astrolabe.register.Validator
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.6.8 `state`: raw patch ops parsed once, validated atomically (rule + sizes on rejection, nothing applied), persisted per version; blocked and retrieval_miss. */
class StateToolTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var tool: StateTool
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))

    private class Ctx(val evidence: Set<String> = emptySet(), val green: Set<Int> = emptySet(), val applied: Set<Int> = emptySet()) : ValidationContext {
        override fun evidenceExists(id: String): Boolean = id in evidence
        override fun acceptGreen(accept: String): Boolean = false
        override val redChecks: Set<String> = emptySet()
        override val greenOps: Set<Int> get() = green
        override val appliedOps: Set<Int> get() = applied
    }

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("README.md", "# fixture\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        tool = StateTool(Validator(HeuristicEstimator()), SqliteRegisterVersions(store, clock), Journal(store, clock), HeuristicEstimator(), FixedIdGen(), ids, clock, Register.empty(ids.context!!, "I1", "fix rounding"))
    }

    @AfterTest
    fun tearDown() {
        store.close()
        repo.close()
    }

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "state", json))) as ParsedCalls.Valid).calls.single()

    private suspend fun run(json: String, turn: Int = 1): ToolOutcome = tool.execute(call(json), TurnContext(turn, Workset().snapshot(), Reservations(Tokens(1_000))))

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `raw ops in scalar and object form apply atomically, bump the version and persist`() = runTest {
        val out = run("""{"op":"patch","patch":[{"plan.add":"locate dispatch"},{"plan.add":{"text":"pass ctx","accept":"run: pytest -k ctx","req":"R1/AC-1"}},{"plan.cursor":1},{"fact.add":{"kind":"h","text":"handlers are keyword-only"}},{"next":"read src/router.py"}]}""")
        assertEquals("ok", status(out), out.body)
        assertTrue(out.applied)
        assertTrue(out.body.startsWith("STATE v1 · applied 5 ops"), out.body)
        assertEquals(1, tool.register.version)
        assertEquals(listOf("locate dispatch", "pass ctx"), tool.register.plan.map { it.text })
        assertEquals(Mark.Cursor, tool.register.step(1)!!.mark)
        assertEquals("run: pytest -k ctx", tool.register.step(2)!!.accept)
        assertEquals(ClaimKind.Hypothesis, tool.register.facts.single().kind)
        assertEquals("read src/router.py", tool.register.next)
        assertEquals(tool.register, SqliteRegisterVersions(store, clock).latest(ids.context!!), "persisted per applied patch")
        assertEquals(tool.register, SqliteRegisterVersions(store, clock).get(ids.context!!, 1))
    }

    @Test
    fun `a rejected patch returns the rule and the sizes and applies nothing`() = runTest {
        run("""{"op":"patch","patch":[{"plan.add":"a"},{"plan.cursor":1},{"next":"go"}]}""")
        val before = tool.register
        val rejected = run("""{"op":"patch","patch":[{"fact.add":{"kind":"v","text":"x","evidence":"#99"}},{"next":"n"}]}""")
        assertEquals("rejected", status(rejected))
        assertFalse(rejected.applied)
        assertTrue(rejected.body.startsWith("STATE v1 unchanged · rejected: v needs an existing evidence id — "), rejected.body)
        assertTrue(Regex("register \\d+/1200 tokens · patch \\d+/400 tokens").containsMatchIn(rejected.body), rejected.body)
        assertEquals(before, tool.register, "nothing applied")
        assertNull(SqliteRegisterVersions(store, clock).get(ids.context!!, 2))

        val twoNext = run("""{"op":"patch","patch":[{"next":"a"},{"next":"b"}]}""")
        assertTrue(twoNext.body.contains("rejected: exactly one Next"), twoNext.body)
        val schema = run("""{"op":"patch","patch":[{"plan.tick":2,"if":"sometime"}]}""")
        assertTrue(schema.body.contains("rejected: schema — op 1: malformed condition 'sometime'"), schema.body)
        val twoKeys = run("""{"op":"patch","patch":[{"plan.add":"a","next":"b"}]}""")
        assertTrue(twoKeys.body.contains("needs exactly one op key"), twoKeys.body)
        val unknownField = run("""{"op":"patch","patch":[{"plan.add":{"text":"a","colour":"red"}},{"next":"n"}]}""")
        assertTrue(unknownField.body.contains("rejected: schema — op 1 (plan.add)"), unknownField.body)
        assertEquals(before, tool.register)
    }

    @Test
    fun `conditions and op-result evidence resolve against the turn, and dropped conditional ops are rendered`() = runTest {
        run("""{"op":"patch","patch":[{"plan.add":"a"},{"plan.cursor":1},{"next":"go"}]}""")
        tool.validation = Ctx(evidence = setOf("#7"), green = setOf(2), applied = setOf(1))
        tool.opResults = mapOf(2 to "#7")
        val out = run("""{"op":"patch","patch":[{"plan.tick":{"n":1,"evidence":"op:2"},"if":"green(op:2)"},{"fact.add":{"kind":"v","text":"ctx flows","evidence":"op:2"},"if":"green(op:2)"},{"fact.add":{"kind":"h","text":"never"},"if":"applied(op:3)"},{"next":"done","if":"green(op:2)"}]}""")
        assertEquals("ok", status(out), out.body)
        assertEquals(Mark.Done, tool.register.step(1)!!.mark)
        assertEquals("#7", tool.register.step(1)!!.evidence, "op:2 resolved to the result alias")
        assertEquals("#7", tool.register.facts.single { it.kind == ClaimKind.Verified }.evidenceId)
        assertTrue(tool.register.facts.none { it.text == "never" }, "an unmet condition drops the op")
        assertTrue(out.body.contains("⟨dropped factadd: if applied(op:3) not met⟩"), out.body)
        assertEquals("done", tool.register.next)
        assertEquals(2, tool.register.version)
    }

    @Test
    fun `blocked is recorded for the cell to end with, and retrieval misses are labelled negatives in the store`() = runTest {
        val blocked = run("""{"op":"blocked","blocked":{"reason":"need the finance policy","evidence":["#12"],"question":"round half-up or bankers?"}}""", turn = 3)
        assertEquals("blocked", status(blocked))
        assertTrue(blocked.body.startsWith("blocked: need the finance policy · evidence: #12 · question: round half-up or bankers?"), blocked.body)
        assertEquals(BlockedRequest("need the finance policy", listOf("#12"), "round half-up or bankers?", 3), tool.pendingBlock)

        val miss = run("""{"op":"retrieval_miss","retrieval_miss":{"need":"where discounts are applied","why":"no symbol named discount in src/"}}""", turn = 4)
        assertEquals("ok", status(miss))
        val recorded = tool.retrievalMisses.single()
        assertEquals("where discounts are applied", recorded.need)
        assertEquals(4, recorded.turn)
        val hits = Journal(store, clock).search("retrieval_miss", JournalScope(ids.work))
        assertEquals(1, hits.events.size, "searchable through look(find, in=store)")
        assertEquals(recorded.eventId, hits.events.single().eventId)
        assertTrue(hits.events.single().text.contains("need=where discounts are applied"))
    }

    @Test
    fun `the parser keeps the typed op vocabulary and nothing else`() {
        val parsed = assertIs<ParsedPatch.Valid>(PatchParser.parse(Json.parseToJsonElement("""[{"decision.add":{"text":"pass ctx explicitly","because":"tests construct handlers","rejected":"contextvar","adrCandidate":true}},{"deadend.add":{"text":"monkeypatch","evidence":"#22","scope":"handlers","reopen":"fixtures isolated"}},{"open.add":{"text":"CLI path?","trip":"any edit under src/cli/ → check"}},{"open.close":{"n":1,"evidence":"#9"}},{"focus.set":"src/pay"},{"amend.propose":{"change":"drop AC-2","reason":"obsolete"}},{"plan.cancel":{"n":2,"reason":"out of scope"}},{"fact.refute":{"n":1,"evidence":"#31"}}]""").let { (it as kotlinx.serialization.json.JsonArray).toList() }))
        assertEquals(listOf(Op.DecisionAdd::class, Op.DeadendAdd::class, Op.OpenAdd::class, Op.OpenClose::class, Op.FocusSet::class, Op.AmendPropose::class, Op.PlanCancel::class, Op.FactRefute::class), parsed.patch.ops.map { it.op::class })
        assertTrue((parsed.patch.ops.first().op as Op.DecisionAdd).adrCandidate)
        assertIs<ParsedPatch.Invalid>(PatchParser.parse(emptyList()))
        assertIs<ParsedPatch.Invalid>(PatchParser.parse(listOf(Json.parseToJsonElement("""{"fact.refute":3}"""))), "an op with two required fields has no scalar form")
        assertIs<ParsedPatch.Invalid>(PatchParser.parse(listOf(Json.parseToJsonElement("""{"plan.delete":1}"""))), "not an op of §5.2")
        assertNotNull(PatchParser.nameOf(Json.parseToJsonElement("""{"next":"x","if":"green(op:1)"}""")))
    }
}
