package io.astrolabe.context

import io.astrolabe.evidence.ClaimKind
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.register.Fact
import io.astrolabe.register.Register
import io.astrolabe.register.RegisterRender
import kotlinx.serialization.Serializable

/**
 * A register record moved out of the projection, kept verbatim with resolvable ids (§6.4): the STATUS note
 * (P2.4.3) holds these, and `x` facts are offered to the extractor as `PIT` candidates (P4.2.2).
 */
@Serializable
public data class ArchivedRecord(val cell: ContextId, val kind: String, val n: Int, val text: String, val evidence: String?, val reason: String)

/** The register after one §6.4 pass, what it archived, the stale streaks to feed the next pass and any capacity gap. */
public data class Retention(
    val register: Register,
    val archived: List<ArchivedRecord>,
    /** Fact number → consecutive cells it was compiled stale. */
    val staleStreak: Map<Int, Int>,
    /** `x` facts, archived or not: durable until campaign end. */
    val pitCandidates: List<Fact>,
    /** Set when required carry-forward still exceeds the cap after every inactive record left: never a deletion. */
    val capacityGap: String?,
)

/**
 * Cross-cell fact coherence and bounded retention (§6.4, P2.4.2), run at each compile. A `v` fact whose evidence
 * does not resolve is only a hypothesis; a moved anchor renders it `v(stale @old)`; stale for two consecutive cells
 * and not in [Retention] `referenced` ⇒ archived and dropped. Over the register cap, `x` facts leave the projection
 * first (oldest first, archived verbatim); dead ends, open items, decisions and live facts are required
 * carry-forward, so what still does not fit is reported as a capacity gap.
 */
public object FactCoherence {
    @JvmStatic
    @JvmOverloads
    public fun retain(
        register: Register,
        previousStreak: Map<Int, Int>,
        referenced: Set<Int>,
        currentVersion: (String) -> FileVersion?,
        evidenceExists: (String) -> Boolean,
        estimator: TokenEstimator,
        capTokens: Int = 1_200,
    ): Retention {
        val archived = ArrayList<ArchivedRecord>()
        val streak = HashMap<Int, Int>()
        val kept = ArrayList<Fact>()
        for (fact in register.facts) {
            var f = fact
            if (f.kind == ClaimKind.Verified && f.evidenceId != null && !evidenceExists(f.evidenceId)) {
                f = f.copy(kind = ClaimKind.Hypothesis)
            }
            val anchor = f.anchor
            if (f.kind == ClaimKind.Verified && anchor != null && f.staleAt == null && currentVersion(anchor.path) != anchor.version) {
                f = f.copy(staleAt = anchor.version)
            }
            if (f.kind == ClaimKind.Verified && f.staleAt != null) {
                val cells = (previousStreak[f.n] ?: 0) + 1
                if (cells >= 2 && f.n !in referenced) {
                    archived += archive(register.cell, f, "stale for $cells consecutive cells, unreferenced")
                    continue
                }
                streak[f.n] = cells
            }
            kept += f
        }
        var projected = register.copy(facts = kept)
        val refuted = kept.filter { it.kind == ClaimKind.Refuted }
        for (fact in refuted) {
            if (RegisterRender.tokens(projected, estimator) <= capTokens) break
            archived += archive(register.cell, fact, "inactive: refuted, over the ${capTokens}-token register cap")
            projected = projected.copy(facts = projected.facts.filter { it.n != fact.n })
        }
        val tokens = RegisterRender.tokens(projected, estimator)
        val gap = if (tokens > capTokens) "required carry-forward is $tokens tokens > the $capTokens-token register cap after archiving inactive records" else null
        return Retention(projected, archived, streak, refuted, gap)
    }

    private fun archive(cell: ContextId, fact: Fact, reason: String): ArchivedRecord {
        val kind = when (fact.kind) {
            ClaimKind.Hypothesis -> "h"
            ClaimKind.Verified -> "v"
            ClaimKind.Refuted -> "x"
        }
        val text = "$kind ${fact.text}" + (fact.anchor?.let { "  $it" } ?: "") + (fact.staleAt?.let { " (stale @${it.hash8})" } ?: "") +
            (fact.refutedBy?.let { "  (refuted $it)" } ?: "")
        return ArchivedRecord(cell, "fact", fact.n, text, fact.evidenceId ?: fact.refutedBy, reason)
    }
}
