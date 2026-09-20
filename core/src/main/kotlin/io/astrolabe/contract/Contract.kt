package io.astrolabe.contract

import io.astrolabe.DClassPolicy
import io.astrolabe.Mode
import io.astrolabe.auth.Stage
import io.astrolabe.budget.Budget
import io.astrolabe.event.Proposer
import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.ContextId
import io.astrolabe.id.InstantSerializer
import io.astrolabe.id.WorkId
import io.astrolabe.workspace.ProtectedPaths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Orchestration shapes (§3.5). */
@Serializable
public enum class Shape { S0, S1, S2, S3 }

/** A verbatim user request; the list on the contract is append-only (§4.1). */
@Serializable
public data class UserRequest(
    val id: String,
    @Serializable(with = InstantSerializer::class) val at: Instant,
    val text: String,
) {
    init {
        require(id.isNotBlank() && text.isNotBlank()) { "request needs an id and text" }
    }
}

/** Harness-derived, never model-written (§4.1). [wire] is the docs' snake_case spelling used in renders. */
@Serializable
public enum class RequirementStatus(public val wire: String) {
    Pending("pending"),
    InProgress("in_progress"),
    Verified("verified"),
    Blocked("blocked"),
}

@Serializable
public data class Requirement(
    val id: String,
    val text: String,
    val acceptance: List<String>,
    val dependsOn: List<String> = emptyList(),
    val authorityRef: String,
    val status: RequirementStatus = RequirementStatus.Pending,
) {
    init {
        require(id.isNotBlank() && text.isNotBlank() && authorityRef.isNotBlank()) { "requirement needs id, text and authority" }
    }
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
@Serializable
public sealed interface Acceptance {
    public val id: String
    public val origin: Origin
    public val obligationVersion: Int

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
    ) : Acceptance {
        override val criterion: String get() = "run: ${command.text}" + (scope?.let { " (scope $it)" } ?: "")
    }

    @Serializable
    @SerialName("check")
    public data class Check(
        override val id: String,
        val text: String,
        override val origin: Origin,
        val evidenceRef: String? = null,
        override val obligationVersion: Int = 1,
    ) : Acceptance {
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
    ) : Acceptance {
        override val criterion: String get() = "review: $text"
    }
}

@Serializable
public data class Constraint(val id: String, val text: String, val authority: String)

/**
 * Write scope bounds possible writes; it authorizes no unrelated work (D-31). Entries are workspace-relative:
 * `dir/` is a directory prefix, a bare `name` matches that file name anywhere in the tree (lock files are not
 * rooted), anything with `*`/`?` is a glob, and [REPOSITORY] is the whole tree.
 */
@Serializable
public data class Scope(val writePaths: List<String>, val protectedPaths: List<String>) {
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

    /** The currently authorized objective: the latest request text (D-17). */
    val objective: String get() = requests.last().text

    /**
     * §4.1 auto-derivation: a harness-sniffed suite is not a goal-level acceptance. While this is false the
     * model must state one in its first register patch or ask one question (entry gate, P1.8.5).
     */
    val goalAcceptanceStated: Boolean get() = acceptance.any { it.origin !is Origin.Harness }
}

/** Increment status (§4.2). */
@Serializable
public enum class IncrementStatus { Pending, InProgress, Verified, Blocked, Cancelled }

/**
 * The minimal increment record (S0's `G_single`, TODO P1.1.1); the requirement graph (P2.1.1) extends it with
 * dependencies, risk, production and sizing.
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
) {
    init {
        require(id.isNotBlank() && requirementIds.isNotEmpty()) { "increment needs an id and requirements" }
        require(expectedFiles >= 0) { "expectedFiles must be ≥ 0" }
    }
}

/** Ledger row per requirement (§4.2): harness-derived, changed only by the controller on receipts. */
@Serializable
public data class LedgerEntry(
    val requirementId: String,
    val status: RequirementStatus,
    val evidence: List<String> = emptyList(),
    val stampValid: Boolean = false,
)

@Serializable
public data class Ledger(val entries: Map<String, LedgerEntry>) {
    public operator fun get(requirementId: String): LedgerEntry? = entries[requirementId]

    public fun unfinished(): List<String> = entries.values.filter { it.status != RequirementStatus.Verified }.map { it.requirementId }

    public companion object {
        @JvmStatic
        public fun initial(contract: Contract): Ledger =
            Ledger(contract.requirements.associate { it.id to LedgerEntry(it.id, RequirementStatus.Pending) })
    }
}
