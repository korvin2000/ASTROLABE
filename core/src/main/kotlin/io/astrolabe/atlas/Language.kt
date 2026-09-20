package io.astrolabe.atlas

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * The languages tier 0 recognises by file extension (§7.2). The set is closed on purpose: a
 * language the outline parsers do not model is [Other], which is rendered by the generic fallback
 * rather than being silently treated as one of the modelled ones.
 *
 * [id] is the rendered spelling used by [Prime] and [Focus]; it never varies with the host locale.
 */
@Serializable
public enum class Language(public val id: String) {
    Python("python"),
    JavaScript("javascript"),
    TypeScript("typescript"),
    Kotlin("kotlin"),
    Java("java"),
    Go("go"),
    Rust("rust"),
    Markdown("markdown"),
    Json("json"),
    Yaml("yaml"),
    Toml("toml"),
    Shell("shell"),
    Other("other"),
    ;

    /** True for the D-09 language set whose declarations the tier-0 parsers model. */
    public val hasOutlineParser: Boolean
        get() = this in PARSED

    public companion object {
        private val PARSED = setOf(Python, JavaScript, TypeScript, Kotlin, Java)

        private val BY_EXTENSION: Map<String, Language> = buildMap {
            for (extension in listOf("py", "pyi", "pyw")) put(extension, Python)
            for (extension in listOf("js", "jsx", "mjs", "cjs")) put(extension, JavaScript)
            for (extension in listOf("ts", "tsx", "mts", "cts")) put(extension, TypeScript)
            for (extension in listOf("kt", "kts")) put(extension, Kotlin)
            put("java", Java)
            put("go", Go)
            put("rs", Rust)
            for (extension in listOf("md", "markdown")) put(extension, Markdown)
            put("json", Json)
            for (extension in listOf("yaml", "yml")) put(extension, Yaml)
            put("toml", Toml)
            for (extension in listOf("sh", "bash", "zsh")) put(extension, Shell)
        }

        /**
         * The language of a repository-relative, forward-slashed [path]. Unknown extensions and
         * extensionless files are [Other]; the extension is lowercased with [Locale.ROOT] so a
         * case-insensitive filesystem cannot change the answer.
         */
        @JvmStatic
        public fun of(path: String): Language {
            val name = path.substringAfterLast('/')
            val lower = name.lowercase(Locale.ROOT)
            val dot = lower.lastIndexOf('.')
            if (dot <= 0 || dot == lower.length - 1) return Other
            return BY_EXTENSION[lower.substring(dot + 1)] ?: Other
        }
    }
}

/**
 * How much a symbol answer can be trusted (§7.2). Tier 0 is lexical: it finds declarations and
 * textual occurrences, and every `refs`/`importers` answer it produces carries `complete = false`
 * so an incomplete index is never mistaken for absence (L8).
 */
@Serializable
public enum class IndexTier(public val level: Int) {
    /** Regex / ctags-grade. Outlines and definitions by name; references are never complete. */
    Lexical(0),

    /** Tree-sitter (P5.4): incremental syntax trees, precise spans; still no cross-module resolution. */
    Syntax(1),

    /** Language-service adapter (P5.4): project semantics, with the adapter's own declared scope. */
    LanguageService(2),
}
