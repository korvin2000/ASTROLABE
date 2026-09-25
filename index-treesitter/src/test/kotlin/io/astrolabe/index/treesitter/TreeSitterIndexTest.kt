package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Atlas
import io.astrolabe.atlas.DeclarationKind
import io.astrolabe.atlas.Impact
import io.astrolabe.atlas.ImpactRequest
import io.astrolabe.atlas.ImportGraph
import io.astrolabe.atlas.IndexTier
import io.astrolabe.atlas.Language
import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.search.Searches
import io.astrolabe.tool.edit.SyntaxResult
import io.astrolabe.workspace.WorkspacePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P5.4.1: the index surface, the D-10 syntax provider, FX-46 degradation and the tier-1 import graph. */
class TreeSitterIndexTest {
    private val workspace = WorkspaceId("main")

    private fun edges(graph: ImportGraph): Set<Pair<String, String>> =
        graph.graph.imports.mapTo(HashSet()) { it.importer.path to it.dependency.path }

    @Test
    fun `def answers from tier-1 outlines and refs stay lexical and incomplete`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            TreeSitterIndex(Atlas.build(repo.root), WorkspacePath.of(repo.root), Searches.jvm()).use { index ->
                val def = index.def("Router").single()
                assertEquals("pay/router.py", def.path)
                assertEquals(DeclarationKind.Class, def.kind)
                val refs = index.refs("Router")
                assertEquals(IndexTier.Lexical, refs.tier)
                assertFalse(refs.complete, "tier 1 claims no cross-module resolution")
                assertTrue(refs.references.none { it.path == def.path && it.line == def.line })
                assertTrue(refs.references.isNotEmpty())
            }
        }
    }

    @Test
    fun `the syntax provider declares its accepted file types and parser completeness`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            TreeSitterIndex(Atlas.build(repo.root)).use { index ->
                assertTrue("py" in index.acceptedExtensions && "tsx" in index.acceptedExtensions)
                assertFalse("kt" in index.acceptedExtensions, "the Kotlin grammar is incomplete (D-211)")

                assertEquals(SyntaxResult.Ok, index.check("pay/router.py", repo.root.resolve("pay/router.py"), Language.Python))
                val broken = repo.write("pay/broken.py", "def (:\n")
                assertIs<SyntaxResult.Error>(index.check("pay/broken.py", broken, Language.Python))

                val kotlin = repo.write("A.kt", "class A\n")
                assertIs<SyntaxResult.NotRun>(index.check("A.kt", kotlin, Language.Kotlin))
                assertIs<SyntaxResult.NotRun>(index.check("pyproject.toml", repo.root.resolve("pyproject.toml"), Language.Toml))
            }
        }
    }

    @Test
    fun `a grammar whose natives cannot load degrades to tier 0 with a reported line`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            val failing = GrammarLoader { throw UnsatisfiedLinkError("no tree-sitter-python in java.library.path") }
            TreeSitterIndex(atlas, WorkspacePath.of(repo.root), Searches.jvm(), failing, 8).use { index ->
                val outline = index.outline("pay/router.py")
                assertEquals(IndexTier.Lexical, outline.tier)
                assertEquals(atlas.outline("pay/router.py"), outline)
                val degradation = assertNotNull(index.degradation)
                assertTrue("tree-sitter python unavailable" in degradation, degradation)
                assertTrue("tier 0" in degradation, degradation)

                val result = assertIs<SyntaxResult.NotRun>(index.check("pay/router.py", repo.root.resolve("pay/router.py"), Language.Python))
                assertTrue("unavailable" in result.reason, result.reason)
                assertEquals(IndexTier.Lexical, index.importGraph(workspace).graph.tier)
            }
        }
    }

    @Test
    fun `a tier-1 import graph keeps the tier-0 edges and lets a blast narrow`() {
        for (fixture in listOf(Fixture.PythonSmall, Fixture.TsSmall, Fixture.GradleSmall)) {
            FixtureRepos.materialize(fixture).use { repo ->
                val atlas = Atlas.build(repo.root)
                TreeSitterIndex(atlas).use { index ->
                    val lexical = ImportGraph.of(atlas, workspace)
                    val syntax = index.importGraph(workspace)
                    assertEquals(IndexTier.Lexical, lexical.graph.tier)
                    assertEquals(IndexTier.Syntax, syntax.graph.tier, fixture.dir)
                    assertEquals(edges(lexical), edges(syntax), fixture.dir)
                    assertEquals(lexical.graph.complete, syntax.graph.complete, "${fixture.dir}: ${syntax.graph.unresolved}")
                }
            }
        }
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            TreeSitterIndex(atlas).use { index ->
                fun blastComplete(graph: ImportGraph): Boolean = Impact.analyze(
                    ImpactRequest(graph.graph, setOf(graph.file("pay/handlers/user.py")), null, emptyList(), emptyList(), true),
                ).blastComplete
                assertFalse(blastComplete(ImportGraph.of(atlas, workspace)), "tier 0 always widens (D-88)")
                assertTrue(blastComplete(index.importGraph(workspace)))
            }
        }
    }

    @Test
    fun `a parse error or an unextracted language keeps the tier-1 graph incomplete`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            repo.write("pay/broken.py", "import pay.router\ndef (:\n")
            repo.write("tools/main.go", "package main\n")
            TreeSitterIndex(Atlas.build(repo.root)).use { index ->
                val graph = index.importGraph(workspace)
                assertEquals(IndexTier.Syntax, graph.graph.tier)
                assertFalse(graph.graph.complete)
                assertTrue(graph.unresolved("pay/broken.py").any { "syntax errors" in it }, "${graph.unresolved("pay/broken.py")}")
                assertTrue(graph.unresolved("tools/main.go").any { "no import extraction" in it })
                assertTrue(graph.isComplete("pay/router.py"))
            }
        }
    }

    @Test
    fun `a file without a grammar or outside the atlas answers as tier 0 does`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            TreeSitterIndex(atlas).use { index ->
                assertEquals(atlas.outline("pyproject.toml"), index.outline("pyproject.toml"))
                assertEquals(emptyList(), index.outline("missing.py").entries)
                assertNull(index.degradation)
            }
        }
    }
}
