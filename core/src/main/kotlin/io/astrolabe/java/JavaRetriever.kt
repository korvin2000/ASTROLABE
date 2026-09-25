package io.astrolabe.java

import io.astrolabe.kb.KbHit
import io.astrolabe.kb.RetrievalSource
import java.util.concurrent.CompletableFuture

/**
 * Java-implementable form of [io.astrolabe.kb.Retriever] (D-07, I-14, TODO P5.5.1). For a host that supplies
 * its own ranked candidate source directly instead of going through [io.astrolabe.kb.EmbeddingProvider] and
 * [io.astrolabe.kb.DenseRetriever]. An exceptional completion degrades the query ([io.astrolabe.kb.Retrieval.merge],
 * FX-46) rather than failing it. No coroutine types appear in this package.
 */
public interface JavaRetriever {
    public fun source(): RetrievalSource

    public fun candidates(query: String, limit: Int): CompletableFuture<List<KbHit>>
}
