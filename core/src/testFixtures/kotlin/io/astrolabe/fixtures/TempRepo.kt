package io.astrolabe.fixtures

import io.astrolabe.os.Git
import io.astrolabe.os.Identity
import io.astrolabe.os.ObjectId
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * A throwaway git repository for harness tests (TODO P0.6.4).
 *
 * The repository is initialised deliberately: a fixed identity and `core.autocrlf=false` are set
 * in the repository's *local* config so that fixtures never depend on the developer's global
 * settings (this machine has `core.autocrlf=true` globally). Variants such as [crlfVariant] and
 * [cleanFilter] change that local config on purpose.
 *
 * Staging here uses plain `git add` / `git commit` on purpose: this builds test scenarios, it is
 * not harness behaviour. The harness wrapper [Git] never touches a user index.
 */
public class TempRepo private constructor(
    public val root: Path,
    private val ownsDirectory: Boolean,
) : AutoCloseable {

    /** The wrapper under test, bound to this repository. */
    public val git: Git = Git(root)

    // -------------------------------------------------------------- building

    /** Writes [bytes] verbatim at the repository-relative [path], creating parent directories. */
    public fun write(path: String, bytes: ByteArray): Path {
        val target = resolve(path)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return target
    }

    /** Writes [text] as UTF-8 with no newline translation at the repository-relative [path]. */
    public fun write(path: String, text: String): Path =
        write(path, text.toByteArray(StandardCharsets.UTF_8))

    /** Stages every change (`git add -A`) and commits it, returning the new commit. */
    public fun commit(message: String): ObjectId {
        run("add", "-A")
        run("commit", "-m", message, "--no-gpg-sign")
        return git.revParse("HEAD")
    }

    /** Stages exactly one repository-relative path. */
    public fun stage(path: String) {
        run("add", "--", path)
    }

    /** Rewrites a tracked path without staging it, producing a worktree-only modification. */
    public fun modify(path: String, text: String) {
        check(Files.exists(resolve(path))) { "modify() expects a tracked path, '$path' is absent" }
        write(path, text)
    }

    /** Writes a path that is left untracked. */
    public fun untracked(path: String, text: String) {
        write(path, text)
    }

    /** Replaces the repository's `.gitattributes` with [text]. */
    public fun gitattributes(text: String) {
        write(".gitattributes", text)
    }

    /**
     * Configures `filter.<name>.clean` locally and points [glob] at it in `.gitattributes`,
     * appending to any attributes already written. The filter runs for ordinary `git add`; raw
     * hashing must bypass it (D-53).
     */
    public fun cleanFilter(name: String, command: String, glob: String) {
        run("config", "filter.$name.clean", command)
        val attributes = resolve(".gitattributes")
        val existing = if (Files.exists(attributes)) {
            Files.readString(attributes, StandardCharsets.UTF_8)
        } else {
            ""
        }
        val prefix = if (existing.isEmpty() || existing.endsWith("\n")) existing else existing + "\n"
        write(".gitattributes", prefix + "$glob filter=$name\n")
    }

    /**
     * Switches this repository to `core.autocrlf=true` and returns it, for fixtures that need the
     * conversion the default [create] deliberately disables.
     */
    public fun crlfVariant(): TempRepo {
        run("config", "core.autocrlf", "true")
        return this
    }

    /** Sets any local config key, for variants the named builders do not cover. */
    public fun config(key: String, value: String) {
        run("config", key, value)
    }

    // ---------------------------------------------- platform-dependent forms

    /**
     * Creates a directory symlink at the repository-relative [link] pointing at [target], for the
     * symlink/junction ancestor cases P1.2.6 consumes. Windows refuses symlink creation without
     * the privilege or developer mode, so this reports [FixtureSupport.Unsupported] rather than
     * throwing.
     */
    public fun symlinkAncestor(link: String, target: String): FixtureSupport {
        val targetPath = resolve(target)
        return try {
            Files.createDirectories(targetPath)
            val linkPath = resolve(link)
            Files.createDirectories(linkPath.parent)
            Files.createSymbolicLink(linkPath, targetPath)
            FixtureSupport.Created(linkPath)
        } catch (failure: IOException) {
            FixtureSupport.Unsupported("cannot create a directory symlink here: ${failure.message}")
        } catch (failure: UnsupportedOperationException) {
            FixtureSupport.Unsupported("this filesystem has no symlinks: ${failure.message}")
        }
    }

    /**
     * Returns a differently-cased spelling of [path] that resolves to the same file, or
     * [FixtureSupport.Unsupported] on a case-sensitive filesystem. [path] must already exist.
     */
    public fun caseAlias(path: String): FixtureSupport {
        val actual = resolve(path)
        if (!Files.exists(actual)) {
            return FixtureSupport.Unsupported("case alias needs an existing path, '$path' is absent")
        }
        val segments = path.split('/')
        val last = segments.last()
        val aliasLast = if (last == last.uppercase(Locale.ROOT)) {
            last.lowercase(Locale.ROOT)
        } else {
            last.uppercase(Locale.ROOT)
        }
        if (aliasLast == last) {
            return FixtureSupport.Unsupported("'$last' has no differently-cased spelling")
        }
        val alias = resolve((segments.dropLast(1) + aliasLast).joinToString("/"))
        return if (Files.exists(alias)) {
            FixtureSupport.Created(alias)
        } else {
            FixtureSupport.Unsupported("this filesystem is case-sensitive")
        }
    }

    // -------------------------------------------------------------- plumbing

    /** Resolves a repository-relative, forward-slashed path against [root]. */
    public fun resolve(path: String): Path {
        require(!path.startsWith('/')) { "expected a repository-relative path, got '$path'" }
        var resolved = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            require(segment != "..") { "path must not escape the repository: '$path'" }
            resolved = resolved.resolve(segment)
        }
        return resolved.normalize()
    }

    override fun close() {
        if (ownsDirectory) deleteRecursivelyBestEffort(root)
    }

    /**
     * Runs a raw git command in this repository. Fixtures may stage and commit; the harness
     * wrapper deliberately cannot.
     */
    private fun run(vararg argv: String) {
        runGit(root, argv.toList())
    }

    public companion object {
        /** The fixed author/committer every fixture commit uses. */
        public val IDENTITY: Identity = Identity("ASTROLABE Fixture", "fixture@astrolabe.invalid")

        /**
         * Creates a repository under [dir] (a fresh temporary directory when `null`) with a fixed
         * identity, `core.autocrlf=false` and a `main` initial branch so results do not depend on
         * the host's global config or `init.defaultBranch`.
         */
        public fun create(dir: Path? = null): TempRepo {
            val root = (dir ?: Files.createTempDirectory("astrolabe-temprepo"))
                .toAbsolutePath().normalize()
            Files.createDirectories(root)
            val owns = dir == null
            runGit(root, listOf("init", "--quiet", "."))
            // `git init -b` needs 2.28; setting HEAD directly works on every tested version and
            // keeps the branch name off the host's `init.defaultBranch`.
            runGit(root, listOf("symbolic-ref", "HEAD", "refs/heads/main"))
            runGit(root, listOf("config", "core.autocrlf", "false"))
            runGit(root, listOf("config", "commit.gpgsign", "false"))
            runGit(root, listOf("config", "user.name", IDENTITY.name))
            runGit(root, listOf("config", "user.email", IDENTITY.email))
            return TempRepo(root, owns)
        }
    }
}

/**
 * The outcome of a fixture helper whose feasibility depends on the host: creating symlinks needs a
 * privilege on Windows, and case aliases need a case-insensitive filesystem. Consumers (P1.2.6)
 * skip rather than fail when the host cannot provide the shape.
 */
public sealed interface FixtureSupport {
    public data class Created(public val path: Path) : FixtureSupport

    public data class Unsupported(public val reason: String) : FixtureSupport
}

/** A fixture git command exited non-zero. */
public class TempRepoError(message: String) : RuntimeException(message)

/**
 * Minimal `git` runner for fixture setup. It is intentionally separate from [Git]: the wrapper
 * must never grow `add`/`commit`/`init`, and fixtures need exactly those.
 */
private fun runGit(directory: Path, argv: List<String>) {
    val command = listOf("git") + argv
    val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
    val environment = builder.environment()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    environment["LC_ALL"] = "C"
    val process = builder.start()
    process.outputStream.close()
    val output = process.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        throw TempRepoError(
            "fixture git exited $exitCode in $directory: ${command.joinToString(" ")}\n$output",
        )
    }
}

/**
 * Deletes a tree, clearing the read-only bit git sets on loose objects. Windows may still hold a
 * handle open, so failures are swallowed: a leftover temp directory must not fail a test.
 */
private fun deleteRecursivelyBestEffort(root: Path) {
    if (!Files.exists(root)) return
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    file.toFile().setWritable(true)
                    runCatching { Files.deleteIfExists(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    runCatching { Files.deleteIfExists(dir) }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (_: IOException) {
        // best effort
    }
}
