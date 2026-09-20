package io.astrolabe.os

import io.astrolabe.fixtures.TempRepo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.6.2: the `Git` wrapper exercised against real temporary repositories. */
class GitTest {

    @TempDir
    lateinit var scratch: Path

    // ------------------------------------------------------------- version

    @Test
    fun `version reports the local git and enforces the tested minimum`() {
        TempRepo.create().use { repo ->
            val version = repo.git.version()
            assertTrue(version.major >= 2, "unexpected git major version: ${version.raw}")
            assertTrue(version.raw.startsWith("git version"), "raw line kept: '${version.raw}'")

            // D-04: 2.20 is a tested floor, not a substitute for the feature tests below.
            assertEquals(version, repo.git.requireMinimum(2, 20))
            assertFailsWith<IllegalStateException> { repo.git.requireMinimum(99, 0) }
        }
    }

    @Test
    fun `a failing command raises a typed error carrying argv, exit code and stderr`() {
        TempRepo.create().use { repo ->
            val failure = assertFailsWith<GitError> { repo.git.revParse("no-such-revision") }
            assertNotEquals(0, failure.exitCode)
            assertTrue(failure.argv.first().endsWith("git"), "argv keeps the executable: ${failure.argv}")
            assertTrue(failure.argv.contains("no-such-revision"), "argv kept: ${failure.argv}")
            assertTrue(failure.stderr.isNotBlank(), "stderr captured: '${failure.stderr}'")
        }
    }

    // -------------------------------------------------------------- status

    @Test
    fun `status reports modified, staged, untracked and renamed paths`() {
        TempRepo.create().use { repo ->
            repo.write("tracked.txt", "one\n")
            repo.write("staged.txt", "two\n")
            repo.write("doomed.txt", "three\n")
            val head = repo.commit("base")

            Files.move(
                repo.resolve("doomed.txt"),
                repo.resolve("moved.txt"),
                StandardCopyOption.ATOMIC_MOVE,
            )
            repo.stage(".")
            repo.write("staged.txt", "two changed\n")
            repo.stage("staged.txt")
            repo.modify("tracked.txt", "one changed\n")
            repo.untracked("fresh.txt", "new\n")

            val status = repo.git.status()

            assertEquals(head, status.branch?.oid)
            assertEquals("main", status.branch?.head)
            assertNull(status.branch?.upstream)

            val tracked = assertIs<StatusEntry.Ordinary>(status.entryFor("tracked.txt"))
            assertEquals(StatusCode.UNMODIFIED, tracked.index)
            assertEquals(StatusCode.MODIFIED, tracked.worktree)
            assertEquals(FileMode.REGULAR, tracked.worktreeMode)
            assertEquals(SubmoduleState.NOT_A_SUBMODULE, tracked.submodule)

            val staged = assertIs<StatusEntry.Ordinary>(status.entryFor("staged.txt"))
            assertEquals(StatusCode.MODIFIED, staged.index)
            assertEquals(StatusCode.UNMODIFIED, staged.worktree)
            assertNotEquals(staged.headId, staged.indexId)

            assertIs<StatusEntry.Untracked>(status.entryFor("fresh.txt"))
            assertEquals(listOf("fresh.txt"), status.untracked)

            val renamed = assertIs<StatusEntry.Renamed>(status.entryFor("moved.txt"))
            assertEquals("doomed.txt", renamed.origPath)
            assertEquals(ChangeOrigin.RENAME, renamed.origin)
            assertEquals(100, renamed.similarityPercent)
            assertEquals(StatusCode.RENAMED, renamed.index)

            assertTrue(!status.isClean)
        }
    }

    @Test
    fun `status on an unborn branch has no head commit`() {
        TempRepo.create().use { repo ->
            val status = repo.git.status()
            assertNull(status.branch?.oid, "an unborn branch prints '(initial)'")
            assertEquals("main", status.branch?.head)
            assertTrue(status.isClean)
        }
    }

    @Test
    fun `status reports ignored paths only when asked`() {
        TempRepo.create().use { repo ->
            repo.write(".gitignore", "build/\n")
            repo.commit("base")
            repo.write("build/out.txt", "x\n")

            assertTrue(repo.git.status().entries.none { it is StatusEntry.Ignored })
            val withIgnored = repo.git.status(includeIgnored = true)
            assertTrue(
                withIgnored.entries.filterIsInstance<StatusEntry.Ignored>().any { it.path.startsWith("build") },
                "expected an ignored entry, got ${withIgnored.entries}",
            )
        }
    }

    // -------------------------------------- raw hashing (D-53) and cat-file

    @Test
    fun `raw hashing bypasses the clean filter and end-of-line conversion`() {
        TempRepo.create().use { repo ->
            repo.gitattributes("* text=auto eol=lf\n")
            // `sed` ships with git on every supported host, so the filter is portable.
            repo.cleanFilter(name = "up", command = "sed s/a/A/", glob = "*.txt")

            val raw = "abc\r\ndef\r\n".toByteArray(StandardCharsets.US_ASCII)
            repo.write("f.txt", raw)

            // First prove the filter and the eol rule are live for an ordinary `git add`.
            repo.stage("f.txt")
            val stagedId = repo.git.lsFiles(listOf("f.txt")).single().id
            assertContentEquals(
                "Abc\ndef\n".toByteArray(StandardCharsets.US_ASCII),
                repo.git.catFile(stagedId),
                "the configured clean filter and eol=lf must both have run for `git add`",
            )

            // D-53: the raw path must produce sha1("blob <len>\0" + bytes) instead.
            val rawId = repo.git.hashObject(raw, noFilters = true, write = true)
            assertEquals(gitBlobSha1(raw), rawId.hex)
            assertNotEquals(stagedId, rawId, "the filtered and raw ids must differ")
            assertContentEquals(raw, repo.git.catFile(rawId), "cat-file must return the raw bytes")
        }
    }

    @Test
    fun `raw hashing survives autocrlf and arbitrary binary content`() {
        TempRepo.create().use { repo ->
            repo.crlfVariant()
            val binary = byteArrayOf(0, 13, 10, -1, -2, 65, 13, 10, 0, 127)
            val id = repo.git.hashObject(binary, write = true)
            assertEquals(gitBlobSha1(binary), id.hex)
            assertContentEquals(binary, repo.git.catFile(id))
        }
    }

    @Test
    fun `hash object without write does not store the object`() {
        TempRepo.create().use { repo ->
            val bytes = "unstored\n".toByteArray(StandardCharsets.US_ASCII)
            val id = repo.git.hashObject(bytes, write = false)
            assertEquals(gitBlobSha1(bytes), id.hex)
            assertFailsWith<GitError> { repo.git.catFile(id) }
        }
    }

    // ---------------------------------- temporary index round trip (D-04/53)

    @Test
    fun `temp index round trip commits a tree and leaves the user index untouched`() {
        TempRepo.create().use { repo ->
            repo.write("tracked.txt", "one\n")
            repo.write("staged.txt", "two\n")
            repo.commit("base")
            repo.write("staged.txt", "two changed\n")
            repo.stage("staged.txt")
            repo.modify("tracked.txt", "one changed\n")
            repo.untracked("fresh.txt", "new\n")

            val indexPath = repo.resolve(".git/index")
            val userIndexBefore = Files.readAllBytes(indexPath)
            val statusBefore = repo.git.status()
            val lsFilesBefore = repo.git.lsFiles()

            val oneId = repo.git.hashObject("snapshot one\n".toByteArray(StandardCharsets.UTF_8), write = true)
            val twoId = repo.git.hashObject("snapshot two\n".toByteArray(StandardCharsets.UTF_8), write = true)
            val tempIndex = scratch.resolve("snapshot/astrolabe.index")

            repo.git.updateIndex(
                tempIndex,
                listOf(
                    IndexEntry(FileMode.REGULAR, oneId, "one.txt"),
                    IndexEntry(FileMode.EXECUTABLE, twoId, "nested/two.sh"),
                ),
            )
            val tree = repo.git.writeTree(tempIndex)

            // git orders tree entries by name with directories sorted as if trailing-slashed,
            // so `nested/` precedes `one.txt`.
            assertEquals(
                listOf(
                    TreeEntry(FileMode.EXECUTABLE, TreeEntryKind.BLOB, twoId, "nested/two.sh"),
                    TreeEntry(FileMode.REGULAR, TreeEntryKind.BLOB, oneId, "one.txt"),
                ),
                repo.git.lsTree(tree, recursive = true),
            )

            val commit = repo.git.commitTree(
                tree = tree,
                parents = emptyList(),
                message = "snapshot",
                authorIdentity = TempRepo.IDENTITY,
            )
            val ref = "refs/astrolabe/work/attempt/workspace/head"
            assertNull(repo.git.readRef(ref))

            // expectedOld = null means "must not exist" (the all-zero id).
            repo.git.updateRef(ref, commit, expectedOld = null)
            assertEquals(commit, repo.git.readRef(ref))

            val second = repo.git.commitTree(
                tree = tree,
                parents = listOf(commit),
                message = "snapshot 2",
                authorIdentity = TempRepo.IDENTITY,
            )

            // A wrong expected id is refused and the ref does not move.
            val wrong = assertFailsWith<RefUpdateRejected> {
                repo.git.updateRef(ref, second, expectedOld = oneId)
            }
            assertEquals(ref, wrong.ref)
            assertEquals(oneId, wrong.expectedOld)
            assertNotEquals(0, wrong.exitCode)
            assertEquals(commit, repo.git.readRef(ref), "a refused update must not move the ref")

            // "Must not exist" against a ref that does exist is the same rejection.
            val exists = assertFailsWith<RefUpdateRejected> {
                repo.git.updateRef(ref, second, expectedOld = null)
            }
            assertNull(exists.expectedOld)
            assertEquals(commit, repo.git.readRef(ref))

            // The correct expected id moves it.
            repo.git.updateRef(ref, second, expectedOld = commit)
            assertEquals(second, repo.git.readRef(ref))

            // D-53: none of the above may read or write the user's staged index.
            assertContentEquals(userIndexBefore, Files.readAllBytes(indexPath), ".git/index must be byte-identical")
            assertEquals(statusBefore, repo.git.status())
            assertEquals(lsFilesBefore, repo.git.lsFiles())
        }
    }

    @Test
    fun `update index replaces the temporary index rather than adding to it`() {
        TempRepo.create().use { repo ->
            repo.write("seed.txt", "seed\n")
            repo.commit("base")
            val tempIndex = scratch.resolve("replace.index")
            val first = repo.git.hashObject("first\n".toByteArray(StandardCharsets.UTF_8), write = true)
            val second = repo.git.hashObject("second\n".toByteArray(StandardCharsets.UTF_8), write = true)

            repo.git.updateIndex(tempIndex, listOf(IndexEntry(FileMode.REGULAR, first, "first.txt")))
            assertEquals(listOf("first.txt"), repo.git.lsTree(repo.git.writeTree(tempIndex)).map { it.path })

            repo.git.updateIndex(tempIndex, listOf(IndexEntry(FileMode.REGULAR, second, "second.txt")))
            assertEquals(
                listOf("second.txt"),
                repo.git.lsTree(repo.git.writeTree(tempIndex)).map { it.path },
                "the temporary index is initialised deliberately from the manifest (D-53)",
            )
        }
    }

    @Test
    fun `update index refuses the repository index`() {
        TempRepo.create().use { repo ->
            repo.write("seed.txt", "seed\n")
            repo.commit("base")
            val id = repo.git.hashObject("x\n".toByteArray(StandardCharsets.UTF_8), write = true)
            val failure = assertFailsWith<IllegalArgumentException> {
                repo.git.updateIndex(
                    repo.resolve(".git/index"),
                    listOf(IndexEntry(FileMode.REGULAR, id, "x.txt")),
                )
            }
            assertTrue(failure.message.orEmpty().contains("D-53"), "message names the decision: ${failure.message}")
        }
    }

    // -------------------------------------------- unsupported forms (D-53)

    @Test
    fun `submodule repositories are refused by name`() {
        TempRepo.create().use { repo ->
            repo.write("seed.txt", "seed\n")
            repo.commit("base")
            assertTrue(repo.git.unsupportedForms().isEmpty())

            repo.write(".gitmodules", "[submodule \"x\"]\n\tpath = x\n\turl = ./x\n")
            assertEquals(setOf(RepositoryForm.SUBMODULES), repo.git.unsupportedForms())

            val refused = assertFailsWith<UnsupportedRepositoryForm> { repo.git.status() }
            assertEquals(RepositoryForm.SUBMODULES, refused.form)
            assertEquals("status", refused.operation)
            assertFailsWith<UnsupportedRepositoryForm> { repo.git.lsFiles() }
            assertFailsWith<UnsupportedRepositoryForm> { repo.git.writeTree(scratch.resolve("i")) }
        }
    }

    @Test
    fun `sparse checkouts are refused by name`() {
        TempRepo.create().use { repo ->
            repo.write("seed.txt", "seed\n")
            repo.commit("base")
            repo.config("core.sparseCheckout", "true")

            assertEquals(setOf(RepositoryForm.SPARSE_CHECKOUT), repo.git.unsupportedForms())
            val refused = assertFailsWith<UnsupportedRepositoryForm> {
                repo.git.updateIndex(scratch.resolve("i"), emptyList())
            }
            assertEquals(RepositoryForm.SPARSE_CHECKOUT, refused.form)
            assertTrue(refused.message.orEmpty().contains("D-53"))
        }
    }

    // ------------------------------------------------- remaining read paths

    @Test
    fun `ls-files, diff and show read the repository`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "alpha\n")
            repo.write("dir/b.txt", "beta\n")
            val head = repo.commit("base")

            val listed = repo.git.lsFiles()
            assertEquals(listOf("a.txt", "dir/b.txt"), listed.map { it.path })
            assertTrue(listed.all { it.stage == 0 && it.mode == FileMode.REGULAR })

            assertEquals(listOf("a.txt"), repo.git.lsFiles(listOf("a.txt")).map { it.path })

            repo.modify("a.txt", "alpha changed\n")
            val worktreeDiff = repo.git.diff()
            assertTrue(worktreeDiff.contains("-alpha"), "unstaged diff: $worktreeDiff")
            assertTrue(worktreeDiff.contains("+alpha changed"), "unstaged diff: $worktreeDiff")
            assertEquals("", repo.git.diff(cached = true), "nothing is staged yet")

            repo.stage("a.txt")
            assertTrue(repo.git.diff(cached = true).contains("+alpha changed"))
            assertEquals("", repo.git.diff(pathspec = listOf("dir/b.txt")))

            val shown = repo.git.show(head)
            assertTrue(shown.contains("base"), "show prints the commit message: $shown")
            assertEquals(head, repo.git.revParse("HEAD"))
        }
    }

    @Test
    fun `ls-tree reads nested trees and is flattened on request`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "alpha\n")
            repo.write("dir/b.txt", "beta\n")
            val head = repo.commit("base")
            val tree = repo.git.lsTree(head)

            assertEquals(listOf("a.txt", "dir"), tree.map { it.path })
            assertEquals(FileMode.TREE, tree.single { it.path == "dir" }.mode)
            assertEquals(TreeEntryKind.TREE, tree.single { it.path == "dir" }.kind)
            assertEquals(
                listOf("a.txt", "dir/b.txt"),
                repo.git.lsTree(head, recursive = true).map { it.path },
            )
        }
    }

    @Test
    fun `read ref answers null for an absent ref and never falls back to a revision`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "alpha\n")
            val head = repo.commit("base")
            assertEquals(head, repo.git.readRef("refs/heads/main"))
            assertNull(repo.git.readRef("refs/astrolabe/absent"))
            // "HEAD" is a revision, not a full ref name that show-ref --verify accepts.
            assertNull(repo.git.readRef("HEAD~1"))
        }
    }

    @Test
    fun `worktree add and remove manage a detached checkout`() {
        TempRepo.create().use { repo ->
            repo.write("a.txt", "alpha\n")
            val head = repo.commit("base")
            val linked = scratch.resolve("linked")

            repo.git.worktreeAdd(linked, head)
            assertTrue(Files.exists(linked.resolve("a.txt")), "the worktree was checked out")
            assertEquals("alpha\n", Files.readString(linked.resolve("a.txt")))

            repo.git.worktreeRemove(linked)
            assertTrue(!Files.exists(linked), "the worktree directory was removed")
        }
    }

    private fun GitStatus.entryFor(path: String): StatusEntry =
        entries.singleOrNull { it.path == path }
            ?: throw AssertionError("no single status entry for '$path' in $entries")

    private fun gitBlobSha1(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size} ".toByteArray(StandardCharsets.US_ASCII))
        digest.update(bytes)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
