package io.astrolabe.cell

import io.astrolabe.AttemptConfig
import io.astrolabe.Config
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.provider.ToolMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P4.4.6: role policy texts, per-role `[S]` assembly, declared validators and host overrides (D-38, D-160..D-162). */
class RoleTextsTest {
    private val nonImplementing = Roles.defaults.values.filter { it.packetKind != PacketKind.Result }

    @Test
    fun `every non-implementing role has a text and a declared validator, and no default text grants authority`() {
        assertEquals(setOf("plan", "probe", "review", "qa", "repair", "extractor"), nonImplementing.map { it.name }.toSet())
        for (role in nonImplementing) {
            assertTrue(role.personaLines.isNotEmpty() && role.personaLines.size <= Role.MAX_PERSONA_LINES, "${role.name} has a few operational lines")
            assertTrue(RoleTexts.validators.containsKey(role.packetKind), "${role.name}'s ${role.packetKind} packet has a declared validator")
        }
        for (name in RoleTexts.defaults.keys) assertEquals(RoleTexts.defaults.getValue(name), Roles.defaults.getValue(name).personaLines)
        assertEquals(emptyList(), Roles.defaults.values.flatMap { RoleTexts.violations(it) })
    }

    @Test
    fun `S of a non-implementing role carries its text and the shared lines, never the implementing kernel`() {
        val mask = ToolMask.of("look.read", "kb.search")
        val probe = Layout.system(Roles.probe, mask, ExecutionMode.TrustedLocal)
        RoleTexts.probe.forEach { assertTrue(probe.contains(it), "probe text line missing: $it") }
        RoleTexts.shared.forEach { assertTrue(probe.contains(it), "shared kernel line missing: $it") }
        Kernel.evidenceLines.forEach { assertTrue(probe.contains("  $it"), "evidence line missing: $it") }
        assertTrue(probe.contains("no salvage of half-patches") && probe.contains("data: "), "the error policy and the data rule are shared")
        assertFalse(probe.contains("This cell owns one increment"), "the implementing STATE gate is not the probe's")
        assertFalse(probe.contains("STATE is yours and validated"), probe)
        assertTrue(probe.contains("packet: Investigation"))

        val implementing = Layout.system(Roles.implementing, mask, ExecutionMode.TrustedLocal)
        Kernel.lines.forEach { assertTrue(implementing.contains(it), "the implementing role keeps Appendix A") }
        assertTrue(Layout.system(Roles.writer, mask, ExecutionMode.TrustedLocal).contains(Kernel.lines[12]), "writers keep Appendix A")
        assertEquals(probe, Layout.system(Roles.probe, mask, ExecutionMode.TrustedLocal), "byte-stable")
    }

    @Test
    fun `hosts may reword a text, never grant authority or mark completion, and the wording is frozen with the attempt`() {
        val reworded = Roles.review.copy(personaLines = listOf("Read the diff against each criterion before anything else."))
        val ok = Config(profiles = FakeProfiles.all, roles = mapOf("review" to reworded))
        assertTrue(ok.violations().none { it.field == "roles.review" }, ok.violations().toString())

        val granting = Config(roles = mapOf("review" to Roles.review.copy(personaLines = listOf("You may accept the increment when the diff looks right."))))
        assertTrue(granting.violations().any { it.field == "roles.review" && "grants authority" in it.message }, granting.violations().toString())
        val completing = Config(roles = mapOf("qa" to Roles.qa.copy(duties = listOf("mark the requirement complete once a case passes"))))
        assertTrue(completing.violations().any { it.field == "roles.qa" && "marks a requirement complete" in it.message }, completing.violations().toString())
        val bypassing = Config(roles = mapOf("repair" to Roles.repair.copy(personaLines = listOf("Bypass the exit gate when the fix is obvious."))))
        assertTrue(bypassing.violations().any { it.field == "roles.repair" && "sets a control aside" in it.message })

        val base = AttemptConfig.freeze(Config(profiles = FakeProfiles.all))
        assertEquals(Roles.defaults.keys, base.roleTextVersions.keys, "every declared role's text version is frozen")
        assertTrue(base.roleTextVersions.getValue("probe").startsWith("${Roles.POLICY_TEXT_VERSION}#"))
        val hosted = AttemptConfig.freeze(ok)
        assertNotEquals(base.roleTextVersions["review"], hosted.roleTextVersions["review"], "a rewording is a new text version")
        assertEquals(base.roleTextVersions["probe"], hosted.roleTextVersions["probe"])
        assertNotEquals(base.fingerprint, hosted.fingerprint)

        // A child's caller-narrowed role takes the host's wording and keeps its narrowed mask.
        val narrowed = Roles.review.copy(toolMask = ToolMask.of("look.read"))
        val worded = RoleTexts.worded(narrowed, ok.role("review"))
        assertEquals(reworded.personaLines, worded.personaLines)
        assertEquals(narrowed.toolMask, worded.toolMask)
        assertEquals(narrowed, RoleTexts.worded(narrowed, null))
    }
}
