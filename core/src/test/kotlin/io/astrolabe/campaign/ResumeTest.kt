package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.SqliteCheckpoints
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.evidence.Intent
import io.astrolabe.evidence.IntentStatus
import io.astrolabe.evidence.SqliteIntentJournal
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
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Profile
import io.astrolabe.provider.SegmentKind
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** P2.2.4: the §13.4 resume protocol across controller deaths — handles, unknown outcomes, a rebuild — with a resume note. */
class ResumeTest {
    @TempDir
    lateinit var stateRoot: Path

    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-r"), AttemptId("a1"), "make a return 10")

    @Volatile
    private var armed = false
    private val faults = FaultPoints { point -> if (armed && point == BlobPoint.BEFORE_FSYNC) { armed = false; throw ProcessDeath() } }

    private fun repo(): TempRepo = TempRepo.create().also { repo ->
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), Tokens(400_000)).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User, scope = Contracts.TOUCHED))))
        }
    }

    private fun config(defaults: Defaults = Defaults()) = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, defaults = defaults)

    private fun model(profile: Profile = FakeProfiles.main, vararg turns: () -> Scripted): Pair<CellModel, FakeAdapter> {
        val adapter = FakeAdapter(ScriptedModel(turns.map { t -> ScriptedModel.Turn({ true }, { t() }) }), FakeProfiles.all + (profile.id to profile))
        return CellModel(adapter, profile, HeuristicEstimator()) to adapter
    }

    private fun reply(vararg items: Item): () -> Scripted = { Scripted.Reply(items.toList()) }

    private fun verifyAndFinish() = arrayOf(
        reply(say("verifying"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1"]}""")),
        reply(say("done")),
    )

    private fun pinnedResume(adapter: FakeAdapter): String =
        adapter.calls.first().request.segment(SegmentKind.T)!!.items.filterIsInstance<Message>().map { it.text }.single { it.startsWith("resumed: ") }

    @Test
    fun `a controller dying during a background command resumes by polling the same handle, never relaunching it`() = runBlocking<Unit> {
        repo().use { repo ->
            val long = if (WINDOWS) "ping -n 30 127.0.0.1" else "sleep 30"
            val (first, _) = model(
                FakeProfiles.main,
                reply(say("start the long check"), call("b1", "run", """{"cmd":"$long","bg":true}""")),
                { armed = true; Scripted.Reply(listOf(say("waiting"), call("p1", "run", """{"op":"poll","handle":"handle-1","timeout":1}"""))) },
            )
            Controller(config(), clock, idGen, faults = faults).let { controller ->
                controller.open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c -> assertFailsWith<ProcessDeath> { controller.runS0(c, first) } }
            }
            val controller = Controller(config(), clock, idGen)
            controller.open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
                assertEquals(1, c.reconciliation.handles.size)
                assertTrue(c.reconciliation.handles.single().startsWith("handle-1 "), c.reconciliation.handles.toString())
                val (second, adapter) = model(FakeProfiles.main, reply(say("polling the same handle"), call("p2", "run", """{"op":"poll","handle":"handle-1","timeout":1}""")), *verifyAndFinish())
                val run = controller.runS0(c, second)
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
                val note = pinnedResume(adapter)
                assertTrue("ended failed" in note && "handles handle-1" in note && "KNOWN: seeds only" in note, note)
                assertEquals(1L, c.store.db.query("SELECT count(*) AS n FROM handles") { it.long("n") }.single(), "the handle was polled, never relaunched")
            }
        }
    }

    @Test
    fun `an external effect whose acknowledgement was lost is an unknown outcome named in the resume note`() = runBlocking<Unit> {
        repo().use { repo ->
            val (first, _) = model(FakeProfiles.main, { armed = true; Scripted.Reply(listOf(say("reading"), read("r1", "src/a.py"))) })
            Controller(config(), clock, idGen, faults = faults).let { controller ->
                controller.open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c -> assertFailsWith<ProcessDeath> { controller.runS0(c, first) } }
            }
            // The crashed controller had dispatched a command whose result never came back (intent open, no receipt).
            Store.open(stateRoot, repo.git, clock).use { store ->
                SqliteIntentJournal(store, clock).record(
                    Intent("int-lost", Identities(request.work, request.attempt, context = ContextId("cell-1")), "act-lost", listOf("make", "deploy"), null, "W", status = IntentStatus.Dispatched, at = Instant.EPOCH),
                )
            }
            val controller = Controller(config(), clock, idGen)
            controller.open(repo.root, request, CampaignPolicy(Tokens(400_000))).use { c ->
                assertEquals(listOf("int-lost"), c.reconciliation.unknownOutcomes)
                val (second, adapter) = model(FakeProfiles.main, *verifyAndFinish())
                assertEquals(CampaignOutcome.Completed, controller.runS0(c, second).outcome)
                val note = pinnedResume(adapter)
                assertTrue("unknown outcomes int-lost (reconcile before any retry)" in note, note)
                assertEquals(IntentStatus.Unknown, SqliteIntentJournal(c.store, clock).get("int-lost")!!.status, "never replayed")
            }
        }
    }

    @Test
    fun `a death right after a pressure rebuild leaves a settled checkpoint the next cell resumes from`() = runBlocking<Unit> {
        io.astrolabe.cell.CellFixture(stateRoot.resolve("cell"), faults = faults, defaults = Defaults(alpha = 0.1)).use { f ->
            val small = Profile("small", FakeProfiles.PROVIDER, "fake-small", FakeProfiles.capabilities(12_000, 500), FakeProfiles.main.priceTable)
            val model = ScriptedModel(
                listOf(
                    ScriptedModel.Turn({ true }, { Scripted.Reply(listOf(say("reading"), read("r1", "src/a.py"))) }),
                    ScriptedModel.Turn({ true }, { armed = true; Scripted.Reply(listOf(say("after the rebuild"), read("r2", "src/b.py"))) }),
                ),
            )
            assertFailsWith<ProcessDeath> { f.run(model, profile = small, profiles = FakeProfiles.all + (small.id to small)) }
            val cell = f.ids.context!!
            assertTrue(f.journal.events(io.astrolabe.evidence.JournalScope(f.ids.work, kinds = setOf(io.astrolabe.evidence.JournalKind.Boundary))).any { it.text.startsWith("rebuilt: pressure (generation 1)") })
            val settled = f.checkpoints.latest(cell)!!
            assertEquals(1, settled.turn, "the turn before the death is the last settled checkpoint")
            assertEquals(CellStatus.Running, settled.status, "a cell that died mid-run is recorded running; reopen marks it lost")
            // §13.4: the next cell starts from that checkpoint's register and export, re-validated against the tree.
            val carry = io.astrolabe.context.CarryForward.carry(
                f.registerVersions.latest(cell)!!, f.checkpoints.export(cell, settled.turn)!!, null, { f.registry.version(it) }, { true }, emptyList(), emptyList(),
            )
            assertTrue(carry.known.startsWith("KNOWN: seeds only"), carry.known)
        }
    }
}
