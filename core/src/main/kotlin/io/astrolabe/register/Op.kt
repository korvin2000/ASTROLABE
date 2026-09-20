package io.astrolabe.register

import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Typed STATE ops (§5.2); the model emits only these, never Markdown. */
@Serializable
public sealed interface Op {
    @Serializable
    @SerialName("plan.add")
    public data class PlanAdd(val text: String, val accept: String? = null, val after: Int? = null, val req: String? = null) : Op

    @Serializable
    @SerialName("plan.cursor")
    public data class PlanCursor(val n: Int) : Op

    @Serializable
    @SerialName("plan.tick")
    public data class PlanTick(val n: Int, val evidence: String? = null) : Op

    @Serializable
    @SerialName("plan.cancel")
    public data class PlanCancel(val n: Int, val reason: String) : Op

    @Serializable
    @SerialName("fact.add")
    public data class FactAdd(val kind: ClaimKind, val text: String, val evidence: String? = null, val anchor: Anchor? = null) : Op

    @Serializable
    @SerialName("fact.refute")
    public data class FactRefute(val n: Int, val evidence: String) : Op

    @Serializable
    @SerialName("deadend.add")
    public data class DeadendAdd(val text: String, val evidence: String?, val scope: String, val reopen: String) : Op

    @Serializable
    @SerialName("decision.add")
    public data class DecisionAdd(val text: String, val because: String, val rejected: String?, val probe: String? = null, val adrCandidate: Boolean = false) : Op

    @Serializable
    @SerialName("open.add")
    public data class OpenAdd(val text: String, val trip: String? = null, val needs: String? = null) : Op

    @Serializable
    @SerialName("open.close")
    public data class OpenClose(val n: Int, val evidence: String) : Op

    @Serializable
    @SerialName("focus.set")
    public data class FocusSet(val dir: String) : Op

    @Serializable
    @SerialName("amend.propose")
    public data class AmendPropose(val change: String, val reason: String) : Op

    @Serializable
    @SerialName("next")
    public data class Next(val text: String) : Op
}

@Serializable
public enum class ConditionKind {
    @SerialName("green")
    Green,

    @SerialName("applied")
    Applied,
}

/** `if: green(op:N)` / `if: applied(op:N)` (§5.5): evaluated after the turn's runs and edits. */
@Serializable
public data class Condition(val kind: ConditionKind, val opId: Int) {
    override fun toString(): String = "${kind.name.lowercase()}(op:$opId)"

    public companion object {
        /** Parses `green(op:2)` or `applied(op:1)`. */
        @JvmStatic
        public fun parse(text: String): Condition? {
            val m = Regex("""^(green|applied)\(op:(\d+)\)$""").matchEntire(text.trim()) ?: return null
            return Condition(if (m.groupValues[1] == "green") ConditionKind.Green else ConditionKind.Applied, m.groupValues[2].toInt())
        }
    }
}

@Serializable
public data class PatchOp(val op: Op, val condition: Condition? = null)

@Serializable
public data class Patch(val ops: List<PatchOp>) {
    public companion object {
        @JvmStatic
        public fun of(vararg ops: Op): Patch = Patch(ops.map { PatchOp(it) })
    }
}
