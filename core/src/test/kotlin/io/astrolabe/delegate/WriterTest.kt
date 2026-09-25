package io.astrolabe.delegate

import io.astrolabe.Config
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Capability
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.RoleTexts
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.contract.Shape
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.kb.EmptyKb
import io.astrolabe.kb.KbWriter
import io.astrolabe.kb.Queue
import io.astrolabe.provider.ToolCall as ProviderCall
import io.astrolabe.store.Store
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.kb.KbTool
import io.astrolabe.tool.task.TaskTool
import io.astrolabe.workset.Workset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P5.1.2: the writer role is the implementer mask minus delegation and minus CON/ADR writes (mask test). */
class WriterTest {
    private val clock = FakeClock.at("2026-09-25T10:00:00Z")
    private val ids = Identities(WorkId("W-1"), AttemptId("a1"), context = ContextId("writer-1"))
    private val turn get() = TurnContext(1, Workset().snapshot(), Reservations(Tokens(1_000)))

    private fun call(tool: String, json: String) = (ToolCalls.parse(listOf(ProviderCall("c1", tool, json))) as ParsedCalls.Valid).calls.single()

    private fun status(o: ToolOutcome) = o.header!!.runtime.status

    @Test
    fun `a writer cannot emit task delegate or propose CON or ADR notes`(@TempDir state: Path) = runTest {
        val wide = Ceiling(CapabilitySet("wide", Capability.entries.toSet()), Stage.LocalCommit, ExecutionMode.TrustedLocal)
        val mask = Roles.writer.effectiveOps(Shape.S3, wide)
        assertFalse(mask.allows("task.delegate") || mask.allows("task.collect") || mask.allows("task.propose"), "a leaf even in S3")
        assertTrue(mask.allows("edit.anchored") && mask.allows("run.run") && mask.allows("task.ask") && mask.allows("kb.propose"), "otherwise the implementer mask")
        assertTrue(Roles.implementing.effectiveOps(Shape.S3, wide).allows("task.delegate"))

        val idGen = FixedIdGen()
        val delegator = Delegator({ error("never dispatched") }, { null }, Cancellation(), DelegationLimits(Tokens(10_000)), Shape.S3, backgroundScope, idGen, clock)
        val packets = TaskPackets(WorkspaceId("ws-main"), wide, ExecutionGeneration.INITIAL)
        val task = TaskTool(AutonomousAuthority(), Contracts(InMemoryContractRepository(), idGen, clock), null, HeuristicEstimator(), idGen, ids, clock, null, mask, null, delegator, packets)
        val delegate = task.execute(call("task", """{"op":"delegate","kind":"writer","packet":{"increment":"I1","requirements":["R1"],"writeScope":["src/a.kt"],"budgetTokens":800}}"""), turn)
        assertEquals("masked", status(delegate), delegate.body)
        assertTrue(delegator.handles.isEmpty())

        val repo = TempRepo.create()
        try {
            repo.write("README.md", "# fixture\n")
            repo.commit("initial")
            Store.open(state, repo.git, clock).use { store ->
                val kb = KbTool(EmptyKb, HeuristicEstimator(), idGen, mask, queue = Queue(store, KbWriter(store, HeuristicEstimator(), clock), idGen, clock), ids = ids, deniedKinds = Roles.writer.deniedNoteKinds)
                for (kind in listOf("CON", "ADR")) {
                    val out = kb.execute(call("kb", """{"op":"propose","note":{"kind":"$kind","summary":"refunds are idempotent","body":"by key","scope":"payments"}}"""), turn)
                    assertEquals("refused", status(out), out.body)
                    assertTrue(out.body.contains("never proposes $kind notes; ask the parent with task.ask"), out.body)
                }
                val lesson = kb.execute(call("kb", """{"op":"propose","note":{"kind":"LES","summary":"run the slow suite last","body":"it takes minutes","scope":"payments"}}"""), turn)
                assertEquals("queued", status(lesson), lesson.body)
            }
        } finally {
            repo.close()
        }

        val regranted = Config(roles = mapOf("writer" to Roles.writer.copy(deniedNoteKinds = emptySet())))
        assertTrue(regranted.violations().any { it.field == "roles.writer" && it.message.contains("re-grants note proposals [ADR, CON]") }, regranted.violations().toString())
        assertEquals(setOf("CON", "ADR"), RoleTexts.worded(Roles.writer, Roles.writer.copy(deniedNoteKinds = emptySet())).deniedNoteKinds, "an override rewords, never re-grants")
    }
}
