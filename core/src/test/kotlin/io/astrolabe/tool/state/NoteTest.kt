package io.astrolabe.tool.state

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Anchor
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.register.InMemoryRegisterVersions
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import io.astrolabe.register.ValidationContext
import io.astrolabe.register.Validator
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P8.D.2 `state(note)` (kernel contract A-D.4): one note, one patch of the existing ops, through the existing validator. */
class NoteTest {
    private lateinit var repo: TempRepo
    private lateinit var contracts: Contracts
    private val clock = FakeClock.at("2026-10-04T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))
    private val estimator = HeuristicEstimator()
    private val idGen = FixedIdGen()
    private val read = FileVersion(Digest("c02e1f9a".repeat(8)))

    private class Ctx(private val stored: Set<String>, private val observed: Map<String, io.astrolabe.evidence.Anchor> = emptyMap()) : ValidationContext {
        override fun evidenceExists(id: String): Boolean = id in stored
        override fun observedFile(id: String) = observed[id]
        override fun currentVersion(path: String): FileVersion? = observed.values.firstOrNull { it.path == path }?.version
        override fun acceptGreen(accept: String): Boolean = false
        override val redChecks: Set<String> = emptySet()
        override val greenOps: Set<Int> = emptySet()
        override val appliedOps: Set<Int> = emptySet()
    }

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("README.md", "# fixture\n")
        repo.commit("initial")
        contracts = Contracts(InMemoryContractRepository(), idGen, clock)
        contracts.open(contracts.deriveS0(ids.work, ids.attempt, "fix rounding", Atlas.build(repo.root), Config(), Tokens(1_000)).contract)
    }

    @AfterTest
    fun tearDown() = repo.close()

    private fun direct(registerCapTokens: Int = 3_000, register: Register = Register.empty(ids.context!!, "I1", "fix rounding")): StateTool =
        StateTool(Validator(estimator, registerCapTokens = registerCapTokens, protocol = Protocol.Direct), InMemoryRegisterVersions(), null, estimator, idGen, ids, clock, register)
            .also { tool ->
                tool.direct(Roles.direct.toolMask, contracts)
                tool.validation = Ctx(setOf("#12", "#14", "#20"), mapOf("#12" to io.astrolabe.evidence.Anchor("src/money.py", read)))
            }

    private suspend fun StateTool.run(json: String, turn: Int = 1): ToolOutcome =
        execute((ToolCalls.parse(listOf(ProviderCall("c1", "state", json))) as ParsedCalls.Valid).calls.single(), TurnContext(turn, Workset().snapshot(), Reservations(Tokens(1_000))))

    private fun note(fields: String) = """{"op":"note","note":{$fields}}"""

    @Test
    fun `every kind is recorded with its id and reads back in the journal`() = runTest {
        val tool = direct()
        val results = listOf(
            tool.run(note(""""kind":"hypothesis","text":"rounding happens in total()"""")),
            tool.run(note(""""kind":"hypothesis","text":"total() rounds half-even","evidence":"#12"""")),
            tool.run(note(""""kind":"decision","text":"round once, at the end"""")),
            tool.run(note(""""kind":"deadend","text":"patching the CLI alone","evidence":"#14"""")),
            tool.run(note(""""kind":"open","text":"does the refund path round too?"""")),
            tool.run(note(""""kind":"amend","text":"the refund path is out of scope"""")),
        )

        assertEquals(
            listOf("h1", "v2", "d1", "dead1", "o1", "a1").mapIndexed { i, id -> "STATE v${i + 1} · note $id recorded" },
            results.map { it.body.substringBefore(" · register") },
        )
        assertTrue(results.all { it.applied && it.header!!.runtime.status == "ok" })
        val r = tool.register
        assertEquals(io.astrolabe.evidence.Anchor("src/money.py", read), r.fact(2)!!.anchor, "evidence of exactly one file anchors the verified note")
        assertEquals(ClaimKind.Verified, r.fact(2)!!.kind)
        assertEquals("" to "task", r.decisions.single().because to r.deadEnds.single().scope)
        assertEquals("new evidence", r.deadEnds.single().reopen)
        val anchor = Anchor.renderDirect(estimator, "── CONTRACT v1 (S0) ──", r, "").text
        assertTrue(
            anchor.contains(
                "── Notes (STATE v6)\n            o1 does the refund path round too?\n            dead1 patching the CLI alone [#14]\n" +
                    "            d1 round once, at the end\n            a1 the refund path is out of scope (pending)\n" +
                    "            h1 rounding happens in total()\n            v2 total() rounds half-even [#12]\n",
            ),
            anchor,
        )
        assertFalse(anchor.contains("# STATE") || anchor.contains("## Next"), anchor)
    }

    @Test
    fun `an amend note records one pending amendment and its line keeps the id`() = runTest {
        val tool = direct()
        tool.run(note(""""kind":"amend","text":"drop the retry path""""))
        tool.run(note(""""kind":"amend","text":" drop the retry path ","evidence":"#12""""))

        val pending = contracts.current(ids.work)!!.amendmentsPending
        assertEquals(1, pending.size, "the same trimmed change is reused, not duplicated")
        assertEquals("drop the retry path", pending.single().change)
        assertEquals(1, contracts.current(ids.work)!!.version, "a pending amendment changes no obligation")
        assertEquals(listOf(pending.single().id, pending.single().id), tool.register.amendments.map { it.id })
        assertEquals("stated in the change", tool.register.amendments.first().reason)
    }

    @Test
    fun `a field its kind does not take refuses the note when it changes the meaning and is named otherwise`() = runTest {
        val tool = direct()
        val closes = tool.run(note(""""kind":"decision","text":"x","closes":1"""))
        assertEquals("rejected", closes.header!!.runtime.status)
        assertTrue(closes.body.startsWith("STATE v0 unchanged · rejected: schema — closes is valid only with kind=open · register "), closes.body)
        assertEquals("schema", tool.lastRejection?.rule)
        val refutes = tool.run(note(""""kind":"open","text":"x","refutes":1"""))
        assertTrue(refutes.body.contains("rejected: schema — refutes is valid only with kind=deadend"), refutes.body)
        val kind = tool.run(note(""""kind":"fact","text":"x""""))
        assertTrue(kind.body.contains("rejected: schema — kind is one of hypothesis, decision, deadend, open, amend"), kind.body)

        val named = tool.run(note(""""kind":"decision","text":"keep the API","evidence":"#12","why":"because""""))
        assertTrue(named.applied, named.body)
        assertTrue(named.body.contains("note: ignored 'why'") && named.body.contains("note: evidence ignored with kind=decision"), named.body)
        assertNull(tool.lastRejection, "an applied note clears the gate's rejection record")

        tool.run(note(""""kind":"open","text":"is the cache stale?""""))
        val closed = tool.run(note(""""kind":"open","text":"answered","closes":"o1","evidence":"#14""""))
        assertTrue(closed.body.startsWith("STATE v3 · note o1 recorded") && closed.body.contains("note: text ignored with closes") && closed.body.contains("note: o1 closed"), closed.body)
        assertTrue(tool.register.openItem(1)!!.closed)
    }

    @Test
    fun `a refutation checks the fact and the evidence before anything is recorded`() = runTest {
        val tool = direct()
        tool.run(note(""""kind":"hypothesis","text":"the cache is stale""""))

        val unknown = tool.run(note(""""kind":"deadend","text":"clearing the cache","refutes":7,"evidence":"#20""""))
        assertTrue(unknown.body.contains("rejected: unknown fact — fact.refute(7)"), unknown.body)
        val unproven = tool.run(note(""""kind":"deadend","text":"clearing the cache","refutes":1,"evidence":"#99""""))
        assertTrue(unproven.body.contains("rejected: refute needs an existing evidence id — fact.refute(1): #99"), unproven.body)
        assertTrue(tool.register.deadEnds.isEmpty(), "the whole note is refused: no dead end without its refutation")

        val refuted = tool.run(note(""""kind":"deadend","text":"clearing the cache","refutes":1,"evidence":"#20""""))
        assertTrue(refuted.body.startsWith("STATE v2 · note dead1 recorded") && refuted.body.contains("note: h1 refuted"), refuted.body)
        assertEquals(ClaimKind.Refuted, tool.register.fact(1)!!.kind)
        assertEquals("#20", tool.register.deadEnds.single().evidence)
    }

    @Test
    fun `the flat form is the nested note and a structured cell never executes one`() = runTest {
        val tool = direct()
        val flat = tool.run("""{"op":"note","kind":"open","text":"which caller passes ctx?"}""")
        assertTrue(flat.body.startsWith("STATE v1 · note o1 recorded"), flat.body)

        val structured = StateTool(Validator(estimator), InMemoryRegisterVersions(), null, estimator, idGen, ids, clock, Register.empty(ids.context!!, "I1", "fix rounding"))
        val masked = structured.run(note(""""kind":"open","text":"x""""))
        assertEquals("masked", masked.header!!.runtime.status, masked.body)
        assertEquals(0, structured.register.version)
    }

    @Test
    fun `a note refused for capacity ends the cell blocked only when the loop gate requires it`() = runTest {
        val start = Register.empty(ids.context!!, "I1", "fix rounding")
        val cap = RegisterRender.tokens(start.copy(version = 1), estimator).toInt()
        val tool = direct(registerCapTokens = cap)

        val refused = tool.run(note(""""kind":"decision","text":"a decision that cannot fit""""))
        assertTrue(
            refused.body.contains("rejected: register cap — ") &&
                refused.body.contains("tokens of active notes after archiving; retire notes first: closes: n ends an open note, refutes: n refutes a hypothesis"),
            refused.body,
        )
        assertNull(tool.pendingBlock, "the cell continues")

        tool.noteRequired = true
        val required = tool.run(note(""""kind":"decision","text":"a decision that cannot fit""""), turn = 2)
        val block = assertNotNull(tool.pendingBlock, required.body)
        assertTrue(block.reason.startsWith("register capacity: ") && block.reason.endsWith("tokens of active notes after archiving; the note the loop gate requires cannot be recorded"), block.reason)
        assertEquals(2, block.turn)
    }

    private fun capOneDecision(): Int {
        val start = Register.empty(ids.context!!, "I1", "fix rounding")
        return RegisterRender.tokens(start.copy(version = 1, decisions = listOf(io.astrolabe.register.Decision(1, "x", "", null))), estimator).toInt()
    }

    @Test
    fun `a required note recorded first keeps a later capacity refusal of the turn from ending the cell`() = runTest {
        val tool = direct(registerCapTokens = capOneDecision())
        tool.noteRequired = true
        assertTrue(tool.run(note(""""kind":"decision","text":"x"""")).applied)
        val later = tool.run(note(""""kind":"decision","text":"a second decision that cannot fit""""))
        assertTrue(later.body.contains("rejected: register cap"), later.body)
        assertNull(tool.pendingBlock, "the loop gate's note is recorded: the cell continues")
    }

    @Test
    fun `the model's own block survives a note applied after a capacity refusal`() = runTest {
        val tool = direct(registerCapTokens = capOneDecision())
        tool.noteRequired = true
        tool.run(note(""""kind":"decision","text":"a decision that cannot fit""""))
        assertNotNull(tool.pendingBlock)
        tool.run("""{"op":"blocked","blocked":{"reason":"the model stops here"}}""")
        assertTrue(tool.run(note(""""kind":"decision","text":"x"""")).applied)
        assertEquals("the model stops here", tool.pendingBlock?.reason)
    }

    @Test
    fun `a refutation whose dead end breaks a line rule records nothing`() = runTest {
        val tool = direct()
        tool.run(note(""""kind":"hypothesis","text":"the cache is stale""""))
        val before = tool.register
        val long = "x".repeat(700)
        val out = tool.run(note(""""kind":"deadend","text":"$long","refutes":1,"evidence":"#20""""))
        assertEquals("rejected", out.header!!.runtime.status, out.body)
        assertTrue(out.body.startsWith("STATE v1 unchanged · rejected: line ≤ 600 chars — "), out.body)
        assertEquals(before, tool.register, "no refutation without its dead end")
        assertEquals(ClaimKind.Hypothesis, tool.register.fact(1)!!.kind)
        assertEquals("line ≤ 600 chars", tool.lastRejection?.rule)
    }
}
