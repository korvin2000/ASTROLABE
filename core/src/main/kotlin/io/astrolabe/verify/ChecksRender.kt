package io.astrolabe.verify

import io.astrolabe.id.CandidateId
import kotlinx.serialization.Serializable

/** Δ of a checker run against its previous result (§8.3). */
@Serializable
public data class Delta(val added: Int, val removed: Int) {
    val changed: Boolean get() = added != 0 || removed != 0
}

/** One rendered check state: signal on delta, state the absolute (L2). */
@Serializable
public sealed interface CheckState {
    @Serializable
    public data class Green(val absolute: String) : CheckState

    @Serializable
    public data class Red(val absolute: String, val count: Int) : CheckState

    @Serializable
    public data class Stale(val reason: String) : CheckState

    @Serializable
    public object NotRun : CheckState

    @Serializable
    public data class Inconclusive(val reason: String) : CheckState

    @Serializable
    public data class Unavailable(val reason: String) : CheckState

    /** Started and killed at the time box (FX-58): distinct from `not run`, which never started. */
    @Serializable
    public data class Timeout(val reason: String) : CheckState
}

/** A line of the `── Checks ──` block: label with scope, Δ when the run changed something, absolute status, stamp and receipt alias. */
@Serializable
public data class CheckLine(
    val label: String,
    val scope: String?,
    val delta: Delta?,
    val state: CheckState,
    val stampHash: String?,
    val receiptAlias: String?,
    /** True when the red state is unchanged since the previous render (compressed form). */
    val unchangedRed: Boolean = false,
)

/**
 * Renders the §8.3 block: `── Checks @d1e7 ── types(touched): Δ +1 −2 · now 3 (#44)` … Never "0 new" alone;
 * unchanged red states stay visible, compressed. At most [maxLines] lines in `[A]`; the rest fold into `+N`.
 */
public object ChecksRender {
    @JvmStatic
    public fun render(stamp: CandidateId?, lines: List<CheckLine>, maxLines: Int = 3): String {
        val head = "── Checks @${stamp?.hash8?.take(4) ?: "none"} ── "
        if (lines.isEmpty()) return head + "no checks registered"
        val items = lines.map(::line)
        val perLine = ArrayList<String>()
        val shown = if (items.size > maxLines) items.take(maxLines - 1) + listOf("+${items.size - maxLines + 1} more: ${lines.drop(maxLines - 1).joinToString(", ") { it.label }}") else items
        val chunks = shown.chunked(2)
        chunks.forEachIndexed { i, chunk ->
            perLine += (if (i == 0) head else " ".repeat(head.length)) + chunk.joinToString(" · ")
        }
        return perLine.joinToString("\n")
    }

    @JvmStatic
    public fun line(l: CheckLine): String {
        val name = l.label + (l.scope?.let { "($it)" } ?: "")
        val sb = StringBuilder(name).append(": ")
        when (val s = l.state) {
            is CheckState.Green -> {
                if (l.delta != null && l.delta.changed) sb.append("Δ +${l.delta.added} −${l.delta.removed} · ")
                sb.append(s.absolute)
            }
            is CheckState.Red -> {
                if (l.unchangedRed) {
                    sb.append("no change · still ${s.count}")
                } else {
                    if (l.delta != null) sb.append("Δ +${l.delta.added} −${l.delta.removed} · ")
                    sb.append("now ${s.count}")
                    if (s.absolute.isNotEmpty()) sb.append(" ").append(s.absolute)
                }
            }
            is CheckState.Stale -> sb.append("stale (").append(s.reason).append(')')
            CheckState.NotRun -> sb.append("not run")
            is CheckState.Inconclusive -> sb.append("inconclusive (").append(s.reason).append(')')
            is CheckState.Unavailable -> sb.append("unavailable (").append(s.reason).append(')')
            is CheckState.Timeout -> sb.append("timeout (").append(s.reason).append(')')
        }
        l.stampHash?.let { sb.append(" @").append(it.take(4)) }
        l.receiptAlias?.let { sb.append(" (").append(it).append(')') }
        return sb.toString()
    }
}
