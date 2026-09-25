package io.astrolabe.campaign

import io.astrolabe.Astrolabe
import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.atlas.Atlas
import io.astrolabe.auth.PublicationPolicy
import io.astrolabe.auth.RefusalReason
import io.astrolabe.auth.Stage
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
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AmendmentProposal
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.DClassRequest
import io.astrolabe.event.Decision
import io.astrolabe.event.Question
import io.astrolabe.event.Resolution
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
import io.astrolabe.os.Git
import io.astrolabe.provider.Message
import io.astrolabe.provider.Role
import io.astrolabe.store.Store
import io.astrolabe.verify.ReviewRequest
import io.astrolabe.verify.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5.8.1 publication invariant through a real finished campaign (§14.2, D-192, D-250): nothing is published without a
 * host request, every requested stage is a separate grant journaled before and after, a stage is published only when
 * the policy and the authority allow it, and the finish receipt reports the stage reached.
 */
class PublicationCampaignTest {
    @TempDir
    lateinit var stateRoot: Path

    @TempDir
    lateinit var remotes: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val idGen = FixedIdGen()
    private val request = CampaignRequest(WorkId("W-pub"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(200_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    private fun seed(ceiling: Stage) {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.Harness, scope = Contracts.TOUCHED)),
                authorization = Authorization(ceiling, DClassPolicy.Ask, "workspace-local-test-only", listOf("push", "merge")),
                risk = Risk(1, Reversibility.Easy, false),
            ))
        }
    }

    private fun controller() = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen)

    /** A scripted S0 campaign that edits, verifies and completes on receipts. */
    private suspend fun complete(ctl: Controller, c: OpenedCampaign): S0Run {
        val v = c.registry.version("src/a.py")!!
        val adapter = FakeAdapter(ScriptedModel.of(
            Scripted.Reply(listOf(say("reading"), read("c1", "src/a.py"))),
            Scripted.Reply(listOf(say("editing"), anchored("c2", "src/a.py", v, "    return 1", "    return 10"))),
            Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
            Scripted.Reply(listOf(say("done: a returns 10"))),
        ))
        val run = ctl.run(c, CellModel(adapter, FakeProfiles.main, HeuristicEstimator()))
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        return run
    }

    private fun bare(name: String): Path = remotes.resolve(name).also { git(remotes, "init", "--bare", "-q", it.toString()) }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = process.inputStream.use { String(it.readAllBytes()) }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    }

    private class Denying : Authority {
        val asked = ArrayList<DClassRequest>()
        override suspend fun ask(question: Question): Answer? = null
        override suspend fun resolve(proposal: AmendmentProposal): Resolution = error("not asked")
        override suspend fun review(request: ReviewRequest): Verdict? = null
        override suspend fun approve(request: DClassRequest): Decision = Decision(request.id, request.contractRevision, false, "the human says no").also { asked += request }
    }

    @Test
    fun `zero unauthorized-stage publications through a finished campaign and the receipt reports the stage reached`() = runBlocking<Unit> {
        seed(Stage.Merge)
        val base = repo.git.readRef("refs/heads/main")!!
        val remote = bare("origin.git")
        repo.git.push(remote.toString(), base.hex, "refs/heads/main")
        repo.git.push(remote.toString(), base.hex, "refs/heads/integration")
        val remoteGit = Git(remote)
        val ctl = controller()
        ctl.open(repo.root, request, policy).use { c ->
            val run = complete(ctl, c)
            assertEquals(Stage.Patch, run.finish!!.highestAuthorizedStage)
            val harness = "refs/heads/astrolabe/${request.work.value}/${request.attempt.value}"
            assertNull(repo.git.readRef(harness), "nothing is published without a host request")

            // The host asks for every stage; the contract's ceiling is merge and the production deploy is above it.
            val asked = PublicationRequest(Stage.Deploy, remote = remote.toString(), mergeTarget = "integration", deployTarget = DeployTarget("prod", production = true), knownRemotes = setOf(remote.toString()))
            val published = ctl.publish(c, run, asked, AutonomousAuthority()) { error("never deployed above the ceiling") }

            assertEquals(listOf(Stage.LocalCommit, Stage.Push, Stage.Merge), published.results.filterIsInstance<PublicationResult.Published>().map { it.stage })
            val deploy = assertIs<PublicationResult.Refused>(published.results.last())
            assertEquals(RefusalReason.AboveStageCeiling, deploy.refusal.reason)
            assertTrue(published.results.filterIsInstance<PublicationResult.Published>().all { it.stage <= c.contract.authorization.ladderCeiling })
            assertEquals(Stage.Merge, published.reached)
            val commit = assertNotNull(repo.git.readRef(harness))
            assertEquals(commit, remoteGit.readRef(harness))
            assertEquals(commit, remoteGit.readRef("refs/heads/integration"))
            assertEquals(base, repo.git.readRef("refs/heads/main"), "the user's branch never moves")
            assertEquals("refs/heads/main", repo.git.symbolicRef())
            assertEquals(base, remoteGit.readRef("refs/heads/main"), "remote main untouched")
            assertEquals("def a():\n    return 10\n", Files.readString(repo.root.resolve("src/a.py")), "the user's working tree keeps the patch")

            // Durable order: each stage's request is journaled before its outcome.
            val lines = c.journal.events(JournalScope(request.work, kinds = setOf(JournalKind.Boundary))).map { it.text }.filter { it.startsWith("publication") }
            val expected = Stage.entries.drop(1).flatMap { stage ->
                val wire = PublicationPolicy.wire(stage)
                listOf("publication requested: $wire", "publication $wire: ")
            }
            assertEquals(expected.size, lines.size, lines.toString())
            for ((line, prefix) in lines.zip(expected)) assertTrue(line.startsWith(prefix), "$line should start with $prefix")
            val exported = Files.readString(c.store.layout.exports.resolve(request.work.value).resolve("finish-receipt.json"))
            assertTrue("\"highestAuthorizedStage\": \"Merge\"" in exported, exported)
        }
    }

    @Test
    fun `a tree that moved after finish and a denied grant publish nothing`() = runBlocking<Unit> {
        seed(Stage.Merge)
        val ctl = controller()
        ctl.open(repo.root, request, policy).use { c ->
            val run = complete(ctl, c)
            val harness = "refs/heads/astrolabe/${request.work.value}/${request.attempt.value}"
            val denying = Denying()
            val denied = ctl.publish(c, run, PublicationRequest(Stage.LocalCommit), denying)
            assertEquals(RefusalReason.NotApproved, assertIs<PublicationResult.Refused>(denied.results.single()).refusal.reason)
            assertEquals(1, denying.asked.size)
            assertEquals(Stage.Patch, denied.reached)

            repo.write("src/a.py", "def a():\n    return 11\n")
            val stale = ctl.publish(c, run, PublicationRequest(Stage.LocalCommit), AutonomousAuthority())
            assertEquals(RefusalReason.UnverifiedCandidate, assertIs<PublicationResult.Refused>(stale.results.single()).refusal.reason)
            assertEquals(Stage.Patch, stale.reached)
            assertNull(repo.git.readRef(harness), "no harness commit of an unverified tree")

            repo.write("src/a.py", "def a():\n    return 10\n")
            c.cancellation.cancel("the host stopped the campaign")
            val fenced = ctl.publish(c, run, PublicationRequest(Stage.LocalCommit), AutonomousAuthority())
            assertTrue(assertIs<PublicationResult.Refused>(fenced.results.single()).refusal.detail.startsWith("publication fenced: cancelled"))
            assertNull(repo.git.readRef(harness), "a cancelled campaign publishes nothing")
        }
    }

    @Test
    fun `the facade publishes only on the host's request and never an unverified candidate`() = runBlocking<Unit> {
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)
        Astrolabe(config, FakeAdapter(ScriptedModel.of(Scripted.Reply(listOf(Message.text(Role.Assistant, "done, trust me"))))), AutonomousAuthority()).use { sdk ->
            sdk.open(repo.root).use { project ->
                val handle = sdk.campaign(project, "make a return 10", publication = PublicationRequest(Stage.LocalCommit))
                val outcome = handle.await()
                assertTrue(outcome != CampaignOutcome.Completed, outcome.wire)
                val run = assertNotNull(handle.publication)
                assertIs<PublicationResult.Refused>(run.results.single())
                assertEquals(Stage.Patch, run.reached)
                assertNull(repo.git.readRef("refs/heads/astrolabe/${handle.workId.value}/${Astrolabe.FIRST_ATTEMPT}"), "no harness branch")
            }
        }
    }
}
