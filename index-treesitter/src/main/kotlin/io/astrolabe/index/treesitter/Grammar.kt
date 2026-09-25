package io.astrolabe.index.treesitter

import io.astrolabe.atlas.Language
import org.treesitter.TSLanguage
import org.treesitter.TreeSitterJava
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterTsx
import org.treesitter.TreeSitterTypescript
import java.util.Locale

/**
 * The tree-sitter grammars tier 1 ships for the D-09 languages, with the file extensions each
 * accepts and whether its parser is complete enough to issue a syntax verdict (D-10, D-211).
 *
 * [syntaxComplete] is `false` for Kotlin: the only published JVM grammar build (fwcd 0.3.8)
 * reports errors on valid code (a member before `}` on one line), so its ERROR nodes are no verdict.
 */
public enum class Grammar(
    public val language: Language,
    public val extensions: Set<String>,
    public val syntaxComplete: Boolean,
) {
    Python(Language.Python, setOf("py", "pyi", "pyw"), syntaxComplete = true),
    JavaScript(Language.JavaScript, setOf("js", "jsx", "mjs", "cjs"), syntaxComplete = true),
    TypeScript(Language.TypeScript, setOf("ts", "mts", "cts"), syntaxComplete = true),
    Tsx(Language.TypeScript, setOf("tsx"), syntaxComplete = true),
    Java(Language.Java, setOf("java"), syntaxComplete = true),
    Kotlin(Language.Kotlin, setOf("kt", "kts"), syntaxComplete = false),
    ;

    /** The grammar's own language object; loading it loads the JNI libraries and may throw a [LinkageError]. */
    internal fun load(): TSLanguage = when (this) {
        Python -> TreeSitterPython()
        JavaScript -> TreeSitterJavascript()
        TypeScript -> TreeSitterTypescript()
        Tsx -> TreeSitterTsx()
        Java -> TreeSitterJava()
        Kotlin -> TreeSitterKotlin()
    }

    public companion object {
        private val BY_EXTENSION: Map<String, Grammar> = buildMap {
            for (grammar in Grammar.entries) for (extension in grammar.extensions) put(extension, grammar)
        }

        /** The grammar for a repository-relative [path], or null when tier 1 has none for it. */
        @JvmStatic
        public fun of(path: String): Grammar? {
            val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return null
            return BY_EXTENSION[name.substring(dot + 1)]
        }
    }
}

/** Loads a grammar's language; a test substitutes a failing loader to exercise the tier-0 fallback (FX-46). */
internal fun interface GrammarLoader {
    fun load(grammar: Grammar): TSLanguage
}
