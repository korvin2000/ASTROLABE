package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellModel
import io.astrolabe.cell.WINDOWS
import io.astrolabe.context.BoundaryReason
import io.astrolabe.context.Manifest
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.Events
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
import io.astrolabe.kb.Notes
import io.astrolabe.provider.Profile
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.Validation
import io.astrolabe.store.Store
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * P2.7.2: a scripted three-increment refactor on a small window. The middle increment's first cell reads until the
 * context overflows — one pressure rebuild, then a partial — and a continuation cell finishes it from carry-forward
 * and seeds; the verified increment before it is never redone and no requirement is dropped.
 */
class LongRefactorTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-long"), AttemptId("a1"), "make a return 10, b return 20 and c return 30")
    private val policy = CampaignPolicy(Tokens(2_000_000))
    private val small = Profile("small", FakeProfiles.PROVIDER, "fake-small", FakeProfiles.capabilities(16_000, 500), FakeProfiles.main.priceTable)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("src/c.py", "def c():\n    return 3\n")
        repo.write("src/big.py", (1..600).joinToString("") { "value_%03d = \"a long legacy constant the refactor has to read around, line %03d\"\n".format(it, it) })
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val authority = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = (1..3).map { i -> Requirement("R$i", "${"abc"[i - 1]} returns ${i * 10}", listOf("AC-$i"), authorityRef = authority) },
                    acceptance = (1..3).map { i -> Acceptance.Run("AC-$i", printing, Origin.User) },
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val plan = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-2"],"write_scope":["src/"],"expected_files":1,"depends_on":["I1"],"produces":"artifact"},
        {"id":"I3","requirements":["R3"],"accept":["AC-3"],"write_scope":["src/"],"expected_files":1,"depends_on":["I2"],"produces":"artifact"}]}"""

    /** A fresh projection: no tool call of an earlier turn is in its `[T]` (every cell starts one; a pressure rebuild keeps six turns). */
    private fun fresh(request: Request): Boolean = request.segment(SegmentKind.T)!!.items.none { it is ToolCall }

    /**
     * The model: a small state machine over cells, each recognised by its fresh projection — plan, I1, I2 (reads the
     * big file until the harness ends the cell), I2's continuation, I3. [edits] records which cell edited what.
     */
    private class Script(private val c: OpenedCampaign, private val fresh: (Request) -> Boolean, private val plan: String) {
        var cell = -1
        var turn = 0
        val edits = ArrayList<Pair<Int, String>>()
        private val versions = listOf("src/a.py", "src/b.py", "src/c.py").associateWith { c.registry.version(it)!! }

        fun next(request: Request): Scripted {
            if (fresh(request)) {
                cell += 1
                turn = 0
            }
            val t = turn++
            val items = when (cell) {
                0 -> if (t == 0) listOf(say("planning three increments"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}""")) else listOf(say("plan ready"))
                1 -> implement("src/a.py", "    return 1", "    return 10", "AC-1", t)
                2 -> when {
                    t == 0 -> listOf(say("scanning the legacy module first"), read("big-0", "src/big.py:1-40"), patch("s-0", """{"next":"finish reading src/big.py, then make src/b.py return 20"}"""))
                    t < 40 -> listOf(say("reading the legacy module, part $t"), read("big-$t", "src/big.py:${t * 40 % 600 + 1}-${t * 40 % 600 + 40}"))
                    else -> fail("the pressure never ended the cell")
                }
                3 -> implement("src/b.py", "    return 2", "    return 20", "AC-2", t)
                4 -> implement("src/c.py", "    return 3", "    return 30", "AC-3", t)
                else -> fail("no cell $cell is scripted")
            }
            return Scripted.Reply(items)
        }

        private fun implement(path: String, from: String, to: String, ac: String, t: Int) = when (t) {
            0 -> listOf(say("reading $path"), read("r-$cell", path))
            1 -> listOf(say("editing $path"), anchored("e-$cell", path, versions.getValue(path), from, to)).also { edits += cell to path }
            2 -> listOf(say("verifying"), call("v-$cell", "verify", """{"what":"acceptance","ids":["$ac"]}"""))
            else -> listOf(say("done with $path"))
        }
    }

    private fun manifests(store: Store): List<Manifest> =
        store.db.query("SELECT body FROM manifests WHERE work_id = ? ORDER BY rowid", request.work.value) { Json.decodeFromString(Manifest.serializer(), it.string("body")) }

    @Test
    fun `a long refactor under context pressure continues the partial increment and drops nothing`() = runBlocking<Unit> {
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all + (small.id to small))
        val events = Events(clock)
        val recorder = EventRecorder().also(events::subscribe)
        events.use { Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
            val script = Script(c, ::fresh, plan)
            val adapter = FakeAdapter(ScriptedModel(listOf(ScriptedModel.Turn({ true }, script::next, once = false))), FakeProfiles.all + (small.id to small))
            val run = Controller(config, clock, idGen, events).run(c, CellModel(adapter, small, HeuristicEstimator()))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val state = c.campaigns.load(request.work, request.attempt)!!
            // No dropped requirement, no false green: every requirement verified on receipts, the contract unchanged.
            assertEquals(List(3) { RequirementStatus.Verified }, state.ledger.entries.values.map { it.status })
            assertTrue(state.graph.increments.all { it.status == IncrementStatus.Verified })
            assertEquals(c.contract.acceptance.map { it.id }, listOf("AC-1", "AC-2", "AC-3"))
            assertEquals(listOf("green", "green", "green"), run.finish!!.acceptance.map { it.status })
            assertEquals(listOf("def a():\n    return 10\n", "def b():\n    return 20\n", "def c():\n    return 30\n"), listOf("a", "b", "c").map { Files.readString(repo.root.resolve("src/$it.py")) })
            assertTrue(adapter.validations.all { it.result == Validation.Ok }, "every request kept valid call/result pairing")

            // I2 took a pressure rebuild, ended partial and was continued; the verified I1 was never redone.
            assertEquals(mapOf("I1" to 1, "I2" to 2, "I3" to 1), state.graph.increments.associate { it.id to it.cells.size })
            val i2 = state.graph.increments.first { it.id == "I2" }.cells
            val first = state.cells.first { it.cell == i2[0] }
            assertEquals(io.astrolabe.cell.CellStatus.Partial, first.status)
            assertEquals(1, io.astrolabe.cell.SqliteCheckpoints(c.store, clock).latest(i2[0])!!.rebuilds, "one pressure rebuild before the partial")
            assertEquals(listOf(1 to "src/a.py", 3 to "src/b.py", 4 to "src/c.py"), script.edits, "only the continuation edits b; nobody touches a again")

            // Manifests name every boundary; the continuation is compiled from carry-forward seeds.
            val manifests = manifests(c.store)
            assertEquals(listOf("plan", "I1", "I2", "I2", "I3"), manifests.map { it.incrementId })
            assertEquals(listOf(null, BoundaryReason.Done, BoundaryReason.Done, BoundaryReason.Partial, BoundaryReason.Done), manifests.map { it.boundaryReason })
            val continuation = manifests[3]
            assertEquals(listOf(i2[0].value), continuation.continuationLineage)
            assertTrue(continuation.seeds.isNotEmpty() && continuation.seeds.all { it.path == "src/big.py" }, "the partial's reads named by its STATE are carried as seeds: $continuation")
            // The harness re-ran the earlier increments' acceptances at campaign end instead of redoing them (§4.1).
            val boundaries = c.journal.events(io.astrolabe.evidence.JournalScope(request.work, kinds = setOf(io.astrolabe.evidence.JournalKind.Boundary))).map { it.text }
            assertTrue(boundaries.any { it.startsWith("regression obligations re-run (campaign end): AC-1, AC-2 ·") }, boundaries.takeLast(4).joinToString("\n"))
            assertTrue(continuation.registerVersionIn != null, "the continuation starts from the partial's validated register")

            // STATUS notes at every boundary: the role switch after the plan, then each cell end.
            val status = Notes(c.store).revisions("STATUS-W-long").map { it.body.lineSequence().first().substringAfter("boundary: ").substringBefore(" ·") }
            assertEquals(listOf("role_switch", "cell_end", "cell_end", "cell_end", "cell_end"), status)

            // P2.7.3: the economics report from manifests, the usage table, checkpoints and the cell events.
            assertTrue(recorder.awaitCount(events.lastSeq.toInt()))
            val report = Economics.report(c, clock, recorder.records)
            assertEquals(manifests.map { it.cell }, report.cells.map { it.cell })
            assertEquals(mapOf("I1" to 0, "I2" to 1, "I3" to 0), report.continuationsPerIncrement)
            assertEquals(1.0 / 5, report.rebuildsPerCell)
            assertEquals(adapter.calls.size, report.cells.sumOf { it.calls })
            assertTrue(report.boundaryCostShare!! > 0 && report.boundaryCostShare!! < 1, "boundary share ${report.boundaryCostShare}")
            assertTrue(report.anchorShare!! > 0 && report.anchorShare!! < 1, "[A] share ${report.anchorShare}")
            assertTrue(report.tokensByCacheClass.isNotEmpty() && report.tokensByCacheClass.values.none { it == null }, "${report.tokensByCacheClass}")
            assertEquals(manifests.drop(1).map { it.cell }, report.breakEven.map { it.cell }, "one break-even diagnostic per boundary")
            assertEquals(EconomicsReport.LIVE_GATE, report.liveGate)
            val exported = Files.readString(Economics.export(c, report))
            assertTrue(exported.startsWith("# Economics — W-long") && "B2 ≥ B1: UNMEASURED" in exported && "| ${i2[1].value} | I2 | partial |" in exported, exported)
        } }
    }
}
