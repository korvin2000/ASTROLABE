package io.astrolabe.kb

/** One knowledge-base hit as the model sees it (§4.5); a stale note is labelled, never served as current (§4.4). */
public data class KbHit(
    val id: String,
    val kind: String,
    val summary: String,
    val stale: Boolean = false,
    val score: Double? = null,
)

/** Search hits with the completeness of the declared scope (L8: an empty scoped search is not absence). */
public data class KbHits @JvmOverloads constructor(
    val hits: List<KbHit>,
    val scope: String,
    val complete: Boolean,
    val truncated: Boolean = false,
    /** An optional retrieval layer that was unavailable and how the search degraded (FX-46); never a block. */
    val degradation: String? = null,
)

/** A note or skill body served by `kb(get)` / `kb(skill)`. */
public data class KbEntry(
    val id: String,
    val kind: String,
    val text: String,
    val stale: Boolean = false,
)

/**
 * The knowledge base as the `kb` tool family reads it (§4.5, §12, TODO P1.6.10): retrieval only — relevance is
 * never authorization (L10), and nothing here can amend the contract. P2.6 supplies the note store and the
 * curator; `kb.propose` stays masked until P4.1.
 */
public interface Kb {
    /** Notes matching [query] within [scope]; [why] is the model's stated need, logged for retrieval-miss analysis. */
    public fun search(query: String, kinds: Set<String>? = null, scope: String? = null, why: String): KbHits

    public fun get(id: String): KbEntry?

    public fun skill(id: String): KbEntry?
}

/**
 * The S0 knowledge base with no notes: every search is **complete and empty** — the whole (empty) base was
 * searched — never "absent" or "unknown"; every lookup finds nothing, with the same completeness.
 */
public object EmptyKb : Kb {
    override fun search(query: String, kinds: Set<String>?, scope: String?, why: String): KbHits =
        KbHits(emptyList(), scope ?: "kb", complete = true)

    override fun get(id: String): KbEntry? = null

    override fun skill(id: String): KbEntry? = null
}
