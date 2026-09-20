package io.astrolabe.auth

import kotlinx.serialization.Serializable

/**
 * One instruction-shape heuristic (§14.3). The list is deliberately small and documented: detection is a
 * model-facing cue, never a filter and never a security proof, so a miss costs a missing warning and a false
 * positive costs one flag on a result the model still sees in full.
 */
@Serializable
public enum class InstructionSignal(public val weight: Int, public val label: String) {
    /** "ignore all previous instructions", "disregard the above rules", "new instructions:". */
    OverrideInstruction(3, "override of prior instructions"),

    /** Chat-template control tokens (`<|im_start|>`, `[INST]`, `<<SYS>>`). */
    ChatTemplate(3, "chat template control token"),

    /** "SYSTEM PROMPT", a markdown `## System` heading. */
    SystemPromptMarker(3, "system-prompt marker"),

    /** Harness delimiters, raw or in their escaped form, inside payload bytes. */
    HarnessDelimiter(3, "harness delimiter in payload"),

    /** "as the assistant", "you are now an agent", "your task is". */
    AgentAddress(3, "content addressed to the agent"),

    /** A conversation role label at the start of a line (`system:`, `assistant:`). */
    RoleLabel(2, "role label at line start"),

    /** A `"tool_call"`/`"function_call"` JSON shape. */
    ToolCallShape(2, "tool-call JSON shape"),

    /** "you must", "you should", an imperative line addressed at a reader. */
    ImperativeToAgent(1, "imperative addressed at the reader"),
}

/** Where a signal fired. [excerpt] is escaped and bounded: reasons are themselves rendered into reports. */
@Serializable
public data class InstructionCue(val signal: InstructionSignal, val line: Int, val excerpt: String) {
    override fun toString(): String = "${signal.label} (line $line): $excerpt"
}

/**
 * Instruction-shape detection over data content (§14.3, F11). Every tool result, note body, packet and file
 * body is data; when it *looks* like an instruction the envelope carries [FLAG]. The content is never filtered,
 * never executed, and authorization is unchanged by the flag — capability is enforced in the executor.
 *
 * `detect` is a pure function of its input: the same text always produces the same cues and score.
 */
public object InstructionShape {
    /** The envelope flag (§5.4 result header). */
    public const val FLAG: String = "⚠ instruction-shaped content"

    /** Distinct signal weights at or above this score raise [FLAG]. */
    public const val THRESHOLD: Int = 3

    /** Detection is a cue, not a proof: beyond this many characters the scan stops and records the limit. */
    public const val MAX_SCAN_CHARS: Int = 200_000

    private const val EXCERPT_CHARS: Int = 80

    private val patterns: List<Pair<InstructionSignal, Regex>> = listOf(
        InstructionSignal.OverrideInstruction to Regex(
            """(?i)\b(?:ignore|disregard|forget|override)\b[^.\n]{0,40}\b(?:previous|prior|above|earlier|all|system)\b[^.\n]{0,40}\b(?:instruction|prompt|rule|direction|guideline|context)""",
        ),
        InstructionSignal.OverrideInstruction to Regex("""(?i)\bnew\s+instructions?\s*:"""),
        InstructionSignal.ChatTemplate to Regex("""<\|(?:im_start|im_end|system|user|assistant|endoftext)\|>|\[/?INST]|<</?SYS>>"""),
        InstructionSignal.SystemPromptMarker to Regex("""(?i)\bsystem\s+prompt\b|^\s{0,3}#{1,6}\s*system\b"""),
        InstructionSignal.HarnessDelimiter to Regex(
            """[${Boundary.RESULT_OPEN}${Boundary.RESULT_CLOSE}${Boundary.GAUGE_OPEN}${Boundary.GAUGE_CLOSE}]|\[\[/?result\b""",
        ),
        InstructionSignal.AgentAddress to Regex(
            """(?i)\bas (?:the|an|your) (?:assistant|agent|ai|model|llm)\b|\byou are (?:now )?(?:an?|the) (?:assistant|agent|ai|model|helpful)\b|\byour (?:new |real )?(?:task|instruction|goal|objective) is\b""",
        ),
        InstructionSignal.RoleLabel to Regex("""(?i)^\s{0,3}(?:system|assistant|user|human|developer)\s*:"""),
        InstructionSignal.ToolCallShape to Regex(""""(?:tool_call|tool_calls|function_call|tool_use)"\s*:|"name"\s*:\s*"[A-Za-z_.]+"\s*,\s*"arguments"\s*:"""),
        InstructionSignal.ImperativeToAgent to Regex("""(?i)\byou (?:must|should|need to|have to|are required to)\b"""),
    )

    /**
     * Scans [text] for instruction-shaped content. Each distinct [InstructionSignal] contributes its weight
     * once, so repetition of one cue cannot flag a document on its own.
     */
    @JvmStatic
    public fun detect(text: String): Detection {
        if (text.isEmpty()) return Detection(flagged = false, score = 0, cues = emptyList(), scanTruncated = false)
        val truncated = text.length > MAX_SCAN_CHARS
        val scanned = if (truncated) text.substring(0, MAX_SCAN_CHARS) else text
        val first = LinkedHashMap<InstructionSignal, InstructionCue>()
        scanned.lineSequence().forEachIndexed { index, line ->
            if (line.isNotEmpty()) {
                for ((signal, regex) in patterns) {
                    if (signal in first) continue
                    val match = regex.find(line) ?: continue
                    first[signal] = InstructionCue(signal, index + 1, excerpt(line, match.range.first))
                }
            }
        }
        val cues = first.values.sortedWith(compareBy({ it.line }, { it.signal.ordinal }))
        val score = first.keys.sumOf { it.weight }
        return Detection(flagged = score >= THRESHOLD, score = score, cues = cues, scanTruncated = truncated)
    }

    // §14.3 an excerpt is rendered back into reports and envelopes, so it is escaped like any other payload.
    private fun excerpt(line: String, at: Int): String {
        val start = (at - 12).coerceAtLeast(0)
        val raw = line.substring(start, (start + EXCERPT_CHARS).coerceAtMost(line.length))
        val trimmed = if (start > 0) "…$raw" else raw
        return Boundary.escape(if (raw.length + start < line.length) "$trimmed…" else trimmed)
    }

    /** The outcome of [detect]: a flag plus the reasons for it. Never a filtering decision. */
    @Serializable
    public data class Detection(
        val flagged: Boolean,
        val score: Int,
        val cues: List<InstructionCue>,
        /** True when the text was longer than [MAX_SCAN_CHARS] and the tail was not scanned. */
        val scanTruncated: Boolean = false,
    ) {
        public val reasons: List<String> get() = cues.map { it.toString() }

        /** Envelope flags for this payload (§5.4): [FLAG] or nothing. */
        public val flags: List<String> get() = if (flagged) listOf(FLAG) else emptyList()

        public fun signals(): Set<InstructionSignal> = cues.map { it.signal }.toSet()
    }
}
