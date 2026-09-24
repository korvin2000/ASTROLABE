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
import io.astrolabe.evidence.SqliteReceipts
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
import io.astrolabe.store.Store
import io.astrolabe.verify.Checks
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** P3.6.2: the full suite and the configured quality gates run every K = 5 verified increments and at campaign end. */
class CadenceTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-k"), AttemptId("a1"), "make every f return its index times ten")
    private val policy = CampaignPolicy(Tokens(4_000_000))
    private val files = (1..7).map { "src/f$it.py" }
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        files.forEachIndexed { i, path -> repo.write(path, "def f():\n    return ${i + 1}\n") }
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            val authority = derived.requests.single().id
            contracts.open(
                derived.copy(
                    shape = Shape.S1,
                    requirements = (1..7).map { Requirement("R$it", "f$it returns ${it * 10}", listOf("AC-$it"), authorityRef = authority) },
                    acceptance = (1..7).map { Acceptance.Run("AC-$it", printing, Origin.User) },
                ),
            )
        }
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private val config get() = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all, qualityGates = listOf(printing))

    private val plan = (1..7).joinToString(",", prefix = """{"increments":[""", postfix = "]}") { i ->
        val depends = if (i > 1) ""","depends_on":["I${i - 1}"]""" else ""
        """{"id":"I$i","requirements":["R$i"],"accept":["AC-$i"],"write_scope":["src/"],"expected_files":1$depends,"produces":"artifact"}"""
    }

    @Test
    fun `a scripted seven-increment campaign runs the suite and quality gates after five verified increments and at the end`() = cadence()

    @Test
    fun `the configured quality gates run on the cadence even when the repository declares no full suite`() {
        // Windows never writes the Makefile, so there is only something to commit on POSIX.
        if (java.nio.file.Files.deleteIfExists(repo.root.resolve("Makefile"))) repo.commit("no declared suite")
        cadence()
    }

    private fun cadence() = runBlocking<Unit> {
        Controller(config, clock, idGen).open(repo.root, request, policy).use { c ->
            val replies = listOf<Scripted>(
                Scripted.Reply(listOf(say("planning seven increments"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
                Scripted.Reply(listOf(say("plan ready"))),
            ) + files.flatMapIndexed { i, path ->
                val v = c.registry.version(path)!!
                listOf(
                    Scripted.Reply(listOf<Item>(say("reading $path"), read("r$i", path))),
                    Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e$i", path, v, "    return ${i + 1}", "    return ${(i + 1) * 10}"))),
                    Scripted.Reply(listOf<Item>(say("done with $path"))),
                )
            }
            val run = Controller(config, clock, idGen).run(c, CellModel(FakeAdapter(ScriptedModel.of(*replies.toTypedArray())), FakeProfiles.main, HeuristicEstimator()), maxCells = 12)

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val suites = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).map { it.text }.filter { it.startsWith("full suite (") }
            assertEquals(listOf("full suite (cadence after 5 verified increments)", "full suite (campaign end)"), suites.map { it.substringBefore("):") + ")" })
            assertEquals(2, SqliteReceipts(c.store, clock).forCheck(Checks.QUALITY_GATE).size, "the configured gate ran with each suite run")
        }
    }
}
