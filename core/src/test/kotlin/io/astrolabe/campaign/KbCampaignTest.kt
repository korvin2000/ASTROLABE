package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Flags
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.context.SqliteManifests
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.SqliteContractRepository
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
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.kb.KbInjection
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteAnchor
import io.astrolabe.kb.NoteBasis
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.kb.Usage
import io.astrolabe.kb.UsageEvent
import io.astrolabe.store.Store
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P4.1.3 FX-29: an admitted CON anchored in the write scope is compiled in and the impact gate flags its touch; the ablation arms. */
class KbCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))
    private val ids = Identities(request.work, request.attempt)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED))))
            val writer = KbWriter(store, HeuristicEstimator(), clock)
            writer.write(Note("CON-1", NoteKind.CON, NoteStatus.Admitted, "a returns an integer", "Callers of a() expect an int.", "src/**", listOf(NoteAnchor("src/a.py", symbol = "a")), basis = NoteBasis(evidenceRefs = listOf("#1"))), ids)
            writer.write(Note("LES-1", NoteKind.LES, NoteStatus.Admitted, "keep a() free of side effects", "Tests call it twice.", "src/**", listOf(NoteAnchor("src/a.py")), basis = NoteBasis(evidenceRefs = listOf("#2", "#3"))), ids)
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun config(flags: Flags = Flags()) = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, flags = flags)

    private fun adapter(v: io.astrolabe.id.FileVersion) = FakeAdapter(ScriptedModel.of(
        Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py"))),
        Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
        Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
        Scripted.Reply(listOf(say("done: a returns 10, per CON-1"))),
    ))

    @Test
    fun `FX-29 - the CON in scope is compiled in under every arm and the contract touch is flagged`() = runTest {
        val events = Events(clock)
        val recorder = EventRecorder().also(events::subscribe)
        events.use {
            Controller(config(), clock, idGen, events).open(repo.root, request, policy).use { c ->
                assertEquals(mapOf("CON-1" to setOf("src/a.py")), c.kb.contractAnchors())
                val run = Controller(config(), clock, idGen, events).runS0(c, CellModel(adapter(c.registry.version("src/a.py")!!), FakeProfiles.main, HeuristicEstimator()))
                assertIs<CellExit.Completed>(run.exit, run.state?.reason)
                // D-89/D-94 are live now: the cell completes, but a contract touch without an ADR in the main line cannot land in S0.
                assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
                assertTrue(run.state?.reason.orEmpty().contains("contract CON-1 touched"), run.state?.reason)

                val manifest = SqliteManifests(c.store, clock).get(c.store.db.query("SELECT id FROM manifests") { it.string("id") }.single())!!
                assertEquals(listOf("contracts-index", "k-mandatory", "note.CON-1"), manifest.selectedUnits, "F23: CON compiled in, the ranked LES stays out with injection off")
                val fired = recorder.ofType<AgentEvent.Cell.GateFired>().map { it.text }
                assertTrue(fired.any { it.startsWith("contract CON-1 touched (src/a.py)") }, fired.toString())
                val boundary = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).map { it.text }
                assertTrue(boundary.any { it.startsWith("kb off for") && "injected 1 (1 mandatory" in it }, boundary.toString())
                assertTrue(boundary.any { it == "kb injected: CON-1" }, boundary.toString())
                // The usage log reads citations from the register, never from the prose.
                val usage = Usage(c.store, clock).all()
                assertEquals(1, usage.getValue("CON-1").injected)
                assertTrue("LES-1" !in usage)
            }
        }
    }

    @Test
    fun `live injection carries the ranked lesson too, frozen ranks the base as of open`() = runTest {
        Controller(config(Flags(kbInjection = KbInjection.Live)), clock, idGen).open(repo.root, request, policy).use { c ->
            val run = Controller(config(Flags(kbInjection = KbInjection.Live)), clock, idGen).runS0(c, CellModel(adapter(c.registry.version("src/a.py")!!), FakeProfiles.main, HeuristicEstimator()))
            assertIs<CellExit.Completed>(run.exit, run.state?.reason)
            val manifest = SqliteManifests(c.store, clock).get(c.store.db.query("SELECT id FROM manifests") { it.string("id") }.single())!!
            assertEquals(listOf("contracts-index", "k-mandatory", "note.CON-1", "note.LES-1"), manifest.selectedUnits)
            assertEquals(setOf("CON-1", "LES-1"), Usage(c.store, clock).all().filterValues { it.injected == 1 }.keys)
            assertEquals(listOf("CON-1", "LES-1"), c.frozenNotes.map { it.id })
        }
    }
}
