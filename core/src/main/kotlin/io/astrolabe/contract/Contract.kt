package io.astrolabe.contract

import io.astrolabe.DClassPolicy
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.event.Proposer
import io.astrolabe.evidence.EvidenceKind
import io.astrolabe.graph.Production
import io.astrolabe.graph.RedOkUntil
import io.astrolabe.graph.Sizing
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.Digest
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.workspace.PathPattern
import io.astrolabe.workspace.ProtectedPaths
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/** Orchestration shapes (§3.5). */
@Serializable
public enum class Shape { S0, S1, S2, S3 }

/**
 * The kind of a message sent to a work (task-workflow §2.1, D-433). The host declares it; the model never classifies a
 * user message. Only [Amendment] changes the contract's revision.
 */
@Serializable
public enum class MessageKind(public val wire: String) {
    /** The first message of a work. */
    @SerialName("request") Request("request"),

    /** "Go on", "yes", an acknowledgement: no new authority. */
    @SerialName("continuation") Continuation("continuation"),

    /** How, not what — and every untyped free text short of a final outcome (§2.2). */
    @SerialName("steering") Steering("steering"),

    /** What: only the explicit "change the task", a resolved model proposal or a `rework(text)` decision; never a default. */
    @SerialName("amendment") Amendment("amendment"),

    /** The answer to a pending `task.ask`; an amendment too only when it changes requirements (D-317). */
    @SerialName("answer") Answer("answer"),
}

/**
 * A verbatim user message; the list on the contract is append-only (§4.1, task-workflow §1.1). [kind] is `null` in a
 * contract stored before W7 ([Contract.kindOf] reads it), [answers] names the question an [MessageKind.Answer] answers,
 * [hostRef] makes a host's retried delivery idempotent. None is encoded at its default, so an older contract keeps its bytes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class UserRequest(
    val id: String,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val text: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: MessageKind? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val answers: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hostRef: String? = null,
) {
    /** The constructor before W7: a message of no recorded kind. Kept for Java callers. */
    public constructor(id: String, at: Instant, text: String) : this(id, at, text, null, null, null)

    init {
        require(id.isNotBlank() && text.isNotBlank()) { "request needs an id and text" }
        require(answers == null || kind == MessageKind.Answer || kind == MessageKind.Amendment) { "only an answer names the question it answers" }
    }
}

/** Who declared a task output (task-workflow §5.1, D-435). */
@Serializable
public enum class OutputDeclarer { User, Host, Model }

/**
 * A path the task declares as its own output (task-workflow §5.1): recorded append-only at the contract [version] that
 * declared it; it leaves candidate identity from the next attempt (W8 applies it). [cell] names the model's cell.
 */
@Serializable
public data class DeclaredOutput(
    val path: String,
    val by: OutputDeclarer,
    val reason: String,
    val version: Int,
    val cell: ContextId? = null,
) {
    init {
        require(path.isNotBlank() && reason.isNotBlank() && version >= 1) { "a declared output names its path, reason and version" }
    }
}

/** Harness-derived, never model-written (§4.1). [wire] is the docs' snake_case spelling used in renders. */
@Serializable
public enum class RequirementStatus(public val wire: String) {
    Pending("pending"),
    InProgress("in_progress"),
    Verified("verified"),
    Blocked("blocked"),

    /** The user cancelled or replaced the requirement (task-workflow §2.4 C): it no longer holds the campaign open. */
    Cancelled("cancelled"),
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class Requirement(
    val id: String,
    val text: String,
    val acceptance: List<String>,
    val dependsOn: List<String> = emptyList(),
    val authorityRef: String,
    val status: RequirementStatus = RequirementStatus.Pending,
    /** Why the user cancelled it (task-workflow §2.4 C); its text never changes. Not encoded when absent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cancelledReason: String? = null,
    /** The requirement that replaced it (§2.4 C). Not encoded when absent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val supersededBy: String? = null,
) {
    /** The constructor before W7. Kept for Java callers. */
    public constructor(id: String, text: String, acceptance: List<String>, dependsOn: List<String>, authorityRef: String, status: RequirementStatus) :
        this(id, text, acceptance, dependsOn, authorityRef, status, null, null)

    init {
        require(id.isNotBlank() && text.isNotBlank() && authorityRef.isNotBlank()) { "requirement needs id, text and authority" }
    }

    /** Cancelled or replaced by the user (§2.4 C): it holds nothing open, and its history stays. */
    val lapsed: Boolean get() = cancelledReason != null || supersededBy != null
}

/** Where an acceptance item came from (§4.1). The model may only add ([Model], `strengthens`). */
@Serializable
public sealed interface Origin {
    @Serializable
    @SerialName("user")
    public object User : Origin {
        override fun toString(): String = "user"
    }

    @Serializable
    @SerialName("harness")
    public object Harness : Origin {
        override fun toString(): String = "harness"
    }

    @Serializable
    @SerialName("model")
    public data class Model(val strengthens: String) : Origin {
        override fun toString(): String = "model(strengthens $strengthens)"
    }

    @Serializable
    @SerialName("amended")
    public data class Amended(val version: Int) : Origin {
        override fun toString(): String = "amended@v$version"
    }
}

/**
 * What an acceptance item is evidence of (task-workflow §3.1, D-434): [Goal] — it checks a named requirement of this task;
 * [Regression] — it shows nothing regressed (the sniffed suite, a command saved in the host, quality gates).
 */
@Serializable
public enum class EvidencePurpose(public val wire: String) {
    @SerialName("goal") Goal("goal"),
    @SerialName("regression") Regression("regression"),
}

/** An executable command: argv form is canonical; [cwd] is workspace-relative. */
@Serializable
public data class Command(val argv: List<String>, val cwd: String? = null) {
    init {
        require(argv.isNotEmpty() && argv.first().isNotBlank()) { "command needs a program" }
    }

    /** Shell-like rendering for digests and receipts; never executed as shell. */
    val text: String get() = argv.joinToString(" ") { if (it.any { c -> c.isWhitespace() }) "\"$it\"" else it }
}

@Serializable
public data class LastRun(val receiptId: String, val stamp: CandidateId, val current: Boolean)

/**
 * Acceptance kinds (§4.1): `run:` executed by the harness, green only with a current stamp; `check:` a claim
 * needing an accepted evidence reference; `review:` signed by the judge or a human. Every item is a regression
 * obligation once green. [obligationVersion] is the contract version that introduced or last amended the item
 * (D-52): assessments bind it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public sealed interface Acceptance {
    public val id: String
    public val origin: Origin
    public val obligationVersion: Int

    /** The stored evidence purpose (task-workflow §3.1); `null` on an item stored before W8 — [evidencePurpose] reads it. */
    public val purpose: EvidencePurpose?

    /** §3.1: the stored field decides; a legacy item reads `harness → regression`, `user | amended | model → goal`. */
    public val evidencePurpose: EvidencePurpose
        get() = purpose ?: if (origin is Origin.Harness) EvidencePurpose.Regression else EvidencePurpose.Goal

    /** One-line criterion text for digests and slices. */
    public val criterion: String

    @Serializable
    @SerialName("run")
    public data class Run(
        override val id: String,
        val command: Command,
        override val origin: Origin,
        /** `touched` for auto-derived suites, or a named selector. */
        val scope: String? = null,
        val last: LastRun? = null,
        override val obligationVersion: Int = 1,
        /** What a pass proves when the host or the user declares it (plan §4.4); null: only a label recognised from the tool. */
        val evidence: EvidenceKind? = null,
        /** Not encoded when absent, so an item stored before W8 keeps its bytes. */
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val purpose: EvidencePurpose? = null,
    ) : Acceptance {
        /** The v1.0 full constructor: no declared evidence kind. Kept for Java callers. */
        public constructor(id: String, command: Command, origin: Origin, scope: String?, last: LastRun?, obligationVersion: Int) :
            this(id, command, origin, scope, last, obligationVersion, null, null)

        /** The constructor before [purpose] (W8). Kept for Java callers. */
        public constructor(id: String, command: Command, origin: Origin, scope: String?, last: LastRun?, obligationVersion: Int, evidence: EvidenceKind?) :
            this(id, command, origin, scope, last, obligationVersion, evidence, null)

        override val criterion: String get() = "run: ${command.text}" + (scope?.let { " (scope $it)" } ?: "") + (evidence?.let { " [${it.wire}]" } ?: "")
    }

    @Serializable
    @SerialName("check")
    public data class Check(
        override val id: String,
        val text: String,
        override val origin: Origin,
        val evidenceRef: String? = null,
        override val obligationVersion: Int = 1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val purpose: EvidencePurpose? = null,
    ) : Acceptance {
        /** The constructor before [purpose] (W8). Kept for Java callers. */
        public constructor(id: String, text: String, origin: Origin, evidenceRef: String?, obligationVersion: Int) : this(id, text, origin, evidenceRef, obligationVersion, null)

        override val criterion: String get() = "check: $text"
    }

    @Serializable
    @SerialName("review")
    public data class Review(
        override val id: String,
        val text: String,
        override val origin: Origin,
        val signedBy: String? = null,
        override val obligationVersion: Int = 1,
        @EncodeDefault(EncodeDefault.Mode.NEVER) override val purpose: EvidencePurpose? = null,
    ) : Acceptance {
        /** The constructor before [purpose] (W8). Kept for Java callers. */
        public constructor(id: String, text: String, origin: Origin, signedBy: String?, obligationVersion: Int) : this(id, text, origin, signedBy, obligationVersion, null)

        override val criterion: String get() = "review: $text"
    }
}

@Serializable
public data class Constraint(val id: String, val text: String, val authority: String)

/**
 * Write scope bounds possible writes; it authorizes no unrelated work (D-31). Entries follow the
 * [PathPattern] convention: `dir/` is a directory prefix, a bare `name` matches that file name anywhere in
 * the tree (lock files are not rooted), anything with `*`/`?` is a glob, and [REPOSITORY] is the whole tree.
 */
@Serializable
public data class Scope(val writePaths: List<String>, val protectedPaths: List<String>) {
    /** True when a write path of this scope names [relative]. */
    public fun covers(relative: String): Boolean = writePaths.any { PathPattern.matches(it, relative) }

    /** True when a protected entry names [relative] (D-class: never written without committed authority). */
    @JvmOverloads
    public fun protects(relative: String, ignoreCase: Boolean = false): Boolean = protectedPaths.any { PathPattern.matches(it, relative, ignoreCase) }

    /** §8.6 scope guard rule for one path: inside the write scope and outside the protected list. */
    public fun allowsWrite(relative: String): Boolean = covers(relative) && !protects(relative)

    public companion object {
        /** The glob that names the whole repository. */
        public const val REPOSITORY: String = "**"

        /**
         * D-31 default for S0: repository-local writes minus the protected defaults (`.git/`, CI config, lock
         * files, migration directories), rendered from the path contract in force so contract and enforcement
         * agree on the list.
         */
        @JvmStatic
        public fun repositoryMinus(protected: ProtectedPaths): Scope = Scope(
            writePaths = listOf(REPOSITORY),
            protectedPaths = protected.writeDeniedPrefixes.sorted().map { "$it/" } + protected.writeDeniedNames.sorted(),
        )
    }
}

@Serializable
public data class Authorization(
    val ladderCeiling: Stage,
    val dClass: DClassPolicy,
    val capabilitySet: String,
    /** D-class effects the contract allowlists for autonomous approval (§4.6). */
    val dClassAllowlist: List<String> = emptyList(),
)

@Serializable
public enum class Reversibility { Easy, Hard }

@Serializable
public data class Risk(val blastRadius: Int, val reversibility: Reversibility, val contractTouch: Boolean)

@Serializable
public enum class AmendmentStatus { Pending, Accepted, Rejected }

/** A proposed contract change (§4.1); policy never auto-accepts a weakening. */
@Serializable
public data class Amendment(
    val id: String,
    val by: Proposer,
    val cell: ContextId?,
    val change: String,
    val reason: String,
    val weakening: Boolean,
    val status: AmendmentStatus = AmendmentStatus.Pending,
    val resolvedBy: String? = null,
)

/**
 * The Task Contract (§4.1): harness-owned, user-authoritative, versioned. The version bumps only on an
 * authorized amendment ([Contracts]); the model's only direct effect is [strengthen]. Everything the model may
 * touch goes through proposals.
 */
@Serializable
public data class Contract(
    val workId: WorkId,
    val version: Int,
    val attemptId: AttemptId,
    val mode: Mode,
    val shape: Shape,
    val requests: List<UserRequest>,
    val requirements: List<Requirement>,
    val acceptance: List<Acceptance>,
    val constraints: List<Constraint>,
    val exclusions: List<String>,
    val contractsTouched: List<String>,
    val scope: Scope,
    val budget: Budget,
    val authorization: Authorization,
    val risk: Risk? = null,
    val amendmentsPending: List<Amendment> = emptyList(),
    /**
     * The output policy the attempt froze before its `s0` (§8.4, W3, owner №32), recorded for authority and display:
     * untracked output under these anchored roots is outside the candidate. `null` for a contract derived before W3,
     * whose attempt has none. Not encoded when absent, so an older contract keeps its bytes.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val scratch: io.astrolabe.verify.ScratchPolicy? = null,
    /**
     * The work this one follows up (task-workflow §1.4): set by the host at the first open, immutable after it; the core
     * opens it only when the parent has a final outcome. `null` for a first run or a new task. Not encoded when absent.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val parentWork: WorkId? = null,
    /** The task's declared outputs (task-workflow §5.1), append-only; W8 applies them from the next attempt. Not encoded when empty. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val outputs: List<DeclaredOutput> = emptyList(),
) {
    init {
        require(version >= 1) { "contract version starts at 1" }
        require(requests.isNotEmpty()) { "a contract carries at least one verbatim request" }
        require(acceptance.map { it.id }.toSet().size == acceptance.size) { "acceptance ids must be unique" }
        require(requirements.map { it.id }.toSet().size == requirements.size) { "requirement ids must be unique" }
        val acceptanceIds = acceptance.map { it.id }.toSet()
        requirements.forEach { r ->
            require(r.acceptance.all { it in acceptanceIds }) { "requirement ${r.id} references unknown acceptance ${r.acceptance - acceptanceIds}" }
        }
    }

    public fun acceptance(id: String): Acceptance? = acceptance.firstOrNull { it.id == id }

    public fun requirement(id: String): Requirement? = requirements.firstOrNull { it.id == id }

    /** The one model-side mutation: adding a strengthening item; never removes or edits an existing one. */
    public fun strengthen(item: Acceptance): Contract {
        require(item.origin is Origin.Model) { "a model-added acceptance item must carry origin model(strengthens …)" }
        require(acceptance(item.id) == null) { "acceptance ${item.id} already exists; existing items cannot be edited" }
        return copy(acceptance = acceptance + item)
    }

    /** [request]'s kind; a request stored before W7 reads as the first `request` and every later one an `amendment` (§1.1). */
    public fun kindOf(request: UserRequest): MessageKind =
        request.kind ?: if (request.id == requests.first().id) MessageKind.Request else MessageKind.Amendment

    /** The messages the objective is made of (task-workflow §1.2): the original request, then every amendment in order. */
    val objectiveRequests: List<UserRequest>
        get() = requests.filterIndexed { i, r -> i == 0 || kindOf(r) == MessageKind.Amendment }

    /**
     * The currently authorized objective (WD-24, task-workflow §1.2): the original request plus every amendment, in order,
     * each after its id — never the last text. Steering and continuation messages are pinned verbatim but are not the goal.
     */
    val objective: String
        get() = objectiveRequests.let { goal ->
            if (goal.size == 1) goal.single().text
            else goal.mapIndexed { i, r -> if (i == 0) "${r.id}: ${r.text}" else "amended by ${r.id}: ${r.text}" }.joinToString("\n")
        }

    /**
     * The requirements [itemId] checks (task-workflow §3.1 `checks`): the inverse of [Requirement.acceptance], plus each one
     * a model item names in `strengthens` (`R1+R2`).
     */
    public fun checks(itemId: String): List<String> {
        val strengthens = (acceptance(itemId)?.origin as? Origin.Model)?.strengthens?.split('+').orEmpty()
        return requirements.filter { itemId in it.acceptance || it.id in strengthens }.map { it.id }
    }

    /**
     * §3.1 (WD-20): the contract has an item of purpose `goal`. A regression item — the sniffed suite, a saved command —
     * is not goal acceptance: while this is false the model must state one or ask one question (entry gate, P1.8.5).
     */
    val goalAcceptanceStated: Boolean get() = acceptance.any { it.evidencePurpose == EvidencePurpose.Goal }
}

/** Increment status (§4.2). */
@Serializable
public enum class IncrementStatus { Pending, InProgress, Verified, Blocked, Cancelled }

/**
 * Increment (§4.2). S0 callers may omit graph fields; S1 proposals must pass RequirementGraph.validate.
 * Status, cells and sizing are harness-owned. [produces] has no inferred default for an S1 proposal.
 */
@Serializable
public data class Increment(
    val id: String,
    val requirementIds: List<String>,
    val accept: List<String>,
    val writeScope: List<String>,
    val expectedFiles: Int,
    val status: IncrementStatus = IncrementStatus.Pending,
    val title: String = "",
    val cancelledReason: String? = null,
    val dependsOn: List<String> = emptyList(),
    val risk: Risk? = null,
    val redOkUntil: RedOkUntil? = null,
    val produces: Production? = null,
    val cells: List<ContextId> = emptyList(),
    val sizing: Sizing = Sizing(),
    /** Acceptance.Check id -> required evidence kind (e.g. diff, refs or run); not an assessment. */
    val evidenceKinds: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank() && requirementIds.isNotEmpty()) { "increment needs an id and requirements" }
        require(expectedFiles >= 0) { "expectedFiles must be ≥ 0" }
        require(status != IncrementStatus.Cancelled || !cancelledReason.isNullOrBlank()) {
            "a cancelled increment retains its reason"
        }
    }

    /** Canonical plan identity, excluding runtime history; binds verification across reordering/replanning. */
    public fun definitionDigest(): Digest = Digest.ofUtf8(Json.encodeToString(serializer(), copy(
        requirementIds = requirementIds.distinct().sorted(), accept = accept.distinct().sorted(),
        writeScope = writeScope.distinct().sorted(), dependsOn = dependsOn.distinct().sorted(),
        evidenceKinds = evidenceKinds.toSortedMap(), status = IncrementStatus.Pending,
        cancelledReason = null, cells = emptyList(), sizing = Sizing(),
    )))
}

/** Ledger row per requirement (§4.2): harness-derived, changed only by the controller on receipts. */
@Serializable
public data class LedgerEntry(
    val requirementId: String,
    val status: RequirementStatus,
    val evidence: List<String> = emptyList(),
    val stampValid: Boolean = false,
    /** How the requirement's acceptance items were accepted (I7, D-342): tested, reviewed, or accepted without verification. */
    val provenance: List<io.astrolabe.verify.ItemProvenance> = emptyList(),
)

@Serializable
public data class Ledger(val entries: Map<String, LedgerEntry>) {
    public operator fun get(requirementId: String): LedgerEntry? = entries[requirementId]

    public fun unfinished(): List<String> = entries.values.filter { it.status != RequirementStatus.Verified && it.status != RequirementStatus.Cancelled }.map { it.requirementId }

    public companion object {
        @JvmStatic
        public fun initial(contract: Contract): Ledger =
            Ledger(contract.requirements.associate { it.id to LedgerEntry(it.id, if (it.lapsed) RequirementStatus.Cancelled else RequirementStatus.Pending) })
    }
}
