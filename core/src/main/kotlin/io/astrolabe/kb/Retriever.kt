package io.astrolabe.kb

/** Where a candidate came from (§4.5, TODO P5.5.1): the always-on lexical default, or a dense source enabled only after a measured lexical-miss rate justifies it ([IM §7.4](../knowledge/records.md#sec-4-5)). */
public enum class RetrievalSource { Lexical, Dense }

/**
 * A ranked source of [KbHit] candidates for a query (§4.5, TODO P5.5.1 Done: "ranker can consume a second
 * candidate source without protocol change"). [StoreKb]'s FTS5 search is the lexical default (P2.6.2, always
 * present, wrapped by [LexicalRetriever]); a dense source ([DenseRetriever]) speaks the exact same [KbHit]
 * shape, so [Retrieval.merge] needs no new protocol to add or drop one. D-195: `suspend` here (D-07) so a
 * dense implementation's embedding call composes without forcing every source to poll — the lexical default
 * wraps a synchronous [Kb.search] trivially, no thread ever blocks a caller waiting on it. No implementation
 * of this interface is host-supplied; a host instead implements [EmbeddingProvider], which is.
 */
public interface Retriever {
    public val source: RetrievalSource

    public suspend fun candidates(query: String, limit: Int): List<KbHit>
}

/** The lexical default (P2.6.2): [Kb.search] hits already ranked by `bm25`; this only adapts the call shape and caps [Retriever.candidates]'s `limit`. */
public class LexicalRetriever(private val kb: Kb) : Retriever {
    override val source: RetrievalSource = RetrievalSource.Lexical

    override suspend fun candidates(query: String, limit: Int): List<KbHit> = kb.search(query, why = "retriever candidates").hits.take(limit)
}

/** [Retrieval.merge]'s outcome: the merged, deduped hits plus what degraded (FX-46: never a block). */
public data class RetrievalResult(public val hits: List<KbHit>, public val degradation: String? = null)

/**
 * Merges every configured [Retriever]'s candidates into one deduped [KbHit] list (TODO P5.5.1). Scores are
 * never compared *across* sources here — a lexical `bm25` score and a dense cosine similarity are not on the
 * same scale — so this only unions by note id, [sources] earlier in the list winning a tie; whatever ranks
 * the union next (e.g. the injection formula's own D-37 score) is unaffected by how many sources fed it. A
 * source that throws degrades instead of blocking the query (FX-46), named in [RetrievalResult.degradation].
 * Dropping every [RetrievalSource.Dense] entry from [sources] reproduces the lexical-only result exactly.
 */
public object Retrieval {
    @JvmStatic
    public suspend fun merge(sources: List<Retriever>, query: String, limit: Int): RetrievalResult {
        val degraded = ArrayList<String>()
        val seen = LinkedHashMap<String, KbHit>()
        for (retriever in sources) {
            val hits = try {
                retriever.candidates(query, limit)
            } catch (unavailable: RuntimeException) {
                degraded += "${retriever.source.name.lowercase()} retrieval unavailable: ${unavailable.message}"
                continue
            }
            for (hit in hits) seen.putIfAbsent(hit.id, hit)
        }
        return RetrievalResult(seen.values.take(limit).toList(), degraded.joinToString("; ").ifEmpty { null })
    }
}
