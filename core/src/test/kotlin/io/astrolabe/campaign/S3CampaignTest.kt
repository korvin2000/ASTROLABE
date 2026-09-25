package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.Flags
import io.astrolabe.ShapePolicy
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
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.RequirementStatus
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.delegate.Judge
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.delegate.ReviewJudge
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.evidence.JournalScope
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.store.Store
import io.astrolabe.telemetry.Accounting
import io.astrolabe.workspace.Workspaces
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P5.8.1 part A: the S3 runtime through the controller — a validated plan with disjoint units selects S3 at intake
 * (D-183), the units run as writer cells in their own worktrees, integrate once through the merge queue and are
 * committed from the integration receipt. FX-49 (S3 parameterization), FX-26 (writer form), FX-27, FX-28, FX-51 and
 * the S3 switch defaulting off.
 */
class S3CampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-s3"), AttemptId("a1"), "make a return 10 and b return 20")
    private val budget = Tokens(5_000_000)
    private val printing = shell("type pytest_pass.txt", "cat pytest_pass.txt")

    /** Red exactly when both writers' units flags exist: each tree alone passes, the combined tree does not (FX-27). */
    private val agreement = shell(
        "if exist src\\a.cents (if exist src\\b.v2 (type pytest_fail.txt & exit /b 1) else (type pytest_pass.txt)) else (type pytest_pass.txt)",
        "if [ -f src/a.cents ] && [ -f src/b.v2 ]; then cat pytest_fail.txt; exit 1; fi; cat pytest_pass.txt",
    )

    private fun shell(windows: String, posix: String) =
        if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        if (!WINDOWS) repo.write("Makefile", "test:\n\tcat pytest_pass.txt\n")
        repo.write("README.md", "# units\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("src/b.py", "def b():\n    return 2\n")
        repo.write("pytest_pass.txt", resource("/shaper/pytest-pass.txt"))
        repo.write("pytest_fail.txt", resource("/shaper/pytest-fail-param.txt"))
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun resource(name: String) = javaClass.getResourceAsStream(name)!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    /** A low-risk contract without a contract touch: S1 at open, so only the plan can select S3. */
    private fun seed(request: CampaignRequest, tokens: Tokens, agreeing: Boolean = false, reversibility: Reversibility = Reversibility.Easy) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), tokens).contract
            val ref = derived.requests.single().id
            val requirements = listOf(Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = ref), Requirement("R2", "b returns 20", listOf("AC-2"), authorityRef = ref)) +
                if (agreeing) listOf(Requirement("R3", "a and b agree on the unit", listOf("AC-3"), authorityRef = ref)) else emptyList()
            val acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User), Acceptance.Run("AC-2", printing, Origin.User)) +
                if (agreeing) listOf(Acceptance.Run("AC-3", agreement, Origin.User)) else emptyList()
            val shape = if (reversibility == Reversibility.Hard) Shape.S2 else Shape.S1
            contracts.open(derived.copy(shape = shape, risk = Risk(1, reversibility, false), requirements = requirements, acceptance = acceptance))
        }
    }

    /** S3 promoted (`S3.enabled`) and the writer runtime on (`flags.s3Writers`): both are needed (D-183). */
    private val s3 = Config(profiles = FakeProfiles.all, defaults = Defaults(shapePolicy = ShapePolicy(s3Enabled = true)), flags = Flags(s3Writers = true))

    private fun controller(config: Config, lease: Duration = Duration.ofHours(1)) =
        Controller(config.copy(stateRoot = stateRoot.toString()), clock, idGen, leaseDuration = lease)

    private fun model(adapter: FakeAdapter) = CellModel(adapter, FakeProfiles.main, HeuristicEstimator(), maxOutputTokens = 4_000)

    private fun texts(request: Request): String = request.segments.flatMap { it.items }.filterIsInstance<Message>().joinToString("\n") { it.text }

    private fun writerOf(request: Request): String? = Regex("role writer, increment (I\\d)").find(texts(request))?.groupValues?.get(1)

    private val disjoint = """{"increments":[
        {"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/a.py"],"expected_files":1,"produces":"artifact"},
        {"id":"I2","requirements":["R2"],"accept":["AC-2"],"write_scope":["src/b.py"],"expected_files":1,"produces":"artifact"}]}"""

    private fun planning(plan: String): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("planning two disjoint increments"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$plan}"""))),
        Scripted.Reply(listOf(say("plan ready"))),
    )

    /** One cell's edit of [path]: read README and the file, edit, verify its acceptance, done. */
    private fun implement(c: OpenedCampaign, path: String, from: String, to: String, ids: String): List<Scripted> {
        val v = c.registry.version(path)!!
        return listOf(
            Scripted.Reply(listOf<Item>(say("reading $path"), read("r-readme-$path", "README.md"), read("r-$path", path))),
            Scripted.Reply(listOf<Item>(say("editing $path"), anchored("e-$path", path, v, from, to))),
            Scripted.Reply(listOf<Item>(say("verifying"), call("v-$path", "verify", """{"what":"acceptance","ids":[$ids]}"""))),
            Scripted.Reply(listOf<Item>(say("done with $path"))),
        )
    }

    /** A writer that only declares its unit by creating [flag] (FX-27). */
    private fun declare(flag: String, ids: String): List<Scripted> = listOf(
        Scripted.Reply(listOf<Item>(say("declaring $flag"), call("c-$flag", "edit", """{"ops":[{"create":"$flag","content":"unit\n"}],"why":"declare the unit"}"""))),
        Scripted.Reply(listOf<Item>(say("verifying"), call("v-$flag", "verify", """{"what":"acceptance","ids":[$ids]}"""))),
        Scripted.Reply(listOf<Item>(say("done with $flag"))),
    )

    /**
     * Main-line replies in order for every request that is not a writer's, and each writer's replies in order for its
     * own requests (writers interleave); [act] runs before the reply at (`main`|`I1`|`I2`, index).
     */
    private fun adapter(main: List<Scripted>, writers: Map<String, List<Scripted>>, act: Pair<Pair<String, Int>, () -> Unit>? = null): FakeAdapter {
        fun turns(lane: String, replies: List<Scripted>, matches: (Request) -> Boolean) = replies.mapIndexed { i, r ->
            ScriptedModel.Turn(matches, { if (act?.first == lane to i) act.second(); r })
        }
        val all = turns("main", main) { writerOf(it) == null } + writers.flatMap { (id, replies) -> turns(id, replies) { writerOf(it) == id } }
        return FakeAdapter(ScriptedModel(all))
    }

    private fun journal(c: OpenedCampaign): List<String> = c.journal.events(JournalScope(c.ids.work)).map { it.text }

    private fun worktrees(c: OpenedCampaign): List<Path> =
        c.store.layout.candidates.resolve("worktrees").takeIf { Files.isDirectory(it) }?.let { dir -> Files.list(dir).use { it.toList() } }.orEmpty()

    @Test
    fun `FX-49 S3 - disjoint units run as parallel writers, integrate once and the campaign completes on receipts`() = runBlocking<Unit> {
        seed(request, budget)
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            assertEquals(Shape.S1, assertIs<ShapeDecision.Selected>(c.shape).shape, "S3 is never selected on the initial pass")
            val readme = c.registry.version("README.md")!!
            val adapter = adapter(
                planning(disjoint),
                mapOf(
                    "I1" to implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\""),
                    "I2" to implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\""),
                ),
            )
            val run = controller(s3).run(c, model(adapter))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason + "\n" + journal(c).joinToString("\n"))
            assertEquals(10, adapter.calls.size, "the plan cell and two writers of four turns each; the main line runs no implementing cell")
            val lines = journal(c)
            assertTrue(lines.any { it.startsWith("plan intake: shape S3") && "s3=admitted" in it }, lines.joinToString("\n"))
            val record = IntegrationRecord.all(c.store, c.ids).single()
            assertTrue(record.published)
            assertEquals(listOf("I1", "I2"), record.increments, "one integration of the batch through the merge queue")
            assertEquals(listOf("src/a.py", "src/b.py"), record.paths, "the union of the merged edit sets")
            assertEquals(c.stamper.report().candidateId.digest.hex, record.resultingStamp)
            assertNotNull(record.patchHash)
            assertTrue(record.receipts.isNotEmpty(), "the combined tree's receipts bind the integration")
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertTrue(state.graph.increments.all { it.status == IncrementStatus.Verified })
            assertEquals(listOf(RequirementStatus.Verified, RequirementStatus.Verified), state.ledger.entries.values.map { it.status })
            assertEquals("def a():\n    return 10\n", Files.readString(repo.root.resolve("src/a.py")))
            assertEquals("def b():\n    return 20\n", Files.readString(repo.root.resolve("src/b.py")))
            assertTrue(worktrees(c).isEmpty(), "every writer and integration worktree is removed")
            assertTrue(c.intents.open().isEmpty(), "the publication intent committed")
            val billed = Accounting(c.store, clock).calls(request.work).sumOf { it.quantities.billedUsage ?: 0 }
            assertTrue(billed <= budget.value, "billed $billed within the $budget budget")

            // FX-51: both writers read README.md at one hash, each in its own workspace with its own shadow ref.
            val writers = state.cells.filter { it.increment in setOf("I1", "I2") }
            assertEquals(2, writers.size)
            val refs = writers.map { cell ->
                val ws = Workspaces.idOf(request.work, request.attempt, "${cell.increment}-${cell.cell.value}")
                "refs/astrolabe/${request.work.value}/${request.attempt.value}/${ws.value}/head"
            }
            assertNotEquals(refs[0], refs[1])
            val heads = refs.map { assertNotNull(c.workspace.git.readRef(it), "$it survives the worktree's removal") }
            assertNotEquals(heads[0], heads[1], "each writer's final bytes are in its own shadow ref")
            assertEquals(readme, c.registry.version("README.md"), "the shared read dependency never moved")
        }
    }

    @Test
    fun `FX-49 S3 over an S2 contract - each unit's required review approves the combined candidate before publication`() = runBlocking<Unit> {
        seed(request, budget, reversibility = Reversibility.Hard)
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            assertEquals(Shape.S2, assertIs<ShapeDecision.Selected>(c.shape).shape)
            val approve = Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}""")))
            val adapter = adapter(
                planning(disjoint) + approve + approve,
                mapOf(
                    "I1" to implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\""),
                    "I2" to implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\""),
                ),
            )
            val run = controller(s3).run(c, model(adapter))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason + "\n" + journal(c).joinToString("\n"))
            val judges = adapter.calls.filter { Judge.OUTPUT in texts(it.request) }
            assertEquals(2, judges.size, "one increment review per unit, before publication")
            val record = IntegrationRecord.all(c.store, c.ids).single()
            assertTrue(record.published && record.receipts.containsAll(listOf("review:I1", "review:I2")), record.toString())
            val reviewed = ReviewCell(ReviewJudge { _, _ -> error("unused") }, AutonomousAuthority(), c.store, idGen, clock).records(c.ids)
            assertEquals(setOf(record.resultingStamp), reviewed.map { it.candidate.digest.hex }.toSet(), "each approval binds the combined candidate that was published")
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.all { it.status == IncrementStatus.Verified })
        }
    }

    @Test
    fun `FX-49 S3 - a cancellation before the writers start dispatches none`() = runBlocking<Unit> {
        seed(request, budget)
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            val adapter = adapter(planning(disjoint), emptyMap(), ("main" to 1) to { c.cancellation.cancel("host stop") })
            val run = controller(s3).run(c, model(adapter))
            assertEquals(CampaignOutcome.Cancelled, run.outcome, run.state?.reason)
            assertTrue(adapter.calls.none { writerOf(it.request) != null }, "no writer request was made")
            assertTrue(worktrees(c).isEmpty() && IntegrationRecord.all(c.store, c.ids).isEmpty())
        }
    }

    @Test
    fun `FX-26 writers - a writer that completes after cancellation is archived and never integrated`() = runBlocking<Unit> {
        seed(request, budget)
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            val adapter = adapter(
                planning(disjoint),
                mapOf(
                    "I1" to implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\""),
                    "I2" to implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\""),
                ),
                ("I1" to 3) to { c.cancellation.cancel("superseded by the host") },
            )
            val run = controller(s3).run(c, model(adapter))
            assertEquals(CampaignOutcome.Cancelled, run.outcome, run.state?.reason)
            val rejected = IntegrationRecord.all(c.store, c.ids).filter { !it.published }
            assertTrue(rejected.isNotEmpty() && IntegrationRecord.all(c.store, c.ids).none { it.published }, "nothing was published")
            val late = rejected.first { "I1" in it.increments }
            assertEquals("Validate", late.step)
            assertTrue(late.reason!!.startsWith("publication authority superseded"), late.reason)
            val manifest = String(c.store.blobs.get(Digest(assertNotNull(late.archived, "the late patch is archived"))), Charsets.UTF_8)
            assertTrue(" src/a.py " in manifest, manifest)
            assertEquals("def a():\n    return 1\n", Files.readString(repo.root.resolve("src/a.py")), "the main line is untouched")
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.none { it.status == IncrementStatus.Verified })
            assertTrue(worktrees(c).isEmpty())
        }
    }

    @Test
    fun `FX-49 S3 - lease expiry while writers run revokes publication and blocks`() = runBlocking<Unit> {
        seed(request, budget)
        controller(s3, lease = Duration.ofMinutes(5)).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            val adapter = adapter(
                planning(disjoint),
                mapOf(
                    "I1" to implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\""),
                    "I2" to implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\""),
                ),
                ("I1" to 3) to { clock.advance(Duration.ofMinutes(10)) },
            )
            val run = controller(s3).run(c, model(adapter))
            assertEquals(CampaignOutcome.BlockedExternal, run.outcome, run.state?.reason)
            assertTrue("expired" in run.state!!.reason!!, run.state!!.reason)
            assertTrue(IntegrationRecord.all(c.store, c.ids).none { it.published })
            assertEquals("def a():\n    return 1\n", Files.readString(repo.root.resolve("src/a.py")))
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.none { it.status == IncrementStatus.Verified })
        }
    }

    @Test
    fun `FX-27 writers green alone but red together are rejected and the decision returns to the main line`() = runBlocking<Unit> {
        seed(request, budget, agreeing = true)
        val plan = """{"increments":[
            {"id":"I1","requirements":["R1","R3"],"accept":["AC-1","AC-3"],"write_scope":["src/a.cents"],"expected_files":1,"produces":"artifact"},
            {"id":"I2","requirements":["R2"],"accept":["AC-2","AC-3"],"write_scope":["src/b.v2"],"expected_files":1,"produces":"artifact"}]}"""
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            val adapter = adapter(planning(plan), mapOf("I1" to declare("src/a.cents", "\"AC-1\",\"AC-3\""), "I2" to declare("src/b.v2", "\"AC-2\",\"AC-3\"")))
            val run = controller(s3).run(c, model(adapter), maxCells = 2)

            assertEquals(CampaignOutcome.BudgetExhausted, run.outcome, run.state?.reason + "\n" + journal(c).joinToString("\n"))
            val record = IntegrationRecord.all(c.store, c.ids).single()
            assertFalse(record.published)
            assertEquals("CombinedCheck", record.step)
            assertEquals(listOf("I1", "I2"), record.increments)
            assertTrue(record.returnsToMainLine, "no vote over worker confidence replaces integration")
            assertTrue(record.evidence.any { it.startsWith("CHK-accept-AC-3: failed") }, record.evidence.toString())
            assertFalse(Files.exists(repo.root.resolve("src/a.cents")) || Files.exists(repo.root.resolve("src/b.v2")), "the main line is untouched")
            val lines = journal(c)
            assertTrue(lines.any { "the shared decision returns to the main line" in it }, lines.joinToString("\n"))
            assertTrue(lines.any { it.startsWith("S3 off for the rest of the campaign") }, lines.joinToString("\n"))
            val state = c.campaigns.load(request.work, request.attempt)!!
            assertTrue(state.graph.increments.none { it.status == IncrementStatus.Verified })
            assertEquals(2, state.cells.count { it.increment in setOf("I1", "I2") }, "the writer cells stay on record for the main line")
            assertTrue(c.intents.open().isEmpty() && worktrees(c).isEmpty())
        }
    }

    @Test
    fun `FX-28 a writer result whose base moved is rebased by replay, re-verified and integrated`() = runBlocking<Unit> {
        seed(request, budget)
        controller(s3).open(repo.root, request, CampaignPolicy(budget)).use { c ->
            val dispatched = c.stamper.report().candidateId
            val adapter = adapter(
                planning(disjoint),
                mapOf(
                    "I1" to implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\""),
                    "I2" to implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\""),
                ),
                ("I2" to 3) to { Files.writeString(repo.root.resolve("README.md"), "# units, moved on the main line\n") },
            )
            val run = controller(s3).run(c, model(adapter))

            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason + "\n" + journal(c).joinToString("\n"))
            val record = IntegrationRecord.all(c.store, c.ids).single()
            assertTrue(record.published, record.toString())
            assertNotEquals(dispatched.digest.hex, record.integrationBase, "integrated on the moved main line, not the dispatch base")
            assertEquals(c.stamper.report().candidateId.digest.hex, record.resultingStamp)
            assertEquals("# units, moved on the main line\n", Files.readString(repo.root.resolve("README.md")))
            assertEquals("def a():\n    return 10\n", Files.readString(repo.root.resolve("src/a.py")))
            assertEquals("def b():\n    return 20\n", Files.readString(repo.root.resolve("src/b.py")))
            assertTrue(c.campaigns.load(request.work, request.attempt)!!.graph.increments.all { it.status == IncrementStatus.Verified })
            assertTrue(worktrees(c).isEmpty())
        }
    }

    @Test
    fun `S3 flag defaults off - a disjoint plan runs sequentially with the refusal logged`() = runBlocking<Unit> {
        assertFalse(Config().defaults.shapePolicy.s3Enabled, "S3.enabled ships off (§10.4)")
        assertFalse(Config().flags.s3Writers, "the writer runtime flag ships off")
        sequential(Config(profiles = FakeProfiles.all), "S3 is not enabled")
    }

    @Test
    fun `FX-49 S3 - without measured slack S3 is refused and the plan runs sequentially`() = runBlocking<Unit> {
        sequential(s3, "slack ")
    }

    private suspend fun sequential(config: Config, refusal: String) {
        val small = Tokens(400_000)
        seed(request, small)
        controller(config).open(repo.root, request, CampaignPolicy(small)).use { c ->
            val main = planning(disjoint) + implement(c, "src/a.py", "    return 1", "    return 10", "\"AC-1\"") + implement(c, "src/b.py", "    return 2", "    return 20", "\"AC-2\"")
            val adapter = adapter(main, emptyMap())
            val run = controller(config).run(c, model(adapter))
            assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            val intake = journal(c).single { it.startsWith("plan intake: shape") }
            assertTrue(intake.startsWith("plan intake: shape S1") && "s3=refused" in intake && refusal in intake, intake)
            assertTrue(adapter.calls.none { writerOf(it.request) != null }, "no writer ran")
            assertTrue(IntegrationRecord.all(c.store, c.ids).isEmpty())
            assertTrue(worktrees(c).isEmpty())
        }
    }
}
