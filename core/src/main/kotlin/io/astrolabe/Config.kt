package io.astrolabe

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.auth.Stage
import io.astrolabe.cell.Protocol
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleTexts
import io.astrolabe.cell.Roles
import io.astrolabe.id.Digest
import io.astrolabe.kb.KbInjection
import io.astrolabe.provider.Profile
import io.astrolabe.route.TierTable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Host-supplied configuration of one SDK instance. Everything a policy may read lives here; nothing here is
 * read mid-attempt — [AttemptConfig.freeze] snapshots it at campaign open (invariant 12). Sections owned by
 * later tasks (role texts, redaction set, tier table, injection weights) are added by those tasks.
 *
 * **Supported execution modes** (D-12, D-43, D-44, D-47; validated by the OS-contract tests on
 * Windows and Linux, JDK 26 — an unlisted behaviour is unsupported, not assumed):
 * - Platforms: Windows (job objects: kill-on-close, breakaway denied) and Linux (a new session and
 *   process group per launch, `killpg`). macOS and other POSIX systems are untested and unsupported.
 * - Processes: owned by the harness and ended with it. Timeouts, deadlines, cancellation, parent
 *   exit and grandchildren are covered; after a harness crash a handle resolves `lost` (a recycled
 *   pid included) and is never relaunched. A detached, harness-surviving mode does not exist.
 * - Files: every operation resolves through `WorkspacePath`; mutation through a symlink, junction or
 *   other reparse ancestor is refused, and case aliases are unified on case-insensitive filesystems.
 *   Publication is an atomic rename with no compare-and-replace against external writers.
 * - Text: edits accept UTF-8 only (a BOM and CRLF endings are preserved); other encodings and
 *   binary files are refused as `unsupported`. Versions hash raw bytes.
 * - Recovery: process termination at any point on both platforms. OS crash or power loss only where
 *   fsync holds; on Windows the directory entry rests on NTFS journaling (see `Layout`).
 */
@Serializable
public data class Config(
    val defaults: Defaults = Defaults(),
    /** Routable profiles by id; [profileRoles] names which one serves each function. */
    val profiles: Map<String, Profile> = emptyMap(),
    val profileRoles: ProfileRoles = defaults.profileRoles,
    val mode: Mode = defaults.mode,
    val executionMode: ExecutionMode = defaults.executionMode,
    val dClass: DClassPolicy = defaults.dClass,
    val integrityApproval: IntegrityApproval = defaults.integrityApproval,
    val unknownOutcomeReconciliation: UnknownOutcomeReconciliation = defaults.unknownOutcomeReconciliation,
    val ceiling: Stage = defaults.ceiling,
    /** The one authorized rules file (D-32); discovery alone never binds one. */
    val rulesFile: RulesBinding? = null,
    /** Secret patterns, env allowlist and scan cap applied before model exposure and persistence (D-14). */
    val redaction: RedactionConfig = RedactionConfig(),
    /** External durable state root (D-44); `null` selects the OS user-state directory. */
    val stateRoot: String? = null,
    val flags: Flags = Flags(),
    /**
     * Host overrides of the declared roles by name (§3.4, D-38): wording may change, executor authority may
     * not — an override must keep its tool mask within, and its permission at or below, the SDK default.
     */
    val roles: Map<String, Role> = emptyMap(),
    /**
     * Project-configured quality gates (complexity, duplication thresholds): each becomes a `CHK-quality-gate*`
     * check run with the full suite (§8.1, P3.6.2). Never invented: none unless the host configures them.
     */
    val qualityGates: List<io.astrolabe.contract.Command> = emptyList(),
    /** The §11.1 tier table (P4.5.1): profiles per tier with its calibration date; untiered, the router serves every tier with the cell's profile (D-108). */
    val tierTable: TierTable = TierTable.UNTIERED,
    /**
     * Plan §4.4, owner decision №4: a test, build or typecheck command the model runs through `run` becomes its own check,
     * origin `model` — an agent test, never independent acceptance. On by default; `false` turns it off. A declared
     * acceptance command is recognised in `run` either way.
     */
    val modelChecks: Boolean = true,
    /**
     * The balance profile (plan §4.6, C3) a task runs under when its `CampaignPolicy` names none; [defaults] are the
     * Balanced values and the profile applies when the attempt freezes ([BalanceProfiles.applied]). The default is not
     * encoded, so a Balanced attempt keeps its fingerprint. The profile's effort step moves only an effort the host left to
     * it: an explicit one (`CellModel.effortExplicit`, C14) is stronger and runs as given.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val balance: BalanceProfile = BalanceProfile.Balanced,
    /**
     * D-412: the capability set a new contract authorizes, by name — a built-in one ([io.astrolabe.auth.CapabilitySet.BUILT_IN])
     * or one the host defines. The default is the development set; `workspace-local-test-only` is the set without
     * network and package installation.
     */
    val capabilitySet: String = defaults.capabilitySet,
    /**
     * Kernel contract A-D.1: the protocol of the main line, an optional layer — `Structured` (Appendix A) unless the host
     * chooses `Direct`. Frozen with the attempt; every shape accepts either value and no shape chooses it
     * ([Roles.mainLine]). The default is not encoded, so the field itself adds no bytes to a structured configuration; the
     * attempt fingerprint still differs from one frozen before the direct role, which `Roles.defaults` now lists.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val protocol: Protocol = Protocol.Structured,
) {
    init {
        // C11: S3 writers resolve no test-integrity flag on the host's path, so they cannot keep IntegrityApproval.Human.
        require(!(integrityApproval == IntegrityApproval.Human && flags.s3Writers)) {
            "integrityApproval = Human cannot run with flags.s3Writers: S3 writers do not ask a person about test-integrity changes — choose Autonomous or turn s3Writers off"
        }
    }

    /** The v2.0 full constructor before the direct protocol: [protocol] takes its default. Kept for Java callers. */
    public constructor(
        defaults: Defaults,
        profiles: Map<String, Profile>,
        profileRoles: ProfileRoles,
        mode: Mode,
        executionMode: ExecutionMode,
        dClass: DClassPolicy,
        integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation,
        ceiling: Stage,
        rulesFile: RulesBinding?,
        redaction: RedactionConfig,
        stateRoot: String?,
        flags: Flags,
        roles: Map<String, Role>,
        qualityGates: List<io.astrolabe.contract.Command>,
        tierTable: TierTable,
        modelChecks: Boolean,
        balance: BalanceProfile,
        capabilitySet: String,
    ) : this(
        defaults, profiles, profileRoles, mode, executionMode, dClass, integrityApproval, unknownOutcomeReconciliation, ceiling,
        rulesFile, redaction, stateRoot, flags, roles, qualityGates, tierTable, modelChecks, balance, capabilitySet, Protocol.Structured,
    )

    /** The v2.0 C1a constructor: [balance] takes its default. Kept for Java callers. */
    public constructor(
        defaults: Defaults,
        profiles: Map<String, Profile>,
        profileRoles: ProfileRoles,
        mode: Mode,
        executionMode: ExecutionMode,
        dClass: DClassPolicy,
        integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation,
        ceiling: Stage,
        rulesFile: RulesBinding?,
        redaction: RedactionConfig,
        stateRoot: String?,
        flags: Flags,
        roles: Map<String, Role>,
        qualityGates: List<io.astrolabe.contract.Command>,
        tierTable: TierTable,
        modelChecks: Boolean,
    ) : this(
        defaults, profiles, profileRoles, mode, executionMode, dClass, integrityApproval, unknownOutcomeReconciliation, ceiling,
        rulesFile, redaction, stateRoot, flags, roles, qualityGates, tierTable, modelChecks, BalanceProfile.Balanced,
    )

    /** The v1.0 full constructor: [modelChecks] takes its default. Kept for Java callers. */
    public constructor(
        defaults: Defaults,
        profiles: Map<String, Profile>,
        profileRoles: ProfileRoles,
        mode: Mode,
        executionMode: ExecutionMode,
        dClass: DClassPolicy,
        integrityApproval: IntegrityApproval,
        unknownOutcomeReconciliation: UnknownOutcomeReconciliation,
        ceiling: Stage,
        rulesFile: RulesBinding?,
        redaction: RedactionConfig,
        stateRoot: String?,
        flags: Flags,
        roles: Map<String, Role>,
        qualityGates: List<io.astrolabe.contract.Command>,
        tierTable: TierTable,
    ) : this(
        defaults, profiles, profileRoles, mode, executionMode, dClass, integrityApproval, unknownOutcomeReconciliation, ceiling,
        rulesFile, redaction, stateRoot, flags, roles, qualityGates, tierTable, true,
    )

    /** Java hosts (D-07): the fields a host sets most, without the full constructor. */
    public fun withStateRoot(stateRoot: String?): Config = copy(stateRoot = stateRoot)

    public fun withProfiles(profiles: Map<String, Profile>): Config = copy(profiles = profiles)

    public fun withFlags(flags: Flags): Config = copy(flags = flags)

    public fun withTierTable(tierTable: TierTable): Config = copy(tierTable = tierTable)

    public fun withModelChecks(modelChecks: Boolean): Config = copy(modelChecks = modelChecks)

    public fun withBalance(balance: BalanceProfile): Config = copy(balance = balance)

    public fun withProtocol(protocol: Protocol): Config = copy(protocol = protocol)

    /** The role [name] as configured, else the SDK default; `null` for a name neither declares. */
    public fun role(name: String): Role? = roles[name] ?: Roles.defaults[name]

    public fun violations(): List<ConfigViolation> = buildList {
        addAll(defaults.violations())
        addAll(redaction.violations())
        roles.forEach { (name, role) ->
            if (name != role.name) add(ConfigViolation("roles.$name", "key differs from role name '${role.name}'"))
            val default = Roles.defaults[name]
            if (default == null) {
                add(ConfigViolation("roles.$name", "not a declared role; the runtime has no duties for it"))
            } else {
                val widened = role.toolMask.allowed - default.toolMask.allowed
                if (widened.isNotEmpty()) add(ConfigViolation("roles.$name", "override widens the tool mask by ${widened.sorted()}; a role is never a security boundary and overrides change wording only (D-38)"))
                (default.deniedNoteKinds - role.deniedNoteKinds).takeIf { it.isNotEmpty() }?.let { add(ConfigViolation("roles.$name", "override re-grants note proposals ${it.sorted()} (D-38)")) }
                if (role.permission.ordinal > default.permission.ordinal) add(ConfigViolation("roles.$name", "override raises the permission to ${role.permission}; the default is ${default.permission} (D-38)"))
                if (role.packetKind != default.packetKind) add(ConfigViolation("roles.$name", "override changes the output packet; duties and packets are the SDK's (D-38)"))
                RoleTexts.violations(role).forEach { add(ConfigViolation("roles.$name", "override text $it; a role text grants no authority and marks nothing complete (D-161)")) }
            }
        }
        if (profiles.isNotEmpty() || profileRoles.main.isNotEmpty()) {
            if (profileRoles.main !in profiles) add(ConfigViolation("profileRoles.main", "profile '${profileRoles.main}' is not configured"))
            profileRoles.helper?.let { if (it !in profiles) add(ConfigViolation("profileRoles.helper", "profile '$it' is not configured")) }
            profileRoles.escalation?.let { if (it !in profiles) add(ConfigViolation("profileRoles.escalation", "profile '$it' is not configured")) }
        }
        profiles.forEach { (id, profile) -> if (id != profile.id) add(ConfigViolation("profiles.$id", "key differs from profile id '${profile.id}'")) }
        tierTable.profiles.forEach { (tier, ids) -> ids.filter { it !in profiles }.forEach { add(ConfigViolation("tierTable.$tier", "profile '$it' is not configured")) } }
    }

    public val mainProfile: Profile? get() = profiles[profileRoles.main]
}

/**
 * Production-optional `[O]` mechanisms, one switch each, all off until their evaluation gate is passed
 * (D-48, §19.5). Counterfactual research arms (reserve off, test-integrity off, delta-only, clamped routing)
 * are not flags: they live in the evaluation module's `EvalArms` and can never be selected here.
 */
@Serializable
public data class Flags(
    val precompile: Boolean = false,
    val calibrationPrior: Boolean = false,
    val treeSitterIndex: Boolean = false,
    val languageService: Boolean = false,
    val denseRetrieval: Boolean = false,
    val generatedTools: Boolean = false,
    val skillsPromotion: Boolean = false,
    val asyncChecker: Boolean = false,
    val qaCell: Boolean = false,
    val l4Gates: Boolean = false,
    val s3Writers: Boolean = false,
    val otelExport: Boolean = false,
    val worthTestEstimate: Boolean = false,
    /** `[O gate: ablation KB injection off/frozen/live]` (§4.5, P4.1.3): ranked note injection; `CON` in scope is compiled in regardless (F23). */
    val kbInjection: KbInjection = KbInjection.Off,
)

/**
 * An authorized rules-file snapshot (§14.3, D-32): canonical repository-relative path, digest of the approved
 * bytes and who bound it. Changed bytes do not inherit approval.
 */
@Serializable
public data class RulesBinding(
    val path: String,
    val digest: Digest,
    val provenance: String,
) {
    init {
        require(path.isNotBlank()) { "rules path must not be blank" }
        require(provenance.isNotBlank()) { "provenance must name the binding authority" }
    }
}
