package io.astrolabe.kb

import io.astrolabe.java.JavaEmbeddingProvider
import io.astrolabe.java.JavaRetriever
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bridges between the Kotlin [Retriever]/[EmbeddingProvider] and their Java SPI forms (D-07, TODO P5.5.1). */
public object Retrievers {
    @JvmStatic
    public fun fromJava(retriever: JavaRetriever): Retriever = object : Retriever {
        override val source: RetrievalSource = retriever.source()
        override suspend fun candidates(query: String, limit: Int): List<KbHit> = retriever.candidates(query, limit).await()
    }

    @JvmStatic
    public fun fromJava(provider: JavaEmbeddingProvider): EmbeddingProvider = EmbeddingProvider { texts -> provider.embed(texts).await() }

    private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value) else cont.resumeWithException(error.unwrap())
        }
    }

    private fun Throwable.unwrap(): Throwable = if (this is CompletionException) cause ?: this else this
}
