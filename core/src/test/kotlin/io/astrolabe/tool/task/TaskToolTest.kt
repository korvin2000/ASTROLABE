package io.astrolabe.tool.task

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.contract.Contracts
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.PacketCost
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Shape
import io.astrolabe.delegate.ChildOutcome
import io.astrolabe.delegate.ChildPacket
import io.astrolabe.delegate.ChildRunner
import io.astrolabe.delegate.DelegationLimits
import io.astrolabe.delegate.Delegator
import io.astrolabe.delegate.Finding
import io.astrolabe.delegate.ClaimKind
import io.astrolabe.delegate.InvestigationPacket
import io.astrolabe.delegate.Searched
import io.astrolabe.delegate.TaskPacket
import io.astrolabe.delegate.TaskPackets
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.WorkspaceId
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.ToolOps
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
    fun `lease expiry while awaiting an answer prevents its contract amendment`() = runTest {
        val before = contracts.current(ids.work)
        var live = true
        val tool = tool(Answering { question ->
            live = false
            Answer(question.id, question.contractRevision, "Change rounding everywhere.", changesRequirements = true)
        })
        tool.beforeDispatch = { check(live) { "dispatch lease expired" } }

        try {
            ask(tool)
        } catch (refused: IllegalStateException) {
            assertEquals("dispatch lease expired", refused.message)
        }

        assertEquals(false, live, "the authority must have returned its answer")
        assertEquals(before, contracts.current(ids.work), "the expired cell cannot commit a new contract revision")
        assertTrue(tool.asked.none { it.amendedToVersion != null })
    }

    @Test
    fun `wrong question and superseded answers do not amend the contract`() = runTest {
        val wrong = tool(Answering { q -> Answer("another-question", q.contractRevision, "new requirement", changesRequirements = true) })
        assertEquals("blocked", status(ask(wrong)))
        assertEquals(1, contracts.current(ids.work)!!.version)
        val late = tool(Answering { q ->
            contracts.amendByUser(ids.work, "new user requirement")
            Answer(q.id, q.contractRevision, "old answer", changesRequirements = true)
        })
        assertEquals("blocked", status(ask(late)))
        assertEquals(2, contracts.current(ids.work)!!.version)
        assertTrue(late.asked.none { it.answer?.text == "old answer" })
    }

    @Test
    fun `finish is a request the harness decides after the turn, once per turn, and masked outside the direct mask`() = runTest {
        val direct = TaskTool(AutonomousAuthority(), contracts, Journal(store, clock), HeuristicEstimator(), FixedIdGen(), ids, clock, mask = io.astrolabe.cell.Roles.direct.toolMask)
        val context = TurnContext(3, Workset().snapshot(), Reservations(Tokens(1_000)))
        val first = direct.execute(call("""{"op":"finish","text":"  a returns 10  ","after_checks":true}"""), context)
        assertEquals("requested", status(first))
        assertEquals("finish requested: the harness decides after this turn", first.body)
        val second = direct.execute(call("""{"op":"finish"}"""), context)
        assertEquals("duplicate finish ignored", second.body)
        assertNull(direct.takeFinish(4), "a request belongs to its own turn")
        direct.execute(call("""{"op":"finish","text":"a returns 10"}"""), TurnContext(5, Workset().snapshot(), Reservations(Tokens(1_000))))
        val taken = assertNotNull(direct.takeFinish(5))
        assertEquals("a returns 10", taken.text)
        assertNull(direct.takeFinish(5), "taken once")

        val structured = tool(AutonomousAuthority()).execute(call("""{"op":"finish"}"""), context)
        assertEquals("masked", status(structured), structured.body)
    }

    @Test
    fun `a refused answer advises a direct cell to finish through the finish request`() = runTest {
        val direct = TaskTool(AutonomousAuthority(), contracts, Journal(store, clock), HeuristicEstimator(), FixedIdGen(), ids, clock, mask = io.astrolabe.cell.Roles.direct.toolMask,
            answerCheck = { "the tree moved since the task opened" })
        direct.protocol = io.astrolabe.cell.Protocol.Direct
        val refused = direct.execute(call("""{"op":"answer","text":"nothing to change"}"""), TurnContext(1, Workset().snapshot(), Reservations(Tokens(1_000))))
        assertEquals("denied", status(refused))
        assertTrue(refused.body.endsWith("finish the work, then call task(finish)"), refused.body)
    }

    private val stamp = CandidateId(Digest.ofUtf8("s0"))

    /** A scripted probe child: it records the packet it got and answers with one finding over nothing it was shown. */
    private class ScriptedProbe : ChildRunner {
        val packets = ArrayList<TaskPacket>()
        override suspend fun run(child: io.astrolabe.delegate.ChildRun): ChildOutcome {
            packets += child.packet
            val packet = InvestigationPacket(child.ids, child.packet.incrementId, child.packet.contractVersion, child.packet.executionGeneration, child.packet.base, emptyMap(), listOf(Finding("nothing rounds yet", ClaimKind.Inferred, emptyList())), Searched(listOf("src/"), true, null), emptyList(), PacketCost())
            return ChildOutcome.Published(ChildPacket.Investigation(packet), Tokens(120))
        }
    }

    private fun delegating(runner: ChildRunner, scope: kotlinx.coroutines.CoroutineScope): TaskTool {
        val idGen = FixedIdGen()
        val delegator = Delegator(runner, { null }, Cancellation(), DelegationLimits(Tokens(10_000)), Shape.S2, scope, idGen, clock)
        val packets = TaskPackets(WorkspaceId("ws-main"), Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal), ExecutionGeneration.INITIAL)
        return TaskTool(AutonomousAuthority(), contracts, Journal(store, clock), HeuristicEstimator(), idGen, ids.withCandidate(stamp), clock, null, ToolMask(ToolOps.all), null, delegator, packets)
    }

    @Test
    fun `delegate assembles exact contract excerpts into the packet and collect reports the child's packet`() = runTest {
        val probe = ScriptedProbe()
        val tool = delegating(probe, this)
        val out = ask(tool, """{"op":"delegate","kind":"probe","mode":"sync","packet":{"increment":"I1","requirements":["R1"],"uncertainties":["where is rounding applied?"],"readScope":["src/"],"budgetTokens":800}}""")
        assertEquals("collected", status(out), out.body)
        assertTrue(out.body.startsWith("dispatched probe child-1 (sync, 1 excerpts, 800 tokens reserved); child-1 published a probe packet (120 tokens)"), out.body)
        val packet = probe.packets.single()
        val requirement = contracts.current(ids.work)!!.requirements.single()
        assertEquals(requirement.text, packet.requirements.single().text, "the child gets the contract's exact text, never the model's words")
        assertEquals(requirement.authorityRef, packet.requirements.single().authorityRef)
        assertEquals(1, packet.contractVersion)
        assertEquals(stamp, packet.dispatchCandidate)
        assertEquals(listOf("where is rounding applied?"), packet.uncertainties)
        assertTrue(packet.writeScope.isEmpty())

        val unknown = ask(tool, """{"op":"delegate","kind":"probe","packet":{"increment":"I1","requirements":["R9"],"budgetTokens":800}}""")
        assertEquals("rejected", status(unknown))
        assertTrue(unknown.body.contains("unknown requirement 'R9' in contract v1"), unknown.body)
        val writing = ask(tool, """{"op":"delegate","kind":"probe","packet":{"increment":"I1","requirements":["R1"],"writeScope":["src/a.kt"],"budgetTokens":800}}""")
        assertTrue(writing.body.contains("a probe is read-only"), writing.body)
        val writer = ask(tool, """{"op":"delegate","kind":"writer","packet":{"increment":"I1","requirements":["R1"],"writeScope":["src/a.kt"],"budgetTokens":800}}""")
        assertEquals("rejected", status(writer))
        assertTrue(writer.body.contains("refused (shape): writers are dispatched in S3 only"), writer.body)
        val collected = ask(tool, """{"op":"collect","handle":"child-1"}""")
        assertEquals("collected", status(collected), collected.body)
        val foreign = ask(tool, """{"op":"collect","handle":"child-7"}""")
        assertEquals("rejected", status(foreign), foreign.body)
    }

    @Test
    fun `without a delegator, delegate and collect are masked`() = runTest {
        val tool = TaskTool(AutonomousAuthority(), contracts, null, HeuristicEstimator(), FixedIdGen(), ids, clock, null, ToolMask(ToolOps.all))
        assertEquals("masked", status(ask(tool, """{"op":"delegate","kind":"probe","packet":{"increment":"I1","requirements":["R1"],"budgetTokens":10}}""")))
        assertEquals("masked", status(ask(tool, """{"op":"collect","handle":"child-1"}""")))
        val s0 = TaskTool(AutonomousAuthority(), contracts, null, HeuristicEstimator(), FixedIdGen(), ids, clock)
        assertEquals("masked", status(ask(s0, """{"op":"delegate","kind":"probe","packet":{"increment":"I1","requirements":["R1"],"budgetTokens":10}}""")))
    }

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
        // P1 #5 (task-workflow §2.4 B, D-317): an amending answer is the question's answer and derives its requirement, so its work has a scope.
        val request = contract.requests.last()
        assertEquals(io.astrolabe.contract.MessageKind.Amendment to tool.asked.single().question.id, request.kind to request.answers)
        assertEquals(request.text, contract.requirements.singleOrNull { it.authorityRef == request.id }?.text, "the answer derives its requirement: ${contract.requirements}")
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
        assertEquals("masked", status(ask(tool, """{"op":"propose","kind":"plan","proposal":{"x":1}}""")))
        assertTrue(tool.asked.isEmpty())
    }

    @Test
    fun `the model's goal criterion is an addition recorded at once in auto mode, never a weakening, and grants no launch`() = runTest {
        val auto = autonomous()
        val proposing = TaskTool(AutonomousAuthority(), contracts, Journal(store, clock), HeuristicEstimator(), FixedIdGen(), auto, clock, mask = ToolMask(ToolOps.all))
        val before = contracts.current(auto.work)!!
        val out = ask(proposing, """{"op":"propose","kind":"acceptance","proposal":{"strengthens":"R1","run":"python -m pytest tests/test_x.py"}}""")
        assertEquals("proposed", status(out), out.body)
        val after = contracts.current(auto.work)!!
        val item = after.acceptance.single() as io.astrolabe.contract.Acceptance.Run
        assertEquals(listOf("python", "-m", "pytest", "tests/test_x.py"), item.command.argv)
        assertEquals(io.astrolabe.contract.Origin.Model("R1") to io.astrolabe.contract.EvidencePurpose.Goal, item.origin to item.evidencePurpose)
        assertEquals(before.version, after.version, "a strengthening is no revision")
        assertTrue(after.amendmentsPending.isEmpty(), "WD-21: no amendment, so nothing an auto policy rejects as a weakening")
        assertTrue("verify does not launch a model-added command (D-262)" in out.body, out.body)
        assertEquals("rejected", status(ask(proposing, """{"op":"propose","kind":"acceptance","proposal":{"strengthens":"R9","run":"pytest"}}""")), "an unknown requirement")
    }

    @Test
    fun `the model's output proposal is refused in auto mode with the policy named, recorded when the policy allows, and a question in ask mode`() = runTest {
        val declared = ArrayList<String>()
        val autonomous = autonomous()
        fun tool(authority: io.astrolabe.event.Authority, auto: Boolean, at: Identities = autonomous) = TaskTool(authority, contracts, Journal(store, clock), HeuristicEstimator(), FixedIdGen(), at, clock,
            mask = ToolMask(ToolOps.all), declareOutput = { path, _ -> declared += path; null }, autoDeclareOutputs = auto)
        val json = """{"op":"propose","kind":"output","proposal":{"path":"reports/","reason":"the report the task writes"}}"""
        val refused = ask(tool(AutonomousAuthority(), auto = false), json)
        assertEquals("rejected", status(refused))
        assertTrue("autoDeclareOutputs" in refused.body, refused.body)
        assertEquals(emptyList(), declared)
        assertEquals("proposed", status(ask(tool(AutonomousAuthority(), auto = true), json)))
        assertEquals(listOf("reports/"), declared)

        val interactive = ids // the derived contract's default mode
        val asked = ArrayList<Question>()
        val no = ask(tool(Answering { q -> asked += q; Answer(q.id, q.contractRevision, "keep it", chosenOption = 1) }, auto = false, at = interactive), json)
        assertEquals("rejected", status(no))
        assertTrue(asked.single().text.contains("reports/") && asked.single().text.contains("the report the task writes"), asked.single().text)
        assertEquals("proposed", status(ask(tool(Answering { q -> Answer(q.id, q.contractRevision, "", chosenOption = 0) }, auto = false, at = interactive), json)))
        assertEquals(listOf("reports/", "reports/"), declared)
    }

    /** A copy of the derived contract in autonomous mode, as work `W-A`. */
    private fun autonomous(): Identities {
        contracts.open(contracts.current(ids.work)!!.copy(workId = WorkId("W-A"), mode = io.astrolabe.Mode.Autonomous))
        return Identities(WorkId("W-A"), AttemptId("a1"), context = ContextId("cell-1"))
    }
}
