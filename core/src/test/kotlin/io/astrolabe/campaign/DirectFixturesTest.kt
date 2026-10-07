package io.astrolabe.campaign

import io.astrolabe.BalanceProfile
import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.resultText
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.Layout
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.Roles
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Events
import io.astrolabe.event.Question
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
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolResult
import io.astrolabe.store.Store
import io.astrolabe.tool.task.TaskTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A-D.8 (D4): the direct fixtures that D1–D3 left uncovered, through the real assembly on the fake adapter — the
 * controller for S0, S1 and the frozen attempt, `CellFixture` for the cell rules. The items D1–D3 already cover are
 * listed in the line's report (`plan2/reports/WP-D4.md`) and not repeated here; with them the direct fixtures stay ≤ 15.
 */
class DirectFixturesTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-07T10:00:00Z")
    private val idGen = FixedIdGen()
    private val policy = CampaignPolicy(Tokens(400_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 10\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    /** Opens the contract of [request] in [shape]: two requirements whose run acceptances pass on the base tree. */
    private fun seed(request: CampaignRequest, shape: Shape) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(
                derived.copy(
                    shape = shape,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "a stays a function", listOf("AC-2"), authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                ),
            )
        }
    }

    private fun texts(request: Request, kind: SegmentKind): String =
        request.segment(kind)?.items?.filterIsInstance<Message>()?.joinToString("\n") { it.text }.orEmpty()

    private fun allTexts(request: Request): String = SegmentKind.entries.joinToString("\n") { texts(request, it) }

    @Test
    fun `DX-01 a direct S0 task runs through the controller from its first turn to a finish without a patch`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-dx01"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, protocol = Protocol.Direct), clock, idGen)
        controller.open(repo.root, request, policy).use { c ->
            assertEquals(Protocol.Direct, c.attempt.config.protocol)
            val adapter = FakeAdapter(ScriptedModel.of(
                Scripted.Reply(listOf(say("reading"), read("r1", "src/a.py"))),
                Scripted.Reply(listOf(say("a already returns 10"), call("f1", "task", """{"op":"finish","text":"nothing to change: a returns 10 on the base"}"""))),
            ))
            val run = controller.runS0(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            assertEquals(2, adapter.calls.size, "the plain proposal of the second turn completes the cell")
            for (r in adapter.calls.map { it.request }) {
                assertTrue(texts(r, SegmentKind.S).startsWith("astrolabe · role direct · kernel-direct/1 · "), texts(r, SegmentKind.S))
                assertFalse(r.mask!!.allows("task.propose"), "S0 masks propose")
            }
            assertEquals(1, c.state!!.cells.size)
            assertEquals("def a():\n    return 10\n", java.nio.file.Files.readString(repo.root.resolve("src/a.py")), "no patch: the tree is the base")
        }
    }

    @Test
    fun `DX-02 an attempt body frozen before the direct role reopens without config-frozen and gains the role`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-dx02"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S0)
        val recorder = EventRecorder()
        Events(clock).use { events ->
            events.subscribe(recorder)
            val controller = { Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen, events) }
            controller().open(repo.root, request, policy).use { }
            // The body as D1 found it: no `direct` among the frozen roles and their text versions.
            Store.open(stateRoot, repo.git, clock).use { store ->
                val body = store.db.query("SELECT body FROM attempts WHERE work_id = ? AND attempt_id = ?", request.work, request.attempt) { it.string("body") }.single()
                val json = Json.parseToJsonElement(body) as JsonObject
                val config = json.getValue("config") as JsonObject
                val roles = config.getValue("roles") as JsonObject
                assertTrue("direct" in roles, "the body frozen now names the direct role")
                val before = JsonObject(json + ("config" to JsonObject(config + ("roles" to JsonObject(roles - "direct")))) +
                    ("roleTextVersions" to JsonObject((json.getValue("roleTextVersions") as JsonObject) - "direct")))
                store.db.tx { it.execute("UPDATE attempts SET body = ? WHERE work_id = ? AND attempt_id = ?", before.toString(), request.work, request.attempt) }
            }
            controller().open(repo.root, request, policy).use { c ->
                assertEquals(Protocol.Structured, c.attempt.config.protocol)
                assertTrue(Roles.direct.name in c.attempt.config.roles, "the snapshot adds the default roles on both sides of the comparison")
                assertEquals(CampaignPhase.Running, c.state!!.phase)
            }
            // The control: a reopen asking for another balance profile does warn, so the barrier below sees delivery.
            controller().open(repo.root, request, CampaignPolicy(policy.tokens, balance = BalanceProfile.Economy)).use { }
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (recorder.ofType<AgentEvent.Warning>().none { it.kind == "config-frozen" } && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1, recorder.ofType<AgentEvent.Warning>().count { it.kind == "config-frozen" }, "only the control reopen warns")
    }

    @Test
    fun `DX-03 a split recorded by a direct S1 cell and its state blocked start a replan before the graph changes`() = runBlocking<Unit> {
        val request = CampaignRequest(WorkId("W-dx03"), AttemptId("a1"), "make a return 10")
        seed(request, Shape.S1)
        val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = alwaysPlan, protocol = Protocol.Direct), clock, idGen)
        controller.open(repo.root, request, policy).use { c ->
            val plan = """{"increments":[{"id":"I1","requirements":["R1","R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
            var plans = 0
            var graphAtReplan: List<String>? = null
            var replan: Request? = null
            val script = ScriptedModel(listOf(ScriptedModel.Turn({ true }, { r ->
                if (texts(r, SegmentKind.S).startsWith("astrolabe · role plan · ")) {
                    plans += 1
                    when (plans) {
                        1 -> Scripted.Reply(listOf(say("planning"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}""")))
                        2 -> Scripted.Reply(listOf(say("plan ready")))
                        else -> {
                            if (replan == null) { replan = r; graphAtReplan = c.state!!.graph.increments.map { it.id } }
                            Scripted.Reply(listOf(call("stop", "state", """{"op":"blocked","blocked":{"reason":"the fixture stops at the replan"}}""")))
                        }
                    }
                } else {
                    Scripted.Reply(listOf(
                        say("too large for one cell"),
                        call("split", "task", """{"op":"propose","kind":"increment_split","proposal":{"increment":"I1","reason":"separate the return value from its regression review","parts":["return value","regression review"]}}"""),
                        call("split-boundary", "state", """{"op":"blocked","blocked":{"reason":"waiting for increment split planning","evidence":[]}}"""),
                    ))
                }
            }, once = false)))
            val adapter = FakeAdapter(script)
            val run = controller.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))

            val direct = adapter.calls.map { it.request }.filter { texts(it, SegmentKind.S).startsWith("astrolabe · role direct · ") }
            assertEquals(1, direct.size, "one direct main-line cell turn: outcome ${run.outcome}, ${run.state?.reason}")
            assertTrue(direct.single().mask!!.allows("task.propose"), "S1 enables propose for the direct line")
            val replanned = assertIs<Request>(replan, "the blocked split started a replan: outcome ${run.outcome}, ${run.state?.reason}")
            assertTrue("separate the return value from its regression review" in allTexts(replanned), allTexts(replanned))
            assertTrue(replanned.mask!!.allows("task.propose") && !replanned.mask!!.allows("edit.anchored"), "the replan is a plan cell")
            assertEquals(listOf("I1"), graphAtReplan, "a split proposal alone never rewrites the graph")
        }
    }

    @Test
    fun `DX-04 WF-15 holds for a direct cell, whose golden S heads a prefix that answers and a revision only append to`() = runBlocking<Unit> {
        CellFixture(stateRoot.resolve("cell")).use { f ->
            val answers = object : Authority by AutonomousAuthority() {
                override suspend fun ask(question: Question): Answer =
                    if ("factual" in question.text) Answer(question.id, question.contractRevision, "the value is 10")
                    else Answer(question.id, question.contractRevision, "make a return 11 instead", changesRequirements = true)
            }
            val task = TaskTool(answers, f.contracts, f.journal, f.estimator, f.idGen, f.ids, f.clock, f.events, Roles.direct.toolMask)
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py"))),
                Scripted.Reply(listOf(say("asking"), call("c2", "task", """{"op":"ask","question":"a factual question?"}"""))),
                Scripted.Reply(listOf(say("asking again"), call("c3", "task", """{"op":"ask","question":"which value now?"}"""))),
                Scripted.Reply(listOf(say("reading again"), read("c4", "src/b.py"))),
                Scripted.Reply(listOf(call("c5", "state", """{"op":"blocked","blocked":{"reason":"stop here"}}"""))),
            )

            f.cell().run(f.context(model, role = Roles.direct, taskTool = task), f.increment, f.budget(6))

            val requests = f.adapter.calls.map { it.request }
            assertEquals(5, requests.size)
            assertEquals(Layout.system(Roles.direct, ExecutionMode.TrustedLocal), texts(requests.first(), SegmentKind.S), "the golden direct [S] heads the request")
            for (n in 1 until requests.size) {
                val (before, after) = requests[n - 1] to requests[n]
                for (kind in listOf(SegmentKind.S, SegmentKind.R, SegmentKind.K)) {
                    assertEquals(before.segment(kind), after.segment(kind), "request ${n + 1}: [$kind] is fixed at the projection's build")
                }
                val t0 = before.segment(SegmentKind.T)!!.items
                assertEquals(t0, after.segment(SegmentKind.T)!!.items.take(t0.size), "request ${n + 1}: [T] of request $n is a prefix")
                assertEquals(before.tools, after.tools, "request ${n + 1}: one schema set for the line")
            }
            val appended = { n: Int -> f.transcript(n).filterIsInstance<Message>().map { it.text } }
            assertTrue(appended(3).any { it.startsWith("[pinned answer ") && "the value is 10" in it }, "the factual answer is appended: ${appended(3)}")
            assertTrue(appended(4).any { it.startsWith("[contract v${f.contract.version + 1} delta]") }, "the revision is appended as its delta: ${appended(4)}")
            assertTrue(texts(requests[3], SegmentKind.K).startsWith("[K] contract v${f.contract.version} "), "[K] stays as built while the contract moved on")
        }
    }

    @Test
    fun `DX-05 a hidden operation is refused in a direct cell and a direct-only one in a structured cell, each alone`() = runBlocking<Unit> {
        CellFixture(stateRoot.resolve("direct")).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(
                    say("structured habits"),
                    call("h1", "look", """{"what":"impact","target":"src/a.py"}"""),
                    call("h2", "kb", """{"op":"search","query":"return value","why":"prior notes"}"""),
                    read("h3", "src/a.py"),
                )),
                Scripted.Reply(listOf(call("h4", "state", """{"op":"blocked","blocked":{"reason":"stop here"}}"""))),
            )

            assertIs<CellExit.Blocked>(f.run(model, role = Roles.direct))

            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(results.getValue("h1").contains("look.impact is not available to the direct role in this cell (any turn)"), results.getValue("h1"))
            assertTrue(results.getValue("h2").contains("kb.search is not available to the direct role in this cell (any turn)"), results.getValue("h2"))
            assertTrue(results.getValue("h3").contains("def a"), "the admitted call of the turn still runs: " + results.getValue("h3"))
            assertFalse(f.request(1).tools.any { it.name == "kb" }, "the direct line is never sent the kb schema")
        }
        CellFixture(stateRoot.resolve("structured")).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(
                    say("direct habits"),
                    call("d1", "task", """{"op":"finish","text":"done"}"""),
                    call("d2", "state", """{"op":"note","note":{"kind":"open","text":"which caller passes ctx?"}}"""),
                    read("d3", "src/a.py"),
                )),
                Scripted.Reply(listOf(call("d4", "state", """{"op":"blocked","blocked":{"reason":"stop here"}}"""))),
            )

            assertIs<CellExit.Blocked>(f.run(model))

            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(results.getValue("d1").contains("task.finish is not available to the implementing role in this cell (any turn)"), results.getValue("d1"))
            assertTrue(results.getValue("d2").contains("state.note is not available to the implementing role in this cell (any turn)"), results.getValue("d2"))
            assertTrue(results.getValue("d3").contains("def a"), results.getValue("d3"))
        }
    }

    @Test
    fun `DX-06 a finish beside an invalid run is not attempted, never proposed and names the refused companion`() = runBlocking<Unit> {
        CellFixture(stateRoot.resolve("cell")).use { f ->
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("check and finish"), call("c1", "run", "not json"), call("c2", "task", """{"op":"finish"}"""))),
                Scripted.Reply(listOf(call("c3", "state", """{"op":"blocked","blocked":{"reason":"stop here"}}"""))),
            )

            assertIs<CellExit.Blocked>(f.run(model, role = Roles.direct))

            val results = f.transcript(2).filterIsInstance<ToolResult>().associate { it.callId to resultText(it) }
            assertTrue(results.getValue("c1").contains("schema error in call c1"), results.getValue("c1"))
            assertTrue(results.getValue("c2").contains("finish requested: the harness decides after this turn"), results.getValue("c2"))
            val anchor = f.anchorText(2)
            assertTrue(anchor.contains("finish not attempted: op 1 (run) was refused or not executed"), anchor)
            assertTrue(anchor.lines().none { it.contains("packet validation") || it.startsWith("exit:") }, "nothing was proposed: $anchor")
            assertTrue(f.recorder.ofType<AgentEvent.Cell.Ended>().none { it.status == "completed" })
        }
    }
}
