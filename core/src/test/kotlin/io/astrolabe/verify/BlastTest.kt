package io.astrolabe.verify

import io.astrolabe.atlas.Impact
import io.astrolabe.atlas.ImpactFile
import io.astrolabe.atlas.ImpactGraph
import io.astrolabe.atlas.ImpactImport
import io.astrolabe.atlas.ImpactRequest
import io.astrolabe.atlas.ImpactScope
import io.astrolabe.atlas.IndexTier
import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.id.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** P3.2.5: blast radius selection (§7.3) — exact tests on a complete graph, package or workspace suite when widened. */
class BlastTest {
    private val ws = WorkspaceId("main")
    private val pay = ImpactScope(ws, "pay")
    private val ledger = ImpactScope(ws, "ledger")
    private val router = ImpactFile(pay, "pay/router.py")
    private val api = ImpactFile(pay, "pay/api.py")
    private val testApi = ImpactFile(pay, "pay/tests/test_api.py")
    private val book = ImpactFile(ledger, "ledger/book.py")
    private val pytest = Command(listOf("python", "-m", "pytest", "-q"))
    private val testsFor: (Set<ImpactFile>) -> Set<ImpactFile> = { files -> if (api in files) setOf(testApi) else emptySet() }
    private val dirs: (ImpactScope) -> String? = { it.packageId }

    private fun analysis(tier: IndexTier, complete: Boolean, vararg edits: ImpactFile) = Impact.analyze(
        ImpactRequest(
            ImpactGraph(
                "v1", "test", setOf(router, api, testApi, book),
                setOf(ImpactImport(api, router), ImpactImport(testApi, api)),
                tier, complete, setOf(pay, ledger), emptySet(),
            ),
            edits.toSet(), null, emptyList(), emptyList(), contractsComplete = true,
        ),
    )

    @Test
    fun `a complete graph selects the blast's tests and the verify line counts the files under test`() {
        val selected = assertIs<BlastSelection.Selected>(Blast.select(analysis(IndexTier.Syntax, true, router), pytest, testsFor, dirs))
        assertEquals(listOf("python", "-m", "pytest", "-q", "pay/tests/test_api.py"), selected.check.command!!.argv)
        assertEquals(Closure.Known(sortedSetOf("pay/api.py", "pay/router.py", "pay/tests/test_api.py")), selected.check.inputClosure)
        assertEquals("blast 3", Blast.scope(selected.check))
        assertEquals(Checks.TESTS_BLAST, selected.check.id)

        val none = assertIs<BlastSelection.NotSelected>(Blast.select(analysis(IndexTier.Syntax, true, book), pytest, testsFor, dirs))
        assertEquals("blast radius: no tests select 1 files", none.reason)
    }

    @Test
    fun `an incomplete graph widens to the package suite, several packages to the workspace, and a suite-only runner never gets paths`() {
        val widened = assertIs<BlastSelection.Selected>(Blast.select(analysis(IndexTier.Lexical, true, router), pytest, testsFor, dirs))
        assertEquals(Command(pytest.argv, "pay"), widened.check.command)
        assertEquals("package pay", Blast.scope(widened.check))

        val two = assertIs<BlastSelection.Selected>(Blast.select(analysis(IndexTier.Lexical, false, router, book), pytest, testsFor, dirs))
        assertEquals(pytest, two.check.command)
        assertEquals("workspace", Blast.scope(two.check))

        val gradle = Command(listOf("./gradlew", "test"))
        val suite = assertIs<BlastSelection.Selected>(Blast.select(analysis(IndexTier.Syntax, true, router), gradle, testsFor, dirs))
        assertEquals(Command(gradle.argv, "pay"), suite.check.command)
        assertEquals("package pay", Blast.scope(suite.check))
    }
}
