package io.astrolabe

import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.auth.Stage
import io.astrolabe.cell.Role
import io.astrolabe.cell.RoleTexts
import io.astrolabe.cell.Roles
import io.astrolabe.id.Digest
import io.astrolabe.kb.KbInjection
import io.astrolabe.provider.Profile
import io.astrolabe.route.TierTable
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
) {
    /** Java hosts (D-07): the fields a host sets most, without the full constructor. */
    public fun withStateRoot(stateRoot: String?): Config = copy(stateRoot = stateRoot)

    public fun withProfiles(profiles: Map<String, Profile>): Config = copy(profiles = profiles)

    public fun withFlags(flags: Flags): Config = copy(flags = flags)

    public fun withTierTable(tierTable: TierTable): Config = copy(tierTable = tierTable)

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
