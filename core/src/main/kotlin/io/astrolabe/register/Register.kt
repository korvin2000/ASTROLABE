package io.astrolabe.register

import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.workspace.VersionChange
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Plan step marks (§5.2). */
@Serializable
public enum class Mark(public val text: String) {
    @SerialName("todo")
    Todo("[ ]"),

    @SerialName("cursor")
    Cursor("[>]"),

    @SerialName("done")
    Done("[x]"),

    @SerialName("cancelled")
    Cancelled("[~]"),
}

@Serializable
public data class Step(
    val n: Int,
    val mark: Mark,
    val text: String,
    /** Per-step acceptance: a contract acceptance id or a `run:`/`check:` text (§4.2 model-added accept). */
    val accept: String? = null,
    val after: Int? = null,
    val req: String? = null,
    /** Evidence id that justified the tick. */
    val evidence: String? = null,
    /** Reason for `[~]`. */
    val reason: String? = null,
)

/**
 * A register fact: epistemic kind `h|v|x` and freshness are separate axes (§5.2). [staleAt] is set by the
 * harness when the anchor moved; the model cannot remove it except by re-verifying.
 */
@Serializable
public data class Fact(
    val n: Int,
    val kind: ClaimKind,
    val text: String,
    val anchor: Anchor? = null,
    val evidenceId: String? = null,
    val staleAt: FileVersion? = null,
    val refutedBy: String? = null,
    /** Harness-owned consecutive cell-boundary stale count. */
    val staleCells: Int = 0,
) {
    val stale: Boolean get() = staleAt != null
}

@Serializable
public data class DeadEnd(val n: Int, val text: String, val evidence: String?, val scope: String, val reopen: String)

@Serializable
public data class Decision(
    val n: Int,
    val text: String,
    val because: String,
    val rejected: String?,
    val probe: String? = null,
    val adrCandidate: Boolean = false,
)

/** An open item; [trip] is a path/glob predicate evaluated after each edit batch (P1.5.2). */
@Serializable
public data class OpenItem(
    val n: Int,
    val text: String,
    val trip: String? = null,
    val needs: String? = null,
    val closed: Boolean = false,
    val closedEvidence: String? = null,
    val fired: Boolean = false,
)

/**
 * A pending amendment proposal as the model phrased it (the only place it may touch acceptance). [id] is the contract
 * amendment a direct `state(note, kind=amend)` recorded (A-D.4); a structured line has none and keeps its bytes.
 */
@Serializable
public data class AmendmentLine(
    val change: String,
    val reason: String,
    val status: String = "pending",
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val id: String? = null,
) {
    /** The v1.0 constructor: [id] takes its default. Kept for Java callers. */
    public constructor(change: String, reason: String, status: String) : this(change, reason, status, null)
}

/** An amendment line the direct protocol archived, with the position its `a<n>` id keeps (A-D.4). */
@Serializable
public data class ArchivedAmendment(val position: Int, val line: AmendmentLine)

/**
 * A-D.4: the notes a direct register archived to keep its active part under the register cap — closed open items,
 * refuted facts and decided amendments. Durable and readable (`look(recall, id="notes", range="archive")`), never
 * rendered in STATE and never counted in its tokens; numbers are never reused, so a new note counts these too.
 */
@Serializable
public data class RegisterArchive(
    val facts: List<Fact> = emptyList(),
    val open: List<OpenItem> = emptyList(),
    val amendments: List<ArchivedAmendment> = emptyList(),
) {
    val isEmpty: Boolean get() = facts.isEmpty() && open.isEmpty() && amendments.isEmpty()
}

/**
 * The Working Register (STATE, §5.2): model-owned through typed ops, harness-validated, rendered by the
 * harness at the tail of `[A]`. Acceptance never lives here; it lives in the contract.
 */
@Serializable
public data class Register(
    val version: Int,
    val cell: ContextId,
    val increment: String,
    val incrementTitle: String,
    val constraints: List<String> = emptyList(),
    val plan: List<Step> = emptyList(),
    val facts: List<Fact> = emptyList(),
    val deadEnds: List<DeadEnd> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val open: List<OpenItem> = emptyList(),
    val focus: String? = null,
    val amendments: List<AmendmentLine> = emptyList(),
    val next: String? = null,
    /** A-D.4: what a direct register archived; empty, and not encoded, for every structured register. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val archive: RegisterArchive = RegisterArchive(),
) {
    /** The v1.0 full constructor: [archive] takes its default. Kept for Java callers. */
    public constructor(
        version: Int,
        cell: ContextId,
        increment: String,
        incrementTitle: String,
        constraints: List<String>,
        plan: List<Step>,
        facts: List<Fact>,
        deadEnds: List<DeadEnd>,
        decisions: List<Decision>,
        open: List<OpenItem>,
        focus: String?,
        amendments: List<AmendmentLine>,
        next: String?,
    ) : this(version, cell, increment, incrementTitle, constraints, plan, facts, deadEnds, decisions, open, focus, amendments, next, RegisterArchive())

    init {
        require(version >= 0) { "register version must be ≥ 0" }
    }

    /**
     * The `a<n>` position of every active amendment line (A-D.4): positions run over the archived lines too, so an
     * archived amendment never hands its number to another.
     */
    public fun amendmentPositions(): List<Int> {
        val taken = archive.amendments.map { it.position }.toSet()
        return generateSequence(1) { it + 1 }.filter { it !in taken }.take(amendments.size).toList()
    }

    /** This register with its archive merged back, in number and position order: what the T7 change test compares. */
    internal fun restored(): Register {
        if (archive.isEmpty) return this
        val positions = amendmentPositions()
        val lines = (amendments.mapIndexed { i, a -> positions[i] to a } + archive.amendments.map { it.position to it.line }).sortedBy { it.first }.map { it.second }
        return copy(facts = (facts + archive.facts).sortedBy { it.n }, open = (open + archive.open).sortedBy { it.n }, amendments = lines, archive = RegisterArchive())
    }

    val cursor: Step? get() = plan.firstOrNull { it.mark == Mark.Cursor }

    val cursors: Int get() = plan.count { it.mark == Mark.Cursor }

    val todos: Int get() = plan.count { it.mark == Mark.Todo }

    public fun step(n: Int): Step? = plan.firstOrNull { it.n == n }

    public fun fact(n: Int): Fact? = facts.firstOrNull { it.n == n }

    public fun openItem(n: Int): OpenItem? = open.firstOrNull { it.n == n }

    /**
     * Harness-side (§4.4 cell horizon): every `v` fact anchored at the changed path at any version other than
     * the new one is marked `v(stale @old)`. Only the model can clear the mark, by re-verifying.
     */
    public fun markStale(change: VersionChange): Register = copy(
        facts = facts.map { f ->
            val anchor = f.anchor
            if (f.kind == ClaimKind.Verified && anchor?.path == change.path && !change.current(anchor.version) && f.staleAt == null) {
                f.copy(staleAt = anchor.version)
            } else {
                f
            }
        },
    )

    /** Harness-side: trips whose glob matches an edited path fire once. */
    public fun fireTrips(editedPaths: Collection<String>): Register {
        if (editedPaths.isEmpty()) return this
        return copy(
            open = open.map { item ->
                if (!item.closed && !item.fired && item.trip != null && editedPaths.any { Trips.matches(item.trip, it) }) item.copy(fired = true) else item
            },
        )
    }

    public companion object {
        @JvmStatic
        public fun empty(cell: ContextId, increment: String, title: String, constraints: List<String> = emptyList()): Register =
            Register(0, cell, increment, title, constraints)
    }
}

/** Trip predicates: `any edit under <dir>/`, a glob (`src/**/*.py`) or a plain path prefix. */
public object Trips {
    @JvmStatic
    public fun matches(trip: String, path: String): Boolean {
        // `any edit under src/cli/ → check`: the predicate is the part before the arrow.
        val pattern = trip.substringBefore("→").substringBefore("->").trim().removePrefix("any edit under ").removePrefix("edit under ").trim()
        if (pattern.isEmpty()) return false
        val normalized = path.replace('\\', '/')
        if (!pattern.contains('*') && !pattern.contains('?')) {
            val prefix = pattern.trimEnd('/')
            return normalized == prefix || normalized.startsWith("$prefix/")
        }
        return globToRegex(pattern).matches(normalized)
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*")
                    i++
                    if (i + 1 < glob.length && glob[i + 1] == '/') i++
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c in ".()+|^$[]{}\\" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Regex(sb.append('$').toString())
    }
}
