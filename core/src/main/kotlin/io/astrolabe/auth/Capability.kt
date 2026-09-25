package io.astrolabe.auth

import io.astrolabe.contract.Authorization
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import kotlinx.serialization.Serializable

/**
 * Executor capabilities (§14.1). A capability authorizes approved access, never arbitrary shell access to the
 * stores behind it; the executor enforces the set regardless of what the model requests (L10).
 */
@Serializable
public enum class Capability(public val wire: String) {
    WorkspaceRead("workspace-read"),
    WorkspaceWrite("workspace-write"),
    RunLocal("run-local"),
    Network("network"),
    PackageInstall("package-install"),
    GitRefs("git-refs"),
    OutsideWorkspace("outside-workspace"),
    Privilege("privilege"),
    ;

    override fun toString(): String = wire
}

/** A named capability set; [Authorization.capabilitySet] refers to one by name. */
@Serializable
public data class CapabilitySet(val name: String, val capabilities: Set<Capability>) {
    init {
        require(name.isNotBlank()) { "a capability set needs a name" }
    }

    public operator fun contains(capability: Capability): Boolean = capability in capabilities

    /** Capabilities of [required] this set does not hold, in declaration order. */
    public fun missing(required: Set<Capability>): Set<Capability> =
        Capability.entries.filter { it in required && it !in capabilities }.toSet()

    override fun toString(): String = "$name{${capabilities.sortedBy { it.ordinal }.joinToString(",") { it.wire }}}"

    public companion object {
        /** The S0 default: read, write and run inside the workspace, nothing else. */
        @JvmField
        public val WORKSPACE_LOCAL_TEST_ONLY: CapabilitySet = CapabilitySet(
            "workspace-local-test-only",
            setOf(Capability.WorkspaceRead, Capability.WorkspaceWrite, Capability.RunLocal),
        )

        /** Investigation-only cells (probe, review): look, never write, never run. */
        @JvmField
        public val WORKSPACE_READ_ONLY: CapabilitySet = CapabilitySet("workspace-read-only", setOf(Capability.WorkspaceRead))

        @JvmField
        public val BUILT_IN: Map<String, CapabilitySet> =
            listOf(WORKSPACE_LOCAL_TEST_ONLY, WORKSPACE_READ_ONLY).associateBy { it.name }

        /** Built-in sets first, then host-defined ones; `null` when the name is unknown (a configuration error). */
        @JvmStatic
        @JvmOverloads
        public fun resolve(name: String, hostSets: Map<String, CapabilitySet> = emptyMap()): CapabilitySet? =
            BUILT_IN[name] ?: hostSets[name]
    }
}

/** Why the executor refused; recorded with the refusal so a report never has to reconstruct it. */
@Serializable
public enum class RefusalReason(public val wire: String) {
    /** The role mask does not list the op this turn (the schema still lists it — D-20). */
    MaskedOp("masked-op"),
    UnknownOp("unknown-op"),
    MissingCapability("missing-capability"),
    AboveStageCeiling("above-stage-ceiling"),
    /** A ladder stage that exists in the vocabulary but is not reachable in this release. */
    StageNotImplemented("stage-not-implemented"),
    ConfinementUnavailable("confinement-unavailable"),
    ProtectedContent("protected-content"),
    /** A publication of a candidate whose L0–L2 checks are not green at its current stamp (§14.2). */
    UnverifiedCandidate("unverified-candidate"),
    /** A publication stage asked for before the stage below it was reached (§14.2). */
    StageOutOfOrder("stage-out-of-order"),
    /** The authority did not approve the D-class grant, or its decision answered another request or revision. */
    NotApproved("not-approved"),
    /** A harness commit aimed at the branch the user has checked out (§20.1 TRACE rejection). */
    UserBranch("user-branch"),
    ;

    override fun toString(): String = wire
}

/** A typed executor refusal (§14.1): recorded, reported, and never downgraded into a relabelled run. */
@Serializable
public data class Refusal(val subject: String, val reason: RefusalReason, val detail: String) {
    init {
        require(subject.isNotBlank() && detail.isNotBlank()) { "a refusal names its subject and reason" }
    }

    override fun toString(): String = "refused $subject [${reason.wire}]: $detail"
}

/** Capabilities each `family.op` needs. Harness-internal families need none beyond the executor itself. */
public object OpCapabilities {
    private val byFamily: Map<ToolFamily, Set<Capability>> = mapOf(
        ToolFamily.Look to setOf(Capability.WorkspaceRead),
        ToolFamily.Edit to setOf(Capability.WorkspaceRead, Capability.WorkspaceWrite),
        ToolFamily.Run to setOf(Capability.WorkspaceRead, Capability.RunLocal),
        ToolFamily.Verify to setOf(Capability.WorkspaceRead, Capability.RunLocal),
        ToolFamily.State to emptySet(),
        ToolFamily.Task to emptySet(),
        ToolFamily.Kb to emptySet(),
    )

    /** `null` for an op outside the seven families (refused as unknown, never dispatched). */
    @JvmStatic
    public fun required(op: String): Set<Capability>? {
        if (op !in ToolOps.all) return null
        val family = ToolFamily.byWire(op.substringBefore('.')) ?: return null
        return byFamily.getValue(family)
    }
}

/**
 * The ceiling an executor enforces for one cell (§14.1, L10): the capability set from the contract, the
 * permission-ladder stage the contract authorizes and the execution-mode label. It is enforced independently
 * of the tool schema and of anything the model asks for; generated scripts and MCP mounts are dispatched
 * through the same ceiling, so they inherit it rather than widening it (FX-39).
 */
@Serializable
public data class Ceiling(
    val capabilities: CapabilitySet,
    val stage: Stage,
    val executionMode: ExecutionMode,
) {
    /**
     * Refuses [op] when the role mask does not list it this turn or when it needs a capability outside the
     * set — even though the frozen schema still lists the op (D-20). `null` means the ceiling permits it.
     */
    public fun allows(op: String, mask: ToolMask): Refusal? {
        val required = OpCapabilities.required(op)
            ?: return Refusal(op, RefusalReason.UnknownOp, "'$op' is not one of the seven tool families")
        if (!mask.allows(op)) {
            return Refusal(op, RefusalReason.MaskedOp, "'$op' is not in this role's mask for this turn")
        }
        val missing = capabilities.missing(required)
        if (missing.isNotEmpty()) {
            return Refusal(
                op,
                RefusalReason.MissingCapability,
                "'$op' needs ${missing.joinToString(", ") { it.wire }}, outside capability set '${capabilities.name}'",
            )
        }
        return null
    }

    /**
     * Refuses a classified command whose effects need capabilities outside the set — the enforcement point
     * for generated scripts, transforms and MCP mounts that request broader access than their caller holds.
     */
    public fun allows(classification: Classification): Refusal? {
        val missing = capabilities.missing(classification.requiredCapabilities)
        if (missing.isEmpty()) return null
        return Refusal(
            "class=${classification.effectClass}",
            RefusalReason.MissingCapability,
            "needs ${missing.joinToString(", ") { it.wire }}, outside capability set '${capabilities.name}'" +
                classification.reasons.joinToString(prefix = " (", postfix = ")", separator = "; "),
        )
    }

    /** Refuses a publication stage above the contract's ceiling (§14.2). */
    public fun allows(requested: Stage): Refusal? =
        if (requested.ordinal <= stage.ordinal) {
            null
        } else {
            Refusal(
                requested.name.lowercase(),
                RefusalReason.AboveStageCeiling,
                "the contract authorizes up to '${stage.name.lowercase()}'",
            )
        }

    public companion object {
        /**
         * The ceiling of a contract: capability set by name from [Authorization], ladder ceiling from the
         * contract and the execution-mode label from `Config`. An unknown capability-set name is a
         * configuration error and fails here rather than degrading to a wider set.
         */
        @JvmStatic
        @JvmOverloads
        public fun of(
            authorization: Authorization,
            executionMode: ExecutionMode,
            hostSets: Map<String, CapabilitySet> = emptyMap(),
        ): Ceiling {
            val set = CapabilitySet.resolve(authorization.capabilitySet, hostSets)
            requireNotNull(set) {
                "unknown capability set '${authorization.capabilitySet}'; known: ${(CapabilitySet.BUILT_IN.keys + hostSets.keys).sorted()}"
            }
            return Ceiling(set, authorization.ladderCeiling, executionMode)
        }
    }
}
