package io.astrolabe.fixtures

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Every P1 test starts from a materialized fixture, so the guarantees asserted here — one `initial`
 * commit, a clean tree before and after a run, and a runner that really executes — are the floor
 * P1.3, P1.6.6, P1.7.5 and P1.12.2 build on.
 *
 * A missing runner skips with [assumeTrue] and is reported as a skip; it never passes silently.
 */
class FixtureReposTest {

    @ParameterizedTest
    @EnumSource(Fixture::class)
    fun `materializes into a clean repository whose only commit is initial`(fixture: Fixture) {
        FixtureRepos.materialize(fixture).use { repo ->
            val status = repo.git.status()
            assertTrue(status.isClean, "${fixture.dir} is dirty: ${status.entries}")
            assertEquals("main", status.branch?.head)

            assertEquals(
                FixtureRepos.files(fixture).sorted(),
                repo.git.lsFiles().map { it.path }.sorted(),
                "${fixture.dir} tracked files differ from its index",
            )

            val log = Runners.run(listOf("git", "log", "--pretty=%s"), repo.root, TOOL_TIMEOUT)
            assertEquals("initial", log.stdout.trim(), describe(log))
            repo.git.revParse("HEAD")
        }
    }

    @Test
    fun `python-small passes, with both same-name tests and every parameterized case`() {
        assumeTrue(Runners.python() != null, "no python interpreter on this host")
        FixtureRepos.materialize(Fixture.PythonSmall).use { repo ->
            val result = FixtureRepos.runTests(Fixture.PythonSmall, repo)
            assertTrue(result.succeeded, describe(result))

            val listing = unittestListing(repo)
            assertTrue(listing.succeeded, describe(listing))
            // Two modules, one test name, two namespaced identities (D-27, IX-13).
            assertContains(listing, "test_smoke (test_router.RouterTest")
            assertContains(listing, "test_smoke (test_handlers.HandlersTest")
            // Both parameterization shapes: one subTest parent and three generated names.
            assertContains(listing, "test_normalize_currency (test_router.NormalizeCurrencyTest")
            for (code in listOf("eur", "usd", "gbp")) {
                assertContains(listing, "test_normalize_currency_$code")
            }
            assertContains(listing, "Ran 9 tests")

            val after = repo.git.status()
            assertTrue(after.isClean, "running the suite dirtied the tree: ${after.entries}")
        }
    }

    @Test
    fun `python-failing fails, naming the pre-existing failure, and skips one test`() {
        assumeTrue(Runners.python() != null, "no python interpreter on this host")
        FixtureRepos.materialize(Fixture.PythonFailing).use { repo ->
            val result = FixtureRepos.runTests(Fixture.PythonFailing, repo)
            assertTrue(!result.timedOut, describe(result))
            assertTrue(result.exitCode != 0, "a red suite must not exit 0\n${describe(result)}")
            assertContains(result, "test_pre_existing_failure")

            val listing = unittestListing(repo)
            assertEquals(1, listing.exitCode, describe(listing))
            assertContains(listing, "FAILED (failures=1, skipped=1)")
            assertContains(listing, "Ran 11 tests")
            // The skip is reported; a skipped test is never a green one (P1.7.5).
            assertContains(listing, "test_skipped_until_fixed")
            assertContains(listing, "skipped 'waiting on the pre-existing failure above'")

            assertTrue(repo.git.status().isClean, "running the suite dirtied the tree")
        }
    }

    @Test
    fun `ts-small passes and only the junit reporter separates its same-name tests`() {
        val node = Runners.node()
        assumeTrue(node != null, "no node on this host")
        FixtureRepos.materialize(Fixture.TsSmall).use { repo ->
            val result = FixtureRepos.runTests(Fixture.TsSmall, repo)
            assertTrue(result.succeeded, describe(result))
            assertContains(result, "pass 7")
            assertContains(result, "fail 0")

            val junit = Runners.run(
                listOf(node!!, "--test", "--test-reporter=junit"),
                repo.root,
                TOOL_TIMEOUT,
            )
            assertTrue(junit.succeeded, describe(junit))
            // Same name in two files: the spec reporter prints both as a bare `smoke`, only the
            // junit reporter's `file` attribute tells them apart (D-27, D-50, IX-13).
            assertEquals(2, occurrences(junit.stdout, "name=\"smoke\""), describe(junit))
            assertContains(junit, "index.test.ts")
            assertContains(junit, "router.test.ts")
            for (code in listOf("eur", "usd", "gbp")) {
                assertContains(junit, "name=\"normalizeCurrency $code\"")
            }

            assertTrue(repo.git.status().isClean, "running the suite dirtied the tree")
        }
    }

    @Test
    fun `gradle-small passes offline and writes parseable JUnit XML`() {
        assumeTrue(Runners.gradle() != null, "no Gradle distribution found; see Runners.gradle()")
        FixtureRepos.materialize(Fixture.GradleSmall).use { repo ->
            val result = FixtureRepos.runTests(Fixture.GradleSmall, repo, GRADLE_TIMEOUT)
            assertTrue(result.succeeded, describe(result))

            val results = repo.root.resolve("build/test-results/test")
            val router = Files.readString(
                results.resolve("TEST-pay.RouterTest.xml"),
                StandardCharsets.UTF_8,
            )
            val handler = Files.readString(
                results.resolve("TEST-pay.handlers.HandlerTest.xml"),
                StandardCharsets.UTF_8,
            )
            // One method name, two packages: only `classname` separates them (D-27, IX-13).
            assertTrue(router.contains("classname=\"pay.RouterTest\""), router)
            assertTrue(handler.contains("classname=\"pay.handlers.HandlerTest\""), handler)
            assertEquals(1, occurrences(router, "name=\"smoke()\""), router)
            assertEquals(1, occurrences(handler, "name=\"smoke()\""), handler)
            // @ParameterizedTest rows keep the parameterization and drop the method name.
            assertTrue(router.contains("tests=\"5\""), router)
            for (ordinal in 1..3) {
                assertTrue(router.contains("name=\"[$ordinal] "), router)
            }
            for (value in listOf("EUR", "Usd", "gbp")) {
                assertTrue(router.contains(value), router)
            }

            val after = repo.git.status()
            assertTrue(after.isClean, "the build dirtied the tree: ${after.entries}")
        }
    }

    private fun unittestListing(repo: TempRepo): RunOutput = Runners.run(
        listOf(Runners.python()!!, "-m", "unittest", "discover", "-s", "tests", "-v"),
        repo.root,
        TOOL_TIMEOUT,
    )

    private fun assertContains(output: RunOutput, needle: String) {
        assertTrue(output.combined.contains(needle), "expected '$needle' in\n${describe(output)}")
    }

    private fun occurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun describe(output: RunOutput): String = buildString {
        append("exit=").append(output.exitCode).append(" timedOut=").append(output.timedOut)
        append("\n--- stdout ---\n").append(output.stdout.take(OUTPUT_EXCERPT))
        append("\n--- stderr ---\n").append(output.stderr.take(OUTPUT_EXCERPT))
    }

    private companion object {
        const val TOOL_TIMEOUT = 120L
        const val GRADLE_TIMEOUT = 300L
        const val OUTPUT_EXCERPT = 4000
    }
}
