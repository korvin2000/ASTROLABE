package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
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
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Message
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P4.5.2 in the S1 loop: a verified failure escalates with evidence; `budget.attempts` per increment, then blocked. */
class EscalationCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-esc"), AttemptId("a1"), "make a return 10 and b return 20")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val failing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "exit 1")) else Command(listOf("/bin/sh", "-c", "exit 1"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                shape = Shape.S1,
                requirements = listOf(
                    Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id),
                    Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = derived.requests.single().id),
                ),
                acceptance = listOf(Acceptance.Run("AC-1", failing, Origin.User), Acceptance.Run("AC-2", failing, Origin.User)),
            ))
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val plan = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"}]}"""

    @Test
    fun `a stalled completion escalates once with its evidence and the second verified failure blocks the increment for good`() = runBlocking<Unit> {
        val ctl = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)
        val replies = listOf<Scripted>(
            Scripted.Reply(listOf(say("planning"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
            Scripted.Reply(listOf(say("plan ready"))),
        ) + List(4) { Scripted.Reply(listOf(say("done"))) }
        val adapter = FakeAdapter(ScriptedModel.of(*replies.toTypedArray()))
        ctl.open(repo.root, request, policy).use { c ->
            val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
            assertTrue("budget.attempts 2 spent on I1" in run.state!!.reason!!, run.state!!.reason)
            val attempts = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).filter { it.text.startsWith("substantive attempt") }
            assertEquals(2, attempts.size, attempts.toString())
            // The second attempt carries the failure evidence and the stated change, one tier up (§11.3).
            val second = adapter.calls.last().request.segments.flatMap { it.items }.filterIsInstance<Message>().joinToString("\n") { it.text }
            assertTrue("escalated to ExtraHigh (StrongerModel)" in second, second)
        }
        // A resume never replenishes budget.attempts: nothing is dispatched.
        val calls = adapter.calls.size
        ctl.open(repo.root, request, policy).use { c ->
            val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
            assertEquals(calls, adapter.calls.size)
        }
    }
}
