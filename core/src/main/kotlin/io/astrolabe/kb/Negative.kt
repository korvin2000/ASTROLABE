package io.astrolabe.kb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The §12.2 `NEG` states: what a negative result means, so a later cell never reads "not found" as "absent". */
@Serializable
public enum class NegativeState(public val wire: String) {
    @SerialName("unknown")
    Unknown("unknown"),

    @SerialName("unsearched")
    Unsearched("unsearched"),

    @SerialName("searched-empty")
    SearchedEmpty("searched-empty"),

    @SerialName("contradicted")
    Contradicted("contradicted"),

    @SerialName("verified-absent")
    VerifiedAbsent("verified-absent"),
}

/**
 * Typed negative evidence (§12.2, L8): every state carries what bounds it — a search its scope, version and index
 * coverage, an absence its domain — and renders its own reading, so the note body says what was and was not
 * established. Only [VerifiedAbsent] may say "absent", and only within its domain.
 */
public sealed interface NegativeEvidence {
    public val state: NegativeState

    /** The body prefix a later cell reads: the state, its bounds and the reading it licenses. */
    public fun render(): String

    /** Nothing was recorded either way. */
    public object Unknown : NegativeEvidence {
        override val state: NegativeState get() = NegativeState.Unknown

        override fun render(): String = "unknown: nothing was searched or verified; no reading either way"
    }

    /** The claim was never looked for. */
    public object Unsearched : NegativeEvidence {
        override val state: NegativeState get() = NegativeState.Unsearched

        override fun render(): String = "unsearched: not looked for; no reading either way"
    }

    /** A bounded search found nothing: not found within [scope] at [version] with [indexCoverage], nothing more. */
    public data class SearchedEmpty(val scope: String, val version: String, val indexCoverage: String) : NegativeEvidence {
        init {
            require(scope.isNotBlank() && version.isNotBlank() && indexCoverage.isNotBlank()) { "a searched-empty result names its scope, version and index coverage" }
        }

        override val state: NegativeState get() = NegativeState.SearchedEmpty

        override fun render(): String = "searched-empty(scope=$scope, version=$version, index coverage=$indexCoverage): not found there at that version; not absent — outside that scope or coverage the claim is unknown"
    }

    /** Evidence against the claim: [by] is the evidence ref that contradicts it. */
    public data class Contradicted(val by: String) : NegativeEvidence {
        init {
            require(by.isNotBlank()) { "a contradiction names its evidence" }
        }

        override val state: NegativeState get() = NegativeState.Contradicted

        override fun render(): String = "contradicted by $by: the claim conflicts with that evidence"
    }

    /** Absence established within a bounded [domain] by [by]; outside the domain nothing is claimed. */
    public data class VerifiedAbsent(val domain: String, val by: String) : NegativeEvidence {
        init {
            require(domain.isNotBlank() && by.isNotBlank()) { "a verified absence names its bounded domain and its evidence" }
        }

        override val state: NegativeState get() = NegativeState.VerifiedAbsent

        override fun render(): String = "verified-absent(domain=$domain) by $by: absent within that domain only; outside it unknown"
    }

    public companion object {
        /** The state a rendered body or a `[state]`-prefixed summary declares, or `null` when it declares none. */
        @JvmStatic
        public fun stateOf(text: String): NegativeState? = NegativeState.entries.firstOrNull { text.startsWith("[${it.wire}]") || text.startsWith("${it.wire}: ") || text.startsWith("${it.wire}(") }
    }
}
