package io.astrolabe.verify

import io.astrolabe.atlas.Manifest
import io.astrolabe.atlas.isTestPath
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.event.Authority
import io.astrolabe.evidence.Closure
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which part of the acceptance surface a touched path belongs to (§8.6). */
public enum class AcceptanceSurface(public val wire: String) {
    TestFile("test file"),
    CheckDefinition("check definition"),
    CiConfig("CI config"),
    AcceptanceCommand("acceptance command input"),
}

/**
 * One acceptance-surface detection (§8.6). [kind] is what the classifier found ([TestIntegrity.classify], P3.4.2:
 * comma-joined kinds, or [TestIntegrity.UNCLASSIFIED] when it saw no bytes or could not tell). [requiredChecks]
 * are the required checks the path can affect; while any exist, the kind is not [TestIntegrity.ADDITIONS_ONLY]
 * and no approving [verdict] is attached, the increment cannot complete. [originalObligation] is the pre-change
 * text the change removed (the deleted test, the original assertion) for the reviewer. [reason] is the worker's
 * recorded justification, rendered with the line and in the finish receipt (`acceptance_surface_modified`).
 */
public data class TestIntegrityFlag(
    val path: String,
    val surface: AcceptanceSurface,
    val cause: String,
    val requiredChecks: List<String>,
    val kind: String = TestIntegrity.UNCLASSIFIED,
    val reason: String? = null,
    val verdict: Verdict? = null,
    val originalObligation: String? = null,
) {
    /**
     * Unknown classification is never proof of no weakening: a touched required check needs an approving review.
     * Only a new test file without skip markers is exempt; additions to existing tests can bypass assertions.
     */
    val blocksCompletion: Boolean get() = requiredChecks.isNotEmpty() && kind != TestIntegrity.ADDITIONS_ONLY && verdict?.approved != true

    /** The acceptance-surface line of the edit result and the anchor (§8.6). */
    val line: String
        get() = buildString {
            append("acceptance surface: ").append(path).append(" (").append(surface.wire).append(") modified by ").append(cause)
            append(" · ").append(kind)
            if (requiredChecks.isNotEmpty()) append(" · required: ").append(requiredChecks.joinToString(", "))
            reason?.let { append(" · reason: ").append(it) }
            append(" · review: ").append(
                when {
                    verdict == null -> if (requiredChecks.isEmpty()) "not required" else "pending"
                    verdict.approved -> "approved by ${verdict.signedBy}"
                    else -> "${verdict.outcome.name.lowercase()} by ${verdict.signedBy}"
                },
            )
        }
}

/**
 * The conservative acceptance-surface policy of S0 (§8.6 test-integrity guard, TODO P1.7.8, I-02): any edit
 * or run that touches a known test file, a check definition, CI configuration or an input of an acceptance
 * command is flagged as an unclassified weakening risk. Completion with an unresolved flag on a required
 * check needs `Authority.review` approval — the human path of D-23 — with the original obligation attached;
 * otherwise the increment stays unaccepted. The guard is heuristic and says so: it makes weakening
 * *visible*, legitimate test maintenance passes through review, never through silence.
 *
 * Deterministic over records: the contract and the check registry are the committed ones the caller holds,
 * so a mid-attempt configuration change cannot reach it (invariant 12, P1.9.4).
 */
public object TestIntegrity {
    public const val UNCLASSIFIED: String = "unclassified-weakening-risk"

    /** (a) a test function or test file deleted or renamed. */
    public const val DELETED_TEST: String = "deleted-test"

    /** (b) an assertion removed or loosened (`assert x == y` → `assert x`, a widened tolerance). */
    public const val WEAKENED_ASSERTION: String = "weakened-assertion"

    /** (c) a skip, xfail, only or disabled marker added. */
    public const val SKIP_MARKER: String = "skip-marker"

    /** (d) a snapshot or golden file changed. */
    public const val SNAPSHOT_UPDATE: String = "snapshot-update"

    /** (e) CI or check configuration changed: which checks run may differ. */
    public const val CHECK_CONFIG: String = "check-config"

    /** (f) an input named by an acceptance command changed. */
    public const val ACCEPTANCE_COMMAND: String = "acceptance-command"

    /** A test file change that only added lines and no marker: rendered, never blocking. */
    public const val ADDITIONS_ONLY: String = "additions-only"

    /** File names that decide which checks run or how they are collected (§8.6 e). */
    @JvmField
    public val CHECK_DEFINITION_NAMES: Set<String> = Manifest.entries.map { it.fileName }.toSet() + setOf(
        "pytest.ini", "conftest.py", "tox.ini", "setup.cfg", "noxfile.py",
        "settings.gradle", "settings.gradle.kts", "phpunit.xml", "phpunit.xml.dist",
    )

    /** File-name prefixes of framework configuration files (`jest.config.ts`, `.mocharc.yml`, …). */
    @JvmField
    public val CHECK_DEFINITION_PREFIXES: List<String> = listOf(
        "jest.config.", "vitest.config.", "vitest.workspace.", "karma.conf.", "playwright.config.", "cypress.config.", ".mocharc",
    )

    @JvmField
    public val CI_PREFIXES: Set<String> = setOf(".github", ".gitlab", ".circleci", ".buildkite")

    @JvmField
    public val CI_NAMES: Set<String> = setOf(".gitlab-ci.yml", ".travis.yml", "Jenkinsfile", "azure-pipelines.yml", "bitbucket-pipelines.yml", "appveyor.yml")

    /**
     * Flags for every touched path that lies on the acceptance surface. [cause] names the mutation
     * (`edit #3`, `run #7`); [checks] is the committed registry, whose required checks are tied to each flag.
     */
    @JvmStatic
    public fun baseline(touched: Collection<String>, cause: String, contract: Contract, checks: Checks): List<TestIntegrityFlag> =
        LinkedHashSet(touched.map(::normalize)).mapNotNull { path ->
            val surface = surfaceOf(path, contract, checks.packageManifest) ?: return@mapNotNull null
            TestIntegrityFlag(path, surface, cause, requiredChecksFor(path, surface, checks))
        }

    /**
     * The §8.6 classifier (P3.4.2, heuristic and labelled as such): flags for every changed path on the acceptance
     * surface, each with the kinds found by a line diff of [SurfaceChange.before] and [SurfaceChange.after] and
     * the removed text as its original obligation. Deterministic: the same bytes always give the same kinds.
     */
    @JvmStatic
    public fun classify(changes: Collection<SurfaceChange>, cause: String, contract: Contract, checks: Checks): List<TestIntegrityFlag> =
        changes.distinctBy { normalize(it.path) }.mapNotNull { change ->
            val path = normalize(change.path)
            val surface = surfaceOf(path, contract, checks.packageManifest) ?: return@mapNotNull null
            val before = lines(change.before)
            val after = lines(change.after)
            val prefix = before.zip(after).takeWhile { (a, b) -> a == b }.size
            val suffix = before.drop(prefix).asReversed().zip(after.drop(prefix).asReversed()).takeWhile { (a, b) -> a == b }.size
            val removed = before.subList(prefix, before.size - suffix)
            val added = after.subList(prefix, after.size - suffix)
            val kinds = kindsOf(path, surface, change, removed, added)
            val original = removed.filter { it.isNotBlank() }.take(MAX_ORIGINAL_LINES).joinToString("\n").ifEmpty { null }
            TestIntegrityFlag(path, surface, cause, requiredChecksFor(path, surface, checks), kinds.joinToString(","), originalObligation = original)
        }

    private fun kindsOf(path: String, surface: AcceptanceSurface, change: SurfaceChange, removed: List<String>, added: List<String>): List<String> {
        when (surface) {
            AcceptanceSurface.CiConfig, AcceptanceSurface.CheckDefinition -> return listOf(CHECK_CONFIG)
            AcceptanceSurface.AcceptanceCommand -> return listOf(ACCEPTANCE_COMMAND)
            AcceptanceSurface.TestFile -> Unit
        }
        if (change.before == null && change.after == null) return listOf(UNCLASSIFIED)
        if (SNAPSHOT.containsMatchIn(path)) return listOf(SNAPSHOT_UPDATE)
        if (change.after == null) return listOf(DELETED_TEST)
        val kinds = ArrayList<String>()
        if (change.before != null && (testNames(change.before) - testNames(change.after).toSet()).isNotEmpty()) kinds += DELETED_TEST
        if (added.any { SKIP.containsMatchIn(it) }) kinds += SKIP_MARKER
        if (weakened(removed, added)) kinds += WEAKENED_ASSERTION
        return when {
            kinds.isNotEmpty() -> kinds
            change.before == null && removed.all { it.isBlank() } -> listOf(ADDITIONS_ONLY)
            else -> listOf(UNCLASSIFIED)
        }
    }

    /** Fewer assertions than before, a removed comparison, or a changed tolerance argument. */
    private fun weakened(removed: List<String>, added: List<String>): Boolean {
        val removedAsserts = removed.filter { ASSERTION.containsMatchIn(it) }
        val addedAsserts = added.filter { ASSERTION.containsMatchIn(it) }
        if (removedAsserts.isEmpty()) return false
        if (addedAsserts.size < removedAsserts.size) return true
        if (removedAsserts.sumOf { COMPARISON.findAll(it).count() } > addedAsserts.sumOf { COMPARISON.findAll(it).count() }) return true
        return removedAsserts.any { TOLERANCE.containsMatchIn(it) } || addedAsserts.any { TOLERANCE.containsMatchIn(it) }
    }

    private fun testNames(text: String): List<String> = TEST_NAME.findAll(text).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toList()

    private fun lines(text: String?): List<String> = text?.lines()?.map { it.trimEnd() } ?: emptyList()

    private const val MAX_ORIGINAL_LINES = 40

    private val SNAPSHOT = Regex("""(^|/)(__snapshots__|snapshots|golden|goldens|testdata/golden)/|\.snap$|\.approved\.|\.golden$""")
    private val SKIP = Regex("""@pytest\.mark\.(skip|skipif|xfail)\b|pytest\.(skip|xfail)\(|@unittest\.skip|\bself\.skipTest\(|\b(it|test|describe)\.(skip|only|todo)\(|\b(xit|xdescribe|xtest|fit|fdescribe)\(|@(Disabled|Ignore)\b|@DisabledIf|\bt\.Skip\(|#\[ignore\]""")
    private val ASSERTION = Regex("""\bassert\w*\b|\bexpect\(|\bassertThat\(|\bself\.assert\w+\(|\bshould\w*\b|\bt\.(Error|Fatal)f?\(|\brequire\.\w+\(""")
    private val COMPARISON = Regex("""==|!=|<=|>=|\bis not\b|\bin\b|\.to(Be|Equal|StrictEqual|Match|Throw|Contain)\w*\(|assert(Equals|NotEquals|Same|Throws|True|False|Null|Contains)\w*\(|\bisEqualTo\(""")
    private val TOLERANCE = Regex("""\bapprox\(|\b(rel|abs|rtol|atol|places|delta|tolerance|epsilon)\s*=|toBeCloseTo\(|assertAlmostEqual|isCloseTo\(|\bwithin\(|offset\(""")
    private val TEST_NAME = Regex("""(?m)^\s*(?:async\s+)?def\s+(test\w*)\s*\(|\b(?:it|test)\s*\(\s*['"`]([^'"`]+)['"`]|@Test[^\n]*\n\s*(?:public\s+|internal\s+|private\s+)?(?:fun|void)\s+(`[^`]+`|\w+)|^\s*func\s+(Test\w+)\(|#\[test\]\s*\n\s*(?:pub\s+)?fn\s+(\w+)""")

    /** The surface [path] belongs to, or `null` when it is ordinary source. CI and check definitions outrank the test-file convention. */
    @JvmStatic
    public fun surfaceOf(path: String, contract: Contract): AcceptanceSurface? = surfaceOf(path, contract) { null }

    internal fun surfaceOf(path: String, contract: Contract, manifest: (String) -> String?): AcceptanceSurface? {
        val relative = normalize(path)
        val name = relative.substringAfterLast('/')
        val first = relative.substringBefore('/')
        return when {
            first in CI_PREFIXES || name in CI_NAMES -> AcceptanceSurface.CiConfig
            name in CHECK_DEFINITION_NAMES || CHECK_DEFINITION_PREFIXES.any { name.startsWith(it) } -> AcceptanceSurface.CheckDefinition
            isTestPath(relative) -> AcceptanceSurface.TestFile
            contract.acceptance.filterIsInstance<Acceptance.Run>().any { names(it.command.argv, it.command.cwd, relative, manifest) } ->
                AcceptanceSurface.AcceptanceCommand
            else -> null
        }
    }

    /** Flags that still block completion: a required check touched without an approving verdict. */
    @JvmStatic
    public fun unresolved(flags: List<TestIntegrityFlag>): List<TestIntegrityFlag> = flags.filter { it.blocksCompletion }

    /**
     * The review the human path answers (D-23): the criteria of every required check the flags touch, and
     * beside them the original obligations — the acceptance definitions with origin and obligation version
     * (D-52) and, per path, whatever [originals] records as the pre-change content (a preimage id, the
     * original assertion) — so that deleting a failure can never satisfy its behaviour requirement.
     */
    @JvmStatic
    public fun reviewRequest(
        id: String,
        flags: List<TestIntegrityFlag>,
        contract: Contract,
        checks: Checks,
        ids: Identities,
        candidate: CandidateId,
        packetRef: String,
        originals: Map<String, String> = emptyMap(),
    ): ReviewRequest {
        val acceptance = flags.flatMap { it.requiredChecks }.distinct()
            .flatMap { checks[it]?.acceptanceIds.orEmpty() }.distinct()
            .mapNotNull { contract.acceptance(it) }
        val obligations = acceptance.map { "${it.id} (${it.origin}, v${it.obligationVersion}): ${it.criterion}" } +
            flags.mapNotNull { flag -> (originals[flag.path] ?: flag.originalObligation)?.let { "original ${flag.path}: $it" } }
        return ReviewRequest(
            id = id,
            contractRevision = contract.version,
            ids = ids,
            scope = ReviewScope.Increment,
            candidate = candidate,
            packetRef = packetRef,
            criteria = acceptance.map { it.criterion } + flags.map { it.line },
            originalObligations = obligations,
        )
    }

    /** Asks [authority] for the verdict and attaches it to every flag; `null` (no reviewer) leaves them blocking. */
    @JvmStatic
    public suspend fun resolve(request: ReviewRequest, flags: List<TestIntegrityFlag>, authority: Authority): List<TestIntegrityFlag> {
        val verdict = authority.review(request) ?: return flags
        require(verdict.requestId == request.id) { "verdict ${verdict.requestId} does not answer review ${request.id}" }
        return flags.map { it.copy(verdict = verdict) }
    }

    // ------------------------------------------------------------- internals

    private fun requiredChecksFor(path: String, surface: AcceptanceSurface, checks: Checks): List<String> = checks.required().filter { check ->
        when (surface) {
            // A changed definition or CI file can alter which checks run and how: every required check is affected.
            AcceptanceSurface.CheckDefinition, AcceptanceSurface.CiConfig -> true
            AcceptanceSurface.TestFile, AcceptanceSurface.AcceptanceCommand -> when (val closure = check.inputClosure) {
                Closure.Unknown -> true
                is Closure.Known -> path in closure.paths
                is Closure.Package -> path == closure.path || path.startsWith(closure.path.trimEnd('/') + "/")
            } || (check.command?.let { names(it.argv, it.cwd, path, checks.packageManifest) } ?: false)
        }
    }.map { it.id }

    private fun names(argv: List<String>, cwd: String?, path: String, manifest: (String) -> String?): Boolean =
        namesPath(argv, cwd, path) || scriptPaths(argv, cwd, manifest).any { path == it || path.startsWith("$it/") }

    /** A package-manager command with its directory option consumed: the directory, and the argv without the option. */
    private class PackageCommand(val directory: String?, val argv: List<String>)

    /**
     * D-375: `npm|pnpm|yarn` with `--prefix|--cwd|--dir|-C <dir>` (or `=<dir>`): the directory is where the manifest is
     * read, never a named input, so the option and its value leave the argv before path classification. Arguments
     * after `--` belong to the script and are kept verbatim; `null` for any other tool.
     */
    private fun packageCommand(argv: List<String>): PackageCommand? {
        val tool = argv.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.lowercase()?.substringBefore('.') ?: return null
        if (tool !in PACKAGE_MANAGERS) return null
        var directory: String? = null
        val rest = arrayListOf(argv.first())
        var i = 1
        while (i < argv.size) {
            val arg = argv[i]
            if (arg == "--") {
                rest += argv.subList(i, argv.size)
                break
            }
            when {
                arg in PREFIX_FLAGS -> directory = argv.getOrNull(++i)
                PREFIX_FLAGS.any { arg.startsWith("$it=") } -> directory = arg.substringAfter('=')
                else -> rest += arg
            }
            i++
        }
        return PackageCommand(directory, rest)
    }

    /**
     * D-374: what `npm|pnpm|yarn test` or `… run <name>` runs is the `scripts.<name>` string of the package's
     * `package.json`, read in the directory option's directory (resolved against the cwd) when one is given; its
     * path-like tokens, resolved against that package directory, are named inputs. One level only, and an unreadable
     * or absent manifest names nothing.
     */
    private fun scriptPaths(argv: List<String>, cwd: String?, manifest: (String) -> String?): List<String> {
        val command = packageCommand(argv) ?: return emptyList()
        val prefix = command.directory
        val positional = command.argv.drop(1).takeWhile { it != "--" }.filterNot { it.startsWith("-") }
        val name = when (positional.firstOrNull()) {
            "test" -> "test"
            "run", "run-script" -> positional.getOrNull(1) ?: return emptyList()
            else -> return emptyList()
        }
        val base = packageDirectory(cwd, prefix) ?: return emptyList()
        val script = runCatching {
            ((Json.parseToJsonElement(manifest(base) ?: return emptyList()) as? JsonObject)?.get("scripts") as? JsonObject)?.get(name) as? JsonPrimitive
        }.getOrNull()?.takeIf { it.isString }?.content ?: return emptyList()
        return script.split(SCRIPT_SEPARATORS).mapNotNull(::pathToken).map { if (base.isEmpty()) it else "$base/$it" }
    }

    /** [cwd] joined with the directory option [prefix], `""` for the root; `null` when either is absolute or escapes. */
    private fun packageDirectory(cwd: String?, prefix: String?): String? {
        val parts = listOfNotNull(cwd, prefix).map(::normalize).filter { it.isNotEmpty() && it != "." }
        if (parts.any { it.startsWith("/") || it.startsWith("..") || it.matches(DRIVE) }) return null
        return parts.joinToString("/")
    }

    /**
     * Index in [argv] (directory option consumed) of the first argument the package manager forwards to the script:
     * the one after `--`, else the one after the script name (`run <name>`, `test`, or a bare `<name>` for yarn and
     * pnpm); `argv.size` when there is none.
     */
    private fun forwardedFrom(argv: List<String>): Int {
        var run = false
        for (i in 1 until argv.size) {
            val arg = argv[i]
            when {
                arg == "--" -> return i + 1
                arg.startsWith("-") -> Unit
                !run && (arg == "run" || arg == "run-script") -> run = true
                else -> return i + 1
            }
        }
        return argv.size
    }

    /** A relative path-like script token (`scripts/validate.js`, `--config=cfg/x.json`), normalised; null for anything else. */
    private fun pathToken(token: String): String? {
        val raw = token.trim('"', '\'').let { if (it.startsWith("-")) it.substringAfter('=', "") else it }
        if (raw.isEmpty() || "://" in raw || raw.startsWith("/") || raw.startsWith("$") || raw.startsWith("%") || raw.matches(DRIVE)) return null
        val path = normalize(raw)
        if (path.isEmpty() || path == "." || path.startsWith("..")) return null
        return path.takeIf { '/' in it || EXTENSION.containsMatchIn(it) }
    }

    private val PACKAGE_MANAGERS = setOf("npm", "pnpm", "yarn")
    private val PREFIX_FLAGS = setOf("--prefix", "--cwd", "--dir", "-C")
    private val SCRIPT_SEPARATORS = Regex("""[\s;|&()<>]+""")
    private val EXTENSION = Regex("""\.[A-Za-z][A-Za-z0-9]*$""")
    private val DRIVE = Regex("^[A-Za-z]:.*")

    /** Reads `package.json` texts through [workspace]'s path contract, for [Checks.seed]. */
    internal fun packageManifests(workspace: Workspace): (String) -> String? = { directory ->
        (workspace.resolve(if (directory.isEmpty()) "package.json" else "$directory/package.json", Intent.Read) as? PathResolution.Resolved)
            ?.let(workspace::bytes)?.toString(Charsets.UTF_8)
    }

    /**
     * True when an argv token or the relative executable, resolved against [cwd], is [path] or a directory above it.
     * The bare [cwd] names nothing (D-369): it would make every source file of the package a command input; the
     * package's manifest is a check definition by name.
     */
    private fun namesPath(command: List<String>, cwd: String?, path: String): Boolean {
        val pkg = packageCommand(command)
        val argv = pkg?.argv ?: command
        val base = cwd?.let { normalize(it) }?.takeIf { it.isNotEmpty() && it != "." }
        // D-375: the script runs in the package directory, so the arguments forwarded to it resolve there.
        val forwarded = if (pkg?.directory == null) argv.size else forwardedFrom(argv)
        val scriptBase = packageDirectory(cwd, pkg?.directory)?.takeIf { it.isNotEmpty() } ?: base
        // Test the raw spelling: normalize strips "./", which would drop a root-level `./check.sh`.
        val executable = argv.firstOrNull()?.takeIf {
            val named = it.replace('\\', '/')
            '/' in named && !named.startsWith('/') && !named.matches(Regex("^[A-Za-z]:.*"))
        }
        val candidates = resolved(argv.subList(1, forwarded) + listOfNotNull(executable), base) + resolved(argv.drop(forwarded), scriptBase)
        return candidates.any { path == it || path.startsWith("$it/") }
    }

    private fun resolved(tokens: List<String>, base: String?): List<String> =
        tokens.map { normalize(it) }.filter { it.isNotEmpty() && !it.startsWith("-") }
            .map { if (base != null && !it.startsWith("$base/")) "$base/$it" else it }

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./").trimEnd('/')
}

/** One changed path for [TestIntegrity.classify]: its text before and after (`null` = absent, or bytes not captured). */
public data class SurfaceChange(val path: String, val before: String?, val after: String?)
