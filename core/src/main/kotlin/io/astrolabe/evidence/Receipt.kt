package io.astrolabe.evidence

import io.astrolabe.contract.Origin
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Identities
import io.astrolabe.id.InstantSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Historical outcome vocabulary (§8.4); applicability is computed separately and never overwrites it. */
@Serializable
public enum class Outcome {
    Passed,
    Failed,
    Timeout,
    InfraError,
    Inconclusive,
    NotRun,
    Unavailable,
    Denied,
    UnknownOutcome,
    ;

    /** Only a parsed, executed pass is green (F16). */
    public val green: Boolean get() = this == Passed
}

/** Parsed counts from runner output; never derived from an exit code alone (§8.3). */
@Serializable
public data class Counts(
    val passed: Int = 0,
    val failed: Int = 0,
    val errors: Int = 0,
    val skipped: Int = 0,
    val discovered: Int = 0,
) {
    init {
        require(passed >= 0 && failed >= 0 && errors >= 0 && skipped >= 0 && discovered >= 0) { "counts must be ≥ 0" }
    }

    val executed: Int get() = passed + failed + errors
}

/** What invalidates a check (§8.1): joins the coherence protocol (§4.4). */
@Serializable
public sealed interface Closure {
    @Serializable
    @SerialName("known")
    public data class Known(val paths: Set<String>) : Closure

    @Serializable
    @SerialName("package")
    public data class Package(val path: String) : Closure

    @Serializable
    @SerialName("unknown")
    public object Unknown : Closure {
        override fun toString(): String = "unknown"
    }
}

/** Stability of the inputs during a check (D-45): only exclusive or isolated inputs can certify a tree. */
@Serializable
public enum class InputStability { Exclusive, Isolated, Unknown }

/** Paths@versions the check actually depended on (declared closure ∪ observed access), D-45. */
@Serializable
public data class TestedInputs(
    val versions: Map<String, FileVersion>,
    val stability: InputStability,
    /** Relevant inputs that changed during the check (rescan after the run); non-empty ⇒ ineligible. */
    val mutatedDuringCheck: Set<String> = emptySet(),
) {
    val eligible: Boolean get() = stability != InputStability.Unknown && mutatedDuringCheck.isEmpty()
}

/**
 * One failing test a run reported (§8.5, P8.C.10): [key], an opaque digest of its namespaced identity, is what decisions
 * compare; [name] and [signature] (the first failure line) are redacted and only shown — no failure text is ever compared.
 */
@Serializable
public data class FailedTest(val key: String, val name: String, val signature: String)

/**
 * The per-test outcomes a run of a harness regression check reported (P8.C.10): its failing tests, the keys of the tests
 * that executed and passed, and the keys reported more than once whatever their outcome (ambiguous, D-27). [truncated]
 * when a bound cut a list, [incomplete] when the capture or the structured report could not be read whole: either way
 * the record shows nothing fixed and nothing new.
 */
@Serializable
public data class TestOutcomes(
    val failed: List<FailedTest> = emptyList(),
    val passed: List<String> = emptyList(),
    val ambiguous: List<String> = emptyList(),
    val truncated: Boolean = false,
    val incomplete: Boolean = false,
)

/** Where evidence about a receipt was truncated or could not be captured (§8.4 limitations). */
@Serializable
public data class Limit(val kind: String, val detail: String)

/**
 * What a check's pass proves (plan §4.4): the build passes, the typecheck passes, or tests were executed and passed.
 * Exit 0 never reads as "tests passed"; only a host or user command's [Build] and [Typecheck] evidence may pass on its
 * expected exit without test counts (owner-approved relaxation of D-50). [wire] is the spelling in renders.
 */
@Serializable
public enum class EvidenceKind(public val wire: String) {
    Tests("tests"),
    Build("build"),
    Typecheck("typecheck"),
    ;

    /** The expected exit alone is the evidence: nothing is counted when a build or a typecheck passes. */
    public val exitSuffices: Boolean get() = this != Tests
}

/**
 * Immutable verification receipt (§4.3, §8.4, TODO P1.4.2). It supports exactly the candidate it tested:
 * `current` for a later candidate is computed from closures (P1.4.4/P3.1.2), never stored here.
 */
@Serializable
public data class Receipt(
    val receiptId: String,
    val ids: Identities,
    val checkId: String,
    val acceptanceIds: List<String>,
    val command: List<String>,
    val cwd: String?,
    /** True when the command ran through one shell invocation (`cmd` form). */
    val shell: Boolean,
    val stampBefore: CandidateId,
    val stampAfter: CandidateId,
    val envId: Digest,
    val verifierVersion: String,
    val checkDefinitionVersion: Digest,
    val contractVersion: Int,
    val outcome: Outcome,
    val parsed: Counts?,
    val inputClosure: Closure,
    val testedInputs: TestedInputs,
    /** Content-addressed raw output. */
    val raw: Digest?,
    val limits: List<Limit> = emptyList(),
    val reuseOf: String? = null,
    val exitCode: Int? = null,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    /** The input closure pinned before the run (§8.1 `closure_manifest`); only a complete one can back a reuse proof. */
    val closureManifest: ClosureManifest? = null,
    /** The check's expected process exit; null when a product QA case asserts only output. HTTP has no process exit. */
    val expectedExitCode: Int? = 0,
    /** What the check's pass proves (plan §4.4): its declared kind, else a label recognised from the tool; null before kinds. */
    val evidenceKind: EvidenceKind? = null,
    /** Who created the check (plan §4.4, C2 provenance axis); `model(…)` marks an agent's own test, null when unknown. */
    val checkOrigin: Origin? = null,
    /** True when the host or the user declared [evidenceKind]; a recognised kind is a label and never decides a pass. */
    val evidenceDeclared: Boolean = false,
    /** What a run of a harness regression check reported test by test (P8.C.10); `null` for any other check or an older receipt. */
    val tests: TestOutcomes? = null,
    /**
     * The id of the output policy ([io.astrolabe.verify.ScratchPolicy.id], W3) the run's stamps, tested inputs and closure
     * manifest were taken under; `null` before W3 or under none. A receipt is current only for the policy it was recorded
     * under. Not encoded at its default, so an older receipt keeps its bytes.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val inputPolicy: String? = null,
) {
    /** The constructor before [inputPolicy] (W3). Kept for Java callers. */
    public constructor(
        receiptId: String,
        ids: Identities,
        checkId: String,
        acceptanceIds: List<String>,
        command: List<String>,
        cwd: String?,
        shell: Boolean,
        stampBefore: CandidateId,
        stampAfter: CandidateId,
        envId: Digest,
        verifierVersion: String,
        checkDefinitionVersion: Digest,
        contractVersion: Int,
        outcome: Outcome,
        parsed: Counts?,
        inputClosure: Closure,
        testedInputs: TestedInputs,
        raw: Digest?,
        limits: List<Limit>,
        reuseOf: String?,
        exitCode: Int?,
        at: Instant,
        closureManifest: ClosureManifest?,
        expectedExitCode: Int?,
        evidenceKind: EvidenceKind?,
        checkOrigin: Origin?,
        evidenceDeclared: Boolean,
        tests: TestOutcomes?,
    ) : this(
        receiptId, ids, checkId, acceptanceIds, command, cwd, shell, stampBefore, stampAfter, envId, verifierVersion, checkDefinitionVersion,
        contractVersion, outcome, parsed, inputClosure, testedInputs, raw, limits, reuseOf, exitCode, at, closureManifest, expectedExitCode,
        evidenceKind, checkOrigin, evidenceDeclared, tests, null,
    )

    /** The constructor before [tests] (P8.C.10). Kept for Java callers. */
    public constructor(
        receiptId: String,
        ids: Identities,
        checkId: String,
        acceptanceIds: List<String>,
        command: List<String>,
        cwd: String?,
        shell: Boolean,
        stampBefore: CandidateId,
        stampAfter: CandidateId,
        envId: Digest,
        verifierVersion: String,
        checkDefinitionVersion: Digest,
        contractVersion: Int,
        outcome: Outcome,
        parsed: Counts?,
        inputClosure: Closure,
        testedInputs: TestedInputs,
        raw: Digest?,
        limits: List<Limit>,
        reuseOf: String?,
        exitCode: Int?,
        at: Instant,
        closureManifest: ClosureManifest?,
        expectedExitCode: Int?,
        evidenceKind: EvidenceKind?,
        checkOrigin: Origin?,
        evidenceDeclared: Boolean,
    ) : this(
        receiptId, ids, checkId, acceptanceIds, command, cwd, shell, stampBefore, stampAfter, envId, verifierVersion, checkDefinitionVersion,
        contractVersion, outcome, parsed, inputClosure, testedInputs, raw, limits, reuseOf, exitCode, at, closureManifest, expectedExitCode,
        evidenceKind, checkOrigin, evidenceDeclared, null,
    )

    /** The v1.0 full constructor: the evidence kind, the check's origin and the declaration take their defaults. Kept for Java callers. */
    public constructor(
        receiptId: String,
        ids: Identities,
        checkId: String,
        acceptanceIds: List<String>,
        command: List<String>,
        cwd: String?,
        shell: Boolean,
        stampBefore: CandidateId,
        stampAfter: CandidateId,
        envId: Digest,
        verifierVersion: String,
        checkDefinitionVersion: Digest,
        contractVersion: Int,
        outcome: Outcome,
        parsed: Counts?,
        inputClosure: Closure,
        testedInputs: TestedInputs,
        raw: Digest?,
        limits: List<Limit>,
        reuseOf: String?,
        exitCode: Int?,
        at: Instant,
        closureManifest: ClosureManifest?,
        expectedExitCode: Int?,
    ) : this(
        receiptId, ids, checkId, acceptanceIds, command, cwd, shell, stampBefore, stampAfter, envId, verifierVersion, checkDefinitionVersion,
        contractVersion, outcome, parsed, inputClosure, testedInputs, raw, limits, reuseOf, exitCode, at, closureManifest, expectedExitCode,
        null, null, false, null,
    )

    init {
        require(receiptId.isNotBlank() && checkId.isNotBlank()) { "receipt needs ids" }
        if (outcome == Outcome.Passed) {
            require(parsed == null || (parsed.failed == 0 && parsed.errors == 0)) { "a passed receipt cannot contain failures" }
            require(exitCode == null || expectedExitCode == null || exitCode == expectedExitCode) { "a passed receipt cannot contradict its expected exit" }
        }
        require(!(outcome == Outcome.Passed && parsed == null) || passesOnExit) { "a passed receipt needs parsed counts" }
        require(!(outcome == Outcome.Passed && parsed != null && parsed.executed == 0 && parsed.discovered == 0)) {
            "a passed receipt with nothing executed is inconclusive, not green"
        }
    }

    /** Green only with parsed counts and a mutation-free, non-unknown input stability (D-45). */
    val greenForFinalTree: Boolean get() = outcome.green && testedInputs.eligible

    /** Declared build or typecheck evidence of a host or user command: its expected exit is the evidence (plan §4.4, D-50 relaxed). */
    val passesOnExit: Boolean get() = evidenceDeclared && evidenceKind?.exitSuffices == true && checkOrigin !is Origin.Model

    /** False for an agent's own test (plan §4.4): evidence of the model's check, never independent acceptance. */
    val independent: Boolean get() = checkOrigin !is Origin.Model
}

/** Source line ↔ rendered line mapping of a redacted view (D-49); hidden lines grant no coverage. */
@Serializable
public data class RedactionMask(
    /** Source lines that were replaced, shortened or removed. */
    val hiddenLines: io.astrolabe.workspace.Ranges = io.astrolabe.workspace.Ranges.EMPTY,
    /** Free-text limitations (patterns that could not be applied, size caps). */
    val limitations: List<String> = emptyList(),
) {
    val applied: Boolean get() = !hiddenLines.isEmpty

    public companion object {
        @JvmField
        public val NONE: RedactionMask = RedactionMask()
    }
}

/** What the model actually saw (§4.3). */
@Serializable
public data class Observation(
    val id: String,
    val ids: Identities,
    val actionId: String,
    val candidate: CandidateId?,
    /** Content-addressed captured bytes (the rendered view). */
    val contentRef: Digest,
    val paths: List<String>,
    val ranges: Map<String, io.astrolabe.workspace.Ranges>,
    val complete: Boolean,
    val sourceVersions: Map<String, FileVersion>,
    val captureComplete: Boolean,
    val redaction: RedactionMask = RedactionMask.NONE,
    val truncated: Boolean = false,
) {
    /** Coverage this observation grants for [path]: displayed ranges minus redacted lines (D-49). */
    public fun coverage(path: String): io.astrolabe.workspace.Ranges = (ranges[path] ?: io.astrolabe.workspace.Ranges.EMPTY) - redaction.hiddenLines
}

@Serializable
public enum class ClaimKind {
    @SerialName("h")
    Hypothesis,

    @SerialName("v")
    Verified,

    @SerialName("x")
    Refuted,
}

@Serializable
public enum class EvidenceState { Hypothesis, Supported, Refuted, Stale, Unknown }

@Serializable
public enum class ClaimAuthority { User, Rules, Observed, Inferred }

@Serializable
public enum class Freshness { Current, Stale, Unknown }

/** Register facts and note claims share this shape (§4.3); epistemic kind and freshness are separate axes. */
@Serializable
public data class Claim(
    val id: String,
    val text: String,
    val kind: ClaimKind,
    val evidenceState: EvidenceState,
    val authority: ClaimAuthority,
    val freshness: Freshness,
    val evidenceRefs: List<String> = emptyList(),
    /** `path@version` anchor when the claim is about source. */
    val anchor: Anchor? = null,
) {
    init {
        if (kind == ClaimKind.Verified) require(evidenceRefs.isNotEmpty()) { "a verified claim needs an evidence id" }
    }
}

@Serializable
public data class Anchor(val path: String, val version: FileVersion, val line: Int? = null) {
    override fun toString(): String = "$path" + (line?.let { ":$it" } ?: "") + " @${version.hash8}"
}
