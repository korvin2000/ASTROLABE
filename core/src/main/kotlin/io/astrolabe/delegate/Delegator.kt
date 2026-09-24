package io.astrolabe.delegate

import io.astrolabe.budget.Reservations
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.Cancellation
import io.astrolabe.campaign.Lease
import io.astrolabe.campaign.PublicationAuthority
import io.astrolabe.cell.ResultPacket
import io.astrolabe.contract.Shape
import io.astrolabe.event.AgentEvent
import io.astrolabe.event.Events
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.verify.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.time.Clock
import java.time.Duration

/**
 * The limits of a task tree (§10.1, invariant 10): writers one level deep, probes two, at most [maxParallelCells]
 * children in flight under one parent, one [treeBudget] every child's reservation comes from, and a lease of
 * [leaseTimeout] per child after which its result is late. Reviews sit with writers at depth 1 (D-106).
 */
public data class DelegationLimits @JvmOverloads constructor(
    val treeBudget: Tokens,
    val writersDepth: Int = 1,
    val probesDepth: Int = 2,
    val maxParallelCells: Int = 3,
    val leaseTimeout: Duration = Duration.ofMinutes(30),
) {
    init {
        require(writersDepth >= 1 && probesDepth >= 1 && maxParallelCells >= 1) { "depths and parallelism are at least 1" }
        require(!leaseTimeout.isNegative && !leaseTimeout.isZero) { "a lease has a positive timeout" }
    }

    public fun depthOf(kind: ChildKind): Int = when (kind) {
        ChildKind.Writer, ChildKind.Review -> writersDepth
        ChildKind.Probe -> probesDepth
    }
}

/** A dispatched child: its id, kind and mode, the child's context id, and the lease it publishes under. */
public data class Handle(val id: String, val kind: ChildKind, val mode: DispatchMode, val child: ContextId, val lease: Lease) {
    init {
        require(id.isNotBlank()) { "a handle has an id" }
    }
}

/** What a child ends with, by kind (§3.4 output column); a kind's packet, never a conversation (§10.1). */
public sealed interface ChildPacket {
    /** Runtime-observed versions the child relied on: dependencies of the parent even outside the child's write scope (§10.1). */
    public val dependencies: Map<String, FileVersion>

    public val kind: ChildKind

    public data class Result(val packet: ResultPacket) : ChildPacket {
        override val dependencies: Map<String, FileVersion> get() = packet.readVersions
        override val kind: ChildKind get() = ChildKind.Writer
    }

    public data class Investigation(val packet: InvestigationPacket) : ChildPacket {
        override val dependencies: Map<String, FileVersion> get() = packet.dependencies
        override val kind: ChildKind get() = ChildKind.Probe
    }

    public data class Review(val verdict: Verdict) : ChildPacket {
        override val dependencies: Map<String, FileVersion> get() = emptyMap()
        override val kind: ChildKind get() = ChildKind.Review
    }
}

/** How a child run ended, as the runner reports it; [spend] is counted against the tree whatever happened. */
public sealed interface ChildOutcome {
    public val spend: Tokens

    public data class Published(val packet: ChildPacket, override val spend: Tokens) : ChildOutcome

    public data class Failed(val reason: String, override val spend: Tokens) : ChildOutcome
}

/** One child to run: its handle, packet and its own cancellation token (the parent's cancellation reaches it). */
public data class ChildRun(val handle: Handle, val packet: TaskPacket, val cancellation: Cancellation) {
    val ids: Identities get() = packet.ids.withContext(handle.child)
}

/**
 * Runs a child cell (§3.6 through the controller's cell path; the probe and review cells are P4.4.2/P4.4.3). It returns an
 * outcome even when cancelled mid-way: an already-started action's effects are published as observations (§10.1).
 */
public fun interface ChildRunner {
    public suspend fun run(child: ChildRun): ChildOutcome
}

/** Why a dispatch was refused. */
public enum class DispatchLimit { Cancelled, Depth, Shape, Parallel, Budget }

public sealed interface Dispatch {
    public data class Started(val handle: Handle) : Dispatch

    public data class Refused(val limit: DispatchLimit, val reason: String) : Dispatch
}

/** What `collect(handle)` finds. */
public sealed interface Collected {
    public val handle: Handle

    /** An async child still running. */
    public data class Pending(override val handle: Handle) : Collected

    /** The child's packet, published under a current generation and lease: the parent may integrate it. */
    public data class Result(override val handle: Handle, val packet: ChildPacket, val spend: Tokens) : Collected {
        val dependencies: Map<String, FileVersion> get() = packet.dependencies
    }

    /**
     * A superseded unit's result (§10.1, FX-26): the child was cancelled, its lease expired, or the parent's generation
     * moved on. Its [observation] stays as an immutable record but is never integrated or accepted; its spend counts.
     */
    public data class Late(override val handle: Handle, val observation: ChildPacket?, val reason: String, val spend: Tokens) : Collected

    public data class Failed(override val handle: Handle, val reason: String, val spend: Tokens) : Collected

    public data class Unknown(override val handle: Handle) : Collected
}

/**
 * The delegation dispatcher of one parent cell (§10.1). [dispatch] checks the parent's authority, the depth and
 * shape rules, the parallel limit and the tree budget, leases the child and hands it to the [runner]; the outcome is
 * checked against cancellation, the lease and the generation before it is published to [collect]. Budget is
 * reserved at dispatch and reconciled with the reported spend even when the result is late (invariant 10).
 * [depth] is the dispatching cell's own depth: the main line is 0.
 */
public class Delegator @JvmOverloads constructor(
    private val runner: ChildRunner,
    private val authority: PublicationAuthority,
    private val cancellation: Cancellation,
    public val limits: DelegationLimits,
    public val shape: Shape,
    private val scope: CoroutineScope,
    private val idGen: IdGen,
    private val clock: Clock,
    private val events: Events? = null,
    public val depth: Int = 0,
) {
    init {
        require(depth >= 0) { "depth counts from the main line at 0" }
    }

    private class Dispatched(val handle: Handle, val packet: TaskPacket, val reservation: Reservations.Reservation, val cancellation: Cancellation) {
        var running: Deferred<ChildOutcome>? = null
        var propagation: AutoCloseable? = null

        @Volatile
        var settled: Collected? = null
    }

    private val lock = Any()
    private val units = LinkedHashMap<String, Dispatched>()

    /** The task-tree budget: every child's reservation, spend and overrun. */
    public val budget: Reservations = Reservations(limits.treeBudget)

    /** Every handle dispatched so far, in order. */
    public val handles: List<Handle> get() = synchronized(lock) { units.values.map { it.handle } }

    public suspend fun dispatch(kind: ChildKind, packet: TaskPacket, mode: DispatchMode): Dispatch {
        require(packet.role == kind.role) { "a ${kind.wire} packet names role '${kind.role}', got '${packet.role}'" }
        // §10.1: cancellation is checked before a child starts.
        refusal()?.let { return Dispatch.Refused(DispatchLimit.Cancelled, "dispatch refused: $it") }
        val childDepth = depth + 1
        if (childDepth > limits.depthOf(kind)) return Dispatch.Refused(DispatchLimit.Depth, "${kind.wire} cells run at most ${limits.depthOf(kind)} deep; this would be depth $childDepth")
        if (kind == ChildKind.Writer && shape != Shape.S3) return Dispatch.Refused(DispatchLimit.Shape, "writers are dispatched in S3 only; the shape is ${shape.name}")
        if (shape == Shape.S0 || shape == Shape.S1) return Dispatch.Refused(DispatchLimit.Shape, "${kind.wire} cells need S2 or S3; the shape is ${shape.name}")
        val unit = synchronized(lock) {
            val inFlight = units.values.count { it.settled == null }
            if (inFlight >= limits.maxParallelCells) return Dispatch.Refused(DispatchLimit.Parallel, "$inFlight children in flight; at most ${limits.maxParallelCells} run in parallel")
            val reservation = budget.reserve(packet.reservedBudget, "${kind.wire} ${packet.incrementId}")
                ?: return Dispatch.Refused(DispatchLimit.Budget, "the task tree has ${budget.available.value} tokens left; ${packet.reservedBudget.value} requested")
            val id = idGen.next("child")
            val lease = Lease(packet.workspace, id, clock.instant().plus(limits.leaseTimeout), packet.executionGeneration)
            val handle = Handle(id, kind, mode, ContextId(id), lease)
            Dispatched(handle, packet, reservation, Cancellation()).also { units[id] = it }
        }
        unit.propagation = cancellation.onCancel { unit.cancellation.cancel(it) }
        events?.emit(AgentEvent.Delegation.Dispatched(packet.ids, unit.handle.id, kind.wire))
        val run = ChildRun(unit.handle, packet, unit.cancellation)
        when (mode) {
            DispatchMode.Sync -> settle(unit, runChild(run))
            DispatchMode.Async -> unit.running = scope.async { runChild(run).also { settle(unit, it) } }
        }
        return Dispatch.Started(unit.handle)
    }

    /** Cancels one child; it may still publish observations, which arrive late (§10.1). */
    public fun cancel(handle: Handle, reason: String) {
        synchronized(lock) { units[handle.id] }?.cancellation?.cancel(reason)
    }

    /** What the parent may take from [handle] now; an async child still running is [Collected.Pending]. */
    public fun collect(handle: Handle): Collected {
        val unit = synchronized(lock) { units[handle.id] } ?: return Collected.Unknown(handle)
        return unit.settled ?: Collected.Pending(handle)
    }

    /** As [collect], after waiting for an async child to end. */
    public suspend fun await(handle: Handle): Collected {
        val unit = synchronized(lock) { units[handle.id] } ?: return Collected.Unknown(handle)
        unit.running?.await()
        return unit.settled ?: Collected.Pending(handle)
    }

    /** The parent's authority to start or publish: its cancellation first, then its lease (§3.7). */
    private fun refusal(): String? = cancellation.reason?.let { "cancelled: $it" } ?: authority.refusal()

    private suspend fun runChild(run: ChildRun): ChildOutcome = try {
        runner.run(run)
    } catch (failure: RuntimeException) {
        ChildOutcome.Failed("child ${run.handle.id} threw ${failure::class.simpleName}: ${failure.message}", Tokens.ZERO)
    }

    /** Publication (§10.1): the outcome is checked against cancellation, the lease and the generation; spend counts either way. */
    private fun settle(unit: Dispatched, outcome: ChildOutcome) {
        unit.reservation.reconcile(outcome.spend)
        unit.propagation?.close()
        val handle = unit.handle
        val ids = unit.packet.ids
        val superseded = supersession(unit, outcome)
        val collected: Collected = when {
            superseded != null -> Collected.Late(handle, (outcome as? ChildOutcome.Published)?.packet, superseded, outcome.spend)
            outcome is ChildOutcome.Failed -> Collected.Failed(handle, outcome.reason, outcome.spend)
            else -> Collected.Result(handle, (outcome as ChildOutcome.Published).packet, outcome.spend)
        }
        unit.settled = collected
        when (collected) {
            is Collected.Late -> events?.emit(AgentEvent.Delegation.Rejected(ids, handle.id, collected.reason))
            is Collected.Failed -> events?.emit(AgentEvent.Delegation.Collected(ids, handle.id, "failed"))
            is Collected.Result -> events?.emit(AgentEvent.Delegation.Collected(ids, handle.id, "published"))
            else -> {}
        }
    }

    private fun supersession(unit: Dispatched, outcome: ChildOutcome): String? {
        unit.cancellation.reason?.let { return "child cancelled: $it" }
        refusal()?.let { return "parent superseded: $it" }
        if (!unit.handle.lease.validAt(clock.instant())) return "lease of ${unit.handle.id} expired at ${unit.handle.lease.expiry}"
        val packet = (outcome as? ChildOutcome.Published)?.packet ?: return null
        if (packet.kind != unit.handle.kind) return "a ${unit.handle.kind.wire} child published a ${packet.kind.wire} packet"
        val generation = when (packet) {
            is ChildPacket.Result -> packet.packet.executionGeneration
            is ChildPacket.Investigation -> packet.packet.executionGeneration
            is ChildPacket.Review -> unit.packet.executionGeneration
        }
        if (generation != unit.packet.executionGeneration) return "published under generation ${generation.value}, dispatched under ${unit.packet.executionGeneration.value}"
        val version = when (packet) {
            is ChildPacket.Result -> packet.packet.contractVersion
            is ChildPacket.Investigation -> packet.packet.contractVersion
            is ChildPacket.Review -> packet.verdict.contractRevision
        }
        if (version != unit.packet.contractVersion) return "published for contract v$version, dispatched under v${unit.packet.contractVersion}"
        return null
    }
}
