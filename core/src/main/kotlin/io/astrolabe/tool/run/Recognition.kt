package io.astrolabe.tool.run

import io.astrolabe.evidence.EvidenceKind

private val WINDOWS: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * C1a (plan §4.4): when a `run` request is the command a registered check declares. Both sides reduce to one canonical
 * token list — an argv as given, a `cmd` line only when it is [plain], and a one-shell wrapper (`sh -c <line>`,
 * `cmd /d /s /c <line>`) to its plain line's tokens — which are then compared exactly: never a prefix, a subset or a
 * reordering, so `pytest -q -x` is not `pytest -q`. On Windows the program token is compared the way the file system
 * resolves it: case folded, `/` read as `\` and a leading `.\` dropped — an extension is kept, since `x.exe` and `x.cmd`
 * are different files; every other token, and every token on POSIX, compares as written. Only a shell named bare or by
 * an absolute path is unwrapped. The directory is the caller's to compare.
 */
internal object CommandMatch {
    private val SHELLS = setOf("sh", "bash", "dash", "zsh", "ash")

    /** Every interpreter of a command line, whose exit is the line's own only for a plain line. */
    private val COMMAND_LINES = SHELLS + setOf("ksh", "fish", "cmd", "powershell", "pwsh")

    /** Shell syntax a plain line never contains: operators, redirections, expansions, globs, escapes and comments. */
    private const val SYNTAX = "|&;<>()\$`'*?[]{}~!%^#\r\n"

    private val CMD_SWITCH = Regex("""/[A-Za-z](:[A-Za-z]+)?""")

    /** A request's canonical tokens: an argv form as given, a `cmd` line only when [plain]; null when it cannot be compared. */
    fun tokens(requested: List<String>, shell: Boolean, windows: Boolean = WINDOWS): List<String>? =
        if (shell) plain(requested.joinToString(" "), windows)?.let { canonical(it, windows) } else canonical(requested, windows)

    /** [argv] with one shell wrapper around a plain line reduced to that line's tokens; otherwise [argv] itself. */
    fun canonical(argv: List<String>, windows: Boolean = WINDOWS): List<String> = script(argv, windows) ?: argv

    /** Whether the [request] tokens name the [declared] argv under this normalization. */
    fun matches(request: List<String>, declared: List<String>, windows: Boolean = WINDOWS): Boolean {
        val a = canonical(request, windows)
        val b = canonical(declared, windows)
        return a.isNotEmpty() && a.size == b.size && program(a[0], windows) == program(b[0], windows) && a.subList(1, a.size) == b.subList(1, b.size)
    }

    /**
     * Whether [argv]'s exit is its program's own: no command-line interpreter, or one shell around a plain line (one
     * simple command). Anything else may hide an exit — `false; exit 0` — and is never exit evidence (D-50).
     */
    fun exitPropagates(argv: List<String>, windows: Boolean = WINDOWS): Boolean =
        argv.isNotEmpty() && (Invocations.basename(argv[0]).lowercase() !in COMMAND_LINES || script(argv, windows) != null)

    /**
     * The words of a shell line with no shell syntax at all ([SYNTAX], and `\` on POSIX): split on spaces and tabs only,
     * where a word may be one whole non-empty double-quoted string; any other blank or control character refuses the
     * line, since a shell would not split there. Null for anything else — such a line is never recognised.
     */
    fun plain(line: String, windows: Boolean = WINDOWS): List<String>? {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var quoted = false
        var closed = false
        var previous = ' '
        for (c in line) {
            val blank = c == ' ' || c == '\t'
            if (c in SYNTAX || (c == '\\' && !windows) || (c == '"' && previous == '\\')) return null
            if (!blank && (c.isWhitespace() || c.isISOControl())) return null
            if (closed && !blank) return null
            closed = false
            previous = c
            when {
                c == '"' && quoted -> {
                    if (word.isEmpty()) return null
                    words += word.toString()
                    word.setLength(0)
                    quoted = false
                    closed = true
                }
                c == '"' -> if (word.isEmpty()) quoted = true else return null
                quoted -> word.append(c)
                blank -> if (word.isNotEmpty()) {
                    words += word.toString()
                    word.setLength(0)
                }
                else -> word.append(c)
            }
        }
        if (quoted) return null
        if (word.isNotEmpty()) words += word.toString()
        return words.takeIf { it.isNotEmpty() }
    }

    private fun script(argv: List<String>, windows: Boolean): List<String>? = line(argv, windows)?.let { plain(it, windows) }

    /** The plain line a one-shell wrapper runs (`sh -c <line>`, `cmd /d /s /c <line>`); null for anything else. */
    fun line(argv: List<String>, windows: Boolean = WINDOWS): String? {
        if (argv.size < 3 || !bareOrAbsolute(argv[0])) return null
        val head = Invocations.basename(argv[0]).lowercase()
        val line = when {
            head in SHELLS -> argv[2].takeIf { argv.size == 3 && argv[1] == "-c" }
            head == "cmd" -> {
                var i = 1
                while (i < argv.size && CMD_SWITCH.matches(argv[i]) && argv[i].lowercase() !in setOf("/c", "/k")) i++
                if (i + 1 < argv.size && argv[i].equals("/c", ignoreCase = true)) argv.subList(i + 1, argv.size).joinToString(" ") else null
            }
            else -> null
        }
        return line?.takeIf { plain(it, windows) != null }
    }

    private fun program(token: String, windows: Boolean): String = if (windows) token.replace('/', '\\').lowercase().removePrefix(".\\") else token

    /** A shell named bare (found on `PATH`) or by an absolute path; a relative one may be any file of the workspace. */
    private fun bareOrAbsolute(program: String): Boolean =
        ('/' !in program && '\\' !in program) || program.startsWith("/") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(program)
}

/**
 * C1a (plan §4.4): the evidence a command's tool provides, recognised from its canonical tokens ([CommandMatch.canonical])
 * through package runners (`npm test`, `npm run build`, `yarn tsc --noEmit`, `npx jest`, `uv run pytest`) and
 * `python -m`. Null for anything else — a linter, a formatter, an unknown script — which is no evidence of these kinds.
 */
internal object EvidenceKinds {
    private val SCRIPT_RUNNERS = setOf("npm", "pnpm", "yarn", "bun")
    private val TOOL_RUNNERS = setOf("npx", "pnpx", "bunx", "uvx", "uv", "poetry", "pdm", "hatch", "pipx")
    private val TEST_TOOLS = setOf("pytest", "py.test", "jest", "vitest", "mocha", "ava", "karma", "rspec", "phpunit", "ctest", "tox", "nox", "nosetests", "unittest")
    private val TYPECHECK_TOOLS = setOf("mypy", "pyright", "basedpyright")
    private val TYPECHECK_SCRIPTS = setOf("typecheck", "type-check", "check-types", "types")

    fun recognize(argv: List<String>, windows: Boolean = WINDOWS): EvidenceKind? {
        val tokens = CommandMatch.canonical(argv, windows)
        val head = name(tokens.firstOrNull() ?: return null)
        val rest = tokens.drop(1)
        return when {
            head in SCRIPT_RUNNERS -> script(rest)
            head in TOOL_RUNNERS -> rest.dropWhile { it.startsWith("-") || it == "run" || it == "exec" || it == "--" }.takeIf { it.isNotEmpty() }?.let { recognize(it, windows) }
            head.startsWith("python") || head == "py" -> {
                val m = rest.indexOf("-m")
                tool(if (m >= 0) rest.drop(m + 1) else rest.dropWhile { it.startsWith("-") })
            }
            else -> tool(tokens)
        }
    }

    /** `npm test`, `npm run build`, `pnpm typecheck`, `yarn jest`: a script by its name, else the binary the runner runs. */
    private fun script(rest: List<String>): EvidenceKind? {
        val args = rest.dropWhile { it.startsWith("-") }
        val first = args.firstOrNull() ?: return null
        return when (first) {
            "run", "run-script" -> args.drop(1).dropWhile { it.startsWith("-") }.takeIf { it.isNotEmpty() }?.let { scriptKind(it[0]) ?: tool(it) }
            "exec", "x", "dlx" -> args.drop(1).dropWhile { it.startsWith("-") || it == "--" }.takeIf { it.isNotEmpty() }?.let(::tool)
            else -> scriptKind(first) ?: tool(args)
        }
    }

    private fun scriptKind(script: String): EvidenceKind? {
        val name = script.lowercase()
        return when {
            name == "t" || name == "test" || name == "tests" || name.startsWith("test:") -> EvidenceKind.Tests
            name in TYPECHECK_SCRIPTS || name.startsWith("typecheck:") -> EvidenceKind.Typecheck
            name == "build" || name == "compile" || name.startsWith("build:") -> EvidenceKind.Build
            else -> null
        }
    }

    private fun tool(tokens: List<String>): EvidenceKind? {
        val name = name(tokens.firstOrNull() ?: return null)
        val args = tokens.drop(1)
        val sub = args.firstOrNull { !it.startsWith("-") }
        return when (name) {
            in TEST_TOOLS -> EvidenceKind.Tests
            in TYPECHECK_TOOLS -> EvidenceKind.Typecheck
            "tsc", "vue-tsc" -> if (args.any { it.equals("--noEmit", ignoreCase = true) || it.equals("-noEmit", ignoreCase = true) }) EvidenceKind.Typecheck else EvidenceKind.Build
            "go" -> when (sub) { "test" -> EvidenceKind.Tests; "build" -> EvidenceKind.Build; "vet" -> EvidenceKind.Typecheck; else -> null }
            "cargo" -> when (sub) { "test", "nextest" -> EvidenceKind.Tests; "build" -> EvidenceKind.Build; "check" -> EvidenceKind.Typecheck; else -> null }
            "dotnet", "swift" -> when (sub) { "test" -> EvidenceKind.Tests; "build" -> EvidenceKind.Build; else -> null }
            "deno" -> when (sub) { "test" -> EvidenceKind.Tests; "check" -> EvidenceKind.Typecheck; else -> null }
            "mix" -> when (sub) { "test" -> EvidenceKind.Tests; "compile" -> EvidenceKind.Build; else -> null }
            "make", "gmake" -> when (sub) { null, "all", "build" -> EvidenceKind.Build; "test", "check" -> EvidenceKind.Tests; else -> null }
            "gradle", "gradlew" -> gradle(args)
            "mvn", "mvnw" -> maven(args)
            "javac" -> EvidenceKind.Build
            "manage" -> if (sub == "test") EvidenceKind.Tests else null
            else -> null
        }
    }

    private fun gradle(args: List<String>): EvidenceKind? {
        val tasks = args.filterNot { it.startsWith("-") }.map { it.substringAfterLast(':').lowercase() }
        return when {
            tasks.any { it == "check" || it.endsWith("test") } -> EvidenceKind.Tests
            tasks.any { it == "build" || it == "assemble" || it == "classes" || it == "jar" || it.startsWith("compile") } -> EvidenceKind.Build
            else -> null
        }
    }

    private fun maven(args: List<String>): EvidenceKind? {
        val phases = args.filterNot { it.startsWith("-") }.map { it.lowercase() }
        return when {
            phases.any { it == "test" || it == "verify" || it == "integration-test" } -> EvidenceKind.Tests
            phases.any { it == "compile" || it == "test-compile" || it == "package" || it == "install" } -> EvidenceKind.Build
            else -> null
        }
    }

    private fun name(token: String): String = Invocations.basename(token).lowercase().removeSuffix(".js").removeSuffix(".py")
}
