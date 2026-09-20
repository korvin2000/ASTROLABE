package io.astrolabe.atlas

import kotlinx.serialization.Serializable

/**
 * What a tier-0 [Declaration] is. The vocabulary is closed (§7.2): a named type (`type`,
 * `interface`, `record`, `object`) is a [Class], because tier 0 does not distinguish type kinds;
 * anything the generic fallback finds is [Other].
 */
@Serializable
public enum class DeclarationKind {
    Function,
    Class,
    Method,
    Const,
    Import,
    Export,
    Other,
}

/**
 * One declaration found by the tier-0 parsers.
 *
 * [from] and [to] are **1-based inclusive** line numbers. The end of the block is estimated — by
 * indentation for Python and by bracket matching elsewhere — so a span is a navigation aid, never
 * an authority for an edit; [Outline.complete] is `false` for the same reason.
 *
 * For [DeclarationKind.Import], [name] is the raw import target as written in the source
 * (`pay.handlers.user`, `./router.ts`); [Atlas] resolves it to a workspace-relative path where it
 * can. [exported] follows each language's own rule: a Python name without a leading underscore, a
 * JS/TS declaration carrying `export`, a Kotlin declaration that is not `private`/`internal`, a
 * Java declaration that is `public`.
 */
@Serializable
public data class Declaration(
    val kind: DeclarationKind,
    val name: String,
    val from: Int,
    val to: Int,
    val exported: Boolean,
) {
    init {
        require(name.isNotEmpty()) { "a declaration name may not be empty" }
        require(from >= 1) { "line numbers are 1-based; got from=$from" }
        require(to >= from) { "a span ends at or after it starts; got $from..$to" }
    }
}

/**
 * The declarations of one file without their bodies (§7.1, §7.2 tier 0).
 *
 * [namespace] is the file's declared namespace where the language has one (`package pay.handlers`
 * in Kotlin and Java, the dotted module path in Python); it is what lets [Atlas] resolve a Kotlin
 * `import pay.Request` to the file that declares `Request`.
 *
 * [complete] is always `false` at [IndexTier.Lexical]: a regex parser finds what it recognises and
 * says nothing about what it did not.
 */
@Serializable
public data class Outline(
    val path: String,
    val language: Language,
    val entries: List<Declaration>,
    val namespace: String? = null,
    val tier: IndexTier = IndexTier.Lexical,
    val complete: Boolean = false,
) {
    /** Names of the top-level declarations this file offers to other files, in source order. */
    public val exports: List<String>
        get() = entries.asSequence()
            .filter { it.exported && it.kind in EXPORTABLE }
            .map { it.name }
            .distinct()
            .toList()

    /** The raw import targets, in source order, as written. */
    public val importTargets: List<String>
        get() = entries.asSequence()
            .filter { it.kind == DeclarationKind.Import }
            .map { it.name }
            .distinct()
            .toList()

    public companion object {
        private val EXPORTABLE = setOf(
            DeclarationKind.Function,
            DeclarationKind.Class,
            DeclarationKind.Const,
            DeclarationKind.Export,
        )

        private const val BINARY_PROBE_BYTES = 8 * 1024

        /** An outline with no entries, for a file tier 0 cannot or will not parse. */
        @JvmStatic
        public fun empty(path: String, language: Language = Language.of(path)): Outline =
            Outline(path, language, emptyList())

        /**
         * Parses [bytes] as the file at [path]. Never throws: binary or syntactically broken input
         * yields an outline with fewer entries, never an exception, because orientation must not be
         * able to fail a campaign (§7.1).
         */
        @JvmStatic
        public fun of(path: String, bytes: ByteArray): Outline {
            val language = Language.of(path)
            if (isBinary(bytes)) return empty(path, language)
            return try {
                val lines = decodeLines(bytes)
                when (language) {
                    Language.Python -> parsePython(path, lines)
                    Language.JavaScript, Language.TypeScript ->
                        parseCurly(path, language, lines, CurlyDialect.Script)

                    Language.Kotlin -> parseCurly(path, language, lines, CurlyDialect.Kotlin)
                    Language.Java -> parseCurly(path, language, lines, CurlyDialect.Java)
                    else -> parseGeneric(path, language, lines)
                }
            } catch (_: RuntimeException) {
                // §7.1 "the atlas never lies about existence": a parser defect must degrade the
                // outline, never remove the file from the atlas or fail the caller.
                empty(path, language)
            } catch (_: StackOverflowError) {
                empty(path, language)
            }
        }

        private fun isBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_PROBE_BYTES)
            for (i in 0 until limit) if (bytes[i] == 0.toByte()) return true
            return false
        }
    }
}

// ------------------------------------------------------------------ decoding

/** Splits UTF-8 [bytes] into lines; `\n` and `\r\n` both terminate, a lone `\r` does not. */
internal fun decodeLines(bytes: ByteArray): List<String> = splitLines(String(bytes, Charsets.UTF_8))

/** Splits [text] into lines; `\n` and `\r\n` both terminate, a lone `\r` does not. */
internal fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = ArrayList<String>()
    var start = 0
    while (start <= text.length) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) {
            if (start < text.length) lines += text.substring(start)
            break
        }
        val end = if (newline > start && text[newline - 1] == '\r') newline - 1 else newline
        lines += text.substring(start, end)
        start = newline + 1
    }
    return lines
}

/** Visual indentation width, counting a tab as four columns. */
internal fun indentOf(line: String): Int {
    var width = 0
    for (c in line) {
        when (c) {
            ' ' -> width += 1
            '\t' -> width += 4
            else -> return width
        }
    }
    return width
}

// --------------------------------------------------------------- block spans

/**
 * Where a declaration's block ends, estimated from the source text alone.
 *
 * Braces and the other brackets are counted separately, because a parameter list must not end a
 * declaration: a `{` block runs to its matching `}`, and a declaration with no block runs to the
 * `;` or the line end at which every bracket is balanced. Strings and comments are skipped so a
 * brace in a literal cannot close a block; a template literal is treated as one string, which
 * balances `${…}` correctly for every case that does not also contain a lone backtick.
 */
internal class BlockScanner(
    private val lineComment: String,
    private val blockComment: Boolean,
    private val tripleQuotes: Boolean,
    private val backtick: Boolean,
) {
    /** The 0-based index of the line where the declaration starting at [start] ends. */
    fun end(lines: List<String>, start: Int): Int {
        if (start >= lines.size) return maxOf(0, lines.size - 1)
        var brace = 0
        var bracket = 0
        var openedBrace = false
        var inBlockComment = false
        var line = start
        val limit = minOf(lines.size, start + MAX_LINES)
        while (line < limit) {
            val text = lines[line]
            var i = 0
            var broke = false
            while (i < text.length) {
                val c = text[i]
                if (inBlockComment) {
                    if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        inBlockComment = false
                        i += 2
                    } else {
                        i++
                    }
                    continue
                }
                if (blockComment && c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
                if (lineComment.isNotEmpty() && text.startsWith(lineComment, i)) break
                if (tripleQuotes && (text.startsWith("\"\"\"", i) || text.startsWith("'''", i))) {
                    val quote = text.substring(i, i + 3)
                    val (nextLine, nextColumn) = skipMultiline(lines, line, i + 3, quote, limit)
                    if (nextLine >= limit) return limit - 1
                    if (nextLine != line) {
                        line = nextLine
                        i = nextColumn
                        broke = true
                        break
                    }
                    i = nextColumn
                    continue
                }
                if (backtick && c == '`') {
                    val (nextLine, nextColumn) = skipMultiline(lines, line, i + 1, "`", limit)
                    if (nextLine >= limit) return limit - 1
                    if (nextLine != line) {
                        line = nextLine
                        i = nextColumn
                        broke = true
                        break
                    }
                    i = nextColumn
                    continue
                }
                if (c == '"' || c == '\'') {
                    i = skipQuoted(text, i + 1, c)
                    continue
                }
                when (c) {
                    '{' -> {
                        brace++
                        openedBrace = true
                    }

                    '}' -> {
                        if (brace > 0) brace--
                        if (brace == 0 && openedBrace) return line
                    }

                    '[', '(' -> bracket++
                    ']', ')' -> if (bracket > 0) bracket--
                    ';' -> if (brace == 0 && bracket == 0) return line
                }
                i++
            }
            // `broke` means a multi-line literal moved the cursor onto another line; the outer
            // loop must re-scan from there rather than advance past it.
            if (broke) continue
            if (brace == 0 && bracket == 0 && !inBlockComment && !continues(text)) return line
            line++
        }
        return limit - 1
    }

    /** True when [text] plainly continues on the next line (an operator or opener at its end). */
    private fun continues(text: String): Boolean {
        val trimmed = text.trimEnd()
        return trimmed.isNotEmpty() && trimmed.last() in CONTINUATION
    }

    private fun skipQuoted(text: String, from: Int, quote: Char): Int {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> return i + 1
            }
            i++
        }
        // An unterminated quote ends with its line, which keeps a stray apostrophe in prose from
        // swallowing the rest of the file.
        return text.length
    }

    /** Skips a multi-line literal; returns the (line, column) just after its terminator. */
    private fun skipMultiline(
        lines: List<String>,
        startLine: Int,
        startColumn: Int,
        terminator: String,
        limit: Int,
    ): Pair<Int, Int> {
        var line = startLine
        var column = startColumn
        while (line < limit) {
            val text = lines[line]
            var i = column
            while (i < text.length) {
                if (text[i] == '\\') {
                    i += 2
                    continue
                }
                if (text.startsWith(terminator, i)) return line to (i + terminator.length)
                i++
            }
            line++
            column = 0
        }
        return limit to 0
    }

    private companion object {
        /** A declaration longer than this is not a declaration; the bound keeps a broken file cheap. */
        const val MAX_LINES = 5_000
        val CONTINUATION = setOf('=', ',', '+', '|', '&', ':', '\\')
    }
}

internal enum class CurlyDialect { Script, Kotlin, Java }

// -------------------------------------------------------------------- Python

private val PY_DEF = Regex("""^(?:\s*)(?:async\s+)?def\s+([A-Za-z_]\w*)\s*\(""")
private val PY_CLASS = Regex("""^(?:\s*)class\s+([A-Za-z_]\w*)\s*[(:]""")
private val PY_CONST = Regex("""^([A-Za-z_]\w*)\s*(?::[^=]+)?=(?!=)""")
private val PY_IMPORT = Regex("""^\s*import\s+(.+)$""")
private val PY_FROM = Regex("""^\s*from\s+(\.*[\w.]*)\s+import\s+""")
private val PY_KEYWORDS = setOf("if", "for", "while", "with", "return", "else", "elif", "try", "except", "assert")

private fun parsePython(path: String, lines: List<String>): Outline {
    val scanner = BlockScanner(lineComment = "#", blockComment = false, tripleQuotes = true, backtick = false)
    val entries = ArrayList<Declaration>()

    val classSpans = ArrayList<IntRange>()
    for ((index, line) in lines.withIndex()) {
        if (PY_CLASS.containsMatchIn(line)) classSpans += index..endByIndent(lines, index, indentOf(line))
    }

    for ((index, line) in lines.withIndex()) {
        val number = index + 1
        val indent = indentOf(line)

        val from = PY_FROM.find(line)
        if (from != null) {
            val module = from.groupValues[1]
            if (module.isNotEmpty()) {
                entries += Declaration(DeclarationKind.Import, module, number, number, exported = false)
            }
            continue
        }

        val import = PY_IMPORT.find(line)
        if (import != null) {
            for (part in import.groupValues[1].split(',')) {
                val module = part.trim().substringBefore(" as ").trim().substringBefore('#').trim()
                if (module.isNotEmpty() && (module[0].isLetter() || module[0] == '_' || module[0] == '.')) {
                    entries += Declaration(DeclarationKind.Import, module, number, number, exported = false)
                }
            }
            continue
        }

        val klass = PY_CLASS.find(line)
        if (klass != null) {
            val name = klass.groupValues[1]
            entries += Declaration(
                kind = DeclarationKind.Class,
                name = name,
                from = number,
                to = endByIndent(lines, index, indent) + 1,
                exported = indent == 0 && !name.startsWith('_'),
            )
            continue
        }

        val def = PY_DEF.find(line)
        if (def != null) {
            val name = def.groupValues[1]
            val nested = indent > 0 || classSpans.any { index in it && index != it.first }
            entries += Declaration(
                kind = if (nested) DeclarationKind.Method else DeclarationKind.Function,
                name = name,
                from = number,
                to = endByIndent(lines, index, indent) + 1,
                exported = !nested && !name.startsWith('_'),
            )
            continue
        }

        if (indent == 0) {
            val const = PY_CONST.find(line)
            val name = const?.groupValues?.get(1)
            if (name != null && name !in PY_KEYWORDS) {
                entries += Declaration(
                    kind = DeclarationKind.Const,
                    name = name,
                    from = number,
                    to = maxOf(scanner.end(lines, index), index) + 1,
                    exported = !name.startsWith('_'),
                )
            }
        }
    }
    entries.sortWith(compareBy({ it.from }, { it.name }))
    return Outline(path, Language.Python, entries, namespace = pythonModule(path))
}

/** The end of an indentation block: the last line more indented than [indent]. */
private fun endByIndent(lines: List<String>, start: Int, indent: Int): Int {
    var end = start
    var i = start + 1
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        if (indentOf(line) <= indent) break
        end = i
        i++
    }
    return end
}

/** `pay/handlers/user.py` → `pay.handlers.user`; `pay/__init__.py` → `pay`. */
internal fun pythonModule(path: String): String? {
    if (!path.endsWith(".py") && !path.endsWith(".pyi")) return null
    val parts = path.substringBeforeLast('.').split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val trimmed = if (parts.last() == "__init__") parts.dropLast(1) else parts
    return trimmed.takeIf { it.isNotEmpty() }?.joinToString(".")
}

// --------------------------------------------------------- curly-brace family

private val JS_IMPORT_FROM = Regex("""^\s*(?:import|export)\b[^;]*?\bfrom\s*["']([^"']+)["']""")
private val JS_IMPORT_BARE = Regex("""^\s*import\s*["']([^"']+)["']""")
private val JS_REQUIRE = Regex("""\brequire\s*\(\s*["']([^"']+)["']\s*\)""")
private val JS_EXPORT_LIST = Regex("""^\s*export\s+(?:type\s+)?\{([^}]*)}""")
private val JS_FUNCTION = Regex(
    """^(\s*)(export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)""",
)
private val JS_CLASS = Regex(
    """^(\s*)(export\s+)?(?:default\s+)?(?:abstract\s+)?(?:class|interface)\s+([A-Za-z_$][\w$]*)""",
)
private val JS_TYPE = Regex("""^(\s*)(export\s+)?type\s+([A-Za-z_$][\w$]*)\s*[=<]""")
private val JS_ENUM = Regex("""^(\s*)(export\s+)?(?:const\s+)?enum\s+([A-Za-z_$][\w$]*)""")
private val JS_CONST = Regex("""^(\s*)(export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)""")
private val JS_MEMBER = Regex(
    """^\s+(?:(?:public|private|protected|static|readonly|async|override|abstract)\s+)*""" +
        """(?:(?:get|set)\s+)?([A-Za-z_$][\w$]*)\s*(?:<[^<>()]*>)?\s*\(""",
)

private val KT_PACKAGE = Regex("""^\s*package\s+([\w.]+)""")
private val KT_IMPORT = Regex("""^\s*import\s+([\w.]+)""")
private val KT_FUNCTION = Regex(
    """^(\s*)((?:public|private|internal|protected|open|override|suspend|inline|operator|infix|""" +
        """tailrec|external|expect|actual|abstract|final)\s+)*fun\s+(?:<[^>]*>\s*)?""" +
        """(?:[\w.<>?\[\], ]+\.)?([A-Za-z_]\w*|`[^`]+`)\s*\(""",
)
private val KT_CLASS = Regex(
    """^(\s*)((?:public|private|internal|protected|open|sealed|abstract|final|data|value|inner|""" +
        """enum|annotation|expect|actual|fun)\s+)*(?:class|interface|object)\s+([A-Za-z_]\w*)""",
)
private val KT_PROPERTY = Regex(
    """^(\s*)((?:public|private|internal|protected|const|lateinit|open|override|expect|actual)\s+)*""" +
        """(?:val|var)\s+([A-Za-z_]\w*)""",
)

private val JAVA_PACKAGE = Regex("""^\s*package\s+([\w.]+)\s*;""")
private val JAVA_IMPORT = Regex("""^\s*import\s+(?:static\s+)?([\w.]+?)(?:\.\*)?\s*;""")
private val JAVA_TYPE = Regex(
    """^(\s*)((?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\s+)*""" +
        """(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)""",
)
private val JAVA_MEMBER = Regex(
    """^(\s+)((?:public|private|protected|static|final|synchronized|abstract|native|default|strictfp)\s+)+""" +
        """(?:<[^>]*>\s*)?[\w.$<>\[\],?\s]+?\s+([A-Za-z_$][\w$]*)\s*\(""",
)
private val JAVA_FIELD = Regex(
    """^(\s+)((?:public|private|protected|static|final|volatile|transient)\s+)+""" +
        """[\w.$<>\[\],?\s]+?\s+([A-Za-z_$][\w$]*)\s*[=;]""",
)

private val MEMBER_KEYWORDS = setOf(
    "if", "for", "while", "switch", "catch", "return", "do", "else", "new", "typeof", "super",
    "this", "function", "await", "yield", "throw", "delete", "void", "in", "of", "case", "with",
)

private fun parseCurly(path: String, language: Language, lines: List<String>, dialect: CurlyDialect): Outline {
    val scanner = BlockScanner(
        lineComment = "//",
        blockComment = true,
        tripleQuotes = dialect == CurlyDialect.Kotlin,
        backtick = dialect == CurlyDialect.Script,
    )
    val entries = ArrayList<Declaration>()
    var namespace: String? = null

    fun container(line: String): MatchResult? = when (dialect) {
        CurlyDialect.Script -> JS_CLASS.find(line) ?: JS_ENUM.find(line)
        CurlyDialect.Kotlin -> KT_CLASS.find(line)
        CurlyDialect.Java -> JAVA_TYPE.find(line)
    }

    fun functionAt(line: String): MatchResult? = when (dialect) {
        CurlyDialect.Script -> JS_FUNCTION.find(line)
        CurlyDialect.Kotlin -> KT_FUNCTION.find(line)
        CurlyDialect.Java -> JAVA_MEMBER.find(line)
    }

    fun memberAt(line: String): MatchResult? = when (dialect) {
        CurlyDialect.Script -> JS_MEMBER.find(line)?.takeIf { '{' in line }
        CurlyDialect.Kotlin -> null
        CurlyDialect.Java -> JAVA_MEMBER.find(line)
    }

    // Pass 1: the spans that decide whether a later match is a top-level declaration, a member, or
    // a local binding inside a body. Tier 0 outlines declarations, never locals.
    val containers = ArrayList<IntRange>()
    for ((index, line) in lines.withIndex()) {
        if (!isCommentLine(line) && container(line) != null) containers += index..scanner.end(lines, index)
    }

    fun nestedAt(index: Int): Boolean = containers.any { index in it && index != it.first }

    val bodies = ArrayList<IntRange>()
    for ((index, line) in lines.withIndex()) {
        if (isCommentLine(line) || container(line) != null) continue
        val declaration = functionAt(line) ?: (if (nestedAt(index)) memberAt(line) else null) ?: continue
        if (declaration.groupValues.isNotEmpty()) bodies += index..scanner.end(lines, index)
    }

    fun insideBody(index: Int): Boolean = bodies.any { index in it && index != it.first }

    fun add(kind: DeclarationKind, name: String, index: Int, exported: Boolean) {
        val cleaned = name.trim('`')
        if (cleaned.isEmpty()) return
        entries += Declaration(kind, cleaned, index + 1, maxOf(scanner.end(lines, index), index) + 1, exported)
    }

    for ((index, line) in lines.withIndex()) {
        val number = index + 1
        if (isCommentLine(line)) continue

        if (dialect == CurlyDialect.Script) {
            var isImportLine = false
            JS_IMPORT_FROM.find(line)?.let {
                entries += Declaration(DeclarationKind.Import, it.groupValues[1], number, number, false)
                isImportLine = true
            }
            if (!isImportLine) {
                JS_IMPORT_BARE.find(line)?.let {
                    entries += Declaration(DeclarationKind.Import, it.groupValues[1], number, number, false)
                    isImportLine = true
                }
            }
            JS_REQUIRE.find(line)?.let {
                entries += Declaration(DeclarationKind.Import, it.groupValues[1], number, number, false)
            }
            val exportList = JS_EXPORT_LIST.find(line)
            if (exportList != null) {
                for (raw in exportList.groupValues[1].split(',')) {
                    val name = raw.trim().substringAfterLast(" as ").removePrefix("type ").trim()
                    if (name.isNotEmpty() && name != "default") {
                        entries += Declaration(DeclarationKind.Export, name, number, number, true)
                    }
                }
                continue
            }
            if (isImportLine) continue
        } else {
            val packageMatch = if (dialect == CurlyDialect.Kotlin) KT_PACKAGE.find(line) else JAVA_PACKAGE.find(line)
            if (packageMatch != null) {
                namespace = packageMatch.groupValues[1]
                continue
            }
            val importMatch = if (dialect == CurlyDialect.Kotlin) KT_IMPORT.find(line) else JAVA_IMPORT.find(line)
            if (importMatch != null) {
                // `import pay.*` leaves a trailing dot behind the star; the target is the package.
                val target = importMatch.groupValues[1].trimEnd('.')
                if (target.isNotEmpty()) {
                    entries += Declaration(DeclarationKind.Import, target, number, number, false)
                }
                continue
            }
        }

        val type = container(line)
        if (type != null) {
            add(DeclarationKind.Class, type.groupValues[3], index, exportedBy(dialect, line, type))
            continue
        }

        if (dialect == CurlyDialect.Script) {
            val alias = JS_TYPE.find(line)
            if (alias != null) {
                add(DeclarationKind.Class, alias.groupValues[3], index, alias.groupValues[2].isNotBlank())
                continue
            }
        }

        val function = if (dialect == CurlyDialect.Java) null else functionAt(line)
        if (function != null) {
            val nested = nestedAt(index) || insideBody(index)
            add(
                if (nested) DeclarationKind.Method else DeclarationKind.Function,
                function.groupValues[3],
                index,
                !nested && exportedBy(dialect, line, function),
            )
            continue
        }

        val constant = when (dialect) {
            CurlyDialect.Script -> JS_CONST.find(line)
            CurlyDialect.Kotlin -> KT_PROPERTY.find(line)
            CurlyDialect.Java -> JAVA_FIELD.find(line)
        }
        if (constant != null) {
            // A binding inside a body is a local, not a declaration; tier 0 leaves it to `refs`.
            if (insideBody(index)) continue
            val nested = nestedAt(index)
            add(
                if (nested) DeclarationKind.Other else DeclarationKind.Const,
                constant.groupValues[3],
                index,
                !nested && exportedBy(dialect, line, constant),
            )
            continue
        }

        if (nestedAt(index) && !insideBody(index)) {
            val member = memberAt(line)
            val name = when (dialect) {
                CurlyDialect.Script -> member?.groupValues?.get(1)
                else -> member?.groupValues?.get(3)
            }
            if (name != null && name !in MEMBER_KEYWORDS) {
                add(DeclarationKind.Method, name, index, exported = false)
            }
        }
    }
    entries.sortWith(compareBy({ it.from }, { it.name }))
    return Outline(path, language, entries, namespace = namespace)
}

private fun exportedBy(dialect: CurlyDialect, line: String, match: MatchResult): Boolean = when (dialect) {
    CurlyDialect.Script -> match.groupValues.getOrElse(2) { "" }.isNotBlank() || line.trimStart().startsWith("export")
    CurlyDialect.Kotlin -> {
        val modifiers = match.groupValues.getOrElse(2) { "" }
        "private" !in modifiers && "internal" !in modifiers && "protected" !in modifiers
    }

    CurlyDialect.Java -> "public" in match.groupValues.getOrElse(2) { "" }
}

private fun isCommentLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
}

// -------------------------------------------------------------- generic tier

private val GENERIC_COMMENT = listOf("#", "//", "/*", "*", "<!--", ";", "--")
private const val GENERIC_NAME_CHARS = 80

/**
 * The fallback for every language without a parser: each top-level (column 0) non-blank,
 * non-comment line opens a block that runs until the next such line. [Declaration.name] is the
 * line itself, trimmed, so the render stays readable; nothing is reported as exported, because
 * tier 0 knows nothing about this language's visibility rules.
 */
private fun parseGeneric(path: String, language: Language, lines: List<String>): Outline {
    val starts = ArrayList<Int>()
    for ((index, line) in lines.withIndex()) {
        if (line.isBlank() || indentOf(line) != 0) continue
        val trimmed = line.trim()
        if (GENERIC_COMMENT.any { trimmed.startsWith(it) }) continue
        starts += index
    }
    val entries = ArrayList<Declaration>(starts.size)
    for ((position, index) in starts.withIndex()) {
        val end = if (position + 1 < starts.size) starts[position + 1] - 1 else lines.size - 1
        entries += Declaration(
            kind = DeclarationKind.Other,
            name = lines[index].trim().take(GENERIC_NAME_CHARS),
            from = index + 1,
            to = maxOf(end, index) + 1,
            exported = false,
        )
    }
    return Outline(path, language, entries)
}
