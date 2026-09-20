package io.astrolabe.atlas

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator
import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.fixtures.TempRepo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** P1.3.1: `Focus.render` — root, directory and file zooms inside the `[A]` budget (§5.1, §7.1). */
class FocusTest {

    private val cap = Defaults().focusZoomMaxTokens

    private fun tokens(text: String): Long = HeuristicEstimator().estimate(text).tokens

    @Test
    fun `the root render lists one level with sizes`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val text = Focus.render(Atlas.build(repo.root), Focus.Root)
            assertEquals(
                """
                atlas /: 9 files · 5.4 KiB
                  pay/ 4 files · 1.8 KiB
                  tests/ 2 files · 1.8 KiB
                  .gitignore 49 B
                  README.md 1.4 KiB
                  pyproject.toml 195 B
                """.trimIndent(),
                text,
            )
            // §7.7: the root render never lists more than one level.
            assertFalse("router.py" in text)
        }
    }

    @Test
    fun `a directory render counts exports and names them`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val text = Focus.render(Atlas.build(repo.root), Focus.Dir("pay"))
            assertEquals(
                """
                atlas pay/: 4 files · 1.8 KiB
                  handlers/ 2 files · 530 B
                  __init__.py 0 exports
                  router.py 5 exports · CURRENCIES, ROUTES, normalize_currency, dispatch, Router
                """.trimIndent(),
                text,
            )
        }
    }

    @Test
    fun `a file render is its outline`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val text = Focus.render(Atlas.build(repo.root), Focus.File("pay/router.py"))
            assertEquals(
                """
                atlas pay/router.py: python · 1.2 KiB · 9bf3b06b · tier 0 · complete=false
                  const CURRENCIES 5
                  const ROUTES 7-9
                  function normalize_currency 12-17
                  function dispatch 20-30
                  class Router 33-48
                  method __init__ 36-39
                  method route 41-45
                  method paths 47-48
                """.trimIndent(),
                text,
            )
        }
    }

    @Test
    fun `a test file render names what it covers`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val text = Focus.render(Atlas.build(repo.root), Focus.File("test/router.test.ts"))
            assertContains(text, "tests_for: src/router.ts")
        }
    }

    @Test
    fun `an unknown path says so instead of inventing one`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            assertEquals("atlas pay/nope.py: not in the atlas", Focus.render(atlas, Focus.File("pay/nope.py")))
            assertEquals("atlas nope/: not in the atlas", Focus.render(atlas, Focus.Dir("nope")))
        }
    }

    @Test
    fun `every fixture zoom fits the focus budget`() {
        for (fixture in Fixture.entries) {
            FixtureRepos.materialize(fixture).use { repo ->
                val atlas = Atlas.build(repo.root)
                val focuses = listOf(Focus.Root) +
                    atlas.subdirectories("").map { Focus.Dir(it) } +
                    atlas.rows.map { Focus.File(it.path) }
                for (focus in focuses) {
                    val text = Focus.render(atlas, focus)
                    assertTrue(
                        tokens(text) <= cap,
                        "${fixture.dir} $focus rendered ${tokens(text)} tokens, cap is $cap",
                    )
                }
            }
        }
    }

    @Test
    fun `an oversized render is truncated deterministically`() {
        TempRepo.create().use { repo ->
            for (index in 1..160) repo.write("file${index.toString().padStart(3, '0')}.txt", "body $index\n")
            val atlas = Atlas.build(repo.root)
            val text = Focus.render(atlas, Focus.Root)
            assertTrue(tokens(text) <= cap, "rendered ${tokens(text)} tokens, cap is $cap")
            val marker = text.lines().last()
            assertTrue(marker.startsWith("… +"), "expected a truncation marker, got '$marker'")
            assertEquals(161 - (text.lines().size - 1), marker.removePrefix("… +").toInt())
            assertEquals(text, Focus.render(atlas, Focus.Root), "truncation is a pure function")
        }
    }

    @Test
    fun `the render is byte-identical for equal repositories in different directories`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { first ->
            FixtureRepos.materialize(Fixture.TsSmall).use { second ->
                for (focus in listOf(Focus.Root, Focus.Dir("src"), Focus.File("src/router.ts"))) {
                    assertEquals(
                        Focus.render(Atlas.build(first.root), focus),
                        Focus.render(Atlas.build(second.root), focus),
                        "$focus is not byte-stable",
                    )
                }
            }
        }
    }

    @Test
    fun `sizes are locale-free and rendered from integers`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("1.0 MiB", formatBytes(1024L * 1024))
        assertEquals("2.5 GiB", formatBytes(1024L * 1024 * 1024 * 5 / 2))
    }
}
