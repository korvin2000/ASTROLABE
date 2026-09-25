package io.astrolabe.java

import java.util.concurrent.CompletableFuture

/**
 * Java-implementable form of [io.astrolabe.kb.EmbeddingProvider] (D-07, I-14, TODO P5.5.1). The future may
 * complete on any thread; an exceptional completion is treated as the dense source being unavailable for that
 * call ([io.astrolabe.kb.Retrieval.merge] degrades, FX-46) rather than failing the whole query. No coroutine
 * types appear in this package.
 */
public interface JavaEmbeddingProvider {
    public fun embed(texts: List<String>): CompletableFuture<List<List<Float>>>
}
