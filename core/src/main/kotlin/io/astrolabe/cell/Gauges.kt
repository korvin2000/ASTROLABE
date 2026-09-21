package io.astrolabe.cell

import io.astrolabe.budget.CellBudget
import io.astrolabe.register.Register
import io.astrolabe.tool.Envelope
import io.astrolabe.tool.Gauge
import io.astrolabe.tool.ToolOutcome
import io.astrolabe.workset.Workset

/**
 * Assembles the ~20-token gauge (§5.7) and puts it where the model sees it: after every tool result
 * and once more at the end of the turn.
 *
 * Every number comes from the component that owns it (L9) — window occupancy from residency, the
 * reserve from [CellBudget], the checks summary from the checker, coverage from the [Workset], the
 * STATE version from the [Register], the turn from the loop. Nothing here is model-authored and
 * nothing is recomputed from text.
 */
public object Gauges {

    /**
     * The gauge for this turn. [contextPercent] is window occupancy as residency measures it
     * (P1.8.6); it is passed in rather than derived here, because the budget's percentage answers a
     * different question and confusing the two is how a gauge starts lying.
     */
    @JvmStatic
    public fun of(
        contextPercent: Int,
        budget: CellBudget.Snapshot,
        checks: String,
        workset: Workset,
        register: Register,
        turn: Int,
        turnsMax: Int,
    ): Gauge {
        require(contextPercent in 0..100) { "context occupancy is a percentage, got $contextPercent" }
        return Gauge(
            contextPercent = contextPercent,
            reserveOk = budget.reserveOk,
            checks = checks.ifBlank { "none" },
            knownFiles = workset.entries.map { it.path }.distinct().size,
            knownTokens = workset.knownTokens,
            stateVersion = register.version,
            turn = turn,
            turnsMax = turnsMax,
        )
    }

    /** One tool result as `[T]` carries it: the envelope of §5.4 closed by the turn's gauge. */
    @JvmStatic
    public fun result(outcome: ToolOutcome, gauge: Gauge): String {
        val header = outcome.header
            ?: return outcome.body.let { if (it.isEmpty()) gauge.line() else it + "\n" + gauge.line() }
        return Envelope.render(header, outcome.body, gauge)
    }
}
