package io.astrolabe.kb

import io.astrolabe.id.WorkId
import io.astrolabe.store.Layout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** The executable form a recurring invariant is proposed as (§12.2 executable promotion). */
public enum class PromotionCheck(public val wire: String) { Test("test"), Linter("linter"), Schema("schema") }

/**
 * The candidate check run over held-out tasks (§12.2): [tasks] tasks the promoted note was not learned from, of which
 * [falsePositives] had the check fire without the defect. [ref] names the run.
 */
@Serializable
public data class HeldOutEvidence(val ref: String, val tasks: Int, val falsePositives: Int) {
    init {
        require(ref.isNotBlank()) { "held-out evidence names its run" }
        require(tasks >= 0 && falsePositives in 0..tasks) { "0 ≤ falsePositives ≤ tasks" }
    }
}

/**
 * One promotion rendered as a proposed task (§12.2): a test, linter rule or schema check for the note's invariant,
 * scoped to the note's own scope until held-out evidence allows generalizing it. [status] is always `proposed`: the
 * harness never commits it, a person or a later task does.
 */
@Serializable
public data class PromotionProposal(
    val noteId: String,
    val pointerId: String,
    val invariant: String,
    val check: String,
    val task: String,
    val scope: String,
    val evidence: List<String>,
    val heldOut: HeldOutEvidence?,
    /** `local` (the note's scope only), `generalizable` (held-out tasks, no false positive) or `blocked` with the reason. */
    val generalization: String,
    val status: String = PROPOSED,
) {
    public companion object {
        public const val PROPOSED: String = "proposed"
    }
}

/** Renders [Curator.promote]'s output as proposal records and exports them as a derived view (never committed). */
public object PromotionProposals {
    private val JSON = Json { encodeDefaults = true; prettyPrint = true }

    private val SCHEMA_WORDS = Regex("""\b(schema|json|yaml|toml|config|configuration|field|column|migration)\b""", RegexOption.IGNORE_CASE)
    private val LINT_WORDS = Regex("""\b(import|imports|naming|name|style|format|never call|forbid|forbidden|deprecated|lint)\b""", RegexOption.IGNORE_CASE)

    /** The check kind a note's invariant suggests (D-202): schema words, then lint words, else a test. */
    @JvmStatic
    public fun checkOf(note: Note): PromotionCheck = when {
        SCHEMA_WORDS.containsMatchIn(note.summary) -> PromotionCheck.Schema
        LINT_WORDS.containsMatchIn(note.summary) -> PromotionCheck.Linter
        else -> PromotionCheck.Test
    }

    /**
     * [promotion] of [note] as a proposed task. The rule stays at the note's scope unless [heldOut] shows at least one
     * held-out task and no false positive — only then may it be generalized (§12.2).
     */
    @JvmStatic
    @JvmOverloads
    public fun of(promotion: Promotion, note: Note, heldOut: HeldOutEvidence? = null): PromotionProposal {
        require(note.id == promotion.noteId) { "the promotion is of ${promotion.noteId}, not ${note.id}" }
        val check = checkOf(note)
        val generalization = when {
            heldOut == null -> "local"
            heldOut.tasks == 0 -> "blocked: the held-out run ${heldOut.ref} covered no task"
            heldOut.falsePositives > 0 -> "blocked: ${heldOut.falsePositives} of ${heldOut.tasks} held-out tasks fired without the defect (${heldOut.ref})"
            else -> "generalizable"
        }
        val scope = if (generalization == "generalizable") "global" else note.scope
        val task = "propose a ${check.wire} check for '${note.summary}' (from ${note.id}, scope $scope); " +
            if (generalization == "generalizable") "held-out evidence ${heldOut!!.ref} allows generalizing it" else "keep it at ${note.scope} until held-out tasks show no false positive"
        return PromotionProposal(note.id, promotion.pointerId, note.summary, check.wire, task, scope, note.basis.evidenceRefs.toList(), heldOut, generalization)
    }

    /** Writes `exports/<work>/promotion-proposals.json` (a derived view, §4) and returns it; nothing else is touched. */
    @JvmStatic
    public fun export(layout: Layout, work: WorkId, proposals: List<PromotionProposal>): Path {
        val dir = layout.exports.resolve(work.value)
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve(FILE), JSON.encodeToString(PROPOSALS, proposals))
    }

    public const val FILE: String = "promotion-proposals.json"

    private val PROPOSALS = ListSerializer(PromotionProposal.serializer())
}
