package io.astrolabe.atlas

import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.Digest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** P1.3.1: the atlas lists every file it did not collapse, and never lies about existence (§7.1). */
class AtlasTest {

    @Test
    fun `every fixture file has a row with its own size and hash`() {
        for (fixture in Fixture.entries) {
            FixtureRepos.materialize(fixture).use { repo ->
                val atlas = Atlas.build(repo.root)
                val expected = FixtureRepos.files(fixture).sorted()
                assertEquals(expected, atlas.rows.map { it.path }, "${fixture.dir} rows")
                for (row in atlas.rows) {
                    val bytes = Files.readAllBytes(repo.resolve(row.path))
                    assertEquals(bytes.size.toLong(), row.bytes, "${fixture.dir}:${row.path} size")
                    assertEquals(Digest.of(bytes).hash8, row.hash8, "${fixture.dir}:${row.path} hash8")
                    assertEquals(Language.of(row.path), row.lang, "${fixture.dir}:${row.path} lang")
                }
            }
        }
    }

    @Test
    fun `python rows carry exports, resolved imports and tests_for`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            val router = atlas.row("pay/router.py")!!
            assertEquals(
                listOf("CURRENCIES", "ROUTES", "normalize_currency", "dispatch", "Router"),
                router.exports,
            )
            assertEquals(listOf("pay/handlers/user.py"), router.imports)
            assertEquals(emptyList<String>(), router.testsFor)

            val test = atlas.row("tests/test_router.py")!!
            assertEquals(listOf("pay/router.py"), test.imports)
            assertEquals(listOf("pay/router.py"), test.testsFor)
            assertTrue(test.isTest)

            assertEquals(listOf("pay/handlers/user.py"), atlas.row("tests/test_handlers.py")!!.testsFor)
            assertEquals(
                listOf("pay/handlers/user.py" to 2, "pay/router.py" to 1),
                atlas.hubs(10),
            )
        }
    }

    @Test
    fun `typescript rows resolve relative specifiers`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            assertEquals(listOf("src/router.ts"), atlas.row("src/index.ts")!!.imports)
            assertEquals(listOf("src/router.ts"), atlas.row("test/router.test.ts")!!.testsFor)
            assertEquals(listOf("src/index.ts"), atlas.row("test/index.test.ts")!!.testsFor)
            assertTrue("normalizeCurrency" in atlas.row("src/router.ts")!!.exports)
            assertEquals(listOf("src/index.ts", "test/router.test.ts"), atlas.importers("src/router.ts"))
        }
    }

    @Test
    fun `kotlin rows resolve imports by declared package`() {
        FixtureRepos.materialize(Fixture.GradleSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            assertEquals(
                listOf("src/main/kotlin/pay/Router.kt"),
                atlas.row("src/main/kotlin/pay/handlers/User.kt")!!.imports,
            )
            // The import names a function in another package; the naming convention adds Router.kt.
            assertEquals(
                listOf("src/main/kotlin/pay/Router.kt", "src/main/kotlin/pay/handlers/User.kt"),
                atlas.row("src/test/kotlin/pay/RouterTest.kt")!!.testsFor,
            )
            assertEquals(
                listOf("Request", "Response", "CURRENCIES", "normalizeCurrency", "Router"),
                atlas.row("src/main/kotlin/pay/Router.kt")!!.exports,
            )
        }
    }

    @Test
    fun `vendor, build, generated, minified, lock and oversized paths are listed but not parsed`() {
        TempRepo.create().use { repo ->
            repo.write("src/app.js", "export const x = 1;\n")
            repo.write("node_modules/left-pad/index.js", "module.exports = 1;\n")
            repo.write("node_modules/left-pad/package.json", "{}\n")
            repo.write("dist/bundle.js", "var a=1\n")
            repo.write("__pycache__/mod.cpython-312.pyc", "x\n")
            repo.write("web/app.min.js", "var a=1\n")
            repo.write("deps.lock", "pinned\n")
            repo.write("huge.txt", "x".repeat((Atlas.MAX_PARSED_BYTES + 1).toInt()))
            val atlas = Atlas.build(repo.root)

            assertEquals(listOf("src/app.js"), atlas.rows.map { it.path })
            assertNull(atlas.row("node_modules/left-pad/index.js"))
            assertEquals(
                listOf(
                    "__pycache__" to CollapseReason.Generated,
                    "deps.lock" to CollapseReason.Lockfile,
                    "dist" to CollapseReason.Build,
                    "huge.txt" to CollapseReason.TooLarge,
                    "node_modules" to CollapseReason.Vendored,
                    "web/app.min.js" to CollapseReason.Minified,
                ),
                atlas.collapsed.map { it.path to it.reason },
            )
            assertEquals(2, atlas.collapsed.single { it.path == "node_modules" }.files)
        }
    }

    @Test
    fun `rebuild from cache equals a fresh build`(@TempDir indexes: Path) {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val fresh = Atlas.build(repo.root)
            fresh.save(indexes)
            val cached = Atlas.load(indexes, repo.root)
            assertEquals(fresh, cached, "a cache hit must reproduce the build exactly")
            assertEquals(fresh.repoKey, cached!!.repoKey)
            assertEquals(Focus.render(fresh, Focus.Root), Focus.render(cached, Focus.Root))
        }
    }

    @Test
    fun `a changed file invalidates the cache`(@TempDir indexes: Path) {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val fresh = Atlas.build(repo.root)
            fresh.save(indexes)
            repo.modify("pay/router.py", "def dispatch(req):\n    return {}\n")
            assertNull(Atlas.load(indexes, repo.root), "a changed row may never produce a cache hit")
            val reopened = Atlas.open(indexes, repo.root)
            assertNotEquals(fresh.repoKey, reopened.repoKey)
            assertEquals(reopened, Atlas.load(indexes, repo.root), "open() stores what it built")
        }
    }

    @Test
    fun `an added and a removed file both invalidate the cache`(@TempDir indexes: Path) {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            Atlas.build(repo.root).save(indexes)
            repo.write("pay/extra.py", "def extra():\n    return 1\n")
            assertNull(Atlas.load(indexes, repo.root), "an added file may never produce a cache hit")

            Atlas.build(repo.root).save(indexes)
            Files.delete(repo.resolve("pay/extra.py"))
            assertNull(Atlas.load(indexes, repo.root), "a removed file may never produce a cache hit")
        }
    }

    @Test
    fun `refresh updates only the touched rows`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val before = Atlas.build(repo.root)
            repo.modify("pay/handlers/user.py", "def handle_user(req):\n    return {\"status\": 204}\n")
            val after = before.refresh(listOf("pay/handlers/user.py"))

            assertEquals(before.rows.map { it.path }, after.rows.map { it.path })
            val changed = after.row("pay/handlers/user.py")!!
            assertNotEquals(before.row("pay/handlers/user.py")!!.hash8, changed.hash8)
            assertEquals(listOf("handle_user"), changed.exports)
            for (row in before.rows) {
                if (row.path == "pay/handlers/user.py") continue
                assertSame(row, after.row(row.path), "${row.path} was rebuilt although it was not touched")
            }
            assertNotEquals(before.repoKey, after.repoKey)
        }
    }

    @Test
    fun `refresh adds a new file and drops a deleted one`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val before = Atlas.build(repo.root)
            repo.write("pay/extra.py", "def extra():\n    return 1\n")
            val added = before.refresh(listOf("pay/extra.py"))
            assertEquals(listOf("extra"), added.row("pay/extra.py")!!.exports)
            assertEquals(before.rows.size + 1, added.rows.size)

            Files.delete(repo.resolve("pay/extra.py"))
            val removed = added.refresh(listOf("pay/extra.py"))
            assertNull(removed.row("pay/extra.py"))
            assertEquals(before.rows.map { it.path }, removed.rows.map { it.path })
            assertEquals(before.repoKey, removed.repoKey, "the key is a pure function of content")
        }
    }

    @Test
    fun `refresh of an untouched path is a no-op`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val before = Atlas.build(repo.root)
            val after = before.refresh(listOf("pay/router.py"))
            assertEquals(before, after)
            assertEquals(before.repoKey, after.repoKey)
        }
    }

    @Test
    fun `the repository key ignores the directory the repository lives in`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { first ->
            FixtureRepos.materialize(Fixture.TsSmall).use { second ->
                assertNotEquals(first.root, second.root)
                assertEquals(Atlas.build(first.root).repoKey, Atlas.build(second.root).repoKey)
            }
        }
    }

    @Test
    fun `dot directories are listed because a rules candidate is one`() {
        TempRepo.create().use { repo ->
            repo.write(".astrolabe/rules.md", "# house rules\n")
            repo.write(".github/workflows/ci.yml", "on: push\n")
            repo.write("main.py", "x = 1\n")
            val atlas = Atlas.build(repo.root)
            assertEquals(
                listOf(".astrolabe/rules.md", ".github/workflows/ci.yml", "main.py"),
                atlas.rows.map { it.path },
            )
            assertFalse(atlas.rows.any { it.path.startsWith(".git/") }, "the git directory is never indexed")
        }
    }

    @Test
    fun `a traversing path is never resolved outside the root`() {
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val atlas = Atlas.build(repo.root)
            assertSame(atlas, atlas.refresh(listOf("../outside.py")), "a traversal is not a touched path")
            assertEquals(
                "atlas : not in the atlas",
                Focus.render(atlas, Focus.File("../pay/router.py")),
            )
            assertNull(atlas.row("../pay/router.py"))
        }
    }

    @Test
    fun `an atlas outside a git repository still lists its files`(@TempDir plain: Path) {
        Files.createDirectories(plain.resolve("src"))
        Files.writeString(plain.resolve("src/a.py"), "def a():\n    return 1\n")
        Files.writeString(plain.resolve("README.md"), "# plain\n")
        val atlas = Atlas.build(plain)
        assertEquals(listOf("README.md", "src/a.py"), atlas.rows.map { it.path })
    }
}
