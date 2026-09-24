package io.astrolabe.campaign

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.NoteCandidate
import io.astrolabe.cell.PacketKind
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.RoleCompletion
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Increment
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
import io.astrolabe.event.ResolutionOutcome
import io.astrolabe.verify.RefactorChecklist
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.graph.Production
import io.astrolabe.graph.RequirementGraph
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.store.Store
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.provider.ToolMask
import io.astrolabe.register.Register
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.workset.Workset
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2.1.2: the plan cell's packet validator and the controller's intake of its proposal (§3.4, §4.1, §4.2, D17). */
class PlanTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val work = WorkId("W-plan")
    private val cell = ContextId("plan-1")

    /** R1 has a runnable oracle; R2 ("the message reads well") has none a command can decide. */
    private fun contract(mode: Mode) = Contract(
        work, 1, AttemptId("a1"), mode, Shape.S1,
        listOf(UserRequest("U1", Instant.EPOCH, "Fix the parser and make its error message readable")),
        listOf(
            Requirement("R1", "the parser accepts trailing commas", listOf("AC1"), authorityRef = "U1"),
            Requirement("R2", "the error message reads well", emptyList(), authorityRef = "U1"),
        ),
        listOf(Acceptance.Run("AC1", Command(listOf("pytest", "tests/test_parser.py")), Origin.User)),
        emptyList(), emptyList(), emptyList(), Scope(listOf("src/", "tests/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    private val review = AcceptanceProposal(Acceptance.Review("AC-R2", "a maintainer judges the message readable", Origin.Model("R2")))
    private val extra = AcceptanceProposal(Acceptance.Run("AC-R1b", Command(listOf("pytest", "-k", "comma")), Origin.Model("R1")))

    private fun increment(id: String, requirement: String, accept: List<String>, vararg dependsOn: String, produces: Production? = Production.Artifact) =
        Increment(id, listOf(requirement), accept, listOf("src/"), 1, title = "do $id", dependsOn = dependsOn.toList(), produces = produces)

    private fun plan(vararg proposals: AcceptanceProposal = arrayOf(review, extra)) = PlanPacket(
        RequirementGraph(
            listOf(increment("I1", "R1", listOf("AC1")), increment("I2", "R2", listOf("AC1", "AC-R2"), "I1")),
            ownershipMap = mapOf("src/parser.py" to "I1"),
        ),
        acceptanceProposals = proposals.toList(),
        conCandidates = listOf(NoteCandidate("CON", "parser error contract", "src/parser.py", emptyList(), emptyList())),
        shapeSuggestion = Shape.S1,
    )

    private fun authority(accept: Set<String>) = object : Authority {
        override suspend fun ask(question: Question): Answer? = null
        override suspend fun approve(request: DClassRequest): Decision = Decision(request.id, request.contractRevision, false)
        override suspend fun resolve(proposal: AmendmentProposal): Resolution {
            val approved = accept.any { "acceptance $it " in proposal.change }
            return Resolution(proposal.id, proposal.contractRevision, if (approved) ResolutionOutcome.Accepted else ResolutionOutcome.Rejected, "human:alice")
        }
        override suspend fun review(request: ReviewRequest): Verdict? = null
    }

    private fun contracts(mode: Mode) = Contracts(InMemoryContractRepository(), FixedIdGen(), clock).also { it.open(contract(mode)) }

    @Test
    fun `the validator rejects think-more nodes, unjudged requirements and harness-owned fields`() {
        val c = contract(Mode.Autonomous)
        assertEquals(emptyList(), PlanPacketValidator.gaps(c, plan()))
        assertEquals(listOf("no plan proposed: end with task.propose(plan)"), PlanPacketValidator.gaps(c, null))

        val unjudged = PlanPacketValidator.gaps(c, plan(extra).copy(graphProposal = RequirementGraph(listOf(increment("I1", "R1", listOf("AC1"))))))
        assertTrue(unjudged.any { it.startsWith("UncoveredRequirement: uncovered requirement R2") }, unjudged.toString())
        assertTrue(unjudged.any { it.startsWith("R2 has no acceptance") && "review:" in it && "never an invented oracle" in it }, unjudged.toString())

        val thinkMore = plan().copy(graphProposal = RequirementGraph(listOf(
            increment("I1", "R1", listOf("AC1"), produces = null), increment("I2", "R2", listOf("AC1", "AC-R2")),
        )))
        assertEquals(listOf("MissingProduction I1: needs an artifact or named uncertainty"), PlanPacketValidator.gaps(c, thinkMore))

        val claimed = plan().copy(graphProposal = RequirementGraph(listOf(
            increment("I1", "R1", listOf("AC1")).copy(status = IncrementStatus.Verified), increment("I2", "R2", listOf("AC1", "AC-R2")),
        )))
        assertTrue(PlanPacketValidator.gaps(c, claimed).single().contains("status and cells are harness-owned"))

        val edits = plan(AcceptanceProposal(Acceptance.Run("AC1", Command(listOf("true")), Origin.Model("R1"))), review)
        assertTrue(PlanPacketValidator.gaps(c, edits).any { it.startsWith("acceptance AC1 already exists") })
        val blank = plan(AcceptanceProposal(Acceptance.Review("AC-R2", " ", Origin.Model("R2"))))
        assertTrue(PlanPacketValidator.gaps(c, blank).contains("review AC-R2 must name the judgment it needs"))
    }

    @Test
    fun `in refactor mode the validator requires the §8-9 checklist and a separated shared decision`() {
        val refactor = contract(Mode.Autonomous).let { c -> c.copy(requirements = c.requirements.map { r -> if (r.id == "R1") r.copy(text = "refactor the parser without changing what it accepts") else r }) }
        assertEquals(emptyList(), PlanPacketValidator.gaps(contract(Mode.Autonomous), plan()), "outside refactor mode nothing is asked")
        val missing = PlanPacketValidator.gaps(refactor, plan())
        assertEquals(1, missing.size, missing.toString())
        assertTrue(missing.single().startsWith("refactor mode (R1: behaviour-preserving requirement ('refactor')): record the §8.9 checklist"), missing.single())

        val checklist = RefactorChecklist("accepted inputs and error messages", "Parser.parse", "one release", "cli, api", "none", "golden CLI outputs", "CON parser contract")
        assertEquals(emptyList(), PlanPacketValidator.gaps(refactor, plan().copy(refactorChecklist = checklist)))
        assertEquals(listOf("refactor checklist: callers/consumers is blank"), PlanPacketValidator.gaps(refactor, plan().copy(refactorChecklist = checklist.copy(callersConsumers = ""))))
        val mechanicalOnly = plan().copy(refactorChecklist = checklist, conCandidates = emptyList())
        assertEquals(listOf("refactor mode: the shared decision 'CON parser contract' is a CON/ADR candidate or a decision packet, separate from the mechanical edits"), PlanPacketValidator.gaps(refactor, mechanicalOnly))
    }

    @Test
    fun `a plan cell ends only when its stored proposal validates`() = runBlocking<Unit> {
        CellFixture(stateRoot).use { f ->
            val proposals = InMemoryPlanProposals(FixedIdGen())
            val c = contract(Mode.Autonomous).copy(workId = f.ids.work, attemptId = f.ids.attempt)
            val completion = RoleCompletion.forRole(Roles.plan, mapOf(PacketKind.PlanArtifacts to PlanPacketValidator.completion({ c }, proposals)))
            val model = ScriptedModel.build {
                reply(say("the plan is ready"))
                on({ true }) { _ -> proposals.record(f.ids, plan()); Scripted.Reply(listOf(say("the plan is ready"))) }
            }

            val exit = assertIs<CellExit.Completed>(f.run(model, role = Roles.plan, completion = completion))

            assertEquals(PacketStatus.Done, exit.packet.status)
            assertEquals(listOf("no plan proposed: end with task.propose(plan)"), exit.packet.gaps, "the first proposal is refused with its gap")
            assertEquals(listOf(proposals.latest(f.ids.work, f.ids.context)!!.id), exit.packet.evidenceRefs)
            assertEquals("plan", exit.packet.role)
        }
    }

    @Test
    fun `autonomous intake freezes proposed acceptance as model origin and admits only a validated graph`() = runBlocking<Unit> {
        val contracts = contracts(Mode.Autonomous)
        val proposals = InMemoryPlanProposals(FixedIdGen())

        val refused = assertIs<PlanAdmission.Refused>(PlanIntake(contracts).admit(work, proposals.record(Identities(work, AttemptId("a1"), context = cell), plan(extra)), authority(emptySet())))
        assertTrue(refused.gaps.any { it.startsWith("R2 has no acceptance") })
        assertEquals(listOf("AC1"), contracts.current(work)!!.acceptance.map { it.id }, "a refused plan changes nothing")

        val stored = proposals.record(Identities(work, AttemptId("a1"), context = cell), plan())
        val admitted = assertIs<PlanAdmission.Admitted>(PlanIntake(contracts).admit(work, stored, authority(emptySet())))
        assertEquals(listOf("AC-R2", "AC-R1b"), admitted.frozen)
        assertEquals(1, admitted.contract.version, "freezing adds items without an amendment")
        assertEquals(Origin.Model("R2"), admitted.contract.acceptance("AC-R2")!!.origin)
        val state = Lifecycle.open(admitted.contract, admitted.graph)
        assertEquals(listOf("I1", "I2"), state.graph.increments.map { it.id })
    }

    @Test
    fun `interactive intake resolves each proposal through the authority before validating`() = runBlocking<Unit> {
        val ids = Identities(work, AttemptId("a1"), context = cell)
        val approving = contracts(Mode.Interactive)
        val stored = InMemoryPlanProposals(FixedIdGen()).record(ids, plan())
        val admitted = assertIs<PlanAdmission.Admitted>(PlanIntake(approving).admit(work, stored, authority(setOf("AC-R2"))))
        assertEquals(listOf("AC-R2"), admitted.approved)
        assertEquals(listOf("AC-R1b"), admitted.rejected)
        assertEquals(2, admitted.contract.version, "an approved item is an authorized amendment")
        assertEquals(2, admitted.contract.acceptance("AC-R2")!!.obligationVersion)
        assertNull(admitted.contract.acceptance("AC-R1b"))

        val rejecting = contracts(Mode.Interactive)
        val refused = assertIs<PlanAdmission.Refused>(PlanIntake(rejecting).admit(work, stored, authority(emptySet())))
        assertEquals(listOf("AC-R2", "AC-R1b"), refused.rejected)
        assertTrue(refused.gaps.any { it.startsWith("UnknownAcceptance I2") }, refused.gaps.toString())
        assertTrue(refused.gaps.any { it.startsWith("R2 has no acceptance") }, refused.gaps.toString())
    }

    @Test
    fun `plan proposals are stored as packets and read back per cell`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val proposals = SqlitePlanProposals(store, FixedIdGen(), clock)
                val first = proposals.record(Identities(work, AttemptId("a1"), context = cell), plan())
                val second = proposals.record(Identities(work, AttemptId("a1"), context = ContextId("plan-2")), plan(extra))
                assertEquals(first, proposals.latest(work, cell))
                assertEquals(second, proposals.latest(work, null))
                assertNull(proposals.latest(WorkId("W-other"), null))
            }
        }
    }

    private val wirePlan = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC1","AC-R2"],"write_scope":["src/"],"depends_on":["I1"],"produces":"artifact"}],
        "ownership":{"src/parser.py":"I1"},
        "acceptance":[{"id":"AC-R2","requirement":"R2","review":"a maintainer judges the message readable"}],
        "con":[{"summary":"parser error contract","scope":"src/parser.py"}],"shape":"S1"}"""

    private fun proposing(contracts: Contracts, plans: PlanProposals, splits: SplitRequests, register: Register?) = TaskTool(
        authority(emptySet()), contracts, null, HeuristicEstimator(), FixedIdGen(), Identities(work, AttemptId("a1"), context = cell), clock,
        mask = ToolMask(Roles.plan.toolMask.allowed), proposals = CampaignProposals(plans, splits, { contracts.current(work) }, { register }),
    )

    private suspend fun TaskTool.propose(kind: String, proposal: String): ToolOutcome {
        val call = (ToolCalls.parse(listOf(ProviderCall("c1", "task", """{"op":"propose","kind":"$kind","proposal":$proposal}"""))) as ParsedCalls.Valid).calls.single()
        return execute(call, TurnContext(1, Workset().snapshot(), Reservations(Tokens(1_000))))
    }

    @Test
    fun `task propose records a plan with the register's decisions and changes neither contract nor graph`() = runBlocking<Unit> {
        val contracts = contracts(Mode.Autonomous)
        val plans = InMemoryPlanProposals(FixedIdGen())
        val register = Register.empty(cell, "plan", "plan the parser work").copy(decisions = listOf(
            io.astrolabe.register.Decision(1, "split parser and messages", "they fail independently", "one increment", adrCandidate = true),
        ))
        val tool = proposing(contracts, plans, InMemorySplitRequests(FixedIdGen()), register)

        val out = tool.propose("plan", wirePlan)
        assertEquals("proposed", out.header!!.runtime.status, out.body)
        assertTrue("2 increments, 1 acceptance proposals; passes the controller's checks" in out.body, out.body)
        val stored = plans.latest(work, cell)!!.packet
        assertEquals(listOf("I1", "I2"), stored.graphProposal.increments.map { it.id })
        assertEquals(Origin.Model("R2"), stored.acceptanceProposals.single().item.origin)
        assertEquals(register.decisions, stored.decisionPackets)
        assertEquals(listOf("split parser and messages because they fail independently; rejected: one increment"), stored.adrCandidates.map { it.summary })
        assertEquals(contract(Mode.Autonomous), contracts.current(work), "a proposal never changes the contract")

        val bad = tool.propose("plan", """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC1"],"produces":"soon"}]}""")
        assertEquals("rejected", bad.header!!.runtime.status)
        assertTrue("produces must be artifact or resolves:<question>" in bad.body, bad.body)
        val gaps = tool.propose("plan", """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC1"],"produces":"artifact"}]}""")
        assertTrue("gaps: " in gaps.body && "R2 has no acceptance" in gaps.body, gaps.body)
    }

    @Test
    fun `a split proposal reaches the plan role as a packet and an amendment stays pending as a weakening`() = runBlocking<Unit> {
        val contracts = contracts(Mode.Autonomous)
        TempRepo.create().use { repo ->
            repo.write("a.txt", "a")
            repo.commit("initial")
            Store.open(stateRoot, repo.git, clock).use { store ->
                val splits = SqliteSplitRequests(store, FixedIdGen(), clock)
                val tool = proposing(contracts, InMemoryPlanProposals(FixedIdGen()), splits, null)
                val out = tool.propose("increment_split", """{"increment":"I2","reason":"message work is independent","parts":["wording","tests"]}""")
                assertEquals("proposed", out.header!!.runtime.status, out.body)
                assertEquals(listOf(IncrementSplit("I2", "message work is independent", listOf("wording", "tests"))), splits.forPlanRole(work).map { it.split })

                val amendment = tool.propose("amendment", """{"change":"drop R2","reason":"out of scope"}""")
                assertEquals("proposed", amendment.header!!.runtime.status, amendment.body)
                val pending = contracts.current(work)!!.amendmentsPending.single()
                assertTrue(pending.weakening, "a model amendment is never auto-accepted as non-weakening (D-69)")
                assertEquals(1, contracts.current(work)!!.version)
            }
        }
        val masked = TaskTool(authority(emptySet()), contracts, null, HeuristicEstimator(), FixedIdGen(), Identities(work, AttemptId("a1"), context = cell), clock)
        assertEquals("masked", masked.propose("plan", wirePlan).header!!.runtime.status, "S0 masks propose")
    }

    @Test
    fun `a cross-boundary interface change references a CON note - validated against the knowledge base, else a recorded planning gap`() {
        val refactor = contract(Mode.Autonomous).let { c -> c.copy(requirements = c.requirements.map { r -> if (r.id == "R1") r.copy(text = "refactor the parser without changing what it accepts") else r }) }
        val checklist = RefactorChecklist("accepted inputs", "Parser.parse signature", "one release", "cli, api", "none", "golden CLI outputs", "CON parser contract")
        val withCandidate = plan().copy(refactorChecklist = checklist)
        val mechanical = withCandidate.copy(conCandidates = emptyList(), decisionPackets = listOf(io.astrolabe.register.Decision(1, "keep the parser contract", "callers depend on it", null)))
        val anchors = mapOf("CON-parser" to setOf("src/parser.py"))

        // No CON notes in the knowledge base: nothing to validate against, so the missing reference is a planning gap, never a refusal.
        assertEquals(emptyList(), PlanPacketValidator.gaps(refactor, mechanical))
        val planning = PlanPacketValidator.planningGaps(refactor, mechanical)
        assertEquals(1, planning.size, planning.toString())
        assertTrue(planning.single().startsWith("planning gap (no CON notes in the knowledge base to validate against): cross-boundary interface change (interfaces to change 'Parser.parse signature') references no CON note"), planning.single())
        assertEquals(emptyList(), PlanPacketValidator.planningGaps(refactor, withCandidate), "a new CON candidate is the reference")
        assertEquals(emptyList(), PlanPacketValidator.planningGaps(refactor, mechanical.copy(refactorChecklist = checklist.copy(interfacesToChange = "none"))), "no interface change: no CON owed")
        assertEquals(emptyList(), PlanPacketValidator.planningGaps(contract(Mode.Autonomous), plan().copy(conCandidates = emptyList())), "outside refactor mode with no contracts touched, nothing is asked")

        // With CON notes in the knowledge base the reference is validated: it must exist, or a new candidate must be named.
        val missing = PlanPacketValidator.gaps(refactor, mechanical, anchors)
        assertEquals(1, missing.size, missing.toString())
        assertTrue(missing.single().contains("references no CON note"), missing.single())
        assertEquals(emptyList(), PlanPacketValidator.gaps(refactor, mechanical.copy(conReferences = listOf("CON-parser")), anchors), "a superseded CON by id")
        assertEquals(listOf("CON reference CON-nope is not a CON note of the knowledge base (known: CON-parser)"), PlanPacketValidator.gaps(refactor, mechanical.copy(conReferences = listOf("CON-nope")), anchors))
        assertEquals(emptyList(), PlanPacketValidator.gaps(refactor, withCandidate, anchors), "a new CON candidate satisfies the rule")
        // contractsTouched is the other cross-boundary signal, refactor mode or not.
        val touched = contract(Mode.Autonomous).copy(contractsTouched = listOf("CON-parser"))
        assertTrue(PlanPacketValidator.gaps(touched, plan().copy(conCandidates = emptyList()), anchors).single().contains("contracts touched CON-parser"))
    }
}
