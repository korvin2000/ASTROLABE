package io.astrolabe.workflow

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignOutcome
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.CampaignRequest
import io.astrolabe.campaign.OpenedCampaign
import io.astrolabe.context.Carry
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Origin
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.telemetry.CountedPhase
import io.astrolabe.cell.CellFixture.Companion.anchored
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.patch
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.CellPacket
import io.astrolabe.context.Manifest
import io.astrolabe.contract.Shape
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WF-14 (task-workflow §4.1, §4.2, §4.7): knowledge survives a cell boundary, in this process and across a reopen. On the
 * dirty repository in S1, a cell reads 6 files, edits 2, records a decision and a dead end, moves its plan to a step that
 * names no path and ends `partial` at its turn budget; the next cell of the increment starts either in the same run or
 * after the host stops and reopens. Its `[K]` carry block is the same bytes both ways, names the 2 touched paths and at
 * least one seed — the fallback rule's, since the next step names no path — and the cell reads no more files before its
 * first model request than it was given seeds. With the cell's packet row cut from the store (a crash inside the old
 * two-step end), the reopen builds the carry from the checkpoint and export, the manifest says `packetMissing`, and the
 * run goes on. A follow-up of a completed parent starts from that parent (§4.5). All four play at once ([Scenario.concurrently]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundaryCarryScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    private val played by lazy {
        Scenario.concurrently(listOf(IN_PROCESS, REOPEN, PACKET_CUT, FOLLOW_UP)) { name -> if (name == FOLLOW_UP) followUp() else scenario(name) }
    }

    private fun of(name: String): Played = played.getValue(name).getOrThrow() as Played

    @Test
    fun `WF-14 the carry of a reopen is the carry of an in-process boundary and names the touched paths and a seed`() {
        val inProcess = of(IN_PROCESS)
        val reopened = of(REOPEN)
        assertEquals(inProcess.carry, reopened.carry, "the [K] carry block is the same bytes in process and after a reopen")
        for (path in listOf(DirtyRepo.SOURCE, UTIL)) assertTrue(Regex("Touched: .*${Regex.escape(path)}@").containsMatchIn(reopened.carry), "the carry names $path: ${reopened.carry}")
        assertTrue("Previous packet: partial" in reopened.carry, reopened.carry)
        assertTrue("move the clamp" in reopened.carry && "patching the test" in reopened.carry, "decisions and dead ends travel: ${reopened.carry}")
        assertTrue(reopened.seeds >= 1, "the fallback rule seeds the next cell: ${reopened.carry}")
        assertEquals("fallback", reopened.manifest.carry?.seedReason, "the manifest names the rule applied: ${reopened.manifest.carry}")
    }

    @Test
    fun `WF-14 the next cell after a reopen reads no more files before its first model request than its seeds`() {
        val reopened = of(REOPEN)
        assertTrue(reopened.readsBeforeFirstRequest <= reopened.seeds,
            "${reopened.readsBeforeFirstRequest} files read before the first model request with ${reopened.seeds} seeds")
    }

    @Test
    fun `WF-14 a cell whose packet row is missing still carries from its checkpoint and the run goes on`() {
        val cut = of(PACKET_CUT)
        assertEquals(true, cut.manifest.carry?.packetMissing, "the manifest records the missing packet: ${cut.manifest.carry}")
        assertTrue(Regex("Touched: .*${Regex.escape(DirtyRepo.SOURCE)}@").containsMatchIn(cut.carry), "the checkpoint's touched paths still travel: ${cut.carry}")
        assertTrue(cut.outcome != null && cut.outcome != "failed", "the run stops resumably, never failed: ${cut.outcome}")
    }

    @Test
    fun `WF-14 a follow-up's first request carries its parent's decisions, dead ends and STATUS and seeds the parent's touched paths`() {
        played.getValue(FOLLOW_UP).getOrThrow()
    }

    /** What one scenario left: cell 2's carry block, its seeds and manifest, and the reads before its first request. */
    private class Played(val carry: String, val seeds: Int, val manifest: Manifest, val readsBeforeFirstRequest: Long, val outcome: String?)

    private fun scenario(name: String): Played = runBlocking {
        DirtyRepo.create(10, bigBytes = 0).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(name)).use { s ->
                // S1: a partial cell continues its increment in a next cell of the same run (S0 has no continuation of its own).
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(dirty.check, shape = Shape.S1) { it.copy(budget = it.budget.copy(turnsPerCell = TURNS)) }
                s.open()
                var reads: Long? = null
                var start = 0L
                fun model(c: OpenedCampaign, first: List<Scripted>) = ScriptedModel(
                    (first + List(TURNS) { Scripted.Reply(listOf(say("looking"), call("t$it", "look", """{"what":"tree"}"""))) }).map { reply ->
                        ScriptedModel.Turn({ true }, { request -> if (reads == null && carried(request)) reads = c.workspace.filesRead - start; reply })
                    },
                )
                if (name == IN_PROCESS) {
                    s.playWith(maxCells = 2) { c -> model(c, cellOne(c)) }
                } else {
                    s.playWith(maxCells = 1) { c -> model(c, cellOne(c)) }
                    if (name == PACKET_CUT) s.campaign!!.store.db.tx { it.execute("DELETE FROM packets WHERE kind = ?", CellPacket.KIND) }
                    val reopened = s.reopen()
                    start = reopened.workspace.filesRead
                    s.playWith(maxCells = 1) { c -> model(c, emptyList()) }
                }
                val c = checkNotNull(s.campaign)
                val request = checkNotNull(s.adapter).calls.map { it.request }.firstOrNull(::carried)
                    ?: error("$name: no request carried a carry-forward; the run ended ${s.outcome?.wire}: ${s.last?.state?.reason}")
                val k = (request.segment(SegmentKind.K)!!.items.single() as Message).text
                val carry = k.substringAfter("## Carry-forward\n").substringBefore("\n## ")
                val seeds = Regex("KNOWN: seeds only \\((\\d+)\\)").find(carry)?.groupValues?.get(1)?.toInt() ?: 0
                val manifests = c.store.db.query("SELECT body FROM manifests ORDER BY rowid") { JSON.decodeFromString(Manifest.serializer(), it.string("body")) }
                val manifest = assertNotNull(manifests.lastOrNull { it.carry != null }, "the continuation's manifest records its carry")
                Played(carry, seeds, manifest, checkNotNull(reads) { "no request carried a carry-forward" }, s.outcome?.wire)
            }
        }
    }

    /**
     * WF-14 (task-workflow §4.5, №33): a parent work reads, edits two files, records a decision and a dead end and
     * completes; a follow-up opens with `parentWork` once, and its first request holds the parent's decisions, dead end and
     * STATUS under the data heading within `parentCarryMaxTokens`, the parent's touched paths as seeds, and the follow-up
     * reads no more files before that request than it was given seeds.
     */
    private fun followUp(): Unit = runBlocking {
        DirtyRepo.create(10, bigBytes = 0).use { dirty ->
            Scenario(dirty.root, stateRoot.resolve(FOLLOW_UP)).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(dirty.check, shape = Shape.S1)
                s.open()
                val parent = s.play(::parentCell)
                assertEquals(CampaignOutcome.Completed, parent.outcome, parent.state?.reason)

                s.policy = s.policy.copy(declaredChecks = listOf(Acceptance.Run("saved", dirty.check, Origin.User)))
                val opens = s.counted(CountedPhase.Open).size
                val followed = s.open(CampaignRequest(WorkId("W-2"), AttemptId("a1"), "also skip None items", parentWork = s.request.work))
                val start = followed.workspace.filesRead
                var reads: Long? = null
                s.playWith(maxCells = 1) { c ->
                    ScriptedModel(listOf(ScriptedModel.Turn({ true }, { _ ->
                        if (reads == null) reads = c.workspace.filesRead - start
                        Scripted.Reply(listOf(say("done")))
                    }, once = false)))
                }
                assertEquals(opens + 1, s.counted(CountedPhase.Open).size, "the follow-up opens once")
                val first = checkNotNull(s.adapter).calls.first().request
                val carry = carryBlock(first)
                assertTrue(carry.startsWith(Carry.DATA_HEADING) && "CARRY-FORWARD from parent W-1 · " in carry, carry)
                assertTrue("move the clamp before the sum" in carry && "patching the test" in carry, "the parent's decision and dead end: $carry")
                assertTrue("STATUS:" in carry, "the parent's STATUS: $carry")
                assertTrue(HeuristicEstimator().estimate(carry).tokens <= Defaults().parentCarryMaxTokens, "within parentCarryMaxTokens")
                val k = kText(first)
                for (path in listOf(DirtyRepo.SOURCE, UTIL)) assertTrue("SEED $path:" in k, "the parent's touched $path is a seed")
                val seeds = Regex("(?m)^SEED ").findAll(k).count()
                assertTrue(checkNotNull(reads) <= seeds, "$reads files read before the first model request with $seeds seeds")
            }
        }
    }

    /** The parent: reads and edits two files, records a decision and a dead end, verifies and reports done. */
    private fun parentCell(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE), read("r2", UTIL))),
        Scripted.Reply(listOf(
            say("editing"),
            anchored("e1", DirtyRepo.SOURCE, c.registry.version(DirtyRepo.SOURCE)!!, "    return sum(items)", "    return sum(x for x in items if x >= 0)"),
            anchored("e2", UTIL, c.registry.version(UTIL)!!, "    return max(lo, min(x, hi))", "    return min(hi, max(x, lo))"),
        )),
        Scripted.Reply(listOf(
            say("recording"),
            patch("s1", """{"decision.add":{"text":"move the clamp before the sum","because":"negatives must not count","rejected":"filter in the caller"}},""" +
                """{"deadend.add":{"text":"patching the test","evidence":null,"scope":"tests/","reopen":"the contract changes"}},{"next":"verify"}"""),
        )),
        Scripted.Reply(listOf(say("verifying"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1"]}"""))),
        Scripted.Reply(listOf(say("done"))),
    )

    private fun kText(request: Request): String = (request.segment(SegmentKind.K)!!.items.single() as Message).text

    private fun carryBlock(request: Request): String = kText(request).substringAfter("## Carry-forward\n").substringBefore("\n## ")

    /** Cell 1: reads 6 files, edits 2, records a decision and a dead end, points its plan at a step naming no path. */
    private fun cellOne(c: OpenedCampaign): List<Scripted> = listOf(
        Scripted.Reply(listOf(say("reading")) + READ.mapIndexed { i, path -> read("r$i", path) }),
        Scripted.Reply(listOf(
            say("editing"),
            anchored("e1", DirtyRepo.SOURCE, c.registry.version(DirtyRepo.SOURCE)!!, "    return sum(items)", "    return sum(x for x in items if x >= 0)"),
            anchored("e2", UTIL, c.registry.version(UTIL)!!, "    return max(lo, min(x, hi))", "    return min(hi, max(x, lo))"),
        )),
        Scripted.Reply(listOf(
            say("recording"),
            patch("s1", """{"plan.add":"finish the change"},{"plan.cursor":1},""" +
                """{"decision.add":{"text":"move the clamp before the sum","because":"negatives must not count","rejected":"filter in the caller"}},""" +
                """{"deadend.add":{"text":"patching the test","evidence":null,"scope":"tests/","reopen":"the contract changes"}},{"next":"verify the change"}"""),
        )),
    )

    private fun carried(request: Request): Boolean =
        (request.segment(SegmentKind.K)?.items?.singleOrNull() as? Message)?.text?.contains("## Carry-forward\n") == true

    private companion object {
        const val IN_PROCESS = "in-process"
        const val REOPEN = "reopen"
        const val PACKET_CUT = "packet-cut"
        const val FOLLOW_UP = "follow-up"
        const val TURNS = 4
        const val UTIL = "src/util.py"
        val READ = listOf(DirtyRepo.SOURCE, UTIL, "tests/test_app.py", DirtyRepo.DATA, DirtyRepo.OUTPUT, "${DirtyRepo.UNTRACKED_DIR}/pkg-0/file-0.txt")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
