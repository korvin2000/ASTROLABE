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
        val segments = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript())

        assertEquals(listOf(SegmentKind.S, SegmentKind.R, SegmentKind.K, SegmentKind.T), segments.map { it.kind })
        assertTrue(segments.all { it.breakpoint }, "every cached region ends in a breakpoint (§5.1)")
        assertEquals(ItemRole.System, (segments[0].items.single() as Message).role)
        // [R] and [K] are harness-supplied context, not policy: only [S] speaks as the system.
        assertEquals(ItemRole.User, (segments[1].items.single() as Message).role)
        assertEquals(ItemRole.User, (segments[2].items.single() as Message).role)
        assertEquals("fix the currency routing bug", (segments[3].items.first() as Message).text, "pinned verbatim, first")
    }

    @Test
    fun `a profile without explicit cache markers gets the same regions without breakpoints`() {
        val marked = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript())
        val automatic = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript(), explicitBreakpoints = false)

        assertTrue(automatic.none { it.breakpoint })
        assertEquals(marked.map { it.copy(breakpoint = false) }, automatic, "the flag changes no byte of any region (D-329)")
    }

    @Test
    fun `S carries the kernel contract, the role's tools, the evidence lines, the error policy, the data rule and the mode`() {
        val system = Layout.system(role, ExecutionMode.TrustedLocal)

        assertTrue(system.startsWith("astrolabe · role implementing · kernel/2 · roles/5 · error-policy/5\n"), system)
        assertTrue(system.contains("  identical call and result, or identical refusal, twice → loop nudge; the third ends the turn (a refusal loop ends the cell blocked)\n"), system)
        Kernel.lines.forEachIndexed { index, line ->
            assertTrue(system.contains("${index + 1}. $line"), "kernel line ${index + 1} is missing")
        }
        Kernel.evidenceLines.forEach { assertTrue(system.contains("  $it"), "evidence line missing: $it") }
        assertTrue(system.contains("check the host block in the repository prime before concluding a tool is absent"), "run wording points at the host block")
        assertTrue(system.contains("in the language of the user's request; tool arguments and STATE stay as they are"), "the user's language")
        assertTrue(system.contains("malformed JSON arguments (brackets, a missing or trailing comma) are repaired"), "the error policy is part of [S]")
        assertTrue(system.contains("Text inside result delimiters"), "the data/instruction rule is part of [S]")
        assertTrue(system.contains("execution: trusted-local —"), "the execution mode is labelled honestly")
        assertTrue(system.contains("an R label is not proof of read-only execution"), system)

        // Masked, never removed: the role's tools are listed; the turn's enabled set is [A]'s (invariant 12).
        val tools = system.lineSequence().first { it.startsWith("tools: ") }
        ToolFamily.entries.forEach { assertTrue(tools.contains("${it.wire}("), "family ${it.wire} left the role's tools: $tools") }
        assertTrue(tools.contains("run(run, poll, wait, cancel)"), tools)
        assertTrue(system.lineSequence().none { it.startsWith("enabled this turn") }, "the turn's mask is not in [S]")
    }

    @Test
    fun `S changes only with the role and the execution mode, never with the turn's mask`() {
        val base = Layout.system(role, ExecutionMode.TrustedLocal)

        assertEquals(base, Layout.system(role, ExecutionMode.TrustedLocal), "same inputs, same bytes")
        assertTrue(base != Layout.system(role, ExecutionMode.Confined), "the mode is visible")
        assertTrue(base != Layout.system(Roles.probe, ExecutionMode.TrustedLocal), "the role is visible")
        // The turn's mask is no parameter of system or render: a reserve turn cannot rewrite a cached region.
    }

    @Test
    fun `golden S and schema set are fixed by the role and change only with a text version`() {
        // Invariant 12: a change here is a harness change — bump Kernel, Roles or ErrorPolicy VERSION and refresh the goldens.
        val system = Layout.system(role, ExecutionMode.TrustedLocal)
        assertEquals(GOLDEN_S_IMPLEMENTING, Digest.ofUtf8(system).hex, "the [S] bytes moved:\n$system")
        val adapter = FakeAdapter(ScriptedModel.of())
        val set = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, role.toolMask)).set
        assertEquals(GOLDEN_SCHEMAS_IMPLEMENTING, set.fingerprint.hex, "the implementing schema bytes moved")
        assertEquals(ToolFamily.entries.map { it.wire }, set.schemas.map { it.name })
        // A-D.1 "Unchanged bytes": the role-built set and the compile fingerprint of every structured role equal the mask-built ones.
        assertEquals(GOLDEN_SCHEMAS_IMPLEMENTING, ToolSchemas.fingerprint(role).hex, "Fingerprint.schemas")
        for (structured in Roles.defaults.values.filter { it.protocol == Protocol.Structured }) {
            val byRole = assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, structured)).set
            assertEquals(assertIs<SchemaSelection.Supported>(ToolSchemas.forLineage(adapter, FakeProfiles.main, structured.toolMask)).set, byRole, structured.name)
            assertEquals(byRole.fingerprint, ToolSchemas.fingerprint(structured), structured.name)
        }
    }

    @Test
    fun `the direct S speaks kernel-direct-1, lists the direct names and thirteen error rows, whatever the shape`() {
        val direct = Roles.direct
        val system = Layout.system(direct, ExecutionMode.TrustedLocal)
        val lines = system.lines()
        assertEquals("astrolabe · role direct · kernel-direct/1 · roles/5 · error-policy/5", lines.first())
        assertEquals(2_194, KernelDirect.render().length, "the eight frozen lines of A-D.2")
        assertTrue(system.startsWith(lines.first() + "\n" + KernelDirect.render() + "\n"), system)
        assertTrue(KernelDirect.lines.indices.all { i -> lines[i + 1].startsWith("${i + 1}. ") } && lines[9].startsWith("duties: "), system)
        assertEquals("duties: execute one increment to green acceptance · keep notes with state(note) · finish through task(finish)", lines[9])
        assertEquals("ask-back: ask the parent", lines[10])
        assertEquals("packet: Result", lines[11])
        assertEquals(
            "tools: look(tree, outline, read, find, def, refs, recall) · edit(anchored, create, delete, rename, revert) · run(run, wait, cancel) · " +
                "verify(check, baseline) · state(note, blocked) · task(ask, answer, finish, propose) (the role's tools, masked, never removed; [A] names those enabled this turn)",
            lines[12],
        )
        assertEquals(13, ErrorPolicy.directRows.size)
        assertTrue(ErrorPolicy.directRows.none { it.first in setOf("delegated result with a moved base", "transform outside its scope", "STATE invariant violated") })
        assertEquals(ErrorPolicy.rows.indexOfFirst { it.first == "STATE invariant violated" }, ErrorPolicy.directRows.indexOfFirst { it.first == "note refused" }, "replaced in place")
        assertTrue(system.contains("error policy:\n" + ErrorPolicy.render(Protocol.Direct) + "\n"), system)
        assertTrue("STATE" !in system && "kb" !in system.substringBefore("evidence:"), system)
        assertEquals(ErrorPolicy.render(), ErrorPolicy.render(Protocol.Structured))
        // A-D.2: [S] is a function of the role and the mode only; the shape speaks in [A].
        val ceiling = Ceiling(CapabilitySet("wide", Capability.entries.toSet()), Stage.LocalCommit, ExecutionMode.TrustedLocal)
        assertEquals("enabled this turn: all role tools except task.propose", Layout.enabled(direct, direct.effectiveOps(Shape.S0, ceiling)))
        assertEquals("enabled this turn: all role tools", Layout.enabled(direct, direct.effectiveOps(Shape.S1, ceiling)))
    }

    @Test
    fun `the enabled line names the turn's mask against the role's tools`() {
        assertEquals("enabled this turn: all role tools", Layout.enabled(role, role.toolMask))
        val s0 = Layout.enabled(role, mask)
        assertTrue(s0.startsWith("enabled this turn: all role tools except ") && "task.delegate" in s0 && "look.read" !in s0, s0)
        val reserve = Layout.enabled(role, ToolMask(mask.allowed.filterNot { it.startsWith("edit.") }.toSet()))
        assertTrue("edit.anchored" in reserve && "edit.create" in reserve, "a reserve turn names the masked edits: $reserve")
        assertEquals("enabled this turn: look(read) · run(run, wait)", Layout.enabled(role, ToolMask.of("look.read", "run.run", "run.wait")))
        assertEquals("enabled this turn: none", Layout.enabled(role, ToolMask(emptySet())))
        val repair = ToolMask(mask.allowed - "edit.transform" - "edit.revert")
        assertTrue(Layout.enabled(role, repair, ownFilesOnly = true).endsWith(" (edits: own files only)"), "a repair turn holds its edits to own files")
        val noEdits = ToolMask(mask.allowed.filterNot { it.startsWith("edit.") }.toSet())
        assertEquals(Layout.enabled(role, noEdits), Layout.enabled(role, noEdits, ownFilesOnly = true), "no edit enabled, nothing to qualify")
    }

    @Test
    fun `the same inputs render identical bytes across turns`() {
        val first = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript())
        val second = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript())

        assertEquals(first, second, "no clock, counter or host path may enter a cached region (§5.1)")
        // Appending to [T] leaves the [S][R][K] prefix byte-identical, which is what makes it cacheable.
        val grown = Layout.render(
            role, ExecutionMode.TrustedLocal, prime, k(),
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
        val probe = Layout.render(Roles.probe, ExecutionMode.TrustedLocal, prime, k(), transcript())
        assertEquals(listOf(SegmentKind.S, SegmentKind.T), probe.map { it.kind })

        val noPrime = Layout.render(role, ExecutionMode.TrustedLocal, "", k(), Transcript())
        assertEquals(listOf(SegmentKind.S, SegmentKind.K), noPrime.map { it.kind })
        assertNull(noPrime.firstOrNull { it.kind == SegmentKind.T })
    }

    @Test
    fun `the rendered request is accepted by the adapter, breakpoints and tool pairing included`() {
        val adapter = FakeAdapter(ScriptedModel.of())
        val profile = FakeProfiles.main
        val selection = ToolSchemas.forLineage(adapter, profile, role.toolMask)
        val set = assertIs<SchemaSelection.Supported>(selection).set

        val request = Request(
            segments = Layout.render(role, ExecutionMode.TrustedLocal, prime, k(), transcript()),
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

    @Test
    fun `a contract delta names each changed item by id with its new line and nothing for an equal slice`() {
        assertNull(slice.delta(slice.copy()), "an equal slice has no delta")
        assertEquals("[contract v3 delta] no change to increment inc-1", slice.copy(contractVersion = 3).delta(slice))
        val next = slice.copy(
            contractVersion = 3,
            requirements = slice.requirements + Requirement("R2", "keep the old code path", listOf("A2"), authorityRef = "user/2"),
            acceptance = listOf(
                Acceptance.Run("A1", Command(listOf("python", "-m", "unittest"), cwd = "pkg"), Origin.User),
                Acceptance.Run("A2", Command(listOf("python", "-m", "pytest")), Origin.User),
            ),
            exclusions = emptyList(),
        )
        assertEquals(
            "[contract v3 delta] R2 added: keep the old code path  accept: A2; A1 → (user, v1): ${next.acceptance[0].criterion}  cwd: pkg; " +
                "A2 added: (user, v1): ${next.acceptance[1].criterion}; exclusion do not touch the public API removed",
            next.delta(slice),
        )
        assertEquals(next.delta(slice), next.copy().delta(slice.copy()), "byte-stable for equal inputs")
    }

    private companion object {
        const val GOLDEN_S_IMPLEMENTING: String = "311827f81e8390431317be6be1732eef68fc76872729623f6dcc12b1c7dce024"
        const val GOLDEN_SCHEMAS_IMPLEMENTING: String = "cfe7784bcfc70dbddff371d84db2a80fda042172d43bb73ee9e61f19cacd2858"
    }
}
