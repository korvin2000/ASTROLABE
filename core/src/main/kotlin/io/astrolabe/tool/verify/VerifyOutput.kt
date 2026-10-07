package io.astrolabe.tool.verify

import io.astrolabe.provider.TokenEstimator

/**
 * The overall cap on one `verify` call's output (P8.C.17, D-424): its checks and their retries together. Within the cap
 * the body is the `── Checks ──` block and every check's view, byte for byte. Past it every view keeps its first line,
 * the failed checks take their first lines next, line by line in turn, and the passed checks follow with what is left;
 * each cut view ends with one line naming its receipt alias, whose `look(recall)` serves the whole stored output.
 * Only the shown body changes: receipts, their raw logs and the outcome stay whole. A pure function of its inputs.
 */
internal object VerifyOutput {
    /** One check's view of a call: [failed] when its receipt did not pass; [alias] its receipt alias, when it has one. */
    data class View(val checkId: String, val text: String, val failed: Boolean, val alias: String?)

    fun cap(checks: String, views: List<View>, budgetTokens: Long, estimator: TokenEstimator): String {
        val whole = checks + "\n" + views.joinToString("\n") { it.text }
        if (views.isEmpty() || estimator.estimate(whole).tokens <= budgetTokens) return whole
        val lines = views.map { it.text.lines() }
        val kept = IntArray(views.size) { 1 }
        fun marker(i: Int): String {
            val pointer = views[i].alias?.let { "full output: look(recall, id=$it)" } ?: "its receipt keeps the full output"
            return "  … ${lines[i].size - kept[i]} more lines of ${views[i].checkId} not shown: this verify passed its output budget of $budgetTokens tokens; $pointer"
        }
        fun assemble(): String = checks + "\n" + views.indices.joinToString("\n") { i ->
            (lines[i].take(kept[i]) + listOfNotNull(marker(i).takeIf { kept[i] < lines[i].size })).joinToString("\n")
        }
        var left = budgetTokens - estimator.estimate(assemble()).tokens
        // Failed checks first, one line each in turn, so every failure shows its first lines, not the tail of the last.
        for (group in listOf(views.indices.filter { views[it].failed }, views.indices.filter { !views[it].failed })) {
            var open = group.filter { kept[it] < lines[it].size }
            while (open.isNotEmpty() && left > 0) {
                val next = ArrayList<Int>()
                for (i in open) {
                    val cost = estimator.estimate(lines[i][kept[i]] + "\n").tokens
                    if (cost > left) continue
                    left -= cost
                    kept[i]++
                    if (kept[i] < lines[i].size) next += i
                }
                open = next
            }
        }
        // Per-line estimates may round below the whole: shrink the longest view until the assembled text fits.
        var text = assemble()
        while (estimator.estimate(text).tokens > budgetTokens) {
            val longest = views.indices.filter { kept[it] > 1 }.maxByOrNull { kept[it] } ?: break
            kept[longest]--
            text = assemble()
        }
        return text
    }
}
