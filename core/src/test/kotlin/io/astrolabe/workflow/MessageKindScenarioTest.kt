package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Acceptances
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.Messages
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.campaign.PendingStatus
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.IncrementStatus
import io.astrolabe.contract.MessageKind
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Shape
import io.astrolabe.event.AgentEvent
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.graph.Production
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import io.astrolabe.evidence.SqliteReceipts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WF-13, WF-10, WF-1 and WF-11's core part (task-workflow §1.5, §2.5; W7) on the dirty repository in S1: a message sent to a
 * work whose increments are all closed reaches a model in the same open — a note on the acceptance card does not, its
 * Send to agent does — and only the explicit "change the task" raises the revision; the objective is the request plus the
 * amendments; an exception inside a cell stops the run resumably and the next open continues the same work; a project the
 * core derives no check for opens once with the host's declared check; a follow-up opens only after a final parent. The
 * guards count opens, model requests, receipts and finalization attempts, so the fixture has one tool file, no big file
 * and no settling wait, and the scenarios play at once on first use (plan §7.2 rule 3). WF-4's locked-file play keeps its
 * own fixture (ten tool files, the big file, the settling wait).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MessageKindScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(
        CARD to { scenario(CARD, gate = true, body = ::cardNoteThenSend) },
        STOPPED to { scenario(STOPPED, gate = true, body = ::stoppedThenSteeringThenAmendment) },
        RUNNING to { scenario(RUNNING, body = ::continuationWhileRunning) },
        FAILED to { scenario(FAILED, body = ::cellExceptionContinues) },
        FOLLOW to { scenario(FOLLOW, seeded = false, body = ::declaredCheckOpensOnceThenFollowUp) },
        LOCKED to { scenario(LOCKED, variant = DirtyRepo.Variant.LockedFile, body = ::unreadableAtReopen) },
        UNREADABLE to { lockedFileStopsResumably() },
    )
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `WF-13 a note on the open card reaches no model, its Send to agent does, and the same request is asked again until the accept completes`() =
        played.getValue(CARD).getOrThrow()

    @Test
    fun `WF-13 untyped text to a stopped work is steering that reaches a model, and only the explicit change raises the revision and supersedes its request`() =
        played.getValue(STOPPED).getOrThrow()

    @Test
    fun `a continuation sent while a cell runs keeps the revision and is pinned in the cell's next request`() = played.getValue(RUNNING).getOrThrow()

    @Test
    fun `WF-10 an exception inside a cell stops the run resumably and the next open continues the same work`() = played.getValue(FAILED).getOrThrow()

    @Test
    fun `WF-1 a project with no sniffed check opens once with the host's declared check, and a follow-up opens only after a final parent`() =
        played.getValue(FOLLOW).getOrThrow()

    @Test
    fun `an input another process holds at a reopen stops the work resumably by its path instead of throwing`() = played.getValue(LOCKED).getOrThrow()

    @Test
    fun `WF-4 a locked file stops the run resumably and names the path`() = played.getValue(UNREADABLE).getOrThrow()

    /** §2.5 (a): the card's note, then its Send to agent, then the user's accept of the request still asked. */
    private suspend fun cardNoteThenSend(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        var answer: AcceptanceDecision? = null
        s.decide { r -> asked += r; answer?.takeIf { it.requestId == r.id } }
        val first = s.play(::edit)
        assertEquals(CampaignOutcome.WaitingForInput, first.outcome, first.state?.reason)
        val k = asked.last { it.incrementId == null }
        val c = s.campaign!!
        // A note stays on the card: untyped text while the decision is pending is no message to the executor (D-430).
        assertFailsWith<IllegalStateException> { Messages.record(c.contracts, c.store, s.clock, s.request.work, null, "also the empty list") }
        assertEquals(1, c.contract.version)
        assertEquals(1, c.contract.requests.size, "the note recorded nothing")
        assertEquals(k.id, Acceptances(c.store, s.clock).open(s.request.work, s.request.attempt)?.requestId, "the request stands under its id")

        // Send to agent: the note as steering, delivered twice by a retrying host and recorded once.
        repeat(2) { Messages.record(c.contracts, c.store, s.clock, s.request.work, MessageKind.Steering, "also the empty list", hostRef = "card-${k.id}") }
        val sent = c.contract.requests.last()
        assertEquals(2, c.contract.requests.size, "a retried delivery is recorded once")
        assertEquals(MessageKind.Steering, sent.kind)
        assertEquals(1, c.contract.version, "steering changes no revision")
        val finishes = s.counted(CountedPhase.Finish).size
        s.reopen()
        val endChecks = endCheckReceipts(s)
        val second = s.play { o -> respond(o) }
        assertTrue(s.adapter!!.calls.isNotEmpty(), "WF-13: the sent note reached no model")
        val response = s.campaign!!.state!!.graph.increments.single { it.id == "inc-${sent.id}" }
        assertEquals(Production.Resolves(sent.id), response.produces)
        // P1 #7: a response takes regression obligations only — AC-1 is the goal's — and is resolved by its packet.
        assertEquals(emptyList(), response.accept, "the response inherits no goal check")
        assertEquals(IncrementStatus.Verified, response.status, second.state?.reason)
        assertEquals(CampaignOutcome.WaitingForInput, second.outcome, second.state?.reason)
        val again = asked.last { it.incrementId == null }
        assertEquals(k.id to k.key, again.id to again.key, "WF-5, WF-6: the same request is asked again under its key")
        assertEquals(k.obligationSet, again.obligationSet, "T-11: the request carries its obligation set")
        assertTrue(k.obligationSet != null, "T-11: a campaign request names its obligation set")
        assertEquals(endChecks, endCheckReceipts(s), "no end check runs again for an unchanged candidate")
        assertEquals(finishes + 1, s.counted(CountedPhase.Finish).size, "one finalization attempt more")

        answer = AcceptanceDecision(k.id, k.contractRevision, k.candidate, DecisionKind.Accept, Decider.User, "user", "accepted")
        s.reopen()
        val held = receipts(s.campaign!!)
        val done = s.play { emptyList() }
        assertEquals(CampaignOutcome.Completed, done.outcome, done.state?.reason)
        assertEquals(held, receipts(s.campaign!!), "WF-6: no check runs after the accept")
        assertTrue(s.adapter!!.calls.isEmpty(), "WF-6: no model request after the accept")
    }

    /** §2.5 (b) and §1.5: a stop with every increment verified and nothing pending, untyped text, then the explicit change. */
    private suspend fun stoppedThenSteeringThenAmendment(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        var editing = true
        s.decide { r ->
            asked += r
            if (!editing) null else {
                editing = false
                Files.writeString(s.root.resolve("src/util.py"), "def clamp(x, lo, hi):\n    return min(max(x, lo), hi)\n")
                AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "done")
            }
        }
        val first = s.play(::edit)
        assertEquals(CampaignOutcome.BlockedExternal, first.outcome, first.state?.reason)
        val c = s.campaign!!
        assertNull(Acceptances(c.store, s.clock).open(s.request.work, s.request.attempt), "nothing is pending")
        val u1 = c.contract.requests.single()

        Messages.record(c.contracts, c.store, s.clock, s.request.work, null, "keep clamp's argument order")
        val steering = c.contract.requests.last()
        assertEquals(MessageKind.Steering, steering.kind, "untyped text to a stopped work is steering")
        assertEquals(1, c.contract.version)
        assertEquals(u1.text, c.contract.objective, "WD-24: steering is not the objective")
        val opens = s.counted(CountedPhase.Open).size
        s.reopen()
        val second = s.play { o -> respond(o) }
        assertEquals(opens + 1, s.counted(CountedPhase.Open).size, "exactly one open")
        assertTrue(s.adapter!!.calls.isNotEmpty(), "WF-13: the message reached no model")
        val pinned = texts(s.adapter!!.calls.first().request)
        assertTrue(u1.text in pinned && steering.text in pinned, "WF-11: the first request pins U1 and the message verbatim")
        val graph = s.campaign!!.state!!.graph
        assertEquals(IncrementStatus.Verified, graph.increments.single { it.id == "inc-1" }.status, "verified work is never reopened")
        assertTrue(graph.increments.any { it.id == "inc-${steering.id}" }, "a response increment: ${graph.increments.map { it.id }}")
        // W9 (task-workflow §4.3): the response increment's first cell starts from inc-1's last cell, as data, by its source.
        val k = (s.adapter!!.calls.first().request.segment(io.astrolabe.provider.SegmentKind.K)!!.items.single() as Message).text
        assertTrue(Regex("CARRY-FORWARD from inc-1 · cell-\\d+").containsMatchIn(k) && "Touched: ${DirtyRepo.SOURCE}@" in k, "the previous increment's carry: $k")
        assertTrue(Regex("carried from inc-1 · cell-\\d+").containsMatchIn(pinned), "the resume note names the source: $pinned")
        assertEquals(1, s.campaign!!.contract.version)
        assertEquals(CampaignOutcome.WaitingForInput, second.outcome, second.state?.reason)
        val old = asked.last { it.incrementId == null }
        assertEquals(1, old.contractRevision)

        // The explicit "change the task": the one way to an amendment.
        Messages.record(s.campaign!!.contracts, s.campaign!!.store, s.clock, s.request.work, MessageKind.Amendment, "clamp also accepts lo above hi")
        val amended = s.campaign!!.contract
        val u3 = amended.requests.last()
        assertEquals(2, amended.version)
        assertEquals(MessageKind.Amendment, u3.kind)
        assertEquals("${u1.id}: ${u1.text}\namended by ${u3.id}: ${u3.text}", amended.objective, "the objective is the request plus the amendment")
        assertEquals(u3.text, amended.requirement("R2")?.text, "R2 is the amendment verbatim")
        assertEquals(u3.id, amended.requirement("R2")?.authorityRef)
        s.reopen()
        val calls = 0
        val third = s.play { o -> respond(o) }
        assertTrue(s.adapter!!.calls.size > calls, "the amendment reached a model")
        val after = s.campaign!!.state!!
        assertEquals(IncrementStatus.Verified, after.graph.increments.single { it.id == "inc-1" }.status, "inc-1 stays verified")
        assertEquals(listOf("R2"), after.graph.increments.single { it.id == "inc-2" }.requirementIds)
        val record = Acceptances(s.campaign!!.store, s.clock).pending(s.request.work, s.request.attempt).single { it.requestId == old.id }
        assertEquals(PendingStatus.Superseded, record.status, "the request of revision 1 is superseded, its record kept: ${record.closedReason}")
        assertEquals(CampaignOutcome.WaitingForInput, third.outcome, third.state?.reason)
        assertEquals(2, asked.last { it.incrementId == null }.contractRevision, "a new request is asked at revision 2")
    }

    /** §2.5 (c): a continuation while the cell runs, at its third request. */
    private suspend fun continuationWhileRunning(s: Scenario) {
        var requests = 0
        s.playWith { c ->
            val version = checkNotNull(c.registry.version(DirtyRepo.SOURCE))
            ScriptedModel(listOf(ScriptedModel.Turn({ true }, { _ ->
                requests++
                when (requests) {
                    1 -> Scripted.Reply(listOf(say("reading"), io.astrolabe.cell.CellFixture.read("c1", DirtyRepo.SOURCE)))
                    2 -> Scripted.Reply(listOf(say("editing"), io.astrolabe.cell.CellFixture.anchored("c2", DirtyRepo.SOURCE, version, "    return sum(items)", "    return sum(x for x in items if x >= 0)")))
                    3 -> {
                        Messages.record(c.contracts, c.store, s.clock, s.request.work, MessageKind.Continuation, "go on, and keep it short")
                        Scripted.Reply(listOf(say("verifying"), call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}""")))
                    }
                    else -> Scripted.Reply(listOf(say("done")))
                }
            }, once = false)))
        }
        val c = s.campaign!!
        assertEquals(1, c.contract.version, "a continuation keeps the revision")
        assertEquals(MessageKind.Continuation, c.contract.requests.last().kind)
        val fourth = s.adapter!!.calls.getOrNull(3)?.request
        assertNotNull(fourth, "the cell went on after the message")
        assertTrue("go on, and keep it short" in texts(fourth), "the cell's next request pins the message")
        // T-36: the running cell consumed the message, so it opens no response increment and costs no model request after it.
        val message = c.contract.requests.last()
        assertTrue(c.state!!.graph.increments.none { it.id == "inc-${message.id}" }, "no response increment: ${c.state!!.graph.increments.map { it.id }}")
        assertEquals(4, s.adapter!!.calls.size, "no extra model request after the cell that consumed the message")
    }

    /** WF-10 (T-02): the model's adapter throws inside the cell; the reopen continues the same work and completes it. */
    private suspend fun cellExceptionContinues(s: Scenario) {
        val failed = s.playWith { ScriptedModel(listOf(ScriptedModel.Turn({ true }, { _ -> throw IllegalStateException("adapter exploded") }))) }
        val state = assertNotNull(failed.state)
        assertEquals(CampaignOutcome.Failed, state.outcome, state.reason)
        assertTrue(state.resumable && state.failedResumably, "a cell's exception is a resumable stop: ${state.reason}")
        assertTrue("adapter exploded" in state.reason.orEmpty(), "the stop names the exception: ${state.reason}")
        Messages.record(s.campaign!!.contracts, s.campaign!!.store, s.clock, s.request.work, null, "try again")
        s.reopen()
        val done = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, done.outcome, done.state?.reason)
        assertEquals(s.request.work, s.campaign!!.ids.work, "the same work")
        assertEquals(listOf(1, 2), s.counted(CountedPhase.Open).map { it.opens }, "one open per host action")
    }

    /** WF-1 (T-01) and §1.3–1.4: one open with the host's declared check; a follow-up only after a final parent. */
    private suspend fun declaredCheckOpensOnceThenFollowUp(s: Scenario) {
        s.policy = s.policy.copy(declaredChecks = listOf(Acceptance.Run("saved", printing, Origin.User)))
        val opened = s.open()
        assertNotNull(opened.state, "the declared check makes the contract executable: ${opened.stop?.reason}")
        assertEquals(1, opened.contract.version, "no host amendment")
        assertTrue(opened.contract.acceptance.any { it is Acceptance.Run && it.command == printing }, "${opened.contract.acceptance}")

        val follow = CampaignRequest(WorkId("W-2"), AttemptId("a1"), "and the docs", parentWork = s.request.work)
        val refused = assertFailsWith<IllegalArgumentException> { s.open(follow) }
        assertTrue("can be continued" in refused.message.orEmpty(), refused.message)
        s.open()
        val done = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, done.outcome, done.state?.reason)
        assertEquals(1, s.counted(CountedPhase.Open).first().opens, "WF-1: the first action opened once")
        assertFailsWith<IllegalStateException> { Messages.record(s.campaign!!.contracts, s.campaign!!.store, s.clock, s.request.work, null, "one more thing") }

        val followed = s.open(follow)
        assertEquals(s.request.work, followed.contract.parentWork, "the follow-up records its parent")
        assertTrue(s.counted().isNotEmpty())
        val event = s.recorder.ofType<AgentEvent.Campaign.Opened>().last()
        assertEquals(s.request.work.value, event.parentWork)
    }

    /** T-24: the reopen meets a held file: a resumable stop that names it, no exception, no model request. */
    private suspend fun unreadableAtReopen(s: Scenario) {
        val dirty = checkNotNull(locked)
        when (val lock = dirty.lock()) {
            is DirtyRepo.Lock.Unsupported -> assumeTrue(false, lock.reason)
            is DirtyRepo.Lock.Held -> lock.use {
                val reopened = runCatching { s.reopen() }
                assertNull(reopened.exceptionOrNull(), "the open returns instead of throwing: ${reopened.exceptionOrNull()}")
                val c = reopened.getOrThrow()
                assertEquals(DirtyRepo.LOCKED, c.unreadable)
                val stop = assertNotNull(c.stop, "the open stops the work")
                assertEquals(CampaignOutcome.BlockedExternal, stop.outcome, stop.reason)
                assertTrue(DirtyRepo.LOCKED in stop.reason, stop.reason)
                val run = s.play { emptyList() }
                assertEquals(CampaignOutcome.BlockedExternal, run.outcome)
                assertTrue(s.adapter!!.calls.isEmpty(), "no model request while the input is held")
            }
        }
    }

    /**
     * Plan §7.2 WF-4: a file another process holds (a tool's lock file, as Gradle's was in R2) does not end the run. The
     * outcome is a resumable stop that names the path — never a failed cell, a thrown run (`agent_error` in a host) or a
     * candidate that silently leaves the file out.
     */
    private fun lockedFileStopsResumably(): Unit = runBlocking {
        DirtyRepo.create(10, DirtyRepo.Variant.LockedFile).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(UNREADABLE)).use { s ->
                s.seed(dirty.check)
                s.open()
                when (val lock = dirty.lock()) {
                    is DirtyRepo.Lock.Unsupported -> assumeTrue(false, lock.reason)
                    is DirtyRepo.Lock.Held -> lock.use {
                        val played = runCatching {
                            s.play { c -> Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)") }
                        }
                        assertNull(played.exceptionOrNull(), "the run returns an outcome instead of throwing: ${played.exceptionOrNull()?.stackTraceToString()?.take(4000)}")
                        val state = assertNotNull(played.getOrThrow().state)
                        val outcome = assertNotNull(state.outcome, "the run stops: ${state.reason}")
                        assertTrue(outcome.resumable, "the stop is resumable, not ${outcome.wire}: ${state.reason}")
                        assertTrue(DirtyRepo.LOCKED in state.reason.orEmpty(), "the stop names ${DirtyRepo.LOCKED}: ${state.reason}")
                    }
                }
            }
        }
    }

    @Volatile
    private var locked: DirtyRepo? = null

    /** The response cell: it checks the regression item and reports done without an edit. */
    private fun respond(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("checking"), call("r1", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
        Scripted.Reply(listOf(say("nothing to change"))),
    )

    private fun edit(c: OpenedCampaign): List<Scripted> = Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    private fun texts(request: Request): String = request.items.filterIsInstance<Message>().joinToString("\n") { it.text }

    private fun receipts(c: OpenedCampaign): Int = c.store.db.query("SELECT body FROM receipts") { it.string("body") }.size

    /** Receipts of every check but `AC-1`: the campaign end's checks. */
    private fun endCheckReceipts(s: Scenario): Int = s.campaign!!.let { c -> receipts(c) - SqliteReceipts(c.store, s.clock).forCheck(Checks.acceptId("AC-1")).size }

    /** S1 over the dirty repository with `AC-1` a printing check; [gate] adds the fixture's data-rewriting quality gate (a final decision). */
    private fun scenario(
        name: String,
        gate: Boolean = false,
        seeded: Boolean = true,
        variant: DirtyRepo.Variant = DirtyRepo.Variant.RewritesData,
        body: suspend (Scenario) -> Unit,
    ) = runBlocking {
        DirtyRepo.create(if (variant == DirtyRepo.Variant.LockedFile) 2 else 1, variant, bigBytes = 0, settle = false).use { dirty ->
            if (variant == DirtyRepo.Variant.LockedFile) locked = dirty
            Scenario(dirty.root, stateRoot.resolve(name), configure = { if (gate) it.copy(qualityGates = listOf(dirty.check)) else it }).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                if (seeded) {
                    s.seed(printing, shape = Shape.S1)
                    s.open()
                }
                body(s)
            }
        }
    }

    private companion object {
        const val CARD = "card"
        const val STOPPED = "stopped"
        const val RUNNING = "running"
        const val FAILED = "failed"
        const val FOLLOW = "follow"
        const val LOCKED = "locked"
        const val UNREADABLE = "unreadable"

        val printing: Command = if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", "type ${DirtyRepo.OUTPUT}")) else Command(listOf("/bin/sh", "-c", "cat ${DirtyRepo.OUTPUT}"))
    }
}
