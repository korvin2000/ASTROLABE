package io.astrolabe.fixtures

import io.astrolabe.kb.EmbeddingProvider

/**
 * A deterministic bag-of-words [EmbeddingProvider] for test fixtures (TODO P5.5.1): each text's vector is a
 * fixed-width hashed term histogram, so texts sharing words score a higher cosine similarity and unrelated
 * texts score near zero — enough to exercise [io.astrolabe.kb.DenseRetriever]'s ranking without a real model.
 * `unavailable` scripts a provider that always fails, for the degrade-not-block path (FX-46).
 */
public class FakeEmbeddingProvider(private val dimensions: Int = 16, private val unavailable: Boolean = false) : EmbeddingProvider {
    override suspend fun embed(texts: List<String>): List<List<Float>> {
        if (unavailable) throw IllegalStateException("fake embedding provider is offline")
        return texts.map { vector(it) }
    }

    private fun vector(text: String): List<Float> {
        val histogram = FloatArray(dimensions)
        for (word in Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }) {
            histogram[Math.floorMod(word.hashCode(), dimensions)] += 1f
        }
        return histogram.toList()
    }
}
