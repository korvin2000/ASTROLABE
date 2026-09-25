package io.astrolabe.delegate

import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.cell.CellCheckpoint
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.CellStatus
import io.astrolabe.cell.Change
import io.astrolabe.cell.ChangeOrigin
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.TouchKind
import io.astrolabe.contract.Shape
import io.astrolabe.evidence.Coherence
import io.astrolabe.evidence.InMemoryIntentJournal
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.BillingDimension
import io.astrolabe.register.Register
import io.astrolabe.workspace.Stamper
import io.astrolabe.workspace.TEST_ENV
import io.astrolabe.workspace.VersionRegistry
import io.astrolabe.workspace.WorkspaceFixture
import io.astrolabe.workspace.Workspaces
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P5.1.3: the integrator and merge queue (FX-26 writer form, FX-27, FX-28) over real worktrees and scripted writers. */
class IntegratorTest {

    /** A repository, its worktrees, the main registry with the integration horizon on its coherence, and a writer script. */
    private class Rig(state: Path, script: (WriterDispatch) -> Map<String, String?>, val gate: CompletableDeferred<Unit>? = null) : AutoCloseable {
        val fixture = WorkspaceFixture.create(state)
        val main = fixture.workspace
        val idGen = FixedIdGen()
        val workspaces = Workspaces(main, fixture.store.layout.candidates, TEST_ENV)
        val registry = VersionRegistry(main)
        val coherence = Coherence(registry)
        val horizon = IntegrationHorizon().also { coherence.register(it) }
        val intents = InMemoryIntentJournal()
        var authority = IntegrationAuthority(1, ExecutionGeneration.INITIAL, PublicationAuthority { null })
        val writers = Writers(workspaces, WriterCell { seat, dispatch, _, _, _ ->
            gate?.await()
            scripted(seat.context, dispatch, script(dispatch))
        })

        fun stamp() = Stamper(main, TEST_ENV).report().candidateId

        fun task(increment: String, writeScope: List<String>) = TaskPacket(
            fixture.ids.withContext(ContextId("cell-1")), increment, "writer", listOf(Excerpt("R1", "Totals are reported in cents.", "u1")), emptyList(), 1,
            stamp(), main.id, emptyList(), emptyList(), listOf("src/"), writeScope, IntegratorTestCeiling, Tokens(2_000), ExecutionGeneration.INITIAL,
        )

        fun delegator(scope: CoroutineScope, cancellation: Cancellation = Cancellation()) =
            Delegator(CellChildRunner({ _, _, _, _, _ -> null }, writers = writers), PublicationAuthority { null }, cancellation, DelegationLimits(Tokens(10_000)), Shape.S3, scope, idGen, fixture.clock)

        fun integrator(checks: IntegrationChecks = PASS, rebase: Rebase? = null) =
            Integrator(workspaces, registry, TEST_ENV, checks, PASS, { authority }, fixture.store.blobs, intents, idGen, fixture.clock, horizon, rebase)

        fun collected(delegator: Delegator, handle: Handle, collected: Collected): WriterResult {
            val dispatch = assertNotNull(writers.dispatchOf(handle))
            return assertNotNull(WriterResult.of(collected, dispatch)).also { horizon.track(it.handle, it.packet.readVersions) }
        }

        fun bytes(path: String): String = Files.readString(main.root.resolve(path))

        override fun close() = fixture.close()

        /** The writer's edits in its worktree and the Result Packet a green implementing exit gate would publish. */
        private fun scripted(context: ContextId, dispatch: WriterDispatch, edits: Map<String, String?>): CellExit {
            val tree = dispatch.worktree.workspace
            val versions = VersionRegistry(tree)
            val reads = LinkedHashMap<String, FileVersion>()
            val changes = edits.map { (path, text) ->
                val before = versions.version(path)?.also { reads[path] = it }
                val file = tree.root.resolve(path)
                if (text == null) Files.delete(file) else Files.createDirectories(file.parent).let { Files.writeString(file, text) }
                Change(path, if (before == null) TouchKind.Added else if (text == null) TouchKind.Deleted else TouchKind.Modified, before, versions.version(path), ChangeOrigin.Edit)
            }
            versions.version("README.md")?.let { reads["README.md"] = it }
            val task = dispatch.task
            val ids = task.ids.withContext(context)
            val packet = ResultPacket(
                ids, task.incrementId, "writer", task.contractVersion, task.executionGeneration, dispatch.base, reads, PacketStatus.Done, null, null,
                Register.empty(context, task.incrementId, "writer"), emptyList(), changes, emptyList(), emptyList(), Stamper(tree, TEST_ENV).report().candidateId, TEST_ENV.envId,
                PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), null, emptyList(), emptyList(), PacketCost(mapOf(BillingDimension("input_tokens") to 300L)),
            )
            return CellExit.Completed(1, packet.register, CellCheckpoint(context, task.incrementId, 1, CellStatus.Completed, 1, packet.stamp, 0, 0, emptyList(), emptyList(), emptyList(), 0), packet, "done", emptyList())
        }
    }

    private fun started(dispatch: Dispatch): Handle = assertIs<Dispatch.Started>(dispatch, dispatch.toString()).handle

    @Test
    fun `FX-26 writer form - a cancelled writer's late patch is archived and its publication rejected`(@TempDir state: Path) = runTest {
        val gate = CompletableDeferred<Unit>()
        Rig(state, { mapOf("src/a.py" to "def a():\n    return 100\n") }, gate).use { rig ->
            val cancellation = Cancellation()
            val delegator = rig.delegator(backgroundScope, cancellation)
            val handle = started(delegator.dispatch(ChildKind.Writer, rig.task("I1", listOf("src/a.py")), DispatchMode.Async))
            cancellation.cancel("user stopped the campaign")
            gate.complete(Unit)
            val late = assertIs<Collected.Late>(delegator.await(handle))
            assertEquals(Tokens(300), late.spend)
            assertEquals(Tokens(300), delegator.budget.spent, "a late writer's spend is counted against the task tree")
            val before = rig.bytes("src/a.py")
            val rejected = assertIs<Integration.Rejected>(rig.integrator().integrate(listOf(rig.collected(delegator, handle, late))).single())
            assertEquals(IntegrationStep.Validate, rejected.step)
            assertEquals("publication authority superseded: child cancelled: user stopped the campaign", rejected.reason)
            val manifest = String(rig.fixture.store.blobs.get(assertNotNull(rejected.archived, "the late effect is archived for reconciliation")), Charsets.UTF_8)
            assertTrue(manifest.startsWith("${handle.id} src/a.py "), manifest)
            val postimage = manifest.trim().substringAfterLast(' ')
            assertEquals("def a():\n    return 100\n", String(rig.fixture.store.blobs.get(Digest(postimage)), Charsets.UTF_8))
            assertEquals(before, rig.bytes("src/a.py"), "nothing reached the main line")
            assertTrue(rig.intents.open().isEmpty())
        }
    }

    @Test
    fun `FX-27 two cleanly merging patches that disagree on semantics are rejected and the decision returns to the main line`(@TempDir state: Path) = runTest {
        val edits = mapOf(
            "I1" to mapOf("src/a.py" to "UNIT = 'cents'\ndef a():\n    return 100\n"),
            "I2" to mapOf("src/b.py" to "UNIT = 'dollars'\ndef b(total):\n    return total\n"),
        )
        Rig(state, { edits.getValue(it.task.incrementId) }).use { rig ->
            val delegator = rig.delegator(backgroundScope)
            val results = listOf("I1" to "src/a.py", "I2" to "src/b.py").map { (increment, path) ->
                val handle = started(delegator.dispatch(ChildKind.Writer, rig.task(increment, listOf(path)), DispatchMode.Sync))
                rig.collected(delegator, handle, assertIs<Collected.Result>(delegator.collect(handle), "each writer passed its own acceptance"))
            }
            // The combined-tree acceptance reads both sides of the protocol together.
            val combined = IntegrationChecks { candidate, union, _ ->
                val unit = { path: String -> Files.readString(candidate.root.resolve(path)).lineSequence().first() }
                assertEquals(sortedSetOf("src/a.py", "src/b.py"), union, "blast radius over the union of merged edit sets")
                IntegrationCheck(if (unit("src/a.py") == unit("src/b.py")) emptyList() else listOf("CHK-report-total: a.py sends ${unit("src/a.py")}, b.py reads ${unit("src/b.py")}"))
            }
            val before = rig.stamp()
            val rejected = assertIs<Integration.Rejected>(rig.integrator(combined).integrate(results).single())
            assertEquals(IntegrationStep.CombinedCheck, rejected.step)
            assertEquals(results.map { it.handle }, rejected.handles)
            assertTrue(rejected.returnsToMainLine, "the shared decision returns to the main line; no vote over worker confidence")
            assertTrue(rejected.evidence.single().startsWith("CHK-report-total"))
            assertEquals(before, rig.stamp(), "the main line is untouched")
            assertEquals(2, rig.workspaces.worktrees.size, "the integration candidate is removed; the writers' worktrees stay for the main line's decision")
            assertTrue(rig.intents.open().isEmpty())
        }
    }

    @Test
    fun `FX-28 a child result whose base moved is stale-for-integration and is rebased and re-verified or rejected`(@TempDir state: Path) = runTest {
        Rig(state, { mapOf("src/a.py" to "def a():\n    return 7\n") }).use { rig ->
            val delegator = rig.delegator(backgroundScope)
            val handle = started(delegator.dispatch(ChildKind.Writer, rig.task("I1", listOf("src/a.py")), DispatchMode.Sync))
            val result = rig.collected(delegator, handle, delegator.collect(handle))
            // The main line moves a file the writer read, after dispatch.
            val readme = rig.registry.version("README.md")
            Files.writeString(rig.main.root.resolve("README.md"), "# moved\n")
            rig.registry.change("README.md", readme, rig.registry.version("README.md"), "edited by the main line")
            assertEquals(setOf("README.md"), rig.horizon.stale(handle.id).keys, "the integration horizon marked the result")

            val rejected = assertIs<Integration.Rejected>(rig.integrator().integrate(listOf(result)).single())
            assertEquals(IntegrationStep.Freshness, rejected.step)
            assertEquals("stale-for-integration", rejected.reason)
            assertTrue(rejected.evidence.any { it.startsWith("main @") } && rejected.evidence.contains("read dependency README.md moved"), rejected.evidence.toString())
            assertEquals("def a():\n    return 1\n", rig.bytes("src/a.py"))

            // Rebase in the child's worktree onto the current main candidate and re-run its acceptance there.
            var rebased: WriterResult? = null
            val rebase = Rebase { stale, onto, moved ->
                assertEquals(listOf("README.md"), moved)
                rig.writers.release(stale.dispatch)
                val child = ChildRun(stale.dispatch.handle, stale.dispatch.task.copy(dispatchCandidate = onto), Cancellation())
                val outcome = assertIs<ChildOutcome.Published>(rig.writers.run(child))
                WriterResult(assertNotNull(rig.writers.dispatchOf(stale.dispatch.handle)), (outcome.packet as ChildPacket.Result).packet, outcome.spend).also { rebased = it }
            }
            val base = rig.stamp()
            val published = assertIs<Integration.Published>(rig.integrator(rebase = rebase).integrate(listOf(result)).single())
            val receipt = published.receipt
            assertEquals(base, receipt.integrationBase)
            assertEquals(base, assertNotNull(rebased).packet.base!!.stamp, "re-verified on the current main candidate")
            assertEquals(listOf("src/a.py"), receipt.paths)
            assertEquals(Integrator.patchHash(rebased!!.packet.changes), receipt.patchHash)
            assertEquals(rig.stamp(), receipt.resultingStamp)
            assertEquals(TEST_ENV.envId, receipt.envId)
            assertEquals(listOf("CHK-tests-blast", "CHK-tests-blast"), receipt.receipts)
            assertEquals("def a():\n    return 7\n", rig.bytes("src/a.py"), "published into the main line")
            assertTrue(rig.intents.open().isEmpty(), "the publication intent committed")
            assertTrue(rig.horizon.stale(handle.id).isEmpty(), "a published result leaves the horizon")
        }
    }
}

private val IntegratorTestCeiling = Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)

private val PASS = IntegrationChecks { _, _, _ -> IntegrationCheck(emptyList(), listOf("CHK-tests-blast")) }
