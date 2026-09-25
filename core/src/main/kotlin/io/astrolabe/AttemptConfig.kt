package io.astrolabe

import io.astrolabe.cell.RoleTexts
import io.astrolabe.id.Digest
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
 * versions and controls. Mid-attempt configuration changes have no effect until the next attempt (P1.9.4).
 * [fingerprint] enters compile and attempt fingerprints (D-38).
 */
@Serializable
public data class AttemptConfig(
    val harnessVersion: String,
    val config: Config,
    val roleTextVersions: Map<String, String>,
    val controls: Controls = Controls.ALL,
    /** False only for evaluation research arms; such attempts are ineligible for promotion. */
    val production: Boolean = true,
) {
    @Transient
    val fingerprint: Digest = Digest.ofUtf8(STABLE_JSON.encodeToString(serializer(), this))

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
            val attempt = AttemptConfig(harnessVersion, config, roleTextVersions)
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
            AttemptConfig(harnessVersion, config, roleTextVersions, controls, production = false)
    }
}

public class InvalidConfig(public val violations: List<ConfigViolation>) :
    IllegalArgumentException("invalid configuration: " + violations.joinToString("; "))
