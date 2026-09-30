package io.astrolabe.cell

import io.astrolabe.contract.Shape
import io.astrolabe.provider.ToolMask

/**
 * The wording of whole-turn refusals the cell returns as results (§3.7, §5.4). A masked op is refused for the
 * whole cell, not for one turn: the text says so, lists what the mask allows and names the role's intended
 * exit, so a model does not re-send the same call hoping the next turn differs (F2a).
 */
internal object Refusals {
    /** The trailer of a refusal that executed nothing. */
    const val WHOLE_TURN: String = "; no call of this turn executed"

    const val PLAN_EXIT: String = "to get commands executed, hand the work over with task.propose(plan); " +
        "if the plan cannot be made, end with state(blocked) or task.ask"

    const val OTHER_EXIT: String = "end with task.ask or state(blocked) if the increment cannot proceed without it"

    /**
     * Why [op] is outside [available] — the role's own mask, the [shape]'s mask, or the capability ceiling
     * ([ceiling] is the ceiling's own refusal detail) — then the available ops and the role's exit, without a trailer.
     */
    fun masked(op: String, role: Role, shape: Shape, available: ToolMask, ceiling: String?): String {
        val why = when {
            !role.toolMask.allows(op) -> "$op is not available to the ${role.name} role in this cell (any turn)"
            !Roles.shapeMask(shape).allows(op) -> "$op is not enabled in shape ${shape.name}"
            else -> "$op is outside this cell's capability ceiling (any turn)" + (ceiling?.let { ": $it" } ?: "")
        }
        val ops = available.allowed.sorted().joinToString(", ").ifEmpty { "none" }
        return "$why; available: $ops; ${exit(role)}"
    }

    /** The intended end for [role] when the work needs an op it cannot call. */
    fun exit(role: Role): String = if (role.packetKind == PacketKind.PlanArtifacts) PLAN_EXIT else OTHER_EXIT
}
