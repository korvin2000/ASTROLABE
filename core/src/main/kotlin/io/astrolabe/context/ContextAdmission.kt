package io.astrolabe.context

import io.astrolabe.provider.Estimate
import io.astrolabe.provider.Request

/** Why a request cannot be admitted (§6.1): the answer is a rebuild or an explicit condition, never a malformed history. */
public enum class CapacityCondition { OverWindow, UnknownHistory }

/** One admission decision, kept for the manifest and for estimation-drift analysis (D-06). */
public data class AdmissionRecord(
    val estimateTokens: Long,
    val boundTokens: Long,
    val outputHeadroom: Long,
    val exact: Boolean,
    val learnedMargin: Long,
    val admitted: Boolean,
)

public sealed interface AdmissionDecision {
    public val record: AdmissionRecord

    public data class Admitted(override val record: AdmissionRecord) : AdmissionDecision

    public data class Capacity(val condition: CapacityCondition, val detail: String, override val record: AdmissionRecord) : AdmissionDecision
}

/**
 * The hard admission check before every dispatch (§6.1, §15.1, P2.3.3). The bound is the profile tokenizer's exact
 * count only when every contribution was counted by it; otherwise the estimate plus its declared margin plus a
 * margin learned from observed misses. Output/reasoning headroom is added. Replayed history of unknown size is
 * never a fit: it forces a fresh lineage or a capacity result (I-17). A provider rejection or a reported input above
 * the bound is an estimation miss and widens the learned margin for the rest of the lineage.
 */
public class ContextAdmission {
    private var learned: Long = 0
    private val log = ArrayList<AdmissionRecord>()

    public val records: List<AdmissionRecord> @Synchronized get() = log.toList()

    public val learnedMargin: Long @Synchronized get() = learned

    @Synchronized
    public fun check(request: Request, estimate: Estimate): AdmissionDecision {
        val headroom = request.maxOutputTokens.toLong()
        val limit = request.profile.capabilities.contextLimitTokens.toLong()
        if (estimate.unknownHistory) {
            val record = AdmissionRecord(estimate.tokens, -1, headroom, exact = false, learned, admitted = false)
            log += record
            return AdmissionDecision.Capacity(CapacityCondition.UnknownHistory, "replayed history of unknown size: start a fresh lineage", record)
        }
        val bound = if (estimate.exact) estimate.tokens else estimate.upperBoundTokens + learned
        val admitted = bound + headroom <= limit
        val record = AdmissionRecord(estimate.tokens, bound, headroom, estimate.exact, learned, admitted)
        log += record
        return if (admitted) {
            AdmissionDecision.Admitted(record)
        } else {
            AdmissionDecision.Capacity(
                CapacityCondition.OverWindow,
                "request bound $bound + output headroom $headroom > ${request.profile.id} window $limit" + (if (estimate.exact) "" else " (estimated)"),
                record,
            )
        }
    }

    /** The provider rejected [estimate]'s request for size: the estimate missed by an unknown amount, at least its margin. */
    @Synchronized
    public fun rejected(estimate: Estimate) {
        learned = maxOf(learned * 2, learned + maxOf(estimate.marginTokens, estimate.tokens / 20, 1))
    }

    /** The provider reported [actualInputTokens] for [estimate]'s request: an under-estimate widens the learned margin. */
    @Synchronized
    public fun observed(estimate: Estimate, actualInputTokens: Long?) {
        if (actualInputTokens == null || estimate.unknownHistory) return
        val miss = actualInputTokens - (estimate.upperBoundTokens + learned)
        if (miss > 0) learned += miss
    }
}
