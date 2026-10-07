package io.astrolabe.workflow

import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.CampaignPolicy
import io.astrolabe.campaign.Messages
import io.astrolabe.cell.CellFixture.Companion.call
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.context.ContractSlice
import io.astrolabe.contract.MessageKind
import io.astrolabe.contract.Shape
import io.astrolabe.event.Answer
import io.astrolabe.event.Authority
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.event.Question
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.provider.Item
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Text
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * WF-15 (task-workflow §4.4, §4.7): appending does not reset the cache. A running cell at its third request gets a
 * steering message from the host, then asks a question the host answers as a fact, then asks again and the answer
 * changes the requirements. Every request's `[S][R][K][T]` bytes are a prefix of the next request's: the message and the
 * answers arrive after the transcript as pinned items, the revision as a `[contract v2 delta]` item, and `[K]` keeps its
 * bytes while the harness reads the current contract — the projection boundary is real.
 */
class AppendOnlyPrefixScenarioTest {
    @TempDir
    lateinit var stateRoot: Path

    @Test
    fun `WF-15 a steering message, a factual answer and an amending answer append to the transcript and keep every request a prefix`() = runBlocking {
        DirtyRepo.create(1, bigBytes = 0, settle = false).use { dirty ->
            Scenario(dirty.root, stateRoot).use { s ->
                s.policy = CampaignPolicy(Tokens(200_000), resumeExpected = true)
                s.seed(dirty.check, shape = Shape.S1)
                s.open()
                s.host = object : Authority by AutonomousAuthority() {
                    override suspend fun ask(question: Question): Answer =
                        if ("factual" in question.text) Answer(question.id, question.contractRevision, "the list holds integers only")
                        else Answer(question.id, question.contractRevision, "zero counts as negative too", changesRequirements = true)
                }
                var n = 0
                val versions = ArrayList<Int>()
                s.playWith(maxCells = 1) { c ->
                    ScriptedModel(listOf(ScriptedModel.Turn({ true }, { _ ->
                        n++
                        versions += c.contracts.current(s.request.work)!!.version
                        when (n) {
                            1 -> Scripted.Reply(listOf(say("reading"), read("r1", DirtyRepo.SOURCE)))
                            2 -> Scripted.Reply(listOf(say("reading more"), read("r2", "src/util.py")))
                            3 -> {
                                Messages.record(c.contracts, c.store, s.clock, s.request.work, MessageKind.Steering, "prefer a generator expression")
                                Scripted.Reply(listOf(say("reading the test"), read("r3", "tests/test_app.py")))
                            }
                            4 -> Scripted.Reply(listOf(say("asking"), call("q1", "task", """{"op":"ask","question":"a factual question: what does the list hold?"}""")))
                            5 -> Scripted.Reply(listOf(say("asking again"), call("q2", "task", """{"op":"ask","question":"is zero negative here?"}""")))
                            6 -> Scripted.Reply(listOf(say("verifying"), call("v1", "verify", """{"what":"acceptance","ids":["AC-1"]}""")))
                            else -> Scripted.Reply(listOf(say("done")))
                        }
                    }, once = false)))
                }
                val requests = checkNotNull(s.adapter).calls.map { it.request }
                assertTrue(requests.size >= 6, "six requests at least: ${requests.size}")
                for (i in 1 until 6) {
                    val (before, after) = requests[i - 1] to requests[i]
                    for (kind in listOf(SegmentKind.S, SegmentKind.R, SegmentKind.K)) assertEquals(before.segment(kind), after.segment(kind), "request ${i + 1}: [$kind] keeps its bytes")
                    val stable = rendered(before)
                    assertTrue(rendered(after).startsWith(stable), "request ${i + 1}: the ${stable.length} stable bytes of request $i are its prefix")
                }
                val t = { i: Int -> requests[i - 1].segment(SegmentKind.T)!!.items.filterIsInstance<Message>().map { it.text } }
                assertTrue(t(4).any { it.startsWith("[pinned U-") && "prefer a generator expression" in it }, "the steering message is appended: ${t(4)}")
                assertTrue(t(5).any { it.startsWith("[pinned answer ") && "integers only" in it }, "the factual answer is appended: ${t(5)}")
                assertTrue(t(6).any { it.startsWith("[contract v2 delta]") }, "the amending answer appends its delta: ${t(6)}")
                assertEquals(listOf(1, 1, 1, 1, 1, 2), versions.take(6), "the steering and the factual answer keep the revision; the amending answer raises it")
                val k = (requests[5].segment(SegmentKind.K)!!.items.single() as Message).text
                val c = checkNotNull(s.campaign)
                val increment = c.state!!.graph.increments.first { it.id == c.state!!.cells.first().increment }
                val current = ContractSlice.forIncrement(c.contract, increment).render()
                assertTrue(k.startsWith("[K] contract v1 "), "[K] stays as built: ${k.lineSequence().first()}")
                assertNotEquals(current, k.substring(0, minOf(k.length, current.length)), "the harness's slice of v2 is not the rendered [K]")
            }
        }
    }

    /** The request's cached regions as text, in order: what a provider's prefix cache compares. */
    private fun rendered(request: Request): String = buildString {
        for (kind in listOf(SegmentKind.S, SegmentKind.R, SegmentKind.K, SegmentKind.T)) {
            request.segment(kind)?.items?.forEach { append(kind.name).append('|').append(text(it)).append('\u0000') }
        }
    }

    private fun text(item: Item): String = when (item) {
        is Message -> "${item.role}:${item.text}"
        is ToolCall -> "call:${item.id}:${item.name}:${item.argsJson}"
        is ToolResult -> "result:${item.callId}:" + item.content.filterIsInstance<Text>().joinToString("") { it.text }
        else -> item.toString()
    }
}
