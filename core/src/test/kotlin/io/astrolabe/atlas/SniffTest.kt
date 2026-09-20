package io.astrolabe.atlas

import io.astrolabe.fixtures.Fixture
import io.astrolabe.fixtures.FixtureRepos
import io.astrolabe.fixtures.TempRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P1.3.4: commands are read out of manifests, never guessed (§7.7, D-09). */
class SniffTest {

    private fun sniff(repo: TempRepo): Sniffed = Sniff.commands(Atlas.build(repo.root))

    @Test
    fun `pyproject that configures pytest yields the pytest runner`() {
        for (fixture in listOf(Fixture.PythonSmall, Fixture.PythonFailing)) {
            FixtureRepos.materialize(fixture).use { repo ->
                val pkg = sniff(repo).packages.single()
                assertEquals(".", pkg.dir)
                assertEquals(Manifest.PyProject, pkg.manifest)
                assertEquals(listOf("python", "-m", "pytest", "-q"), pkg.test)
                assertNull(pkg.build)
                assertNull(pkg.lint)
                assertNull(pkg.typecheck)
            }
        }
    }

    @Test
    fun `package json with a bare node runner yields node --test and no type check`() {
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val pkg = sniff(repo).packages.single()
            assertEquals(Manifest.PackageJson, pkg.manifest)
            assertEquals(listOf("node", "--test"), pkg.test)
            // D-09: the fixture's `check` script is `node --check`, which never validates TypeScript,
            // and the fixture declares no typescript dependency — so there is no type check.
            assertNull(pkg.typecheck)
            assertNull(pkg.lint)
            assertNull(pkg.build)
        }
    }

    @Test
    fun `gradle without a wrapper uses the gradle launcher`() {
        FixtureRepos.materialize(Fixture.GradleSmall).use { repo ->
            val pkg = sniff(repo).packages.single()
            assertEquals(Manifest.GradleKts, pkg.manifest)
            assertEquals(listOf("gradle", "test"), pkg.test)
            assertEquals(listOf("gradle", "build"), pkg.build)
        }
    }

    @Test
    fun `a wrapper in the repository root is preferred over the gradle launcher`() {
        TempRepo.create().use { repo ->
            repo.write("gradlew", "#!/bin/sh\n")
            repo.write("modules/api/build.gradle.kts", "plugins { kotlin(\"jvm\") }\n")
            val pkg = sniff(repo).packages.single()
            assertEquals("modules/api", pkg.dir)
            assertEquals(listOf("gradlew", "test"), pkg.test)
        }
    }

    @Test
    fun `no manifest yields no commands`() {
        TempRepo.create().use { repo ->
            repo.write("README.md", "# nothing\n")
            repo.write("src/app.rb", "puts 1\n")
            assertEquals(Sniffed.NONE, sniff(repo))
            assertTrue(sniff(repo).isEmpty)
        }
    }

    @Test
    fun `a monorepo reports one package per manifest`() {
        TempRepo.create().use { repo ->
            repo.write(
                "packages/web/package.json",
                """{"scripts": {"test": "vitest run", "build": "tsc -b", "lint": "eslint ."}}""" + "\n",
            )
            repo.write("packages/web/tsconfig.json", "{}\n")
            repo.write(
                "services/pay/pyproject.toml",
                "[project]\nname = \"pay\"\n\n[tool.pytest.ini_options]\ntestpaths = [\"tests\"]\n\n[tool.ruff]\n",
            )
            repo.write("Makefile", "all: test\n\ntest:\n\tmake -C services/pay test\n\nlint:\n\techo lint\n")
            val packages = sniff(repo).packages

            assertEquals(
                listOf("." to Manifest.Makefile, "packages/web" to Manifest.PackageJson, "services/pay" to Manifest.PyProject),
                packages.map { it.dir to it.manifest },
            )
            assertEquals(listOf("make", "test"), packages[0].test)
            assertEquals(listOf("make", "lint"), packages[0].lint)
            assertNull(packages[0].typecheck, "the Makefile declares no typecheck target")

            assertEquals(listOf("npm", "test"), packages[1].test)
            assertEquals(listOf("npm", "run", "build"), packages[1].build)
            assertEquals(listOf("npm", "run", "lint"), packages[1].lint)
            assertNull(packages[1].typecheck, "no typescript dependency is declared")

            assertEquals(listOf("python", "-m", "pytest", "-q"), packages[2].test)
            assertEquals(listOf("python", "-m", "ruff", "check", "."), packages[2].lint)
            assertEquals("services/pay/pyproject.toml", packages[2].manifestPath)
        }
    }

    @Test
    fun `tsc is only proposed when a tsconfig and a typescript dependency both exist`() {
        TempRepo.create().use { repo ->
            repo.write(
                "package.json",
                """{"devDependencies": {"typescript": "5.9.0"}, "scripts": {"test": "node --test"}}""" + "\n",
            )
            repo.write("tsconfig.json", "{}\n")
            assertEquals(listOf("npx", "tsc", "--noEmit"), sniff(repo).packages.single().typecheck)
        }
    }

    @Test
    fun `a python project without pytest configuration falls back to unittest discovery`() {
        TempRepo.create().use { repo ->
            repo.write("pyproject.toml", "[project]\nname = \"p\"\n\n[tool.mypy]\nstrict = true\n")
            repo.write("tests/test_p.py", "import unittest\n")
            val pkg = sniff(repo).packages.single()
            assertEquals(listOf("python", "-m", "unittest", "discover", "-s", "tests"), pkg.test)
            assertEquals(listOf("python", "-m", "mypy", "."), pkg.typecheck)
        }
    }

    @Test
    fun `cargo, go and maven manifests map to their own runners`() {
        TempRepo.create().use { repo ->
            repo.write("rust/Cargo.toml", "[package]\nname = \"r\"\n")
            repo.write("golang/go.mod", "module example.com/g\n")
            repo.write("java/pom.xml", "<project></project>\n")
            val byDir = sniff(repo).packages.associateBy { it.dir }

            assertEquals(listOf("cargo", "test"), byDir.getValue("rust").test)
            assertEquals(listOf("cargo", "clippy"), byDir.getValue("rust").lint)
            assertEquals(listOf("go", "test", "./..."), byDir.getValue("golang").test)
            assertEquals(listOf("go", "vet", "./..."), byDir.getValue("golang").lint)
            assertEquals(listOf("mvn", "-q", "test"), byDir.getValue("java").test)
            assertNull(byDir.getValue("java").lint, "a pom declares no lint by itself")
        }
    }

    @Test
    fun `a malformed package json declares nothing rather than something wrong`() {
        TempRepo.create().use { repo ->
            repo.write("package.json", "{ this is not json\n")
            val pkg = sniff(repo).packages.single()
            assertTrue(pkg.isEmpty, "a manifest that cannot be read declares nothing")
        }
    }
}
