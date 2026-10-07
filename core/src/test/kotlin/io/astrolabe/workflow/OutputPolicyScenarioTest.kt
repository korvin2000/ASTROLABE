package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Acceptances
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Command
import io.astrolabe.contract.Shape
import io.astrolabe.id.AttemptId
import kotlinx.serialization.json.JsonPrimitive
import io.astrolabe.evidence.Closure
import io.astrolabe.evidence.SqliteReceipts
import io.astrolabe.fixtures.Scripted
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.verify.AcceptanceDecision
import io.astrolabe.verify.AcceptanceDecisionRequest
import io.astrolabe.verify.Checks
import io.astrolabe.verify.Decider
import io.astrolabe.verify.DecisionKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WF-5, its output part (plan §7.2, W3, owner №32) on the dirty repository in S1: a check that writes only output under a
 * declared root — the fixture's `build/` — moves no candidate and voids no decision (c5); a check that rewrites its inputs
 * stops once with the paths named and typed (c15) — a tracked file under `build/` and a tracked source among them, which
 * stay in identity — and the user's accept of that candidate records it accepted; a red final that also rewrote an input
 * stays red whatever the host accepts (P1-4). The guards count requests, receipts and outcomes, so the fixture has one tool
 * file, no big file and no settling wait; the scenarios play at once on first use (plan §7.2 rule 3).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutputPolicyScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val plays: Map<String, () -> Unit> = mapOf(
        SCRATCH to { policy(SCRATCH, DirtyRepo.Variant.ScratchOutput, { scratchGate }, ::scratchOutput) },
        REWRITES to { policy(REWRITES, DirtyRepo.Variant.RewritesData, { rewrites }, ::rewrittenInputs) },
        RED to { policy(RED, DirtyRepo.Variant.RewritesData, { redRewrites }, ::redStaysRed) },
        OUTSIDE to { policy(OUTSIDE, DirtyRepo.Variant.RewritesData, { check }, ::outsideInputMoved) },
        DECLARED to { declaredOutput() },
        WAITED to { policy(WAITED, DirtyRepo.Variant.RewritesData, { check }, ::outsideInputMovedWhileAsked) },
        LIVE to { policy(LIVE, DirtyRepo.Variant.Plain, { check }, ::applyNowWhileRunning) },
        TOOLCHAIN to { toolchainKept() },
    )
    private val played by lazy { Scenario.concurrently(plays.keys) { plays.getValue(it)() } }

    @Test
    fun `WF-5 a final gate that writes only output under a declared root moves no candidate and asks nothing`() = played.getValue(SCRATCH).getOrThrow()

    @Test
    fun `a gate that rewrites its inputs stops once naming them, scratch written meanwhile voids no decision, and the accept records them accepted`() =
        played.getValue(REWRITES).getOrThrow()

    @Test
    fun `a red final gate that also rewrote an input stays red whatever the host accepts`() = played.getValue(RED).getOrThrow()

    @Test
    fun `a pinned input outside the candidate that changes while the final waits voids its stored results and the decision key`() =
        played.getValue(OUTSIDE).getOrThrow()

    @Test
    fun `T-06 T-07 a nested generated directory is outside identity, a declared output leaves it from the next attempt, and apply now opens it on the tree`() =
        played.getValue(DECLARED).getOrThrow()

    @Test
    fun `an accept of evidence whose pinned input moved while it was asked applies to nothing, and no later key is answered by it`() =
        played.getValue(WAITED).getOrThrow()

    @Test
    fun `apply now while the attempt's cell runs is refused before anything moves`() = played.getValue(LIVE).getOrThrow()

    @Test
    fun `a toolchain a check launches from under a generated-directory marker stays in identity, frozen in the policy and its id`() =
        played.getValue(TOOLCHAIN).getOrThrow()

    /**
     * P1 #1: the quality gate declares the untracked `build/input.json` (pinned, outside identity) and rewrites a tracked
     * file, so the final waits; the input changes while the decision is asked and the user accepts what they saw: the
     * accept applies to nothing, and the next request — a new key — is never answered by it.
     */
    private suspend fun outsideInputMovedWhileAsked(s: Scenario) {
        Files.createDirectories(s.root.resolve(INPUT).parent)
        Files.writeString(s.root.resolve(INPUT), "1\n")
        declareGateInput(s.campaign!!)
        val asked = ArrayList<AcceptanceDecisionRequest>()
        s.decide { r ->
            asked += r
            if (r.incrementId != null || asked.count { it.incrementId == null } > 1) null else {
                Files.writeString(s.root.resolve(INPUT), "2\n")
                AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "accepted as I saw it")
            }
        }
        val first = s.play(::edit)
        assertNotEquals(CampaignOutcome.Completed, first.outcome, "the accept was about an input that moved while it was asked: ${first.state?.reason}")
        assertTrue("inputs outside the candidate its evidence pinned changed" in first.state?.reason.orEmpty(), "the stop names the moved pinned input: ${first.state?.reason}")
        declareGateInput(s.reopen())
        val second = s.play { emptyList() }
        assertNotEquals(CampaignOutcome.Completed, second.outcome, "the earlier accept answers no later key: ${second.state?.reason}")
        val final = asked.filter { it.incrementId == null }
        assertEquals(2, final.size, "the final is asked again: ${final.map { it.key }}")
        assertNotEquals(final.first().key, final.last().key, "under a new key")
    }

    /** P1 #2 (task-workflow §5.1): "apply now" while the attempt's cell runs is refused before anything moves; the run keeps its records. */
    private suspend fun applyNowWhileRunning(s: Scenario) {
        io.astrolabe.campaign.DeclaredOutputs.declare(s.campaign!!, "reports/", io.astrolabe.contract.OutputDeclarer.User, "the task's report", clock = s.clock)
        var refused: Throwable? = null
        var turns = 0
        val run = s.playWith { o ->
            val version = checkNotNull(o.registry.version(DirtyRepo.SOURCE))
            io.astrolabe.fixtures.ScriptedModel(listOf(io.astrolabe.fixtures.ScriptedModel.Turn({ true }, { _ ->
                turns++
                when (turns) {
                    1 -> {
                        refused = runCatching { io.astrolabe.campaign.DeclaredOutputs.applyNow(o, AttemptId("a2"), s.clock) }.exceptionOrNull()
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("reading"), io.astrolabe.cell.CellFixture.read("c1", DirtyRepo.SOURCE)))
                    }
                    2 -> Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("editing"),
                        io.astrolabe.cell.CellFixture.anchored("c2", DirtyRepo.SOURCE, version, "    return sum(items)", "    return sum(x for x in items if x >= 0)")))
                    3 -> Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("verifying"), io.astrolabe.cell.CellFixture.call("c3", "verify", """{"what":"acceptance","ids":["AC-1"]}""")))
                    else -> Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("done")))
                }
            }, once = false)))
        }
        assertTrue(refused is IllegalStateException, "apply now under a running cell is refused: $refused")
        assertEquals(AttemptId("a1"), s.campaign!!.contract.attemptId, "the contract stays with the running attempt")
        assertNotNull(run.outcome, "the run ends on its own records: ${run.state?.reason}")
    }

    /**
     * P1 #4 (task-workflow §5.2, D-435): a toolchain a registered check launches from an untracked directory under a
     * generated-directory marker stays in identity; the attempt's effective policy freezes the exception and its id names it.
     */
    private fun toolchainKept() = runBlocking {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            Files.createDirectories(dirty.root.resolve(TOOL).parent)
            Files.writeString(dirty.root.resolve(TOOL), "echo lint\n")
            Scenario(dirty.root, stateRoot.resolve(TOOLCHAIN)).use { s ->
                s.seed(Command(listOf(TOOL)), shape = Shape.S1)
                val c = s.open()
                assertFalse(c.attempt.scratch.excludes(TOOL), "the launched toolchain is identity: ${c.attempt.scratch}")
                assertTrue(c.attempt.scratch.excludes("tools/.cache/other/x.txt"), "the rest of the marker's directory stays outside")
                assertNotEquals(io.astrolabe.verify.ScratchPolicy.BUILT_IN.id, c.attempt.scratch.id, "the policy id names the exception")
                assertTrue(TOOL in c.stamper.report(fresh = true).untracked.map { it.path }, "the stamp hashes the toolchain")
            }
        }
    }

    /**
     * Task-workflow §5.3 (D-435, WF-5, WF-3): under v3 a check writes `tests/__pycache__/x.pyc` (a generated-directory marker:
     * outside identity) and `reports/out.json` (moves the candidate: its own input, so it stops once naming it). The user declares `reports/`: the contract's version
     * moves, the running attempt's effective policy and receipts do not, and the model's own proposal of another path is
     * refused in auto mode with the policy named. The user applies it now: the next attempt opens on the tree with a new
     * `s0` under a policy naming `reports`, the check's rerun moves no candidate and stores nothing, and the tracked
     * `reports/README.md` stays in identity.
     */
    private fun declaredOutput() = runBlocking {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            dirty.repo.write("reports/README.md", "reports land here\n")
            dirty.repo.commit("tracked readme under reports")
            // The generated directory exists before the run, so the check's line starts with its output and is recognised as the pytest run it prints.
            Files.createDirectories(dirty.root.resolve("tests/__pycache__"))
            Scenario(dirty.root, stateRoot.resolve(DECLARED), configure = { it.copy(mode = io.astrolabe.Mode.Autonomous) }).use { s ->
                val asked = ArrayList<AcceptanceDecisionRequest>()
                s.decide { r -> asked += r; null }
                s.seed(generating, shape = Shape.S1)
                val first = s.open()
                val v3 = first.attempt.scratch
                assertEquals(io.astrolabe.verify.ScratchPolicy.BUILT_IN, v3, "a new attempt freezes v3")
                val run = s.play {
                    listOf(
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("running the check"), io.astrolabe.cell.CellFixture.call("c1", "run", """{"argv":${kotlinx.serialization.json.JsonArray(generating.argv.map(::JsonPrimitive))}}"""))),
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("declaring"), io.astrolabe.cell.CellFixture.call("c2", "task", """{"op":"propose","kind":"output","proposal":{"path":"logs/","reason":"the run's logs"}}"""))),
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("done"))),
                    )
                }
                assertEquals(CampaignOutcome.WaitingForInput, run.outcome, run.state?.reason)
                // Typed, not the reason's text: the reason echoes the check's argv, which names both paths (§5.3 "no flag").
                val flagged = asked.flatMap { it.items }.filter { it.obligation == "AC-1" }
                assertTrue(flagged.isNotEmpty() && flagged.all { it.rewrittenInputs == listOf(OUT) }, "the report moved the candidate, the marker's file did not: ${flagged.map { it.rewrittenInputs }} (${run.state?.reason})")
                val untracked = first.stamper.report(fresh = true).untracked.map { it.path }
                assertTrue(OUT in untracked && PYC !in untracked, "T-06: the marker's file is outside identity, the report is not: $untracked")
                assertTrue("autoDeclareOutputs" in s.adapter!!.calls.last().request.toString(), "the model's proposal is refused with the policy named")
                val before = first.contract.version
                val receipt = SqliteReceipts(first.store, s.clock).forCheck(Checks.acceptId("AC-1")).last()
                val askedBefore = asked.size

                assertEquals("reports/README.md is tracked: a tracked path is never excluded (D-429)", io.astrolabe.campaign.DeclaredOutputs.refusal(first, "reports/README.md"))
                val declared = io.astrolabe.campaign.DeclaredOutputs.declare(first, "reports/", io.astrolabe.contract.OutputDeclarer.User, "the task's report", clock = s.clock)
                assertEquals(before + 1 to listOf("reports"), declared.version to declared.outputs.map { it.path }, "T-07: a host-origin revision")
                assertEquals(v3.id, first.stamper.report(fresh = true).scratch.id, "the running attempt's effective policy does not move")
                assertEquals(receipt.stampAfter, first.stamper.stamp(fresh = true).id, "its receipts stay current")

                io.astrolabe.campaign.DeclaredOutputs.applyNow(first, AttemptId("a2"), s.clock)
                val next = s.open(s.request.copy(attempt = AttemptId("a2")))
                assertEquals(setOf("reports"), next.attempt.scratch.outputs, "the next attempt's effective policy names the declared output")
                assertTrue(next.attempt.scratch.id != v3.id && next.s0.stampId != first.s0.stampId, "a new s0 under the new policy")
                kotlin.test.assertFalse(next.attempt.scratch.excludes("reports/README.md", tracked = true), "the tracked file stays in identity")
                val rerun = s.play {
                    listOf(
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.call("c1", "run", """{"argv":${kotlinx.serialization.json.JsonArray(generating.argv.map(::JsonPrimitive))}}"""))),
                        Scripted.Reply(listOf(io.astrolabe.cell.CellFixture.say("done"))),
                    )
                }
                assertEquals(CampaignOutcome.Completed, rerun.outcome, rerun.state?.reason)
                val again = SqliteReceipts(next.store, s.clock).forCheck(Checks.acceptId("AC-1")).filter { it.ids.attempt == AttemptId("a2") }
                assertEquals(listOf(next.s0.stampId), again.map { it.stampAfter }, "one receipt, and the rerun moved no candidate")
                assertEquals(askedBefore, asked.size, "no decision request in the next attempt: ${asked.drop(askedBefore).map { it.items }}")
                val snapshots = s.counted(CountedPhase.Snapshot).filter { it.ids.attempt == AttemptId("a2") }
                assertEquals(0L, snapshots.sumOf { it.objectsWritten }, "the next attempt's snapshots store neither path: $snapshots")
            }
        }
    }

    private suspend fun scratchOutput(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        s.decide { r -> asked += r; null }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Completed, run.outcome, run.state?.reason)
        assertTrue(asked.isEmpty(), "no decision was asked: ${asked.map { it.items }}")
        assertTrue(Files.exists(s.root.resolve("build/report.txt")), "the gate wrote its output")
        val c = s.campaign!!
        val accept = SqliteReceipts(c.store, s.clock).forCheck(Checks.acceptId("AC-1"))
        assertEquals(1, accept.size, "AC-1 ran once: the gate's output did not move the candidate it certified")
        assertEquals(accept.single().stampAfter, c.stamper.stamp(fresh = true).id, "the final candidate is the one AC-1 certified")
        assertEquals(listOf(1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
        // T-28: the reopen's capture counts the output it kept out, and the host reads that count.
        assertEquals(1, s.reopen().scratchCount(), "build/report.txt is the one untracked output file kept out")
    }

    private suspend fun rewrittenInputs(s: Scenario) {
        val asked = ArrayList<AcceptanceDecisionRequest>()
        s.decide { r ->
            asked += r
            // Output under a declared root while the decision is asked (a dev server, a report): no move of the candidate (c5).
            Files.writeString(s.root.resolve("build/late.txt"), "written while asked\n")
            AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "the rewritten files are expected")
        }
        val run = s.play(::edit)
        val final = asked.single { it.incrementId == null }
        val item = final.items.single { it.rewrittenInputs.isNotEmpty() }
        assertEquals(listOf(TRACKED_OUTPUT, DirtyRepo.DATA, UTIL), item.rewrittenInputs, "c15: the rewritten inputs are named, typed: ${item.reason}")
        assertTrue(DirtyRepo.DATA in item.reason, item.reason)
        assertEquals(CampaignOutcome.Completed, run.outcome, "the accept of the candidate it saw applies: ${run.state?.reason}")
        val c = s.campaign!!
        val record = Acceptances(c.store, s.clock).decisions(s.request.work, s.request.attempt).single { it.incrementId == null }
        assertEquals(DecisionKind.Accept to Decider.User, record.decision.kind to record.decision.decider)
        assertTrue(item.obligation in record.obligations, "the rewritten obligation is accepted, never verified: ${record.obligations}")
        assertEquals(listOf(1), s.counted(CountedPhase.Finish).map { it.finishAttempts })
    }

    private suspend fun redStaysRed(s: Scenario) {
        s.decide { r -> AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.Policy, "studio:policy(auto)", "auto mode accepts what could not be verified") }
        val run = s.play(::edit)
        assertEquals(CampaignOutcome.Failed, run.outcome, run.state?.reason)
    }

    /**
     * WR P1-1: `AC-1` declares the untracked `build/input.json` (pinned, outside identity, D-429) and certifies; the gate's
     * rewrite of a tracked file leaves the final waiting. The input changes — the candidate does not — and the user then
     * accepts the request they saw. The host is Studio's shape: a kept answer is found by the request's key (D-430).
     */
    private suspend fun outsideInputMoved(s: Scenario) {
        Files.createDirectories(s.root.resolve(INPUT).parent)
        Files.writeString(s.root.resolve(INPUT), "1\n")
        declareInput(s.campaign!!)
        val asked = ArrayList<AcceptanceDecisionRequest>()
        val kept = HashSet<String>()
        s.decide { r ->
            asked += r
            if (r.key in kept) AcceptanceDecision(r.id, r.contractRevision, r.candidate, DecisionKind.Accept, Decider.User, "user", "accepted as it is") else null
        }
        val first = s.play(::edit)
        assertEquals(CampaignOutcome.WaitingForInput, first.outcome, first.state?.reason)
        val request = asked.last { it.incrementId == null }
        val stamp = s.campaign!!.stamper.stamp(fresh = true).id

        Files.writeString(s.root.resolve(INPUT), "2\n")
        assertEquals(stamp, s.campaign!!.stamper.stamp(fresh = true).id, "the input is outside the candidate")
        kept += request.key
        declareInput(s.reopen())
        val before = receipts(s.campaign!!)
        val second = s.play { emptyList() }
        assertEquals(CampaignOutcome.WaitingForInput, second.outcome, "the accept of the earlier request no longer applies: ${second.state?.reason}")
        val reasked = asked.last { it.incrementId == null }
        assertNotEquals(request.key, reasked.key, "the decision key moved with the input")
        assertNotEquals(request.id, reasked.id, "a new request")
        assertTrue(reasked.items.any { it.obligation == "AC-1" && INPUT in it.reason }, "AC-1's evidence no longer holds: ${reasked.items.map { it.reason }}")
        assertTrue(receipts(s.campaign!!) > before, "the stored results were not reused: the end checks ran again")

        kept += reasked.key
        declareInput(s.reopen())
        val third = s.play { emptyList() }
        assertEquals(CampaignOutcome.Completed, third.outcome, third.state?.reason)
    }

    /** The quality gate with a known closure: the tracked data file it rewrites and the untracked input under `build/`. */
    private fun declareGateInput(c: OpenedCampaign) {
        val gate = c.checks.all().single { it.kind == io.astrolabe.verify.CheckKind.Quality }
        c.checks.replace(gate.copy(inputClosure = Closure.Known(setOf(DirtyRepo.DATA, INPUT))))
    }

    /** `AC-1` with a known closure: the printed output and the untracked input under `build/`. */
    private fun declareInput(c: OpenedCampaign) {
        val check = checkNotNull(c.checks[Checks.acceptId("AC-1")])
        c.checks.replace(check.copy(inputClosure = Closure.Known(setOf(DirtyRepo.OUTPUT, INPUT))))
    }

    private fun receipts(c: OpenedCampaign): Int = c.store.db.query("SELECT body FROM receipts") { it.string("body") }.size

    /** The plan cell is skipped (the acceptance is executable); one cell edits, verifies `AC-1` and reports done. */
    private fun edit(c: OpenedCampaign): List<Scripted> = Scenario.editThenVerify(c, DirtyRepo.SOURCE, "    return sum(items)", "    return sum(x for x in items if x >= 0)")

    /** S1 over the dirty repository with `AC-1` a plain printing check and [gate] the campaign's quality gate; [name] keeps its store apart. */
    private fun policy(name: String, variant: DirtyRepo.Variant, gate: DirtyRepo.() -> Command, body: suspend (Scenario) -> Unit) = runBlocking {
        DirtyRepo.create(1, variant, bigBytes = 0, settle = false).use { dirty ->
            dirty.repo.write(TRACKED_OUTPUT, "[1]\n")
            dirty.repo.commit("tracked output under build")
            Files.writeString(dirty.root.resolve(FAILING), recorded("pytest-fail-param.txt"))
            val quality = dirty.gate()
            Scenario(dirty.root, stateRoot.resolve(name), configure = { it.copy(qualityGates = listOf(quality)) }).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(printing, shape = Shape.S1)
                s.open()
                body(s)
            }
        }
    }

    private companion object {
        const val SCRATCH = "scratch"
        const val REWRITES = "rewrites"
        const val RED = "red"
        const val OUTSIDE = "outside"
        const val DECLARED = "declared"
        const val WAITED = "waited"
        const val LIVE = "live"
        const val TOOLCHAIN = "toolchain"
        const val TOOL = "tools/.cache/bin/lint.cmd"
        const val OUT = "reports/out.json"
        const val PYC = "tests/__pycache__/x.pyc"
        const val INPUT = "build/input.json"
        const val FAILING = "pytest_fail.txt"
        const val TRACKED_OUTPUT = "build/keep.json"
        const val UTIL = "src/util.py"

        val printing: Command = shell("type ${DirtyRepo.OUTPUT}", "cat ${DirtyRepo.OUTPUT}")

        /**
         * The scratch variant's write without its `if not exist … mkdir` prelude (`build/` is there: the tracked output),
         * so the line is recognised as the pytest run it prints, as the data variant's is.
         */
        val scratchGate: Command = shell("echo scratch> build\\report.txt & type ${DirtyRepo.OUTPUT}", "echo scratch > build/report.txt; cat ${DirtyRepo.OUTPUT}")

        /** Passes, and rewrites a tracked data file, a tracked file under `build/` and a tracked source. */
        val rewrites: Command = shell(
            "echo [2]> data\\notes.json & echo [2]> build\\keep.json & echo x = 1> src\\util.py & type ${DirtyRepo.OUTPUT}",
            "echo [2] > data/notes.json; echo [2] > build/keep.json; echo x = 1 > src/util.py; cat ${DirtyRepo.OUTPUT}",
        )

        /** Passes, and writes a generated file under a nested marker and a report under `reports/` (task-workflow §5.3). */
        val generating: Command = shell(
            "echo pyc> tests\\__pycache__\\x.pyc & echo {}> reports\\out.json & type ${DirtyRepo.OUTPUT}",
            "echo pyc > tests/__pycache__/x.pyc; echo {} > reports/out.json; cat ${DirtyRepo.OUTPUT}",
        )

        /** Fails, and rewrites the tracked data file. */
        val redRewrites: Command = shell("echo [3]> data\\notes.json & type $FAILING & exit /b 1", "echo [3] > data/notes.json; cat $FAILING; exit 1")

        fun shell(windows: String, posix: String): Command =
            if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

        fun recorded(name: String): String =
            OutputPolicyScenarioTest::class.java.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
