package io.astrolabe.register

import io.astrolabe.evidence.ClaimKind
import io.astrolabe.provider.TokenEstimator

/**
 * Renders STATE in the §5.2 shape. Deterministic: same register ⇒ same bytes; no timestamps. The harness adds
 * the `v(stale @old)` tag and the fired-trip marker; the model never emits this text (kernel contract §14).
 */
public object RegisterRender {
    @JvmStatic
    public fun markdown(register: Register): String {
        val sb = StringBuilder()
        sb.append("# STATE v").append(register.version).append(" · cell ").append(register.cell)
            .append(" · ").append(register.increment).append(" \"").append(register.incrementTitle).append("\"\n")
        if (register.constraints.isNotEmpty()) {
            sb.append("## Constraints (inferred)  ").append(register.constraints.joinToString(" · ") { "- $it" }).append('\n')
        }
        sb.append("## Plan       ")
        if (register.plan.isEmpty()) sb.append("(none)\n") else {
            register.plan.forEachIndexed { i, step ->
                if (i > 0) sb.append("              ")
                sb.append(step.n).append(". ").append(step.mark.text).append(' ').append(step.text)
                step.evidence?.let { sb.append(" (").append(it).append(')') }
                step.accept?.let { sb.append("  accept: ").append(it) }
                step.req?.let { sb.append("  → ").append(it) }
                step.after?.let { sb.append("  after: ").append(it) }
                step.reason?.let { sb.append(" — ").append(it) }
                sb.append('\n')
            }
        }
        if (register.facts.isNotEmpty()) {
            sb.append("## Facts      ")
            register.facts.forEachIndexed { i, fact ->
                if (i > 0) sb.append("              ")
                sb.append("- ").append(kind(fact))
                sb.append(' ').append(fact.text)
                fact.anchor?.let { sb.append("  ").append(it.toString()) }
                fact.evidenceId?.let { sb.append(" [").append(it).append(']') }
                fact.refutedBy?.let { sb.append("  (refuted ").append(it).append("; kept)") }
                if (fact.stale) sb.append("  ← harness-rendered; re-look")
                sb.append('\n')
            }
        }
        section(sb, "## Dead ends  ", register.deadEnds) { d ->
            "- ${d.text}" + (d.evidence?.let { " ($it)" } ?: "") + "   scope: ${d.scope}   reopen: ${d.reopen}"
        }
        section(sb, "## Decisions  ", register.decisions) { d ->
            "- D${d.n}: ${d.text} — because ${d.because}" + (d.rejected?.let { "; rejected: $it" } ?: "") +
                (d.probe?.let { "; probe: $it" } ?: "") + (if (d.adrCandidate) "   (→ candidate ADR)" else "")
        }
        val openItems = register.open.filter { !it.closed }
        section(sb, "## Open       ", openItems) { o ->
            "- Q${o.n}: ${o.text}" + (o.trip?.let { " (trip: $it)" } ?: "") + (o.needs?.let { "  (needs: $it)" } ?: "")
        }
        register.focus?.let { sb.append("## Focus      ").append(it).append('\n') }
        section(sb, "## Amendments ", register.amendments) { a -> "- propose ${a.change} because ${a.reason} (${a.status})" }
        sb.append("## Next       ").append(register.next ?: "(none)").append('\n')
        return sb.toString()
    }

    /** Fired-trip lines for `[A]` (P1.8.3), rendered once each. */
    @JvmStatic
    public fun firedTrips(register: Register): List<String> =
        register.open.filter { it.fired && !it.closed }.map { "⟨trip Q${it.n} fired: ${it.trip} → ${it.text}⟩" }

    @JvmStatic
    public fun tokens(register: Register, estimator: TokenEstimator): Long = estimator.estimate(markdown(register)).tokens

    private fun kind(fact: Fact): String = when (fact.kind) {
        ClaimKind.Hypothesis -> "h"
        ClaimKind.Verified -> if (fact.stale) "v(stale @${fact.staleAt!!.hash8})" else "v"
        ClaimKind.Refuted -> "x"
    }

    private fun <T> section(sb: StringBuilder, header: String, items: List<T>, line: (T) -> String) {
        if (items.isEmpty()) return
        sb.append(header)
        items.forEachIndexed { i, item ->
            if (i > 0) sb.append("              ")
            sb.append(line(item)).append('\n')
        }
    }
}
