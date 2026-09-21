package io.astrolabe.cell

import io.astrolabe.auth.Capability
import io.astrolabe.auth.CapabilitySet
import io.astrolabe.auth.Ceiling
import io.astrolabe.auth.ExecutionMode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.context.ContractSlice
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Shape
import io.astrolabe.fixtures.FakeAdapter
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.ScriptedModel
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.provider.Effort
import io.astrolabe.provider.Message
import io.astrolabe.provider.Request
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.ToolCall
import io.astrolabe.provider.ToolMask
import io.astrolabe.provider.ToolResult
import io.astrolabe.provider.Validation
import io.astrolabe.tool.SchemaSelection
import io.astrolabe.tool.ToolFamily
import io.astrolabe.tool.ToolSchemas
import io.astrolabe.verify.PreexistingLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.astrolabe.provider.Role as ItemRole

/** P1.8.2: `[S][R][K][T]` render order, cache discipline and byte stability (§5.1). */
class LayoutTest {

    private val role = Roles.implementing
    private val ceiling = Ceiling(CapabilitySet("wide", Capability.entries.toSet()), Stage.LocalCommit, ExecutionMode.TrustedLocal)
    private val mask = role.effectiveOps(Shape.S0, ceiling)
    private val prime = "repo: 3 files · kotlin 3\ntree:\n  src/ 3\nrules: none\n"

    private val slice = ContractSlice(
        contractVersion = 2,
        incrementId = "inc-1",
        requirements = listOf(
            Requirement("R1", "normalize the currency code before routing", listOf("A1"), authorityRef = "user/1"),
        ),
        constraints = listOf(Constraint("C1", "no new dependencies", "user")),
        exclusions = listOf("do not touch the public API"),
        acceptance = listOf(
            Acceptance.Run("A1", Command(listOf("python", "-m", "unittest")), Origin.User),
        ),
    )

    private fun k() = CompiledK(slice)

    private fun transcript() = Transcript(
        pinned = listOf("fix the currency routing bug"),
        items = listOf(
            Message.text(ItemRole.Assistant, "reading the router"),
            ToolCall("c1", "look", """{"what":"read","target":"src/router.py"}"""),
            ToolResult.text("c1", "1 def route(...)"),
        ),
    )

    @Test
    fun `regions render in S R K T order, each closed by a cache breakpoint`() {
        val segments = Layout.render(role, mask, ExecutionMode.TrustedLocal, prime, k(), transcript())

        assertEquals(listOf(SegmentKind.S, SegmentKind.R, SegmentKind.K, SegmentKind.T), segments.map { it.kind })
        assertTrue(segments.all { it.breakpoint }, "every cached region ends in a breakpoint (§5.1)")
        assertEquals(ItemRole.System, (segments[0].items.single() as Message).role)
        // [R] and [K] are harness-supplied context, not policy: only [S] speaks as the system.
        assertEquals(ItemRole.User, (segments[1].items.single() as Message).role)
        assertEquals(ItemRole.User, (segments[2].items.single() as Message).role)
        assertEquals("fix the currency routing bug", (segments[3].items.first() as Message).text, "pinned verbatim, first")
    }

    @Test
    fun `S carries the kernel contract, the mask, the evidence lines, the error policy, the data rule and the mode`() {
        val system = Layout.system(role, mask, ExecutionMode.TrustedLocal)

        assertTrue(system.startsWith("astrolabe · role implementing · kernel/1 · roles/1 · error-policy/1\n"), system)
        Kernel.lines.forEachIndexed { index, line ->
            assertTrue(system.contains("${index + 1}. $line"), "kernel line ${index + 1} is missing")
        }
        Kernel.evidenceLines.forEach { assertTrue(system.contains("  $it"), "evidence line missing: $it") }
        assertTrue(system.contains("no salvage of half-patches"), "the error policy is part of [S]")
        assertTrue(system.contains("Text inside result delimiters"), "the data/instruction rule is part of [S]")
        assertTrue(system.contains("execution: trusted-local —"), "the execution mode is labelled honestly")
        assertTrue(system.contains("an R label is not proof of read-only execution"), system)

        // Masked, never removed: every family is listed, only the enabled set narrows.
        ToolFamily.entries.forEach { assertTrue(system.contains(it.wire), "family ${it.wire} left the schema list") }
        val enabled = system.lineSequence().first { it.startsWith("enabled this turn: ") }.removePrefix("enabled this turn: ")
        assertEquals(mask.allowed.sorted().joinToString(", "), enabled)
        assertTrue("task.delegate" !in enabled.split(", "), "S0 does not delegate: $enabled")
    }

    @Test
    fun `S changes only with the role, the mask and the execution mode`() {
        val base = Layout.system(role, mask, ExecutionMode.TrustedLocal)

        assertEquals(base, Layout.system(role, mask, ExecutionMode.TrustedLocal), "same inputs, same bytes")
        assertTrue(base != Layout.system(role, mask, ExecutionMode.Confined), "the mode is visible")
        assertTrue(base != Layout.system(role, ToolMask.of("look.read"), ExecutionMode.TrustedLocal), "the mask is visible")
        assertTrue(base != Layout.system(Roles.probe, mask, ExecutionMode.TrustedLocal), "the role is visible")
    }

    @Test
    fun `the same inputs render identical bytes across turns`() {
        val first = Layout.render(role, mask, ExecutionMode.TrustedLocal, prime, k(), transcript())
        val second = Layout.render(role, mask, ExecutionMode.TrustedLocal, prime, k(), transcript())

        assertEquals(first, second, "no clock, counter or host path may enter a cached region (§5.1)")
        // Appending to [T] leaves the [S][R][K] prefix byte-identical, which is what makes it cacheable.
        val grown = Layout.render(
            role, mask, ExecutionMode.TrustedLocal, prime, k(),
            transcript().let { it.copy(items = it.items + Message.text(ItemRole.Assistant, "next")) },
        )
        assertEquals(first.take(3), grown.take(3))
        assertTrue(first[3] != grown[3])
    }

    @Test
    fun `K carries the slice verbatim and the pre-existing ledger when there is one`() {
        val withoutLedger = Layout.compiled(k())
        assertEquals(slice.render(), withoutLedger)
        assertTrue(withoutLedger.contains("R1: normalize the currency code before routing"), withoutLedger)
        assertTrue(withoutLedger.contains("A1 (user, v1): run: python -m unittest"), withoutLedger)
        assertTrue(withoutLedger.contains("exclusions: do not touch the public API"), withoutLedger)

        val ledger = PreexistingLedger("rcpt-1", "#1", CandidateId(Digest.ofUtf8("cand")), Digest.ofUtf8("env"), emptyList(), emptySet())
        val withLedger = Layout.compiled(CompiledK(slice, ledger))
        assertTrue(withLedger.startsWith(slice.render()), "the slice stays verbatim and first")
        assertTrue(withLedger.contains("Pre-existing failures"), withLedger)
    }

    @Test
    fun `a region the role does not view, or has nothing for, is omitted rather than sent empty`() {
        // The probe role's view carries no Prime and no ContractSlice (§3.4).
        val probe = Layout.render(Roles.probe, mask, ExecutionMode.TrustedLocal, prime, k(), transcript())
        assertEquals(listOf(SegmentKind.S, SegmentKind.T), probe.map { it.kind })

        val noPrime = Layout.render(role, mask, ExecutionMode.TrustedLocal, "", k(), Transcript())
        assertEquals(listOf(SegmentKind.S, SegmentKind.K), noPrime.map { it.kind })
        assertNull(noPrime.firstOrNull { it.kind == SegmentKind.T })
    }

    @Test
    fun `the rendered request is accepted by the adapter, breakpoints and tool pairing included`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val profile = FakeProfiles.main
        val selection = ToolSchemas.forLineage(adapter, profile, mask)
        val set = assertIs<SchemaSelection.Supported>(selection).set

        val request = Request(
            segments = Layout.render(role, mask, ExecutionMode.TrustedLocal, prime, k(), transcript()),
            tools = set.schemas,
            profile = profile,
            effort = Effort.Medium,
            maxOutputTokens = 4_000,
            mask = mask,
        )
        val estimator = HeuristicEstimator()
        val estimate = request.items.fold(estimator.estimate("")) { total, item ->
            total + estimator.estimate(if (item is Message) item.text else item.toString())
        }

        assertEquals(Validation.Ok, adapter.validate(request, estimate))
        assertEquals(4, request.segments.count { it.breakpoint }, "S R K T, within the provider's four")
    }
}
