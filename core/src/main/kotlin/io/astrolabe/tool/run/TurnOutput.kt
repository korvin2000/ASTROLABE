package io.astrolabe.tool.run

import io.astrolabe.evidence.Aliases
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.tool.ToolOutcome

/**
 * The output budget one turn's `run` and `verify` results share in `[T]` (P8.C.15, `Defaults.runTurnBudgetTokens`).
 * Within it every result is shown as its executor rendered it, byte for byte. Past it a result that does not fit what
 * the others left is shown short: its head (the status and exit line, counts, limitations, first failures) and its tail
 * within its share, joined by one line that says the turn's budget cut it and how to read the whole.
 *
 * Only the shown body changes. The call ran in full; its header, `green`/`ready` facts, receipts, log, parse and the
 * outcome every gate, signature and claim reads stay the executor's, and a cut body grants nothing (D-49).
 */
internal object TurnOutput {
    /**
     * The short forms of [results] (op id → outcome, in emitted order) whose bodies together pass [budgetTokens];
     * an op absent from the map is shown as executed. Every result keeps at least a floor, the turn's failing results
     * get the remainder first and the rest follow in emitted order. A pure function of the outcomes.
     */
    fun fit(results: List<Pair<Int, ToolOutcome>>, budgetTokens: Long, estimator: TokenEstimator): Map<Int, ToolOutcome> {
        if (results.isEmpty()) return emptyMap()
        val costs = results.associate { (opId, outcome) -> opId to estimator.estimate(outcome.body).tokens }
        if (costs.values.sum() <= budgetTokens) return emptyMap()
        val floor = minOf(SHORT_FORM_TOKENS, budgetTokens / results.size)
        var left = budgetTokens - costs.values.sumOf { minOf(it, floor) }
        val shown = HashMap<Int, ToolOutcome>()
        for ((opId, outcome) in results.sortedBy { if (red(it.second)) 0 else 1 }) {
            val cost = costs.getValue(opId)
            if (cost <= floor) continue
            if (cost - floor <= left) {
                left -= cost - floor
                continue
            }
            val short = shortForm(outcome, floor + left, budgetTokens, estimator)
            if (short !== outcome) shown[opId] = short
            left = maxOf(0L, left - maxOf(0L, short.tokens - floor))
        }
        return shown
    }

    private fun red(outcome: ToolOutcome): Boolean = outcome.header?.runtime?.status in RED

    /** [outcome] within [roomTokens]: the first line always, then the head up to [HEAD_SHARE] of the room, the tail after. */
    private fun shortForm(outcome: ToolOutcome, roomTokens: Long, budgetTokens: Long, estimator: TokenEstimator): ToolOutcome {
        val lines = outcome.body.lines()
        if (lines.size < 2) return outcome
        val costs = lines.map { estimator.estimate(it + "\n").tokens }
        val pointer = pointer(outcome)
        fun marker(cut: Int) = "… $cut of ${lines.size} lines not shown: this turn's run and verify results passed their output budget of $budgetTokens tokens; $pointer …"
        val room = roomTokens - estimator.estimate(marker(lines.size) + "\n").tokens
        var head = 1
        var used = costs[0]
        val headRoom = (room * HEAD_SHARE).toLong()
        while (head < lines.size && used + costs[head] <= headRoom) used += costs[head++]
        var tail = 0
        while (head + tail < lines.size && used + costs[lines.size - 1 - tail] <= room) used += costs[lines.size - 1 - tail++]
        if (head + tail >= lines.size) return outcome
        val body = (lines.take(head) + marker(lines.size - head - tail) + lines.takeLast(tail)).joinToString("\n")
        return outcome.copy(body = body, tokens = estimator.estimate(body).tokens)
    }

    private fun pointer(outcome: ToolOutcome): String {
        val alias = outcome.resultAlias?.takeIf { Aliases.parse(it) != null }
        return when {
            outcome.header?.tool == "run" && alias != null -> "full output: look(recall, id=$alias)"
            outcome.header?.tool == "verify" -> "the full check output is not recallable; the receipt lines above hold each check's outcome"
            else -> "the full output is not recallable"
        }
    }

    /** What a short form keeps at least, when the budget allows it for every result of the turn. */
    private const val SHORT_FORM_TOKENS: Long = 300

    /** The head carries the status, counts and first failures, so it takes the larger share. */
    private const val HEAD_SHARE: Double = 2.0 / 3.0

    /** Run and verify statuses that report a failure: these results take the remainder first. */
    private val RED: Set<String> = setOf("failed", "timeout", "infra_error", "deadline_exceeded")
}
