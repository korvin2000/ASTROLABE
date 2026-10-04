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

/** One line of a direct anchor's `── Runs` block (§5.10-D); an [essential] line — a live handle, a red receipt — survives reduction. */
public data class RunLine(val text: String, val essential: Boolean)

/** The last receipt of one registered check with a command, as `── Runs` lists it (§5.10-D). */
public data class RunReceipt @JvmOverloads constructor(
    /** The receipt's alias (`#42`). */
    val alias: String,
    val argv: List<String>,
    /** `green`, `red: <n> failed`, `timeout`, `unavailable` or `inconclusive`. */
    val result: String,
    val red: Boolean,
    /** The receipt's stamp, four hex characters. */
    val stamp: String,
    val stale: Boolean = false,
    /** P8.C.2: a red check that is not required, recorded by the runtime from its receipt. */
    val knownRed: Boolean = false,
)

/** The lines of `── Runs` (§5.10-D): a pure function of the run handles and the check receipts; no clock, no elapsed time. */
public object RunsRender {
    /** `<handle> <command> → running`, with ` · ready (<line or port>)` once a readiness condition was met (T8). */
    @JvmStatic
    public fun live(handle: String, argv: List<String>, ready: String?): RunLine =
        RunLine("$handle ${command(argv)} → running" + (ready?.let { " · ready ($it)" } ?: ""), essential = true)

    /** Live handles in handle order, then receipts — red first, then the others, each group by the higher alias first. */
    @JvmStatic
    public fun lines(live: List<RunLine>, receipts: List<RunReceipt>): List<RunLine> =
        live + receipts.sortedWith(compareBy<RunReceipt> { !it.red }.thenByDescending { aliasNumber(it.alias) }).map { r ->
            RunLine(
                "${r.alias} ${command(r.argv)} → ${r.result} @${r.stamp}" + (if (r.stale) " (stale)" else "") + (if (r.knownRed) " · known red, not required" else ""),
                essential = r.red,
            )
        }

    /** The canonical argv joined by spaces, cut at 60 characters with `…`. */
    @JvmStatic
    public fun command(argv: List<String>): String {
        val text = argv.joinToString(" ")
        return if (text.length <= COMMAND_MAX_CHARS) text else text.take(COMMAND_MAX_CHARS - 1) + "…"
    }

    private fun aliasNumber(alias: String): Long = alias.filter { it.isDigit() }.toLongOrNull() ?: -1

    private const val COMMAND_MAX_CHARS: Int = 60
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
 * zoom, focus notes, the turn's enabled tools, gauge, nudges, fired trips — and each carries its own cap.
 * The enabled line is here, not in `[S]`, so a turn's mask never rewrites the cached prefix (invariant 12).
 *
 * **Caps are enforced here even though the producers cap too** (except the contract digest, which
 * [io.astrolabe.register.ContractDigest] bounds or refuses with a typed error), because a caller may hand over an
 * unbounded string and a silently oversized anchor is a cost bug that hides in every turn. Truncation
 * drops whole trailing lines and says how many, and every reduction is named in
 * [AnchorRender.reductions].
 *
 * **Reduction order is fixed** so the render is a pure function of its inputs: focus notes, then the
 * focus zoom, then the Touched ledger down to three lines, then STATE at a halved cap. The contract
 * digest, the checks, the enabled tools, the gauge, the nudges and fired trips are never reduced — they are the lines a
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
        enabled: String? = null,
    ): AnchorRender {
        val reductions = ArrayList<String>()
        var registerText = cap(estimator, register, defaults.registerCapTokens, "STATE", reductions)
        val worksetText = cap(estimator, workset, WORKSET_CAP_TOKENS, "Workset", reductions)
        var touchedLines = touched.takeLast(defaults.touchedInAnchor)
        if (touchedLines.size < touched.size) reductions += "Touched: showed the last ${touchedLines.size} of ${touched.size}"
        var zoom = focusZoom?.let { cap(estimator, it, defaults.focusZoomMaxTokens, "focus zoom", reductions) }
        var notes = focusNotes?.let { cap(estimator, it, defaults.focusNotesMaxTokens, "focus notes", reductions) }
        val nudgeLines = nudges.take(MAX_NUDGES)
        if (nudgeLines.size < nudges.size) reductions += "nudges: showed ${nudgeLines.size} of ${nudges.size}"

        // D-270: the digest is mandatory and already bounded by ContractDigest (typed DigestCapacity); never line-capped here.
        fun compose() = compose(digest, registerText, worksetText, touchedLines, checks, zoom, notes, enabled, gauge, nudgeLines, firedTrips)

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
        enabled: String?,
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
        appendBlock(out, enabled)
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

    /**
     * The direct protocol's `[A]` (rendered turn §5.10-D, A-D.7 A1–A2): the harness journal in place of STATE. Blocks in
     * order — contract digest, Workset, Touched (the last three), Checks, Runs, Notes, the turn's enabled tools, gauge,
     * nudges, the repair helper's diagnosis lines — an empty block omitted. No focus notes, no focus zoom, no fired trips.
     *
     * Over [Defaults.directAnchorTargetTokens] the render reduces in a fixed order — Runs to live handles and red
     * receipts, then Notes to half their cap — and names each step; still over, it is sent as it is and the reductions
     * say by how much. [AnchorRender.overBudget] keeps its meaning against [Defaults.anchorMaxTokens].
     */
    @JvmStatic
    @JvmOverloads
    public fun renderDirect(
        estimator: TokenEstimator,
        digest: String,
        register: io.astrolabe.register.Register,
        workset: String,
        touched: List<Touched> = emptyList(),
        checks: String = "",
        runs: List<RunLine> = emptyList(),
        gauge: String? = null,
        nudges: List<String> = emptyList(),
        diagnoses: List<String> = emptyList(),
        defaults: Defaults = Defaults(),
        enabled: String? = null,
    ): AnchorRender {
        val reductions = ArrayList<String>()
        val worksetText = cap(estimator, workset, WORKSET_CAP_TOKENS, "Workset", reductions)
        val touchedLines = touched.takeLast(MIN_TOUCHED)
        if (touchedLines.size < touched.size) reductions += "Touched: showed the last ${touchedLines.size} of ${touched.size}"
        val nudgeLines = nudges.take(MAX_NUDGES)
        if (nudgeLines.size < nudges.size) reductions += "nudges: showed ${nudgeLines.size} of ${nudges.size}"
        val notes = io.astrolabe.register.NotesRender.anchorLines(register)
        val target = defaults.directAnchorTargetTokens

        var runLines = runs
        var notesCap = defaults.directNotesMaxTokens
        fun compose(): String {
            val out = StringBuilder()
            appendBlock(out, digest)
            if (worksetText.isNotBlank()) appendBlock(out, "── Workset  $worksetText")
            if (touchedLines.isNotEmpty()) appendBlock(out, "── Touched  " + touchedLines.joinToString(INDENT) { it.render() })
            appendBlock(out, checks)
            runsBlock(runLines, defaults.directRunsMaxLines)?.let { appendBlock(out, it) }
            notesBlock(estimator, register.version, notes, notesCap)?.let { appendBlock(out, it) }
            appendBlock(out, enabled)
            appendBlock(out, gauge)
            nudgeLines.forEach { appendBlock(out, it) }
            diagnoses.forEach { appendBlock(out, it) }
            return out.toString()
        }

        var text = compose()
        if (estimator.estimate(text).tokens > target && runLines.any { !it.essential }) {
            runLines = runLines.filter { it.essential }
            reductions += "Runs: live handles and red receipts only, the anchor was over $target tokens"
            text = compose()
        }
        if (estimator.estimate(text).tokens > target && notes.isNotEmpty()) {
            notesCap = defaults.directNotesMaxTokens / 2
            reductions += "Notes: capped at $notesCap tokens, the anchor was over $target tokens"
            text = compose()
        }
        val tokens = estimator.estimate(text).tokens
        if (tokens > target) reductions += "over the $target-token target: $tokens tokens"
        return AnchorRender(text, tokens, tokens > defaults.anchorMaxTokens, reductions)
    }

    /** `── Runs`: at most [maxLines] lines; the lines beyond collapse into a last `+<n> more`. */
    private fun runsBlock(lines: List<RunLine>, maxLines: Int): String? {
        if (lines.isEmpty()) return null
        val max = maxLines.coerceAtLeast(1)
        val shown = if (lines.size <= max) lines.map { it.text } else lines.take(max - 1).map { it.text } + "+${lines.size - (max - 1)} more"
        return "── Runs     " + shown.joinToString(INDENT)
    }

    /**
     * `── Notes (STATE v<N>)`: whole lines while they fit [capTokens]; the ids that did not fit are named on one last line
     * outside the cap, so every note stays addressable (§5.10-D).
     */
    private fun notesBlock(estimator: TokenEstimator, version: Int, notes: List<io.astrolabe.register.NoteLine>, capTokens: Int): String? {
        if (notes.isEmpty()) return null
        val shown = ArrayList<String>()
        for (note in notes) {
            val next = (shown + note.toString()).joinToString("\n")
            if (estimator.estimate(next).tokens > capTokens) break
            shown += note.toString()
        }
        val rest = notes.drop(shown.size).map { it.id }
        val overflow = if (rest.isEmpty()) emptyList() else listOf(
            "… +${rest.size} not shown: " + rest.take(MAX_HIDDEN_IDS).joinToString(" ") + (if (rest.size > MAX_HIDDEN_IDS) " …" else "") + " — look(recall, id=notes)",
        )
        return "── Notes (STATE v$version)" + INDENT + (shown + overflow).joinToString(INDENT)
    }

    /** `[A]` Workset line: `KNOWN … NOT SEEN …` is a hint; the registry holds the authoritative coverage. */
    private const val WORKSET_CAP_TOKENS: Int = 60

    /** The continuation indent of a multi-line `[A]` block. */
    private const val INDENT: String = "\n            "

    /** §5.10-D: the notes line names at most this many ids it did not show. */
    private const val MAX_HIDDEN_IDS: Int = 30

    /** §5.1 `[A]`: at most four nudges per turn (D-372). */
    private const val MAX_NUDGES: Int = 4

    /** What the Touched ledger keeps when the anchor is over budget. */
    private const val MIN_TOUCHED: Int = 3
}
