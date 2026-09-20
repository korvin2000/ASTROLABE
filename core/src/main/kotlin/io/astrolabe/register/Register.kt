package io.astrolabe.register

import io.astrolabe.evidence.Anchor
import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
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

/** A pending amendment proposal as the model phrased it (the only place it may touch acceptance). */
@Serializable
public data class AmendmentLine(val change: String, val reason: String, val status: String = "pending")

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
) {
    init {
        require(version >= 0) { "register version must be ≥ 0" }
    }

    val cursor: Step? get() = plan.firstOrNull { it.mark == Mark.Cursor }

    val cursors: Int get() = plan.count { it.mark == Mark.Cursor }

    val todos: Int get() = plan.count { it.mark == Mark.Todo }

    public fun step(n: Int): Step? = plan.firstOrNull { it.n == n }

    public fun fact(n: Int): Fact? = facts.firstOrNull { it.n == n }

    public fun openItem(n: Int): OpenItem? = open.firstOrNull { it.n == n }

    /** Harness-side: marks every `v` fact anchored at [path] with the old version as stale (§4.4 cell horizon). */
    public fun markStale(path: String, oldVersion: FileVersion): Register = copy(
        facts = facts.map { f ->
            if (f.kind == ClaimKind.Verified && f.anchor?.path == path && f.anchor.version == oldVersion && f.staleAt == null) f.copy(staleAt = oldVersion) else f
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
