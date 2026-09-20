package io.astrolabe

import io.astrolabe.fixtures.FakeProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AttemptConfigTest {
    private val config = Config(profiles = FakeProfiles.all, profileRoles = ProfileRoles("main", "helper", "escalation"))

    @Test
    fun `a valid production config freezes with a stable fingerprint`() {
        val a = AttemptConfig.freeze(config, roleTextVersions = mapOf("implementing" to "1"))
        val b = AttemptConfig.freeze(config, roleTextVersions = mapOf("implementing" to "1"))
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(a.production)
        assertTrue(a.productionViolations().isEmpty())
        val c = AttemptConfig.freeze(config.copy(flags = Flags(precompile = true)))
        assertNotEquals(a.fingerprint, c.fingerprint)
    }

    @Test
    fun `reserve-off and other control-disabling values are rejected for production (IX-18)`() {
        val reserveOff = config.copy(defaults = config.defaults.copy(reserveVerification = 0.0))
        val error = assertFailsWith<InvalidConfig> { AttemptConfig.freeze(reserveOff) }
        assertTrue(error.violations.any { it.field == "reserveVerification" }, error.message)

        val noChecker = config.copy(defaults = config.defaults.copy(checkerTimeBoxSeconds = 0))
        assertFailsWith<InvalidConfig> { AttemptConfig.freeze(noChecker) }

        val noAttempts = config.copy(defaults = config.defaults.copy(attemptsPerIncrement = 0))
        assertFailsWith<InvalidConfig> { AttemptConfig.freeze(noAttempts) }

        val badProfiles = config.copy(profileRoles = ProfileRoles(main = "missing"))
        val e2 = assertFailsWith<InvalidConfig> { AttemptConfig.freeze(badProfiles) }
        assertTrue(e2.violations.any { it.field == "profileRoles.main" })
    }

    @Test
    fun `a research arm can represent disabled controls but is never production-eligible`() {
        val arm = AttemptConfig.researchArm(config, Controls(reserve = false, testIntegrityGuard = false))
        assertFalse(arm.production)
        assertFalse(arm.controls.allEnabled)
        val violations = arm.productionViolations().map { it.field }
        assertTrue("controls.reserve" in violations && "controls.testIntegrityGuard" in violations && "production" in violations, violations.toString())
    }
}
