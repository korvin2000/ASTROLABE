package io.astrolabe.cell

import io.astrolabe.Defaults
import io.astrolabe.id.FileVersion
import io.astrolabe.provider.Message
import io.astrolabe.provider.Segment
import io.astrolabe.provider.SegmentKind
import io.astrolabe.provider.TokenEstimator
import io.astrolabe.workspace.VersionChange
import io.astrolabe.provider.Role as ItemRole

/** How a path moved in this cell's Touched ledger. */
public enum class TouchKind(public val letter: String) { Added("A"), Modified("M"), Deleted("D") }

/**
 * One line of the `[A]` Touched ledger (§5.10): `M src/handlers/user.py (+2 −1) @c02e→d1e7 "accept ctx" #41`.
 *
 * The cell loop appends one per applied mutation; the anchor shows the most recent
 * [Defaults.touchedInAnchor]. Versions are the registry's, never the model's.
 */
public data class Touched @JvmOverloads constructor(
    val path: String,
    val kind: TouchKind,
    val added: Int? = null,
    val removed: Int? = null,
    val from: FileVersion? = null,
    val to: FileVersion? = null,
    val note: String? = null,
    val alias: String? = null,
) {
    init {
        require(path.isNotBlank()) { "a touched entry needs a path" }
    }

    public fun render(): String {
        val sb = StringBuilder(kind.letter).append(' ').append(path)
        if (added != null || removed != null) sb.append(" (+").append(added ?: 0).append(" −").append(removed ?: 0).append(')')
        if (from != null || to != null) sb.append(" @").append(short(from)).append('→').append(short(to))
        note?.let { sb.append(" \"").append(it).append('"') }
        alias?.let { sb.append(' ').append(it) }
        return sb.toString()
    }

    private fun short(version: FileVersion?): String = version?.digest?.hash8?.take(4) ?: "none"

    public companion object {
        /** The ledger entry for a registry transition; [added]/[removed] come from the edit that caused it. */
        @JvmStatic
        @JvmOverloads
        public fun of(
            change: VersionChange,
            added: Int? = null,
            removed: Int? = null,
            note: String? = null,
            alias: String? = null,
        ): Touched = Touched(
            path = change.path,
            kind = when {
                change.to == null -> TouchKind.Deleted
                change.from == null -> TouchKind.Added
                else -> TouchKind.Modified
            },
            added = added,
            removed = removed,
            from = change.from,
            to = change.to,
            note = note,
            alias = alias,
        )
    }
}

/** What [Anchor.render] produced, with the `[A]` size metric §6.8 treats as first class. */
public data class AnchorRender(
    val text: String,
    val tokens: Long,
    /** True when the anchor still exceeds its cap after every reduction below — reported, never hidden. */
    val overBudget: Boolean,
    /** Every reduction this render applied, in the order it applied them. */
    val reductions: List<String>,
) {
    /** `[A]` is the volatile tail: rebuilt every turn, never persisted and never a cache breakpoint. */
    public fun segment(): Segment =
        Segment(SegmentKind.A, listOf(Message.text(ItemRole.User, text)), breakpoint = false)
}

/**
 * Renders `[A]` (§5.1, §5.10): the volatile tail the harness rebuilds every turn and the model never
 * writes. Blocks appear in a fixed order — contract digest, STATE, Workset, Touched, Checks, focus
 * zoom, focus notes, gauge, nudges, fired trips — and each carries its own cap.
 *
 * **Caps are enforced here even though the producers cap too**, because a caller may hand over an
 * unbounded string and a silently oversized anchor is a cost bug that hides in every turn. Truncation
 * drops whole trailing lines and says how many, and every reduction is named in
 * [AnchorRender.reductions].
 *
 * **Reduction order is fixed** so the render is a pure function of its inputs: focus notes, then the
 * focus zoom, then the Touched ledger down to three lines, then STATE at a halved cap. The contract
 * digest, the checks, the gauge, the nudges and fired trips are never reduced — they are the lines a
 * cell must not miss. If the anchor is still over its cap, that is reported rather than papered over.
 */
public object Anchor {

    @JvmStatic
    @JvmOverloads
    public fun render(
        estimator: TokenEstimator,
        digest: String,
        register: String,
        workset: String,
        touched: List<Touched> = emptyList(),
        checks: String = "",
        focusZoom: String? = null,
        focusNotes: String? = null,
        gauge: String? = null,
        nudges: List<String> = emptyList(),
        firedTrips: List<String> = emptyList(),
        defaults: Defaults = Defaults(),
    ): AnchorRender {
        val reductions = ArrayList<String>()
        var digestText = cap(estimator, digest, defaults.digestCapTokens, "contract digest", reductions)
        var registerText = cap(estimator, register, defaults.registerCapTokens, "STATE", reductions)
        val worksetText = cap(estimator, workset, WORKSET_CAP_TOKENS, "Workset", reductions)
        var touchedLines = touched.takeLast(defaults.touchedInAnchor)
        if (touchedLines.size < touched.size) reductions += "Touched: showed the last ${touchedLines.size} of ${touched.size}"
        var zoom = focusZoom?.let { cap(estimator, it, defaults.focusZoomMaxTokens, "focus zoom", reductions) }
        var notes = focusNotes?.let { cap(estimator, it, defaults.focusNotesMaxTokens, "focus notes", reductions) }
        val nudgeLines = nudges.take(MAX_NUDGES)
        if (nudgeLines.size < nudges.size) reductions += "nudges: showed ${nudgeLines.size} of ${nudges.size}"

        fun compose() = compose(digestText, registerText, worksetText, touchedLines, checks, zoom, notes, gauge, nudgeLines, firedTrips)

        var text = compose()
        // One pass per lever, in the declared order; each is recorded even when it does not suffice.
        if (estimator.estimate(text).tokens > defaults.anchorMaxTokens && notes != null) {
            notes = null
            reductions += "focus notes: dropped, the anchor was over ${defaults.anchorMaxTokens} tokens"
            text = compose()
        }
        if (estimator.estimate(text).tokens > defaults.anchorMaxTokens && zoom != null) {
            zoom = null
            reductions += "focus zoom: dropped, the anchor was over ${defaults.anchorMaxTokens} tokens"
            text = compose()
        }
        if (estimator.estimate(text).tokens > defaults.anchorMaxTokens && touchedLines.size > MIN_TOUCHED) {
            reductions += "Touched: reduced to $MIN_TOUCHED of ${touchedLines.size}, the anchor was over ${defaults.anchorMaxTokens} tokens"
            touchedLines = touchedLines.takeLast(MIN_TOUCHED)
            text = compose()
        }
        if (estimator.estimate(text).tokens > defaults.anchorMaxTokens) {
            registerText = cap(estimator, registerText, defaults.registerCapTokens / 2, "STATE", reductions)
            text = compose()
        }
        // The digest is the recitation: it shrinks only when nothing else is left, and never vanishes.
        if (estimator.estimate(text).tokens > defaults.anchorMaxTokens) {
            digestText = cap(estimator, digestText, defaults.digestCapTokens / 2, "contract digest", reductions)
            text = compose()
        }

        val tokens = estimator.estimate(text).tokens
        return AnchorRender(text, tokens, tokens > defaults.anchorMaxTokens, reductions)
    }

    private fun compose(
        digest: String,
        register: String,
        workset: String,
        touched: List<Touched>,
        checks: String,
        focusZoom: String?,
        focusNotes: String?,
        gauge: String?,
        nudges: List<String>,
        firedTrips: List<String>,
    ): String {
        val out = StringBuilder()
        appendBlock(out, digest)
        appendBlock(out, register)
        if (workset.isNotBlank()) appendBlock(out, "── Workset  $workset")
        if (touched.isNotEmpty()) appendBlock(out, "── Touched  " + touched.joinToString("\n            ") { it.render() })
        appendBlock(out, checks)
        focusZoom?.let { appendBlock(out, "── Focus    ${it.replace("\n", "\n            ")}") }
        focusNotes?.let { appendBlock(out, "── Notes    ${it.replace("\n", "\n            ")}") }
        appendBlock(out, gauge)
        nudges.forEach { appendBlock(out, it) }
        firedTrips.forEach { appendBlock(out, it) }
        return out.toString()
    }

    private fun appendBlock(out: StringBuilder, block: String?) {
        if (block.isNullOrBlank()) return
        out.append(block.trimEnd('\n')).append('\n')
    }

    /** Whole trailing lines are dropped and counted; a block never ends mid-line. */
    private fun cap(estimator: TokenEstimator, text: String, capTokens: Int, label: String, reductions: MutableList<String>): String {
        if (text.isBlank() || estimator.estimate(text).tokens <= capTokens) return text
        val lines = text.trimEnd('\n').split('\n')
        var keep = lines.size
        var out = text
        while (keep > 1) {
            keep--
            out = (lines.take(keep) + "… +${lines.size - keep} lines").joinToString("\n")
            if (estimator.estimate(out).tokens <= capTokens) break
        }
        reductions += "$label: capped at $capTokens tokens, ${lines.size - keep} lines dropped"
        return out
    }

    /** `[A]` Workset line: `KNOWN … NOT SEEN …` is a hint; the registry holds the authoritative coverage. */
    private const val WORKSET_CAP_TOKENS: Int = 60

    /** §5.1 `[A]`: at most two nudges per turn. */
    private const val MAX_NUDGES: Int = 2

    /** What the Touched ledger keeps when the anchor is over budget. */
    private const val MIN_TOUCHED: Int = 3
}
