package io.astrolabe.os.search

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes

/**
 * A fixture tree the two backends are compared on: LF and CRLF files, Unicode text, a binary file,
 * a hidden file, a `.gitignore`d file and an ignored directory, nested directories and an empty
 * file. Every excluded file contains the word the tests search for, so an exclusion that silently
 * stops working shows up as an extra hit.
 */
internal class SearchFixture private constructor(val root: Path, val isGitRepo: Boolean) {

    /** Candidate files, in the canonical order both backends use, when the tree is a git repo. */
    val gitCandidates: List<String> = listOf(
        "empty.txt", "meta.txt", "numbers.txt", "src/a.txt", "src/crlf.txt", "src/nested/deep.txt", "uni.txt",
    )

    /** Without git there are no ignore rules, so the two ignored files join the candidate list. */
    val plainCandidates: List<String> = (gitCandidates + listOf("build/out.txt", "notes.log")).sorted()

    fun symlinkOrNull(target: String, link: String): Path? =
        try {
            val path = root.resolve(link)
            Files.createSymbolicLink(path, root.resolve(target))
            path
        } catch (_: IOException) {
            null
        } catch (_: UnsupportedOperationException) {
            null
        }

    fun delete() {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { path ->
            try {
                path.toFile().setWritable(true)
                path.deleteIfExists()
            } catch (_: IOException) {
                // A leftover git lock file is not worth failing a test over.
            }
        }
    }

    companion object {
        /** The tree, initialised as a git repository so `.gitignore` rules are live. */
        fun gitRepo(): SearchFixture {
            val root = newRoot()
            writeTree(root)
            val initialised = git(root, "init", "--quiet") && git(root, "add", "--all", ".")
            check(initialised) { "could not initialise a git repository at $root" }
            return SearchFixture(root, isGitRepo = true)
        }

        /** The same tree with no repository, which exercises the tree-walk branch. */
        fun plainTree(): SearchFixture {
            val root = newRoot()
            writeTree(root)
            return SearchFixture(root, isGitRepo = false)
        }

        private fun newRoot(): Path = Files.createTempDirectory("astrolabe-search-")
            .toRealPath()

        private fun writeTree(root: Path) {
            write(root, ".gitignore", "*.log\nbuild/\n")
            write(root, ".secret", "hidden beta\n")
            write(root, "notes.log", "ignored beta\n")
            write(root, "build/out.txt", "ignored beta\n")
            write(root, "empty.txt", "")
            // Literal vs regex ('a.txt' / 'a\.txt') and the word boundary in '\bgamma\b'.
            write(root, "meta.txt", "call a.txt here\ncall axtxt here\nzetagamma once\n")
            write(root, "numbers.txt", "a1 b22 c333\nno digits here\n")
            write(root, "src/a.txt", "alpha beta\nBETA gamma\nbeta beta\n")
            write(root, "src/crlf.txt", "alpha beta\r\nBETA gamma\r\n")
            write(root, "src/nested/deep.txt", "nested beta\n")
            write(root, "uni.txt", "café naïve beta\nCAFÉ NAÏVE BETA\n")
            root.resolve("bin.dat").writeBytes(
                "beta".toByteArray(UTF_8) + byteArrayOf(0, 1, 2) + "binary\n".toByteArray(UTF_8),
            )
        }

        private fun write(root: Path, relPath: String, content: String) {
            val path = root.resolve(relPath)
            path.parent?.createDirectories()
            path.writeBytes(content.toByteArray(UTF_8))
        }

        private fun git(root: Path, vararg args: String): Boolean =
            try {
                val process = ProcessBuilder(listOf("git") + args)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.use { it.readBytes() }
                process.waitFor() == 0
            } catch (_: IOException) {
                false
            }

        /** True when `git` can be run at all; the git-repo fixture needs it. */
        fun gitAvailable(): Boolean =
            try {
                val process = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() }
                process.waitFor() == 0
            } catch (_: IOException) {
                false
            }
    }
}

/** The hits every backend must return; `null` when the outcome carries no hits. */
internal fun SearchOutcome.hitsOrNull(): Hits? = when (this) {
    is SearchOutcome.Found -> hits
    is SearchOutcome.Incomplete -> hits
    else -> null
}

internal fun SearchOutcome.requireHits(): Hits =
    hitsOrNull() ?: error("expected an outcome carrying hits, got $this")

/** `path:line:column:text` for readable assertion failures. */
internal fun Hits.rendered(): List<String> = hits.map { "${it.path}:${it.line}:${it.column}:${it.text}" }
