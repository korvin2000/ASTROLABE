package io.astrolabe.tool

import io.astrolabe.register.Condition
import io.astrolabe.register.ConditionKind

/** The four execution phases of a turn (§5.4), executed in this order regardless of emission order. */
public enum class TurnPhase { Read, Edit, Execute, Metadata }

/**
 * A turn's calls partitioned by **effect** (§5.4 turn semantics): `look` and `kb` reads run first and in
 * parallel, then one `edit` batch (or one transform alone), then `run`/`verify`, then the metadata writes —
 * `state`, `task` and `kb.propose` are proposals recorded after execution, not reads. Op ids are the emitted
 * order and survive the partition; inside a phase the emitted order is kept.
 */
public sealed interface Partition {
    public data class Ordered(
        val reads: List<ToolCall>,
        val edits: List<ToolCall>,
        val executes: List<ToolCall>,
        val metadata: List<ToolCall>,
    ) : Partition {
        /** True when the turn mutates the workspace: one shadow-ref checkpoint precedes the batch (§5.4). */
        val mutating: Boolean get() = edits.isNotEmpty()

        /** Every call in execution order. */
        val order: List<ToolCall> get() = reads + edits + executes + metadata

        public fun phaseOf(opId: Int): TurnPhase? = when {
            reads.any { it.opId == opId } -> TurnPhase.Read
            edits.any { it.opId == opId } -> TurnPhase.Edit
            executes.any { it.opId == opId } -> TurnPhase.Execute
            metadata.any { it.opId == opId } -> TurnPhase.Metadata
            else -> null
        }
    }

    /** The turn is refused before any effect; every call gets a `NotExecuted` disposition naming [reason]. */
    public data class Rejected(val opId: Int, val reason: String) : Partition

    public companion object {
        /** Partition by effects, not merely by family name (§5.4). */
        @JvmStatic
        public fun phaseOf(call: ToolCall): TurnPhase = when (call.family) {
            ToolFamily.Look -> TurnPhase.Read
            ToolFamily.Kb -> if (call.op == "propose") TurnPhase.Metadata else TurnPhase.Read
            ToolFamily.Edit -> TurnPhase.Edit
            ToolFamily.Run, ToolFamily.Verify -> TurnPhase.Execute
            ToolFamily.State, ToolFamily.Task -> TurnPhase.Metadata
        }

        /**
         * Orders [calls] and validates their dependencies: a condition must name an existing op of the right
         * kind (`applied` → an edit, `green` → a run or verify) that executes **before** the conditioned call —
         * run-after-edit and state-after-run are valid, edit-after-a-later-run is rejected before effects (F03).
         * A transform runs alone in its turn.
         */
        @JvmStatic
        public fun of(calls: List<ToolCall>): Partition {
            require(calls.mapIndexed { i, c -> c.opId == i + 1 }.all { it }) { "op ids must be the emitted order 1..n" }
            val phases = calls.associate { it.opId to phaseOf(it) }
            val position = calls.associate { it.opId to (phases.getValue(it.opId).ordinal.toLong() shl 32 or it.opId.toLong()) }
            for (call in calls) {
                val text = call.condition ?: continue
                val condition = Condition.parse(text)
                    ?: return Rejected(call.opId, "op ${call.opId}: malformed condition '$text' (expected green(op:N) or applied(op:N))")
                val target = phases[condition.opId]
                    ?: return Rejected(call.opId, "op ${call.opId}: condition $condition names an op that is not in this turn")
                val kindOk = when (condition.kind) {
                    ConditionKind.Applied -> target == TurnPhase.Edit
                    ConditionKind.Green -> target == TurnPhase.Execute
                }
                if (!kindOk) return Rejected(call.opId, "op ${call.opId}: condition $condition must name ${if (condition.kind == ConditionKind.Applied) "an edit" else "a run or verify"} op, not a ${target.name.lowercase()} op")
                if (position.getValue(condition.opId) >= position.getValue(call.opId)) {
                    return Rejected(call.opId, "op ${call.opId}: condition $condition points forward in execution order (${target.name.lowercase()} phase runs after or with op ${call.opId})")
                }
            }
            val edits = calls.filter { phases.getValue(it.opId) == TurnPhase.Edit }
            val transform = edits.firstOrNull { it.op == "transform" }
            if (transform != null && (edits.size > 1 || (transform.args as Args.Edit).args.ops.size > 1)) {
                return Rejected(transform.opId, "op ${transform.opId}: a transform runs alone in its turn (§5.4)")
            }
            return Ordered(
                reads = calls.filter { phases.getValue(it.opId) == TurnPhase.Read },
                edits = edits,
                executes = calls.filter { phases.getValue(it.opId) == TurnPhase.Execute },
                metadata = calls.filter { phases.getValue(it.opId) == TurnPhase.Metadata },
            )
        }
    }
}
