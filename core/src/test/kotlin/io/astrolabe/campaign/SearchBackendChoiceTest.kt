package io.astrolabe.campaign

import io.astrolabe.Config
import io.astrolabe.atlas.OsFamily
import io.astrolabe.atlas.PathProbe
import io.astrolabe.budget.Tokens
import io.astrolabe.fixtures.FakeClock
import io.astrolabe.fixtures.FakeProfiles
import io.astrolabe.fixtures.FixedIdGen
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.AttemptId
import io.astrolabe.id.WorkId
import io.astrolabe.os.search.RipgrepSearch
import io.astrolabe.os.search.SearchBackend
import io.astrolabe.os.search.SearchMode
import io.astrolabe.os.search.SearchOutcome
import io.astrolabe.os.search.SearchRequest
import io.astrolabe.os.search.SearchScope
import io.astrolabe.os.search.Searches
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * C18 item 3: a campaign's cells search with ripgrep when `rg` resolves on the host's `PATH` and with the JVM backend
 * otherwise, chosen once per open. The `PATH` is a test directory — on Windows too — and the `rg` there is never started.
 */
class SearchBackendChoiceTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the search backend is ripgrep when rg resolves on the host PATH and the JVM otherwise, chosen once per open`() {
        val os = OsFamily.of(System.getProperty("os.name").orEmpty())
        val bin = Files.createDirectories(tmp.resolve("bin"))
        val empty = Files.createDirectories(tmp.resolve("empty"))
        val rg = Files.writeString(bin.resolve(if (os == OsFamily.Windows) "rg.exe" else "rg"), "never started\n")
        rg.toFile().setExecutable(true)
        fun env(dir: Path) = if (os == OsFamily.Windows) mapOf("Path" to dir.toString(), "PATHEXT" to ".COM;.EXE") else mapOf("PATH" to dir.toString())
        val clock = FakeClock.at("2026-10-07T10:00:00Z")
        val idGen = FixedIdGen()
        for ((dir, expected) in listOf(empty to SearchBackend.Jvm, bin to SearchBackend.Ripgrep)) {
            TempRepo.create().use { repo ->
                repo.write("src/a.py", "def a():\n    return 1\n")
                repo.commit("initial")
                val config = Config(stateRoot = tmp.resolve("state-${expected.name}").toString(), profiles = FakeProfiles.all)
                val controller = Controller(config, clock, idGen, host = PathProbe(os, env(dir)))
                controller.open(repo.root, CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10"), CampaignPolicy(Tokens(100_000))).use { c ->
                    val chosen = controller.search(c)
                    assertEquals(expected, chosen.backend, "PATH=$dir")
                    assertSame(chosen, controller.search(c), "the backend is chosen once per open")
                }
            }
        }
    }

    @Test
    fun `a Windows rg shim on PATH is never chosen, so the open searches with the JVM backend`() {
        val shims = Files.createDirectories(tmp.resolve("shims"))
        Files.writeString(shims.resolve("rg.cmd"), "@echo never started\n")
        Files.writeString(shims.resolve("rg.bat"), "@echo never started\n")
        val probe = PathProbe(OsFamily.Windows, mapOf("Path" to shims.toString(), "PATHEXT" to ".COM;.EXE;.BAT;.CMD"))
        assertTrue(probe.onPath("rg"), "the shim is on PATH for the host block")
        assertFalse(probe.launchable("rg"), "a start without a shell runs rg.exe only")
        TempRepo.create().use { repo ->
            repo.write("src/a.py", "def a():\n    return 1\n")
            repo.commit("initial")
            val controller = Controller(Config(stateRoot = tmp.resolve("state").toString(), profiles = FakeProfiles.all), FakeClock.at("2026-10-07T10:00:00Z"), FixedIdGen(), host = probe)
            controller.open(repo.root, CampaignRequest(WorkId("W-1"), AttemptId("a1"), "make a return 10"), CampaignPolicy(Tokens(100_000))).use { c ->
                assertEquals(SearchBackend.Jvm, controller.search(c).backend)
            }
        }
    }

    @Test
    fun `a ripgrep that cannot start falls back to the JVM backend on its first search and keeps it`() {
        val root = Files.createDirectories(tmp.resolve("tree"))
        Files.writeString(root.resolve("a.txt"), "alpha needle\n")
        val request = SearchRequest("needle", SearchMode.Literal, SearchScope.All(root), 1_000_000)
        val missing = tmp.resolve("missing").resolve("rg").toString()
        assertIs<SearchOutcome.Failed>(Searches.ripgrep(missing).find(request), "without a fallback a failed start is a failed search")
        val search = RipgrepSearch(missing, Searches.jvm())
        assertEquals(SearchBackend.Ripgrep, search.backend)
        val found = assertIs<SearchOutcome.Found>(search.find(request))
        assertEquals(listOf("a.txt"), found.hits.hits.map { it.path })
        assertEquals(SearchBackend.Jvm, found.hits.backend)
        assertEquals(SearchBackend.Jvm, search.backend, "every later search of the open runs on the JVM")
    }
}
