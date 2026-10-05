package io.astrolabe.workflow

import io.astrolabe.cell.WINDOWS
import io.astrolabe.contract.Command
import io.astrolabe.fixtures.TempRepo
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant

/**
 * The "dirty repository" of plan §7.2 (W0), generated per test and never committed: one commit of a few sources, a
 * recorded test output and a tracked data file; **no `.gitignore`**; an untracked tool directory of [untrackedFiles]
 * small files (the `devtools/` of the 5 October runs) plus one file of [BIG_BYTES]. The [variant] decides what the
 * fixture's [check] does to the tree besides printing a passing pytest run.
 */
internal class DirtyRepo private constructor(val repo: TempRepo, val untrackedFiles: Int, val variant: Variant) : AutoCloseable {
    enum class Variant {
        /** The check only prints. */
        Plain,

        /** (a) The check also writes scratch output under `build/`. */
        ScratchOutput,

        /** (b) The check also rewrites the tracked [DATA] file (WD-14). */
        RewritesData,

        /** (c) [lock] can make [LOCKED] unreadable while the scenario runs (WD-05). */
        LockedFile,
    }

    val root: Path get() = repo.root

    /** The fixture's test command, in the platform's shell form, without inner quotes (the cmd.exe quoting rule). */
    val check: Command = when (variant) {
        Variant.ScratchOutput -> shell(
            "if not exist build mkdir build & echo scratch> build\\report.txt & type $OUTPUT",
            "mkdir -p build && echo scratch > build/report.txt; cat $OUTPUT",
        )
        Variant.RewritesData -> shell("echo [2]> data\\notes.json & type $OUTPUT", "echo [2] > data/notes.json; cat $OUTPUT")
        else -> shell("type $OUTPUT", "cat $OUTPUT")
    }

    /**
     * Makes [LOCKED] unreadable until the returned handle closes: on Windows an exclusive range lock (reads through
     * another handle fail, as a Gradle lock file did in R2), elsewhere no read permission. A host where neither holds
     * (a superuser ignores permissions) gets [Lock.Unsupported] with the reason, and the test skips on it.
     */
    fun lock(): Lock {
        val file = root.resolve(LOCKED)
        if (WINDOWS) {
            val channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)
            val held = channel.lock()
            return Lock.Held { held.release(); channel.close() }
        }
        val before = Files.getPosixFilePermissions(file)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        val restore = AutoCloseable { Files.setPosixFilePermissions(file, before) }
        return if (runCatching { Files.readAllBytes(file) }.isFailure) Lock.Held(restore)
        else Lock.Unsupported("this host reads a file without read permission (superuser?)").also { restore.close() }
    }

    sealed interface Lock {
        data class Held(val handle: AutoCloseable) : Lock, AutoCloseable by handle

        data class Unsupported(val reason: String) : Lock
    }

    override fun close() = repo.close()

    companion object {
        const val UNTRACKED_DIR: String = "devtools"
        const val BIG: String = "$UNTRACKED_DIR/bundle.bin"
        const val BIG_BYTES: Int = 20 * 1024 * 1024
        const val DATA: String = "data/notes.json"
        const val LOCKED: String = "$UNTRACKED_DIR/build.lock"
        const val SOURCE: String = "src/app.py"
        const val OUTPUT: String = "pytest_pass.txt"
        private const val PER_DIRECTORY = 100

        fun create(untrackedFiles: Int = 1500, variant: Variant = Variant.Plain): DirtyRepo {
            require(untrackedFiles >= 1) { "the tool directory holds at least one file" }
            val repo = TempRepo.create()
            try {
                // No exclusion from the host either: a global core.excludesFile or a template info/exclude would hide the tool directory.
                val exclude = repo.root.resolve(".git/info/exclude")
                Files.createDirectories(exclude.parent)
                Files.write(exclude, ByteArray(0))
                repo.config("core.excludesFile", exclude.toString().replace('\\', '/'))
                repo.write(SOURCE, "def total(items):\n    return sum(items)\n")
                repo.write("src/util.py", "def clamp(x, lo, hi):\n    return max(lo, min(x, hi))\n")
                repo.write("tests/test_app.py", "from src.app import total\n\n\ndef test_total():\n    assert total([1, 2]) == 3\n")
                repo.write(DATA, "[1]\n")
                repo.write(OUTPUT, recorded("pytest-pass.txt"))
                repo.commit("initial")
                // Untracked and not ignored: no .gitignore anywhere (§7.2 fixture).
                for (n in 0 until untrackedFiles - 1) {
                    write(repo.root, "$UNTRACKED_DIR/pkg-${n / PER_DIRECTORY}/file-$n.txt", "tool file $n\n".toByteArray())
                }
                write(repo.root, LOCKED, "locked by a tool\n".toByteArray())
                write(repo.root, BIG, ByteArray(BIG_BYTES) { (it * 31 + (it ushr 11)).toByte() })
                // A real tool directory is old. Files written within the racy window (D-364) are re-read by every
                // non-fresh stamp, so the read counters would measure how fast the scenario follows the generation.
                val old = FileTime.from(Instant.now().minus(Duration.ofHours(1)))
                Files.walk(repo.root.resolve(UNTRACKED_DIR)).use { all -> all.filter(Files::isRegularFile).forEach { Files.setLastModifiedTime(it, old) } }
                return DirtyRepo(repo, untrackedFiles, variant)
            } catch (failure: Throwable) {
                repo.close()
                throw failure
            }
        }

        private fun write(root: Path, path: String, bytes: ByteArray) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }

        private fun shell(windows: String, posix: String): Command =
            if (WINDOWS) Command(listOf("cmd.exe", "/d", "/s", "/c", windows)) else Command(listOf("/bin/sh", "-c", posix))

        private fun recorded(name: String): String =
            DirtyRepo::class.java.getResourceAsStream("/shaper/$name")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
