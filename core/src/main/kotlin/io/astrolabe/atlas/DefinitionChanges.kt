package io.astrolabe.atlas

/** How a definition changed across an edit batch (§7.4 impact nudge: signature, visibility, export). */
public enum class DefinitionChange(public val wire: String) {
    Signature("signature changed"),
    Visibility("visibility changed"),
    Export("export changed"),
    Removed("removed"),
}

/** One changed definition of [symbol] in [path]; [public] when it was or is exported. */
public data class ChangedDefinition(
    val path: String,
    val symbol: String,
    val kind: DeclarationKind,
    val change: DefinitionChange,
    val public: Boolean,
) {
    init { require(path.isNotEmpty() && symbol.isNotEmpty()) }
}

/**
 * Outline diff of one file across an edit batch (§7.4). A definition is compared by its header — the
 * declaration's lines up to the one that opens its body, at most [HEADER_LINES] — with whitespace runs
 * collapsed, so a body-only edit is never a changed definition. Same-name declarations (overloads) are
 * compared as a multiset of headers. Tier 0: a declaration the parser did not recognise is not reported.
 */
public object DefinitionChanges {
    private const val HEADER_LINES = 8
    private val MODIFIERS = Regex("\\b(public|private|protected|internal|export|default|open|static)\\s+")

    /** [before] null means the file did not exist, [after] null that it was deleted. */
    @JvmStatic
    public fun of(path: String, before: ByteArray?, after: ByteArray?): List<ChangedDefinition> {
        val old = headers(path, before)
        val new = headers(path, after)
        val out = ArrayList<ChangedDefinition>()
        for ((name, was) in old) {
            val now = new[name].orEmpty()
            if (was.map { it.text }.sorted() == now.map { it.text }.sorted() && was.map { it.exported }.sorted() == now.map { it.exported }.sorted()) continue
            val change = when {
                now.isEmpty() -> DefinitionChange.Removed
                was.map { it.exported }.toSet() != now.map { it.exported }.toSet() -> DefinitionChange.Export
                was.map { strip(it.text) }.sorted() == now.map { strip(it.text) }.sorted() -> DefinitionChange.Visibility
                else -> DefinitionChange.Signature
            }
            out += ChangedDefinition(path, name, was.first().kind, change, (was + now).any { it.exported })
        }
        return out
    }

    private class Header(val kind: DeclarationKind, val text: String, val exported: Boolean)

    private fun headers(path: String, bytes: ByteArray?): Map<String, List<Header>> {
        if (bytes == null) return emptyMap()
        val lines = decodeLines(bytes)
        return Outline.of(path, bytes).entries.filter { it.kind != DeclarationKind.Import }.groupBy({ it.name }) { entry ->
            val text = StringBuilder()
            for (line in entry.from..minOf(entry.to, entry.from + HEADER_LINES - 1, lines.size)) {
                val raw = lines[line - 1]
                text.append(raw.trim()).append(' ')
                val end = raw.trimEnd()
                if ('{' in raw || end.endsWith(":") || end.endsWith(";") || end.endsWith("=")) break
            }
            Header(entry.kind, text.toString().replace(Regex("\\s+"), " ").trim(), entry.exported)
        }
    }

    private fun strip(header: String): String = MODIFIERS.replace(header, "")
}
