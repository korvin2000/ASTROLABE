package io.astrolabe.evallive

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.LongSupplier

/** File trees of a run: the copy of a task's base, the acceptance copy, their removal. */
internal object Trees {
    /** Copies the tree under [from] into [to]; a top-level entry named in [skipTop] is left out with everything below it. */
    fun copy(from: Path, to: Path, skipTop: Set<String> = emptySet()) {
        Files.createDirectories(to)
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = from.relativize(dir)
                if (relative.nameCount == 1 && relative.toString().isNotEmpty() && relative.toString() in skipTop) return FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(to.resolve(relative.toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = from.relativize(file)
                if (relative.nameCount == 1 && relative.toString() in skipTop) return FileVisitResult.CONTINUE
                Files.copy(file, to.resolve(relative.toString()), StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Removes [root] and everything below it; read-only files (git objects on Windows) are made writable first. */
    fun delete(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                remove(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                remove(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun remove(path: Path) {
        var attempt = 0
        while (true) {
            try {
                path.toFile().setWritable(true)
                Files.deleteIfExists(path)
                return
            } catch (e: IOException) {
                // A process the acceptance or the agent started may hold a handle for a moment after it was killed (Windows).
                if (++attempt >= 5) throw e
                Thread.sleep(200L * attempt)
            }
        }
    }
}

/** A finished child process: [exitCode] is null when it was killed at its deadline. */
internal data class ProcResult(val exitCode: Int?, val timedOut: Boolean, val output: String, val durationMillis: Long)

internal object Proc {
    /** Runs [argv] in [cwd] with stdin closed and stderr merged into stdout; past [timeout] the process tree is killed. */
    fun run(argv: List<String>, cwd: Path, timeout: Duration, env: Map<String, String> = emptyMap(), nanos: LongSupplier = LongSupplier(System::nanoTime)): ProcResult {
        val builder = ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true)
        builder.environment().putAll(env)
        val start = nanos.asLong
        val process = builder.start()
        process.outputStream.close()
        val buffer = ByteArrayOutputStream()
        val reader = Thread.ofVirtual().start { process.inputStream.use { it.transferTo(buffer) } }
        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        // An orphaned grandchild may keep the pipe open; its output is not waited for beyond this.
        reader.join(Duration.ofSeconds(10))
        val output = synchronized(buffer) { buffer.toString(StandardCharsets.UTF_8) }
        val millis = (nanos.asLong - start) / 1_000_000
        return ProcResult(if (finished) process.exitValue() else null, !finished, output, millis)
    }
}

/** What a run's diff is taken against: the base commit, or the base tree with the runner's own [index] when there is no commit. */
internal data class GitBase(val rev: String, val index: Path?) {
    val env: Map<String, String> get() = index?.let { mapOf(INDEX_FILE to it.toString()) } ?: emptyMap()

    companion object {
        const val INDEX_FILE: String = "GIT_INDEX_FILE"
    }
}

/** The git repository a run's workspace becomes: the core works on a repository, and the base commit is what the diff is taken against. */
internal object GitRepo {
    private val settings = listOf(
        "-c", "user.name=eval-live", "-c", "user.email=eval-live@localhost", "-c", "commit.gpgsign=false",
        "-c", "core.autocrlf=false", "-c", "init.defaultBranch=main",
    )

    /** Initialises [dir] and commits its whole content as the base; returns the base commit. */
    fun initWithBase(dir: Path): GitBase {
        git(dir, "init", "-q")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "--no-verify", "-m", "base")
        return GitBase(git(dir, "rev-parse", "HEAD").trim(), null)
    }

    /**
     * Initialises [dir] as a repository without a commit, its content untracked (WP-B7). The base is the tree of that
     * content, written through the runner's own [index] outside the workspace: the repository's index stays empty.
     */
    fun initWithoutCommit(dir: Path, index: Path): GitBase {
        git(dir, "init", "-q")
        val env = mapOf(GitBase.INDEX_FILE to index.toAbsolutePath().toString())
        git(dir, "add", "-A", env = env)
        return GitBase(git(dir, "write-tree", env = env).trim(), index.toAbsolutePath())
    }

    /** Every change of [dir] against [base], untracked files included, as a binary-safe patch. */
    fun diff(dir: Path, base: GitBase): String {
        git(dir, "add", "-A", env = base.env)
        return git(dir, "diff", "--cached", "--binary", base.rev, env = base.env)
    }

    /** The paths of [dir] changed against [base], untracked ones included, without touching the repository's index. */
    fun changedFiles(dir: Path, base: GitBase): List<String> {
        if (base.index != null) {
            git(dir, "add", "-A", env = base.env)
            return git(dir, "diff", "--cached", "--name-only", base.rev, env = base.env).lines().map { it.trim() }.filter { it.isNotEmpty() }.sorted()
        }
        val tracked = git(dir, "diff", "--name-only", base.rev).lines()
        val untracked = git(dir, "ls-files", "--others", "--exclude-standard").lines()
        return (tracked + untracked).map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    /** Whether [dir]'s repository has a commit. */
    fun hasCommit(dir: Path): Boolean = Proc.run(listOf("git") + settings + listOf("rev-parse", "--verify", "-q", "HEAD"), dir, Duration.ofMinutes(2)).exitCode == 0

    private fun git(dir: Path, vararg args: String, env: Map<String, String> = emptyMap()): String {
        val result = Proc.run(listOf("git") + settings + args, dir, Duration.ofMinutes(2), env)
        check(result.exitCode == 0) { "git ${args.first()} failed in $dir (exit ${result.exitCode}): ${result.output.take(2000)}" }
        return result.output
    }
}
