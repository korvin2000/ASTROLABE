package io.astrolabe.tool

import io.astrolabe.auth.Capability
import io.astrolabe.auth.Classification
import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest

/** The §12.2 lifecycle of a generated tool: an ephemeral script, a project tool, a global tool. */
public enum class ToolLevel(public val wire: String) {
    /** A script run through `run`/`transform`; never registered. */
    Ephemeral("ephemeral"),

    /** README + schema + tests + declared effects, and a judge review when it has side effects. */
    Project("project"),

    /** Eval-gated against a disposable script and the tool it displaces. */
    Global("global"),
}

/**
 * The global gate's evidence (§12.2): the evaluation run [ref] compared the tool against a disposable script
 * ([beatsScript]) and, when it displaces one, against [displaced] ([beatsDisplaced]).
 */
public data class ToolEvaluation @JvmOverloads constructor(val ref: String, val beatsScript: Boolean, val displaced: String? = null, val beatsDisplaced: Boolean? = null) {
    init {
        require(ref.isNotBlank()) { "an evaluation names its run" }
    }
}

/**
 * One version of a generated tool (§12.2, §15.3). [script] is the argv it runs (the caller's arguments are appended);
 * [effects] and [capabilities] are what it declares — declarations can only raise what the executor would classify,
 * never lower it, and the caller's ceiling still decides (FX-39). [review] is the approving verdict of a side-effecting
 * project tool; [evaluation] the global gate's evidence.
 */
public data class GeneratedTool @JvmOverloads constructor(
    val name: String,
    val version: Int,
    val level: ToolLevel,
    val script: List<String>,
    val effects: EffectClass,
    val capabilities: Set<Capability>,
    val readme: String = "",
    val inputSchema: String = "{\"type\":\"object\"}",
    val tests: List<String> = emptyList(),
    val review: String? = null,
    val evaluation: ToolEvaluation? = null,
) {
    init {
        require(MountDescriptor.NAME.matches(name)) { "generated tool name '$name' must match ${MountDescriptor.NAME.pattern}" }
        require(version >= 1) { "a tool version starts at 1" }
        require(script.isNotEmpty() && script.first().isNotBlank()) { "a generated tool runs a script" }
    }

    val program: String get() = "tool:$name"

    /** The `look(catalog)` one-liner: program, version, level, declared class and the README's first line (data). */
    val line: String
        get() {
            val first = readme.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(100)
            return "$program@v$version [${level.wire}, ${effects.name}]" + (if (first.isEmpty()) "" else " — $first")
        }
}

/** What [ToolRegistry.register] did. */
public sealed interface ToolRegistration {
    /** Recorded as [tool]; it becomes active at the next attempt boundary, never mid-attempt. */
    public data class Registered(val tool: GeneratedTool) : ToolRegistration

    public data class Refused(val gaps: List<String>) : ToolRegistration
}

/** The §12.2 promotion policy: pure functions of the tool record. */
public object GeneratedTools {
    /** What keeps [tool] from its declared level; empty ⇔ it may be registered there. */
    @JvmStatic
    public fun gaps(tool: GeneratedTool): List<String> {
        val gaps = ArrayList<String>()
        when (tool.level) {
            ToolLevel.Ephemeral -> gaps += "an ephemeral script runs through run or transform; it is never registered"
            ToolLevel.Project, ToolLevel.Global -> {
                if (tool.readme.isBlank()) gaps += "a project tool has a README"
                if (MountDescriptor.parseObject(tool.inputSchema) == null) gaps += "a project tool's schema is a JSON object"
                if (tool.tests.isEmpty()) gaps += "a project tool has tests"
                if (tool.effects != EffectClass.R && tool.review.isNullOrBlank()) gaps += "a tool with side effects (${tool.effects}) needs an approving judge review"
            }
        }
        if (tool.level == ToolLevel.Global) {
            val evaluation = tool.evaluation
            when {
                evaluation == null -> gaps += "a global tool is eval-gated: no evaluation"
                !evaluation.beatsScript -> gaps += "a global tool must beat a disposable script (${evaluation.ref})"
                evaluation.displaced != null && evaluation.beatsDisplaced != true -> gaps += "a global tool must beat ${evaluation.displaced}, the tool it displaces (${evaluation.ref})"
            }
        }
        return gaps
    }

    /**
     * [classified] (the executor's classification of the tool's script) with [tool]'s declarations folded in: the
     * higher effect class and the union of capabilities. A generated wrapper never gains a privilege its caller lacks
     * (§12.2): the caller's ceiling judges the result like any command.
     */
    @JvmStatic
    public fun inherit(classified: Classification, tool: GeneratedTool): Classification = classified.copy(
        effectClass = maxOf(classified.effectClass, tool.effects),
        requiredCapabilities = classified.requiredCapabilities + tool.capabilities,
        reasons = classified.reasons + "generated tool ${tool.name}@v${tool.version} (${tool.level.wire}): declares ${tool.effects}, needs " +
            tool.capabilities.sortedBy { it.ordinal }.joinToString(",") { it.wire }.ifEmpty { "nothing" },
        command = "${tool.program} → ${classified.command}",
    )
}

/**
 * Versioned registration (§12.2): every registration is a new version of its tool; what an attempt sees is the set
 * frozen at its boundary ([boundary]), so a tool registered mid-attempt becomes active only at the next one.
 */
public class ToolRegistry {
    private val versions = LinkedHashMap<String, MutableList<GeneratedTool>>()

    /** Registers [tool] as the next version of its name when [GeneratedTools.gaps] finds nothing. */
    @Synchronized
    public fun register(tool: GeneratedTool): ToolRegistration {
        val history = versions[tool.name].orEmpty()
        val expected = (history.lastOrNull()?.version ?: 0) + 1
        val gaps = GeneratedTools.gaps(tool) + listOfNotNull("${tool.name} is at v${expected - 1}: the next version is v$expected".takeIf { tool.version != expected })
        if (gaps.isNotEmpty()) return ToolRegistration.Refused(gaps)
        versions.getOrPut(tool.name) { ArrayList() } += tool
        return ToolRegistration.Registered(tool)
    }

    /** Every recorded version of [name], oldest first. */
    @Synchronized
    public fun history(name: String): List<GeneratedTool> = versions[name].orEmpty().toList()

    /**
     * The tools active for [attempt]: the latest version of each name as registered now, frozen. With the optional
     * layer off (`Flags.generatedTools`, [enabled] false) no generated tool is active.
     */
    @Synchronized
    public fun boundary(attempt: AttemptId, enabled: Boolean): ToolSet =
        if (!enabled) ToolSet(attempt, emptyList()) else ToolSet(attempt, versions.values.map { it.last() })
}

/** The generated tools one attempt runs with (§12.2): frozen at its boundary, listed by `look(catalog)`. */
public class ToolSet(public val attempt: AttemptId?, tools: Collection<GeneratedTool>) {
    private val byProgram: Map<String, GeneratedTool> = tools.sortedBy { it.name }.associateBy { it.program }

    public val lines: List<String> = byProgram.values.map { it.line }

    public val digest: Digest = Digest.ofUtf8(byProgram.values.joinToString("\n") { "${it.program}@v${it.version}\u0000${it.level}\u0000${it.effects}\u0000${it.script}\u0000${it.inputSchema}" })

    /** The active tool `tool:<name>` names, or `null` when none was active at this attempt's boundary. */
    public fun resolve(program: String): GeneratedTool? = byProgram[program.trim()]

    public companion object {
        @JvmField
        public val EMPTY: ToolSet = ToolSet(null, emptyList())
    }
}
