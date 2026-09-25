package io.astrolabe.recover

import io.astrolabe.budget.Tokens
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps

/**
 * The failure capsule (§13.2 step 4): everything the repair helper sees — it gets no transcript. [intendedOperation]
 * is the failing call's `family.op`; its family bounds the helper's tools ([mask]).
 */
public data class Capsule(
    val intendedOperation: String,
    val acceptanceCriterion: String,
    val callArguments: String,
    val environment: String,
    val errorOrExit: String,
    val artifactVersions: Map<String, FileVersion>,
    val completedEffects: List<String>,
    val rawEvidenceRefs: List<String>,
    val previousAttempts: List<String>,
    val allowedFixes: List<String>,
    val remainingBudget: Tokens,
) {
    init {
        require(intendedOperation in ToolOps.all) { "the intended operation is a known family.op, got '$intendedOperation'" }
        require(acceptanceCriterion.isNotBlank()) { "a capsule names the acceptance criterion the repair must re-verify" }
        require(errorOrExit.isNotBlank()) { "a capsule carries the error or exit status" }
    }

    public val family: ToolFamily get() = ToolFamily.byWire(intendedOperation.substringBefore('.'))!!

    /** §3.4 repair row: the failing family + `look` + `run`, nothing else. */
    public val mask: ToolMask
        get() = ToolMask((ToolOps.of(ToolFamily.Look).map { ToolOps.name(ToolFamily.Look, it) } +
            ToolOps.of(ToolFamily.Run).map { ToolOps.name(ToolFamily.Run, it) } +
            ToolOps.of(family).map { ToolOps.name(family, it) }).toSet())

    /** A copy whose collections the caller can no longer change (authority boundary). */
    internal fun frozen(): Capsule = copy(
        artifactVersions = LinkedHashMap(artifactVersions).toMap(),
        completedEffects = completedEffects.toList(),
        rawEvidenceRefs = rawEvidenceRefs.toList(),
        previousAttempts = previousAttempts.toList(),
        allowedFixes = allowedFixes.toList(),
    )
}
