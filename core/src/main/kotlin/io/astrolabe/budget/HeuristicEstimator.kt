package io.astrolabe.budget

import io.astrolabe.provider.Estimate
import io.astrolabe.provider.TokenEstimator
import kotlin.math.ceil

/**
 * Planning-grade estimate (D-06): `ceil(utf8Bytes / 3.6)` plus one token per four symbol characters, because
 * code punctuation tokenizes densely. Never exact; the declared margin is 10 % (at least one token). Dispatch
 * admission must use a profile-specific contract on top of this (I-17).
 */
public class HeuristicEstimator(private val bytesPerToken: Double = 3.6) : TokenEstimator {
    override val id: String = "heuristic-bytes"
    override val version: String = "1"

    override fun estimate(text: String): Estimate {
        if (text.isEmpty()) return Estimate.zero(id, version, exact = false)
        val bytes = text.toByteArray(Charsets.UTF_8).size
        var symbols = 0
        for (c in text) if (!c.isLetterOrDigit() && !c.isWhitespace()) symbols++
        val tokens = ceil(bytes / bytesPerToken).toLong() + symbols / 4
        val margin = maxOf(1L, ceil(tokens * MARGIN).toLong())
        return Estimate(tokens, exact = false, estimatorId = id, version = version, marginTokens = margin)
    }

    private companion object {
        const val MARGIN = 0.10
    }
}
