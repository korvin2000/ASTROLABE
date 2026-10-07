package io.astrolabe

import io.astrolabe.cell.RoleTexts
import io.astrolabe.id.Digest
import io.astrolabe.verify.ScratchPolicy
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Mandatory correctness controls. They are always on in production; only an evaluation research arm may
 * represent a disabled control, and such an arm is never promotion-eligible (D-48, I-18).
 */
@Serializable
public data class Controls(
    val reserve: Boolean = true,
    val testIntegrityGuard: Boolean = true,
    val deltaPlusAbsolute: Boolean = true,
    val floors: Boolean = true,
    val lifecycleControls: Boolean = true,
) {
    val allEnabled: Boolean get() = reserve && testIntegrityGuard && deltaPlusAbsolute && floors && lifecycleControls

    public fun disabled(): List<String> = buildList {
        if (!reserve) add("reserve")
        if (!testIntegrityGuard) add("testIntegrityGuard")
        if (!deltaPlusAbsolute) add("deltaPlusAbsolute")
        if (!floors) add("floors")
        if (!lifecycleControls) add("lifecycleControls")
    }

    public companion object {
        @JvmField
        public val ALL: Controls = Controls()
    }
}

/**
 * The frozen configuration of one attempt (invariant 12): harness version, validated config snapshot, role-text
 * versions, controls and the output policy. Mid-attempt configuration changes have no effect until the next attempt
 * (P1.9.4). [fingerprint] enters compile and attempt fingerprints (D-38).
 */
@Serializable(with = AttemptConfig.Serializer::class)
public class AttemptConfig(
    public val harnessVersion: String,
    config: Config,
    roleTextVersions: Map<String, String>,
    public val controls: Controls = Controls.ALL,
    /** False only for evaluation research arms; such attempts are ineligible for promotion. */
    public val production: Boolean = true,
    scratch: ScratchPolicy?,
) {
    /** The constructor before [scratch] (W3): an attempt without an output policy. Kept for Java callers. */
    @JvmOverloads
    public constructor(harnessVersion: String, config: Config, roleTextVersions: Map<String, String>, controls: Controls = Controls.ALL, production: Boolean = true) :
        this(harnessVersion, config, roleTextVersions, controls, production, null)

    public val config: Config = configSnapshot(config)
    public val roleTextVersions: Map<String, String> = frozenMap(roleTextVersions)

    /**
     * The output policy of the attempt (§8.4, W3, owner №32), frozen before its `s0`: every stamper, snapshot and scheduler
     * of the attempt uses it. An attempt frozen before W3 has none ([ScratchPolicy.NONE]: its v1 identity is kept).
     */
    public val scratch: ScratchPolicy = scratch ?: ScratchPolicy.NONE
    private val snapshot = AttemptConfigSnapshot(harnessVersion, this.config, this.roleTextVersions, controls, production, scratch?.takeIf { it.id != null })
    public val fingerprint: Digest = Digest.ofUtf8("astrolabe/attempt-config/v2\n" + STABLE_JSON.encodeToString(AttemptConfigSnapshot.serializer(), snapshot))

    /** Every construction path, including copy and decoding, takes a fresh immutable snapshot. */
    public fun copy(
        harnessVersion: String = this.harnessVersion,
        config: Config = this.config,
        roleTextVersions: Map<String, String> = this.roleTextVersions,
        controls: Controls = this.controls,
        production: Boolean = this.production,
    ): AttemptConfig = AttemptConfig(harnessVersion, config, roleTextVersions, controls, production, scratch.takeIf { it.id != null })

    /**
     * Task-workflow §5.1 (D-435): this configuration with the effective output policy — the frozen base plus the task's
     * [declared] outputs at the attempt's open — frozen before its `s0` like the base. A policy before version 3 takes none.
     */
    public fun withOutputs(declared: Collection<String>): AttemptConfig =
        AttemptConfig(harnessVersion, config, roleTextVersions, controls, production, scratch.withOutputs(declared).takeIf { it.id != null })

    public operator fun component1(): String = harnessVersion
    public operator fun component2(): Config = config
    public operator fun component3(): Map<String, String> = roleTextVersions
    public operator fun component4(): Controls = controls
    public operator fun component5(): Boolean = production

    override fun equals(other: Any?): Boolean = other is AttemptConfig && snapshot == other.snapshot
    override fun hashCode(): Int = snapshot.hashCode()
    override fun toString(): String = snapshot.toString().replaceFirst("AttemptConfigSnapshot", "AttemptConfig")

    internal object Serializer : KSerializer<AttemptConfig> {
        override val descriptor: SerialDescriptor = AttemptConfigSnapshot.serializer().descriptor
        override fun serialize(encoder: Encoder, value: AttemptConfig) {
            encoder.encodeSerializableValue(AttemptConfigSnapshot.serializer(), value.snapshot)
        }
        override fun deserialize(decoder: Decoder): AttemptConfig {
            val value = decoder.decodeSerializableValue(AttemptConfigSnapshot.serializer())
            return AttemptConfig(value.harnessVersion, value.config, value.roleTextVersions, value.controls, value.production, value.scratch)
        }
    }

    /** Every reason this configuration must not run as a production attempt. */
    public fun productionViolations(): List<ConfigViolation> = buildList {
        addAll(config.violations())
        controls.disabled().forEach { add(ConfigViolation("controls.$it", "mandatory control disabled")) }
        if (!production) add(ConfigViolation("production", "research arm, not a production attempt"))
        if (harnessVersion.isBlank()) add(ConfigViolation("harnessVersion", "must not be blank"))
    }

    public companion object {
        private val STABLE_JSON = Json { encodeDefaults = true }

        /**
         * Snapshots [config] for a production attempt; throws [InvalidConfig] when any control would be disabled. The role
         * texts are frozen with it: by default every declared role's text version as [config] words it (D-38).
         */
        @JvmStatic
        public fun freeze(config: Config, harnessVersion: String = Astrolabe.VERSION, roleTextVersions: Map<String, String> = RoleTexts.versions(config)): AttemptConfig {
            val attempt = AttemptConfig(harnessVersion, config, roleTextVersions, scratch = ScratchPolicy.BUILT_IN)
            val violations = attempt.productionViolations()
            if (violations.isNotEmpty()) throw InvalidConfig(violations)
            return attempt
        }

        /**
         * A counterfactual research arm (evaluation module only): representable, runnable by the evaluator,
         * never production and never promotion-eligible (D-48).
         */
        @JvmStatic
        public fun researchArm(config: Config, controls: Controls, harnessVersion: String = Astrolabe.VERSION, roleTextVersions: Map<String, String> = emptyMap()): AttemptConfig =
            AttemptConfig(harnessVersion, config, roleTextVersions, controls, production = false, scratch = ScratchPolicy.BUILT_IN)
    }
}

@Serializable
@SerialName("io.astrolabe.AttemptConfig")
private data class AttemptConfigSnapshot(
    val harnessVersion: String,
    val config: Config,
    val roleTextVersions: Map<String, String>,
    val controls: Controls = Controls.ALL,
    val production: Boolean = true,
    /** Not encoded when absent, so an attempt frozen before W3 keeps its body and its fingerprint. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scratch: ScratchPolicy? = null,
)

public class InvalidConfig(public val violations: List<ConfigViolation>) :
    IllegalArgumentException("invalid configuration: " + violations.joinToString("; "))
