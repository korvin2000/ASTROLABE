package io.astrolabe.tool

import io.astrolabe.budget.Tokens
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workset.Workset
import io.astrolabe.workspace.LineRange
import io.astrolabe.workspace.Ranges
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P1.6.2 dispatcher: dispatch-time coverage (FX-50), conditions, edit-batch gating, one shared read budget, one checkpoint, dispositions for every id. */
class DispatcherTest {
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"))
    private val v1 = FileVersion(Digest.ofUtf8("v1"))
    private val workset = Workset()
    private val checkpoints = ArrayList<Int>()

    private fun calls(vararg raw: Pair<String, String>): List<ToolCall> =
        (ToolCalls.parse(raw.mapIndexed { i, (family, json) -> ProviderCall("c${i + 1}", family, json) }) as ParsedCalls.Valid).calls

    private fun look(budget: Int = 500) = "look" to """{"what":"read","target":"src/a.py:1-10","budget":$budget}"""
    private fun edit() = "edit" to """{"ops":[{"path":"src/a.py","expect":"c02e","hunks":[{"anchor":"x","new":"y"}]}],"why":"w"}"""
    private fun run(argv: String, condition: String? = null) = "run" to """{"argv":[$argv]${condition?.let { ""","if":"$it"""" } ?: ""}}"""
    private fun state() = "state" to """{"op":"patch","patch":[{"next":"x"}]}"""

    /** Fakes: a look registers the read at v1; an edit applies only when the dispatch-time snapshot covers the hunk. */
    private val executors: Map<ToolFamily, ToolExecutor> = mapOf(
        ToolFamily.Look to ToolExecutor { call, _ ->
            workset.register(Entry("src/a.py", Ranges.single(1, 10), v1, EntrySource.Look, turn = 1, resultId = "#${call.opId}", tokens = 120))
            ToolOutcome("result #${call.opId} look", tokens = 120, resultAlias = "#${call.opId}")
        },
        ToolFamily.Edit to ToolExecutor { call, context ->
            val covered = context.coverage.covers("src/a.py", v1, LineRange(1, 5))
            ToolOutcome(if (covered) "result #${call.opId} edit ok" else "result #${call.opId} edit rejected: region not displayed at dispatch time", applied = covered)
        },
        ToolFamily.Run to ToolExecutor { call, _ ->
            val argv = (call.args as Args.Run).args.argv.orEmpty()
            ToolOutcome("result #${call.opId} run", green = "pass" in argv)
        },
        ToolFamily.State to ToolExecutor { call, _ -> ToolOutcome("result #${call.opId} state") },
    )

    private fun dispatcher(events: Events? = null, maxParallelReads: Int = 4) =
        Dispatcher(executors, workset, ids, events, checkpoint = { checkpoints += it }, maxParallelReads = maxParallelReads)

    @Test
    fun `a read in the same batch never authorizes an edit and a failed batch blocks the runs (FX-50)`() = runTest {
        val turn1 = dispatcher().dispatch(1, calls(look(), edit(), run("\"pytest\",\"pass\"", "applied(op:2)"), state()), Tokens(5_000))
        assertEquals(listOf(1, 2, 3, 4), turn1.dispositions.map { it.opId })
        assertIs<Disposition.Executed>(turn1.of(1))
        val edit = assertIs<Disposition.Executed>(turn1.of(2))
        assertFalse(edit.outcome.applied)
        assertTrue(edit.outcome.text.contains("not displayed at dispatch time"))
        val run = assertIs<Disposition.NotExecuted>(turn1.of(3))
        assertTrue(run.reason.contains("did not apply fully"), run.reason)
        assertIs<Disposition.Executed>(turn1.of(4), "metadata writes still run")
        assertTrue(turn1.mutating)
        assertFalse(turn1.editsApplied)
        assertEquals(listOf(1), checkpoints, "one checkpoint for the mutating turn")

        val turn2 = dispatcher().dispatch(2, calls(edit(), run("\"pytest\",\"pass\"", "applied(op:1)")), Tokens(5_000))
        assertTrue(assertIs<Disposition.Executed>(turn2.of(1)).outcome.applied, "the earlier turn's read is coverage now")
        assertTrue(assertIs<Disposition.Executed>(turn2.of(2)).outcome.green)
        assertTrue(turn2.editsApplied)
        assertEquals(listOf(1, 2), checkpoints)
    }

    @Test
    fun `conditions gate runs and a read-only turn takes no checkpoint`() = runTest {
        val result = dispatcher().dispatch(3, calls(run("\"pytest\",\"fail\""), run("\"pytest\",\"pass\"", "green(op:1)"), run("\"pytest\",\"pass\""), state()), Tokens(1_000))
        assertFalse(assertIs<Disposition.Executed>(result.of(1)).outcome.green)
        assertEquals("condition green(op:1) not met: op 1 is not green", assertIs<Disposition.NotExecuted>(result.of(2)).reason)
        assertTrue(assertIs<Disposition.Executed>(result.of(3)).outcome.green)
        assertFalse(result.mutating)
        assertTrue(result.editsApplied, "no batch counts as applied")
        assertTrue(checkpoints.isEmpty())
    }

    @Test
    fun `parallel reads draw on one budget admitted in emitted order and reconciled to actual use`() = runTest {
        val result = dispatcher(maxParallelReads = 2).dispatch(4, calls(look(600), look(600), look(600)), Tokens(1_500))
        assertIs<Disposition.Executed>(result.of(1))
        assertIs<Disposition.Executed>(result.of(2))
        val third = assertIs<Disposition.NotExecuted>(result.of(3))
        assertTrue(third.reason.startsWith("read budget exhausted: 600 tokens requested, 300 available"), third.reason)
        assertEquals(2, result.executed.size)
    }

    @Test
    fun `a rejected turn, a missing executor and a throwing executor each leave an explicit disposition`() = runTest {
        val rejected = dispatcher().dispatch(5, calls(edit(), run("\"pytest\"", "green(op:1)")), Tokens(1_000))
        assertTrue(rejected.rejected!!.startsWith("op 2: condition green(op:1) must name a run or verify op"), rejected.rejected)
        assertTrue(rejected.dispositions.all { it is Disposition.NotExecuted && it.reason.startsWith("turn rejected before effects") })
        assertTrue(checkpoints.isEmpty(), "a rejected turn has no effects")

        val missing = dispatcher().dispatch(6, calls("kb" to """{"op":"search","query":"ctx","why":"w"}"""), Tokens(2_000))
        assertEquals("no executor registered for kb", assertIs<Disposition.NotExecuted>(missing.of(1)).reason)

        val throwing = Dispatcher(executors + (ToolFamily.Edit to ToolExecutor { _, _ -> error("disk gone") }), workset, ids)
        val failed = throwing.dispatch(7, calls(edit(), run("\"pytest\",\"pass\"")), Tokens(1_000))
        assertEquals("IllegalStateException: disk gone", assertIs<Disposition.Failed>(failed.of(1)).error)
        assertIs<Disposition.NotExecuted>(failed.of(2), "unknown edit effects never let a run proceed")
        assertFalse(failed.editsApplied)
    }

    @Test
    fun `the host sees every call and result in execution order`() = runTest {
        Events().use { events ->
            val recorder = EventRecorder()
            events.subscribe(recorder)
            dispatcher(events).dispatch(8, calls(state(), run("\"pytest\",\"pass\""), look()), Tokens(2_000))
            recorder.awaitCount(6)
            val seen = recorder.events.map {
                when (it) {
                    is AgentEvent.Cell.ToolCalled -> "called ${it.opId} ${it.family}.${it.op} ${it.phase}"
                    is AgentEvent.Cell.ToolResulted -> "resulted ${it.opId} ${it.resultAlias} ${it.header}"
                    else -> it.toString()
                }
            }
            assertEquals(
                listOf(
                    "called 3 look.read Locate", "resulted 3 #3 result #3 look",
                    "called 2 run.run Verify", "resulted 2 null result #2 run",
                    "called 1 state.patch Understand", "resulted 1 null result #1 state",
                ),
                seen,
            )
        }
    }
}
