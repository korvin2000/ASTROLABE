package io.astrolabe.kb

import kotlin.math.sqrt

/**
 * Host-supplied embedding computation (§4.5, TODO P5.5.1 Build: "embedding provider contract (compute + index
 * in `indexes/`, disposable)"): [embed] turns each text into one vector, same order. `suspend` here (D-07);
 * see [io.astrolabe.java.JavaEmbeddingProvider] for the Java-implementable form. Building an [Embedding] per
 * note (id/kind/summary + this vector) and persisting the set under `indexes/` — a derived, regenerable cache;
 * SQLite stays canonical for the note itself (§2.3 storage rule) — is the indexer's job, deferred to P5.5's
 * wiring; this fixes the compute contract only. A failing or cold provider is [Retrieval.merge]'s concern
 * (FX-46), not this interface's: implementations raise, callers degrade.
 */
public fun interface EmbeddingProvider {
    public suspend fun embed(texts: List<String>): List<List<Float>>
}

/** One note's precomputed dense vector plus enough front matter to answer a [KbHit] without a second lookup. */
public data class Embedding(public val noteId: String, public val kind: String, public val summary: String, public val vector: List<Float>) {
    init {
        require(noteId.isNotBlank()) { "an embedding names its note" }
        require(vector.isNotEmpty()) { "an embedding needs at least one dimension" }
    }
}

/**
 * A dense [Retriever] over a precomputed [index] (TODO P5.5.1): cosine similarity between the query's own
 * embedding (computed once through [provider]) and every indexed note, highest first. This only ranks what it
 * is given — building and refreshing [index] from `indexes/` is P5.5's wiring debt (same deferral as
 * [io.astrolabe.atlas.LanguageService]'s `look` wiring, P5.4.2).
 */
public class DenseRetriever(private val provider: EmbeddingProvider, private val index: List<Embedding>) : Retriever {
    override val source: RetrievalSource = RetrievalSource.Dense

    override suspend fun candidates(query: String, limit: Int): List<KbHit> {
        if (index.isEmpty()) return emptyList()
        val queried = provider.embed(listOf(query)).firstOrNull() ?: return emptyList()
        return index.asSequence()
            .mapNotNull { embedding -> cosine(queried, embedding.vector)?.let { embedding to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (embedding, score) -> KbHit(embedding.noteId, embedding.kind, embedding.summary, score = score.toDouble()) }
            .toList()
    }

    private fun cosine(a: List<Float>, b: List<Float>): Float? {
        if (a.size != b.size || a.isEmpty()) return null
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return null
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
