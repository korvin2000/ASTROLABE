package io.astrolabe.os.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every subset feature run through both backends on the same fixture repository, asserting the same
 * [Hit] list and the same flags. The JVM half always runs and always asserts concrete values; the
 * ripgrep comparison runs when `rg` is installed and, when it is not, the test is reported skipped
 * from [reportSkippedRipgrep] rather than passing silently.
 */
class SearchBackendParityTest {

    private var ripgrepSkipped = false

    // ---- file rules -------------------------------------------------------------------------

    @Test
    fun `ignored, hidden and binary files are excluded from both backends`() {
        val hits = bothBackends(request("beta", SearchMode.Literal)).requireHits()
        assertEquals(
            listOf(
                "src/a.txt:1:7:alpha beta",
                "src/a.txt:3:1:beta beta",
                "src/crlf.txt:1:7:alpha beta",
                "src/nested/deep.txt:1:8:nested beta",
                "uni.txt:1:12:café naïve beta",
            ),
            hits.rendered(),
        )
        assertEquals(fixture.gitCandidates.size, hits.filesSearched)
        val excluded = setOf("notes.log", "build/out.txt", ".secret", "bin.dat", ".gitignore")
        assertTrue(hits.hits.none { it.path in excluded }, "an excluded file was searched: ${hits.rendered()}")
    }

    @Test
    fun `a CRLF line is reported without its carriage return and still anchors`() {
        val hits = bothBackends(request("beta$", SearchMode.Regex)).requireHits()
        assertEquals(
            listOf(
                "src/a.txt:1:7:alpha beta",
                "src/a.txt:3:6:beta beta",
                "src/crlf.txt:1:7:alpha beta",
                "src/nested/deep.txt:1:8:nested beta",
                "uni.txt:1:12:café naïve beta",
            ),
            hits.rendered(),
        )
        assertTrue(hits.hits.none { '\r' in it.text }, "a hit text still carries a carriage return")
    }

    @Test
    fun `without a git repository there are no ignore rules and both backends still agree`() {
        val plain = SearchFixture.plainTree()
        try {
            val hits = bothBackends(
                SearchRequest("beta", SearchMode.Literal, SearchScope.All(plain.root), BUDGET),
            ).requireHits()
            assertEquals(plain.plainCandidates.size, hits.filesSearched)
            assertEquals(
                listOf("build/out.txt:1:9:ignored beta", "notes.log:1:9:ignored beta"),
                hits.rendered().filter { it.startsWith("build/") || it.startsWith("notes.log") },
            )
            assertTrue(hits.hits.none { it.path == ".secret" || it.path == "bin.dat" })
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `a symlink is never followed by either backend`() {
        val plain = SearchFixture.plainTree()
        try {
            assumeTrue(plain.symlinkOrNull("src/a.txt", "link.txt") != null, "symlinks are not creatable here")
            val hits = bothBackends(
                SearchRequest("beta", SearchMode.Literal, SearchScope.All(plain.root), BUDGET),
            ).requireHits()
            assertTrue(hits.hits.none { it.path == "link.txt" }, "the symlink was searched: ${hits.rendered()}")
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `an unreadable file is a denial, not a failure`() {
        val plain = SearchFixture.plainTree()
        try {
            val target = plain.root.resolve("src/a.txt")
            assumeTrue(
                Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null,
                "POSIX permissions are not available on this filesystem",
            )
            val original = Files.getPosixFilePermissions(target)
            try {
                Files.setPosixFilePermissions(target, emptySet())
                assumeTrue(!Files.isReadable(target), "this user can read a file with no permissions")
                val outcome = bothBackends(
                    SearchRequest("beta", SearchMode.Literal, SearchScope.All(plain.root), BUDGET),
                )
                val denied = outcome as? SearchOutcome.Denied ?: error("expected Denied, got $outcome")
                assertEquals(listOf("src/a.txt"), denied.paths)
            } finally {
                Files.setPosixFilePermissions(target, original)
            }
        } finally {
            plain.delete()
        }
    }

    @Test
    fun `a candidate list longer than one command line is chunked and stays ordered`() {
        val root = Files.createTempDirectory("astrolabe-search-chunk-").toRealPath()
        try {
            val names = (0 until 600).map { "chunked-candidate-file-with-a-long-name-%04d.txt".format(it) }
            names.forEach { Files.write(root.resolve(it), "chunk beta marker\n".toByteArray(UTF_8)) }

            val complete = bothBackends(
                SearchRequest("beta", SearchMode.Literal, SearchScope.All(root), BUDGET),
            ).requireHits()
            assertEquals(names.sorted(), complete.hits.map { it.path })
            assertEquals(names.size, complete.filesSearched)
            assertTrue(complete.complete)

            val capped = bothBackends(
                SearchRequest("beta", SearchMode.Literal, SearchScope.All(root), BUDGET, maxHits = 300),
            )
            val incomplete = capped as? SearchOutcome.Incomplete ?: error("expected Incomplete, got $capped")
            assertEquals(IncompleteReason.MaxHitsReached, incomplete.reason)
            assertEquals(names.sorted().take(300), incomplete.hits.hits.map { it.path })
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `since keeps only files modified at or after it`() {
        val dated = SearchFixture.gitRepo()
        try {
            val old = FileTime.from(Instant.parse("2001-01-01T00:00:00Z"))
            dated.gitCandidates.forEach { Files.setLastModifiedTime(dated.root.resolve(it), old) }
            Files.setLastModifiedTime(
                dated.root.resolve("src/a.txt"),
                FileTime.from(Instant.parse("2021-01-01T00:00:00Z")),
            )
            val hits = bothBackends(
                SearchRequest(
                    pattern = "beta",
                    mode = SearchMode.Literal,
                    scope = SearchScope.All(dated.root),
                    budgetBytes = BUDGET,
                    since = Instant.parse("2010-01-01T00:00:00Z"),
                ),
            ).requireHits()
            assertEquals(listOf("src/a.txt:1:7:alpha beta", "src/a.txt:3:1:beta beta"), hits.rendered())
            assertEquals(1, hits.filesSearched)
        } finally {
            dated.delete()
        }
    }

    // ---- regex subset features --------------------------------------------------------------

    @Test
    fun `case insensitive matching agrees on ASCII and Latin-1`() {
        assertEquals(
            listOf("uni.txt:1:1:café naïve beta", "uni.txt:2:1:CAFÉ NAÏVE BETA"),
            bothBackends(
                SearchRequest("café", SearchMode.Literal, all(), BUDGET, caseSensitive = false),
            ).requireHits().rendered(),
        )
        assertEquals(
            listOf("uni.txt:1:6:café naïve beta", "uni.txt:2:6:CAFÉ NAÏVE BETA"),
            bothBackends(
                SearchRequest("naÏve", SearchMode.Regex, all(), BUDGET, caseSensitive = false),
            ).requireHits().rendered(),
        )
    }

    @Test
    fun `a leading question-mark-i flag is the same as caseSensitive false`() {
        val flagged = bothBackends(request("(?i)beta", SearchMode.Regex)).requireHits()
        val explicit = bothBackends(
            SearchRequest("beta", SearchMode.Regex, all(), BUDGET, caseSensitive = false),
        ).requireHits()
        assertEquals(explicit.hits, flagged.hits)
        assertEquals(
            listOf(
                "src/a.txt:1:7:alpha beta",
                "src/a.txt:2:1:BETA gamma",
                "src/a.txt:3:1:beta beta",
                "src/crlf.txt:1:7:alpha beta",
                "src/crlf.txt:2:1:BETA gamma",
                "src/nested/deep.txt:1:8:nested beta",
                "uni.txt:1:12:café naïve beta",
                "uni.txt:2:12:CAFÉ NAÏVE BETA",
            ),
            flagged.rendered(),
        )
    }

    @Test
    fun `bracket classes, ranges, negation and shorthand classes agree`() {
        assertEquals(listOf("numbers.txt:1:2:a1 b22 c333"), numbers("[0-9]+"))
        assertEquals(listOf("numbers.txt:1:2:a1 b22 c333"), numbers("[\\d]"))
        assertEquals(
            listOf("numbers.txt:1:1:a1 b22 c333", "numbers.txt:2:1:no digits here"),
            numbers("[^0-9 ]"),
        )
        assertEquals(listOf("numbers.txt:1:1:a1 b22 c333"), numbers("[a-c]\\d"))
        assertEquals(listOf("numbers.txt:1:1:a1 b22 c333"), numbers("\\w\\d\\s"))
        assertEquals(listOf("numbers.txt:2:2:no digits here"), numbers("o\\sd"))
    }

    @Test
    fun `anchors are line oriented`() {
        assertEquals(
            listOf("src/a.txt:3:1:beta beta"),
            bothBackends(request("^beta", SearchMode.Regex)).requireHits().rendered(),
        )
        assertEquals(listOf("numbers.txt:2:1:no digits here"), numbers("^no digits here$"))
        assertEquals(listOf("numbers.txt:1:1:a1 b22 c333"), numbers("^a"))
    }

    @Test
    fun `alternation and groups agree`() {
        assertEquals(
            listOf(
                "src/a.txt:1:1:alpha beta",
                "src/crlf.txt:1:1:alpha beta",
                "src/nested/deep.txt:1:1:nested beta",
            ),
            bothBackends(request("(alpha|nested)", SearchMode.Regex)).requireHits().rendered(),
        )
        assertEquals(
            listOf("src/a.txt:1:1:alpha beta", "src/crlf.txt:1:1:alpha beta"),
            bothBackends(request("(?:al)(pha)", SearchMode.Regex)).requireHits().rendered(),
        )
    }

    @Test
    fun `quantifiers including counted and lazy forms agree`() {
        assertEquals(listOf("numbers.txt:1:5:a1 b22 c333"), numbers("\\d{2,3}"))
        assertEquals(listOf("numbers.txt:1:9:a1 b22 c333"), numbers("\\d{3}"))
        assertEquals(listOf("numbers.txt:1:1:a1 b22 c333"), numbers("a1?"))
        assertEquals(listOf("numbers.txt:1:2:a1 b22 c333"), numbers("\\d{1,}"))
        assertEquals(
            listOf("src/a.txt:1:1:alpha beta", "src/a.txt:2:7:BETA gamma", "src/a.txt:3:4:beta beta"),
            bothBackends(
                SearchRequest("a.+?a", SearchMode.Regex, SearchScope.Paths(fixture.root, listOf("src/a.txt")), BUDGET),
            ).requireHits().rendered(),
        )
    }

    @Test
    fun `word boundaries agree`() {
        assertEquals(
            listOf("meta.txt:3:5:zetagamma once", "src/a.txt:2:6:BETA gamma", "src/crlf.txt:2:6:BETA gamma"),
            bothBackends(request("gamma", SearchMode.Literal)).requireHits().rendered(),
        )
        assertEquals(
            listOf("src/a.txt:2:6:BETA gamma", "src/crlf.txt:2:6:BETA gamma"),
            bothBackends(request("\\bgamma\\b", SearchMode.Regex)).requireHits().rendered(),
        )
    }

    @Test
    fun `literal mode and escaped metacharacters agree`() {
        assertEquals(
            listOf("meta.txt:1:6:call a.txt here", "meta.txt:2:6:call axtxt here"),
            bothBackends(request("a.txt", SearchMode.Regex)).requireHits().rendered(),
        )
        assertEquals(
            listOf("meta.txt:1:6:call a.txt here"),
            bothBackends(request("a.txt", SearchMode.Literal)).requireHits().rendered(),
        )
        assertEquals(
            listOf("meta.txt:1:6:call a.txt here"),
            bothBackends(request("a\\.txt", SearchMode.Regex)).requireHits().rendered(),
        )
    }

    // ---- outcomes ---------------------------------------------------------------------------

    @Test
    fun `zero matches is a complete Found, not a failure`() {
        val outcome = bothBackends(request("zzzznotpresent", SearchMode.Literal))
        val found = outcome as? SearchOutcome.Found ?: error("expected Found, got $outcome")
        assertTrue(found.hits.hits.isEmpty())
        assertTrue(found.hits.complete)
        assertTrue(!found.hits.truncated)
        assertEquals(fixture.gitCandidates.size, found.hits.filesSearched)
    }

    @Test
    fun `a budget the next hit would exceed yields Incomplete and truncated`() {
        val outcome = bothBackends(
            SearchRequest("beta", SearchMode.Literal, all(), budgetBytes = "alpha beta".length.toLong()),
        )
        val incomplete = outcome as? SearchOutcome.Incomplete ?: error("expected Incomplete, got $outcome")
        assertEquals(IncompleteReason.BudgetBytesExceeded, incomplete.reason)
        assertEquals(listOf("src/a.txt:1:7:alpha beta"), incomplete.hits.rendered())
        assertTrue(incomplete.hits.truncated)
        assertTrue(!incomplete.hits.complete)
    }

    @Test
    fun `a budget smaller than the first hit yields Incomplete with no hits`() {
        val outcome = bothBackends(SearchRequest("beta", SearchMode.Literal, all(), budgetBytes = 1))
        val incomplete = outcome as? SearchOutcome.Incomplete ?: error("expected Incomplete, got $outcome")
        assertEquals(IncompleteReason.BudgetBytesExceeded, incomplete.reason)
        assertTrue(incomplete.hits.hits.isEmpty())
        assertTrue(incomplete.hits.truncated)
    }

    @Test
    fun `maxHits truncates only when a further hit exists`() {
        val capped = bothBackends(SearchRequest("beta", SearchMode.Literal, all(), BUDGET, maxHits = 2))
        val incomplete = capped as? SearchOutcome.Incomplete ?: error("expected Incomplete, got $capped")
        assertEquals(IncompleteReason.MaxHitsReached, incomplete.reason)
        assertEquals(
            listOf("src/a.txt:1:7:alpha beta", "src/a.txt:3:1:beta beta"),
            incomplete.hits.rendered(),
        )

        val exact = bothBackends(SearchRequest("beta", SearchMode.Literal, all(), BUDGET, maxHits = 5))
        val found = exact as? SearchOutcome.Found ?: error("expected Found, got $exact")
        assertEquals(5, found.hits.hits.size)
        assertTrue(found.hits.complete)
    }

    @Test
    fun `a nonexistent explicit scope path fails in both backends`() {
        val outcome = bothBackends(
            SearchRequest(
                "beta",
                SearchMode.Literal,
                SearchScope.Paths(fixture.root, listOf("src/a.txt", "nope.txt")),
                BUDGET,
            ),
        )
        val failed = outcome as? SearchOutcome.Failed ?: error("expected Failed, got $outcome")
        assertTrue("nope.txt" in failed.reason, failed.reason)
    }

    @Test
    fun `an explicit path scope restricts the candidate set`() {
        val hits = bothBackends(
            SearchRequest("beta", SearchMode.Literal, SearchScope.Paths(fixture.root, listOf("src")), BUDGET),
        ).requireHits()
        assertEquals(
            listOf(
                "src/a.txt:1:7:alpha beta",
                "src/a.txt:3:1:beta beta",
                "src/crlf.txt:1:7:alpha beta",
                "src/nested/deep.txt:1:8:nested beta",
            ),
            hits.rendered(),
        )
        assertEquals(3, hits.filesSearched)
    }

    @Test
    fun `a glob scope restricts the candidate set`() {
        val hits = bothBackends(
            SearchRequest("beta", SearchMode.Literal, SearchScope.Glob(fixture.root, "src/*.txt"), BUDGET),
        ).requireHits()
        assertEquals(
            listOf("src/a.txt:1:7:alpha beta", "src/a.txt:3:1:beta beta", "src/crlf.txt:1:7:alpha beta"),
            hits.rendered(),
        )
        assertEquals(2, hits.filesSearched)
    }

    @Test
    fun `an empty candidate set is a complete Found from both backends`() {
        val hits = bothBackends(
            SearchRequest("beta", SearchMode.Literal, SearchScope.Glob(fixture.root, "*.nothing"), BUDGET),
        ).requireHits()
        assertTrue(hits.hits.isEmpty())
        assertTrue(hits.complete)
        assertEquals(0, hits.filesSearched)
    }

    // ---- harness ----------------------------------------------------------------------------

    private fun all(): SearchScope = SearchScope.All(fixture.root)

    private fun request(pattern: String, mode: SearchMode): SearchRequest =
        SearchRequest(pattern, mode, all(), BUDGET)

    /** A regex over `numbers.txt` only, so a class test is not coupled to the rest of the tree. */
    private fun numbers(pattern: String): List<String> = bothBackends(
        SearchRequest(pattern, SearchMode.Regex, SearchScope.Paths(fixture.root, listOf("numbers.txt")), BUDGET),
    ).requireHits().rendered()

    /**
     * Runs [request] through the JVM backend and, when `rg` is installed, through ripgrep, asserting
     * the two agree on every field but [Hits.backend]. Returns the JVM outcome so the caller can
     * assert concrete values whether or not ripgrep is present.
     */
    private fun bothBackends(request: SearchRequest): SearchOutcome {
        val jvm = Searches.jvm().find(request)
        if (Searches.ripgrepAvailable()) {
            assertSameOutcome(jvm, Searches.ripgrep().find(request), request)
        } else {
            ripgrepSkipped = true
        }
        return jvm
    }

    @AfterEach
    fun reportSkippedRipgrep() {
        assumeTrue(!ripgrepSkipped, "rg is not on PATH; the ripgrep half of this test did not run")
    }

    private fun assertSameOutcome(jvm: SearchOutcome, rg: SearchOutcome, request: SearchRequest) {
        val where = "pattern='${request.pattern}' mode=${request.mode} scope=${request.scope}"
        assertEquals(jvm::class, rg::class, "$where: different outcome kinds (jvm=$jvm rg=$rg)")
        when (jvm) {
            is SearchOutcome.Found -> assertSameHits(jvm.hits, (rg as SearchOutcome.Found).hits, where)
            is SearchOutcome.Incomplete -> {
                rg as SearchOutcome.Incomplete
                assertEquals(jvm.reason, rg.reason, "$where: different incomplete reasons")
                assertSameHits(jvm.hits, rg.hits, where)
            }

            is SearchOutcome.Failed -> assertEquals(jvm.reason, (rg as SearchOutcome.Failed).reason, where)
            is SearchOutcome.Denied -> {
                rg as SearchOutcome.Denied
                assertEquals(jvm.reason, rg.reason, where)
                assertEquals(jvm.paths, rg.paths, where)
            }

            is SearchOutcome.Unsupported ->
                assertEquals(jvm.reason, (rg as SearchOutcome.Unsupported).reason, where)
        }
    }

    private fun assertSameHits(jvm: Hits, rg: Hits, where: String) {
        assertEquals(SearchBackend.Jvm, jvm.backend, "$where: the JVM backend misreported itself")
        assertEquals(SearchBackend.Ripgrep, rg.backend, "$where: the ripgrep backend misreported itself")
        assertEquals(jvm.rendered(), rg.rendered(), "$where: different hits")
        assertEquals(jvm.hits, rg.hits, "$where: different hit records")
        assertEquals(jvm.complete, rg.complete, "$where: different complete flags")
        assertEquals(jvm.truncated, rg.truncated, "$where: different truncated flags")
        assertEquals(jvm.filesSearched, rg.filesSearched, "$where: different filesSearched")
        assertEquals(jvm.scope, rg.scope, "$where: different scopes")
    }

    companion object {
        private const val BUDGET = 1L shl 20

        private lateinit var fixture: SearchFixture

        @JvmStatic
        @BeforeAll
        fun createFixture() {
            assumeTrue(SearchFixture.gitAvailable(), "git is not on PATH; the fixture repository needs it")
            fixture = SearchFixture.gitRepo()
        }

        @JvmStatic
        @AfterAll
        fun deleteFixture() {
            if (::fixture.isInitialized) fixture.delete()
        }
    }
}
