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
 *
 * A failing `verify` is cut like any red result (P8.C.17, D-424): its own output is already capped per call with every
 * failed check's first lines kept, and each check's whole output is recallable by its receipt alias.
 */
internal object TurnOutput {
    /**
     * The short forms of [results] (op id → outcome, in emitted order) whose bodies together pass [budgetTokens];
     * an op absent from the map is shown as executed. Every cut result keeps at least a floor, the turn's failing
     * results get the remainder first and the rest follow in emitted order. A pure function of the outcomes.
     */
    fun fit(results: List<Pair<Int, ToolOutcome>>, budgetTokens: Long, estimator: TokenEstimator): Map<Int, ToolOutcome> {
        if (results.isEmpty()) return emptyMap()
        val costs = results.associate { (opId, outcome) -> opId to estimator.estimate(outcome.body).tokens }
        if (costs.values.sum() <= budgetTokens) return emptyMap()
        val floor = minOf(SHORT_FORM_TOKENS, budgetTokens / results.size)
        var left = budgetTokens - results.sumOf { minOf(costs.getValue(it.first), floor) }
        val shown = HashMap<Int, ToolOutcome>()
        for ((opId, outcome) in results.sortedBy { if (red(it.second)) 0 else 1 }) {
            val cost = costs.getValue(opId)
            if (cost <= floor) continue
            if (cost - floor <= left) {
                left -= cost - floor
                continue
            }
            val short = shortForm(outcome, floor + left, budgetTokens, estimator)
            shown[opId] = short
            left = maxOf(0L, left - maxOf(0L, short.tokens - floor))
        }
        return shown
    }

    /**
     * A `verify` whose receipts are not all green for the final tree. Its header status reads `ok` for a `check` over
     * failed receipts, so the executor's `green` — derived from the receipts — decides, and the header stays untouched.
     */
    private fun failingVerify(outcome: ToolOutcome): Boolean = outcome.header?.tool == "verify" && !outcome.green

    private fun red(outcome: ToolOutcome): Boolean = outcome.header?.runtime?.status in RED || failingVerify(outcome)

    /**
     * [outcome] measured as one text within [roomTokens]: the first line, then the head up to [HEAD_SHARE] of the room
     * and the tail, shrunk line by line until the assembled body fits (per-line estimates may round below the whole).
     * When not even the first line fits beside the marker, the first line is cut beside a compact marker, then the
     * marker stands alone, then nothing is shown and the header alone carries the status.
     */
    private fun shortForm(outcome: ToolOutcome, roomTokens: Long, budgetTokens: Long, estimator: TokenEstimator): ToolOutcome {
        fun fits(text: String) = estimator.estimate(text).tokens <= roomTokens
        fun shown(body: String) = outcome.copy(body = body, tokens = estimator.estimate(body).tokens)
        val lines = outcome.body.lines()
        val pointer = pointer(outcome)
        if (lines.size >= 2) {
            fun marker(cut: Int) = "… $cut of ${lines.size} lines not shown: this turn's run and verify results passed their output budget of $budgetTokens tokens; $pointer …"
            fun assemble(head: Int, tail: Int) = (lines.take(head) + marker(lines.size - head - tail) + lines.takeLast(tail)).joinToString("\n")
            val costs = lines.map { estimator.estimate(it + "\n").tokens }
            val room = roomTokens - estimator.estimate(marker(lines.size) + "\n").tokens
            var head = 1
            var used = costs[0]
            val headRoom = (room * HEAD_SHARE).toLong()
            while (head < lines.size - 1 && used + costs[head] <= headRoom) used += costs[head++]
            var tail = 0
            while (head + tail < lines.size - 1 && used + costs[lines.size - 1 - tail] <= room) used += costs[lines.size - 1 - tail++]
            while (!fits(assemble(head, tail)) && (tail > 0 || head > 1)) if (tail > 0) tail-- else head--
            assemble(head, tail).let { if (fits(it)) return shown(it) }
        }
        val compact = "… cut by this turn's run and verify output budget of $budgetTokens tokens; $pointer"
        if (!fits(compact)) return shown("")
        val first = lines.first()
        var low = 0
        var high = first.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (fits(first.take(middle) + " …\n" + compact)) low = middle else high = middle - 1
        }
        if (low > 0 && first[low - 1].isHighSurrogate()) low--
        return shown(if (low == 0) compact else first.take(low) + " …\n" + compact)
    }

    private fun pointer(outcome: ToolOutcome): String {
        val alias = outcome.resultAlias?.takeIf { Aliases.parse(it) != null }
        return when {
            outcome.header?.tool == "run" && alias != null -> "full output: look(recall, id=$alias)"
            outcome.header?.tool == "verify" -> "each check's full output: look(recall, id=<its receipt alias in the lines above>)"
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
