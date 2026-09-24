package io.astrolabe.kb

import io.astrolabe.auth.Redaction
import kotlinx.serialization.Serializable

/** Who decides admission (§4.5): a person through `Authority.resolve`, or the deterministic policy (D7). */
public enum class AdmissionMode { Interactive, Autonomous }

/** The §4.5 scope vocabulary: `global · subsystem:<s> · <path glob> · task:<family> · roles:<r>`. */
public enum class ScopeKind { Global, Subsystem, PathGlob, TaskFamily, Roles, Unbounded }

public object NoteScope {
    @JvmStatic
    public fun kind(scope: String): ScopeKind = when {
        scope == "global" -> ScopeKind.Global
        scope.startsWith("subsystem:") && scope.length > 10 -> ScopeKind.Subsystem
        scope.startsWith("task:") && scope.length > 5 -> ScopeKind.TaskFamily
        scope.startsWith("roles:") && scope.length > 6 -> ScopeKind.Roles
        scope.any { it == '/' || it == '*' || it == '?' } || scope.contains('.') -> ScopeKind.PathGlob
        else -> ScopeKind.Unbounded
    }

    /** `subsystem:<s>` ⇒ `s`, else `null`. */
    @JvmStatic
    public fun subsystem(scope: String): String? = scope.takeIf { kind(it) == ScopeKind.Subsystem }?.removePrefix("subsystem:")
}

/** One deterministic lint rule of the curator (§4.5 `admit`). */
@Serializable
public enum class LintRule { Duplicate, EvidenceMissing, EvidenceUnresolvable, AnchorUnresolvable, ScopeUnbounded, Contradiction, Secret, OneOffGeneralization }

@Serializable
public data class LintFinding(val rule: LintRule, val detail: String)

/** What the curator resolves candidates against: the tree for anchors, the evidence stores for refs. */
public interface KbResolver {
    public fun anchorExists(path: String): Boolean

    public fun evidenceExists(ref: String): Boolean

    public companion object {
        /** Everything resolves; for a KB with no tree behind it. */
        @JvmField
        public val ALL: KbResolver = object : KbResolver {
            override fun anchorExists(path: String): Boolean = true
            override fun evidenceExists(ref: String): Boolean = true
        }

        @JvmStatic
        public fun of(anchor: (String) -> Boolean, evidence: (String) -> Boolean): KbResolver = object : KbResolver {
            override fun anchorExists(path: String): Boolean = anchor(path)
            override fun evidenceExists(ref: String): Boolean = evidence(ref)
        }
    }
}

/**
 * The curator's lint (§4.5, §12.2): a pure function of the candidate, the admitted notes and the resolver. Every
 * rule is deterministic and bounded; none rewrites the candidate (secrets are refused, never redacted in place).
 */
public object Lint {
    /** Word-set similarity at or above which two summaries of one kind are the same note. */
    public const val DUPLICATE_SIMILARITY: Double = 0.8

    private val WORD = Regex("[\\p{L}\\p{N}_]+")
    private val NEGATION = setOf("not", "never", "no", "cannot", "dont", "doesnt", "isnt", "arent", "wont")
    /** Auxiliaries a negation rides on ("do not need" vs "need"): dropped before the summaries are compared. */
    private val AUXILIARY = setOf("do", "does", "did", "is", "are", "was", "were")
    private val CONDITIONAL = Regex("\\b(when|if|under|unless|only|given|after|before|while|with)\\b", RegexOption.IGNORE_CASE)

    @JvmStatic
    @JvmOverloads
    public fun check(candidate: Note, admitted: List<Note>, resolver: KbResolver, redaction: Redaction = Redaction()): List<LintFinding> {
        val out = ArrayList<LintFinding>()
        val active = admitted.filter { it.status == NoteStatus.Admitted && it.id != candidate.id && it.id != candidate.supersedes }
        active.firstOrNull { it.kind == candidate.kind && similarity(it.summary, candidate.summary) >= DUPLICATE_SIMILARITY }
            ?.let { out += LintFinding(LintRule.Duplicate, "summary duplicates ${it.id}") }
        if (candidate.basis.evidenceRefs.isEmpty()) out += LintFinding(LintRule.EvidenceMissing, "no evidence refs in basis")
        candidate.basis.evidenceRefs.filterNot(resolver::evidenceExists).takeIf { it.isNotEmpty() }
            ?.let { out += LintFinding(LintRule.EvidenceUnresolvable, "evidence ${it.joinToString(", ")} does not resolve") }
        candidate.anchors.map { it.path }.distinct().filterNot(resolver::anchorExists).takeIf { it.isNotEmpty() }
            ?.let { out += LintFinding(LintRule.AnchorUnresolvable, "anchor ${it.joinToString(", ")} is not in the tree") }
        if (NoteScope.kind(candidate.scope) == ScopeKind.Unbounded) out += LintFinding(LintRule.ScopeUnbounded, "scope '${candidate.scope}' is none of global, subsystem:, a path glob, task: or roles:")
        contradiction(candidate, active)?.let { out += it }
        val hits = redaction.apply(candidate.summary + "\n" + candidate.body).hits
        if (hits.isNotEmpty()) out += LintFinding(LintRule.Secret, "secret-like content (${hits.joinToString(", ") { it.kind }}): a note is never redacted in place")
        oneOff(candidate)?.let { out += it }
        return out
    }

    /** Jaccard similarity of the summaries' lowercased word sets, in [0, 1]. */
    @JvmStatic
    public fun similarity(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        val union = (wa + wb).size
        return if (union == 0) 0.0 else (wa intersect wb).size.toDouble() / union
    }

    /** A `PIT` states the conditions under which the approach failed (§12.2: conditional evidence, never a ban). */
    @JvmStatic
    public fun conditional(body: String): Boolean = CONDITIONAL.containsMatchIn(body)

    /**
     * Two forms of contradiction the lint can decide without a model: an admitted note of the same kind whose summary is
     * the candidate's with the negation flipped, and a second `CON` for an anchor symbol an admitted `CON` already
     * governs that the candidate does not supersede.
     */
    private fun contradiction(candidate: Note, active: List<Note>): LintFinding? {
        val (cWords, cNeg) = split(candidate.summary)
        for (note in active) {
            if (note.kind != candidate.kind) continue
            val (nWords, nNeg) = split(note.summary)
            if (cWords == nWords && cNeg != nNeg) return LintFinding(LintRule.Contradiction, "contradicts ${note.id}: '${note.summary}'")
        }
        if (candidate.kind == NoteKind.CON) {
            val symbols = candidate.anchors.filter { it.symbol != null }.map { it.path + "#" + it.symbol }.toSet()
            for (note in active) {
                if (note.kind != NoteKind.CON) continue
                val shared = note.anchors.filter { it.symbol != null }.map { it.path + "#" + it.symbol }.filter { it in symbols }
                if (shared.isNotEmpty()) return LintFinding(LintRule.Contradiction, "a second contract for ${shared.joinToString(", ")} governed by ${note.id}; supersede it instead")
            }
        }
        return null
    }

    /** One use is conditional evidence in its scope: a single evidence ref generalized globally or unconditionally is refused. */
    private fun oneOff(candidate: Note): LintFinding? {
        if (candidate.basis.evidenceRefs.size > 1) return null
        if (candidate.kind != NoteKind.LES && candidate.kind != NoteKind.PIT) return null
        if (NoteScope.kind(candidate.scope) == ScopeKind.Global) return LintFinding(LintRule.OneOffGeneralization, "one evidence ref generalized to the global scope")
        if (candidate.kind == NoteKind.PIT && !conditional(candidate.body)) return LintFinding(LintRule.OneOffGeneralization, "a PIT from one failed use states no conditions (when/if/under/unless)")
        return null
    }

    private fun words(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value }.toSet()

    private fun split(summary: String): Pair<Set<String>, Boolean> {
        val all = WORD.findAll(summary.lowercase()).map { it.value.replace("'", "") }.toList()
        return all.filter { it !in NEGATION && it !in AUXILIARY }.toSet() to all.any { it in NEGATION }
    }
}

/** The curator's decision for one queued candidate. */
public sealed interface AdmissionDecision {
    public data class Admit(val admittedBy: String, val signedBy: String? = null) : AdmissionDecision

    public data class Wait(val reason: String) : AdmissionDecision

    public data class Reject(val findings: List<LintFinding>, val reason: String) : AdmissionDecision
}

/**
 * The §4.5 admission policy (D7), a pure function of the candidate, its lint findings and the mode. Interactive
 * mode queues for the user; autonomous mode admits only lint-passing, anchored, subsystem- or task-family-scoped
 * factual `LES` and conditional `PIT` at `confidence ≤ 0.6` as `admitted_by: policy`; ADRs are never auto-admitted.
 */
public object AdmissionPolicy {
    public const val POLICY: String = "policy"
    public const val AUTO_ADMIT_MAX_CONFIDENCE: Double = 0.6

    @JvmStatic
    public fun decide(candidate: Note, findings: List<LintFinding>, mode: AdmissionMode): AdmissionDecision {
        if (findings.isNotEmpty()) return AdmissionDecision.Reject(findings, findings.joinToString("; ") { "${it.rule}: ${it.detail}" })
        return when (mode) {
            AdmissionMode.Interactive -> AdmissionDecision.Wait("queued for the user")
            AdmissionMode.Autonomous -> when {
                candidate.kind == NoteKind.ADR -> AdmissionDecision.Wait("an ADR is signed only through Authority.resolve")
                candidate.kind != NoteKind.LES && candidate.kind != NoteKind.PIT -> AdmissionDecision.Wait("${candidate.kind} waits for the user")
                candidate.anchors.isEmpty() -> AdmissionDecision.Wait("policy admits anchored notes only")
                NoteScope.kind(candidate.scope) !in AUTO_SCOPES -> AdmissionDecision.Wait("policy admits subsystem- or task-family-scoped notes only")
                candidate.confidence == null || candidate.confidence > AUTO_ADMIT_MAX_CONFIDENCE ->
                    AdmissionDecision.Wait("confidence ${candidate.confidence ?: "unset"} is above the policy's $AUTO_ADMIT_MAX_CONFIDENCE")
                candidate.kind == NoteKind.PIT && !Lint.conditional(candidate.body) -> AdmissionDecision.Wait("policy admits conditional PITs only")
                else -> AdmissionDecision.Admit(POLICY)
            }
        }
    }

    private val AUTO_SCOPES = setOf(ScopeKind.Subsystem, ScopeKind.TaskFamily)
}
