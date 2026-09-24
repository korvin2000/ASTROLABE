package io.astrolabe.cell

import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Stage
import io.astrolabe.contract.Shape
import io.astrolabe.provider.ToolMask
import io.astrolabe.route.Tier
import io.astrolabe.tool.ToolOps
import kotlinx.serialization.Serializable

/** What a role's compiled context is built from (§3.4 "context view"). */
@Serializable
public enum class ContextPart {
    Kernel, Prime, ContractSlice, Notes, BehaviourMaps, CalibrationPrior, Transcript, Anchor,
    Question, EvidencePacket, EntryPoints, ChildContract, Capsule, FinalState, JournalDigest, DiffSummary, Receipts,
}

/** The packet a role ends with (§3.4 "output"). */
@Serializable
public enum class PacketKind { Result, Investigation, Verdict, ReceiptsAndCases, Diagnosis, NoteCandidates, PlanArtifacts }

/**
 * A role is a configuration of the one cell runtime (§3.4): context view × note scope × skill filter × tool
 * mask × permission level × tier prior × duties × ask-back × output packet, plus at most three persona lines.
 * It is never a security boundary: [effectiveOps] intersects the mask with the shape's mask and the
 * contract's capability ceiling, and the executor enforces capability regardless (L10). Persona and duty
 * text are frozen SDK defaults; a host override changes wording only (D-38, see `Config.violations`).
 */
@Serializable
public data class Role(
    val name: String,
    val contextView: Set<ContextPart>,
    /** Note kinds the role reads (`GLOBAL`, `CON`, `ADR`, `LES`, `STATUS`, …); empty = none. */
    val noteScope: Set<String>,
    /** Skill ids or tags the role may load; `*` = every admitted skill. */
    val skillFilter: Set<String>,
    val toolMask: ToolMask,
    val permission: Stage,
    val tierPrior: Tier,
    val duties: List<String>,
    val askBack: Boolean,
    val packetKind: PacketKind,
    val personaLines: List<String> = emptyList(),
    /** Version of the role's policy text, recorded in attempt and compile fingerprints (D-38). */
    val policyTextVersion: String = Roles.POLICY_TEXT_VERSION,
) {
    init {
        require(name.isNotBlank()) { "a role needs a name" }
        require(personaLines.size <= MAX_PERSONA_LINES) { "at most $MAX_PERSONA_LINES persona lines, got ${personaLines.size}" }
        require(toolMask.allowed.all { it in ToolOps.all }) { "unknown ops in the mask of '$name': ${toolMask.allowed - ToolOps.all}" }
    }

    /** Role mask ∩ shape/stage mask ∩ what the authorization's capability set allows (§3.4). */
    public fun effectiveOps(shape: Shape, ceiling: Ceiling): ToolMask {
        val shapeMask = Roles.shapeMask(shape)
        return ToolMask(toolMask.allowed.filter { op -> shapeMask.allows(op) && ceiling.allows(op, toolMask) == null }.toSet())
    }

    public companion object {
        public const val MAX_PERSONA_LINES: Int = 3
    }
}

/** The declared role table (§3.4) and the shape masks that bound it. */
public object Roles {
    public const val POLICY_TEXT_VERSION: String = "roles/2"

    private fun ops(vararg names: String): ToolMask = ToolMask(names.toSet())

    private val all: Set<String> = ToolOps.all

    @JvmField
    public val implementing: Role = Role(
        name = "implementing",
        contextView = setOf(ContextPart.Kernel, ContextPart.Prime, ContextPart.ContractSlice, ContextPart.Notes, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR", "LES", "STATUS", "CAL"),
        skillFilter = setOf("*"),
        // §3.4: `task.delegate(writer)` only in S3 — the shape mask (S2+) and the Delegator's kind rule bound it (P4.4.1).
        toolMask = ToolMask(all - setOf("kb.propose")),
        permission = Stage.LocalCommit,
        tierPrior = Tier.High,
        duties = listOf("execute one increment to green acceptance", "maintain STATE through typed ops", "propose notes, never admit them"),
        askBack = true,
        packetKind = PacketKind.Result,
    )

    @JvmField
    public val plan: Role = Role(
        name = "plan",
        contextView = setOf(ContextPart.Kernel, ContextPart.ContractSlice, ContextPart.Prime, ContextPart.Notes, ContextPart.BehaviourMaps, ContextPart.CalibrationPrior, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR"),
        skillFilter = setOf("*"),
        toolMask = ToolMask(ToolOps.look.map { ToolOps.name(io.astrolabe.tool.ToolFamily.Look, it) }.toSet() + ToolOps.kb.filter { it != "propose" }.map { "kb.$it" } + ToolOps.state.map { "state.$it" } + setOf("task.ask", "task.delegate", "task.collect", "task.propose", "verify.baseline")),
        permission = Stage.Patch,
        tierPrior = Tier.High,
        duties = listOf("requirement graph and acceptance proposals", "increments with write scopes and an ownership map", "decision packets, CON/ADR candidates, shape suggestion"),
        askBack = true,
        packetKind = PacketKind.PlanArtifacts,
        personaLines = listOf(
            "Plan, do not implement: end with task.propose(plan) holding pending increments, each with a run: or a check: naming its evidence kind.",
            "A requirement no command can decide gets a review: item naming the judgment it needs, never an invented oracle.",
            "Record consequential choices with decision.add; mark those that cross a boundary as ADR candidates.",
        ),
    )

    @JvmField
    public val probe: Role = Role(
        name = "probe",
        contextView = setOf(ContextPart.Kernel, ContextPart.Question, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON"),
        skillFilter = emptySet(),
        toolMask = ToolMask(ToolOps.look.map { "look.$it" }.toSet() + setOf("kb.search", "kb.get", "run.run", "run.poll", "run.cancel", "state.patch", "state.blocked", "state.retrieval_miss", "task.ask")),
        permission = Stage.Patch,
        tierPrior = Tier.Medium,
        duties = listOf("bounded findings with coverage and completeness", "read-only: R-class runs only"),
        askBack = true,
        packetKind = PacketKind.Investigation,
    )

    @JvmField
    public val review: Role = Role(
        name = "review",
        contextView = setOf(ContextPart.Kernel, ContextPart.EvidencePacket, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR"),
        skillFilter = emptySet(),
        toolMask = ToolMask(ToolOps.look.map { "look.$it" }.toSet() + setOf("verify.tests", "kb.search", "kb.get", "state.patch")),
        permission = Stage.Patch,
        tierPrior = Tier.High,
        duties = listOf("verdict against acceptance and contracts, never the proposer's transcript", "findings; insufficient_evidence allowed"),
        askBack = false,
        packetKind = PacketKind.Verdict,
    )

    @JvmField
    public val qa: Role = Role(
        name = "qa",
        contextView = setOf(ContextPart.Kernel, ContextPart.ContractSlice, ContextPart.EntryPoints, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON"),
        skillFilter = emptySet(),
        toolMask = ToolMask(ToolOps.look.map { "look.$it" }.toSet() + ToolOps.run.map { "run.$it" } + ToolOps.verify.filter { it != "review" }.map { "verify.$it" } + ToolOps.state.map { "state.$it" }),
        permission = Stage.Patch,
        tierPrior = Tier.Medium,
        duties = listOf("independent cases", "exercise the product in a disposable environment"),
        askBack = false,
        packetKind = PacketKind.ReceiptsAndCases,
    )

    @JvmField
    public val writer: Role = implementing.copy(
        name = "writer",
        contextView = setOf(ContextPart.Kernel, ContextPart.ChildContract, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR", "LES"),
        // A writer is a leaf (writers depth 1, §10.1): it never delegates.
        toolMask = ToolMask(implementing.toolMask.allowed - setOf("task.propose", "task.delegate", "task.collect")),
        duties = listOf("one packet to green acceptance", "never decides interfaces; CON/ADR writes stay with the main line"),
        tierPrior = Tier.Medium,
    )

    @JvmField
    public val repair: Role = Role(
        name = "repair",
        contextView = setOf(ContextPart.Kernel, ContextPart.Capsule),
        noteScope = emptySet(),
        skillFilter = emptySet(),
        toolMask = ToolMask(ToolOps.look.map { "look.$it" }.toSet() + setOf("run.run", "edit.anchored", "state.patch")),
        permission = Stage.Patch,
        tierPrior = Tier.Low,
        duties = listOf("at most two attempts: fixed, diagnosis, or escalate", "a diagnosis of at most 100 tokens plus an optional corrected call"),
        askBack = false,
        packetKind = PacketKind.Diagnosis,
    )

    @JvmField
    public val extractor: Role = Role(
        name = "extractor",
        contextView = setOf(ContextPart.Kernel, ContextPart.FinalState, ContextPart.JournalDigest, ContextPart.DiffSummary, ContextPart.Receipts),
        noteScope = setOf("GLOBAL", "CON", "ADR", "LES", "STATUS", "CAL"),
        skillFilter = emptySet(),
        toolMask = ToolMask(ToolOps.kb.map { "kb.$it" }.toSet()),
        permission = Stage.Patch,
        tierPrior = Tier.Low,
        duties = listOf("candidate notes with evidence", "dedupe, supersession, lint"),
        askBack = false,
        packetKind = PacketKind.NoteCandidates,
    )

    @JvmField
    public val defaults: Map<String, Role> = listOf(implementing, plan, probe, review, qa, writer, repair, extractor).associateBy { it.name }

    /**
     * What a shape enables (§3.5 collapsibility): S0 = one implementing cell without delegation, proposals or
     * reviews; S1 adds proposals and note proposals; S2 adds probes, reviews and collection; S3 everything. The
     * tools, transforms included, are active in every shape (§3.5 table; D-97): the role mask and the capability
     * ceiling still bound them.
     */
    @JvmStatic
    public fun shapeMask(shape: Shape): ToolMask = when (shape) {
        Shape.S0 -> ToolOps.implementingS0
        Shape.S1 -> ToolMask(ToolOps.implementingS0.allowed + setOf("task.propose", "kb.propose"))
        Shape.S2 -> ToolMask(all)
        Shape.S3 -> ToolMask(all)
    }
}
