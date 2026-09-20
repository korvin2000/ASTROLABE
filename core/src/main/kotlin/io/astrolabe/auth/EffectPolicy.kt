package io.astrolabe.auth

import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.RunArgs
import kotlinx.serialization.Serializable

/**
 * The classification of one command (§4.6). Effect classes are **policy labels verified after the fact**: an
 * `R` run whose stamp changed is reclassified to `W` by the scheduler (§9.4), and this function never sees the
 * filesystem. It reads argv shape only, so it is a pure, deterministic function of its inputs.
 */
@Serializable
public data class Classification(
    val effectClass: EffectClass,
    val reasons: List<String>,
    val requiredCapabilities: Set<Capability>,
    /** The classified command, so the decision can be logged with its input. */
    val command: String,
    /**
     * True when argv cannot predict the effects — a script interpreter or a shell line. The script inherits
     * the caller's [Ceiling] (it is dispatched through the same executor) and its real effects are known only
     * from the stamp diff (§9.4).
     */
    val effectsUnknown: Boolean = false,
    /** True when the classification came from splitting a shell command line rather than from argv. */
    val approximate: Boolean = false,
) {
    override fun toString(): String =
        "$effectClass $command" + if (reasons.isEmpty()) "" else reasons.joinToString(prefix = " — ", separator = "; ")
}

/**
 * Configurable command vocabularies for [EffectPolicy]. Every entry is a pattern `program [sub] [tokens…]`:
 * the first non-flag word after the program is matched positionally, flag-like words (`-x`, `/s`) must be
 * present anywhere in the arguments. Package installation is D-class by default and configurable (§4.6).
 */
@Serializable
public data class EffectPolicyConfig(
    val privilegeCommands: Set<String> = DEFAULT_PRIVILEGE,
    val networkCommands: Set<String> = DEFAULT_NETWORK,
    val packageInstallCommands: Set<String> = DEFAULT_PACKAGE_INSTALL,
    val gitRefMutations: Set<String> = DEFAULT_GIT_REF_MUTATIONS,
    val destructiveFileCommands: Set<String> = DEFAULT_DESTRUCTIVE_FILE,
    val writingCommands: Set<String> = DEFAULT_WRITING,
    val scriptInterpreters: Set<String> = DEFAULT_SCRIPT_INTERPRETERS,
    /** Workspace-relative prefixes a destructive delete may target without becoming D-class. */
    val tmpPrefixes: Set<String> = setOf("tmp", ".astrolabe/tmp", "build/tmp", "target/tmp"),
    /** D-class package installation (§4.6 "package installation (configurable)"). */
    val packageInstallIsDClass: Boolean = true,
    /**
     * Case-insensitive path comparison. Off by default so the policy is identical on Windows and Linux;
     * real case/alias identity belongs to the `WorkspacePath` contract (D-47, P1.2.6).
     */
    val caseInsensitivePaths: Boolean = false,
) {
    public companion object {
        @JvmField
        public val DEFAULT_PRIVILEGE: Set<String> = setOf("sudo", "doas", "runas", "su", "pkexec")

        @JvmField
        public val DEFAULT_NETWORK: Set<String> =
            setOf("curl", "wget", "ssh", "scp", "sftp", "rsync", "nc", "ncat", "telnet", "ftp", "Invoke-WebRequest")

        @JvmField
        public val DEFAULT_PACKAGE_INSTALL: Set<String> = setOf(
            "pip install", "pip3 install", "uv add", "uv pip", "poetry add", "conda install",
            "npm install", "npm i", "npm ci", "yarn add", "yarn install", "pnpm add", "pnpm install",
            "apt install", "apt-get install", "apk add", "dnf install", "yum install", "brew install",
            "cargo install", "go get", "gem install", "dotnet add", "choco install", "winget install",
        )

        @JvmField
        public val DEFAULT_GIT_REF_MUTATIONS: Set<String> = setOf(
            "git push", "git commit", "git reset --hard", "git clean", "git checkout .", "git branch -D",
            "git update-ref", "git stash", "git tag -d", "git rebase", "git merge", "git cherry-pick",
        )

        @JvmField
        public val DEFAULT_DESTRUCTIVE_FILE: Set<String> =
            setOf("rm -rf", "rm -fr", "rm -r", "rm --recursive", "del /s", "rmdir /s", "rd /s", "rmdir -r")

        /** Known builders, formatters and test runners: they write caches, artifacts or reformatted files. */
        @JvmField
        public val DEFAULT_WRITING: Set<String> = setOf(
            "gradlew", "gradle", "mvn", "make", "cmake", "ninja", "bazel", "sbt", "ant",
            "npm run", "npm test", "yarn run", "pnpm run", "cargo build", "cargo test", "cargo fmt",
            "cargo clippy", "go build", "go test", "go vet", "gofmt", "rustfmt", "dotnet build", "dotnet test",
            "pytest", "tox", "nox", "jest", "vitest", "mocha", "phpunit", "rspec", "ruff", "black", "isort",
            "prettier", "eslint", "tsc", "javac", "kotlinc", "webpack", "vite", "rollup", "esbuild",
        )

        @JvmField
        public val DEFAULT_SCRIPT_INTERPRETERS: Set<String> = setOf(
            "python", "python3", "py", "node", "deno", "bun", "sh", "bash", "zsh", "dash", "fish",
            "pwsh", "powershell", "cmd", "ruby", "perl", "php", "groovy", "lua", "rscript", "java", "jshell",
        )
    }
}

/**
 * Effect-class policy (§4.6, §14.1). `D` covers writes outside the workspace, writes under a protected path,
 * network egress, mutation of the user's git refs, package installation (configurable), privilege escalation
 * and destructive filesystem or git commands. `W` covers known builders, formatters, test runners, scripts and
 * redirects inside the workspace. Everything else is labelled `R` — a label, not proof of read-only execution
 * in trusted-local mode (§4.6): post-hoc verification is the stamp diff (P1.6.5).
 */
public object EffectPolicy {

    /** Classifies argv. [cwd] is workspace-relative; [workspaceRoot] is the absolute workspace path. */
    @JvmStatic
    @JvmOverloads
    public fun classify(
        argv: List<String>,
        cwd: String? = null,
        workspaceRoot: String,
        protectedPaths: List<String> = emptyList(),
        config: EffectPolicyConfig = EffectPolicyConfig(),
    ): Classification {
        require(argv.isNotEmpty()) { "a command needs a program" }
        return classifySegments(listOf(argv), cwd, workspaceRoot, protectedPaths, config, approximate = false, rendered = render(argv))
    }

    /**
     * Classifies a `run` call. The `cmd` (single shell invocation) form is split on shell operators and each
     * segment is classified; the result is marked [Classification.approximate] because a shell line can hide
     * effects that argv cannot.
     */
    @JvmStatic
    @JvmOverloads
    public fun classify(
        args: RunArgs,
        workspaceRoot: String,
        protectedPaths: List<String> = emptyList(),
        config: EffectPolicyConfig = EffectPolicyConfig(),
    ): Classification {
        val argv = args.argv
        if (argv != null && argv.isNotEmpty()) return classify(argv, args.cwd, workspaceRoot, protectedPaths, config)
        val cmd = args.cmd.orEmpty()
        val segments = shellSegments(cmd)
        return classifySegments(segments, args.cwd, workspaceRoot, protectedPaths, config, approximate = true, rendered = cmd)
    }

    private fun classifySegments(
        segments: List<List<String>>,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
        approximate: Boolean,
        rendered: String,
    ): Classification {
        var effect = EffectClass.R
        val reasons = LinkedHashSet<String>()
        val capabilities = linkedSetOf(Capability.RunLocal, Capability.WorkspaceRead)
        var effectsUnknown = false
        if (approximate) reasons += "shell command line: classification is approximate, effects are verified by the stamp diff"
        if (segments.isEmpty()) {
            reasons += "no command could be parsed"
            return Classification(EffectClass.D, reasons.toList(), capabilities + Capability.OutsideWorkspace, rendered, true, approximate)
        }
        for (tokens in segments) {
            val one = classifyOne(tokens, cwd, workspaceRoot, protectedPaths, config)
            effect = maxOf(effect, one.effectClass)
            reasons += one.reasons
            capabilities += one.requiredCapabilities
            effectsUnknown = effectsUnknown || one.effectsUnknown
        }
        return Classification(effect, reasons.toList(), capabilities, rendered, effectsUnknown, approximate)
    }

    private fun classifyOne(
        tokens: List<String>,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
    ): Classification {
        val reasons = ArrayList<String>()
        val capabilities = linkedSetOf(Capability.RunLocal, Capability.WorkspaceRead)
        var effect = EffectClass.R
        var effectsUnknown = false

        val redirects = ArrayList<String>()
        val argv = ArrayList<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token in REDIRECTS) {
                if (token != "<") {
                    effect = maxOf(effect, EffectClass.W)
                    capabilities += Capability.WorkspaceWrite
                    reasons += "output redirect '$token'"
                }
                tokens.getOrNull(index + 1)?.let { redirects += it }
                index += 2
                continue
            }
            argv += token
            index++
        }
        if (argv.isEmpty()) return Classification(effect, reasons, capabilities, render(tokens), effectsUnknown = true)

        val program = programName(argv.first())
        val args = argv.drop(1)

        if (matchesAny(program, args, config.privilegeCommands)) {
            effect = EffectClass.D
            capabilities += Capability.Privilege
            reasons += "privilege escalation: '$program'"
        }
        if (matchesAny(program, args, config.networkCommands)) {
            effect = EffectClass.D
            capabilities += Capability.Network
            reasons += "network egress: '$program'"
        }
        matchedPattern(program, args, config.packageInstallCommands)?.let { pattern ->
            capabilities += Capability.PackageInstall
            if (config.packageInstallIsDClass) {
                effect = EffectClass.D
                capabilities += Capability.Network
                reasons += "package installation: '$pattern'"
            } else {
                effect = maxOf(effect, EffectClass.W)
                capabilities += Capability.WorkspaceWrite
                reasons += "package installation: '$pattern' (configured as non-D)"
            }
        }
        matchedPattern(program, args, config.gitRefMutations)?.let { pattern ->
            effect = EffectClass.D
            capabilities += Capability.GitRefs
            reasons += "mutation of the user's git refs: '$pattern'"
        }
        val destructive = matchedPattern(program, args, config.destructiveFileCommands)
        if (destructive != null) {
            val targets = args.filter { !isFlag(it) }
            val outsideTmp = targets.isEmpty() || targets.any { !underTmp(it, cwd, workspaceRoot, config) }
            if (outsideTmp) {
                effect = EffectClass.D
                reasons += "destructive delete outside tmp: '$destructive'"
            } else {
                effect = maxOf(effect, EffectClass.W)
                capabilities += Capability.WorkspaceWrite
                reasons += "destructive delete under tmp: '$destructive'"
            }
        }
        if (matchesAny(program, args, config.writingCommands)) {
            effect = maxOf(effect, EffectClass.W)
            capabilities += Capability.WorkspaceWrite
            reasons += "known builder, formatter or test runner: '$program' writes under the workspace"
        }
        if (program in config.scriptInterpreters) {
            effect = maxOf(effect, EffectClass.W)
            capabilities += Capability.WorkspaceWrite
            effectsUnknown = true
            // §14.3 a generated script is dispatched through the caller's ceiling; argv shape cannot predict
            // what it does, so the effects stay unknown until the stamp diff (§9.4).
            reasons += "script interpreter '$program': effects are unknown until the stamp diff"
        }

        for (token in targetTokens(argv) + redirects) {
            val resolved = resolve(token, cwd, workspaceRoot, config)
            if (resolved == null) {
                effect = EffectClass.D
                capabilities += Capability.OutsideWorkspace
                reasons += "path '$token' resolves outside the workspace"
                continue
            }
            val protectedBy = protectedPaths.firstOrNull { matchesPath(resolved, it, config.caseInsensitivePaths) }
            if (protectedBy != null) {
                effect = EffectClass.D
                reasons += "path '$token' is under the protected path '$protectedBy'"
            }
        }
        return Classification(effect, reasons, capabilities, render(tokens), effectsUnknown)
    }

    // ---- command patterns -------------------------------------------------------------------------------

    private val REDIRECTS = setOf(">", ">>", "<", "2>", "2>>", "&>")

    /** `-rf`, `--hard` and Windows switches (`/s`); a POSIX absolute path such as `/etc/passwd` is not a flag. */
    private fun isFlag(token: String): Boolean = when {
        token.startsWith("-") -> token.length > 1
        token.startsWith("/") -> token.length in 2..3 && token.drop(1).all { it.isLetterOrDigit() }
        else -> false
    }

    /** Program name without directory, extension or case; `./gradlew` and `C:\bin\curl.exe` normalize alike. */
    private fun programName(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val cut = base.substringBeforeLast('.')
        val name = if (cut.isEmpty() || base.substringAfterLast('.', "") !in EXECUTABLE_SUFFIXES) base else cut
        return name.lowercase()
    }

    private val EXECUTABLE_SUFFIXES = setOf("exe", "cmd", "bat", "com", "ps1")

    /** The positional subcommand: the first non-flag argument (`git -C dir push` ⇒ `push`). */
    private fun subcommand(args: List<String>): String? {
        var skipNext = false
        for (token in args) {
            if (skipNext) {
                skipNext = false
                continue
            }
            if (token in FLAGS_WITH_VALUE) {
                skipNext = true
                continue
            }
            if (isFlag(token)) continue
            return token.lowercase()
        }
        return null
    }

    private val FLAGS_WITH_VALUE = setOf("-C", "-c", "--git-dir", "--work-tree")

    private fun matchesAny(program: String, args: List<String>, patterns: Set<String>): Boolean =
        matchedPattern(program, args, patterns) != null

    private fun matchedPattern(program: String, args: List<String>, patterns: Set<String>): String? {
        val sub = subcommand(args)
        val lowered = args.map { it.lowercase() }
        return patterns.firstOrNull { pattern ->
            val words = pattern.trim().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty() || programName(words.first()) != program) return@firstOrNull false
            val rest = words.drop(1)
            // The first non-flag word of a pattern is positional (`git push`); every other word must be
            // present somewhere in the arguments (`git reset --hard`, `git checkout .`, `del /s`).
            val positionalIndex = rest.indexOfFirst { !isFlag(it) }
            val positional = rest.getOrNull(positionalIndex)
            val required = rest.filterIndexed { i, _ -> i != positionalIndex }
            (positional == null || positional.lowercase() == sub) && required.all { it.lowercase() in lowered }
        }
    }

    // ---- paths ------------------------------------------------------------------------------------------

    /**
     * Argument tokens that may name a filesystem target. `argv[0]` is skipped: a program is resolved through
     * `PATH`, and launching a system binary is not an escape — what a command *touches* is what the remaining
     * tokens say. A bare word cannot leave the workspace but can still name a protected path, so it is kept.
     */
    private fun targetTokens(argv: List<String>): List<String> = argv.drop(1).mapNotNull { raw ->
        val token = if (raw.startsWith("--") && raw.contains('=')) raw.substringAfter('=') else raw
        // A URL is network egress, not a filesystem target.
        if (token.isNotEmpty() && !isFlag(token) && !token.contains("://")) token else null
    }

    private fun underTmp(token: String, cwd: String?, workspaceRoot: String, config: EffectPolicyConfig): Boolean {
        val resolved = resolve(token, cwd, workspaceRoot, config) ?: return false
        return config.tmpPrefixes.any { matchesPath(resolved, it, config.caseInsensitivePaths) }
    }

    /** Workspace-relative canonical form of [token], or `null` when it leaves the workspace. Purely lexical. */
    private fun resolve(token: String, cwd: String?, workspaceRoot: String, config: EffectPolicyConfig): String? {
        val text = token.replace('\\', '/')
        if (text.startsWith("~")) return null
        val root = workspaceRoot.replace('\\', '/').trimEnd('/')
        if (isAbsolute(text)) {
            val normalized = normalizeAbsolute(text)
            val prefix = if (config.caseInsensitivePaths) root.lowercase() else root
            val candidate = if (config.caseInsensitivePaths) normalized.lowercase() else normalized
            if (candidate == prefix) return ""
            if (!candidate.startsWith("$prefix/")) return null
            return normalized.substring(root.length + 1)
        }
        val base = cwd?.replace('\\', '/')?.trim('/').orEmpty()
        return normalizeRelative(if (base.isEmpty()) text else "$base/$text")
    }

    private fun isAbsolute(text: String): Boolean =
        text.startsWith("/") || (text.length >= 3 && text[1] == ':' && text[2] == '/' && text[0].isLetter())

    private fun normalizeAbsolute(text: String): String {
        val prefixLength = if (text.startsWith("/")) 1 else 3
        val prefix = text.substring(0, prefixLength)
        val parts = ArrayList<String>()
        for (segment in text.substring(prefixLength).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return prefix.trimEnd('/') + (if (parts.isEmpty()) "" else "/" + parts.joinToString("/"))
    }

    private fun normalizeRelative(text: String): String? {
        val parts = ArrayList<String>()
        for (segment in text.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    /** Prefix match on path segments, or a `*` / `**` glob (D-31 protected defaults are written both ways). */
    private fun matchesPath(relative: String, pattern: String, caseInsensitive: Boolean): Boolean {
        val path = if (caseInsensitive) relative.lowercase() else relative
        val raw = pattern.replace('\\', '/').trim('/').let { if (caseInsensitive) it.lowercase() else it }
        if (raw.isEmpty()) return false
        if (raw.none { it == '*' || it == '?' }) return path == raw || path.startsWith("$raw/")
        val regex = buildString {
            append('^')
            var i = 0
            while (i < raw.length) {
                when {
                    raw.startsWith("**", i) -> {
                        append(".*")
                        i += 2
                    }
                    raw[i] == '*' -> {
                        append("[^/]*")
                        i++
                    }
                    raw[i] == '?' -> {
                        append("[^/]")
                        i++
                    }
                    else -> {
                        append(Regex.escape(raw[i].toString()))
                        i++
                    }
                }
            }
            append("(/.*)?$")
        }
        return Regex(regex).matches(path)
    }

    // ---- shell form -------------------------------------------------------------------------------------

    private val OPERATORS = setOf("|", "||", "&&", ";", "&")

    /** Splits one shell command line into command segments; quotes are honoured, operators separate segments. */
    private fun shellSegments(line: String): List<List<String>> {
        val segments = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (token in tokenizeShell(line)) {
            if (token in OPERATORS) {
                if (current.isNotEmpty()) segments += current
                current = ArrayList()
            } else {
                current += token
            }
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }

    private fun tokenizeShell(line: String): List<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quote = ' '
        var i = 0
        fun flush() {
            if (token.isNotEmpty()) {
                tokens += token.toString()
                token.clear()
            }
        }
        while (i < line.length) {
            val c = line[i]
            when {
                quote != ' ' -> if (c == quote) quote = ' ' else token.append(c)
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> flush()
                c == '|' || c == '&' -> {
                    flush()
                    val double = i + 1 < line.length && line[i + 1] == c
                    tokens += if (double) "$c$c" else c.toString()
                    if (double) i++
                }
                c == ';' -> {
                    flush()
                    tokens += ";"
                }
                c == '>' || c == '<' -> {
                    val prefix = if (token.toString() == "2" || token.toString() == "&") token.toString() else ""
                    if (prefix.isNotEmpty()) token.clear() else flush()
                    val double = i + 1 < line.length && line[i + 1] == c
                    tokens += prefix + (if (double) "$c$c" else c.toString())
                    if (double) i++
                }
                else -> token.append(c)
            }
            i++
        }
        flush()
        return tokens
    }

    private fun render(argv: List<String>): String =
        argv.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
}
