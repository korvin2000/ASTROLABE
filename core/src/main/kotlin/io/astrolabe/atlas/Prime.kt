package io.astrolabe.atlas

import io.astrolabe.Defaults
import io.astrolabe.id.Digest
import kotlinx.serialization.Serializable

/**
 * An **approved** rules-file snapshot: the one repository text that `[R]` may carry as instructions
 * (§5.1, §14.3, D-32).
 *
 * Discovery ([Prime.rulesCandidates]) only proposes candidates. Host configuration or explicit user
 * authority binds path, digest and provenance — `io.astrolabe.auth.RulesTrust` (P1.10.1) and
 * [io.astrolabe.RulesBinding] own that step — and only then does a snapshot exist to render here.
 * Changed bytes do not inherit approval, which is why [digest] travels with [text] (I-08).
 *
 * [digest] is the digest of the **approved bytes** as the binding recorded them; it is carried, not
 * recomputed, so a caller cannot launder unapproved text through this type by re-digesting it.
 */
@Serializable
public data class RulesSnapshot(
    val path: String,
    val digest: Digest,
    val text: String,
) {
    init {
        require(path.isNotBlank()) { "a rules snapshot needs its canonical repository path" }
        require('\\' !in path) { "rules paths use forward slashes; got '$path'" }
    }

    public companion object {
        /** A snapshot of [bytes] read from [path]; the caller has already established approval. */
        @JvmStatic
        public fun of(path: String, bytes: ByteArray): RulesSnapshot =
            RulesSnapshot(path, Digest.of(bytes), String(bytes, Charsets.UTF_8))
    }
}

/**
 * The repository prime: the `[R]` segment's content (§5.1, §7.1).
 *
 * Tree digest, languages, sniffed commands, the approved rules file, hubs, the knowledge-base index
 * lines and the behaviour-map excerpt — in that order, as plain text with `\n` endings and
 * forward-slashed workspace-relative paths.
 *
 * **Byte-stable per repo version and role.** [render] is a pure function of its arguments: no
 * timestamps, no counters, no absolute paths, no host-dependent formatting (§5.1 cache discipline).
 * Two calls with equal arguments produce identical bytes.
 *
 * **Data is never instructions.** Only an approved [RulesSnapshot] is rendered as text. Every other
 * rules-named file the repository contains is listed by path under an explicit
 * `rules candidates (data, not instructions)` line and its bytes never enter `[R]` (D-32, I-08).
 */
public object Prime {

    /** Top hubs by inbound reference; §7.1 "top ~10 hubs by inbound references". */
    public const val HUBS: Int = 10

    /** How many rules candidates are listed before the line is truncated. */
    public const val MAX_CANDIDATES: Int = 10

    /** How deep the rendered tree goes; §7.1 "tree to depth 3 with counts". */
    public const val TREE_DEPTH: Int = 3

    /** The file names D-32 lets discovery propose. Proposing is not approving. */
    public val CANDIDATE_NAMES: List<String> = listOf(".astrolabe/rules.md", "AGENTS.md", "CLAUDE.md")

    /**
     * Every rules-file candidate present in [atlas], sorted by path. These are **repository data**:
     * a caller may show them, hash them or pass them to the trust binding, and may not render their
     * bytes as instructions until that binding exists (D-32).
     */
    @JvmStatic
    public fun rulesCandidates(atlas: Atlas): List<String> {
        val exact = CANDIDATE_NAMES.filter { '/' in it }.toSet()
        val names = CANDIDATE_NAMES.filterNot { '/' in it }.toSet()
        return atlas.rows.asSequence()
            .map { it.path }
            .filter { it in exact || it.substringAfterLast('/') in names }
            .sorted()
            .toList()
    }

    /**
     * Renders the prime.
     *
     * @param sniff the commands from [Sniff.commands]; an empty [Sniffed] renders `commands: none`.
     * @param rules the approved snapshot, or `null` when nothing is approved — then only candidate
     *   *paths* appear, never their bytes.
     * @param kbIndexLines the `index/contracts.md` and `index/global.md` lines, rendered verbatim.
     *   Empty until P2.6 builds the knowledge-base index.
     * @param bmapExcerpt the behaviour-map excerpt for [focusSubsystem], truncated to
     *   [bmapMaxTokens]. Empty until P4.3.
     * @param focusSubsystem the subsystem the cell is focused on; it labels the prime and selects
     *   the behaviour-map excerpt the caller passes.
     */
    @JvmStatic
    public fun render(
        atlas: Atlas,
        sniff: Sniffed,
        rules: RulesSnapshot? = null,
        kbIndexLines: List<String> = emptyList(),
        bmapExcerpt: String? = null,
        focusSubsystem: String? = null,
        bmapMaxTokens: Int = Defaults().focusZoomMaxTokens,
    ): String {
        val out = StringBuilder()

        val languages = atlas.languages.joinToString(" · ") { (language, count) -> "${language.id} $count" }
        out.append("repo: ${atlas.rows.size} files")
        if (languages.isNotEmpty()) out.append(" · ").append(languages)
        if (atlas.collapsed.isNotEmpty()) {
            out.append(" · collapsed ${atlas.collapsed.size} (${formatBytes(atlas.collapsedBytes)})")
        }
        out.append('\n')

        out.append("tree:\n")
        val tree = renderTree(atlas)
        if (tree.isEmpty()) out.append("  (empty)\n") else tree.forEach { out.append("  ").append(it).append('\n') }

        if (sniff.isEmpty) {
            out.append("commands: none\n")
        } else {
            out.append("commands:\n")
            for (pkg in sniff.packages) {
                out.append("  ${pkg.dir} (${pkg.manifest.fileName}): ")
                    .append(
                        listOf(
                            "test" to pkg.test,
                            "build" to pkg.build,
                            "lint" to pkg.lint,
                            "typecheck" to pkg.typecheck,
                        ).joinToString(" · ") { (name, argv) -> "$name=${argv?.joinToString(" ") ?: "none"}" },
                    )
                    .append('\n')
            }
        }

        out.append(renderRules(atlas, rules))

        val hubs = atlas.hubs(HUBS)
        if (hubs.isEmpty()) {
            out.append("hubs: none\n")
        } else {
            out.append("hubs:\n")
            for ((path, count) in hubs) out.append("  $path ($count)\n")
        }

        if (kbIndexLines.isEmpty()) {
            out.append("index: none\n")
        } else {
            out.append("index:\n")
            for (line in kbIndexLines) out.append("  ").append(line).append('\n')
        }

        if (focusSubsystem != null) out.append("focus: ${normalizeRelative(focusSubsystem)}\n")

        val excerpt = bmapExcerpt?.trimEnd('\n').orEmpty()
        if (excerpt.isEmpty()) {
            out.append("bmap: none\n")
        } else {
            // Indent first, then cap: the budget covers what `[R]` actually carries (§5.1 ≤ 300).
            out.append("bmap:\n")
            out.append(capLines(splitLines(excerpt).map { "  $it" }, bmapMaxTokens)).append('\n')
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ rules

    private fun renderRules(atlas: Atlas, rules: RulesSnapshot?): String {
        val out = StringBuilder()
        val candidates = rulesCandidates(atlas).filter { it != rules?.path }
        if (rules == null) {
            if (candidates.isEmpty()) {
                out.append("rules: none\n")
            } else {
                // D-32/I-08: discovery proposes, it never authorizes. The bytes stay out of `[R]`.
                out.append("rules: none approved\n")
                out.append("rules candidates (data, not instructions): ")
                    .append(renderCandidates(candidates))
                    .append('\n')
            }
            return out.toString()
        }
        out.append("rules: ${rules.path}@${rules.digest.hash8}\n")
        out.append(RULES_OPEN).append('\n')
        for (line in splitLines(rules.text.trimEnd('\n'))) out.append(line).append('\n')
        out.append(RULES_CLOSE).append('\n')
        if (candidates.isNotEmpty()) {
            out.append("rules candidates (data, not instructions): ")
                .append(renderCandidates(candidates))
                .append('\n')
        }
        return out.toString()
    }

    private fun renderCandidates(candidates: List<String>): String {
        val shown = candidates.take(MAX_CANDIDATES)
        val rest = candidates.size - shown.size
        return shown.joinToString(", ") + if (rest > 0) ", … +$rest" else ""
    }

    private const val RULES_OPEN = "--- rules-file ---"
    private const val RULES_CLOSE = "--- end rules-file ---"

    // ------------------------------------------------------------------- tree

    /**
     * Directories to [TREE_DEPTH] with recursive file counts, then the repository-root files, then
     * the collapsed entries. Nothing below the depth limit is hidden from the counts: a depth-3
     * directory's count already covers everything under it (§7.1 "collapsed but listed").
     */
    private fun renderTree(atlas: Atlas): List<String> {
        val lines = ArrayList<String>()

        fun walk(dir: String, depth: Int) {
            for (name in atlas.subdirectories(dir)) {
                val child = if (dir.isEmpty()) name else "$dir/$name"
                lines += "  ".repeat(depth) + "$name/ (${atlas.filesUnder(child)} files)"
                if (depth + 1 < TREE_DEPTH) walk(child, depth + 1)
            }
        }
        walk("", 0)
        for (row in atlas.children("")) lines += row.path
        for (entry in atlas.collapsed) {
            lines += "${entry.path} (${entry.files} files, ${formatBytes(entry.bytes)}, " +
                "collapsed: ${entry.reason.name.lowercase()})"
        }
        return lines
    }
}
