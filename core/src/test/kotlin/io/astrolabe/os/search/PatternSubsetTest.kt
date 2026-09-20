package io.astrolabe.os.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one validator both backends share, and the proof that both treat the same patterns alike. */
class PatternSubsetTest {

    @ParameterizedTest
    @MethodSource("insideTheSubset")
    fun `patterns inside the subset are accepted`(pattern: String) {
        assertNull(PatternSubset.check(pattern, SearchMode.Regex), "'$pattern' should be inside the subset")
    }

    /** The validator is only useful if everything it accepts really does compile the same in both engines. */
    @ParameterizedTest
    @MethodSource("insideTheSubset")
    fun `an accepted pattern runs and matches identically in both engines`(pattern: String) {
        val request = SearchRequest(pattern, SearchMode.Regex, SearchScope.All(root), BUDGET)
        val jvm = Searches.jvm().find(request)
        assertTrue(jvm is SearchOutcome.Found, "the JVM backend did not run '$pattern': $jvm")
        assumeTrue(Searches.ripgrepAvailable(), "rg is not on PATH")
        val ripgrep = Searches.ripgrep().find(request)
        assertTrue(ripgrep is SearchOutcome.Found, "ripgrep did not run '$pattern': $ripgrep")
        assertEquals(jvm.hits.rendered(), ripgrep.hits.rendered(), "'$pattern' matched differently")
    }

    @ParameterizedTest
    @MethodSource("outsideTheSubset")
    fun `patterns outside the subset are refused with the rule they violate`(pattern: String) {
        val refusal = PatternSubset.check(pattern, SearchMode.Regex)
        assertNotNull(refusal, "'$pattern' should be outside the subset")
        assertTrue(refusal.reason.isNotBlank(), "'$pattern' was refused without naming a rule")
    }

    @ParameterizedTest
    @MethodSource("outsideTheSubset")
    fun `both backends refuse an unsupported pattern with the same reason`(pattern: String) {
        val request = SearchRequest(pattern, SearchMode.Regex, SearchScope.All(root), BUDGET)
        val expected = PatternSubset.check(pattern, SearchMode.Regex)
        assertNotNull(expected)
        assertEquals(expected, Searches.jvm().find(request), "the JVM backend did not refuse '$pattern'")
        assumeTrue(Searches.ripgrepAvailable(), "rg is not on PATH")
        assertEquals(expected, Searches.ripgrep().find(request), "ripgrep did not refuse '$pattern'")
    }

    @ParameterizedTest
    @ValueSource(strings = ["(?=x)", "a\\1b", "[]", "\\p{L}", "a*+", "(?<name>a)"])
    fun `literal mode never refuses a pattern`(pattern: String) {
        assertNull(PatternSubset.check(pattern, SearchMode.Literal), "literal mode refused '$pattern'")
    }

    @Test
    fun `an empty pattern is refused in both modes`() {
        assertNotNull(PatternSubset.check("", SearchMode.Regex))
        assertNotNull(PatternSubset.check("", SearchMode.Literal))
        assertNotNull(PatternSubset.check("(?i)", SearchMode.Regex))
    }

    @Test
    fun `record invariants are checked at construction`() {
        assertRejected { SearchRequest("", SearchMode.Literal, SearchScope.All(root), BUDGET) }
        assertRejected { SearchRequest("a", SearchMode.Literal, SearchScope.All(root), budgetBytes = 0) }
        assertRejected { SearchRequest("a", SearchMode.Literal, SearchScope.All(root), BUDGET, maxHits = 0) }
        assertRejected { SearchScope.All(Path.of("relative")) }
        assertRejected { SearchScope.Paths(root, emptyList()) }
        assertRejected { SearchScope.Paths(root, listOf("../escape")) }
        assertRejected { SearchScope.Paths(root, listOf("src\\a.txt")) }
        assertRejected { SearchScope.Paths(root, listOf("/absolute")) }
        assertRejected { SearchScope.Glob(root, "src\\*") }
        assertRejected { Hit("a.txt", line = 0, column = 1, text = "x") }
        assertRejected { Hit("a\\b.txt", line = 1, column = 1, text = "x") }
        assertRejected { hits(complete = true, truncated = true) }
        assertRejected { SearchOutcome.Found(hits(complete = false, truncated = true)) }
        assertRejected {
            SearchOutcome.Incomplete(hits(complete = true, truncated = false), IncompleteReason.MaxHitsReached)
        }
    }

    private fun hits(complete: Boolean, truncated: Boolean): Hits = Hits(
        hits = emptyList(),
        scope = SearchScope.All(root),
        complete = complete,
        truncated = truncated,
        backend = SearchBackend.Jvm,
        filesSearched = 0,
    )

    private fun assertRejected(block: () -> Unit) {
        val rejected = try {
            block()
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(rejected, "the invariant was not checked at construction")
    }

    companion object {
        private const val BUDGET = 1L shl 20

        private lateinit var root: Path

        @JvmStatic
        fun insideTheSubset(): List<String> = listOf(
            "word", "a.c", "[a-z]", "[^a-z]", "[a-]", "[-a]", "[\\d\\w]", "[\\]a]", "[a\\-z]",
            "\\d+", "\\w{2}", "\\w{2,}", "\\w{2,3}", "\\s*?", "a??", "a+?", "^a", "c$", "(a|b)",
            "(?:ab)+", "a\\.c", "a\\-c", "a\\/c", "a\\\\c", "x\\ty", "(?i)WORD", "\\bword\\b",
            "\\Bord", "(a)(b)|c", "a{0,}", "[0-9]{1,3}", "a}b", "a]b", "\\\$\\^\\*", "(?:(a|b)c)+",
            "[^ ]+", ".*", "0[0-9]*3",
        )

        @JvmStatic
        fun outsideTheSubset(): List<String> = listOf(
            // backreferences and octal escapes
            "a\\1b", "\\0",
            // lookaround
            "(?=x)", "(?!x)", "(?<=x)", "(?<!x)", "a(?=b)",
            // named and atomic groups
            "(?<name>a)", "(?P<name>a)", "(?>a)",
            // inline flags other than a leading (?i)
            "(?m)^a", "(?s).", "(?x) a", "(?i:x)", "a(?i)b", "(?U)a",
            // possessive and stacked quantifiers
            "a*+", "a++", "a?+", "a**", "a?{2}", "a{2}{3}", "a{2}+",
            // quantifiers with nothing to quantify, malformed counts
            "*a", "+a", "?a", "^*", "a{", "a{2", "a{,3}", "a{2,1}", "{2}",
            // escapes outside the subset
            "\\p{L}", "\\P{L}", "\\A", "\\z", "\\Z", "\\G", "\\Qa.b\\E", "a\\x41", "a\\u0041",
            "a\\n", "a\\r", "a\\f", "a\\v", "a\\e", "a\\h", "a\\R", "a\\X", "a\\N", "a\\cA", "a\\",
            // character-class constructs outside the subset
            "[[:alpha:]]", "[a-z&&\\d]", "[a-z[0-9]]", "[a-z--b]", "[a-z~~b]", "[]", "[^]",
            "[\\d-z]", "[a-\\d]", "[abc", "[\\b]", "[a\\1]",
            // structure
            "(a", "a)", "((a)",
        )

        @JvmStatic
        @BeforeAll
        fun createRoot() {
            root = Files.createTempDirectory("astrolabe-subset-").toRealPath()
            Files.write(
                root.resolve("probe.txt"),
                "a.c abc a-c a\\c x\ty a}b a]b 0123 word\nwords ab c \$^* A-Z\n".toByteArray(UTF_8),
            )
        }

        @JvmStatic
        @AfterAll
        fun deleteRoot() {
            if (!::root.isInitialized) return
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
