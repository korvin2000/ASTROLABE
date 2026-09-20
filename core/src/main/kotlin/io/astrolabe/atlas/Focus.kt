package io.astrolabe.atlas

import io.astrolabe.Defaults
import io.astrolabe.budget.HeuristicEstimator

/**
 * Which zoom of the atlas to render (§7.1, §7.7 "`Focus` selects the zoom").
 *
 * The root render never lists more than one level, so a monorepo's root stays one screen: top-level
 * directories with their totals, then the files that sit at the root.
 */
public sealed interface Focus {
    /** Top-level directories with sizes, then top-level files. */
    public data object Root : Focus

    /** The children of [path]: subdirectories with totals, files with export counts and names. */
    public data class Dir(val path: String) : Focus {
        init {
            require('\\' !in path) { "focus paths use forward slashes; got '$path'" }
        }
    }

    /** One file's tier-0 outline. */
    public data class File(val path: String) : Focus {
        init {
            require(path.isNotBlank()) { "a file focus needs a path" }
            require('\\' !in path) { "focus paths use forward slashes; got '$path'" }
        }
    }

    public companion object {
        /**
         * Renders [focus] over [atlas] as plain text, bounded by [maxTokens]
         * ([Defaults.focusZoomMaxTokens], §5.1 `[A]` focus atlas zoom ≤ 300).
         *
         * The result is a pure function of the atlas and the focus: no timestamps, no counters, no
         * absolute paths, `\n` line endings and forward slashes on every platform, so the same
         * inputs render the same bytes. Truncation drops whole trailing lines and marks what it
         * dropped with `… +N`.
         */
        @JvmStatic
        @JvmOverloads
        public fun render(
            atlas: Atlas,
            focus: Focus,
            maxTokens: Int = Defaults().focusZoomMaxTokens,
        ): String {
            val lines = when (focus) {
                is Root -> renderRoot(atlas)
                is Dir -> renderDir(atlas, normalizeRelative(focus.path))
                is File -> renderFile(atlas, normalizeRelative(focus.path))
            }
            return capLines(lines, maxTokens)
        }

        private fun renderRoot(atlas: Atlas): List<String> {
            val lines = ArrayList<String>()
            lines += "atlas /: ${atlas.rows.size} files · ${formatBytes(atlas.bytes)}"
            for (directory in atlas.subdirectories("")) {
                lines += "  $directory/ ${atlas.filesUnder(directory)} files · " +
                    formatBytes(atlas.bytesUnder(directory))
            }
            for (row in atlas.children("")) {
                lines += "  ${row.path} ${formatBytes(row.bytes)}"
            }
            for (entry in atlas.collapsed) {
                lines += "  ${entry.path} ${entry.files} files · ${formatBytes(entry.bytes)} " +
                    "(${entry.reason.name.lowercase()}, collapsed)"
            }
            return lines
        }

        private fun renderDir(atlas: Atlas, dir: String): List<String> {
            val subdirectories = atlas.subdirectories(dir)
            val children = atlas.children(dir)
            if (subdirectories.isEmpty() && children.isEmpty()) {
                return listOf("atlas ${displayDir(dir)}: not in the atlas")
            }
            val lines = ArrayList<String>()
            lines += "atlas ${displayDir(dir)}: ${atlas.filesUnder(dir)} files · ${formatBytes(atlas.bytesUnder(dir))}"
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            for (name in subdirectories) {
                val child = "$prefix$name"
                lines += "  $name/ ${atlas.filesUnder(child)} files · ${formatBytes(atlas.bytesUnder(child))}"
            }
            for (row in children) {
                val name = row.path.substringAfterLast('/')
                val counted = "${row.exports.size} export${if (row.exports.size == 1) "" else "s"}"
                val names = row.exports.joinToString(", ")
                lines += if (names.isEmpty()) "  $name $counted" else "  $name $counted · $names"
            }
            return lines
        }

        private fun renderFile(atlas: Atlas, path: String): List<String> {
            val row = atlas.row(path) ?: return listOf("atlas $path: not in the atlas")
            val outline = atlas.outline(path)
            val lines = ArrayList<String>()
            lines += "atlas $path: ${row.lang.id} · ${formatBytes(row.bytes)} · ${row.hash8} · tier " +
                "${outline.tier.level} · complete=${outline.complete}"
            if (row.testsFor.isNotEmpty()) lines += "  tests_for: ${row.testsFor.joinToString(", ")}"
            for (entry in outline.entries) {
                if (entry.kind == DeclarationKind.Import) continue
                val span = if (entry.from == entry.to) "${entry.from}" else "${entry.from}-${entry.to}"
                lines += "  ${entry.kind.name.lowercase()} ${entry.name} $span"
            }
            if (outline.entries.none { it.kind != DeclarationKind.Import }) lines += "  (no declarations)"
            return lines
        }

        private fun displayDir(dir: String): String = if (dir.isEmpty()) "/" else "$dir/"
    }
}

// --------------------------------------------------------------- formatting

private val ESTIMATOR = HeuristicEstimator()
private const val TRUNCATION_MARKER_TOKENS = 4

/**
 * Joins as many leading [lines] as fit in [maxTokens], measured with [HeuristicEstimator] (D-06),
 * and appends `… +N` naming how many lines were dropped. Deterministic: the same lines and cap
 * always produce the same bytes.
 */
internal fun capLines(lines: List<String>, maxTokens: Int): String {
    if (lines.isEmpty()) return ""
    val budget = maxOf(1, maxTokens)
    val whole = lines.joinToString("\n")
    if (tokensOf(whole) <= budget) return whole
    val kept = ArrayList<String>()
    var text = ""
    for (line in lines) {
        val candidate = if (kept.isEmpty()) line else "$text\n$line"
        if (tokensOf(candidate) > budget - TRUNCATION_MARKER_TOKENS) break
        kept += line
        text = candidate
    }
    val dropped = lines.size - kept.size
    if (kept.isEmpty()) return "… +$dropped"
    return "$text\n… +$dropped"
}

private fun tokensOf(text: String): Long = ESTIMATOR.estimate(text).tokens

private const val KIB = 1024L

/**
 * A locale-free, deterministic size: bytes below 1 KiB, otherwise one decimal computed with
 * integer arithmetic so no floating-point or locale rule can change the rendered text.
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < KIB) return "$bytes B"
    var value = bytes
    var unit = 0
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    while (value >= KIB * KIB && unit < units.size - 1) {
        value /= KIB
        unit++
    }
    val tenths = value * 10 / KIB
    return "${tenths / 10}.${tenths % 10} ${units[unit]}"
}
