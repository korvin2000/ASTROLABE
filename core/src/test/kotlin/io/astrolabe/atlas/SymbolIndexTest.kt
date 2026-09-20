package io.astrolabe.atlas

import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.os.search.Searches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.3.3: tier-0 definitions and references, always flagged incomplete (§7.2). */
class SymbolIndexTest {

    // The in-process backend, so the assertions do not depend on ripgrep being installed; both
    // backends return identical hits for this pattern subset (D-05).
    private fun indexOf(root: java.nio.file.Path) = SymbolIndex(Atlas.build(root), Searches.jvm())

    @Test
    fun `definitions carry their file, line and kind`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val index = indexOf(repo.root)
            assertEquals(
                listOf(Location("pay/router.py", 20, DeclarationKind.Function, "dispatch")),
                index.def("dispatch"),
            )
            assertEquals(
                listOf(Location("pay/router.py", 33, DeclarationKind.Class, "Router")),
                index.def("Router"),
            )
            // One method name declared in two modules stays two distinct locations (D-27).
            assertEquals(
                listOf("tests/test_handlers.py", "tests/test_router.py"),
                index.def("test_smoke").map { it.path },
            )
            assertEquals(emptyList(), index.def("no_such_symbol"))
        }
    }

    @Test
    fun `references are lexical, exclude the definition line and are never complete`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val index = indexOf(repo.root)
            val refs = index.refs("dispatch")

            assertEquals(IndexTier.Lexical, refs.tier)
            assertEquals(0, refs.tier.level)
            assertFalse(refs.complete, "tier 0 references are never complete (§7.2)")
            assertFalse(refs.truncated)
            assertTrue(refs.references.isNotEmpty())
            assertFalse(
                refs.references.any { it.path == "pay/router.py" && it.line == 20 },
                "the definition line is not a reference to itself",
            )
            assertTrue(refs.references.any { it.path == "tests/test_router.py" })
            assertTrue(refs.references.all { atlasKnows(repo.root, it.path) })
        }
    }

    @Test
    fun `an empty result is never a claim of absence`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val refs = indexOf(repo.root).refs("definitely_not_here")
            assertEquals(emptyList(), refs.references)
            assertFalse(refs.complete)
        }
    }

    @Test
    fun `a word-boundary match does not report a longer identifier`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val index = indexOf(repo.root)
            assertTrue(index.refs("normalize_currency").references.isNotEmpty())
            // `Router` must not match `RouterTest`.
            val routerLines = index.refs("Router").references.map { it.text }
            assertTrue(routerLines.none { it.trim() == "class RouterTest(unittest.TestCase):" })
        }
    }

    @Test
    fun `a truncating budget is reported separately from tier incompleteness`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val refs = indexOf(repo.root).refs("Router", budgetBytes = 16, maxHits = 1)
            assertTrue(refs.truncated, "a budget stop must be visible")
            assertFalse(refs.complete)
        }
    }

    @Test
    fun `importers come from the resolved import edges`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val importers = indexOf(repo.root).importers("src/router.ts")
            assertEquals(listOf("src/index.ts", "test/router.test.ts"), importers.paths)
            assertEquals(IndexTier.Lexical, importers.tier)
            assertFalse(importers.complete)
        }
    }

    @Test
    fun `definitions work across the D-09 language set`() {
        FixtureRepos.materialize(Fixture.GradleSmall).use { repo ->
            val index = indexOf(repo.root)
            assertEquals(
                listOf(Location("src/main/kotlin/pay/handlers/User.kt", 7, DeclarationKind.Function, "handleUser")),
                index.def("handleUser"),
            )
        }
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val index = indexOf(repo.root)
            assertEquals(
                listOf("src/index.ts", "src/router.ts"),
                index.def("normalizeCurrency").map { it.path }.distinct(),
            )
        }
    }

    private fun atlasKnows(root: java.nio.file.Path, path: String): Boolean =
        Atlas.build(root).row(path) != null
}
