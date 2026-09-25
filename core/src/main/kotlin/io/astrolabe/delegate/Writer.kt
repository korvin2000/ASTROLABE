package io.astrolabe.delegate

import io.astrolabe.budget.Tokens
import io.astrolabe.cell.CellExit
import io.astrolabe.cell.PacketBase
import io.astrolabe.cell.PacketStatus
import io.astrolabe.cell.ResultPacket
import io.astrolabe.cell.Role
import io.astrolabe.cell.Roles
import io.astrolabe.route.RoutingFunction
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.Workspaces
import io.astrolabe.workspace.Worktree

/** The turns a writer cell runs on (D-181); its tokens are the packet's reservation, charged to the parent's task tree. */
public data class WriterBudget @JvmOverloads constructor(val turns: Int = 40) {
    init {
        require(turns >= 1) { "a writer runs at least one turn" }
    }

    public companion object {
        @JvmField
        public val DEFAULT: WriterBudget = WriterBudget()
    }
}

/**
 * The recorded dispatch of one writer (§10.4): its handle, the task packet (the child contract slice: exact excerpts,
 * write scope, contract version, generation) and the worktree it runs in. [base] — the main candidate the worktree
 * reproduces, in the writer's own workspace — is what the writer's Result Packet must preserve.
 */
public data class WriterDispatch(val handle: Handle, val task: TaskPacket, val worktree: Worktree) {
    init {
        require(handle.kind == ChildKind.Writer) { "a writer dispatch holds a writer handle" }
    }

    val base: PacketBase get() = PacketBase(task.dispatchCandidate, worktree.id)
}

/**
 * Runs one writer cell in its worktree (§10.4): the runtime kernel with [role] (the writer mask), the implementing
 * exit gate as its completion, its own shadow ref and the rendered child slice as its only view of the parent.
 * `null` when it was cancelled before it produced an exit.
 */
public fun interface WriterCell {
    public suspend fun run(seat: ChildSeat, dispatch: WriterDispatch, role: Role, budget: ChildBudget, brief: String): CellExit?
}

/**
 * The writer side of delegation (§10.4, P5.1.2): each writer gets its own worktree reproducing the dispatch candidate,
 * runs on [cell], and publishes its Result Packet only when it passes [validate] against its slice. Dispatches are
 * recorded so the integrator can check a result against its recorded base; [release] removes a worktree once its
 * result was integrated or rejected.
 */
public class Writers @JvmOverloads constructor(
    private val workspaces: Workspaces,
    private val cell: WriterCell,
    private val budget: WriterBudget = WriterBudget.DEFAULT,
) {
    private val dispatches = LinkedHashMap<String, WriterDispatch>()

    /** The recorded dispatch of [handle], or `null` for a handle this instance never ran. */
    public fun dispatchOf(handle: Handle): WriterDispatch? = synchronized(dispatches) { dispatches[handle.id] }

    public suspend fun run(child: ChildRun): ChildOutcome {
        val task = child.packet
        val worktree = workspaces.createWorktree(task.ids.work, task.ids.attempt, "${task.incrementId}-${child.handle.id}")
        if (worktree.base != task.dispatchCandidate) {
            workspaces.remove(worktree)
            return ChildOutcome.Failed("writer ${child.handle.id}: the main line is @${worktree.base.hash8}, dispatched @${task.dispatchCandidate.hash8}; re-dispatch from the current candidate", Tokens.ZERO)
        }
        val dispatch = WriterDispatch(child.handle, task, worktree)
        synchronized(dispatches) { dispatches[child.handle.id] = dispatch }
        val seat = ChildSeat(child.handle.child, child.cancellation, RoutingFunction.Implementing)
        val exit = cell.run(seat, dispatch, Roles.writer, ChildBudget(budget.turns, task.reservedBudget), ChildBrief.render(task, OUTPUT))
            ?: return ChildOutcome.Failed("writer ${child.handle.id} was cancelled before it ended", Tokens.ZERO)
        val spend = CellChildRunner.spendOf(exit.packet.cost)
        val gaps = validate(dispatch, exit.packet)
        return if (exit is CellExit.Completed && gaps.isEmpty()) ChildOutcome.Published(ChildPacket.Result(exit.packet), spend)
        else ChildOutcome.Failed("writer ${child.handle.id} ended ${exit.packet.status.wire}: ${(listOfNotNull(exit.packet.reason) + gaps).joinToString("; ").ifEmpty { "no result" }}", spend)
    }

    /** Removes [dispatch]'s worktree; its shadow ref and the archived evidence stay. */
    public fun release(dispatch: WriterDispatch) {
        synchronized(dispatches) { dispatches.remove(dispatch.handle.id) }
        workspaces.remove(dispatch.worktree)
    }

    public companion object {
        /** What the writer's brief asks it to end with. */
        public const val OUTPUT: String = "the implementing exit gate over this slice: every acceptance item green, STATE done, changes inside the write scope; " +
            "interfaces and contract questions are the parent's (task.ask), never decided here"

        /**
         * The writer's packet validator (§10.4): the implementing exit gate's result held against the child slice — the
         * recorded base, the increment, the contract version and generation it was dispatched under, `done` on a stamped
         * candidate, and every change inside the slice's write scope. Empty ⇔ the packet may be published.
         */
        @JvmStatic
        public fun validate(dispatch: WriterDispatch, packet: ResultPacket): List<String> = buildList {
            val task = dispatch.task
            if (packet.role != Roles.writer.name) add("a writer packet names role '${packet.role}'")
            if (packet.increment != task.incrementId) add("packet for ${packet.increment}, dispatched for ${task.incrementId}")
            if (packet.base != dispatch.base) add("base ${packet.base?.let { "@${it.stamp.hash8} in ${it.workspace.value}" }} is not the recorded dispatch base @${dispatch.base.stamp.hash8} in ${dispatch.base.workspace.value}")
            if (packet.contractVersion != task.contractVersion) add("contract v${packet.contractVersion}, dispatched under v${task.contractVersion}")
            if (packet.executionGeneration != task.executionGeneration) add("generation ${packet.executionGeneration.value}, dispatched under ${task.executionGeneration.value}")
            if (packet.status != PacketStatus.Done) add("status ${packet.status.wire}, not done")
            packet.changes.map { it.path }.filter { path -> task.writeScope.none { PathPattern.matches(it, path) } }
                .takeIf { it.isNotEmpty() }?.let { add("changes outside the write scope: ${it.sorted()}") }
        }
    }
}
