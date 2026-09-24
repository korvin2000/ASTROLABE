package io.astrolabe.kb

import io.astrolabe.evidence.Journal
import io.astrolabe.evidence.JournalEvent
import io.astrolabe.evidence.JournalKind
import io.astrolabe.id.IdGen
import io.astrolabe.id.Identities
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.register.Register
import java.time.Clock

/**
 * The knowledge side of one cell (§6.3): the focus-notes block for `[A]` each turn, and the register-citation hook
 * that turns `injected` into `cited`. Synchronous, so a Java host can supply one.
 */
public interface CellKnowledge {
    /** This turn's focus notes for the `[A]` slot, or `null`; [focus] is the register's focus directory. */
    public fun focusNotes(focus: String?, touched: Set<String>): String?

    /** Called after every turn with the current register: cited injected notes are recorded once per cell. */
    public fun cited(register: Register)
}

/**
 * Labelled negatives for the ranker (§4.5, §6.3): a `kb.search` with no hits (`retrieval_miss`) and an
 * `Open (needs: …)` register line are journal entries, so retrieval tuning can read them back.
 */
public class KbNegatives(private val journal: Journal, private val idGen: IdGen, private val clock: Clock) {
    private val logged = LinkedHashSet<String>()

    @Synchronized
    public fun retrievalMiss(ids: Identities, turn: Int?, log: KbSearchLog) {
        if (log.hits > 0) return
        journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Nudge, text = "$RETRIEVAL_MISS: '${log.query}' in ${log.scope ?: "kb"} (kinds ${log.kinds?.joinToString(",") ?: "any"}) why: ${log.why}", at = clock.instant()))
    }

    /** Every open item with a `needs:` is logged once per cell. */
    @Synchronized
    public fun openNeeds(ids: Identities, turn: Int?, register: Register) {
        for (item in register.open) {
            val needs = item.needs ?: continue
            if (item.closed || !logged.add("${item.n}:$needs")) continue
            journal.append(JournalEvent(idGen.next("ev"), ids, turn, JournalKind.Nudge, refs = listOf(needs), text = "$OPEN_NEEDS: Q${item.n} ${item.text} (needs: $needs)", at = clock.instant()))
        }
    }

    public companion object {
        public const val RETRIEVAL_MISS: String = "retrieval_miss"
        public const val OPEN_NEEDS: String = "open-needs"
    }
}

/**
 * The controller's [CellKnowledge]: focus notes over the admitted notes not already compiled into `[K]`, citations
 * of the injected ids found in the register (each once per cell, `Usage.Cited`), and the open-needs negatives.
 */
public class KnowledgeUse @JvmOverloads constructor(
    notes: List<Note>,
    private val injected: Set<String>,
    private val usage: Usage,
    private val ids: Identities,
    estimator: TokenEstimator,
    focusMaxTokens: Int = 300,
    private val negatives: KbNegatives? = null,
) : CellKnowledge {
    private val focus = FocusNotes(notes, estimator, focusMaxTokens, compiled = injected)
    private val citedIds = LinkedHashSet<String>()

    override fun focusNotes(focus: String?, touched: Set<String>): String? {
        val before = this.focus.shownIds.toSet()
        val block = this.focus.render(focus, touched)
        for (id in this.focus.shownIds) if (id !in before) usage.record(id, ids, UsageEvent.Injected, "focus")
        return block
    }

    override fun cited(register: Register) {
        val visible = injected + focus.shownIds
        val text = io.astrolabe.register.RegisterRender.markdown(register)
        for (id in Usage.citations(text, visible)) if (citedIds.add(id)) usage.record(id, ids, UsageEvent.Cited)
        negatives?.openNeeds(ids, register.version, register)
    }

    /** `(injected, cited-in-register?)` per note shown to this cell, for the log line. */
    public fun log(): String = (injected + focus.shownIds).joinToString(", ") { "$it:${if (it in citedIds) "cited" else "not cited"}" }
}
