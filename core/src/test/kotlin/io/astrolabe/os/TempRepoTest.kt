package io.astrolabe.os

import io.astrolabe.fixtures.FixtureSupport
import io.astrolabe.fixtures.TempRepo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.6.4: the `TempRepo` builder every later test depends on. */
class TempRepoTest {

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `create initialises a deliberate repository regardless of global config`() {
        TempRepo.create().use { repo ->
            // The developer machine has core.autocrlf=true globally; the fixture must not inherit it.
            assertEquals("false", repo.readConfig("core.autocrlf"))
            assertEquals(TempRepo.IDENTITY.name, repo.readConfig("user.name"))
            assertEquals(TempRepo.IDENTITY.email, repo.readConfig("user.email"))
            assertEquals("main", repo.git.status().branch?.head)
            assertTrue(Files.isDirectory(repo.resolve(".git")))
        }
    }

    @Test
    fun `create accepts a caller-supplied directory and then leaves it in place`() {
        val dir = scratch.resolve("given")
        TempRepo.create(dir).use { repo ->
            repo.write("a.txt", "alpha\n")
            repo.commit("base")
            assertEquals(dir.toAbsolutePath().normalize(), repo.root)
        }
        // close() only deletes directories the fixture created itself.
        assertTrue(Files.exists(dir.resolve(".git")))
    }

    @Test
    fun `close deletes a fixture-owned directory`() {
        val repo = TempRepo.create()
        repo.write("a.txt", "alpha\n")
        repo.commit("base")
        val root = repo.root
        assertTrue(Files.exists(root))
        repo.close()
        assertTrue(!Files.exists(root), "close() must delete the read-only loose objects too")
    }

    @Test
    fun `write keeps bytes verbatim and commit produces a reachable commit`() {
        TempRepo.create().use { repo ->
            val raw = "keep\r\nthese\r\n".toByteArray(StandardCharsets.US_ASCII)
            repo.write("crlf.txt", raw)
            assertContentEquals(raw, Files.readAllBytes(repo.resolve("crlf.txt")))

            val first = repo.commit("first")
            repo.write("b.txt", "beta\n")
            val second = repo.commit("second")

            assertEquals(second, repo.git.revParse("HEAD"))
            assertEquals(first, repo.git.revParse("HEAD~1"))
            assertTrue(repo.git.status().isClean)
        }
    }

    @Test
    fun `stage, modify and untracked produce the three dirty shapes`() {
        TempRepo.create().use { repo ->
            repo.write("tracked.txt", "one\n")
            repo.write("staged.txt", "two\n")
            repo.commit("base")

            repo.write("staged.txt", "two changed\n")
            repo.stage("staged.txt")
            repo.modify("tracked.txt", "one changed\n")
            repo.untracked("fresh.txt", "new\n")

            val kinds = repo.git.status().entries.associateBy { it.path }
            assertEquals(
                StatusCode.MODIFIED,
                (kinds.getValue("staged.txt") as StatusEntry.Ordinary).index,
            )
            assertEquals(
                StatusCode.MODIFIED,
                (kinds.getValue("tracked.txt") as StatusEntry.Ordinary).worktree,
            )
            assertTrue(kinds.getValue("fresh.txt") is StatusEntry.Untracked)

            assertFailsWith<IllegalStateException> { repo.modify("absent.txt", "x\n") }
        }
    }

    @Test
    fun `gitattributes and cleanFilter compose instead of overwriting`() {
        TempRepo.create().use { repo ->
            repo.gitattributes("* text=auto eol=lf\n")
            repo.cleanFilter("up", "sed s/a/A/", "*.txt")
            val attributes = Files.readString(repo.resolve(".gitattributes"))
            assertTrue(attributes.contains("* text=auto eol=lf"), attributes)
            assertTrue(attributes.contains("*.txt filter=up"), attributes)
            assertEquals("sed s/a/A/", repo.readConfig("filter.up.clean"))
        }
    }

    @Test
    fun `crlfVariant switches the repository to autocrlf`() {
        TempRepo.create().use { repo ->
            assertEquals("false", repo.readConfig("core.autocrlf"))
            assertEquals(repo, repo.crlfVariant())
            assertEquals("true", repo.readConfig("core.autocrlf"))
        }
    }

    @Test
    fun `resolve refuses absolute and escaping paths`() {
        TempRepo.create().use { repo ->
            assertEquals(repo.root.resolve("a").resolve("b.txt"), repo.resolve("a/b.txt"))
            assertFailsWith<IllegalArgumentException> { repo.resolve("/abs.txt") }
            assertFailsWith<IllegalArgumentException> { repo.resolve("../escape.txt") }
        }
    }

    @Test
    fun `host-dependent helpers report a typed outcome instead of throwing`() {
        TempRepo.create().use { repo ->
            repo.write("alias.txt", "x\n")

            // Both outcomes are legitimate: Windows refuses symlinks without the privilege, and a
            // case-sensitive filesystem has no alias. P1.2.6 consumes this and skips.
            val symlink = repo.symlinkAncestor("link", "real")
            when (symlink) {
                is FixtureSupport.Created -> assertTrue(Files.isSymbolicLink(symlink.path))
                is FixtureSupport.Unsupported -> assertTrue(symlink.reason.isNotBlank())
            }

            val alias = repo.caseAlias("alias.txt")
            when (alias) {
                is FixtureSupport.Created -> {
                    assertEquals("ALIAS.TXT", alias.path.fileName.toString())
                    assertContentEquals(
                        Files.readAllBytes(repo.resolve("alias.txt")),
                        Files.readAllBytes(alias.path),
                    )
                }

                is FixtureSupport.Unsupported -> assertTrue(alias.reason.isNotBlank())
            }

            assertTrue(repo.caseAlias("absent.txt") is FixtureSupport.Unsupported)
        }
    }

    /** Reads one local config value through a plain git call, independent of the wrapper. */
    private fun TempRepo.readConfig(key: String): String {
        val process = ProcessBuilder(listOf("git", "config", "--local", "--get", key))
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        assertEquals(0, process.waitFor(), "git config --get $key failed: $output")
        return output.trim()
    }
}
