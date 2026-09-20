package io.astrolabe.tool.task

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Events
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.6.9 `task.ask`: a factual answer is evidence without a version bump, a requirement-changing answer amends, no answer ends the cell blocked. */
class TaskToolTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private lateinit var store: Store
    private lateinit var contracts: Contracts
    private val clock = FakeClock.at("2026-09-20T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("cell-1"))

    private class Answering(private val reply: (Question) -> Answer?) : Authority {
        override suspend fun ask(question: Question): Answer? = reply(question)
        override suspend fun approve(request: DClassRequest): Decision = error("unused")
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = error("unused")
        override suspend fun review(request: ReviewRequest): Verdict? = null
    }

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("README.md", "# fixture\n")
        repo.commit("initial")
        store = Store.open(stateRoot, repo.git, clock)
        contracts = Contracts(InMemoryContractRepository(), FixedIdGen(), clock)
        contracts.open(contracts.deriveS0(ids.work, ids.attempt, "fix rounding", Atlas.build(repo.root), Config(), Tokens(1_000)).contract)
    }

    @AfterTest
    fun tearDown() {
        store.close()
        repo.close()
    }

    private fun tool(authority: Authority, events: Events? = null) = TaskTool(authority, contracts, Journal(store, clock), HeuristicEstimator(), FixedIdGen(), ids, clock, events)

    private fun call(json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", "task", json))) as ParsedCalls.Valid).calls.single()

    private suspend fun ask(tool: TaskTool, json: String = """{"op":"ask","question":"round half-up or bankers?","options":["half-up","bankers"]}""", turn: Int = 2): ToolOutcome =
        tool.execute(call(json), TurnContext(turn, Workset().snapshot(), Reservations(Tokens(1_000))))

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `a factual answer is recorded as evidence and pinned without a version bump`() = runTest {
        Events().use { events ->
            val recorder = EventRecorder()
            events.subscribe(recorder)
            val tool = tool(Answering { q -> Answer(q.id, q.contractRevision, "", chosenOption = 0) }, events)
            val out = ask(tool)
            assertEquals("answered", status(out), out.body)
            assertTrue(out.body.startsWith("answered (factual, recorded as evidence #event 1; contract stays v1): half-up"), out.body)
            assertEquals(1, contracts.current(ids.work)!!.version, "a factual answer never bumps the version")
            val pinned = tool.asked.single()
            assertEquals("round half-up or bankers?", pinned.question.text)
            assertEquals(2, pinned.turn)
            assertNull(pinned.amendedToVersion)
            val event = Journal(store, clock).get(assertNotNull(pinned.evidenceEventId))!!
            assertTrue(event.text.startsWith("answer to q-1 (round half-up or bankers?): half-up"), event.text)
            assertEquals(1, Journal(store, clock).search("half-up", JournalScope(ids.work)).events.size)
            assertNull(tool.pendingBlock)
            recorder.awaitCount(2)
            assertEquals(listOf("Question", "Answered"), recorder.events.map { it::class.simpleName })
            assertEquals(false, (recorder.events[1] as AgentEvent.Ask.Answered).changesRequirements)
        }
    }

    @Test
    fun `an answer that changes requirements is a user amendment, appended verbatim with a version bump`() = runTest {
        val tool = tool(Answering { q -> Answer(q.id, q.contractRevision, "Use bankers rounding everywhere, including reports.", changesRequirements = true) })
        val out = ask(tool)
        assertEquals("answered", status(out), out.body)
        assertTrue(out.body.startsWith("answered (amends the contract → v2): Use bankers rounding everywhere"), out.body)
        val contract = contracts.current(ids.work)!!
        assertEquals(2, contract.version)
        assertEquals("Use bankers rounding everywhere, including reports.", contract.requests.last().text, "the authority is the message, stored verbatim")
        assertEquals(2, tool.asked.single().amendedToVersion)
        assertNull(tool.asked.single().evidenceEventId)
        assertTrue(Journal(store, clock).search("bankers", JournalScope(ids.work)).events.isEmpty(), "an amendment is the contract's record, not evidence")
    }

    @Test
    fun `no answer, a superseded reply or an empty one ends the cell blocked with the question`() = runTest {
        val autonomous = tool(AutonomousAuthority())
        val blocked = ask(autonomous)
        assertEquals("blocked", status(blocked))
        assertTrue(blocked.body.startsWith("blocked with a question (no answer is available): round half-up or bankers? · options: half-up | bankers"), blocked.body)
        assertEquals("round half-up or bankers?", autonomous.pendingBlock!!.question)
        assertEquals(2, autonomous.pendingBlock!!.turn)
        assertNull(autonomous.asked.single().answer)
        assertEquals(1, contracts.current(ids.work)!!.version)

        val stale = tool(Answering { q -> Answer(q.id, q.contractRevision - 1, "half-up") })
        assertEquals("blocked", status(ask(stale)))
        assertTrue(stale.pendingBlock!!.reason.contains("superseded by v1"), stale.pendingBlock!!.reason)

        val empty = tool(Answering { q -> Answer(q.id, q.contractRevision, "   ") })
        assertEquals("blocked", status(ask(empty)))
        assertTrue(empty.pendingBlock!!.reason.contains("the answer is empty"))
    }

    @Test
    fun `delegate, collect and propose are masked in S0`() = runTest {
        val tool = tool(AutonomousAuthority())
        assertEquals("masked", status(ask(tool, """{"op":"delegate","kind":"probe"}""")))
        assertEquals("masked", status(ask(tool, """{"op":"propose","proposal":{"x":1}}""")))
        assertTrue(tool.asked.isEmpty())
    }
}
