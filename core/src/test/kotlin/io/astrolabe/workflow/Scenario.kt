package io.astrolabe.workflow

import io.astrolabe.Config
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
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.contract.SqliteContractRepository
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Authority
import io.astrolabe.event.Events
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.os.Git
import io.astrolabe.store.Store
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import java.nio.file.Path

/**
 * The workflow scenario harness of plan §7.2 (W0): the real composition — [Controller] open, a cell, the dispatcher and
 * the tools — over a directory, with the fake adapter as the only model. One instance is one host: one controller,
 * one event stream, one clock and one [FixedIdGen] (a second one collides on SQLite keys). Guards read counters from
 * [counted], never seconds.
 */
internal class Scenario(
    val root: Path,
    stateRoot: Path,
    text: String = "make total ignore negative items",
    configure: (Config) -> Config = { it },
) : AutoCloseable {
    val clock: FakeClock = FakeClock.at("2026-10-05T10:00:00Z")
    val idGen: FixedIdGen = FixedIdGen()
    val events: Events = Events(clock)
    val recorder: EventRecorder = EventRecorder().also { events.subscribe(it) }
    val config: Config = configure(Config(stateRoot = stateRoot.toString(), profiles = FakeProfiles.all))
    val controller: Controller = Controller(config, clock, idGen, events)
    val request: CampaignRequest = CampaignRequest(WorkId("W-1"), AttemptId("a1"), text)
    var policy: CampaignPolicy = CampaignPolicy(Tokens(200_000))

    /** The host's authority for the next runs: autonomous until [decide] answers final acceptance requests. */
    var host: Authority = AutonomousAuthority()

    /** The open campaign, if any; [open] and [reopen] replace it. */
    var campaign: OpenedCampaign? = null
        private set

    /** The last run's result and the adapter it ran on (its requests are the model calls). */
    var last: S0Run? = null
        private set
    var adapter: FakeAdapter? = null
        private set

    /**
     * Stores a contract whose acceptance is the `run:` item [check] before the first open (the harness derives the rest);
     * a [shape] replaces the derived one, its requirements accepted by [id] (S1 runs the campaign gate's end checks).
     */
    fun seed(check: Command, id: String = "AC-1", shape: Shape? = null) {
        check(campaign == null) { "seed before the first open" }
        Store.open(stateRoot(), Git(root), clock).use { store ->
            val contracts = Contracts(SqliteContractRepository(store, clock), idGen, clock)
            val derived = contracts.deriveS0(request.work, request.attempt, request.text, Atlas.build(root), Config(), policy.tokens).contract
            val seeded = derived.copy(acceptance = listOf(Acceptance.Run(id, check, Origin.Harness, scope = Contracts.TOUCHED)))
            contracts.open(if (shape == null) seeded else seeded.copy(shape = shape, requirements = seeded.requirements.map { it.copy(acceptance = listOf(id)) }))
        }
    }

    /** Opens the campaign (closing an open one first): one `phase.counted` open event per call. */
    fun open(): OpenedCampaign {
        campaign?.close()
        campaign = null
        return controller.open(root, request, policy).also { campaign = it }
    }

    /** Closes and opens again, as a host's resume does. */
    fun reopen(): OpenedCampaign = open()

    /** Plays one run of model replies against the open campaign (opening it when none is); [script] sees the campaign for file versions. */
    suspend fun play(script: (OpenedCampaign) -> List<Scripted>): S0Run {
        val c = campaign ?: open()
        val fake = FakeAdapter(ScriptedModel.of(*script(c).toTypedArray()))
        adapter = fake
        return controller.run(c, CellModel(fake, FakeProfiles.main, HeuristicEstimator()), host).also { last = it }
    }

    /** The host answers every final acceptance request with [answer] from now on (`null` leaves it waiting). */
    fun decide(answer: (AcceptanceDecisionRequest) -> AcceptanceDecision?) {
        host = object : Authority by AutonomousAuthority() {
            override suspend fun decide(request: AcceptanceDecisionRequest): AcceptanceDecision? = answer(request)
        }
    }

    /** Every `phase.counted` event emitted so far (delivery is asynchronous: this waits for it), optionally of one [phase]. */
    fun counted(phase: CountedPhase? = null): List<AgentEvent.Telemetry.PhaseCounted> {
        check(recorder.awaitCount(events.lastSeq.toInt())) { "events up to ${events.lastSeq} were not delivered" }
        return recorder.ofType<AgentEvent.Telemetry.PhaseCounted>().filter { phase == null || it.counted == phase.wire }
    }

    /** The campaign's outcome as stored now (`null` while it may still dispatch). */
    val outcome: CampaignOutcome? get() = campaign?.state?.outcome ?: last?.outcome

    override fun close() {
        try {
            campaign?.close()
        } finally {
            campaign = null
            events.close()
        }
    }

    private fun stateRoot(): Path = Path.of(config.stateRoot!!)

    companion object {
        /** The usual one-cell script: read [path], replace [anchor] by [replacement], verify [acceptance], report done. */
        fun editThenVerify(c: OpenedCampaign, path: String, anchor: String, replacement: String, acceptance: String = "AC-1"): List<Scripted> {
            val version = checkNotNull(c.registry.version(path)) { "$path is not in the tree" }
            return listOf(
                Scripted.Reply(listOf(say("reading"), read("c1", path))),
                Scripted.Reply(listOf(say("editing"), anchored("c2", path, version, anchor, replacement))),
                Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["$acceptance"]}"""))),
                Scripted.Reply(listOf(say("done"))),
            )
        }
    }
}
