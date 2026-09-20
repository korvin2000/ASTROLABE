package io.astrolabe.tool.edit

import io.astrolabe.atlas.Language
import io.astrolabe.os.Command
import io.astrolabe.os.Os
import io.astrolabe.os.ProcStatus
import io.astrolabe.os.SpawnSpec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The inline syntax verdict for one written file (D-10): a fact about that exact version, never behavioural acceptance. */
public sealed interface SyntaxResult {
    public object Ok : SyntaxResult {
        override fun toString(): String = "ok"
    }

    public data class Error(val line: Int?, val message: String) : SyntaxResult {
        override fun toString(): String = "error" + (line?.let { ":$it" } ?: "") + " $message"
    }

    /** No checker ran: unsupported language, missing interpreter, or the time box. */
    public data class NotRun(val reason: String) : SyntaxResult {
        override fun toString(): String = "not_run ($reason)"
    }
}

/** Inline syntax check after every anchored edit (§5.4 layer table); the edit tool never guesses a verdict. */
public fun interface SyntaxCheck {
    public fun check(relative: String, real: Path, language: Language): SyntaxResult
}

/**
 * Cheap language CLIs (D-10): `python -c "ast.parse(...)"` (no bytecode written into the tree) and
 * `node --check`. TypeScript is `not_run`: `node --check` never validates TypeScript (D-09), the project's
 * declared checker owns it. Any other language is `not_run` by name.
 */
public class CliSyntax(
    private val os: Os,
    private val workingDirectory: Path,
    private val logsDir: Path,
    private val python: String?,
    private val node: String?,
    private val timeBoxSeconds: Long = 20,
) : SyntaxCheck {
    private var counter = 0

    override fun check(relative: String, real: Path, language: Language): SyntaxResult {
        val argv = when (language) {
            Language.Python -> python?.let { listOf(it, "-c", PY_PARSE, real.toString()) } ?: return SyntaxResult.NotRun("no python interpreter available")
            Language.JavaScript -> node?.let { listOf(it, "--check", real.toString()) } ?: return SyntaxResult.NotRun("no node available")
            Language.TypeScript -> return SyntaxResult.NotRun("node --check never validates TypeScript; the project's type checker owns it (D-09)")
            else -> return SyntaxResult.NotRun("no inline checker for ${language.id}")
        }
        return try {
            Files.createDirectories(logsDir)
            val log = logsDir.resolve("syntax-${++counter}.log")
            var proc = os.spawn(SpawnSpec(Command.Argv(argv), workingDirectory, log, deadlineSeconds = timeBoxSeconds))
            val output = StringBuilder()
            var cursor = 0L
            while (!proc.status.isTerminal) {
                val poll = os.poll(proc, cursor, timeBoxSeconds)
                output.append(poll.text())
                cursor = poll.nextCursorBytes
                proc = proc.copy(status = poll.status)
            }
            val tail = os.poll(proc, cursor, 0)
            output.append(tail.text())
            when (val status = proc.status) {
                is ProcStatus.Exited -> if (status.exitCode == 0) SyntaxResult.Ok else SyntaxResult.Error(lineOf(output), firstMeaningfulLine(output.toString()))
                ProcStatus.DeadlineExceeded -> SyntaxResult.NotRun("syntax check exceeded the ${timeBoxSeconds}s time box")
                else -> SyntaxResult.NotRun("syntax checker ended ${status::class.simpleName?.lowercase()}")
            }
        } catch (failure: IOException) {
            SyntaxResult.NotRun("cannot start the syntax checker: ${failure.message}")
        }
    }

    private fun lineOf(output: CharSequence): Int? =
        Regex("""line (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""[:(](\d+)[:)]""").find(output)?.groupValues?.get(1)?.toIntOrNull()

    private fun firstMeaningfulLine(output: String): String =
        output.lineSequence().map { it.trim() }.lastOrNull { it.contains("Error") || it.contains("error") }
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "syntax check failed"

    public companion object {
        /** Parses the file named in argv[1] without writing bytecode; SyntaxError carries `(file, line N)`. */
        public const val PY_PARSE: String = "import ast, sys; ast.parse(open(sys.argv[1], 'rb').read(), sys.argv[1])"
    }
}
