package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.JournalKind
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Skill
import io.astrolabe.kb.SkillStore
import io.astrolabe.kb.SkillTrigger
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.SegmentKind
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4.4.6: the controller's role wiring — plan packets carry the plan cell's decisions; triggered skills reach `[K]` (D-160). */
class RoleWiringTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-roles"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val printing = if (windows) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        if (!windows) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = listOf(
                        Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                        Requirement("R2", "a stays a function", listOf("AC-2"), authorityRef = derived.requests.single().id),
                    ),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)),
                ),
            )
            val ids = Identities(request.work, request.attempt, context = ContextId("seed"))
            val skill = Skill(
                id = "SKILL-returns", version = 1, trigger = SkillTrigger(paths = listOf("src/**")),
                prerequisites = emptyList(), steps = listOf("change the returned constant, never the signature"),
                expectedArtifacts = emptyList(), verification = listOf("the acceptance run prints ok"), failureExit = "report as Open",
                freshness = "while src/a.py keeps its signature", tokenBudget = 400,
            )
            val writer = KbWriter(store, HeuristicEstimator(), clock)
            writer.write(SkillStore(store).candidate(skill, "returned constants in src", "subsystem:src", ids), ids)
            writer.setStatus("SKILL-returns", NoteStatus.Admitted, ids)
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun texts(request: io.astrolabe.provider.Request, kind: SegmentKind): String =
        request.segment(kind)?.items?.filterIsInstance<Message>()?.joinToString("\n") { it.text }.orEmpty()

    @Test
    fun `the plan packet carries the plan cell's decisions and a path-triggered skill reaches the implementing K`() = runBlocking<Unit> {
        val hosted = Roles.implementing.copy(personaLines = listOf("Keep each edit to one hunk."))
        val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, roles = mapOf("implementing" to hosted)), clock, idGen)
        controller.open(repo.root, request, policy).use { c ->
            val plan = """{"increments":[{"id":"I1","requirements":["R1","R2"],"accept":["AC-1","AC-2"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
            val decision = """[{"decision.add":{"text":"one increment is enough","because":"one requirement, one file","rejected":"split per function"}},{"next":"propose the plan"}]"""
            val v = c.registry.version("src/a.py")!!
            val replies = listOf(
                Scripted.Reply(listOf(say("deciding"), call("s1", "state", """{"op":"patch","patch":$decision}"""))),
                Scripted.Reply(listOf(say("planning"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
                Scripted.Reply(listOf(say("plan ready"))),
                Scripted.Reply(listOf<Item>(say("reading"), read("r1", "src/a.py"))),
                Scripted.Reply(listOf<Item>(say("editing"), anchored("e1", "src/a.py", v, "    return 1", "    return 10"))),
                Scripted.Reply(listOf<Item>(say("verifying"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1","AC-2"]}"""))),
                Scripted.Reply(listOf<Item>(say("done"))),
            )
            val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))
            val run = controller.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)

            val stored = SqlitePlanProposals(c.store, idGen, clock).latest(request.work, null)!!
            assertEquals(listOf("one increment is enough"), stored.packet.decisionPackets.map { it.text }, "handoff debt 1: the intake reads the plan cell's register")

            val implementing = adapter.calls.map { it.request }.first { it.mask!!.allows("edit.anchored") }
            assertTrue("change the returned constant, never the signature" in texts(implementing, SegmentKind.K), texts(implementing, SegmentKind.K))
            assertTrue("Keep each edit to one hunk." in texts(implementing, SegmentKind.S), "the host's wording reaches [S] (D-38)")
            val kbLines = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).map { it.text }.filter { it.startsWith("kb off for I1") }
            assertTrue(kbLines.isNotEmpty() && kbLines.all { "skills SKILL-returns" in it }, kbLines.toString())
        }
    }
}
