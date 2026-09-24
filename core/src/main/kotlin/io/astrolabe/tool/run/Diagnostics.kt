package io.astrolabe.tool.run

/** The end-of-turn checkers with a diagnostics parser (§8.1 layer table); [id] is the runner name as the docs write it. */
public enum class DiagnosticTool(public val id: String) {
    Ruff("ruff"),
    Eslint("eslint"),
    Mypy("mypy"),
    Pyright("pyright"),
    Tsc("tsc"),
    CargoCheck("cargo check"),
    GoVet("go vet"),
    ;

    /** Tools that print nothing when clean: for them exit 0 with no output is the success signature. */
    public val silentWhenClean: Boolean get() = this == Eslint || this == Tsc || this == GoVet
}

/** Severity as the tool named it; only [Error] counts as an error (§8.3). */
public enum class DiagnosticSeverity { Error, Warning, Note }

/**
 * One parsed diagnostic. [path], [line] and [col] are null when the tool did not attach a location
 * (e.g. `tsc` config errors); [code] is the tool's rule or error code when it printed one.
 */
public data class Diagnostic(
    val path: String?,
    val line: Int?,
    val col: Int?,
    val severity: DiagnosticSeverity,
    val code: String?,
    val message: String,
) {
    init {
        require(path == null || path.isNotBlank()) { "a located diagnostic needs a path" }
        require((line ?: 0) >= 0 && (col ?: 0) >= 0) { "line and column are never negative" }
        require(message.isNotBlank()) { "a diagnostic needs a message" }
    }

    /** `path:line:col: message` (the checker's error line); the location prefix is omitted when unknown. */
    public fun render(): String = buildString {
        if (path != null) {
            append(path)
            if (line != null) append(':').append(line)
            if (line != null && col != null) append(':').append(col)
            append(": ")
        }
        append(message)
    }
}

/**
 * What a recognised tool's output said: every [diagnostics] entry, [successSignature] when the tool's own
 * clean line was seen (or, for a tool that is [DiagnosticTool.silentWhenClean], exit 0 with no output), and
 * [summaryErrors] when its summary line named an error total. Errors are the [DiagnosticSeverity.Error]
 * entries; warnings and notes never count (§8.3).
 */
public data class Diagnostics(
    val tool: DiagnosticTool,
    val diagnostics: List<Diagnostic>,
    val successSignature: Boolean,
    val summaryErrors: Int? = null,
) {
    val errors: List<Diagnostic> get() = diagnostics.filter { it.severity == DiagnosticSeverity.Error }
    val errorCount: Int get() = errors.size
    val warningCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Warning }
}

/**
 * Pure parsers for the default human output of the end-of-turn checkers (§8.1): `ruff`, `eslint` (stylish),
 * `mypy`, `pyright`, `tsc`, `cargo check` and `go vet`. The tool is recognised from the argv — the runner
 * basename, through `python -m`, `node`, `npx`/`npm`/`pnpm`/`yarn`/`uv`/`poetry` and one `sh -c` wrapper — and
 * the text is parsed once at the boundary. An exit code alone never becomes a count (§8.3): the parsers
 * report what the tool printed and leave the verdict to the checker.
 */
public object DiagnosticsParser {
    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
    private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ash", "cmd", "powershell", "pwsh")
    private val INTERPRETERS = setOf("node", "nodejs", "py", "python", "python3")
    private val PACKAGE_RUNNERS = setOf("npx", "npm", "pnpm", "yarn", "bunx", "bun", "uv", "uvx", "poetry", "pipx", "pdm", "hatch")

    /** The tool [argv] invokes, or null when no parser applies (`ruff format` is a formatter, not a checker). */
    @JvmStatic
    public fun recognise(argv: List<String>): DiagnosticTool? {
        val tokens = runnerTokens(argv)
        if (tokens.isEmpty()) return null
        val name = tokens[0]
        val rest = tokens.drop(1).filterNot { it.startsWith("+") }
        return when (name) {
            "ruff" -> if (rest.firstOrNull() == "format") null else DiagnosticTool.Ruff
            "eslint" -> DiagnosticTool.Eslint
            "mypy" -> DiagnosticTool.Mypy
            "pyright", "basedpyright" -> DiagnosticTool.Pyright
            "tsc" -> DiagnosticTool.Tsc
            "cargo" -> if (rest.firstOrNull() == "check") DiagnosticTool.CargoCheck else null
            "go" -> if (rest.firstOrNull() == "vet") DiagnosticTool.GoVet else null
            else -> null
        }
    }

    /** Parses [text] for the tool [argv] names; null when the tool is not recognised. */
    @JvmStatic
    public fun parse(argv: List<String>, text: String, exitCode: Int?): Diagnostics? =
        recognise(argv)?.let { parse(it, text, exitCode) }

    /** Parses [text] as [tool]'s default output; [exitCode] only decides the silent-success signature. */
    @JvmStatic
    public fun parse(tool: DiagnosticTool, text: String, exitCode: Int?): Diagnostics {
        val lines = ANSI.replace(text, "").lineSequence().map { it.trimEnd() }.toList()
        val parsed = when (tool) {
            DiagnosticTool.Ruff -> ruff(lines)
            DiagnosticTool.Eslint -> eslint(lines)
            DiagnosticTool.Mypy -> mypy(lines)
            DiagnosticTool.Pyright -> pyright(lines)
            DiagnosticTool.Tsc -> tsc(lines)
            DiagnosticTool.CargoCheck -> cargoCheck(lines)
            DiagnosticTool.GoVet -> goVet(lines)
        }
        val silent = tool.silentWhenClean && exitCode == 0 && lines.all { it.isBlank() }
        return parsed.copy(successSignature = parsed.successSignature || silent)
    }

    // ── argv recognition ─────────────────────────────────────────────────────────────────────────

    /** The runner tokens after interpreters and package runners, with the tool name normalised to its basename. */
    private fun runnerTokens(argv: List<String>): List<String> {
        var tokens = argv
        val head = tokens.firstOrNull()?.let { Invocations.basename(it).lowercase() } ?: return emptyList()
        // One shell wrapper (`sh -c "<line>"`, `cmd.exe /d /s /c <line>`): the line is the last token, as in Invocations.shellText.
        if (head in SHELLS && tokens.size >= 3) tokens = Invocations.tokenize(tokens.last())
        while (true) {
            val first = tokens.firstOrNull() ?: return emptyList()
            val base = Invocations.basename(first).lowercase()
            when {
                base in INTERPRETERS || base.startsWith("python") -> {
                    val m = tokens.indexOf("-m")
                    tokens = if (m >= 0 && m + 1 < tokens.size) {
                        tokens.drop(m + 1)
                    } else {
                        // `node node_modules/.bin/eslint …`: the script after the interpreter's flags.
                        tokens.drop(1).dropWhile { it.startsWith("-") }
                    }
                    if (tokens.isEmpty()) return emptyList()
                    if (Invocations.basename(tokens[0]).lowercase().let { it in INTERPRETERS || it.startsWith("python") }) return emptyList()
                }
                base in PACKAGE_RUNNERS -> {
                    tokens = tokens.drop(1).dropWhile { it.startsWith("-") || it == "run" || it == "exec" || it == "--" }
                }
                else -> return listOf(toolName(first)) + tokens.drop(1)
            }
        }
    }

    private fun toolName(token: String): String =
        Invocations.basename(token).lowercase().removeSuffix(".js").removeSuffix(".py")

    // ── ruff ─────────────────────────────────────────────────────────────────────────────────────

    private val RUFF_CONCISE = Regex("""^(\S+?):(\d+):(\d+): ([A-Z]+\d+)(?: \[\*\])? (.+)$""")
    private val RUFF_FULL_HEADER = Regex("""^([A-Z]+\d+)(?: \[\*\])? (.+)$""")
    private val RUFF_SUMMARY = Regex("""^Found (\d+) errors?""")
    private val RUFF_CLEAN = Regex("""^All checks passed!""")
    private val ARROW = Regex("""^\s*--> (\S+?):(\d+)(?::(\d+))?$""")

    private fun ruff(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        var clean = false
        var pending: Pair<String, String>? = null
        for (line in lines) {
            val concise = RUFF_CONCISE.find(line)
            if (concise != null) {
                pending = null
                val g = concise.groupValues
                out += Diagnostic(g[1], g[2].toInt(), g[3].toInt(), DiagnosticSeverity.Error, g[4], g[5])
                continue
            }
            val summaryLine = RUFF_SUMMARY.find(line)
            if (summaryLine != null) {
                summary = summaryLine.groupValues[1].toInt()
                continue
            }
            if (RUFF_CLEAN.containsMatchIn(line)) {
                clean = true
                continue
            }
            val arrow = ARROW.find(line)
            if (arrow != null) {
                val (code, message) = pending ?: continue
                pending = null
                out += Diagnostic(arrow.groupValues[1], arrow.groupValues[2].toInt(), arrow.groupValues[3].toIntOrNull(), DiagnosticSeverity.Error, code, message)
                continue
            }
            RUFF_FULL_HEADER.find(line)?.let { pending = it.groupValues[1] to it.groupValues[2] }
        }
        return Diagnostics(DiagnosticTool.Ruff, out, clean, summary)
    }

    // ── eslint (stylish) ─────────────────────────────────────────────────────────────────────────

    private val ESLINT_DIAGNOSTIC = Regex("""^\s+(\d+):(\d+)\s+(error|warning)\s+(.*?)(?:\s{2,}(\S+))?\s*$""")
    private val ESLINT_SUMMARY = Regex("""^\s*✖ (\d+) problems? \((\d+) errors?, (\d+) warnings?\)""")

    private fun eslint(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        var file: String? = null
        for (line in lines) {
            if (line.isBlank()) continue
            val summaryLine = ESLINT_SUMMARY.find(line)
            if (summaryLine != null) {
                summary = summaryLine.groupValues[2].toInt()
                continue
            }
            val m = ESLINT_DIAGNOSTIC.find(line)
            if (m == null) {
                if (!line[0].isWhitespace() && !line.startsWith("(node:")) file = line.trim()
                continue
            }
            out += Diagnostic(
                file, m.groupValues[1].toInt(), m.groupValues[2].toInt(), severity(m.groupValues[3]),
                m.groupValues[5].takeIf { it.isNotEmpty() }, m.groupValues[4],
            )
        }
        return Diagnostics(DiagnosticTool.Eslint, out, summary == 0, summary)
    }

    // ── mypy ─────────────────────────────────────────────────────────────────────────────────────

    private val MYPY_DIAGNOSTIC = Regex("""^(\S+?):(\d+)(?::(\d+))?(?::\d+:\d+)?: (error|warning|note): (.*?)(?:\s+\[([\w-]+)\])?$""")
    private val MYPY_SUMMARY = Regex("""^Found (\d+) errors? in \d+ files?""")
    private val MYPY_CLEAN = Regex("""^Success: no issues found""")

    private fun mypy(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        var clean = false
        for (line in lines) {
            val m = MYPY_DIAGNOSTIC.find(line)
            if (m != null) {
                out += Diagnostic(
                    m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), severity(m.groupValues[4]),
                    m.groupValues[6].takeIf { it.isNotEmpty() }, m.groupValues[5],
                )
                continue
            }
            MYPY_SUMMARY.find(line)?.let { summary = it.groupValues[1].toInt() }
            if (MYPY_CLEAN.containsMatchIn(line)) clean = true
        }
        return Diagnostics(DiagnosticTool.Mypy, out, clean, summary)
    }

    // ── pyright ──────────────────────────────────────────────────────────────────────────────────

    private val PYRIGHT_DIAGNOSTIC = Regex("""^\s*(\S+?):(\d+):(\d+) - (error|warning|information): (.*?)(?:\s+\(([\w-]+)\))?$""")
    private val PYRIGHT_SUMMARY = Regex("""^(\d+) errors?, (\d+) warnings?, (\d+) (?:informations?|infos?)""")

    private fun pyright(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        for (line in lines) {
            val m = PYRIGHT_DIAGNOSTIC.find(line)
            if (m != null) {
                out += Diagnostic(
                    m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), severity(m.groupValues[4]),
                    m.groupValues[6].takeIf { it.isNotEmpty() }, m.groupValues[5],
                )
                continue
            }
            PYRIGHT_SUMMARY.find(line)?.let { summary = it.groupValues[1].toInt() }
        }
        return Diagnostics(DiagnosticTool.Pyright, out, summary == 0, summary)
    }

    // ── tsc ──────────────────────────────────────────────────────────────────────────────────────

    private val TSC_PLAIN = Regex("""^(\S+?)\((\d+),(\d+)\): (error|warning) (TS\d+): (.*)$""")
    private val TSC_PRETTY = Regex("""^(\S+?):(\d+):(\d+) - (error|warning) (TS\d+): (.*)$""")
    private val TSC_GLOBAL = Regex("""^(error|warning) (TS\d+): (.*)$""")
    private val TSC_SUMMARY = Regex("""^Found (\d+) errors?""")

    private fun tsc(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        for (line in lines) {
            val located = TSC_PLAIN.find(line) ?: TSC_PRETTY.find(line)
            if (located != null) {
                val g = located.groupValues
                out += Diagnostic(g[1], g[2].toInt(), g[3].toInt(), severity(g[4]), g[5], g[6])
                continue
            }
            val global = TSC_GLOBAL.find(line)
            if (global != null) {
                out += Diagnostic(null, null, null, severity(global.groupValues[1]), global.groupValues[2], global.groupValues[3])
                continue
            }
            TSC_SUMMARY.find(line)?.let { summary = it.groupValues[1].toInt() }
        }
        return Diagnostics(DiagnosticTool.Tsc, out, summary == 0, summary)
    }

    // ── cargo check ──────────────────────────────────────────────────────────────────────────────

    private val CARGO_HEADER = Regex("""^(error|warning)(?:\[([A-Z]\d+)\])?: (.+)$""")
    private val CARGO_ABORTING = Regex("""^error: aborting due to (\d+) previous errors?""")
    private val CARGO_FINISHED = Regex("""^\s*Finished `?\S""")

    private fun cargoCheck(lines: List<String>): Diagnostics {
        val out = ArrayList<Diagnostic>()
        var summary: Int? = null
        var finished = false
        var pending: Triple<DiagnosticSeverity, String?, String>? = null
        for (line in lines) {
            val aborting = CARGO_ABORTING.find(line)
            if (aborting != null) {
                summary = aborting.groupValues[1].toInt()
                pending = null
                continue
            }
            if (CARGO_FINISHED.containsMatchIn(line)) {
                finished = true
                continue
            }
            val header = CARGO_HEADER.find(line)
            if (header != null) {
                pending = Triple(severity(header.groupValues[1]), header.groupValues[2].takeIf { it.isNotEmpty() }, header.groupValues[3])
                continue
            }
            // Only the primary span (the first `-->` after the header) locates the diagnostic; secondary spans use `:::`.
            val arrow = ARROW.find(line) ?: continue
            val (severity, code, message) = pending ?: continue
            pending = null
            out += Diagnostic(arrow.groupValues[1], arrow.groupValues[2].toInt(), arrow.groupValues[3].toIntOrNull(), severity, code, message)
        }
        return Diagnostics(DiagnosticTool.CargoCheck, out, finished, summary)
    }

    // ── go vet ───────────────────────────────────────────────────────────────────────────────────

    private val GO_VET_DIAGNOSTIC = Regex("""^(?:vet: )?(\S+?):(\d+)(?::(\d+))?: (.+)$""")

    private fun goVet(lines: List<String>): Diagnostics {
        val out = lines.mapNotNull { line ->
            if (line.startsWith("#")) return@mapNotNull null
            val m = GO_VET_DIAGNOSTIC.find(line) ?: return@mapNotNull null
            Diagnostic(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), DiagnosticSeverity.Error, null, m.groupValues[4])
        }
        return Diagnostics(DiagnosticTool.GoVet, out, successSignature = false)
    }

    private fun severity(word: String): DiagnosticSeverity = when (word) {
        "error" -> DiagnosticSeverity.Error
        "warning" -> DiagnosticSeverity.Warning
        else -> DiagnosticSeverity.Note
    }
}
