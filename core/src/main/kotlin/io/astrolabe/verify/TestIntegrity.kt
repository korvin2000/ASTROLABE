package io.astrolabe.verify

import io.astrolabe.atlas.Manifest
import io.astrolabe.atlas.isTestPath
import io.astrolabe.contract.Acceptance
import io.astrolabe.contract.Contract
import io.astrolabe.event.Authority
import io.astrolabe.evidence.Closure
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Identities

/** Which part of the acceptance surface a touched path belongs to (§8.6). */
public enum class AcceptanceSurface(public val wire: String) {
    TestFile("test file"),
    CheckDefinition("check definition"),
    CiConfig("CI config"),
    AcceptanceCommand("acceptance command input"),
}

/**
 * One acceptance-surface detection (§8.6). The P1 baseline never classifies *how* a change weakens a check:
 * [kind] stays [TestIntegrity.UNCLASSIFIED] (P3.4.2 adds deleted tests, weakened assertions, skip markers,
 * snapshot updates). [requiredChecks] are the required checks the path can affect; while any exist and no
 * approving [verdict] is attached, the increment cannot complete. [reason] is the worker's recorded
 * justification, rendered with the line and in the finish receipt (`acceptance_surface_modified`).
 */
public data class TestIntegrityFlag(
    val path: String,
    val surface: AcceptanceSurface,
    val cause: String,
    val requiredChecks: List<String>,
    val kind: String = TestIntegrity.UNCLASSIFIED,
    val reason: String? = null,
    val verdict: Verdict? = null,
) {
    /** Unknown classification is never proof of no weakening: a touched required check needs an approving review. */
    val blocksCompletion: Boolean get() = requiredChecks.isNotEmpty() && verdict?.approved != true

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
            val surface = surfaceOf(path, contract) ?: return@mapNotNull null
            TestIntegrityFlag(path, surface, cause, requiredChecksFor(path, surface, checks))
        }

    /** The surface [path] belongs to, or `null` when it is ordinary source. CI and check definitions outrank the test-file convention. */
    @JvmStatic
    public fun surfaceOf(path: String, contract: Contract): AcceptanceSurface? {
        val relative = normalize(path)
        val name = relative.substringAfterLast('/')
        val first = relative.substringBefore('/')
        return when {
            first in CI_PREFIXES || name in CI_NAMES -> AcceptanceSurface.CiConfig
            name in CHECK_DEFINITION_NAMES || CHECK_DEFINITION_PREFIXES.any { name.startsWith(it) } -> AcceptanceSurface.CheckDefinition
            isTestPath(relative) -> AcceptanceSurface.TestFile
            contract.acceptance.filterIsInstance<Acceptance.Run>().any { namesPath(it.command.argv, it.command.cwd, relative) } ->
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
            flags.mapNotNull { flag -> originals[flag.path]?.let { "original ${flag.path}: $it" } }
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
            } || (check.command?.let { namesPath(it.argv, it.cwd, path) } ?: false)
        }
    }.map { it.id }

    /** True when an argv token (resolved against [cwd]) or [cwd] itself is [path] or a directory above it. */
    private fun namesPath(argv: List<String>, cwd: String?, path: String): Boolean {
        val base = cwd?.let { normalize(it) }?.takeIf { it.isNotEmpty() && it != "." }
        val candidates = argv.drop(1).map { normalize(it) }.filter { it.isNotEmpty() && !it.startsWith("-") }
            .map { if (base != null && !it.startsWith("$base/")) "$base/$it" else it } + listOfNotNull(base)
        return candidates.any { path == it || path.startsWith("$it/") }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').removePrefix("./").trimEnd('/')
}
