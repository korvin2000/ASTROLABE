package io.astrolabe.cell

import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.Stage
import io.astrolabe.contract.Shape
import io.astrolabe.provider.ToolMask
import io.astrolabe.route.Tier
import io.astrolabe.tool.ToolOps
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The cell protocol a role speaks (kernel contract Appendix A-D.1): the structured protocol of Appendix A, or the direct
 * protocol `kernel-direct/1`. A configuration field, never a consequence of the shape: [Roles.mainLine] picks the role.
 */
@Serializable
public enum class Protocol {
    @SerialName("structured")
    Structured,

    @SerialName("direct")
    Direct,
}

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
    /** Note kinds the role may never propose (`kb.propose`): a writer's CON/ADR writes stay with the main line (§10.4). */
    val deniedNoteKinds: Set<String> = emptySet(),
    /** A-D.1: the protocol the cell loop speaks for this role; not encoded at its default, so a structured role keeps its bytes. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val protocol: Protocol = Protocol.Structured,
) {
    /** The v1.0 full constructor: [protocol] takes its default. Kept for Java callers. */
    public constructor(
        name: String,
        contextView: Set<ContextPart>,
        noteScope: Set<String>,
        skillFilter: Set<String>,
        toolMask: ToolMask,
        permission: Stage,
        tierPrior: Tier,
        duties: List<String>,
        askBack: Boolean,
        packetKind: PacketKind,
        personaLines: List<String>,
        policyTextVersion: String,
        deniedNoteKinds: Set<String>,
    ) : this(name, contextView, noteScope, skillFilter, toolMask, permission, tierPrior, duties, askBack, packetKind, personaLines, policyTextVersion, deniedNoteKinds, Protocol.Structured)

    init {
        require(name.isNotBlank()) { "a role needs a name" }
        require(personaLines.size <= MAX_PERSONA_LINES) { "at most $MAX_PERSONA_LINES persona lines, got ${personaLines.size}" }
        require(toolMask.allowed.all { it in ToolOps.known }) { "unknown ops in the mask of '$name': ${toolMask.allowed - ToolOps.known}" }
    }

    /** [toolMask] with the ops it implies ([ToolOps.implied]): what the role may call, refusals and `[S]`/`[A]` lines read this. */
    internal val ops: ToolMask get() = ToolOps.implied(toolMask)

    /** Role mask ∩ shape/stage mask ∩ what the authorization's capability set allows (§3.4). */
    public fun effectiveOps(shape: Shape, ceiling: Ceiling): ToolMask {
        val shapeMask = Roles.shapeMask(shape)
        val ops = ops
        return ToolMask(ops.allowed.filter { op -> shapeMask.allows(op) && ceiling.allows(op, ops) == null }.toSet())
    }

    public companion object {
        public const val MAX_PERSONA_LINES: Int = 3
    }
}

/** The declared role table (§3.4) and the shape masks that bound it. */
public object Roles {
    public const val POLICY_TEXT_VERSION: String = "roles/5"

    private fun ops(vararg names: String): ToolMask = ToolMask(names.toSet())

    private val all: Set<String> = ToolOps.all

    @JvmField
    public val implementing: Role = Role(
        name = "implementing",
        contextView = setOf(ContextPart.Kernel, ContextPart.Prime, ContextPart.ContractSlice, ContextPart.Notes, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR", "LES", "STATUS", "CAL"),
        skillFilter = setOf("*"),
        // §3.4: `task.delegate(writer)` only in S3 — the shape mask (S2+) and the Delegator's kind rule bound it (P4.4.1).
        toolMask = ToolMask(all),
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
        toolMask = ToolMask(ToolOps.look.map { ToolOps.name(io.astrolabe.tool.ToolFamily.Look, it) }.toSet() + ToolOps.kb.filter { it != "propose" }.map { "kb.$it" } + ToolOps.state.map { "state.$it" } + setOf("task.ask", "task.delegate", "task.collect", "task.propose", "verify.baseline", "run.run", "run.poll", "run.wait", "run.cancel")),
        permission = Stage.Patch,
        tierPrior = Tier.High,
        duties = listOf("requirement graph and acceptance proposals", "increments with write scopes and an ownership map", "decision packets, CON/ADR candidates, shape suggestion", "read-only: R-class runs only"),
        askBack = true,
        packetKind = PacketKind.PlanArtifacts,
        personaLines = listOf(
            """Plan, do not implement: hand the work over with task.propose(plan) — {"increments":[{"id":"inc-1","requirements":["R1"],"accept":["AC-1"],"title":"…"}]} using the Contract's R-/AC- ids (optional per increment: write_scope, depends_on, produces artifact|resolves:<question>; optional "acceptance":[{id, requirement, run|check|review}] adds items) — then reply with a one-line summary and no tool call.""",
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
        toolMask = ToolMask(ToolOps.look.map { "look.$it" }.toSet() + setOf("kb.search", "kb.get", "run.run", "run.poll", "run.wait", "run.cancel", "state.patch", "state.blocked", "state.retrieval_miss", "task.ask")),
        permission = Stage.Patch,
        tierPrior = Tier.Medium,
        duties = listOf("bounded findings with coverage and completeness", "read-only: R-class runs only"),
        askBack = true,
        packetKind = PacketKind.Investigation,
        personaLines = RoleTexts.probe,
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
        personaLines = RoleTexts.review,
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
        personaLines = RoleTexts.qa,
    )

    @JvmField
    public val writer: Role = implementing.copy(
        name = "writer",
        contextView = setOf(ContextPart.Kernel, ContextPart.ChildContract, ContextPart.Transcript, ContextPart.Anchor),
        noteScope = setOf("GLOBAL", "CON", "ADR", "LES"),
        // §10.4: the implementer mask minus delegation (a leaf, writers depth 1) and minus CON/ADR writes.
        toolMask = ToolMask(implementing.toolMask.allowed - setOf("task.propose", "task.delegate", "task.collect")),
        duties = listOf("one packet to green acceptance", "never decides interfaces; CON/ADR writes stay with the main line"),
        tierPrior = Tier.Medium,
        deniedNoteKinds = setOf("CON", "ADR"),
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
        personaLines = RoleTexts.repair,
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
        personaLines = RoleTexts.extractor,
    )

    /**
     * A-D.1: the direct protocol's main line in S0 and S1 (owner №2). The implementing role's view, scopes, permission and
     * tier with the 23 direct operations of A-D.3; the S0 shape mask hides `task.propose`. No persona lines.
     */
    @JvmField
    public val direct: Role = implementing.copy(
        name = "direct",
        toolMask = ToolMask(
            setOf(
                "look.tree", "look.outline", "look.read", "look.find", "look.def", "look.refs", "look.recall",
                "edit.anchored", "edit.create", "edit.delete", "edit.rename", "edit.revert",
                "run.run", "run.wait", "run.cancel",
                "verify.check", "verify.baseline",
                "state.note", "state.blocked",
                "task.ask", "task.answer", "task.finish", "task.propose",
            ),
        ),
        duties = listOf("execute one increment to green acceptance", "keep notes with state(note)", "finish through task(finish)"),
        protocol = Protocol.Direct,
    )

    @JvmField
    public val defaults: Map<String, Role> = listOf(implementing, plan, probe, review, qa, writer, repair, extractor, direct).associateBy { it.name }

    /**
     * A-D.1: the role of a main-line cell for [protocol] in [shape] — the contract's shape at the cell's start. The one place
     * a shape chooses a role: `Structured` is [implementing] in every shape; `Direct` is [direct] in S0 and S1 and, until the
     * S2/S3 direct role exists (H1), [implementing] in S2 and S3.
     */
    @JvmStatic
    public fun mainLine(protocol: Protocol, shape: Shape): Role = when (protocol) {
        Protocol.Structured -> implementing
        Protocol.Direct -> when (shape) {
            Shape.S0, Shape.S1 -> direct
            Shape.S2, Shape.S3 -> implementing
        }
    }

    /**
     * The roles whose duty is `read-only: R-class runs only`: their run executor refuses every W- and D-class
     * command before dispatch. Keyed by name because a host override changes wording only (D-38).
     */
    internal fun readOnlyRuns(role: Role): Boolean = role.name == plan.name || role.name == probe.name

    /**
     * What a shape enables (§3.5 collapsibility): S0 = one implementing cell without delegation, proposals or
     * reviews and note proposals (P4.1.3); S1 adds task proposals; S2 adds probes, reviews and collection; S3 everything. The
     * tools, transforms included, are active in every shape (§3.5 table; D-97): the role mask and the capability
     * ceiling still bound them. Every shape admits the direct-only operations (A-D.3 rule 3): no structured role lists them,
     * so a structured cell's effective mask is unchanged.
     */
    @JvmStatic
    public fun shapeMask(shape: Shape): ToolMask = when (shape) {
        Shape.S0 -> S0_MASK
        Shape.S1 -> S1_MASK
        Shape.S2 -> ALL_MASK
        Shape.S3 -> ALL_MASK
    }

    private val S0_MASK: ToolMask = ToolMask(ToolOps.implementingS0.allowed + ToolOps.directOnly)
    private val S1_MASK: ToolMask = ToolMask(S0_MASK.allowed + "task.propose")
    private val ALL_MASK: ToolMask = ToolMask(ToolOps.known)
}
