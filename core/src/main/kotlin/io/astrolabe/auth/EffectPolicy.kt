package io.astrolabe.auth

import io.astrolabe.atlas.OsFamily
import io.astrolabe.tool.EffectClass
import io.astrolabe.tool.RunArgs
import io.astrolabe.workspace.PathPattern
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
 * D-375: the file-system half of a delete or move classification. [EffectPolicy] reads text only; a caller with the
 * workspace on disk answers for one workspace-relative path (forward slashes, no `..`, never the root): true only when
 * the path was inspected, neither it nor an ancestor is a link, and nothing at or below it is protected or a link.
 * Whatever was not inspected answers false.
 */
public fun interface ContainmentProbe {
    public fun contained(relative: String): Boolean

    /**
     * [contained], except that a link strictly below [relative] passes when its real target lies strictly inside the
     * workspace clear of protected paths: `rm -r` and `rd /s` remove such a link without following it. A probe that
     * cannot tell answers [contained].
     */
    public fun containedWithInnerLinks(relative: String): Boolean = contained(relative)
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
    /** Workspace-relative prefixes a destructive delete may target without becoming D-class, when a probe proves it contained. */
    val tmpPrefixes: Set<String> = setOf("tmp", ".astrolabe/tmp", "build/tmp", "target/tmp"),
    /** D-class package installation (§4.6 "package installation (configurable)"). */
    val packageInstallIsDClass: Boolean = true,
    /**
     * Case-insensitive path comparison. Off by default so the policy is identical on Windows and Linux;
     * real case/alias identity belongs to the `WorkspacePath` contract (D-47, P1.2.6).
     */
    val caseInsensitivePaths: Boolean = false,
    /**
     * D-375: the platform the command runs on — `cmd.exe /d /v:off /s /c` on Windows, `sh -c` elsewhere (`Command.Shell`). It
     * decides which switches are flags (`/s` only for `cmd.exe`) and which redirect target is the null device.
     */
    val os: OsFamily = OsFamily.of(System.getProperty("os.name").orEmpty()),
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
 * and destructive git commands or deletes and moves not proven to stay inside the workspace clear of protected paths.
 * `W` covers known builders, formatters, test runners, scripts, deletes and moves of literal paths a [ContainmentProbe]
 * inspected inside the workspace (D-373, D-375; tmp prefixes included) and
 * redirects to literal paths inside the workspace. Only known read-only command forms are labelled `R` (existence probes even outside
 * the workspace, D-373); unknown executables are `W` with unknown effects — a label, not proof of read-only execution
 * in trusted-local mode (§4.6): post-hoc verification is the stamp diff (P1.6.5).
 */
public object EffectPolicy {

    /**
     * Classifies argv. [cwd] is workspace-relative; [workspaceRoot] is the absolute workspace path. Without a [probe]
     * no delete or move is proven contained (D-375), under the tmp prefixes neither: a link there may lead outside.
     */
    @JvmStatic
    @JvmOverloads
    public fun classify(
        argv: List<String>,
        cwd: String? = null,
        workspaceRoot: String,
        protectedPaths: List<String> = emptyList(),
        config: EffectPolicyConfig = EffectPolicyConfig(),
        probe: ContainmentProbe? = null,
    ): Classification {
        require(argv.isNotEmpty()) { "a command needs a program" }
        return classifySegments(listOf(argv), cwd, workspaceRoot, protectedPaths, config, probe, approximate = false, rendered = render(argv))
    }

    /**
     * Classifies a `run` call. The `cmd` (single shell invocation) form is split on shell operators and line breaks
     * and each segment is classified, after a `cd` in every directory it may run in, behind shell grammar also as the
     * commands it may run; a delete, move or redirect after a segment that may change the disk is never proven. The
     * result is marked [Classification.approximate] because a shell line can hide effects that argv cannot.
     */
    @JvmStatic
    @JvmOverloads
    public fun classify(
        args: RunArgs,
        workspaceRoot: String,
        protectedPaths: List<String> = emptyList(),
        config: EffectPolicyConfig = EffectPolicyConfig(),
        probe: ContainmentProbe? = null,
    ): Classification {
        val argv = args.argv
        if (argv != null && argv.isNotEmpty()) return classify(argv, args.cwd, workspaceRoot, protectedPaths, config, probe)
        val cmd = args.cmd.orEmpty()
        val segments = shellSegments(cmd, config.os == OsFamily.Windows)
        return classifySegments(segments, args.cwd, workspaceRoot, protectedPaths, config, probe, approximate = true, rendered = cmd)
    }

    private fun classifySegments(
        segments: List<List<String>>,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
        probe: ContainmentProbe?,
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
        // A `cd` moves the directory later segments run in, unless it failed (`||`) or ran in a subshell (`|`): each later
        // segment is classified in every directory it may run in, and a directory the text cannot name resolves nothing.
        // The probe sees the disk before the line runs, so a delete, move or redirect is proven only while every earlier
        // segment is read-only, a proven delete or move, or a plain `cd`: anything else may create the link it follows.
        val windows = config.os == OsFamily.Windows
        var directories = listOf(cwd)
        var proven = true
        for (tokens in segments) {
            var linkSafe = true
            for (directory in directories) {
                val one = classifyOne(tokens, directory, workspaceRoot, protectedPaths, config, probe, shell = approximate, proven = proven)
                linkSafe = linkSafe && one.linkSafe
                val variants = if (approximate && !(one.classification.effectClass == EffectClass.R && !one.classification.effectsUnknown)) embedded(tokens, windows) else emptyList()
                for (classification in listOf(one.classification) + variants.map { classifyOne(it, directory, workspaceRoot, protectedPaths, config, probe, shell = true, proven = proven).classification }) {
                    effect = maxOf(effect, classification.effectClass)
                    reasons += classification.reasons
                    capabilities += classification.requiredCapabilities
                    effectsUnknown = effectsUnknown || classification.effectsUnknown
                }
            }
            if (!approximate) continue
            val change = directoryChange(tokens, windows)
            val target = change?.target
            if (target != null) {
                val moved = directories.map { directory -> if (target == UNKNOWN_DIRECTORY) UNKNOWN_DIRECTORY else resolve(target, directory, workspaceRoot, config) ?: UNKNOWN_DIRECTORY }
                if (UNKNOWN_DIRECTORY in moved) reasons += "'${render(tokens)}' may move to a directory the text cannot name: later paths resolve nowhere"
                directories = (directories + moved).distinct()
            }
            if (!linkSafe && change?.exact != true) proven = false
        }
        return Classification(effect, reasons.toList(), capabilities, rendered, effectsUnknown, approximate)
    }

    /** A cwd under which every relative path resolves outside the workspace: the directory after an unreadable `cd`. */
    private const val UNKNOWN_DIRECTORY = ".."

    private val CHANGE_DIRECTORY = setOf("cd", "chdir", "pushd")

    /** `cmd.exe` reads `cd..`, `cd\x` and `cd/d x` as a `cd`. */
    private val GLUED_CHANGE_DIRECTORY = Regex("^(cd|chdir|pushd)[./\\\\].*")

    /** Words that run a script in this shell, so it may change the directory: `. x.sh`, `source`, `eval`, `call x.bat`. */
    private val IN_SHELL = setOf(".", "source", "eval", "call")

    private val DRIVE = Regex("^[A-Za-z]:$")

    /**
     * Where a segment moves the shell: [target] ([UNKNOWN_DIRECTORY] when the text cannot say, `null` when it stays); [exact]
     * for a plain `cd`, which touches no file.
     */
    private class DirectoryChange(val target: String?, val exact: Boolean)

    /**
     * The directory change of one segment, or `null` when it is no `cd` (`popd` returns to a directory already listed).
     * Only a plain `cd`, `chdir` or `pushd` with a literal target is followed; `cmd.exe` takes the rest of the line as one
     * path and prints the directory when it has none, `sh` goes home. A `cd` anywhere else in the segment (a group, loop,
     * `call`, behind an assignment or `command`), a script run in this shell or a drive change moves it somewhere unknown.
     */
    private fun directoryChange(tokens: List<String>, windows: Boolean): DirectoryChange? {
        val words = ArrayList<String>()
        var index = 0
        while (index < tokens.size) {
            if (redirect(tokens[index]) != null) index += 2 else words += tokens[index++]
        }
        val executable = words.firstOrNull() ?: return null
        if ('/' !in executable && '\\' !in executable && executable.lowercase() in CHANGE_DIRECTORY) {
            val operands = words.drop(1).filterNot { if (windows) it.equals("/d", ignoreCase = true) else it in POSIX_CD_FLAGS }
            if (windows && operands.isEmpty()) return DirectoryChange(null, exact = true)
            val target =(if (windows) operands.joinToString(" ") else operands.singleOrNull())?.takeIf { it != "-" && literalPath(it, windows) }
            return DirectoryChange(target ?: UNKNOWN_DIRECTORY, exact = true)
        }
        val bare = words.map { it.trimStart('(', '{', '@').lowercase() }
        val unknown = bare.any { it in CHANGE_DIRECTORY || GLUED_CHANGE_DIRECTORY.matches(it) } || bare.first() in IN_SHELL || (windows && DRIVE.matches(executable))
        return if (unknown) DirectoryChange(UNKNOWN_DIRECTORY, exact = false) else null
    }

    private val POSIX_CD_FLAGS = setOf("-L", "-P", "-e", "-@")

    /** Shell grammar and prefixes before a command: `then curl …`, `X=1 curl …`, `call curl …`, `for … do curl …`. */
    private val POSIX_PREFIXES = setOf("!", "{", "}", "(", ")", "if", "then", "else", "elif", "fi", "do", "done", "while", "until", "for", "case", "esac", "select", "time", "command", "builtin", "exec")
    private val WINDOWS_PREFIXES = setOf("if", "for", "call", "start", "(", ")", "else", "do", "not")
    private val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

    /**
     * The commands a segment may run behind shell grammar or a prefix, each to be classified as well: the segment without
     * a leading `(`, `{` or `@`, and after a keyword or assignment every suffix that starts at a word. Classifying more
     * only adds labels, so a misreading is stricter, never looser.
     */
    private fun embedded(tokens: List<String>, windows: Boolean): List<List<String>> {
        val head = tokens.firstOrNull() ?: return emptyList()
        val bare = head.trimStart('(', '{', '@')
        val keyword = bare.isEmpty() || bare.lowercase() in (if (windows) WINDOWS_PREFIXES else POSIX_PREFIXES) || (!windows && ASSIGNMENT.matches(head))
        val variants = ArrayList<List<String>>()
        if (bare != head && bare.isNotEmpty()) variants += listOf(bare) + tokens.drop(1)
        if (!keyword) return variants
        for (start in 1 until tokens.size) {
            if (redirect(tokens[start]) != null || redirect(tokens[start - 1]) != null) continue
            val word = tokens[start].trimStart('(', '{', '@')
            if (word.isNotEmpty()) variants += listOf(word) + tokens.drop(start + 1)
        }
        return variants
    }

    /** One segment's classification, and whether it cannot create a link a later segment would follow ([linkSafe]). */
    private class Classified(val classification: Classification, val linkSafe: Boolean)

    private const val UNPROVEN = "an earlier command in this line may change the disk before it runs; run it as a call of its own"

    /** [proven]: every earlier segment of the line is link-safe, so the probe's view of the disk still holds. */
    private fun classifyOne(
        tokens: List<String>,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
        containment: ContainmentProbe?,
        shell: Boolean,
        proven: Boolean = true,
    ): Classified {
        val reasons = ArrayList<String>()
        val capabilities = linkedSetOf(Capability.RunLocal, Capability.WorkspaceRead)
        var effect = EffectClass.R
        var effectsUnknown = false

        val redirects = ArrayList<String>()
        val writes = ArrayList<String>()
        val argv = ArrayList<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            // D-375: argv reaches the program without a shell, so `>` there is an argument, never a redirect.
            val operator = if (shell) redirect(token) else null
            if (operator != null) {
                val target = tokens.getOrNull(index + 1)
                when {
                    target == null || operator.heredoc || nullDevice(target, config) -> Unit
                    // `2>&1`, `>&2`, `<&-` name a descriptor, not a file.
                    operator.duplicates && (target == "-" || target.all(Char::isDigit)) -> Unit
                    operator.writes -> {
                        effect = maxOf(effect, EffectClass.W)
                        capabilities += Capability.WorkspaceWrite
                        reasons += "output redirect '$token'"
                        redirects += target
                        writes += target
                    }
                    else -> redirects += target
                }
                index += 2
                continue
            }
            argv += token
            index++
        }
        // A redirect target is a write path as written: the shell expands anything but a literal path to a place the text
        // cannot see, and a link on the way (checked when a probe is given) leads it out of the workspace.
        val windows = config.os == OsFamily.Windows
        for (target in writes) {
            if (!literalPath(target, windows)) {
                effect = EffectClass.D
                capabilities += Capability.OutsideWorkspace
                reasons += "redirect target '$target' is not a literal path"
                continue
            }
            if (!proven) {
                effect = EffectClass.D
                reasons += "redirect target '$target' is not proven: $UNPROVEN"
                continue
            }
            val resolved = resolve(target, cwd, workspaceRoot, config)
            if (resolved != null && containment != null && !containment.contained(resolved)) {
                effect = EffectClass.D
                reasons += "redirect target '$target' is reached through a link, is one or is protected"
            }
        }
        if (argv.isEmpty()) {
            checkPaths(redirects, probe = false, cwd, workspaceRoot, protectedPaths, config, reasons, capabilities)?.let { effect = it }
            return Classified(Classification(effect, reasons, capabilities, render(tokens), effectsUnknown = false), linkSafe = true)
        }

        val program = programName(argv.first())
        val args = argv.drop(1)
        // D-283: a command or process substitution runs a command argv cannot see, so it is never read-only.
        val substitution = tokens.any { "$(" in it || '`' in it } || redirects.any { it.startsWith("(") }
        // D-373: a read-only probe (`where`, `dir`, `if exist`, ...) with no file redirect stays R wherever its paths point.
        val probe = !substitution && redirects.isEmpty() && probe(argv)
        val removing = program in DELETE_OR_MOVE
        val readOnlyProgram = !substitution && readOnly(argv.first(), program, args)
        var provenRemoval = false
        val recognized = probe || removing || readOnlyProgram || program in config.scriptInterpreters ||
            listOf(
                config.privilegeCommands, config.networkCommands, config.packageInstallCommands,
                config.gitRefMutations, config.destructiveFileCommands, config.writingCommands,
            ).any { matchesAny(program, args, it) }

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
        if (destructive != null || removing) {
            val label = destructive ?: program
            // D-375: W only for operands proven to be literal paths that stay inside the workspace; the shell expands
            // anything else (`%VAR%`, `$HOME`, `~`, globs) to paths the text cannot see.
            val parsed = if (removing) removal(program, args, config) else Removal(args.filter { !isFlag(it) }, null, byName = false)
            val doubt = when {
                removing && ('/' in argv.first() || '\\' in argv.first()) -> "'${argv.first()}' is not a bare program name"
                parsed.unknown != null -> "'${parsed.unknown}' is not a recognised flag of '$program'"
                parsed.operands.isEmpty() -> "no operand"
                else -> parsed.operands.firstOrNull { !literal(it, windows) }?.let { "'$it' is not a literal path" }
                    // `link/../x` normalises lexically to `x`, but the shell follows `link` first.
                    ?: parsed.operands.firstOrNull { removing && ".." in it.split('/', '\\') }?.let { "'$it' has a '..' segment a link can redirect" }
            }
            // A tmp prefix is no proof either: a link or junction there leads outside, so it needs the probe as well.
            val unlinking = removing && program in UNLINKING
            val why = doubt ?: UNPROVEN.takeUnless { proven }
                ?: parsed.operands.firstNotNullOfOrNull { uncontained(it, cwd, workspaceRoot, protectedPaths, config, containment, parsed.byName, unlinking) }
            provenRemoval = removing && why == null
            when {
                why == null && parsed.operands.all { underTmp(if (parsed.byName) "$it/.." else it, cwd, workspaceRoot, config) } -> {
                    effect = maxOf(effect, EffectClass.W)
                    capabilities += Capability.WorkspaceWrite
                    reasons += "destructive delete under tmp: '$label'"
                }
                why == null && removing -> {
                    effect = maxOf(effect, EffectClass.W)
                    capabilities += Capability.WorkspaceWrite
                    reasons += "delete or move inside the workspace: '$label'"
                }
                else -> {
                    effect = EffectClass.D
                    reasons += "destructive delete outside tmp: '$label'" + (why?.let { " ($it)" } ?: "")
                }
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

        checkPaths(targetTokens(argv) + redirects, probe, cwd, workspaceRoot, protectedPaths, config, reasons, capabilities, program)?.let { effect = it }
        if (!recognized) {
            effect = maxOf(effect, EffectClass.W)
            capabilities += Capability.WorkspaceWrite
            effectsUnknown = true
            reasons += "unknown executable '$program': effects cannot be predicted from argv"
        }
        // A read-only program, or a proven delete or move, creates no link; a redirect it carries writes a checked file.
        val linkSafe = !effectsUnknown && (probe || readOnlyProgram || provenRemoval)
        return Classified(Classification(effect, reasons, capabilities, render(tokens), effectsUnknown), linkSafe)
    }

    /** A redirect or `cd` target as written: [literal], with no `..` a link can redirect and no wildcard the shell may expand. */
    private fun literalPath(token: String, windows: Boolean): Boolean =
        literal(token, windows) && token.none { it == '*' || it == '?' } && ".." !in token.split('/', '\\')

    /**
     * Known read-only forms (D-283): bare program names only, and every option that writes a file, runs another
     * program or changes system state keeps the command W/unknown. Redirects are classified before this check.
     */
    private fun readOnly(executable: String, program: String, args: List<String>): Boolean {
        if ('/' in executable || '\\' in executable) return false
        return when (program) {
            in READ_ONLY_ANY_ARGS -> true
            "rg" -> args.none { it == "--pre" || it.startsWith("--pre=") || it.startsWith("--pre-glob") || it.startsWith("--hostname-bin") }
            "find" -> args.none { it in FIND_WRITES || it.startsWith("-exec") || it.startsWith("-ok") || it.startsWith("-fprint") }
            "file" -> args.none { it == "-C" || it == "--compile" || shortCluster(it, 'C') }
            "tree" -> args.none { it == "-R" || it.startsWith("-o") || it == "--output" }
            "sort" -> args.none { abbreviates(it, "--output") || abbreviates(it, "--compress-program") || shortCluster(it, 'o') }
            "uniq" -> args.count { !isFlag(it) } <= 1
            "date" -> args.all { it.startsWith("+") || it in DATE_READ_FLAGS || it.startsWith("--iso-8601") || it.startsWith("--rfc-") }
            "hostname" -> args.all { it in HOSTNAME_READ_FLAGS }
            "git" -> gitReadOnly(args)
            else -> false
        }
    }

    /** A single-dash cluster such as `-uo` that contains the short option [option]. */
    private fun shortCluster(token: String, option: Char): Boolean =
        token.length > 1 && token[0] == '-' && token[1] != '-' && option in token.substring(1)

    /** [token] names the long [option], also as a unique-prefix abbreviation (`--out=x`) that getopt and git accept. */
    private fun abbreviates(token: String, option: String): Boolean {
        val name = token.substringBefore('=')
        return name.length > 2 && name.startsWith("--") && option.startsWith(name)
    }

    /** Git read forms; global options (`-C`, `-c`, `--git-dir`, …) before the subcommand are never read-only. */
    private fun gitReadOnly(args: List<String>): Boolean {
        val sub = args.firstOrNull() ?: return false
        val rest = args.drop(1)
        val flags = rest.filter { it.startsWith("-") }
        val positionals = rest.filterNot { it.startsWith("-") }
        val diffWrites = { token: String -> abbreviates(token, "--output") || abbreviates(token, "--ext-diff") }
        return when (sub) {
            "status", "blame", "ls-files", "ls-tree", "rev-parse", "describe", "shortlog" -> true
            "diff", "log", "show" -> rest.none(diffWrites)
            "grep" -> rest.none { diffWrites(it) || abbreviates(it, "--open-files-in-pager") || shortCluster(it, 'O') }
            "cat-file" -> rest.size == 2 && rest[0] in setOf("-p", "-t", "-s")
            "branch" -> flags.all { it in GIT_BRANCH_LIST_FLAGS } && (positionals.isEmpty() || "--list" in flags)
            "tag" -> flags.all { it == "-l" || it == "--list" } && (positionals.isEmpty() || flags.isNotEmpty())
            "remote" -> rest.isEmpty() || rest == listOf("-v") || rest == listOf("--verbose")
            "config" -> rest.firstOrNull() in GIT_CONFIG_READS && rest.drop(1).none { it.startsWith("-") }
            else -> false
        }
    }

    /**
     * D-373: probes that only report what exists (never file content), R even for paths outside the workspace. `if [not] exist <path> <cmd>`
     * is one when every command it runs (before and after `else`) is one.
     */
    private fun probe(argv: List<String>): Boolean {
        val executable = argv.first()
        if ('/' in executable || '\\' in executable) return false
        val program = programName(executable)
        if (program in PROBES) return true
        if (program != "if") return false
        var rest = argv.drop(1).filter { it.lowercase() != "/i" }
        if (rest.firstOrNull()?.lowercase() == "not") rest = rest.drop(1)
        if (rest.firstOrNull()?.lowercase() != "exist" || rest.size < 3) return false
        val command = rest.drop(2).map { it.removePrefix("(").removeSuffix(")") }.filter { it.isNotEmpty() }
        val parts = ArrayList<List<String>>()
        var part = ArrayList<String>()
        for (token in command) {
            if (token.lowercase() == "else") {
                parts += part
                part = ArrayList()
            } else {
                part += token
            }
        }
        parts += part
        return parts.all { it.isNotEmpty() && probe(it) }
    }

    private val PROBES = setOf("where", "which", "dir", "ls", "ver", "echo")
    /**
     * D-375: a redirect target that writes no file in the shell that runs the line — `nul` / `nul:` for `cmd.exe`,
     * `/dev/null` for `sh`. `$null` is never one here: the redirects this classifier sees belong to `cmd.exe` or `sh`
     * (which create a file `$null` or expand it), and PowerShell's own live inside its `-Command` string, which is
     * classified as a script interpreter's.
     */
    private fun nullDevice(target: String, config: EffectPolicyConfig): Boolean =
        if (config.os == OsFamily.Windows) target.lowercase() in setOf("nul", "nul:") else target == "/dev/null"

    private val DELETE_OR_MOVE = setOf("rm", "rmdir", "rd", "del", "erase", "mv", "move", "remove-item")

    /**
     * The operands of a delete or move, the first token that is neither one of its known flags nor an operand
     * ([unknown]), and whether the command applies each operand's name throughout its parent directory (`del /s`).
     */
    private class Removal(val operands: List<String>, val unknown: String?, val byName: Boolean)

    /** D-375: `cmd.exe` switches (`/s`) count only where `cmd.exe` runs; elsewhere `/s` is an absolute path. */
    private fun removal(program: String, args: List<String>, config: EffectPolicyConfig): Removal {
        val windows = config.os == OsFamily.Windows
        val operands = ArrayList<String>()
        var options = true
        var byName = false
        for (token in args) {
            val lowered = token.lowercase()
            when {
                !options -> operands += token
                token == "--" && program != "del" && program != "erase" && program != "move" -> options = false
                token.startsWith("-") && token.length > 1 -> if (!dashFlag(program, lowered)) return Removal(operands, token, byName)
                windows && SWITCH.matches(token) -> {
                    if (lowered !in WINDOWS_SWITCHES[program].orEmpty()) return Removal(operands, token, byName)
                    if (lowered == "/s" && (program == "del" || program == "erase")) byName = true
                }
                else -> operands += token
            }
        }
        return Removal(operands, null, byName)
    }

    private fun dashFlag(program: String, lowered: String): Boolean = when (program) {
        "rm" -> lowered in RM_LONG || (!lowered.startsWith("--") && lowered.drop(1).all { it in "rfidv" })
        "mv" -> lowered in MV_LONG || (!lowered.startsWith("--") && lowered.drop(1).all { it in "finv" })
        "rmdir", "rd" -> lowered == "-v" || lowered == "--verbose" || lowered == "--ignore-fail-on-non-empty"
        "remove-item" -> lowered in REMOVE_ITEM_FLAGS
        else -> false
    }

    private val SWITCH = Regex("^/-?[A-Za-z]$")
    private val WINDOWS_SWITCHES = mapOf(
        "rmdir" to setOf("/s", "/q"), "rd" to setOf("/s", "/q"), "del" to setOf("/p", "/f", "/q", "/s"),
        "erase" to setOf("/p", "/f", "/q", "/s"), "move" to setOf("/y", "/-y"),
    )
    private val RM_LONG = setOf("--recursive", "--force", "--dir", "--verbose", "--one-file-system", "--preserve-root")
    private val MV_LONG = setOf("--force", "--no-clobber", "--verbose")
    private val REMOVE_ITEM_FLAGS = setOf("-recurse", "-force", "-path", "-literalpath")

    /**
     * D-375: [token] is a path the shell passes on as written: no expansion (`%`, `$`, backtick, `!`, `~`, which also
     * spells 8.3 aliases), no glob class or brace, no PowerShell list or provider, no drive-relative, UNC or device form,
     * no segment Windows would trim (`name.`), and on POSIX no backslash, which `sh` unescapes (`.\./x` is `../x`).
     */
    private fun literal(token: String, windows: Boolean): Boolean {
        if (token.isEmpty() || token.any { it in NOT_LITERAL || it.isISOControl() }) return false
        if (!windows && '\\' in token) return false
        val text = token.replace('\\', '/')
        if (text.startsWith("//")) return false
        val drive = text.length >= 3 && text[0].isLetter() && text[1] == ':' && text[2] == '/'
        if (':' in (if (drive) text.substring(2) else text)) return false
        return text.split('/').none { it != "." && it != ".." && (it.endsWith('.') || it.endsWith(' ')) }
    }

    private val NOT_LITERAL = "%\$`!~[]{}()^,\"'<>|&;@".toSet()

    /**
     * Deletes that remove a link below their operand without following it: `rm -r` (GNU, BSD, busybox, MSYS) and the
     * `cmd.exe` builtin `rd /s`, also spelled `rmdir /s` (a POSIX `rmdir` removes empty directories only). `del /s`,
     * `Remove-Item -Recurse` and moves are not among them.
     */
    private val UNLINKING = setOf("rm", "rd", "rmdir")

    /**
     * Why the literal operand [token] is not proven to stay strictly inside the workspace clear of protected paths, or
     * `null`: it resolves below the root, a wildcard sits in its last segment only and cannot match a dot-entry, no
     * protected path is it, holds it or lies under it, and the [probe] inspected it on disk — with [innerLinks], links
     * below it pass when they stay inside. A wildcard — and with [byName] any operand — is checked as its parent
     * directory, which must not be the root.
     */
    private fun uncontained(
        token: String,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
        probe: ContainmentProbe?,
        byName: Boolean,
        innerLinks: Boolean,
    ): String? {
        val resolved = resolve(token, cwd, workspaceRoot, config) ?: return "'$token' resolves outside the workspace"
        val segments = resolved.split('/')
        val glob = segments.indexOfFirst { segment -> segment.any { it == '*' || it == '?' } }
        if (glob >= 0 && (glob != segments.lastIndex || segments[glob].startsWith("."))) return "'$token' is a wildcard that can reach a directory or a dot-entry"
        val checked = if (glob >= 0 || byName) resolved.substringBeforeLast('/', "") else resolved
        if (checked.isEmpty()) return "'$token' names the workspace root or every entry of it"
        if (protectedPaths.any { matchesPath(checked, it, true) || PathPattern.matches(it, checked, true) || holds(checked, it, true) }) {
            return "'$token' is, holds or lies under a protected path"
        }
        if (probe == null) return "'$checked' was not inspected on disk"
        if (innerLinks) return if (probe.containedWithInnerLinks(checked)) null else "'$checked' holds a protected path or a link leaving the workspace, or is reached through a link"
        return if (probe.contained(checked)) null else "'$checked' holds a protected path or a link, or is reached through a link"
    }

    /**
     * Checks argument and redirect paths: one outside the workspace or under a protected path makes the command D (the
     * returned class); a read-only [probe] may name paths outside.
     */
    private fun checkPaths(
        tokens: List<String>,
        probe: Boolean,
        cwd: String?,
        workspaceRoot: String,
        protectedPaths: List<String>,
        config: EffectPolicyConfig,
        reasons: MutableList<String>,
        capabilities: MutableSet<Capability>,
        program: String = "",
    ): EffectClass? {
        var effect: EffectClass? = null
        for (token in tokens) {
            val resolved = resolve(token, cwd, workspaceRoot, config)
            if (resolved == null && probe) {
                reasons += "read-only probe '$program' names '$token' outside the workspace"
                continue
            }
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
        return effect
    }

    /** A protected [pattern] that could name something under the directory [path]: its literal prefix lies below it, or it starts with a double star. */
    private fun holds(path: String, pattern: String, caseInsensitive: Boolean): Boolean {
        val raw = pattern.replace('\\', '/').trim('/').let { if (caseInsensitive) it.lowercase() else it }
        val dir = if (caseInsensitive) path.lowercase() else path
        val glob = raw.indexOfFirst { it == '*' || it == '?' }
        val literal = if (glob < 0) raw else raw.substring(0, glob).substringBeforeLast('/', "")
        if (literal.isEmpty()) return glob >= 0 && raw.startsWith("**")
        return literal == dir || literal.startsWith("$dir/")
    }

    private val READ_ONLY_ANY_ARGS = setOf(
        "ls", "cat", "type", "pwd", "echo", "true", "false", "whoami", "id",
        "head", "tail", "wc", "grep", "egrep", "fgrep", "findstr", "where", "which", "stat", "du", "df", "dir",
        "uname", "cut", "diff", "cmp", "comm", "nl", "tac", "basename", "dirname", "realpath", "readlink",
    )
    private val FIND_WRITES = setOf("-delete", "-fls")
    private val DATE_READ_FLAGS = setOf("-u", "--utc", "--universal", "-R", "--rfc-email", "-I", "/t", "/T")
    private val HOSTNAME_READ_FLAGS = setOf("-s", "--short", "-f", "--fqdn", "--long", "-d", "--domain", "-i", "-I", "-A")
    private val GIT_BRANCH_LIST_FLAGS = setOf("-a", "--all", "-r", "--remotes", "-v", "-vv", "--verbose", "--list")
    private val GIT_CONFIG_READS = setOf("--get", "--get-all", "--get-regexp", "--list", "-l")

    // ---- command patterns -------------------------------------------------------------------------------

    /** A redirect operator: it [writes] its target (`>`, `>>`, `>|`, `<>`, `>&file`), [duplicates] a descriptor (`>&2`, `<&0`) or opens a [heredoc]. */
    private class Redirect(val writes: Boolean, val duplicates: Boolean, val heredoc: Boolean)

    /** An optional descriptor (`2`), then the operator, as [tokenizeShell] emits it. */
    private val REDIRECT = Regex("^(\\d*)(>>|>\\||>&|>|<<<|<<-|<<|<>|<&|<)$")

    private fun redirect(token: String): Redirect? {
        val operator = REDIRECT.matchEntire(token)?.groupValues?.get(2) ?: return null
        return Redirect(writes = '>' in operator, duplicates = operator.endsWith('&'), heredoc = operator.startsWith("<<"))
    }

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

    /** Splits one shell command line into command segments; quotes are honoured, operators and line breaks separate segments. */
    private fun shellSegments(line: String, windows: Boolean): List<List<String>> {
        val segments = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (token in tokenizeShell(line, windows)) {
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

    /**
     * Shell tokens as the shell that runs the line reads them. A line break ends a command like `;` — in `cmd.exe` also
     * inside quotes, which never span lines there. `sh` quotes with `"` and `'` and escapes with `\`; `cmd.exe` quotes
     * with `"` only and escapes with `^`; an escape before a line break joins the lines. A redirect operator is one token
     * with its descriptor (`2>&`, `>|`, `<<-`), so `&` and `|` inside it separate nothing. Any other `&` separates: `cmd.exe`
     * and `dash` read `x &>f y` as `x &` then `>f y`; bash's `&>` reading puts the same file write in the same line.
     */
    private fun tokenizeShell(line: String, windows: Boolean): List<String> {
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
        fun at(index: Int, c: Char) = index < line.length && line[index] == c
        while (i < line.length) {
            val c = line[i]
            when {
                (c == '\n' || c == '\r') && (quote == ' ' || windows) -> {
                    quote = ' '
                    flush()
                    tokens += ";"
                }
                quote == '\'' -> if (c == '\'') quote = ' ' else token.append(c)
                quote == '"' -> when {
                    c == '"' -> quote = ' '
                    // Inside `sh` double quotes a backslash escapes only `$`, backtick, `"`, `\` and a line break.
                    !windows && c == '\\' && i + 1 < line.length && line[i + 1] in "\$`\"\\\n" -> {
                        if (line[i + 1] != '\n') token.append(line[i + 1])
                        i++
                    }
                    else -> token.append(c)
                }
                c == '"' -> quote = c
                c == '\'' && !windows -> quote = c
                c == (if (windows) '^' else '\\') -> {
                    var next = i + 1
                    val lineBreak = if (at(next, '\r') && at(next + 1, '\n')) 2 else if (at(next, '\n')) 1 else 0
                    if (lineBreak > 0) {
                        next += lineBreak
                        // `cmd.exe` also takes the first character of the joined line literally.
                        if (windows && next < line.length) token.append(line[next++])
                    } else if (next < line.length) {
                        token.append(line[next++])
                    }
                    i = next - 1
                }
                c.isWhitespace() -> flush()
                c == '|' || c == '&' -> {
                    flush()
                    val double = at(i + 1, c)
                    tokens += if (double) "$c$c" else c.toString()
                    if (double) i++
                }
                c == ';' -> {
                    flush()
                    tokens += ";"
                }
                c == '>' || c == '<' -> {
                    val pending = token.toString()
                    val descriptor = pending.isNotEmpty() && pending.all(Char::isDigit)
                    if (descriptor) token.clear() else flush()
                    val operator = StringBuilder(if (descriptor) pending else "").append(c)
                    var next = i + 1
                    if (c == '>') {
                        if (at(next, '>') || at(next, '|') || at(next, '&')) operator.append(line[next++])
                    } else if (at(next, '<')) {
                        operator.append(line[next++])
                        if (at(next, '<') || at(next, '-')) operator.append(line[next++])
                    } else if (at(next, '>') || at(next, '&')) {
                        operator.append(line[next++])
                    }
                    tokens += operator.toString()
                    i = next - 1
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
