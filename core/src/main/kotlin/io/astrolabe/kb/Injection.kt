package io.astrolabe.kb

import io.astrolabe.cell.ContextPart
import io.astrolabe.cell.Role
import io.astrolabe.id.Digest
import io.astrolabe.id.FileVersion
import io.astrolabe.id.WorkId
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.workspace.PathPattern
import kotlinx.serialization.Serializable

/** The `[O]` ablation arms of KB injection (§4.5, §19.5): off, frozen at campaign open, or live from the store. */
@Serializable
public enum class KbInjection { Off, Frozen, Live }

/**
 * D-37 weights and caps: `score = w_scope·match + w_dep·overlap + w_fresh·freshness + w_use·use_value +
 * w_evid·evidence_quality − tokens / lengthPerTokens`; every feature is bounded to [0, 1] ([Injection.features]).
 */
public data class InjectionWeights @JvmOverloads constructor(
    val scope: Double = 3.0,
    val dependency: Double = 2.0,
    val freshness: Double = 1.0,
    val use: Double = 1.0,
    val evidence: Double = 1.0,
    /** `w_len = tokens / lengthPerTokens` (D-37: tokens/500). */
    val lengthPerTokens: Double = 500.0,
    val threshold: Double = 2.0,
    val maxNotes: Int = 8,
    val maxTokens: Int = 1_500,
) {
    init {
        require(lengthPerTokens > 0 && maxNotes >= 1 && maxTokens >= 1) { "caps are positive" }
    }
}

/** What one compile ranks against (§6.3): the write scope, the contracts in play, the stamp and the usage counters. */
public data class InjectionInputs @JvmOverloads constructor(
    val role: Role,
    val work: WorkId,
    val writeScope: List<String>,
    /** Paths touched so far (a continuation cell): `CON` anchored on them bypass the cap like the write scope. */
    val touched: Set<String> = emptySet(),
    /** Contract names in play (`CON-…` note ids and contract ids) for the `depends_on` overlap. */
    val contractsInPlay: Set<String> = emptySet(),
    val stamp: String? = null,
    val usage: Map<String, NoteUsage> = emptyMap(),
    /** Current tree versions; `null` skips the anchor eligibility check. */
    val currentVersion: ((String) -> FileVersion?)? = null,
    /** Digests of advice already injected in this cell ([Injection.digest]); unchanged advice is never re-injected. */
    val alreadyInjected: Set<String> = emptySet(),
)

/** The bounded D-37 features of one note, each in [0, 1] except [tokens]. */
public data class InjectionFeatures(val scopeMatch: Double, val dependencyOverlap: Double, val freshness: Double, val useValue: Double, val evidenceQuality: Double, val tokens: Int)

public data class InjectedNote(val note: Note, val score: Double, val mandatory: Boolean, val tokens: Int, val features: InjectionFeatures)

public data class InjectionExclusion(val id: String, val reason: String)

/** The ranked outcome: what to compile in, and every admitted-or-stale note left out with its reason (FX-35 flags). */
public data class InjectionResult(val selected: List<InjectedNote>, val excluded: List<InjectionExclusion>) {
    val notes: List<Note> get() = selected.map { it.note }

    /** One log line: `injected 3/12 (2 mandatory, 410 tokens); excluded LES-2: stale …`. */
    val log: String get() = "injected ${selected.size} (${selected.count { it.mandatory }} mandatory, ${selected.sumOf { it.tokens }} tokens)" +
        (if (excluded.isEmpty()) "" else "; excluded " + excluded.joinToString(", ") { "${it.id}: ${it.reason}" })
}

/**
 * Precision-gated injection (§6.3, §4.5): staleness and role scope are eligibility checks *before* ranking; the
 * D-37 score ranks the eligible notes; `CON` (and `ADR`) anchored in the write scope or on touched paths are
 * mandatory and bypass the cap and the threshold (F23: CON always compiled in); at most 8 notes / 1.5K tokens of
 * optional advice, none when nothing scores above the threshold. A pure function of records: equal inputs rank equally.
 */
public object Injection {
    @JvmStatic
    @JvmOverloads
    public fun select(notes: List<Note>, inputs: InjectionInputs, estimator: TokenEstimator, weights: InjectionWeights = InjectionWeights()): InjectionResult {
        val byId = notes.associateBy { it.id }
        val excluded = ArrayList<InjectionExclusion>()
        val eligible = ArrayList<Note>()
        for (note in notes.sortedBy { it.id }) {
            val reason = eligibility(note, byId, inputs)
            if (reason == null) eligible += note
            else if (note.status == NoteStatus.Admitted || note.status == NoteStatus.Stale) excluded += InjectionExclusion(note.id, reason)
        }
        val scored = eligible.map { note ->
            val f = features(note, inputs, estimator)
            val score = weights.scope * f.scopeMatch + weights.dependency * f.dependencyOverlap + weights.freshness * f.freshness +
                weights.use * f.useValue + weights.evidence * f.evidenceQuality - f.tokens / weights.lengthPerTokens
            InjectedNote(note, score, mandatory(note, inputs), f.tokens, f)
        }
        val selected = ArrayList<InjectedNote>()
        selected += scored.filter { it.mandatory }.sortedBy { it.note.id }
        var count = 0
        var tokens = 0
        for (candidate in scored.filter { !it.mandatory }.sortedWith(compareByDescending<InjectedNote> { it.score }.thenBy { it.note.id })) {
            if (candidate.score < weights.threshold) {
                excluded += InjectionExclusion(candidate.note.id, "score ${String.format(java.util.Locale.ROOT, "%.2f", candidate.score)} below the threshold ${weights.threshold}")
                continue
            }
            if (count + 1 > weights.maxNotes || tokens + candidate.tokens > weights.maxTokens) {
                excluded += InjectionExclusion(candidate.note.id, "over the cap (${weights.maxNotes} notes / ${weights.maxTokens} tokens)")
                continue
            }
            selected += candidate
            count += 1
            tokens += candidate.tokens
        }
        return InjectionResult(selected, excluded.sortedBy { it.id })
    }

    /** Why [note] is not eligible for ranking, or `null` when it is. */
    @JvmStatic
    public fun eligibility(note: Note, all: Map<String, Note>, inputs: InjectionInputs): String? {
        when (note.status) {
            NoteStatus.Admitted -> Unit
            NoteStatus.Stale -> return "stale: its anchors or dependencies moved (rechecked by the curator, never served as current)"
            else -> return "${note.status.wire}, not admitted"
        }
        if (note.kind == NoteKind.STATUS && note.scope != "task:${inputs.work.value}") return "STATUS of another task"
        val inScope = when (note.kind) {
            // D-42: the CAL prior reaches a role through its calibration view (or an explicit CAL scope), never as "global" advice.
            NoteKind.CAL -> ContextPart.CalibrationPrior in inputs.role.contextView || "CAL" in inputs.role.noteScope
            else -> note.kind.name in inputs.role.noteScope || (note.scope == "global" && "GLOBAL" in inputs.role.noteScope)
        }
        if (!inScope) return "kind ${note.kind} is outside the ${inputs.role.name} role's note scope"
        for (dep in note.validity.dependsOn) {
            val target = all[dep.substringBeforeLast('@')] ?: continue
            if (target.status != NoteStatus.Admitted) return "references ${target.id}, which is ${target.status.wire}"
        }
        inputs.currentVersion?.let { current ->
            for (anchor in note.anchors) {
                val recorded = anchor.version ?: continue
                val now = current(anchor.path) ?: return "anchor ${anchor.path} is gone from the tree"
                if (!now.digest.hex.startsWith(recorded)) return "anchor ${anchor.path} moved (recorded $recorded, now ${now.digest.hex.take(recorded.length)})"
            }
        }
        if (digest(note) in inputs.alreadyInjected) return "unchanged advice already injected in this cell"
        return null
    }

    /**
     * `CON`/`ADR` anchored in the write scope or on a touched path are compiled in regardless of score or cap (F23);
     * so is the `CAL` prior for a role that views it (D-42: it replaces the statistics block, never competes with advice).
     */
    @JvmStatic
    public fun mandatory(note: Note, inputs: InjectionInputs): Boolean = when (note.kind) {
        NoteKind.CON, NoteKind.ADR -> note.anchors.any { a -> a.path in inputs.touched || inputs.writeScope.any { PathPattern.matches(it, a.path) } }
        NoteKind.CAL -> ContextPart.CalibrationPrior in inputs.role.contextView
        else -> false
    }

    @JvmStatic
    public fun features(note: Note, inputs: InjectionInputs, estimator: TokenEstimator): InjectionFeatures {
        val scopeMatch = when (NoteScope.kind(note.scope)) {
            ScopeKind.Global -> 0.25
            ScopeKind.Subsystem -> if (inputs.writeScope.any { subsystemOf(it, NoteScope.subsystem(note.scope)!!) }) 1.0 else 0.0
            ScopeKind.PathGlob -> if (inputs.writeScope.any { PathPattern.matches(note.scope, it.trimEnd('*', '/')) || PathPattern.matches(it, note.scope.trimEnd('*', '/')) || it == note.scope }) 1.0 else 0.0
            ScopeKind.TaskFamily -> if (note.scope == "task:${inputs.work.value}") 1.0 else 0.0
            ScopeKind.Roles -> if (note.scope.removePrefix("roles:").split(',').any { it.trim() == inputs.role.name }) 1.0 else 0.0
            ScopeKind.Unbounded -> 0.0
        }.let { base -> if (note.anchors.any { a -> inputs.writeScope.any { PathPattern.matches(it, a.path) } }) 1.0 else base }
        val deps = note.validity.dependsOn.map { it.substringBeforeLast('@') }
        val overlap = if (deps.isEmpty()) 0.0 else deps.count { it in inputs.contractsInPlay }.toDouble() / deps.size
        val freshness = when {
            note.validity.lastValidated == null -> 0.25
            inputs.stamp != null && note.validity.lastValidated == inputs.stamp -> 1.0
            else -> 0.5
        }
        val use = inputs.usage[note.id] ?: note.usage
        val useValue = if (use.injected == 0) 0.5 else (use.cited.toDouble() / use.injected).coerceIn(0.0, 1.0)
        val evidence = when {
            note.signedBy != null -> 1.0
            else -> (note.basis.evidenceRefs.size / 2.0).coerceIn(0.0, 1.0)
        }
        val tokens = estimator.estimate(note.line + "\n" + note.body).tokens.toInt()
        return InjectionFeatures(scopeMatch, overlap, freshness, useValue, evidence, tokens)
    }

    /** The advice digest that "unchanged advice within a cell" is judged by. */
    @JvmStatic
    public fun digest(note: Note): String = Digest.ofUtf8(note.id + "\n" + note.summary + "\n" + note.body).hex

    private fun subsystemOf(scopePath: String, subsystem: String): Boolean {
        val p = scopePath.replace('\\', '/')
        return p == subsystem || p.startsWith("$subsystem/") || "/$subsystem/" in p || p.startsWith("src/$subsystem")
    }
}

/**
 * §6.3 focus notes: per turn, at most [maxTokens] of admitted notes anchored in the current `Focus` directory or the
 * files touched this turn, each shown once per cell, rendered for the `[A]` focus-notes slot (P1.8.3). Notes already
 * compiled into `[K]` are never repeated here.
 */
public class FocusNotes @JvmOverloads constructor(
    private val notes: List<Note>,
    private val estimator: TokenEstimator,
    private val maxTokens: Int = 300,
    compiled: Set<String> = emptySet(),
) {
    private val shown = LinkedHashSet<String>(compiled)

    /** The shown ids so far, in order, for the `(injected, cited, outcome)` log. */
    public val shownIds: List<String> get() = shown.toList()

    /** Renders this turn's block, or `null` when nothing new is anchored in [focus] or [touched]. */
    @Synchronized
    public fun render(focus: String?, touched: Set<String>): String? {
        val dir = focus?.replace('\\', '/')?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != "." }
        val eligible = notes.filter { note ->
            note.status == NoteStatus.Admitted && note.id !in shown && note.kind != NoteKind.STATUS && note.anchors.any { a ->
                a.path in touched || (dir != null && (a.path == dir || a.path.startsWith("$dir/")))
            }
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val lines = ArrayList<String>()
        for (note in eligible) {
            val candidate = lines + note.line
            if (estimator.estimate(candidate.joinToString("\n")).tokens > maxTokens) break
            lines += note.line
            shown += note.id
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
