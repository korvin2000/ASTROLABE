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
import io.astrolabe.os.search.SearchBackend
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
