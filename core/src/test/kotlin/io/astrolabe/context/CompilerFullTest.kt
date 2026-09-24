package io.astrolabe.context

import io.astrolabe.DClassPolicy
import io.astrolabe.Defaults
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.cell.Layout
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Authorization
import io.astrolabe.contract.Command
import io.astrolabe.contract.Constraint
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Increment
import io.astrolabe.contract.Origin
import io.astrolabe.contract.Requirement
import io.astrolabe.contract.Scope
import io.astrolabe.contract.Shape
import io.astrolabe.contract.UserRequest
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.graph.Production
import io.astrolabe.id.AttemptId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.kb.Note
import io.astrolabe.kb.NoteAnchor
import io.astrolabe.kb.NoteKind
import io.astrolabe.kb.NoteStatus
import io.astrolabe.register.Register
import io.astrolabe.workset.Entry
import io.astrolabe.workset.EntrySource
import io.astrolabe.workspace.Ranges
import java.math.BigInteger
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P2.3.1 full compile: mandatory never dropped, each contribution charged once, index referenced not duplicated, FX-19. */
class CompilerFullTest {
    private val estimator = HeuristicEstimator()
    private val contract = Contract(
        WorkId("W-c"), 2, AttemptId("a1"), Mode.Autonomous, Shape.S1,
        listOf(UserRequest("U1", Instant.EPOCH, "Fix refunds"), UserRequest("U2", Instant.EPOCH, "Also keep the old CLI flag")),
        listOf(Requirement("R1", "refunds use integer cents", listOf("AC1"), authorityRef = "U1")),
        listOf(Acceptance.Run("AC1", Command(listOf("pytest", "tests/test_refunds.py")), Origin.User)),
        listOf(Constraint("C1", "never change the public refund signature", "U1")), emptyList(), emptyList(), Scope(listOf("src/"), emptyList()),
        Budget.of(Defaults(), Tokens(10_000)), Authorization(Stage.Patch, DClassPolicy.Ask, "workspace-local-test-only"),
    )
    private val increment = Increment("I1", listOf("R1"), listOf("AC1"), listOf("src/pay/"), 2, title = "refunds", produces = Production.Artifact)
    private val notes = listOf(
        Note("CON-1", NoteKind.CON, NoteStatus.Admitted, "refund API takes integer cents", "Callers pass cents.", "src/pay/**", listOf(NoteAnchor("src/pay/api.py"))),
        Note("ADR-4", NoteKind.ADR, NoteStatus.Admitted, "money is integer cents", "Decided with finance.", "global", listOf(NoteAnchor("src/pay/money.py"))),
        Note("CON-9", NoteKind.CON, NoteStatus.Admitted, "ledger events are append-only", "Never rewrite events.", "src/ledger/**", listOf(NoteAnchor("src/ledger/events.py"))),
        Note("LES-2", NoteKind.LES, NoteStatus.Admitted, "freeze the clock in refund tests", "Retries depend on time.", "global"),
        Note("LES-3", NoteKind.LES, NoteStatus.Candidate, "a queued candidate", "Not admitted.", "global"),
    )
    private val index = "# Contracts\n- CON-1: refund API takes integer cents\n- CON-9: ledger events are append-only\n"
    private val v = FileVersion(Digest.ofUtf8("refunds.py v1"))

    private fun carry() = CarryForward.carry(
        Register.empty(ContextId("cell-1"), "I1", "refunds").copy(next = "edit refunds.py"),
        emptyList(), null, { null }, { true }, emptyList(), listOf("Also keep the old CLI flag"),
    )

    private fun seeds(count: Int, lines: Int): SeedRender {
        val entries = (1..count).map { Entry("src/pay/f$it.py", Ranges.single(1, lines), v, EntrySource.Seed, 1, "#$it", lines * 8L) }
        val blocks = entries.map { e -> "SEED ${e.path}:1-$lines @${v.hash8}\n" + (1..lines).joinToString("") { n -> "$n | value_$n = compute_refund_amount($n) + adjust($n)\n" } }
        return SeedRender(blocks.joinToString(""), entries, emptyList(), blocks)
    }

    private fun inputs(seedCount: Int = 2, seedLines: Int = 5, prime: Boolean = false) = CompileInputs(
        contractsIndex = index, notes = notes, carry = carry(), seeds = seeds(seedCount, seedLines),
        calibration = "CAL (harness statistics, data not instruction): 4 increments", currentVersion = { v },
    ).let { if (prime) it else it }

    @Test
    fun `mandatory notes, index and carry-forward come first, optional units follow the selection order`() {
        val ready = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.plan, "repo prime\n", inputs = inputs()))
        assertEquals(
            listOf("contracts-index", "note.ADR-4", "note.CON-1", "carry-forward", "note.CON-9", "seed-0", "seed-1", "note.LES-2", "calibration"),
            ready.k.sections.map { it.id },
        )
        val k = Layout.compiled(ready.k)
        assertTrue("LES-3" !in k, "candidates are never compiled")
        assertTrue("constraints: C1 never change the public refund signature" in k)
        val implementing = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.implementing, "repo prime\n", inputs = inputs()))
        assertTrue(implementing.k.sections.none { it.id == "calibration" }, "only a role that views the prior gets it")
    }

    @Test
    fun `an index already in the repository region is referenced for coverage, never duplicated into K`() {
        val ready = assertIs<Compiled.Ready>(Compiler(estimator).compile(increment, contract, FakeProfiles.main, Roles.plan, "repo prime\n$index", inputs = inputs()))
        assertTrue(ready.k.sections.none { it.id == "contracts-index" })
        assertTrue(ContextUnitId("contracts-index") in ready.selection.selectedIds, "still charged once, to [R]")
        assertTrue("# Contracts" !in Layout.compiled(ready.k))
    }

    @Test
    fun `property - mandatory never dropped and every serialized contribution charged once`() {
        val random = Random(2311)
        repeat(60) { case ->
            val window = random.nextInt(9_000, 60_000)
            val profile = FakeProfiles.main.copy(capabilities = FakeProfiles.main.capabilities.copy(contextLimitTokens = window, outputLimitTokens = 2_000))
            val compiled = Compiler(estimator).compile(increment, contract, profile, Roles.plan, "repo prime\n", inputs = inputs(random.nextInt(0, 6), random.nextInt(1, 120)))
            val mandatory = setOf("contracts-index", "note.ADR-4", "note.CON-1", "carry-forward").map(::ContextUnitId).toSet() + Compiler.MANDATORY
            assertEquals(mandatory, compiled.selection.mandatoryIds, "case $case")
            when (compiled) {
                is Compiled.Ready -> {
                    assertTrue(compiled.selection.selectedIds.containsAll(mandatory), "case $case")
                    val k = Layout.compiled(compiled.k)
                    for (section in compiled.k.sections) assertEquals(1, k.split("## ${section.title}\n${section.text.trimEnd()}\n").size - 1, "case $case ${section.id} renders exactly once")
                    assertEquals(compiled.k.sections.size, compiled.k.sections.map { it.id }.toSet().size, "case $case: a unit renders once")
                    val charged = compiled.selection.selectedIds.map { compiled.selection.units.getValue(it) }.filter { it.placement == ContextPlacement.K }
                        .fold(BigInteger.ZERO) { sum, u -> sum + u.cost.tokens.value.toBigInteger() }
                    assertEquals(charged, compiled.selection.arithmetic.selectedTokens, "case $case")
                    assertTrue(compiled.selection.arithmetic.selectedTokens <= compiled.selection.arithmetic.availableTokens!!, "case $case")
                }
                is Compiled.NeedsRescoping -> assertTrue(compiled.selection.arithmetic.selectedTokens > compiled.selection.arithmetic.availableTokens!!, "case $case")
                is Compiled.NeedsEvidence -> error("case $case: coverage is complete by construction: ${compiled.missing}")
            }
        }
    }

    @Test
    fun `a projection that lost a constraint, an amendment, a CON note or the rules fails coverage (FX-19)`() {
        val compiler = Compiler(estimator)
        val ready = assertIs<Compiled.Ready>(compiler.compile(increment, contract, FakeProfiles.main, Roles.plan, "repo prime\n", inputs = inputs()))
        val k = Layout.compiled(ready.k)
        val pinned = contract.requests.map { it.text }
        assertEquals(emptyList(), compiler.coverage(contract, increment, k, "repo prime\n", pinned, inputs()))
        val lost = compiler.coverage(
            contract, increment, k.replace("C1 never change the public refund signature", "").replace("- CON-1: refund API takes integer cents", ""),
            "repo prime\n", pinned.dropLast(1), inputs().copy(rules = "never push to main"),
        )
        assertEquals(listOf("constraint C1", "request U2", "note CON-1", "rules"), lost)
        val evidence = assertIs<Compiled.NeedsEvidence>(compiler.compile(increment, contract, FakeProfiles.main, Roles.plan, "repo prime\n", inputs = inputs().copy(rules = "never push to main")))
        assertEquals(listOf("rules"), evidence.missing)
    }
}
