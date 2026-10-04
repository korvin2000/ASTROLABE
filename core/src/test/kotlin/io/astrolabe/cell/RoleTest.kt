package io.astrolabe.cell

import io.astrolabe.Config
import io.astrolabe.DClassPolicy
import io.astrolabe.auth.Capability
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Shape
import io.astrolabe.provider.ToolMask
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolOps
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.8.1: roles are declared configurations; effective ops = role mask ∩ shape mask ∩ authorization; overrides change wording only (D-38). */
class RoleTest {
    private val authorization = Authorization(Stage.LocalCommit, DClassPolicy.Ask, "workspace-local-test-only")

    @Test
    fun `the declared table has every role of §3_4 with its mask and packet`() {
        assertEquals(setOf("implementing", "plan", "probe", "review", "qa", "writer", "repair", "extractor", "direct"), Roles.defaults.keys)
        assertTrue(Roles.implementing.toolMask.allows("edit.anchored") && Roles.implementing.toolMask.allows("run.run"))
        assertTrue(Roles.implementing.toolMask.allows("task.delegate") && Roles.implementing.toolMask.allows("task.collect"), "delegation is unmasked per role (P4.4.1); the shape mask and the Delegator bound it")
        assertFalse(Roles.writer.toolMask.allows("task.delegate") || Roles.writer.toolMask.allows("task.collect"), "a writer is a leaf: writers depth 1")
        assertTrue(Roles.plan.toolMask.allows("task.delegate") && Roles.plan.toolMask.allows("task.collect"))
        assertTrue(Roles.plan.toolMask.allows("run.run") && Roles.plan.toolMask.allows("run.poll") && Roles.plan.toolMask.allows("run.cancel") && !Roles.plan.toolMask.allows("edit.anchored"))
        assertTrue("read-only: R-class runs only" in Roles.plan.duties && "read-only: R-class runs only" in Roles.probe.duties)
        assertTrue(Roles.probe.toolMask.allows("look.read") && Roles.probe.toolMask.allows("run.run") && !Roles.probe.toolMask.allows("edit.anchored"))
        assertTrue(Roles.review.toolMask.allows("verify.tests") && !Roles.review.toolMask.allows("run.run"))
        assertEquals(ToolMask(ToolOps.kb.map { "kb.$it" }.toSet()), Roles.extractor.toolMask)
        assertEquals(PacketKind.Result, Roles.writer.packetKind)
        assertFalse(Roles.writer.toolMask.allows("task.propose"), "a writer never decides interfaces")
        assertTrue(Roles.defaults.values.all { it.personaLines.size <= Role.MAX_PERSONA_LINES && it.duties.isNotEmpty() })
        assertTrue(runCatching { Roles.implementing.copy(personaLines = listOf("a", "b", "c", "d")) }.isFailure)
        assertTrue(runCatching { Roles.implementing.copy(toolMask = ToolMask.of("bash.exec")) }.isFailure, "only the seven families exist")
        val json = Json { encodeDefaults = true }
        assertEquals(Roles.probe, json.decodeFromString(Role.serializer(), json.encodeToString(Role.serializer(), Roles.probe)), "roles are configuration records")
    }

    @Test
    fun `effective ops are the intersection of role mask, shape mask and capability ceiling`() {
        val trusted = Ceiling.of(authorization, ExecutionMode.TrustedLocal)
        val s0 = Roles.implementing.effectiveOps(Shape.S0, trusted)
        assertTrue(s0.allows("edit.anchored") && s0.allows("run.run") && s0.allows("look.read") && s0.allows("state.patch"))
        assertTrue(s0.allows("edit.transform"), "transforms are a tool, active in every shape (P3.3.1)")
        assertTrue(s0.allows("look.bmap"), "behaviour maps since P4.3.2")
        assertFalse(s0.allows("task.propose"), "proposals arrive with S1")
        assertTrue(Roles.implementing.effectiveOps(Shape.S1, trusted).allows("task.propose"))
        assertFalse(Roles.implementing.effectiveOps(Shape.S1, trusted).allows("task.delegate"), "S1 has no children: the shape mask bounds the role")
        assertTrue(Roles.implementing.effectiveOps(Shape.S3, trusted).allows("task.delegate") && Roles.implementing.effectiveOps(Shape.S2, trusted).allows("task.collect"))
        assertFalse(Roles.writer.effectiveOps(Shape.S3, trusted).allows("task.delegate"), "the role mask still bounds S3")

        val readOnly = Ceiling(CapabilitySet.WORKSPACE_READ_ONLY, Stage.Patch, ExecutionMode.TrustedLocal)
        val probe = Roles.probe.effectiveOps(Shape.S2, readOnly)
        assertTrue(probe.allows("look.read") && probe.allows("kb.search") && probe.allows("task.ask"))
        assertFalse(probe.allows("run.run"), "the authorization's capability set removes what the role table lists")
        assertEquals(emptySet(), Roles.repair.effectiveOps(Shape.S0, readOnly).allowed.filter { it.startsWith("edit") || it.startsWith("run") }.toSet())
        assertTrue(Roles.plan.effectiveOps(Shape.S0, trusted).allowed.none { it.startsWith("task.delegate") }, "the plan role cannot spawn a probe in S0 merely because its table lists it")
        assertTrue(Roles.plan.effectiveOps(Shape.S2, Ceiling(CapabilitySet("wide", Capability.entries.toSet()), Stage.Patch, ExecutionMode.TrustedLocal)).allows("task.delegate"))
    }

    @Test
    fun `the main line's role is chosen by protocol and shape in one place`() {
        for (shape in Shape.entries) assertEquals(Roles.implementing, Roles.mainLine(Protocol.Structured, shape), "structured in $shape")
        assertEquals(Roles.direct, Roles.mainLine(Protocol.Direct, Shape.S0))
        assertEquals(Roles.direct, Roles.mainLine(Protocol.Direct, Shape.S1))
        assertEquals(Roles.implementing, Roles.mainLine(Protocol.Direct, Shape.S2), "the S2 direct role arrives with H1")
        assertEquals(Roles.implementing, Roles.mainLine(Protocol.Direct, Shape.S3), "the S3 direct role arrives with H1")
    }

    @Test
    fun `the direct role lists 23 operations, every shape mask admits the direct-only ones and structured cells see no change`() {
        val direct = Roles.direct
        assertEquals(Protocol.Direct, direct.protocol)
        assertEquals(23, direct.toolMask.allowed.size)
        assertEquals(listOf("execute one increment to green acceptance", "keep notes with state(note)", "finish through task(finish)"), direct.duties)
        assertEquals(emptyList(), direct.personaLines)
        val implementing = Roles.implementing
        assertEquals(
            listOf(implementing.contextView, implementing.noteScope, implementing.skillFilter, implementing.permission, implementing.tierPrior, implementing.askBack, implementing.policyTextVersion, implementing.packetKind),
            listOf(direct.contextView, direct.noteScope, direct.skillFilter, direct.permission, direct.tierPrior, direct.askBack, direct.policyTextVersion, direct.packetKind),
        )
        val directNames = ToolFamily.entries.flatMap { f -> ToolOps.directOf(f).map { ToolOps.name(f, it) } }.toSet()
        for (role in Roles.defaults.values) {
            if (role.protocol == Protocol.Direct) assertTrue(directNames.containsAll(role.toolMask.allowed), "${role.name} lists a hidden op")
            else assertTrue(ToolOps.all.containsAll(role.toolMask.allowed), "${role.name} lists a direct-only op")
        }
        assertTrue(listOf("kb", "look.impact", "look.bmap", "edit.transform", "run.poll", "verify.tests", "state.patch").none { hidden -> direct.toolMask.allowed.any { it == hidden || it.startsWith("$hidden.") } })

        val wide = Ceiling(CapabilitySet("wide", Capability.entries.toSet()), Stage.LocalCommit, ExecutionMode.TrustedLocal)
        for (shape in Shape.entries) {
            assertTrue(ToolOps.directOnly.all { Roles.shapeMask(shape).allows(it) }, "shape $shape admits note and finish")
            val ops = direct.effectiveOps(shape, wide)
            assertTrue(ops.allows("state.note") && ops.allows("task.finish"), "$shape: ${ops.allowed}")
            assertEquals(shape != Shape.S0, ops.allows("task.propose"), "propose is masked in S0 only")
            // The structured masks of A-D.3 rule 3, before the direct-only names: no structured role's effective ops move.
            val before = when (shape) {
                Shape.S0 -> ToolOps.implementingS0.allowed
                Shape.S1 -> ToolOps.implementingS0.allowed + "task.propose"
                Shape.S2, Shape.S3 -> ToolOps.all
            }
            for (role in Roles.defaults.values.filter { it.protocol == Protocol.Structured }) {
                assertEquals(role.ops.allowed.filter { it in before && wide.allows(it, role.ops) == null }.toSet(), role.effectiveOps(shape, wide).allowed, "${role.name} in $shape")
            }
        }
        assertEquals(
            "task.propose is not enabled in shape S0; available: " + direct.effectiveOps(Shape.S0, wide).allowed.sorted().joinToString(", ") + "; " + Refusals.OTHER_EXIT,
            Refusals.masked("task.propose", direct, Shape.S0, direct.effectiveOps(Shape.S0, wide), null),
        )
    }

    @Test
    fun `the protocol is a configuration field, structured by default and frozen with the attempt`() {
        assertEquals(Protocol.Structured, Config().protocol)
        val json = Json { encodeDefaults = true }
        assertFalse("protocol" in json.encodeToString(Config.serializer(), Config()), "a structured configuration keeps its bytes")
        assertFalse("protocol" in json.encodeToString(Role.serializer(), Roles.implementing), "a structured role keeps its bytes")
        assertEquals(Roles.direct, json.decodeFromString(Role.serializer(), json.encodeToString(Role.serializer(), Roles.direct)))
        val host = Config(profiles = io.astrolabe.fixtures.FakeProfiles.all)
        val frozen = io.astrolabe.AttemptConfig.freeze(host.withProtocol(Protocol.Direct))
        assertEquals(Protocol.Direct, frozen.config.protocol)
        assertEquals(frozen, json.decodeFromString(io.astrolabe.AttemptConfig.serializer(), json.encodeToString(io.astrolabe.AttemptConfig.serializer(), frozen)))
        assertTrue(frozen.fingerprint != io.astrolabe.AttemptConfig.freeze(host).fingerprint, "the protocol enters the attempt fingerprint")
        assertEquals(Roles.implementing, Role(
            Roles.implementing.name, Roles.implementing.contextView, Roles.implementing.noteScope, Roles.implementing.skillFilter, Roles.implementing.toolMask,
            Roles.implementing.permission, Roles.implementing.tierPrior, Roles.implementing.duties, Roles.implementing.askBack, Roles.implementing.packetKind,
            Roles.implementing.personaLines, Roles.implementing.policyTextVersion, Roles.implementing.deniedNoteKinds,
        ), "the full constructor before the protocol stays")
    }

    @Test
    fun `roles load from Config, overrides may reword but never widen authority or change the packet`() {
        assertEquals(Roles.implementing, Config().role("implementing"))
        assertNull(Config().role("oracle"))
        val reworded = Roles.implementing.copy(personaLines = listOf("Prefer small verified steps."), duties = listOf("execute one increment to green acceptance"), toolMask = ToolMask(Roles.implementing.toolMask.allowed - "edit.rename"))
        val ok = Config(roles = mapOf("implementing" to reworded))
        assertTrue(ok.violations().none { it.field.startsWith("roles.") }, ok.violations().toString())
        assertEquals(reworded, ok.role("implementing"))

        val widened = Config(roles = mapOf("writer" to Roles.writer.copy(toolMask = ToolMask(Roles.writer.toolMask.allowed + "task.delegate"))))
        assertTrue(widened.violations().any { it.field == "roles.writer" && it.message.contains("widens the tool mask by [task.delegate]") }, widened.violations().toString())
        val raised = Config(roles = mapOf("probe" to Roles.probe.copy(permission = Stage.Push)))
        assertTrue(raised.violations().any { it.message.contains("raises the permission to Push") }, raised.violations().toString())
        val repacked = Config(roles = mapOf("qa" to Roles.qa.copy(packetKind = PacketKind.Verdict)))
        assertTrue(repacked.violations().any { it.message.contains("changes the output packet") })
        val unknown = Config(roles = mapOf("oracle" to Roles.probe.copy(name = "oracle")))
        assertTrue(unknown.violations().any { it.message.contains("not a declared role") })
        val mismatched = Config(roles = mapOf("probe" to Roles.review))
        assertTrue(mismatched.violations().any { it.message.contains("key differs from role name") })
    }
}
