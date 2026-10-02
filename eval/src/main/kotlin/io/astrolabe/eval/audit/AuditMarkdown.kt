package io.astrolabe.eval.audit

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/** `audit.md`: the report's tables per run and per group; `—` marks what the log cannot measure and `n/a` a class no provider has — never a zero. */
public object AuditMarkdown {
    @JvmStatic
    public fun render(report: AuditReport): String = buildString {
        appendLine("# Audit · ${report.runs.size} runs")
        appendLine()
        appendLine("Money shares are of each run's basis total: billed when every call with usage was billed, else the priced estimate;")
        appendLine("`partial` marks a run some of whose calls have no usage, an unknown class or no charge.")
        appendLine("Hit share = Σ cache_read / Σ input (F §6.1); q̂ = Σ min(c, b) / Σ b over unchanged-prefix steps (§10.4); the Beta posterior of q is E1's.")
        appendLine()
        appendLine("## Prices (per million tokens)")
        appendLine()
        appendLine("Fitted from the billed calls of each route (exact least squares). `*` pools a model's upstreams: an estimate, used only for a route whose own calls identify nothing.")
        appendLine("Condition: of the design matrix with unit-norm columns. Sensitivity: largest move of a price if every charge moved by one unit of its last decimal.")
        appendLine()
        row("route", "calls", "agreement", "uncached", "cache read", "cache write", "output", "max residual", "max rel.", "condition", "sensitivity (u/c/o)")
        rule(11)
        for (f in report.prices) {
            val p = f.perMillion
            val s = f.sensitivityPerMillion
            row(f.binding.toString(), "${f.calls}", f.agreement.name, money(p[PriceClass.UncachedInput]), money(p[PriceClass.CacheRead]),
                money(p[PriceClass.CacheWrite]), money(p[PriceClass.Output]), money(f.maxResidual), number(f.maxRelativeResidual), number(f.condition),
                listOf(PriceClass.UncachedInput, PriceClass.CacheRead, PriceClass.Output).joinToString(" / ") { number(s[it]) })
        }
        appendLine()
        appendLine("## Runs: anatomy and cache")
        appendLine()
        row("run", "calls", "basis", "total", "billed", "estimate", "uncached", "cache read", "cache write", "output", "of it reasoning", "unexplained", "hit share", "q̂ (steps)", "reliability")
        rule(15)
        for (r in report.runs) {
            val a = r.anatomy
            row(r.run.source, "${a.calls}" + if (a.callsWithUsage < a.calls) " (${a.calls - a.callsWithUsage} no usage)" else "",
                a.basis.name + (if (a.complete) "" else " · partial") + (if (a.pricesEstimated) " · est. prices" else ""), money(a.total),
                money(a.billed), money(a.estimate), share(a.uncachedInput), share(a.cacheRead), share(a.cacheWrite), share(a.output), share(a.reasoning),
                money(a.unexplained), pct(r.cache.hitShare), "${pct(r.cache.qHat)} (${r.cache.eligibleSteps})", pct(r.cache.reliability))
        }
        appendLine()
        appendLine("## Runs: losses (share of run money; overlapping)")
        appendLine()
        row("run", *Waste.entries.map { it.code }.toTypedArray())
        rule(1 + Waste.entries.size)
        for (r in report.runs) row(r.run.source, *r.wastes.map { w -> if (w.unmeasured != null) "—" else pct(w.share) }.toTypedArray())
        appendLine()
        appendLine("## Runs: Residency what-if")
        appendLine()
        appendLine("Conditional estimates on this log with the model's trajectory held fixed — not bounds; the calibration error is the replay of the observed cadence minus the observed cost.")
        appendLine("Batches: turn (stubbed/losses); the journal's own batches beside them.")
        appendLine()
        row("run", "observed (priced)", "calibration error", "scenarios", "logged batches")
        rule(5)
        for (r in report.runs) {
            val w = r.residency
            val scenarios = if (w.unmeasured != null) "— ${w.unmeasured}" else w.scenarios.joinToString("<br>") { s ->
                "${s.label}: ${money(s.cost)} · batches ${s.batches.joinToString(", ") { b -> "${b.turn}${if (b.trigger == "budget") "b" else ""} (${b.stubbed}/${b.losses})" }.ifEmpty { "none" }} · re-paid ${s.repaidTokens}"
            }
            row(r.run.source, money(w.observedCost), money(w.calibrationError), scenarios,
                w.loggedBatches?.joinToString(", ") { "${it.turn} (${it.stubbed}/${it.losses})" }?.ifEmpty { "none" } ?: "not logged")
        }
        appendLine()
        appendLine("## Runs: provenance")
        appendLine()
        row("run", "requirements", "verification", "checks", "outcome", "acceptance", "accept-unverified", "acceptance surface", "test edits", "external")
        rule(10)
        for (r in report.runs) {
            val p = r.provenance
            row(r.run.source, p.requirements.joinToString("<br>"), p.verification ?: "—", p.checks.joinToString("<br>").ifEmpty { "—" }, p.outcome ?: "—",
                p.acceptance ?: "—", p.acceptUnverified?.toString() ?: "—", "${p.acceptanceSurface.size}", p.testEdits.joinToString(", ").ifEmpty { "—" }, p.external ?: "—")
        }
        appendLine()
        appendLine("## Groups (model × arm)")
        appendLine()
        row("group", "runs", "calls", "accepted", "accept-unverified", "billed", "estimate", "uncached", "cache read", "output", "reasoning", "hit share", "q̂", *Waste.entries.map { it.code }.toTypedArray())
        rule(13 + Waste.entries.size)
        for (g in report.groups) {
            row(g.group + if (g.complete) "" else " · partial", "${g.runs}", "${g.calls}", g.externallyAccepted?.toString() ?: "—", "${g.acceptUnverified}", money(g.billed), money(g.estimate),
                share(g.uncachedInput), share(g.cacheRead), share(g.output), share(g.reasoning), pct(g.hitShare), pct(g.qHat),
                *g.wastes.map { w -> if (w.unmeasured != null) "—" else pct(w.share) }.toTypedArray())
        }
        appendLine()
        appendLine("## Details")
        for (r in report.runs) {
            appendLine()
            appendLine("### ${r.run.source}")
            val a = r.anatomy
            appendLine("- tokens: uncached ${tokens(a.uncachedInput)} · cache read ${tokens(a.cacheRead)} · cache write ${tokens(a.cacheWrite)} · output ${tokens(a.output)} · reasoning ${tokens(a.reasoning)}")
            appendLine("- money: uncached ${money(a.uncachedInput.money)} · cache read ${money(a.cacheRead.money)} · output ${money(a.output.money)} (reasoning ${money(a.reasoning.money)}) · billed upstream ${money(a.billedUpstream)}")
            appendLine("- prices: ${a.prices.joinToString("; ").ifEmpty { "none" }}")
            for (b in r.cache.breaks) appendLine("- break at turn ${b.turn} (${b.cause}): b ${b.step.cacheable}, cached ${b.step.cached}, re-paid ${b.step.shortfall}, cost ${money(b.cost)}")
            for (w in r.wastes) {
                val what = w.unmeasured?.let { "unmeasured: $it" } ?: "${money(w.money)} (${pct(w.share)}), calls ${w.calls ?: "—"}, tokens ${w.tokens ?: "—"}"
                appendLine("- ${w.waste.code} ${w.waste.name}: $what" + (w.detail?.let { "; $it" } ?: ""))
            }
            if (r.provenance.acceptanceSurface.isNotEmpty()) appendLine("- acceptance surface: ${r.provenance.acceptanceSurface.joinToString(" | ")}")
            if (r.provenance.extra.isNotEmpty()) appendLine("- unread finish fields: ${r.provenance.extra.keys.joinToString(", ")}")
        }
    }

    private fun StringBuilder.row(vararg cells: String) {
        appendLine(cells.joinToString(" | ", "| ", " |") { it.replace("|", "\\|").replace("\n", " ") })
    }

    private fun StringBuilder.rule(n: Int) {
        appendLine((1..n).joinToString("|", "|", "|") { "---" })
    }

    private fun share(c: Category): String = if (!c.reported) "n/a" else pct(c.share)

    private fun tokens(c: Category): String = if (!c.reported) "n/a" else c.tokens?.toString() ?: "—"

    private fun pct(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f %%", it * 100) } ?: "—"

    private fun number(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3g", it) } ?: "—"

    private fun money(value: BigDecimal?): String = value?.round(MathContext(5))?.stripTrailingZeros()?.toPlainString() ?: "—"
}
