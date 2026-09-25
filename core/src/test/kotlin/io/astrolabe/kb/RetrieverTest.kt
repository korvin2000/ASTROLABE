package io.astrolabe.kb

import io.astrolabe.fixtures.FakeEmbeddingProvider
import io.astrolabe.java.JavaEmbeddingProvider
import io.astrolabe.java.JavaRetriever
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P5.5.1: the `Retriever` contract — the lexical default, a dense fake, and the merge that adds one without protocol change. */
class RetrieverTest {
    private val notes = mapOf(
        "LES-1" to "the payments handler retries on a 503 from the gateway",
        "LES-2" to "the report totals round half-up per merchant policy",
    )

    private fun kb(hits: List<KbHit>): Kb = object : Kb {
        override fun search(query: String, kinds: Set<String>?, scope: String?, why: String): KbHits = KbHits(hits, "kb", complete = true)
        override fun get(id: String): KbEntry? = null
        override fun skill(id: String): KbEntry? = null
    }

    @Test
    fun `the lexical retriever adapts kb search to the retriever protocol and caps the limit`() = runTest {
        val hits = listOf(KbHit("LES-1", "LES", "retries on 503", score = 1.2), KbHit("LES-2", "LES", "totals round half-up", score = 3.4))
        val retriever: Retriever = LexicalRetriever(kb(hits))
        assertEquals(RetrievalSource.Lexical, retriever.source)
        assertEquals(hits, retriever.candidates("gateway", limit = 5))
        assertEquals(hits.take(1), retriever.candidates("gateway", limit = 1))
    }

    @Test
    fun `the dense retriever ranks a precomputed index by cosine similarity to the query`() = runTest {
        val provider = FakeEmbeddingProvider()
        val index = notes.entries.map { (id, text) -> Embedding(id, "LES", text.take(40), provider.embed(listOf(text)).single()) }
        val retriever: Retriever = DenseRetriever(provider, index)
        assertEquals(RetrievalSource.Dense, retriever.source)
        val top = retriever.candidates("gateway retries payments handler 503", limit = 1)
        assertEquals("LES-1", top.single().id, "the shared vocabulary (payments/retries/handler/503/gateway) ranks LES-1 first")
        assertEquals(emptyList(), DenseRetriever(provider, emptyList()).candidates("anything", limit = 5))
    }

    @Test
    fun `an unavailable source degrades the merge instead of blocking it`() = runTest {
        val lexical = LexicalRetriever(kb(listOf(KbHit("LES-2", "LES", "totals round half-up", score = 3.4))))
        val brokenDense = DenseRetriever(FakeEmbeddingProvider(unavailable = true), listOf(Embedding("LES-1", "LES", "x", listOf(1f))))
        val result = Retrieval.merge(listOf(lexical, brokenDense), "totals", limit = 5)
        assertEquals(listOf("LES-2"), result.hits.map { it.id })
        assertTrue(result.degradation!!.contains("dense retrieval unavailable"), result.degradation.toString())
    }

    @Test
    fun `merge unions two sources by note id without a protocol change, the earlier source winning a tie`() = runTest {
        val lexicalHit = KbHit("LES-1", "LES", "lexical summary", score = 9.0)
        val lexical = LexicalRetriever(kb(listOf(lexicalHit)))
        val provider = FakeEmbeddingProvider()
        val index = listOf(Embedding("LES-1", "LES", "dense summary", provider.embed(listOf("x")).single()), Embedding("LES-2", "LES", "dense only", provider.embed(listOf("y")).single()))
        val dense = DenseRetriever(provider, index)
        val result = Retrieval.merge(listOf(lexical, dense), "x", limit = 5)
        assertEquals(setOf("LES-1", "LES-2"), result.hits.map { it.id }.toSet())
        assertEquals(lexicalHit, result.hits.single { it.id == "LES-1" }, "the lexical source (listed first) wins the LES-1 tie")
        assertNull(result.degradation)
        assertEquals(RetrievalResult(emptyList(), null), Retrieval.merge(emptyList(), "x", limit = 5))
    }

    @Test
    fun `java retriever and embedding-provider bridges map futures`() = runTest {
        val javaProvider = object : JavaEmbeddingProvider {
            override fun embed(texts: List<String>): CompletableFuture<List<List<Float>>> = CompletableFuture.completedFuture(texts.map { listOf(1f, 0f) })
        }
        val provider = Retrievers.fromJava(javaProvider)
        assertEquals(listOf(listOf(1f, 0f)), provider.embed(listOf("q")))

        val javaRetriever = object : JavaRetriever {
            override fun source(): RetrievalSource = RetrievalSource.Dense
            override fun candidates(query: String, limit: Int): CompletableFuture<List<KbHit>> = CompletableFuture.completedFuture(listOf(KbHit("LES-1", "LES", "x")))
        }
        val retriever = Retrievers.fromJava(javaRetriever)
        assertEquals(RetrievalSource.Dense, retriever.source)
        assertEquals(listOf(KbHit("LES-1", "LES", "x")), retriever.candidates("q", 5))
    }
}
