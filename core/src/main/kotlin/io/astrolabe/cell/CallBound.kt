package io.astrolabe.cell

import io.astrolabe.provider.Item
import io.astrolabe.provider.ReasoningRef
import io.astrolabe.provider.Response
import io.astrolabe.provider.ToolCall

/**
 * A response cut to the calls the cell will run (D-407). A response that degenerates repeats the same few calls
 * hundreds of times; every call needs a result, so running or answering them all puts the whole batch into `[T]`,
 * where no eviction removes a call/result unit. The cut happens before the output is journaled, appended or
 * dispatched: a dropped call never enters `[T]`, the journal names what was dropped, and the next anchor tells the model.
 *
 * Only a tail is ever cut. Calls name each other by position (`if: green(op:3)`, `evidence: "op:2"`), so a call
 * removed from the middle would re-point every later reference at another call.
 */
internal class BoundedResponse(
    val response: Response,
    val received: Int,
    /** Why the tail was cut; `null` when the response is as received. */
    val cut: String?,
    /** The dropped calls for the journal — the most repeated distinct ones with their counts — since the payload keeps only what stayed. */
    val droppedSummary: String = "",
    /** An `edit` call is among the dropped: the turn's edit batch is not whole, so its runs and verifies wait. */
    val droppedEdit: Boolean = false,
) {
    val kept: Int get() = response.toolCalls.size

    val dropped: Int get() = received - kept

    /** The nudge line of the next anchor; `null` when nothing was dropped. Kept is not run: validation may still refuse a kept call. */
    val line: String?
        get() = cut?.let { "calls: the response held $received tool calls; the first $kept were kept and the other $dropped were dropped unrun — $it" }
}

internal object CallBound {
    /**
     * [response] up to its first call that is past [max] calls or that repeats, by name and arguments, a call the
     * response already made `sameMax - 1` times: that call, every item after it and the reasoning that led into it are
     * removed. Two identical calls stand — a check before and after a change is one — the [sameMax]-th is a loop.
     */
    fun of(response: Response, max: Int, sameMax: Int): BoundedResponse {
        require(max > 0 && sameMax > 1) { "the call limit is positive and a call may at least be made once" }
        val received = response.toolCalls.size
        if (received <= 1) return BoundedResponse(response, received, null)
        val seen = HashMap<Pair<String, String>, Int>()
        val items = ArrayList<Item>(minOf(response.items.size, max * 2))
        val reasoning = ArrayList<Item>()
        var kept = 0
        for (item in response.items) {
            when (item) {
                is ToolCall -> {
                    val same = seen.merge(item.name to item.argsJson, 1, Int::plus)!!
                    val cut = when {
                        same >= sameMax -> "call ${kept + 1} made the same ${item.name} call for the ${ordinal(same)} time in one response; a call repeated within a response is not run again"
                        kept >= max -> "a response runs at most $max calls; send the rest in the next response"
                        else -> null
                    }
                    if (cut != null) {
                        val dropped = response.toolCalls.drop(kept)
                        return BoundedResponse(response.copy(items = items), received, cut, summary(dropped), dropped.any { it.name == EDIT })
                    }
                    items += reasoning
                    reasoning.clear()
                    items += item
                    kept++
                }
                is ReasoningRef -> reasoning += item
                else -> {
                    items += reasoning
                    reasoning.clear()
                    items += item
                }
            }
        }
        return BoundedResponse(response, received, null)
    }

    /** At most [SUMMARY_CALLS] distinct dropped calls, most repeated first, each cut to [SUMMARY_CHARS]. */
    private fun summary(dropped: List<ToolCall>): String {
        val counts = dropped.groupingBy { it.name to it.argsJson }.eachCount().entries.sortedByDescending { it.value }
        val shown = counts.take(SUMMARY_CALLS).joinToString(", ") { (call, n) -> "$n× ${call.first} ${call.second.take(SUMMARY_CHARS)}" }
        return if (counts.size > SUMMARY_CALLS) "$shown, … ${counts.size - SUMMARY_CALLS} more distinct" else shown
    }

    private const val EDIT = "edit"
    private const val SUMMARY_CALLS = 8
    private const val SUMMARY_CHARS = 120

    private fun ordinal(n: Int): String = when (n) {
        2 -> "second"
        3 -> "third"
        else -> "${n}th"
    }
}
