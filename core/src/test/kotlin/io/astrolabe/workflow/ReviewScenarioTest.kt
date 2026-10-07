package io.astrolabe.workflow

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.campaign.S0Run
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
import io.astrolabe.contract.Reversibility
import io.astrolabe.contract.Risk
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.delegate.ReviewCell
import io.astrolabe.delegate.ReviewJudge
import io.astrolabe.delegate.ReviewRecord
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
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
import io.astrolabe.provider.Profile
import io.astrolabe.store.BlobPoint
import io.astrolabe.store.CrashPoint
import io.astrolabe.store.FaultPoints
import io.astrolabe.store.ProjectLockHeld
import io.astrolabe.store.Store
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WF-9 (core, plan §7.2, W4): a blocking reviewer starts and can read. An S2 increment owes a review (hard
 * reversibility, D-34); the review cell runs on the default review budget against a model whose maximum output is 64K,
 * and must make at least one model request and at least one `look`. Plus the W0 tail: an open that fails still emits
 * its `phase.counted` event. Guards count events and fake-adapter calls, never seconds; the repository is three files. The
 * three review scenarios play at once on first use, each its own host (plan §7.2 rule 3, T-45).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, suspend Host.() -> Unit> = mapOf(WF9 to { reviewCellReads() }, UNADMITTED to { unadmitted() }, CAMPAIGN to { campaignReviewWaits() })
    private val played by lazy { Scenario.concurrently(plays.keys) { name -> Host(stateRoot.resolve(name)).use { host -> runBlocking { plays.getValue(name)(host) } } } }

    @Test
    fun `WF-9 the review cell on the default budget and a 64K-output model asks the model and reads the changed file`() = played.getValue(WF9).getOrThrow()

    @Test
    fun `a review its configured budget cannot admit is unavailable with the numbers, waits for a decision once, and the user's accept completes`() =
        played.getValue(UNADMITTED).getOrThrow()

    /**
     * T-09 (W8): the campaign-scope review end to end. An unsigned `review:` item owes it (§8.8); its review cell cannot be
     * admitted on the configured campaign budget and no host reviewer answers, so the campaign stops waiting — the review
     * is never skipped — with the review cell's numbers in the finish receipt, the review put to the decider, and the
     * user's accept at the reopen completes without a model call.
     */
    @Test
    fun `T-09 a campaign review nobody can give is never skipped, waits for a decision with the numbers, and the user's accept completes`() =
        played.getValue(CAMPAIGN).getOrThrow()

    @Test
    fun `an open the project lock refuses still emits its open counters`() = Host(stateRoot.resolve("lock")).use { it.lockRefused() }

    @Test
    fun `an open that fails still emits its open counters`() = Host(stateRoot.resolve("fault")).use { it.openFails() }

    /** One host of the class's scenarios: its repository of three files, store, clock, ids and event stream. */
    private class Host(private val stateRoot: Path) : AutoCloseable {
        private val repo: TempRepo = TempRepo.create()
        private val clock = FakeClock.at("2026-10-05T10:00:00Z")
        private val idGen = FixedIdGen()
        private val events = Events(clock)
        private val recorder = EventRecorder().also { events.subscribe(it) }
        private val request = CampaignRequest(WorkId("W-rv"), AttemptId("a1"), "make a return 10")
        private val policy = CampaignPolicy(Tokens(400_000))
        private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

        /** A model with a large output limit: its maximum output alone exceeds the review cell's working tokens. */
        private val large = Profile("large", FakeProfiles.PROVIDER, "fake-large", FakeProfiles.capabilities(200_000, 64_000), FakeProfiles.main.priceTable)

        init {
            repo.write("src/a.py", "def a():\n    return 1\n")
            repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
            repo.commit("initial")
        }

        override fun close() {
            events.close()
            repo.close()
        }

        suspend fun reviewCellReads() {
            review(Defaults(),
                // The review cell: it reads the changed file, then publishes its verdict.
                Scripted.Reply(listOf(say("checking the change"), read("rv1", "src/a.py"))),
                Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
            ) { c, run, reviewed ->
                assertTrue(reviewed.requests > 0, "WF-9: the review cell made no model request: ${reviewed.record?.unavailable}")
                // A read that executed covers the edited file at its version now; a refused or failed look carries no version.
                val edited = "src/a.py: " + checkNotNull(c.registry.version("src/a.py")).hash8.take(4)
                assertTrue(reviewed.looks.any { edited in it }, "WF-9: the review cell never read the changed file at its edited version ($edited): ${reviewed.looks}")
                val record = assertNotNull(reviewed.record, "the increment's review is recorded")
                assertTrue(record.approved, "the reviewer's approval stands on its own verdict: ${record.unavailable}")
                assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
            }
        }

        suspend fun unadmitted() {
            val asked = ArrayList<AcceptanceDecisionRequest>()
            var accept = false
            val host = object : Authority by AutonomousAuthority() {
                override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
                    asked += request
                    return if (accept) AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, "user", "reviewed by hand") else null
                }
            }
            val config = review(Defaults().copy(reviewIncrementTokens = 3_000), host = host) { _, run, reviewed ->
                assertEquals(0, reviewed.requests, "the unadmittable review cell asked the model")
                val record = assertNotNull(reviewed.record, "the increment's review is recorded")
                assertEquals(null, record.verdict, "no verdict without a review: never approved, never declined")
                val why = record.unavailable.orEmpty()
                assertTrue("not admitted before its model call" in why && "usable budget 2400 tokens" in why && "input estimate" in why && "needed output" in why, why)
                assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
                assertTrue(asked.any { it.incrementId == "I1" }, "the unverified review is put to the decider: ${asked.map { it.incrementId }}")
            }
            // An unchanged resume asks no model and runs no review again: the stored unavailable review is the answer (I3).
            Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
                val reviews = ReviewCell(ReviewJudge { _, _ -> error("no judge") }, host, c.store, idGen, clock).records(c.ids).count { !it.reused }
                val fake = FakeAdapter(ScriptedModel.of())
                val again = Controller(config, clock, idGen, events).run(c, CellModel(fake, large, HeuristicEstimator()), host)
                assertEquals(CampaignOutcome.WaitingForInput, again.outcome, again.state?.reason)
                assertTrue(fake.calls.isEmpty(), "the resume called the model ${fake.calls.size} times")
                assertEquals(reviews, ReviewCell(ReviewJudge { _, _ -> error("no judge") }, host, c.store, idGen, clock).records(c.ids).count { !it.reused }, "the resume never runs the review again")
            }
            // The user accepts what was put to them: the increment and the campaign complete without a model call.
            accept = true
            Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
                val fake = FakeAdapter(ScriptedModel.of())
                val done = Controller(config, clock, idGen, events).run(c, CellModel(fake, large, HeuristicEstimator()), host)
                assertEquals(CampaignOutcome.Completed, done.outcome, done.state?.reason)
                assertTrue(fake.calls.isEmpty(), "the accept called the model ${fake.calls.size} times")
            }
        }

        suspend fun campaignReviewWaits() {
            val asked = ArrayList<AcceptanceDecisionRequest>()
            var accept = false
            val host = object : Authority by AutonomousAuthority() {
                override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? {
                    asked += request
                    return if (accept) AcceptanceDecision(request.id, request.contractRevision, request.candidate, DecisionKind.Accept, Decider.User, "user", "reviewed by hand") else null
                }
            }
            val owed = Acceptance.Review("AC-R", "a maintainer approves the whole change", Origin.User)
            val config = review(Defaults().copy(reviewCampaignTokens = 3_000),
                Scripted.Reply(listOf(say("checking the change"), read("rv1", "src/a.py"))),
                Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
                host = host, extra = listOf(owed),
            ) { _, run, reviewed ->
                assertTrue(reviewed.record?.approved == true, "the increment's own review approved: ${reviewed.record?.unavailable}")
                assertEquals(CampaignOutcome.WaitingForInput, run.outcome, "an unavailable campaign review is never skipped: ${run.state?.reason}")
                val review = assertNotNull(run.finish?.review, "the receipt names the owed campaign review")
                assertEquals(null, review.verdict)
                val why = review.unavailable.orEmpty()
                assertTrue("no reviewer answered" in why && "not admitted before its model call" in why, why)
                assertTrue(asked.any { it.incrementId == null && it.items.any { item -> item.obligation == "campaign-review" } },
                    "the campaign review is put to the decider: ${asked.map { it.incrementId to it.items.map { i -> i.obligation } }}")
            }
            accept = true
            Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
                val fake = FakeAdapter(ScriptedModel.of())
                val done = Controller(config, clock, idGen, events).run(c, CellModel(fake, large, HeuristicEstimator()), host)
                assertEquals(CampaignOutcome.Completed, done.outcome, done.state?.reason)
                assertTrue(fake.calls.isEmpty(), "the accept called the model ${fake.calls.size} times")
            }
        }

        private class Reviewed(val requests: Int, val looks: List<String>, val record: ReviewRecord?)

        /**
         * Plans one increment, implements it and lets its owed review run on [defaults] under [host]; [judge] are the review
         * cell's replies. [verify] sees the campaign, the run and the review cell's requests, look result headers and record.
         */
        private suspend fun review(
            defaults: Defaults, vararg judge: Scripted, host: Authority = AutonomousAuthority(), extra: List<Acceptance> = emptyList(),
            verify: (OpenedCampaign, S0Run, Reviewed) -> Unit,
        ): Config {
            seed(extra)
            val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all + (large.id to large), defaults = defaults)
            Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
                val v = checkNotNull(c.registry.version("src/a.py"))
                val adapter = FakeAdapter(ScriptedModel.of(
                    Scripted.Reply(listOf(say("planning one increment"), call("p1", "task", """{"op":"propose","kind":"plan","proposal":$PLAN}"""))),
                    Scripted.Reply(listOf(say("plan ready"))),
                    Scripted.Reply(listOf(say("reading"), read("r1", "src/a.py"))),
                    Scripted.Reply(listOf(say("editing"), anchored("e1", "src/a.py", v, "    return 1", "    return 10"))),
                    Scripted.Reply(listOf(say("verifying"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
                    Scripted.Reply(listOf(say("done"))),
                    *judge,
                ))
                val run = Controller(config, clock, idGen, events).run(c, CellModel(adapter, large, HeuristicEstimator()), host)
                check(recorder.awaitCount(events.lastSeq.toInt())) { "events up to ${events.lastSeq} were not delivered" }
                val reviewer = { e: AgentEvent.Cell -> e.ids.context?.value?.startsWith("review") == true }
                val requests = recorder.ofType<AgentEvent.Cell.ModelRequested>().filter(reviewer)
                val looks = recorder.ofType<AgentEvent.Cell.ToolCalled>().filter(reviewer).filter { it.family == "look" }.map { it.ids to it.opId }.toSet()
                val headers = recorder.ofType<AgentEvent.Cell.ToolResulted>().filter { (it.ids to it.opId) in looks }.map { it.header }
                val record = ReviewCell.latest(c.store, c.ids)
                println("review budget ${defaults.reviewIncrementTokens} tokens; review requests ${requests.size} (estimates ${requests.map { it.estimatedTokens }}); " +
                    "looks $headers; request output limits ${adapter.calls.map { it.request.maxOutputTokens }}; review ${record?.verdict?.outcome ?: record?.unavailable}; " +
                    "campaign ${run.outcome}: ${run.state?.reason}")
                verify(c, run, Reviewed(requests.size, headers, record))
            }
            return config
        }

        fun lockRefused() {
            val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all)
            Controller(config, clock, idGen, events).open(repo.root, request, policy).use {
                val failure = runCatching { Controller(config, clock, idGen, events).open(repo.root, request, policy).close() }.exceptionOrNull()
                assertTrue(failure is ProjectLockHeld, "the second open meets the held project lock: $failure")
            }
            check(recorder.awaitCount(events.lastSeq.toInt())) { "events up to ${events.lastSeq} were not delivered" }
            val opens = recorder.ofType<AgentEvent.Telemetry.PhaseCounted>().filter { it.counted == CountedPhase.Open.wire }
            assertEquals(2, opens.size, "the refused open emits its own open phase.counted: $opens")
            assertTrue(opens.last().gitProcesses > 0, "the refused open's git commands are counted: ${opens.last()}")
        }

        fun openFails() {
            repo.write("notes.txt", "untracked\n")
            var armed = true
            val faults = FaultPoints(CrashPoint { point -> if (armed && point == BlobPoint.AFTER_ROW) { armed = false; throw OpenCrash() } })
            val controller = Controller(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all), clock, idGen, events, faults = faults)
            val failure = runCatching { controller.open(repo.root, request, policy).close() }.exceptionOrNull()
            assertTrue(failure is OpenCrash, "the injected fault fails the open: $failure")
            check(recorder.awaitCount(events.lastSeq.toInt())) { "events up to ${events.lastSeq} were not delivered" }
            val opens = recorder.ofType<AgentEvent.Telemetry.PhaseCounted>().filter { it.counted == CountedPhase.Open.wire }
            assertEquals(1, opens.size, "the failed open emits one open phase.counted: $opens")
            assertTrue(opens.single().gitProcesses > 0, "the failed open's counters are its own: ${opens.single()}")
        }

        private class OpenCrash : RuntimeException("injected crash during open")

        /** Hard reversibility makes the risk high: S2 is selected and the D-34 floor owes the increment a review (D-122). */
        private fun seed(extra: List<Acceptance> = emptyList()) {
            Store.open(stateRoot, repo.git, clock).use { store ->
                val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
                val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
                contracts.open(derived.copy(
                    shape = Shape.S2,
                    risk = Risk(1, Reversibility.Hard, false),
                    requirements = listOf(Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id)),
                    acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User)) + extra,
                ))
            }
        }
    }

    private companion object {
        const val WF9 = "wf9"
        const val UNADMITTED = "unadmitted"
        const val CAMPAIGN = "campaign"
        const val PLAN = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
    }
}
