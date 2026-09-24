package io.astrolabe.context

import io.astrolabe.Config
import io.astrolabe.atlas.Atlas
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.budget.Tokens
import io.astrolabe.campaign.ShapeSelector
import io.astrolabe.cell.Roles
import io.astrolabe.contract.Contract
import io.astrolabe.contract.Contracts
import io.astrolabe.contract.InMemoryContractRepository
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** P1.9.3 `Compiler` S0 form: mandatory `[K]` only, sized against the profile window with the next request's reserves. */
class CompilerTest {
    private fun contract(): Contract = TempRepo.create().use { repo ->
        repo.write("Makefile", "test:\n\techo ok\n")
        repo.commit("initial")
        Contracts(InMemoryContractRepository(), FixedIdGen(), FakeClock.at("2026-09-24T10:00:00Z"))
            .deriveS0(WorkId("W-1"), AttemptId("a1"), "make a return 10", Atlas.build(repo.root), Config(), Tokens(200_000)).contract
    }

    @Test
    fun `the mandatory slice fits the main profile and the arithmetic is recorded`() {
        val contract = contract()
        val compiled = Compiler(HeuristicEstimator()).compile(ShapeSelector.single(contract).increments.single(), contract, FakeProfiles.main, Roles.implementing, "repo: 1 files\n")
        val ready = assertIs<Compiled.Ready>(compiled)
        assertTrue("AC-1" in ready.k.slice.render())
        val arithmetic = ready.selection.arithmetic
        assertTrue(arithmetic.availableTokens!! >= arithmetic.selectedTokens && arithmetic.selectedTokens.signum() > 0)
        assertEquals(setOf(Compiler.MANDATORY), ready.selection.selectedIds)
    }

    @Test
    fun `a window too small for the mandatory part asks for rescoping instead of dropping it`() {
        val contract = contract()
        val main = FakeProfiles.main
        val tiny = main.copy(capabilities = main.capabilities.copy(contextLimitTokens = main.capabilities.outputLimitTokens + 3_000))
        val compiled = Compiler(HeuristicEstimator()).compile(ShapeSelector.single(contract).increments.single(), contract, tiny, Roles.implementing, "repo: 1 files\n")
        val rescoping = assertIs<Compiled.NeedsRescoping>(compiled)
        assertTrue("split the increment or use a larger profile" in rescoping.reason, rescoping.reason)
        assertEquals(setOf(Compiler.MANDATORY), rescoping.selection.mandatoryIds)
    }
}
