package io.astrolabe.delegate

import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketClaims
import io.astrolabe.cell.PacketCost
import io.astrolabe.cell.PacketCoverage
import io.astrolabe.cell.PacketFlags
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.contract.Shape
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.fixtures.EventRecorder
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.ExecutionGeneration
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.register.Register
import io.astrolabe.verify.Verdict
import io.astrolabe.verify.VerdictOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P4.4.1: packets carry exact excerpts and versions; the Delegator enforces depth, shape, parallelism, budget, leases and generations (FX-26, FX-41 pre-condition). */
class DelegatorTest {
    private val clock = FakeClock.at("2026-09-24T10:00:00Z")
    private val stamp = CandidateId(Digest.ofUtf8("s0"))
    private val workspace = WorkspaceId("ws-main")
    private val parent = Identities(WorkId("W-1"), AttemptId("a1"), stamp, ContextId("cell-1"))
    private val ceiling = Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)
    private val excerpt = Excerpt("R1", "Round half-up in every report total.", "u1")
    private val v1 = FileVersion(Digest.ofUtf8("a v1"))
    private val v2 = FileVersion(Digest.ofUtf8("iface v1"))

    private fun packet(kind: ChildKind, budget: Long = 1_000, generation: ExecutionGeneration = ExecutionGeneration.INITIAL, writeScope: List<String> = emptyList()) = TaskPacket(
        parent, "I1", kind.role, listOf(excerpt), emptyList(), 1, stamp, workspace, listOf("receipt"), listOf("where is rounding applied?"),
        listOf("src/"), writeScope, ceiling, Tokens(budget), generation,
    )

    private fun investigation(child: ChildRun, generation: ExecutionGeneration = child.packet.executionGeneration, version: Int = child.packet.contractVersion) = InvestigationPacket(
        child.ids, "I1", version, generation, child.packet.base, mapOf("src/a.kt" to v1),
        listOf(Finding("totals round in a.kt", ClaimKind.Observed, listOf(EvidenceRef.Range("src/a.kt", "10-20", v1)))), Searched(listOf("src/"), true, 1.0), emptyList(), PacketCost(),
    )

    private fun result(child: ChildRun, generation: ExecutionGeneration = child.packet.executionGeneration) = ResultPacket(
        child.ids, "I1", "writer", 1, generation, child.packet.base, mapOf("src/a.kt" to v1, "src/api/Iface.kt" to v2), PacketStatus.Done, null, null,
        Register.empty(child.handle.child, "I1", "Fix it"), emptyList(), emptyList(), emptyList(), emptyList(), stamp, Digest.ofUtf8("env"),
        PacketCoverage(0, emptyList()), PacketFlags(emptyList(), emptyList()), PacketClaims(), null, emptyList(), emptyList(), PacketCost(),
    )

    private fun delegator(
        runner: ChildRunner, scope: CoroutineScope, shape: Shape = Shape.S2, depth: Int = 0, cancellation: Cancellation = Cancellation(),
        authority: PublicationAuthority = PublicationAuthority { null }, events: Events? = null, limits: DelegationLimits = DelegationLimits(Tokens(10_000), leaseTimeout = Duration.ofMinutes(10)),
    ) = Delegator(runner, authority, cancellation, limits, shape, scope, FixedIdGen(), clock, events, depth)

    private fun started(dispatch: Dispatch): Handle = assertIs<Dispatch.Started>(dispatch, dispatch.toString()).handle

    @Test
    fun `a task packet carries exact excerpts with authority refs and a bounded deliverable, never a transcript`() {
        val p = packet(ChildKind.Probe)
        assertEquals(PacketBase(stamp, workspace), p.base)
        assertEquals("u1", p.requirements.single().authorityRef)
        assertTrue(runCatching { Excerpt("R1", "x".repeat(Excerpt.MAX_CHARS + 1), "u1") }.isFailure, "an excerpt is bounded (D-122)")
        assertTrue(runCatching { p.copy(requirements = emptyList(), uncertainties = emptyList()) }.isFailure, "no deliverable, no packet")
        assertTrue(runCatching { p.copy(ids = parent.withContext(null)) }.isFailure, "packets are dispatched from a cell")
        assertTrue(runCatching { p.copy(reservedBudget = Tokens.ZERO) }.isFailure)
    }

    @Test
    fun `findings carry versions the probe was shown and observed claims point at evidence`() {
        val ids = parent.withContext(ContextId("child-1"))
        val base = PacketBase(stamp, workspace)
        val ok = InvestigationPacket(ids, "I1", 1, ExecutionGeneration.INITIAL, base, mapOf("src/a.kt" to v1), listOf(Finding("seen", ClaimKind.Observed, listOf(EvidenceRef.Range("src/a.kt", "1-3", v1)))), Searched(listOf("src/"), false, 0.5), listOf("tests?"), PacketCost())
        assertEquals(mapOf("src/a.kt" to v1), ok.dependencies)
        assertEquals("src/a.kt:1-3@${v1.hash8}", ok.findings.single().evidence.single().wire)
        assertTrue(runCatching { ok.copy(findings = listOf(Finding("stale", ClaimKind.Observed, listOf(EvidenceRef.Range("src/a.kt", "1-3", v2))))) }.isFailure, "a finding cites only a version the probe was shown (FX-41 pre-condition)")
        assertTrue(runCatching { ok.copy(findings = listOf(Finding("unseen", ClaimKind.Observed, listOf(EvidenceRef.Range("src/b.kt", "1-3", v1))))) }.isFailure)
        assertTrue(runCatching { Finding("guess", ClaimKind.Observed, emptyList()) }.isFailure, "an observed finding points at evidence")
        assertEquals("#7", Finding("guess", ClaimKind.Inferred, listOf(EvidenceRef.Alias("#7"))).evidence.single().wire)
    }

    @Test
    fun `FX-26 probe form - a cancelled probe still publishes observations, which arrive late with their spend counted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cancellation = Cancellation()
        Events().use { events ->
            val recorder = EventRecorder()
            events.subscribe(recorder)
            val delegator = delegator(scope = backgroundScope, cancellation = cancellation, events = events, runner = { child ->
                gate.await()
                assertTrue(child.cancellation.cancelled, "the parent's cancellation reached the child")
                ChildOutcome.Published(ChildPacket.Investigation(investigation(child)), Tokens(400))
            })
            val handle = started(delegator.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async))
            assertEquals("child-1", handle.id)
            assertEquals(Tokens(1_000), delegator.budget.heldTokens)
            assertIs<Collected.Pending>(delegator.collect(handle))
            cancellation.cancel("user stopped the campaign")
            gate.complete(Unit)
            val late = assertIs<Collected.Late>(delegator.await(handle))
            assertEquals("child cancelled: user stopped the campaign", late.reason)
            val observation = assertIs<ChildPacket.Investigation>(assertNotNull(late.observation, "observations of an already-started action are kept"))
            assertEquals("totals round in a.kt", observation.packet.findings.single().claim)
            assertEquals(Tokens(400), late.spend)
            assertEquals(Tokens(400), delegator.budget.spent, "a late result's spend is counted")
            assertEquals(Tokens.ZERO, delegator.budget.heldTokens)
            recorder.awaitCount(2)
            assertEquals(listOf("Dispatched", "Rejected"), recorder.events.map { it::class.simpleName })
            assertEquals("child cancelled: user stopped the campaign", (recorder.events[1] as AgentEvent.Delegation.Rejected).reason)
            assertIs<Dispatch.Refused>(delegator.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync)).let {
                assertEquals(DispatchLimit.Cancelled, it.limit)
            }
        }
    }

    @Test
    fun `FX-26 review form - a verdict published under a superseded generation is never accepted`() = runTest {
        var refusal: String? = null
        val delegator = delegator(scope = this, authority = { refusal }, runner = { child ->
            refusal = "lease of ws-main superseded (generation 1, held 0)"
            ChildOutcome.Published(ChildPacket.Review(Verdict("rv-1", 1, stamp, VerdictOutcome.Approve, confidence = 0.9, signedBy = child.handle.child.value)), Tokens(300))
        })
        val handle = started(delegator.dispatch(ChildKind.Review, packet(ChildKind.Review), DispatchMode.Sync))
        val late = assertIs<Collected.Late>(delegator.collect(handle))
        assertTrue(late.reason.startsWith("parent superseded: lease of ws-main superseded"), late.reason)
        assertTrue(assertIs<ChildPacket.Review>(late.observation).verdict.approved, "the observation is immutable; it just cannot accept anything")
        assertEquals(Tokens(300), delegator.budget.spent)
    }

    @Test
    fun `a writer's result under the current generation is collectable and its read versions are dependencies even outside its write scope`() = runTest {
        val delegator = delegator(scope = this, shape = Shape.S3, runner = { child -> ChildOutcome.Published(ChildPacket.Result(result(child)), Tokens(700)) })
        val handle = started(delegator.dispatch(ChildKind.Writer, packet(ChildKind.Writer, writeScope = listOf("src/a.kt")), DispatchMode.Sync))
        val collected = assertIs<Collected.Result>(delegator.collect(handle))
        assertEquals(setOf("src/a.kt", "src/api/Iface.kt"), collected.dependencies.keys, "an interface assumption outside the write scope is still a dependency")
        assertEquals(PacketBase(stamp, workspace), assertIs<ChildPacket.Result>(collected.packet).packet.base, "the result preserves the dispatch base")
        assertEquals(Tokens(700), delegator.budget.spent)
        assertEquals(Tokens(9_300), delegator.budget.available)

        val moved = delegator(scope = this, shape = Shape.S3, runner = { child -> ChildOutcome.Published(ChildPacket.Result(result(child, ExecutionGeneration.INITIAL.next())), Tokens(1)) })
        val stale = assertIs<Collected.Late>(moved.collect(started(moved.dispatch(ChildKind.Writer, packet(ChildKind.Writer), DispatchMode.Sync))))
        assertEquals("published under generation 1, dispatched under 0", stale.reason)
    }

    @Test
    fun `limits - writers depth 1 and S3 only, probes depth 2, at most 3 parallel cells, one task-tree budget`() = runTest {
        val idle: ChildRunner = ChildRunner { ChildOutcome.Published(ChildPacket.Investigation(investigation(it)), Tokens(10)) }
        assertEquals(DispatchLimit.Shape, assertIs<Dispatch.Refused>(delegator(idle, this, shape = Shape.S2).dispatch(ChildKind.Writer, packet(ChildKind.Writer), DispatchMode.Sync)).limit)
        assertEquals(DispatchLimit.Shape, assertIs<Dispatch.Refused>(delegator(idle, this, shape = Shape.S1).dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync)).limit)
        assertEquals(DispatchLimit.Depth, assertIs<Dispatch.Refused>(delegator(idle, this, shape = Shape.S3, depth = 1).dispatch(ChildKind.Writer, packet(ChildKind.Writer), DispatchMode.Sync)).limit)
        assertEquals(DispatchLimit.Depth, assertIs<Dispatch.Refused>(delegator(idle, this, depth = 1).dispatch(ChildKind.Review, packet(ChildKind.Review), DispatchMode.Sync)).limit)
        assertIs<Dispatch.Started>(delegator(idle, this, depth = 1).dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync), "a probe may run under a depth-1 cell")
        assertEquals(DispatchLimit.Depth, assertIs<Dispatch.Refused>(delegator(idle, this, depth = 2).dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync)).limit)
        assertTrue(runCatching { delegator(idle, this).dispatch(ChildKind.Writer, packet(ChildKind.Probe), DispatchMode.Sync) }.isFailure, "a packet's role matches its kind")

        val gate = CompletableDeferred<Unit>()
        val parallel = delegator(scope = backgroundScope, runner = { child ->
            gate.await()
            ChildOutcome.Published(ChildPacket.Investigation(investigation(child)), Tokens(10))
        })
        val handles = (1..3).map { started(parallel.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async)) }
        val fourth = assertIs<Dispatch.Refused>(parallel.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async))
        assertEquals(DispatchLimit.Parallel, fourth.limit)
        assertEquals(Tokens(3_000), parallel.budget.heldTokens)
        gate.complete(Unit)
        handles.forEach { assertIs<Collected.Result>(parallel.await(it)) }
        assertIs<Dispatch.Started>(parallel.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async), "a settled child frees its slot")
        yield()

        val tree = delegator(idle, this, limits = DelegationLimits(Tokens(1_500)))
        assertIs<Dispatch.Started>(tree.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync))
        val broke = assertIs<Dispatch.Refused>(tree.dispatch(ChildKind.Probe, packet(ChildKind.Probe, budget = 1_500), DispatchMode.Sync))
        assertEquals(DispatchLimit.Budget, broke.limit)
        assertEquals("the task tree has 1490 tokens left; 1500 requested", broke.reason, "the first child's hold settled to its 10 tokens of spend")
        assertIs<Dispatch.Started>(tree.dispatch(ChildKind.Probe, packet(ChildKind.Probe, budget = 1_490), DispatchMode.Sync))
    }

    @Test
    fun `a child lease has a timeout - a result after expiry is late and the child's own cancel is honoured`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delegator = delegator(scope = backgroundScope, runner = { child ->
            gate.await()
            ChildOutcome.Published(ChildPacket.Investigation(investigation(child)), Tokens(50))
        })
        val handle = started(delegator.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async))
        assertEquals(clock.instant().plus(Duration.ofMinutes(10)), handle.lease.expiry)
        assertEquals("child-1", handle.lease.holder)
        clock.advance(Duration.ofMinutes(11))
        gate.complete(Unit)
        val late = assertIs<Collected.Late>(delegator.await(handle))
        assertTrue(late.reason.startsWith("lease of child-1 expired at"), late.reason)
        assertEquals(Tokens(50), delegator.budget.spent)

        val second = CompletableDeferred<Unit>()
        val own = delegator(scope = backgroundScope, runner = { child ->
            second.await()
            ChildOutcome.Published(ChildPacket.Investigation(investigation(child)), Tokens(5))
        })
        val h = started(own.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Async))
        own.cancel(h, "parent no longer needs it")
        second.complete(Unit)
        assertEquals("child cancelled: parent no longer needs it", assertIs<Collected.Late>(own.await(h)).reason)
        assertIs<Collected.Unknown>(delegator.collect(h.copy(id = "child-9")), "handles belong to the delegator that issued them")
    }

    @Test
    fun `a failed child and a kind or version mismatch are reported, with spend counted`() = runTest {
        val failing = delegator(scope = this, runner = { throw IllegalStateException("provider down") })
        val failed = assertIs<Collected.Failed>(failing.collect(started(failing.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync))))
        assertTrue(failed.reason.contains("provider down"), failed.reason)

        val wrongKind = delegator(scope = this, runner = { child -> ChildOutcome.Published(ChildPacket.Review(Verdict("rv", 1, stamp, VerdictOutcome.Approve, confidence = 1.0, signedBy = "x")), Tokens(20)) })
        assertEquals("a probe child published a review packet", assertIs<Collected.Late>(wrongKind.collect(started(wrongKind.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync)))).reason)

        val wrongVersion = delegator(scope = this, runner = { child -> ChildOutcome.Published(ChildPacket.Investigation(investigation(child, version = 2)), Tokens(20)) })
        assertEquals("published for contract v2, dispatched under v1", assertIs<Collected.Late>(wrongVersion.collect(started(wrongVersion.dispatch(ChildKind.Probe, packet(ChildKind.Probe), DispatchMode.Sync)))).reason)
        assertEquals(Tokens(20), wrongVersion.budget.spent)
    }
}
