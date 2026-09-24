package io.astrolabe.atlas

import io.astrolabe.contract.Command
import io.astrolabe.evidence.Closure
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.search.Searches
import io.astrolabe.verify.Check
import io.astrolabe.verify.CheckKind
import io.astrolabe.verify.CostClass
import io.astrolabe.verify.Selector
import io.astrolabe.verify.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3.2.2: `impact(E)` assembled at runtime from the graph, the index, the checks and supplied anchors (§7.4). */
class ImpactAssemblyTest {
    private val workspace = WorkspaceId("main")

    private fun assemblyOf(repo: TempRepo): ImpactAssembly {
        val atlas = Atlas.build(repo.root)
        return ImpactAssembly(ImportGraph.of(atlas, workspace), SymbolIndex(atlas, Searches.jvm()))
    }

    private fun check(id: String, closure: Closure, cwd: String? = null) = Check(
        id, CheckKind.Acceptance, Selector.Named(Command(listOf("pytest", "-q"), cwd)), closure, CostClass.Fast, Trigger.IncrementEnd,
        acceptanceIds = listOf("R1"), command = Command(listOf("pytest", "-q"), cwd),
    )

    @Test
    fun `risk reproduces the section 7-4 formula on a hand-computed diff with indexed fan-in`() {
        TempRepo.create().use { repo ->
            repo.write("pyproject.toml", "[project]\nname = \"pay\"\n")
            repo.write("pay/__init__.py", "")
            repo.write("pay/router.py", "def dispatch(req):\n    return req\n\ndef helper(x):\n    return x\n")
            repo.write("pay/app.py", "from pay.router import dispatch\na = dispatch(1)\nb = dispatch(2)\n")
            repo.commit("initial")
            val assembly = assemblyOf(repo)
            // dispatch: 3 lexical references (the import line and two calls); helper: none.
            val edits = EditSet(
                setOf("pay/router.py"),
                listOf(EditHunk("pay/router.py", 0, 2, 0, 2), EditHunk("pay/router.py", 3, 2, 3, 2)),
            )
            val projection = assembly.analyze(edits, emptyList(), null)
            val hunks = projection.analysis.request.hunks!!
            assertEquals(listOf("dispatch", "helper"), hunks.map { it.symbol })
            assertEquals(listOf(3L, 0L), hunks.map { it.fanIn.count })
            assertTrue(hunks.none { it.fanIn.complete }, "tier 0 fan-in is never complete")
            // Δlines counts deleted + added: 4 · (1 + log2(1 + 3)) + 4 · (1 + log2(1 + 0)) = 4 · 3 + 4 · 1 = 16.
            assertEquals(16.0, projection.analysis.risk.estimate)
            assertEquals(8.toBigInteger(), projection.analysis.risk.changedLines)
            assertNull(projection.analysis.risk.exceedsThreshold, "lexical fan-in leaves the verdict unknown")
            assertTrue(projection.slowChecksEarly)
            assertEquals(3L, projection.riskFloor.maxFanIn)
            assertFalse(projection.riskFloor.fanInComplete)
            assertFalse(projection.riskFloor.contractsComplete, "no contract inventory was supplied")
            assertEquals(IndexTier.Lexical, projection.tier)
            assertFalse(projection.complete)
        }
    }

    @Test
    fun `a check whose closure intersects the importer closure is affected and an unrelated edit leaves it alone FX54`() {
        TempRepo.create().use { repo ->
            repo.write("pyproject.toml", "[project]\nname = \"pay\"\n")
            repo.write("pay/__init__.py", "")
            repo.write("pay/core.py", "def core():\n    return 1\n")
            repo.write("pay/mid.py", "from pay.core import core\n\n\ndef mid():\n    return core()\n")
            repo.write("pay/other.py", "def other():\n    return 2\n")
            repo.write("tests/test_mid.py", "from pay.mid import mid\n\n\ndef test_mid():\n    assert mid() == 1\n")
            repo.write("tests/test_other.py", "from pay.other import other\n\n\ndef test_other():\n    assert other() == 2\n")
            repo.commit("initial")
            val assembly = assemblyOf(repo)
            val checks = listOf(
                check("mid-test", Closure.Known(setOf("tests/test_mid.py", "pay/mid.py"))),
                check("other-test", Closure.Known(setOf("tests/test_other.py", "pay/other.py"))),
                // An unknown closure with a cwd requires that package's suite; without one, the workspace suite.
                check("unknown", Closure.Unknown, cwd = "tests"),
            )
            val graph = assembly.let { ImportGraph.of(Atlas.build(repo.root), workspace) }
            val request = assembly.request(EditSet.paths("pay/core.py"), checks, emptyList())
            val mid = request.checks.single { it.id == "mid-test" }
            assertEquals(setOf(graph.file("tests/test_mid.py")), mid.testFiles)
            assertEquals(setOf(graph.file("pay/mid.py")), mid.namingTargets)
            assertTrue(mid.closureComplete)
            val unknown = request.checks.single { it.id == "unknown" }
            assertNull(unknown.closure)
            assertFalse(unknown.closureComplete)

            val onCore = assembly.analyze(EditSet.paths("pay/core.py"), checks, emptyList())
            assertEquals(setOf("mid-test"), onCore.blastChecks)
            assertEquals(setOf("mid-test", "other-test", "unknown"), onCore.affectedTests, "a lexical graph retains every check")
            assertEquals(setOf(ImpactScope(workspace, repo.root.fileName.toString())), onCore.verificationScopes)
            assertEquals(ImpactScope(workspace, repo.root.fileName.toString()), request.checks.single { it.id == "unknown" }.scope)

            val onOther = assembly.analyze(EditSet.paths("pay/other.py"), checks, emptyList())
            assertEquals(setOf("other-test"), onOther.blastChecks)
            assertFalse("mid-test" in onOther.blastChecks)
        }
    }

    @Test
    fun `contract touch and interface change project the shape and routing anchors`() {
        TempRepo.create().use { repo ->
            repo.write("pyproject.toml", "[project]\nname = \"pay\"\n")
            repo.write("pay/__init__.py", "")
            repo.write("pay/core.py", "def core():\n    return 1\n")
            repo.write("pay/other.py", "def other():\n    return 2\n")
            repo.commit("initial")
            val assembly = assemblyOf(repo)
            val contracts = listOf(
                ContractAnchors("CON-core", setOf("pay/core.py")),
                ContractAnchors("ADR-other", setOf("pay/other.py")),
                ContractAnchors("CON-unknown", null),
            )
            val touched = assembly.analyze(EditSet.paths("pay/core.py"), emptyList(), contracts, interfaceChange = true)
            assertEquals(setOf("CON-core"), touched.analysis.contractsTouched)
            assertTrue(touched.contractTouch)
            assertTrue(touched.requiresMainLineAdr)
            assertTrue(touched.neverAutoMerged)
            assertTrue(touched.neverInS3Child)
            assertEquals(1, touched.riskFloor.contractsTouched)
            assertFalse(touched.riskFloor.contractsComplete, "an unknown anchor set cannot prove absence")
            assertNull(touched.riskFloor.maxFanIn, "paths only: no hunks, no fan-in")

            val untouched = assembly.analyze(EditSet.paths("pay/other.py"), emptyList(), contracts.take(1))
            assertFalse(untouched.contractTouch)
            assertFalse(untouched.requiresMainLineAdr)
            assertFalse(untouched.neverAutoMerged)
            assertTrue(untouched.riskFloor.contractsComplete)
        }
    }
}
