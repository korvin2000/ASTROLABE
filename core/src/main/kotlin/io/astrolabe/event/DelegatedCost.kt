package io.astrolabe.event

import kotlinx.serialization.Serializable

/**
 * The §10.1 worth-test estimate of one delegation, in tokens: `context duplication + child generation + tool work +
 * parent interpretation + validation + integration + retries`. Advisory data carried on the dispatch event; it never
 * decides a dispatch on its own.
 */
@Serializable
public data class DelegatedCost(
    val contextDuplicationTokens: Long,
    val childGenerationTokens: Long,
    val toolWorkTokens: Long,
    val parentInterpretationTokens: Long,
    val validationTokens: Long,
    val integrationTokens: Long,
    val retriesTokens: Long,
) {
    init {
        require(listOf(contextDuplicationTokens, childGenerationTokens, toolWorkTokens, parentInterpretationTokens, validationTokens, integrationTokens, retriesTokens).all { it >= 0 }) {
            "cost components are ≥ 0"
        }
    }

    val totalTokens: Long
        get() = contextDuplicationTokens + childGenerationTokens + toolWorkTokens + parentInterpretationTokens + validationTokens + integrationTokens + retriesTokens
}
