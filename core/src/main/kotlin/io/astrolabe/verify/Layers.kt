package io.astrolabe.verify

/**
 * The scheduled rows of the §8.1 layer table (L1–L2 of the §8.2 ladder). Inline syntax runs inside `edit` and the
 * end-of-turn checker in [Checker]; independent review (L5) arrives with P4.
 */
public enum class Layer {
    /** `[>]` moves · `risk > θ` · fused `run(if: applied)` · `verify(tests)`: blast-radius tests ∪ the step's `accept:`. */
    BlastAndStepAccept,

    /** Increment end: every `accept:` of the increment (verify-on-stop, P3.1.3). */
    IncrementAcceptance,

    /** Every K increments and at campaign end: the project suite and the configured quality gates. */
    FullSuiteAndQuality,

    /** S3 merge (P5.1.3): blast radius ∪ acceptance over the combined tree. */
    IntegrationReverification,
}

/** What one layer runs now, and what it cannot test (`not_tested`, §8.2), each with the concrete reason. */
public data class LayerSelection(val layer: Layer, val run: List<Check>, val notTested: List<String>)

/** Check selection per layer (§8.1): only checks that [LayerSelection.run] needs are selected; valid receipts stand. */
public object Layers {
    /**
     * The checks [layer] runs for [acceptanceIds], keeping those [due] says have no current, eligible receipt. A step
     * `accept:` without a registered check and a missing blast, full-suite or quality-gate check are `not_tested`;
     * [blastUnselected] is why `CHK-tests-blast` is absent ([BlastSelection.NotSelected]).
     */
    @JvmStatic
    @JvmOverloads
    public fun select(
        layer: Layer,
        checks: Checks,
        acceptanceIds: Collection<String>,
        blastUnselected: String = "blast radius: not selected",
        due: (Check) -> Boolean,
    ): LayerSelection {
        val notTested = ArrayList<String>()
        fun accepted(): List<Check> = acceptanceIds.flatMap { checks.forAcceptance(it) }.filter { it.kind != CheckKind.Full }
        fun blast(): List<Check> = listOfNotNull(checks[Checks.TESTS_BLAST]).also { if (it.isEmpty()) notTested += blastUnselected }
        val candidates = when (layer) {
            Layer.BlastAndStepAccept, Layer.IntegrationReverification -> {
                acceptanceIds.filter { checks.forAcceptance(it).isEmpty() }.forEach { notTested += "accept $it: no registered check" }
                blast() + accepted()
            }
            Layer.IncrementAcceptance -> accepted()
            Layer.FullSuiteAndQuality -> {
                val suite = checks[Checks.FULL] ?: checks.all().firstOrNull { it.kind == CheckKind.Full }
                if (suite == null) notTested += "full suite: none declared by the repository"
                val gates = listOfNotNull(checks[Checks.QUALITY_GATE]) + checks.all().filter { it.kind == CheckKind.Quality && it.id != Checks.QUALITY_GATE }
                if (gates.isEmpty()) notTested += "quality gates: none configured"
                listOfNotNull(suite) + gates
            }
        }
        return LayerSelection(layer, candidates.distinctBy { it.id }.filter(due), notTested)
    }
}
