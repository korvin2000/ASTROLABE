package io.astrolabe.os.search

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/** How [SearchRequest.pattern] is interpreted (D-05). */
public enum class SearchMode {
    /** The pattern is a fixed string; no character has special meaning. */
    Literal,

    /** The pattern is a regular expression restricted to the [PatternSubset] common subset. */
    Regex,
}

/** Which engine produced a [Hits]. The choice is always explicit and always reported (D-05). */
public enum class SearchBackend {
    /** The external `rg` executable. */
    Ripgrep,

    /** The in-process `java.util.regex` fallback. */
    Jvm,
}

/** Why a search stopped before it had seen every candidate line (D-05 "partial"). */
public enum class IncompleteReason {
    /** The next hit would have pushed the returned text past [SearchRequest.budgetBytes]. */
    BudgetBytesExceeded,

    /** [SearchRequest.maxHits] hits were returned and at least one further hit existed. */
    MaxHitsReached,
}

/**
 * Which files a search covers. Every scope names an absolute [root] directory; every path inside a
 * scope, and every [Hit.path], is workspace-relative and forward-slash separated on every OS.
 */
public sealed interface SearchScope {
    /** Absolute directory the search is rooted at; relative paths resolve against it. */
    public val root: Path

    /** Every candidate file under [root]. */
    public data class All(override val root: Path) : SearchScope {
        init {
            requireRoot(root)
        }
    }

    /**
     * Only candidates at, or under, the listed [paths]. A path that does not exist makes the whole
     * search [SearchOutcome.Failed]; a path that exists but is excluded by the file rules of
     * [PatternSubset] (ignored, hidden, binary, symlink) contributes no candidates.
     */
    public data class Paths(override val root: Path, val paths: List<String>) : SearchScope {
        init {
            requireRoot(root)
            require(paths.isNotEmpty()) { "SearchScope.Paths needs at least one path" }
            paths.forEach(::requireRelativePath)
        }
    }

    /** Only candidates whose relative path matches [glob] (Java glob syntax, forward slashes). */
    public data class Glob(override val root: Path, val glob: String) : SearchScope {
        init {
            requireRoot(root)
            require(glob.isNotBlank()) { "SearchScope.Glob needs a non-blank glob" }
            require('\\' !in glob) { "globs use forward slashes; got '$glob'" }
        }
    }
}

private fun requireRoot(root: Path) {
    require(root.isAbsolute) { "a search root must be absolute; got '$root'" }
}

private fun requireRelativePath(path: String) {
    require(path.isNotBlank()) { "a scope path must be non-blank" }
    require('\\' !in path) { "scope paths use forward slashes; got '$path'" }
    require(!path.startsWith("/")) { "scope paths are workspace-relative; got '$path'" }
    require(path.split('/').none { it == ".." }) { "scope paths may not escape the root; got '$path'" }
}

/**
 * One search. [budgetBytes] bounds the total UTF-8 size of the returned [Hit.text]; [maxHits]
 * bounds the number of hits; [since] keeps only files whose last-modified time is at or after it.
 */
public data class SearchRequest(
    val pattern: String,
    val mode: SearchMode,
    val scope: SearchScope,
    val budgetBytes: Long,
    val since: Instant? = null,
    val caseSensitive: Boolean = true,
    val maxHits: Int? = null,
) {
    init {
        require(pattern.isNotEmpty()) { "a search pattern may not be empty" }
        require(budgetBytes > 0) { "budgetBytes must be positive; got $budgetBytes" }
        require(maxHits == null || maxHits > 0) { "maxHits must be positive; got $maxHits" }
    }
}

/**
 * One matching line. [line] is 1-based; [column] is the 1-based UTF-16 index of the first match on
 * that line; [text] is the line without its `\n` or `\r\n` terminator.
 */
public data class Hit(
    val path: String,
    val line: Int,
    val column: Int?,
    val text: String,
) {
    init {
        require(path.isNotBlank()) { "a hit path may not be blank" }
        require('\\' !in path) { "hit paths use forward slashes; got '$path'" }
        require(line >= 1) { "line numbers are 1-based; got $line" }
        require(column == null || column >= 1) { "columns are 1-based; got $column" }
    }
}

/**
 * The hits a search produced plus the flags that describe how much of the candidate set it covered.
 *
 * [filesSearched] is the number of candidate files the backend was handed after the file rules were
 * applied. When [truncated] is true the backend stopped early, so it is the number of files
 * *selected*, not the number it managed to open; that definition is the only one both backends can
 * report identically, because `rg` cannot be asked how far it got before it was stopped.
 */
public data class Hits(
    val hits: List<Hit>,
    val scope: SearchScope,
    val complete: Boolean,
    val truncated: Boolean,
    val backend: SearchBackend,
    val filesSearched: Int?,
) {
    init {
        require(complete != truncated) { "a result is complete exactly when it is not truncated" }
        require(filesSearched == null || filesSearched >= 0) { "filesSearched may not be negative" }
    }
}

/**
 * The result of one search. Zero matches, an incomplete search, a failed search and a denied search
 * are four different outcomes and are never collapsed. [Unsupported] is the fifth: a pattern outside
 * the documented common subset is refused before either backend runs, so the two backends can never
 * silently differ on it (D-05).
 */
// §5.4 result envelope: "Zero matches, incomplete search, failed search and denied search are four
// different outcomes."
public sealed interface SearchOutcome {
    /** The search covered every candidate. [Hits.hits] may be empty: that is zero matches, not a failure. */
    public data class Found(val hits: Hits) : SearchOutcome {
        init {
            require(hits.complete && !hits.truncated) { "Found carries a complete, untruncated result" }
        }
    }

    /** The search stopped early; [hits] is a prefix of the full result in canonical order. */
    public data class Incomplete(val hits: Hits, val reason: IncompleteReason) : SearchOutcome {
        init {
            require(!hits.complete && hits.truncated) { "Incomplete carries a truncated result" }
        }
    }

    /** The search could not run or could not be trusted; no partial result is implied. */
    public data class Failed(val reason: String) : SearchOutcome

    /** The search was blocked by the filesystem on [paths]. */
    public data class Denied(val reason: String, val paths: List<String>) : SearchOutcome

    /** The pattern is outside the common subset; [reason] names the rule it violated. */
    public data class Unsupported(val reason: String) : SearchOutcome
}

/** One search engine. [backend] is what [Hits.backend] will report. */
public interface Search {
    /** Which engine this instance uses. */
    public val backend: SearchBackend

    /** Runs [request] to completion, to its budget, or to its first blocking condition. */
    public fun find(request: SearchRequest): SearchOutcome
}

/** Probes whether the `rg` executable can be used. */
public fun interface RipgrepProbe {
    /** True when `rg --version` succeeds. */
    public fun ripgrepAvailable(): Boolean
}

/** Constructs [Search] instances and decides, explicitly, which backend to use. */
public object Searches {
    private const val EXECUTABLE = "rg"

    private val probed: Boolean by lazy { probe(EXECUTABLE) }

    /** The ripgrep backend when `rg --version` succeeds (probed once per JVM), else the JVM backend. */
    @JvmStatic
    public fun available(): Search = auto(RipgrepProbe { probed })

    /** The ripgrep backend when [probe] says so, else the JVM backend. */
    @JvmStatic
    public fun auto(probe: RipgrepProbe): Search = if (probe.ripgrepAvailable()) ripgrep() else jvm()

    /** The ripgrep backend using `rg` from `PATH`. */
    @JvmStatic
    public fun ripgrep(): Search = ripgrep(EXECUTABLE)

    /** The ripgrep backend using [executable]. */
    @JvmStatic
    public fun ripgrep(executable: String): Search = RipgrepSearch(executable)

    /** The in-process JVM backend. */
    @JvmStatic
    public fun jvm(): Search = JvmSearch()

    /** Whether `rg --version` succeeded; probed once per JVM and cached. */
    @JvmStatic
    public fun ripgrepAvailable(): Boolean = probed

    private fun probe(executable: String): Boolean =
        try {
            val process = ProcessBuilder(executable, "--version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.readBytes() }
            process.waitFor() == 0
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}

/**
 * The documented common subset both backends implement (D-05). One validator decides, so a refusal
 * is identical whichever backend runs.
 *
 * **Literal mode** takes the pattern as a fixed string (`rg -F` / `Pattern.quote`) and is never
 * refused.
 *
 * **Regex mode** accepts: literal characters; `.`; bracket classes `[...]` with ranges `a-z`,
 * leading negation `^`, a leading or trailing literal `-`, and the class escapes below; the classes
 * `\d \D \w \W \s \S`; the zero-width assertions `\b \B`; the anchors `^ $`, which are line-oriented
 * because both backends match one terminator-stripped line at a time; capturing groups `( )` and
 * non-capturing groups `(?: )`; alternation `|`; the quantifiers `* + ? {m} {m,} {m,n}`, each with
 * an optional lazy `?` suffix; the escape `\t`; and `\` before any ASCII punctuation character,
 * which is that character taken literally. `(?i)` is accepted only as the whole leading prefix and
 * is then handled as [SearchRequest.caseSensitive]` = false`.
 *
 * **Regex mode refuses**, naming the rule: backreferences and octal escapes (`\1`); lookaround
 * (`(?=`, `(?!`, `(?<=`, `(?<!`); named groups (`(?<name>`, `(?P<name>`); atomic groups (`(?>`);
 * every inline flag other than a leading `(?i)`, including `(?i:`; possessive quantifiers (`a*+`)
 * and stacked quantifiers (`a**`, `a?{2}`) — `rg` reads `a*+` as repetition of `a*` while the JVM
 * reads it as possessive, which is a real divergence, verified; every other `\`+letter escape,
 * which covers `\p{…} \P{…} \A \z \Z \G \Q \E \x \u \n \r \f \v \a \e \c \h \R \X \N \k`; `\`
 * before a digit, before a non-ASCII character, or at the end of the pattern; class intersection
 * `&&`, class difference `--` and class symmetric difference `~~`; nested classes and POSIX classes
 * (`[a-z[0-9]]`, `[[:alpha:]]`); an empty class `[]`; a range whose endpoint is not a single
 * character (`[\d-z]`); a `{` that does not open a well-formed counted quantifier, or one whose
 * bounds are reversed (`{2,1}`); a quantifier with nothing to quantify (`*a`, `^*`); and unbalanced
 * parentheses or an unterminated class.
 *
 * **Case folding.** `caseSensitive = false` maps to `rg -i` and to
 * `CASE_INSENSITIVE or UNICODE_CASE`. ripgrep uses Unicode simple case folding and the JVM uses its
 * own two-way `Character.toUpperCase`/`toLowerCase` comparison; the two agree on ASCII and
 * Latin-1, which is the range this subset guarantees and the range the tests cover. Beyond
 * Latin-1 the backends may differ and no guarantee is made.
 *
 * **Files.** Both backends are handed the same candidate list, built once: inside a git repository
 * it is `git ls-files -z --cached --others --exclude-standard`, so the ignore rules are exactly the
 * ones git itself resolves — `.gitignore` files, `.git/info/exclude` and the user's
 * `core.excludesFile` — which is what ripgrep's default behaviour approximates. ripgrep's own
 * `.ignore` and `.rgignore` files are deliberately **not** honoured, because only one of the two
 * backends could read them. Outside a repository the list is a tree walk that skips `.git` and
 * applies no ignore rules at all, so an ignored file becomes searchable. Then, identically
 * for both: entries with a leading-dot path segment are skipped, symlinks and non-regular files are
 * skipped and never followed, a file with a NUL byte in its first 8 KiB is skipped as binary, and
 * [SearchRequest.since] keeps only files modified at or after it. Content is decoded as UTF-8 with
 * replacement, `\n` and `\r\n` both terminate a line, and [Hit.text] excludes the terminator. The
 * candidate list is sorted by relative path so both backends truncate at the same place.
 */
public object PatternSubset {

    /** The rule [pattern] violates, or null when it is inside the subset. */
    public fun check(pattern: String, mode: SearchMode): SearchOutcome.Unsupported? {
        if (pattern.isEmpty()) return refuse("an empty pattern is not searchable")
        if (mode == SearchMode.Literal) return null
        val body = if (pattern.startsWith(CASE_INSENSITIVE_PREFIX)) {
            pattern.substring(CASE_INSENSITIVE_PREFIX.length)
        } else {
            pattern
        }
        if (body.isEmpty()) return refuse("'$CASE_INSENSITIVE_PREFIX' must be followed by a pattern")
        return scan(body)
    }

    internal const val CASE_INSENSITIVE_PREFIX: String = "(?i)"

    private const val CLASS_ESCAPES = "dDwWsS"

    private fun refuse(reason: String): SearchOutcome.Unsupported = SearchOutcome.Unsupported(reason)

    private fun scan(pattern: String): SearchOutcome.Unsupported? {
        val n = pattern.length
        var i = 0
        var depth = 0
        var quantifiable = false
        while (i < n) {
            when (val c = pattern[i]) {
                '\\' -> {
                    val escape = escapeAt(pattern, i) ?: return refuse(escapeRefusal(pattern, i))
                    // \b and \B are zero-width: quantifying them is accepted by the JVM and rejected
                    // by rg, so the subset never allows a quantifier after them.
                    quantifiable = escape != 'b' && escape != 'B'
                    i += 2
                }

                '[' -> {
                    val end = scanClass(pattern, i)
                    if (end is ClassScan.Refused) return refuse(end.reason)
                    i = (end as ClassScan.Ok).next
                    quantifiable = true
                }

                '(' -> {
                    when {
                        pattern.startsWith("(?:", i) -> i += 3
                        pattern.startsWith("(?", i) ->
                            return refuse(
                                "'(?' groups are limited to '(?:' and a leading '$CASE_INSENSITIVE_PREFIX'; " +
                                    "lookaround, named groups, atomic groups and inline flags are outside the subset",
                            )

                        else -> i += 1
                    }
                    depth++
                    quantifiable = false
                }

                ')' -> {
                    if (depth == 0) return refuse("unbalanced ')' at index $i")
                    depth--
                    i++
                    quantifiable = true
                }

                '|' -> {
                    i++
                    quantifiable = false
                }

                '^', '$' -> {
                    i++
                    quantifiable = false
                }

                '.' -> {
                    i++
                    quantifiable = true
                }

                '*', '+', '?' -> {
                    if (!quantifiable) return refuse("the quantifier '$c' at index $i has nothing to quantify")
                    i = quantifierSuffix(pattern, i + 1) ?: return refuse(stackedRefusal(pattern, i))
                    quantifiable = false
                }

                '{' -> {
                    val counted = countedQuantifier(pattern, i)
                        ?: return refuse(
                            "'{' at index $i does not open a counted quantifier '{m}', '{m,}' or '{m,n}' " +
                                "with m <= n; escape it as '\\{' to match a literal brace",
                        )
                    if (!quantifiable) return refuse("the quantifier at index $i has nothing to quantify")
                    i = quantifierSuffix(pattern, counted) ?: return refuse(stackedRefusal(pattern, i))
                    quantifiable = false
                }

                else -> {
                    i++
                    quantifiable = true
                }
            }
        }
        if (depth != 0) return refuse("unbalanced '(': $depth group(s) left open")
        return null
    }

    /** The escape character at [i], or null when `\`+that character is outside the subset. */
    private fun escapeAt(pattern: String, i: Int): Char? {
        if (i + 1 >= pattern.length) return null
        val e = pattern[i + 1]
        return when {
            e in CLASS_ESCAPES || e == 'b' || e == 'B' || e == 't' -> e
            isAsciiLetter(e) || isAsciiDigit(e) -> null
            isAsciiPunctuation(e) -> e
            else -> null
        }
    }

    private fun escapeRefusal(pattern: String, i: Int): String {
        if (i + 1 >= pattern.length) return "a trailing '\\' at index $i is not a complete escape"
        val e = pattern[i + 1]
        return when {
            isAsciiDigit(e) -> "backreferences and octal escapes ('\\$e' at index $i) are outside the subset"
            isAsciiLetter(e) ->
                "the escape '\\$e' at index $i is outside the subset; only '\\d \\D \\w \\W \\s \\S \\b \\B \\t' " +
                    "and '\\' before ASCII punctuation are supported"

            else -> "'\\' at index $i may only precede ASCII punctuation or a supported escape letter"
        }
    }

    private fun stackedRefusal(pattern: String, i: Int): String =
        "the quantifier at index $i is followed by another quantifier; possessive quantifiers ('a*+') and " +
            "stacked quantifiers ('a**', 'a?{2}') are outside the subset because rg and the JVM read them " +
            "differently (pattern '$pattern')"

    /** Consumes an optional lazy `?`; null when a possessive or stacked quantifier follows. */
    private fun quantifierSuffix(pattern: String, from: Int): Int? {
        var i = from
        if (i < pattern.length && pattern[i] == '?') i++
        if (i < pattern.length && pattern[i] in "*+?{") return null
        return i
    }

    /** The index just past a well-formed `{m}` / `{m,}` / `{m,n}` at [start], else null. */
    private fun countedQuantifier(pattern: String, start: Int): Int? {
        var i = start + 1
        val minStart = i
        while (i < pattern.length && isAsciiDigit(pattern[i])) i++
        if (i == minStart) return null
        val min = pattern.substring(minStart, i).toLongOrNull() ?: return null
        if (i < pattern.length && pattern[i] == '}') return i + 1
        if (i >= pattern.length || pattern[i] != ',') return null
        i++
        val maxStart = i
        while (i < pattern.length && isAsciiDigit(pattern[i])) i++
        if (i >= pattern.length || pattern[i] != '}') return null
        if (i > maxStart) {
            val max = pattern.substring(maxStart, i).toLongOrNull() ?: return null
            if (max < min) return null
        }
        return i + 1
    }

    private sealed interface ClassScan {
        data class Ok(val next: Int) : ClassScan
        data class Refused(val reason: String) : ClassScan
    }

    private fun scanClass(pattern: String, start: Int): ClassScan {
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') i++
        var members = 0
        while (i < pattern.length) {
            when {
                pattern[i] == ']' -> {
                    if (members == 0) {
                        return ClassScan.Refused(
                            "the character class at index $start is empty; escape ']' as '\\]' to match it",
                        )
                    }
                    return ClassScan.Ok(i + 1)
                }

                pattern[i] == '[' ->
                    return ClassScan.Refused(
                        "nested character classes and POSIX classes ('[[:alpha:]]') are outside the subset; " +
                            "escape '[' as '\\[' at index $i",
                    )

                pattern.startsWith("&&", i) ->
                    return ClassScan.Refused("class intersection '&&' at index $i is outside the subset")

                pattern.startsWith("--", i) ->
                    return ClassScan.Refused("class difference '--' at index $i is outside the subset")

                pattern.startsWith("~~", i) ->
                    return ClassScan.Refused("class symmetric difference '~~' at index $i is outside the subset")

                else -> {
                    val item = classItem(pattern, i) ?: return ClassScan.Refused(classItemRefusal(pattern, i))
                    i = item.next
                    members++
                    // A range: both endpoints must be single characters, otherwise rg refuses
                    // '[\d-z]' while the JVM accepts it.
                    if (i < pattern.length && pattern[i] == '-' && i + 1 < pattern.length && pattern[i + 1] != ']') {
                        if (!item.single) {
                            return ClassScan.Refused(
                                "a character range at index $i must start with a single character, not a class escape",
                            )
                        }
                        val hi = classItem(pattern, i + 1) ?: return ClassScan.Refused(classItemRefusal(pattern, i + 1))
                        if (!hi.single) {
                            return ClassScan.Refused(
                                "a character range at index $i must end with a single character, not a class escape",
                            )
                        }
                        i = hi.next
                    }
                }
            }
        }
        return ClassScan.Refused("the character class opened at index $start is never closed")
    }

    private data class ClassItem(val next: Int, val single: Boolean)

    private fun classItem(pattern: String, i: Int): ClassItem? {
        if (pattern[i] != '\\') return ClassItem(i + 1, single = true)
        if (i + 1 >= pattern.length) return null
        val e = pattern[i + 1]
        return when {
            e in CLASS_ESCAPES -> ClassItem(i + 2, single = false)
            e == 't' -> ClassItem(i + 2, single = true)
            isAsciiLetter(e) || isAsciiDigit(e) -> null
            isAsciiPunctuation(e) -> ClassItem(i + 2, single = true)
            else -> null
        }
    }

    private fun classItemRefusal(pattern: String, i: Int): String {
        if (i + 1 >= pattern.length) return "a trailing '\\' at index $i is not a complete escape"
        val e = pattern[i + 1]
        return when {
            isAsciiDigit(e) -> "backreferences and octal escapes ('\\$e' at index $i) are outside the subset"
            e == 'b' || e == 'B' ->
                "'\\$e' at index $i means a backspace inside a JVM character class and something else to rg; " +
                    "it is outside the subset"

            isAsciiLetter(e) ->
                "the escape '\\$e' at index $i is outside the subset inside a character class; " +
                    "only '\\d \\D \\w \\W \\s \\S \\t' and '\\' before ASCII punctuation are supported"

            else -> "'\\' at index $i may only precede ASCII punctuation or a supported escape letter"
        }
    }

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    private fun isAsciiPunctuation(c: Char): Boolean =
        c.code in 0x21..0x2F || c.code in 0x3A..0x40 || c.code in 0x5B..0x60 || c.code in 0x7B..0x7E
}

/** The pattern both backends compile, after the leading `(?i)` has been folded into the flags. */
internal data class NormalizedPattern(val body: String, val caseSensitive: Boolean)

internal fun SearchRequest.normalizedPattern(): NormalizedPattern =
    if (mode == SearchMode.Regex && pattern.startsWith(PatternSubset.CASE_INSENSITIVE_PREFIX)) {
        NormalizedPattern(pattern.substring(PatternSubset.CASE_INSENSITIVE_PREFIX.length), caseSensitive = false)
    } else {
        NormalizedPattern(pattern, caseSensitive)
    }

/** One file both backends will search, with its canonical workspace-relative path. */
internal data class Candidate(val relPath: String, val absPath: Path)

/** The shared file-selection step; its result is what makes the two backends comparable. */
internal sealed interface CandidateSet {
    data class Selected(val files: List<Candidate>) : CandidateSet
    data class Failed(val reason: String) : CandidateSet
    data class Denied(val reason: String, val paths: List<String>) : CandidateSet
}

internal object Candidates {
    private const val BINARY_PROBE_BYTES = 8 * 1024
    private const val GIT_EXECUTABLE = "git"

    fun resolve(request: SearchRequest): CandidateSet {
        val root = request.scope.root.normalize()
        if (!Files.isDirectory(root)) return CandidateSet.Failed("search root is not a directory: $root")

        val scoped = when (val scope = request.scope) {
            is SearchScope.All -> null
            is SearchScope.Glob -> null
            is SearchScope.Paths -> {
                val missing = scope.paths.filterNot { Files.exists(root.resolve(it)) }
                if (missing.isNotEmpty()) {
                    return CandidateSet.Failed("scope path does not exist: ${missing.joinToString(", ")}")
                }
                scope.paths.toSet()
            }
        }

        val globMatcher = (request.scope as? SearchScope.Glob)
            ?.let { FileSystems.getDefault().getPathMatcher("glob:${it.glob}") }

        val base = gitListFiles(root) ?: walk(root)
        val denied = ArrayList<String>()
        val selected = ArrayList<Candidate>()
        for (rel in base) {
            if (hasHiddenSegment(rel)) continue
            if (scoped != null && scoped.none { rel == it || rel.startsWith("$it/") }) continue
            if (globMatcher != null && !globMatcher.matches(Path.of(rel))) continue
            val abs = root.resolve(rel)
            val attributes = try {
                Files.readAttributes(abs, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                continue
            } catch (_: AccessDeniedException) {
                denied += rel
                continue
            } catch (_: IOException) {
                continue
            }
            if (attributes.isSymbolicLink || !attributes.isRegularFile) continue
            if (request.since != null && attributes.lastModifiedTime().toInstant() < request.since) continue
            when (isBinary(abs)) {
                BinaryProbe.Binary -> continue
                BinaryProbe.Denied -> {
                    denied += rel
                    continue
                }

                BinaryProbe.Gone -> continue
                BinaryProbe.Text -> selected += Candidate(rel, abs)
            }
        }
        if (denied.isNotEmpty()) {
            return CandidateSet.Denied("the filesystem denied access to ${denied.size} file(s)", denied.sorted())
        }
        selected.sortBy { it.relPath }
        return CandidateSet.Selected(selected)
    }

    private fun hasHiddenSegment(rel: String): Boolean = rel.split('/').any { it.startsWith(".") }

    private enum class BinaryProbe { Text, Binary, Denied, Gone }

    private fun isBinary(path: Path): BinaryProbe =
        try {
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(BINARY_PROBE_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                if ((0 until read).any { buffer[it] == 0.toByte() }) BinaryProbe.Binary else BinaryProbe.Text
            }
        } catch (_: AccessDeniedException) {
            BinaryProbe.Denied
        } catch (_: NoSuchFileException) {
            BinaryProbe.Gone
        } catch (_: IOException) {
            BinaryProbe.Gone
        }

    /**
     * The files git itself considers part of the tree, which is what ripgrep's default ignore
     * handling approximates. Null when [root] is not in a git repository, or git is unavailable.
     */
    private fun gitListFiles(root: Path): List<String>? {
        val output = try {
            val process = ProcessBuilder(
                GIT_EXECUTABLE, "ls-files", "-z", "--cached", "--others", "--exclude-standard",
            ).directory(root.toFile()).start()
            val stderr = drainAsync(process)
            val bytes = process.inputStream.use { it.readBytes() }
            val exit = process.waitFor()
            stderr.join()
            if (exit != 0) return null
            String(bytes, UTF_8)
        } catch (_: IOException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return output.split(' ').filter { it.isNotEmpty() }.distinct()
    }

    private fun drainAsync(process: Process): Thread =
        Thread { process.errorStream.use { it.readBytes() } }
            .apply {
                isDaemon = true
                start()
            }

    private fun walk(root: Path): List<String> {
        val files = ArrayList<String>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString().startsWith(".")) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isSymbolicLink && attrs.isRegularFile) {
                        files += root.relativize(file).joinToString("/")
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return files
    }
}

/**
 * Applies [SearchRequest.budgetBytes] and [SearchRequest.maxHits] to a stream of hits. Both backends
 * feed it in the same canonical order, so both truncate at exactly the same hit.
 */
internal class HitCollector(private val budgetBytes: Long, private val maxHits: Int?) {
    private val hits = ArrayList<Hit>()
    private var usedBytes = 0L

    /** Why the search stopped, or null while it may continue. */
    var truncation: IncompleteReason? = null
        private set

    /** Records [hit]; returns false once the search must stop. */
    fun offer(hit: Hit): Boolean {
        if (truncation != null) return false
        if (maxHits != null && hits.size >= maxHits) {
            truncation = IncompleteReason.MaxHitsReached
            return false
        }
        val size = hit.text.toByteArray(UTF_8).size.toLong()
        if (usedBytes + size > budgetBytes) {
            truncation = IncompleteReason.BudgetBytesExceeded
            return false
        }
        usedBytes += size
        hits += hit
        return true
    }

    fun outcome(scope: SearchScope, backend: SearchBackend, filesSearched: Int): SearchOutcome {
        val reason = truncation
        val result = Hits(
            hits = hits.toList(),
            scope = scope,
            complete = reason == null,
            truncated = reason != null,
            backend = backend,
            filesSearched = filesSearched,
        )
        return if (reason == null) SearchOutcome.Found(result) else SearchOutcome.Incomplete(result, reason)
    }
}

/** Drops the `\n` or `\r\n` terminator a backend reported with a line. */
internal fun stripLineTerminator(line: String): String = when {
    line.endsWith("\r\n") -> line.dropLast(2)
    line.endsWith("\n") -> line.dropLast(1)
    else -> line
}
