package io.astrolabe.tool

import io.astrolabe.provider.ToolMask
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The seven tool families (§5.4); `tools.catalog` is shorthand for `look(catalog)`, not an eighth family. */
@Serializable
public enum class ToolFamily(public val wire: String) {
    @SerialName("look")
    Look("look"),

    @SerialName("edit")
    Edit("edit"),

    @SerialName("run")
    Run("run"),

    @SerialName("verify")
    Verify("verify"),

    @SerialName("state")
    State("state"),

    @SerialName("task")
    Task("task"),

    @SerialName("kb")
    Kb("kb"),
    ;

    public companion object {
        @JvmStatic
        public fun byWire(name: String): ToolFamily? = entries.firstOrNull { it.wire == name }
    }
}

/** Effect classes (§4.6): policy labels verified after the fact by the stamp diff. */
@Serializable
public enum class EffectClass { R, W, D }

/**
 * Operation names as `family.op`; a mask lists the ones a role may call this turn. Local enforcement always
 * applies (L10); schemas stay byte-stable and masked ops are refused by the executor, never removed (D-20).
 */
public object ToolOps {
    public val look: List<String> = listOf("tree", "outline", "read", "find", "def", "refs", "importers", "impact", "recall", "bmap", "catalog")
    public val edit: List<String> = listOf("anchored", "create", "delete", "rename", "revert", "transform")
    public val run: List<String> = listOf("run", "poll", "cancel")
    public val verify: List<String> = listOf("check", "tests", "acceptance", "baseline", "review")
    public val state: List<String> = listOf("patch", "blocked", "retrieval_miss")
    public val task: List<String> = listOf("ask", "delegate", "collect", "propose")
    public val kb: List<String> = listOf("search", "get", "propose", "skill")

    public fun of(family: ToolFamily): List<String> = when (family) {
        ToolFamily.Look -> look
        ToolFamily.Edit -> edit
        ToolFamily.Run -> run
        ToolFamily.Verify -> verify
        ToolFamily.State -> state
        ToolFamily.Task -> task
        ToolFamily.Kb -> kb
    }

    public fun name(family: ToolFamily, op: String): String = "${family.wire}.$op"

    public val all: Set<String> = ToolFamily.entries.flatMap { f -> of(f).map { name(f, it) } }.toSet()

    /**
     * Everything an S0 implementing cell may call in Stage A (refs/importers/impact since P3.2.3, transform since P3.3.1;
     * kb.propose since P4.1.3; bmap arrives later; propose is S1+, delegate/collect S2+ through the shape mask, P4.4.1). `verify.review` is the campaign-scope human review path (P3.5.2, D-23); the review cell is P4.4.3.
     */
    public val implementingS0: ToolMask = ToolMask(
        all - setOf("look.bmap", "task.delegate", "task.collect", "task.propose"),
    )
}
