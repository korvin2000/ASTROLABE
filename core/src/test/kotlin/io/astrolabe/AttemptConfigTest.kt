package io.astrolabe

import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.auth.RedactionConfig
import io.astrolabe.auth.RedactionPattern
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Command
import io.astrolabe.provider.BillingDimension
import io.astrolabe.provider.SchemaDialect
import io.astrolabe.provider.StratumOutcome
import io.astrolabe.provider.ToolMask
import io.astrolabe.route.Tier
import io.astrolabe.route.TierTable
import io.astrolabe.verify.ScratchPolicy
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    @Test
    fun `all attempt construction paths detach nested host collections`() {
        val constructors: List<(Config, Map<String, String>) -> AttemptConfig> = listOf(
            { host, versions -> AttemptConfig.freeze(host, roleTextVersions = versions) },
            { host, versions -> AttemptConfig.researchArm(host, Controls(reserve = false), roleTextVersions = versions) },
            { host, versions -> AttemptConfig("test-harness", host, versions) },
            { host, versions -> AttemptConfig.freeze(config).copy(config = host, roleTextVersions = versions) },
        )
        for ((index, construct) in constructors.withIndex()) {
            val (host, mutateSources) = mutableHost()
            val versions = linkedMapOf("implementing" to "1", "plan" to "2")
            val frozen = construct(host, versions)
            val before = Json.encodeToString(AttemptConfig.serializer(), frozen)
            val fingerprint = frozen.fingerprint

            mutateSources.forEach { it() }
            versions.clear()

            assertEquals(before, Json.encodeToString(AttemptConfig.serializer(), frozen), "constructor $index")
            assertEquals(fingerprint, frozen.fingerprint, "constructor $index")
            assertEquals(fingerprint, Json.decodeFromString(AttemptConfig.serializer(), before).fingerprint)
        }
    }

    @Test
    fun `frozen and deserialized collections reject mutation through exposed references`() {
        val frozen = AttemptConfig.freeze(mutableHost().first, roleTextVersions = mapOf("implementing" to "1"))
        val restored = Json.decodeFromString(AttemptConfig.serializer(), Json.encodeToString(AttemptConfig.serializer(), frozen))
        for (attempt in listOf(frozen, restored)) {
            val c = attempt.config
            val profile = c.profiles.getValue("main")
            val role = c.roles.getValue("implementing")
            val operations: List<() -> Unit> = listOf(
                { (attempt.roleTextVersions as MutableMap).clear() },
                { (c.profiles as MutableMap).clear() },
                { (c.roles as MutableMap).clear() },
                { (profile.priceTable.perMillion as MutableMap).clear() },
                { (profile.capabilities.usageFields as MutableSet).clear() },
                { (profile.capabilities.schemaDialects as MutableSet).clear() },
                { (profile.capabilities.caching.writeClasses as MutableSet).clear() },
                { (profile.stratumOutcomes as MutableList).clear() },
                { (profile.config as Map<String, JsonElement> as MutableMap).clear() },
                { (profile.config.getValue("nested").jsonObject as Map<String, JsonElement> as MutableMap).clear() },
                { (profile.config.entries.first() as MutableMap.MutableEntry).setValue(JsonPrimitive("changed")) },
                { (profile.config.getValue("nested").jsonObject.entries.first() as MutableMap.MutableEntry).setValue(JsonPrimitive("changed")) },
                { (profile.config.getValue("ordered").jsonArray as List<JsonElement> as MutableList).clear() },
                { (c.redaction.envAllowlist as MutableSet).clear() },
                { (c.redaction.patterns as MutableList).clear() },
                { (role.contextView as MutableSet).clear() },
                { (role.noteScope as MutableSet).clear() },
                { (role.skillFilter as MutableSet).clear() },
                { (role.deniedNoteKinds as MutableSet).clear() },
                { (role.toolMask.allowed as MutableSet).clear() },
                { (role.duties as MutableList).clear() },
                { (role.personaLines as MutableList).clear() },
                { (c.qualityGates as MutableList).clear() },
                { (c.qualityGates.first().argv as MutableList).clear() },
                { (c.tierTable.profiles as MutableMap).clear() },
                { (c.tierTable.profiles.getValue(Tier.High) as MutableSet).clear() },
            )
            for ((index, mutate) in operations.withIndex()) {
                val failure = assertFails("exposed collection $index must reject mutation") { mutate() }
                assertTrue(failure is UnsupportedOperationException || failure is ClassCastException, failure.toString())
            }
        }
    }

    @Test
    fun `default roles are captured as immutable attempt inputs`() {
        val frozen = AttemptConfig.freeze(config)
        val role = frozen.config.role("implementing")!!
        assertEquals(Roles.implementing, role)
        assertTrue(role !== Roles.implementing)
        assertFailsWith<UnsupportedOperationException> { (role.duties as MutableList)[0] = "changed" }
    }

    @Test
    fun `equivalent map and set insertion orders have one fingerprint`() {
        val forward = mutableHost().first
        val reverse = mutableHost(reverse = true).first
        assertEquals(forward, reverse, "only unordered collection insertion order differs")
        val a = AttemptConfig.freeze(forward, roleTextVersions = linkedMapOf("implementing" to "1", "plan" to "2"))
        val b = AttemptConfig.freeze(reverse, roleTextVersions = linkedMapOf("plan" to "2", "implementing" to "1"))
        assertEquals(a.fingerprint, b.fingerprint)
        assertEquals(a.fingerprint, Json.decodeFromString(AttemptConfig.serializer(), Json.encodeToString(AttemptConfig.serializer(), b)).fingerprint)
    }

    @Test
    fun `fingerprints retain ordered sequences and actual configuration values`() {
        val host = mutableHost().first
        val profile = host.profiles.getValue("main")
        val original = AttemptConfig.freeze(host).fingerprint
        val reorderedSettings = JsonObject(profile.config + ("ordered" to JsonArray(profile.config.getValue("ordered").jsonArray.reversed())))
        val changedPrices = profile.priceTable.copy(perMillion = profile.priceTable.perMillion + (BillingDimension.OUTPUT to BigDecimal("999")))
        val changed = listOf(
            host.copy(qualityGates = host.qualityGates.reversed()),
            host.copy(qualityGates = listOf(host.qualityGates.first().copy(argv = host.qualityGates.first().argv.reversed())) + host.qualityGates.drop(1)),
            host.copy(redaction = host.redaction.copy(patterns = host.redaction.patterns.reversed())),
            host.copy(roles = host.roles + ("implementing" to host.roles.getValue("implementing").copy(duties = host.roles.getValue("implementing").duties.reversed()))),
            host.copy(profiles = host.profiles + ("main" to profile.copy(config = reorderedSettings))),
            host.copy(profiles = host.profiles + ("main" to profile.copy(priceTable = changedPrices))),
            host.copy(redaction = host.redaction.copy(envAllowlist = host.redaction.envAllowlist + "ADDITIONAL_ENV")),
        )
        changed.forEachIndexed { index, next ->
            assertNotEquals(original, AttemptConfig.freeze(next).fingerprint, "change $index must affect fingerprint")
        }
    }

    /** Every collection has a retained mutable source; reversing affects only maps and sets. */
    private fun mutableHost(reverse: Boolean = false): Pair<Config, List<() -> Unit>> {
        val mutations = mutableListOf<() -> Unit>()
        fun <T> ordered(values: Collection<T>): MutableList<T> = values.toMutableList().also { source -> mutations += { source.clear() } }
        fun <T> unordered(values: Collection<T>): MutableSet<T> =
            LinkedHashSet(if (reverse) values.reversed() else values).also { source -> mutations += { source.clear() } }
        fun <K, V> mapping(values: Map<K, V>): MutableMap<K, V> =
            (if (reverse) values.entries.reversed() else values.entries.toList()).associateTo(linkedMapOf()) { it.toPair() }
                .also { source -> mutations += { source.clear() } }

        val base = FakeProfiles.main
        val profile = base.copy(
            priceTable = base.priceTable.copy(perMillion = mapping(base.priceTable.perMillion)),
            capabilities = base.capabilities.copy(
                usageFields = unordered(base.capabilities.usageFields),
                schemaDialects = unordered(listOf(SchemaDialect.JSON_SCHEMA_2020_12, SchemaDialect.OPENAI_STRICT)),
                caching = base.capabilities.caching.copy(writeClasses = unordered(base.capabilities.caching.writeClasses)),
            ),
            config = JsonObject(mapping(linkedMapOf(
                "nested" to JsonObject(mapping(linkedMapOf("alpha" to JsonPrimitive(1), "beta" to JsonPrimitive(2)))),
                "ordered" to JsonArray(ordered(listOf(JsonPrimitive("first"), JsonPrimitive("second")))),
            ))),
            stratumOutcomes = ordered(listOf(StratumOutcome("a", 2, 1), StratumOutcome("b", 3, 2))),
        )
        val role = Roles.implementing.copy(
            contextView = unordered(Roles.implementing.contextView),
            noteScope = unordered(Roles.implementing.noteScope),
            skillFilter = unordered(listOf("one", "two")),
            deniedNoteKinds = unordered(listOf("CON", "ADR")),
            toolMask = ToolMask(unordered(Roles.implementing.toolMask.allowed)),
            duties = ordered(Roles.implementing.duties),
            personaLines = ordered(listOf("Be precise.", "Check evidence.")),
        )
        val host = config.copy(
            profiles = mapping(linkedMapOf("main" to profile, "helper" to FakeProfiles.helper, "escalation" to FakeProfiles.escalation)),
            roles = mapping(linkedMapOf("implementing" to role, "plan" to Roles.plan)),
            redaction = RedactionConfig(
                patterns = ordered(listOf(RedactionPattern("first", "secret-one"), RedactionPattern("second", "secret-two"))),
                envAllowlist = unordered(listOf("PATH", "HOME")),
            ),
            qualityGates = ordered(listOf(Command(ordered(listOf("check", "--first"))), Command(ordered(listOf("lint", "--second"))))),
            tierTable = TierTable("test", profiles = mapping(linkedMapOf(Tier.High to unordered(listOf("main", "escalation")), Tier.Low to unordered(listOf("helper"))))),
        )
        return host to mutations
    }

    @Test
    fun `a new attempt freezes the built-in output policy, and one frozen before it keeps its body and has none`() {
        val json = Json { encodeDefaults = true }
        val frozen = AttemptConfig.freeze(config)
        assertEquals(ScratchPolicy.BUILT_IN, frozen.scratch)
        val round = json.decodeFromString(AttemptConfig.serializer(), json.encodeToString(AttemptConfig.serializer(), frozen))
        assertEquals(ScratchPolicy.BUILT_IN to frozen.fingerprint, round.scratch to round.fingerprint)

        val legacy = AttemptConfig("test-harness", config, emptyMap())
        val body = json.encodeToString(AttemptConfig.serializer(), legacy)
        assertFalse("\"scratch\"" in body, "an attempt without a policy encodes no field, so its fingerprint is the one before W3")
        val decoded = json.decodeFromString(AttemptConfig.serializer(), body)
        assertEquals(ScratchPolicy.NONE to legacy.fingerprint, decoded.scratch to decoded.fingerprint)
        assertNotEquals(legacy.fingerprint, AttemptConfig.freeze(config, "test-harness", emptyMap()).fingerprint, "the policy enters the fingerprint")
        assertEquals(ScratchPolicy.NONE, legacy.copy(production = false).scratch, "a copy keeps the policy")
    }
}
