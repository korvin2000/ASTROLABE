package io.astrolabe.workflow

import io.astrolabe.Config
import io.astrolabe.Defaults
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Controller
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
import io.astrolabe.delegate.ReviewRecord
import io.astrolabe.event.AgentEvent
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
import io.astrolabe.store.Store
import io.astrolabe.telemetry.CountedPhase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WF-9 (core, plan §7.2, W4): a blocking reviewer starts and can read. An S2 increment owes a review (hard
 * reversibility, D-34); the review cell runs on the default review budget against a model whose maximum output is 64K,
 * and must make at least one model request and at least one `look`. Plus the W0 tail: an open that fails still emits
 * its `phase.counted` event. Guards count events and fake-adapter calls, never seconds; the repository is three files.
 */
class ReviewScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private lateinit var repo: TempRepo
    private val clock = FakeClock.at("2026-10-05T10:00:00Z")
    private val idGen = FixedIdGen()
    private val events = Events(clock)
    private val recorder = EventRecorder().also { events.subscribe(it) }
    private val request = CampaignRequest(WorkId("W-rv"), AttemptId("a1"), "make a return 10")
    private val policy = CampaignPolicy(Tokens(400_000))
    private val printing = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type pytest_pass.txt")) else Command(listOf("/bin/sh", "-c", "cat pytest_pass.txt"))

    /** A model with a large output limit: its maximum output alone exceeds the review cell's working tokens. */
    private val large = Profile("large", FakeProfiles.PROVIDER, "fake-large", FakeProfiles.capabilities(200_000, 64_000), FakeProfiles.main.priceTable)

    @BeforeTest
    fun setUp() {
        repo = TempRepo.create()
        repo.write("src/a.py", "def a():\n    return 1\n")
        repo.write("pytest_pass.txt", javaClass.getResourceAsStream("/shaper/pytest-pass.txt")!!.use { String(it.readAllBytes(), Charsets.UTF_8) })
        repo.commit("initial")
    }

    @AfterTest
    fun tearDown() {
        events.close()
        repo.close()
    }

    @Test
    fun `WF-9 the review cell on the default budget and a 64K-output model asks the model and looks`() = runBlocking<Unit> {
        val reviewed = review(Defaults(),
            // The review cell: it reads the changed file, then publishes its verdict.
            Scripted.Reply(listOf(say("checking the change"), read("rv1", "src/a.py"))),
            Scripted.Reply(listOf(say("""{"verdict":"approve","confidence":0.9,"findings":[]}"""))),
        )
        assertTrue(reviewed.requests > 0, "WF-9: the review cell made no model request: ${reviewed.record?.unavailable}")
        assertTrue(reviewed.looks > 0, "WF-9: the review cell never looked: ${reviewed.record?.unavailable}")
        val record = assertNotNull(reviewed.record, "the increment's review is recorded")
        assertTrue(record.approved, "the reviewer's approval stands on its own verdict: ${record.unavailable}")
    }

    @Test
    fun `a review its configured budget cannot admit is unavailable with the numbers before any model call`() = runBlocking<Unit> {
        val reviewed = review(Defaults().copy(reviewIncrementTokens = 3_000))
        assertEquals(0, reviewed.requests, "the unadmittable review cell asked the model")
        val record = assertNotNull(reviewed.record, "the increment's review is recorded")
        assertEquals(null, record.verdict, "no verdict without a review: never approved, never declined")
        val why = record.unavailable.orEmpty()
        assertTrue("not admitted before its model call" in why && "usable budget 2400 tokens" in why && "input estimate" in why && "needed output" in why, why)
    }

    private class Reviewed(val requests: Int, val looks: Int, val record: ReviewRecord?)

    /** Plans one increment, implements it and lets its owed review run on [defaults]; [judge] are the review cell's replies. */
    private suspend fun review(defaults: Defaults, vararg judge: Scripted): Reviewed {
        seed()
        val config = Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all + (large.id to large), defaults = defaults)
        return Controller(config, clock, idGen, events).open(repo.root, request, policy).use { c ->
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
            val run = Controller(config, clock, idGen, events).run(c, CellModel(adapter, large, HeuristicEstimator()))
            check(recorder.awaitCount(events.lastSeq.toInt())) { "events up to ${events.lastSeq} were not delivered" }
            val reviewer = { e: AgentEvent.Cell -> e.ids.context?.value?.startsWith("review") == true }
            val requests = recorder.ofType<AgentEvent.Cell.ModelRequested>().filter(reviewer)
            val looks = recorder.ofType<AgentEvent.Cell.ToolCalled>().filter(reviewer).filter { it.family == "look" }
            val record = ReviewCell.latest(c.store, c.ids)
            println("review budget ${defaults.reviewIncrementTokens} tokens; review requests ${requests.size} (estimates ${requests.map { it.estimatedTokens }}); " +
                "looks ${looks.size}; request output limits ${adapter.calls.map { it.request.maxOutputTokens }}; review ${record?.verdict?.outcome ?: record?.unavailable}; " +
                "campaign ${run.outcome}: ${run.state?.reason}")
            Reviewed(requests.size, looks.size, record)
        }
    }

    @Test
    fun `an open that fails still emits its open counters`() {
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
    private fun seed() {
        Store.open(stateRoot, repo.git, clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(repo.root), Config(), policy.tokens).contract
            contracts.open(derived.copy(
                shape = Shape.S2,
                risk = Risk(1, Reversibility.Hard, false),
                requirements = listOf(Requirement("R1", "a returns 10", listOf("AC-1"), authorityRef = derived.requests.single().id)),
                acceptance = listOf(Acceptance.Run("AC-1", printing, Origin.User)),
            ))
        }
    }

    private companion object {
        const val PLAN = """{"increments":[{"id":"I1","requirements":["R1"],"accept":["AC-1"],"write_scope":["src/"],"expected_files":1,"produces":"artifact"}]}"""
    }
}
