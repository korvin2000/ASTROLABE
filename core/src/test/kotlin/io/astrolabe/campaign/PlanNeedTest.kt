package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.PlanCellPolicy
import io.astrolabe.ShapePolicy
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.contract.UserRequest
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Text
import io.astrolabe.provider.ToolResult
import io.astrolabe.route.RoutingFunction
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Defaults for tests that exercise the plan cell over a run-only S1 contract, which `WhenNeeded` would skip. */
internal val alwaysPlan: Defaults = Defaults(shapePolicy = ShapePolicy(planCell = PlanCellPolicy.Always))

/** §4.2 `planCell = WhenNeeded`: no plan cell when the contract already is the plan; the plan role's runs are R-class only. */
class PlanNeedTest {
    private val run = Command(listOf("./gradlew.bat", "test"))

    private fun contract(
        shape: Shape = Shape.S1,
        requirements: List<Requirement> = listOf(Requirement("R1", "the project's tests pass with the bundled JDK and Gradle", listOf("AC-1", "AC-2", "AC-3"), authorityRef = "U1")),
        acceptance: List<Acceptance> = listOf(Acceptance.Run("AC-1", run, Origin.User), Acceptance.Run("AC-2", run, Origin.User), Acceptance.Run("AC-3", run, Origin.User)),
        touched: List<String> = emptyList(),
    ) = Contract(
        WorkId("W-need"), 1, AttemptId("a1"), Mode.Autonomous, shape,
        listOf(UserRequest("U1", Instant.EPOCH, "verify the project with the bundled JDK and Gradle")),
        requirements, acceptance, emptyList(), emptyList(), touched, Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "local"),
    )

    @Test
    fun `an S1 contract with executable acceptance only is its own plan`() {
        val c = contract()
        assertEquals(ShapeSelector.single(c), PlanNeed.trivialGraph(c, emptyMap()))
        assertEquals("acceptance is executable (AC-1, AC-2, AC-3); no review items; no contracts touched", PlanNeed.reason(c))
    }

    @Test
    fun `a review item, a touched contract or a shape other than S1 keeps the plan cell`() {
        val review = contract(
            requirements = listOf(Requirement("R1", "the project's tests pass", listOf("AC-1", "AC-R"), authorityRef = "U1")),
            acceptance = listOf(Acceptance.Run("AC-1", run, Origin.User), Acceptance.Review("AC-R", "a maintainer approves", Origin.User)),
        )
        assertNull(PlanNeed.trivialGraph(review, emptyMap()))
        assertNull(PlanNeed.trivialGraph(contract(touched = listOf("CON-api")), emptyMap()))
        assertNull(PlanNeed.trivialGraph(contract(shape = Shape.S2), emptyMap()), "runS1 also serves S2, whose review paths the plan feeds")
        assertNull(PlanNeed.trivialGraph(contract(shape = Shape.S0), emptyMap()))
    }

    @Test
    fun `an S1 contract whose only acceptance is regression keeps the plan cell, and says why`() {
        val sniffed = contract(acceptance = listOf(Acceptance.Run("AC-1", run, Origin.Harness, scope = "touched"), Acceptance.Run("AC-2", run, Origin.Harness), Acceptance.Run("AC-3", run, Origin.Harness)))
        assertNull(PlanNeed.trivialGraph(sniffed, emptyMap()))
        assertEquals("acceptance is regression only (AC-1, AC-2, AC-3): the plan states a goal criterion", PlanNeed.regressionOnly(sniffed))
        assertNull(PlanNeed.regressionOnly(contract()), "user items are goal acceptance")
    }

    @Test
    fun `the plan validator decides where graph validation alone would admit G_single`() {
        val refactor = contract(requirements = listOf(Requirement("R1", "rename the parser module and keep its tests green", listOf("AC-1", "AC-2", "AC-3"), authorityRef = "U1")))
        assertEquals(emptyList(), ShapeSelector.single(refactor).validate(refactor))
        assertTrue(PlanPacketValidator.refactorGaps(refactor, PlanPacket(ShapeSelector.single(refactor))).isNotEmpty())
        assertNull(PlanNeed.trivialGraph(refactor, emptyMap()), "refactor mode needs the plan's §8.9 checklist")

        val unjudged = contract(requirements = listOf(
            Requirement("R1", "the project's tests pass", listOf("AC-1", "AC-2", "AC-3"), authorityRef = "U1"),
            Requirement("R2", "the build output is tidy", emptyList(), authorityRef = "U1"),
        ))
        assertEquals(emptyList(), ShapeSelector.single(unjudged).validate(unjudged))
        assertNull(PlanNeed.trivialGraph(unjudged, emptyMap()), "a requirement without acceptance of its own needs the plan to name its oracle")
    }

    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-30T10:00:00Z")
    private val idGen = FixedIdGen()
    private val policy = CampaignPolicy(Tokens(400_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    /** An S1 contract of two requirements over two run acceptances, plus [extra] acceptance owned by R2; a `review:` item opens it at S2 (§3.5). */
    private fun seed(work: String, vararg extra: Acceptance): CampaignRequest {
        val request = CampaignRequest(WorkId(work), AttemptId("a1"), "make a return 10 and b return 20")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(
                derived.copy(
                    shape = if (extra.any { it is Acceptance.Review }) Shape.S2 else Shape.S1,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "b returns 20", listOf("AC-2") + extra.map { it.id }, authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)) + extra,
                ),
            )
        }
        return request
    }

    private fun controller(events: Events, planCell: PlanCellPolicy = PlanCellPolicy.WhenNeeded) = Controller(
        Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = Defaults(shapePolicy = ShapePolicy(planCell = planCell))),
        clock, idGen, events,
    )

    private fun EventRecorder.startedRoles(events: Events): List<String> {
        assertTrue(awaitCount(events.lastSeq.toInt()))
        return ofType<AgentEvent.Cell.Started>().map { it.role }
    }

    private fun skipped(c: OpenedCampaign): List<String> =
        c.journal.events(JournalScope(c.ids.work, kinds = setOf(JournalKind.Boundary))).map { it.text }.filter { it.startsWith("plan cell skipped") }

    /** [replies] in order; [act] runs just before the reply at index [at] is returned. */
    private fun acting(replies: List<Scripted>, at: Int, act: () -> Unit) =
        FakeAdapter(ScriptedModel(replies.mapIndexed { i, r -> ScriptedModel.Turn({ true }, { if (i == at) act(); r }) }))

    @Test
    fun `a run-only S1 contract opens on the single increment without a plan cell`() = runBlocking<Unit> {
        val request = seed("W-skip")
        Events(clock).use { events ->
            val recorder = EventRecorder().also(events::subscribe)
            val ctl = controller(events)
            ctl.open(repo.root, request, policy).use { c ->
                val va = c.registry.version("src/a.py")!!
                val vb = c.registry.version("src/b.py")!!
                val adapter = FakeAdapter(ScriptedModel.of(
                    Scripted.Reply(listOf<Item>(say("reading"), read("r-a", "src/a.py"), read("r-b", "src/b.py"))),
                    Scripted.Reply(listOf<Item>(say("editing"), anchored("e-a", "src/a.py", va, "    return 1", "    return 10"), anchored("e-b", "src/b.py", vb, "    return 2", "    return 20"))),
                    Scripted.Reply(listOf<Item>(say("verifying"), call("v", "verify", """{"what":"acceptance","ids":["AC-1","AC-2"]}"""))),
                    Scripted.Reply(listOf<Item>(say("done"))),
                ))
                val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                assertEquals(listOf("implementing"), recorder.startedRoles(events), "no plan cell started")
                assertTrue(adapter.calls.first().request.mask!!.allows("edit.anchored"), "the first model call is the implementing cell's")
                assertEquals(listOf(RoutingFunction.Implementing), ctl.router.calibration.entries().map { it.function })
                assertEquals(listOf("plan cell skipped: acceptance is executable (AC-1, AC-2); no review items; no contracts touched; single increment inc-1"), skipped(c))
                val state = c.campaigns.load(request.work, request.attempt)!!
                assertEquals(listOf(ShapeSelector.SINGLE), state.graph.increments.map { it.id })
                assertEquals(IncrementStatus.Verified, state.graph.increments.single().status)
                assertEquals(listOf(ShapeSelector.SINGLE), state.cells.map { it.increment })
                assertNull(SqlitePlanProposals(c.store, idGen, clock).latest(request.work, null), "no plan is stored, as in S0")
                assertEquals("def b():\n    return 20\n", Files.readString(repo.root.resolve("src/b.py")))
            }
        }
    }

    @Test
    fun `a review item keeps the plan cell`() = runBlocking<Unit> {
        val request = seed("W-review", Acceptance.Review("AC-R", "a maintainer approves the wording", Origin.User))
        Events(clock).use { events ->
            val recorder = EventRecorder().also(events::subscribe)
            val ctl = controller(events)
            ctl.open(repo.root, request, policy).use { c ->
                ctl.run(c, CellModel(acting(listOf(Scripted.Reply(listOf(say("planning")))), 0) { c.cancellation.cancel("test stop") }, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(listOf("plan"), recorder.startedRoles(events))
                assertEquals(emptyList(), skipped(c))
            }
        }
    }

    @Test
    fun `planCell Always runs the plan cell for a run-only contract`() = runBlocking<Unit> {
        val request = seed("W-always")
        Events(clock).use { events ->
            val recorder = EventRecorder().also(events::subscribe)
            val ctl = controller(events, PlanCellPolicy.Always)
            ctl.open(repo.root, request, policy).use { c ->
                ctl.run(c, CellModel(acting(listOf(Scripted.Reply(listOf(say("planning")))), 0) { c.cancellation.cancel("test stop") }, FakeProfiles.main, HeuristicEstimator()))
                assertEquals(listOf("plan"), recorder.startedRoles(events))
                assertEquals(emptyList(), skipped(c))
            }
        }
    }

    @Test
    fun `the plan cell runs R-class commands and is refused W-class ones before dispatch`() = runBlocking<Unit> {
        val request = seed("W-plan-runs")
        Events(clock).use { events ->
            val ctl = controller(events, PlanCellPolicy.Always)
            ctl.open(repo.root, request, policy).use { c ->
                val replies = listOf(
                    Scripted.Reply(listOf(
                        say("looking at the build"),
                        call("r1", "run", """{"argv":["git","status","--short"]}"""),
                        call("w1", "run", """{"argv":["./gradlew","test"]}"""),
                    )),
                    Scripted.Reply(listOf(say("planning"))),
                )
                val adapter = acting(replies, 1) { c.cancellation.cancel("test stop") }
                ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
                val plan = adapter.calls.first().request
                assertTrue(plan.mask!!.allows("run.run") && plan.mask!!.allows("task.propose") && !plan.mask!!.allows("edit.anchored"))
                val results = adapter.calls[1].request.segments.flatMap { it.items }.filterIsInstance<ToolResult>()
                    .map { r -> r.content.filterIsInstance<Text>().joinToString("") { it.text } }
                val refused = assertNotNull(results.singleOrNull { "R-class commands only" in it }, results.toString())
                assertTrue("denied: the plan role runs R-class commands only; this command is W-class" in refused && "nothing was dispatched" in refused, refused)
                assertTrue(results.any { "exit code 0" in it && "denied" !in it }, results.toString())
                assertTrue(c.intents.open().none { it.argv.first() == "./gradlew" })
            }
        }
    }
}
