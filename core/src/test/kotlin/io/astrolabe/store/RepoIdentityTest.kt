package io.astrolabe.store

import io.astrolabe.fixtures.TempRepo
import io.astrolabe.os.Git
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: the repository identity that keys the external project state root (D-44). */
class RepoIdentityTest {

    @TempDir
    lateinit var scratch: Path

    @Test
    fun `two sibling repositories get different identities`() {
        TempRepo.create().use { first ->
            TempRepo.create().use { second ->
                first.write("a.txt", "first")
                first.commit("first")
                second.write("a.txt", "second")
                second.commit("second")

                val a = RepoIdentity.of(first.git)
                val b = RepoIdentity.of(second.git)
                assertNotEquals(a.digest, b.digest, "sibling repositories must not share a state root")
                assertNotEquals(a.commonDir, b.commonDir)
            }
        }
    }

    @Test
    fun `a linked worktree resolves to the identity of its repository`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "one")
            val commit = repo.commit("one")
            val linked = scratch.resolve("linked")
            repo.git.worktreeAdd(linked, commit)
            try {
                val main = RepoIdentity.of(repo.git)
                val worktree = RepoIdentity.of(Git(linked))
                assertEquals(main, worktree, "all worktrees of a repository share one project store")
                assertEquals(listOf(commit), main.roots)
            } finally {
                repo.git.worktreeRemove(linked, force = true)
            }
        }
    }

    @Test
    fun `an unborn repository has no roots and is keyed by its common directory`() {
        TempRepo.create().use { repo ->
            val identity = RepoIdentity.of(repo.git)
            assertTrue(identity.roots.isEmpty(), "an unborn repository has no root commit")
            assertEquals(identity, RepoIdentity.of(emptyList(), identity.commonDir))
            assertNotEquals(
                identity.digest,
                RepoIdentity.of(emptyList(), identity.commonDir + "-other").digest,
            )
        }
    }

    @Test
    fun `every root commit takes part and the order of the roots does not`() {
        val x = "0".repeat(40)
        val y = "1".repeat(40)
        val roots = listOf(io.astrolabe.os.ObjectId(y), io.astrolabe.os.ObjectId(x))
        val forward = RepoIdentity.of(roots, "/repo/.git")
        val reversed = RepoIdentity.of(roots.reversed(), "/repo/.git")
        assertEquals(forward, reversed, "the roots are sorted before hashing")
        assertEquals(listOf(io.astrolabe.os.ObjectId(x), io.astrolabe.os.ObjectId(y)), forward.roots)
        assertNotEquals(
            forward.digest,
            RepoIdentity.of(listOf(io.astrolabe.os.ObjectId(x)), "/repo/.git").digest,
            "dropping a root must change the identity",
        )
    }

    @Test
    fun `the common directory is canonicalised to forward slashes and a lowercase drive`() {
        val backslashes = RepoIdentity.of(emptyList(), "C:\\work\\repo\\.git")
        val slashes = RepoIdentity.of(emptyList(), "c:/work/repo/.git")
        assertEquals("c:/work/repo/.git", backslashes.commonDir)
        assertEquals(slashes.digest, backslashes.digest)
    }

    @Test
    fun `the directory name is the full digest`() {
        val identity = RepoIdentity.of(emptyList(), "/repo/.git")
        assertEquals(identity.digest.hex, identity.directoryName)
        assertEquals(64, identity.directoryName.length)
    }
}
