package io.astrolabe.delegate

import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.CellBudget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Reserves
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.cell.CellFixture
import io.astrolabe.cell.CellFixture.Companion.read
import io.astrolabe.cell.CellFixture.Companion.say
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Shape
import io.astrolabe.event.AutonomousAuthority
import io.astrolabe.fixtures.Scripted
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkspaceId
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.ParsedCalls
import io.astrolabe.tool.ToolCalls
import io.astrolabe.tool.ToolOps
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.tool.TurnContext
import io.astrolabe.tool.task.TaskTool
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.astrolabe.provider.ToolCall as ProviderCall

/** P4.4.2: the probe cell ends with a validated Investigation packet; the parent sees a bounded summary of pointers (FX-41). */
class ProbeTest {
    @TempDir
    lateinit var stateRoot: Path

    private val estimator = HeuristicEstimator()

    private fun call(family: String, json: String) = (ToolCalls.parse(listOf(ProviderCall("c9", family, json))) as ParsedCalls.Valid).calls.single()

    @Test
    fun `the probe packet binds every cited range to a version it was shown and says what it could not`() {
        val v1 = FileVersion(Digest.ofUtf8("a v1"))
        val shown = mapOf("src/a.py" to v1)
        val ok = assertIs<ProbeOutput.Parsed>(
            Probe.parse(
                """here: {"findings":[{"claim":"a returns 1","kind":"observed","evidence":["src/a.py:1-2@${v1.hash8}","#3"]},{"claim":"b looks unused","kind":"inferred","evidence":[]}],"searched":{"scopes":["src/"],"complete":false,"indexCoverage":0.5},"unresolved":["who calls b?"]}""",
                shown,
            ),
        )
        assertEquals(listOf(EvidenceRef.Range("src/a.py", "1-2", v1), EvidenceRef.Alias("#3")), ok.findings[0].evidence)
        assertEquals(Searched(listOf("src/"), false, 0.5), ok.searched)
        val gaps = assertIs<ProbeOutput.Gaps>(
            Probe.parse("""{"findings":[{"claim":"b is 2","kind":"observed","evidence":["src/b.py:2"]},{"claim":"a is 1","kind":"observed","evidence":["src/a.py:1@deadbeef"]},{"claim":"guess","kind":"observed","evidence":[]}],"searched":{"scopes":["src/"],"complete":true}}""", shown),
        ).gaps
        assertTrue(gaps.any { "src/b.py was never shown to you" in it }, gaps.toString())
        assertTrue(gaps.any { "src/a.py@deadbeef is not the version you were shown" in it }, gaps.toString())
        assertTrue(gaps.any { "an observed claim points at evidence" in it }, gaps.toString())
        assertIs<ProbeOutput.Gaps>(Probe.parse("all done", shown))

        val many = InvestigationPacket(
            io.astrolabe.id.Identities(io.astrolabe.id.WorkId("W-1"), io.astrolabe.id.AttemptId("a1"), context = ContextId("child-1")), "I1", 1, ExecutionGeneration.INITIAL,
            PacketBase(io.astrolabe.id.CandidateId(Digest.ofUtf8("s0")), WorkspaceId("main")), shown,
            (1..60).map { Finding("finding number $it about the rounding of report totals in module a", ClaimKind.Observed, listOf(EvidenceRef.Range("src/a.py", "$it", v1))) },
            Searched(listOf("src/"), true, 1.0), emptyList(), PacketCost(),
        )
        val summary = Probe.summary("child-1", many, { v1 }, estimator)
        assertTrue(estimator.estimate(summary).upperBoundTokens <= Probe.MAX_SUMMARY_TOKENS, summary)
        assertTrue(summary.endsWith("pointers only: look a range to make it KNOWN before relying on it"), summary)
    }

    @Test
    fun `FX-41 - findings for ranges that changed since are marked stale and the parent re-looks`() = runTest {
        CellFixture(stateRoot).use { f ->
            val shownVersion = f.version("src/a.py")
            val model = ScriptedModel.of(
                Scripted.Reply(listOf(say("reading a"), read("c1", "src/a.py"))),
                Scripted.Reply(listOf(say("""{"findings":[{"claim":"a() returns 1","kind":"observed","evidence":["src/a.py:1-2"]},{"claim":"b is unrelated","kind":"inferred","evidence":[]}],"searched":{"scopes":["src/"],"complete":true,"indexCoverage":1.0},"unresolved":["no test covers b"]}"""))),
            )
            val briefs = ArrayList<String>()
            val cell = ChildCell { _, role, completion, budget, brief ->
                briefs += brief
                assertEquals(Roles.probe, role)
                f.cell(completion = completion).run(f.context(model, role = role), f.increment, CellBudget.of(budget.tokens, budget.turns, Reserves()))
            }
            val delegator = Delegator(CellChildRunner(cell), { null }, Cancellation(), DelegationLimits(Tokens(200_000)), Shape.S2, this, f.idGen, f.clock)
            val packets = TaskPackets(WorkspaceId("ws-1"), Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal), ExecutionGeneration.INITIAL)
            val parent = TaskTool(AutonomousAuthority(), f.contracts, f.journal, estimator, f.idGen, f.ids.withCandidate(f.stamper.report().candidateId), f.clock, null, ToolMask(ToolOps.all), null, delegator, packets, f.registry::version)
            suspend fun task(json: String): ToolOutcome = parent.execute(call("task", json), TurnContext(2, f.workset.snapshot(), Reservations(Tokens(10_000))))

            val out = task("""{"op":"delegate","kind":"probe","mode":"sync","packet":{"increment":"inc-1","requirements":["R1"],"uncertainties":["what does a return?"],"readScope":["src/"],"budgetTokens":40000}}""")
            assertEquals("collected", out.header!!.runtime.status, out.body)
            val brief = briefs.single()
            assertTrue(brief.contains("Question: what does a return?") && brief.contains("R1 [") && brief.contains("write scope: none (read-only)"), brief)
            assertTrue(out.body.contains("2 findings (0 stale)"), out.body)
            assertTrue(out.body.contains("1. [observed] a() returns 1 — src/a.py:1-2@${shownVersion.hash8}"), out.body)
            assertTrue(out.body.contains("unresolved: no test covers b"), out.body)
            val handle = delegator.handles.single()
            val investigation = assertIs<ChildPacket.Investigation>(assertIs<Collected.Result>(delegator.collect(handle)).packet).packet
            assertEquals(mapOf("src/a.py" to shownVersion), investigation.dependencies)

            // The range changes after the probe saw it: the pointer is stale and the parent is told to look again.
            f.repo.write("src/a.py", CellFixture.A_PY.replace("return 1", "return 10"))
            val again = task("""{"op":"collect","handle":"${handle.id}"}""")
            assertTrue(again.body.contains("2 findings (1 stale)"), again.body)
            assertTrue(again.body.contains("src/a.py:1-2@${shownVersion.hash8} STALE — src/a.py changed since; look it again"), again.body)
            assertEquals(listOf("a() returns 1"), Probe.stale(investigation, f.registry::version).map { it.claim })

            val relook = f.look.execute(call("look", """{"what":"read","target":"src/a.py"}"""), TurnContext(3, f.workset.snapshot(), Reservations(Tokens(10_000))))
            assertTrue(relook.body.contains("return 10"), relook.body)
            assertNotEquals(shownVersion, f.version("src/a.py"), "the parent's look is at the current version, not the probe's pointer")
        }
    }
}
