package io.astrolabe.tool.edit

import io.astrolabe.workspace.LineRange

/** An anchor located in a file: character offsets in the decoded text and the 1-based lines it spans. */
public data class Located(val start: Int, val end: Int, val lines: LineRange, val exact: Boolean)

/** One of the three nearest lines offered when an anchor matches nowhere (§5.4 error policy). */
public data class Candidate(val line: Int, val text: String)

/** The outcome of locating one anchor (D-33). */
public sealed interface Location {
    public data class One(val span: Located) : Location

    /** Zero matches: the nearest lines, so the retry needs no re-read. */
    public data class None(val candidates: List<Candidate>) : Location

    /** More than one match after `near`: every site, never a guess. */
    public data class Many(val sites: List<Located>) : Location
}

/**
 * Anchor location (§9.1, D-33): exact matching first; then a tightly specified whitespace normalization —
 * line endings normalized, trailing blanks ignored, runs of blanks collapsed, blank-line runs collapsed —
 * with an explicit mapping back to spans of the original text; `near` (a literal within the twenty lines
 * before the match, or inside it) selects among candidates; anything but exactly one match is refused with
 * the sites or the three nearest lines.
 */
public object Anchors {
    public const val NEAR_WINDOW_LINES: Int = 20

    @JvmStatic
    public fun locate(text: String, anchor: String, near: String? = null): Location {
        require(anchor.isNotEmpty()) { "an anchor may not be empty" }
        val lineStarts = lineStarts(text)
        var sites = exact(text, anchor).map { (s, e) -> Located(s, e, linesOf(lineStarts, s, e), exact = true) }
        if (sites.isEmpty()) sites = normalized(text, anchor).map { (s, e) -> Located(s, e, linesOf(lineStarts, s, e), exact = false) }
        if (sites.size > 1 && near != null) {
            val lines = text.lines()
            val selected = sites.filter { site ->
                val from = maxOf(1, site.lines.from - NEAR_WINDOW_LINES)
                (from..site.lines.to).any { lines.getOrNull(it - 1)?.contains(near) == true }
            }
            // A `near` that selects nothing is a wrong hint, not a missing anchor: every site stays listed.
            if (selected.isNotEmpty()) sites = selected
        }
        return when (sites.size) {
            1 -> Location.One(sites.single())
            0 -> Location.None(nearest(text, anchor))
            else -> Location.Many(sites)
        }
    }

    /** Character spans of every exact occurrence, non-overlapping, left to right. */
    private fun exact(text: String, anchor: String): List<Pair<Int, Int>> {
        val found = ArrayList<Pair<Int, Int>>()
        var from = 0
        while (true) {
            val at = text.indexOf(anchor, from)
            if (at < 0) break
            found += at to at + anchor.length
            from = at + anchor.length
        }
        return found
    }

    /** Occurrences under whitespace normalization, mapped back to original spans. */
    private fun normalized(text: String, anchor: String): List<Pair<Int, Int>> {
        val normalizedAnchor = normalize(anchor).text.trim()
        if (normalizedAnchor.isEmpty()) return emptyList()
        val file = normalize(text)
        return exact(file.text, normalizedAnchor).map { (s, e) -> file.origin[s] to file.origin[e - 1] + 1 }
    }

    private class Normalized(val text: String, val origin: IntArray)

    /**
     * `\r\n` → `\n`; trailing blanks dropped; a run of blanks → one space; a run of blank lines → one; each
     * normalized character remembers the original index it came from (the first of a collapsed run).
     */
    private fun normalize(text: String): Normalized {
        val out = StringBuilder(text.length)
        val origin = IntArray(text.length + 1)
        var i = 0
        var lineStart = 0
        var pendingBlanks = -1 // original index of the first blank in the current run, or -1
        var blankLines = 0
        var lineHasContent = false
        fun flushBlanks() {
            if (pendingBlanks >= 0 && lineHasContent) {
                origin[out.length] = pendingBlanks
                out.append(' ')
            }
            pendingBlanks = -1
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' && i + 1 < text.length && text[i + 1] == '\n' -> {
                    i++
                    continue
                }
                c == '\n' || c == '\r' -> {
                    pendingBlanks = -1
                    if (lineHasContent) {
                        origin[out.length] = i
                        out.append('\n')
                        blankLines = 0
                    } else if (blankLines == 0 && out.isNotEmpty()) {
                        origin[out.length] = i
                        out.append('\n')
                        blankLines = 1
                    }
                    lineHasContent = false
                    lineStart = i + 1
                }
                c == ' ' || c == '\t' -> if (pendingBlanks < 0) pendingBlanks = i
                else -> {
                    flushBlanks()
                    origin[out.length] = i
                    out.append(c)
                    lineHasContent = true
                }
            }
            i++
        }
        return Normalized(out.toString(), origin)
    }

    /** The three lines most similar to the anchor's first non-blank line, by shared identifier tokens. */
    private fun nearest(text: String, anchor: String): List<Candidate> {
        val probe = anchor.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return emptyList()
        val probeTokens = tokens(probe)
        if (probeTokens.isEmpty()) return emptyList()
        return text.lines().mapIndexedNotNull { index, line ->
            val shared = tokens(line).count { it in probeTokens }
            if (shared == 0) null else Triple(index + 1, line, shared)
        }.sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(3)
            .map { Candidate(it.first, it.second.trim().take(120)) }
    }

    private fun tokens(line: String): Set<String> = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(line).map { it.value }.toSet()

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun linesOf(lineStarts: IntArray, start: Int, end: Int): LineRange {
        val from = lineAt(lineStarts, start)
        val last = if (end > start) end - 1 else start
        return LineRange(from, maxOf(from, lineAt(lineStarts, last)))
    }

    private fun lineAt(lineStarts: IntArray, offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low + 1
    }
}

/** A bounded line diff for `diff since expect` (§9.1): what moved between the version shown and the bytes now. */
public object LineDiff {
    public const val MAX_LINES: Int = 4_000

    /** Unified-style hunks with [context] lines, at most [maxOutputLines] lines; `null` when either side is too large. */
    @JvmStatic
    public fun unified(old: String, new: String, context: Int = 2, maxOutputLines: Int = 80): String? {
        val a = old.lines()
        val b = new.lines()
        if (a.size > MAX_LINES || b.size > MAX_LINES) return null
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val ops = ArrayList<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops += ' ' to a[i]; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops += '-' to a[i]; i++ }
                else -> { ops += '+' to b[j]; j++ }
            }
        }
        while (i < a.size) { ops += '-' to a[i]; i++ }
        while (j < b.size) { ops += '+' to b[j]; j++ }
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { index, op -> if (op.first != ' ') for (k in maxOf(0, index - context)..minOf(ops.size - 1, index + context)) keep[k] = true }
        val out = ArrayList<String>()
        var gap = false
        for ((index, op) in ops.withIndex()) {
            if (!keep[index]) { gap = true; continue }
            if (gap && out.isNotEmpty()) out += "…"
            gap = false
            out += "${op.first}${op.second}"
        }
        if (out.isEmpty()) return "(no line differs; whitespace or encoding changed)"
        return if (out.size > maxOutputLines) (out.take(maxOutputLines) + "… ${out.size - maxOutputLines} more diff lines").joinToString("\n") else out.joinToString("\n")
    }
}
