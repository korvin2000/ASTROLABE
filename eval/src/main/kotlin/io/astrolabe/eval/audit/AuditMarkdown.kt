package io.astrolabe.eval.audit

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/** `audit.md`: the report's tables per run and per group; `—` marks what the log cannot measure, never a zero. */
public object AuditMarkdown {
    @JvmStatic
    public fun render(report: AuditReport): String = buildString {
        appendLine("# Audit · ${report.runs.size} runs")
        appendLine()
        appendLine("Money shares are of each run's basis total: billed when every call with usage was billed, else the priced estimate.")
        appendLine("Hit share = Σ cache_read / Σ input (F §6.1); q̂ = Σ c / Σ b over unchanged-prefix steps (§10.4).")
        appendLine()
        appendLine("## Prices (per million tokens)")
        appendLine()
        row("route", "calls", "agreement", "uncached", "cache read", "cache write", "output", "max residual", "max rel.")
        rule(9)
        for (f in report.prices) {
            val p = f.perMillion
            row(f.binding.toString(), "${f.calls}", f.agreement.name, money(p[PriceClass.UncachedInput]), money(p[PriceClass.CacheRead]),
                money(p[PriceClass.CacheWrite]), money(p[PriceClass.Output]), money(f.maxResidual), f.maxRelativeResidual?.let { String.format(Locale.ROOT, "%.2g", it) } ?: "—")
        }
        appendLine()
        appendLine("## Runs: anatomy and cache")
        appendLine()
        row("run", "calls", "basis", "total", "billed", "estimate", "uncached", "cache read", "output", "of it reasoning", "unexplained", "hit share", "q̂ (steps)", "reliability")
        rule(14)
        for (r in report.runs) {
            val a = r.anatomy
            row(r.run.source, "${a.calls}" + if (a.callsWithUsage < a.calls) " (${a.calls - a.callsWithUsage} no usage)" else "", a.basis.name, money(a.total),
                money(a.billed), money(a.estimate), pct(a.uncachedInput.share), pct(a.cacheRead.share), pct(a.output.share), pct(a.reasoning.share),
                money(a.unexplained), pct(r.cache.hitShare), "${pct(r.cache.qHat)} (${r.cache.eligibleSteps})", pct(r.cache.reliability))
        }
        appendLine()
        appendLine("## Runs: losses (share of run money; overlapping)")
        appendLine()
        row("run", *Waste.entries.map { it.code }.toTypedArray())
        rule(1 + Waste.entries.size)
        for (r in report.runs) row(r.run.source, *r.wastes.map { w -> if (w.unmeasured != null) "—" else pct(w.share) }.toTypedArray())
        appendLine()
        appendLine("## Runs: Residency what-if (fixed trajectory; low–high = ± the replay's error on the observed cadence)")
        appendLine()
        row("run", "observed (priced)", "model error", "scenarios", "logged batches")
        rule(5)
        for (r in report.runs) {
            val w = r.residency
            val scenarios = if (w.unmeasured != null) "— ${w.unmeasured}" else w.scenarios.joinToString("<br>") { s ->
                "${s.label}: ${money(s.cost)} (${money(s.low)}–${money(s.high)}) · batches ${s.batches.joinToString(",").ifEmpty { "none" }} · re-paid ${s.repaidTokens}"
            }
            row(r.run.source, money(w.observedCost), money(w.modelError), scenarios, w.loggedBatches?.joinToString(",")?.ifEmpty { "none" } ?: "not logged")
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
            row(g.group, "${g.runs}", "${g.calls}", g.externallyAccepted?.toString() ?: "—", "${g.acceptUnverified}", money(g.billed), money(g.estimate),
                pct(g.uncachedInput.share), pct(g.cacheRead.share), pct(g.output.share), pct(g.reasoning.share), pct(g.hitShare), pct(g.qHat),
                *g.wastes.map { w -> if (w.unmeasured != null) "—" else pct(w.share) }.toTypedArray())
        }
        appendLine()
        appendLine("## Details")
        for (r in report.runs) {
            appendLine()
            appendLine("### ${r.run.source}")
            val a = r.anatomy
            appendLine("- tokens: uncached ${a.uncachedInput.tokens ?: "—"} · cache read ${a.cacheRead.tokens ?: "—"} · cache write ${a.cacheWrite.tokens ?: "—"} · output ${a.output.tokens ?: "—"} · reasoning ${a.reasoning.tokens ?: "—"}")
            appendLine("- money: uncached ${money(a.uncachedInput.money)} · cache read ${money(a.cacheRead.money)} · output ${money(a.output.money)} (reasoning ${money(a.reasoning.money)}) · billed upstream ${money(a.billedUpstream)}")
            appendLine("- prices: ${a.prices.joinToString("; ").ifEmpty { "none" }}")
            for (b in r.cache.breaks) appendLine("- break at turn ${b.turn} (${b.cause}): b ${b.step.cacheable}, cached ${b.step.cached}, re-paid ${b.step.shortfall}, cost ${money(b.cost)}")
            for (w in r.wastes) {
                val what = w.unmeasured?.let { "unmeasured: $it" } ?: "${money(w.money)} (${pct(w.share)}), calls ${w.calls ?: "—"}, tokens ${w.tokens ?: "—"}" + (w.detail?.let { "; $it" } ?: "")
                appendLine("- ${w.waste.code} ${w.waste.name}: $what")
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

    private fun pct(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f %%", it * 100) } ?: "—"

    private fun money(value: BigDecimal?): String = value?.round(MathContext(5))?.stripTrailingZeros()?.toPlainString() ?: "—"
}
