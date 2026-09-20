package io.astrolabe.os.search

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backend selection is explicit, and what a search reports is the backend that actually ran. */
class SearchesTest {

    @Test
    fun `auto reports the backend it was told to use`() {
        assertEquals(SearchBackend.Jvm, Searches.auto { false }.backend)
        assertEquals(SearchBackend.Ripgrep, Searches.auto { true }.backend)
    }

    @Test
    fun `auto reports the backend actually used in the Hits it returns`() {
        val request = SearchRequest("beta", SearchMode.Literal, SearchScope.All(fixture.root), BUDGET)

        val jvm = Searches.auto { false }
        assertEquals(SearchBackend.Jvm, jvm.backend)
        assertEquals(SearchBackend.Jvm, jvm.find(request).requireHits().backend)

        val chosen = Searches.available()
        val expected = if (Searches.ripgrepAvailable()) SearchBackend.Ripgrep else SearchBackend.Jvm
        assertEquals(expected, chosen.backend, "available() disagreed with its own probe")
        assertEquals(expected, chosen.find(request).requireHits().backend, "the reported backend is not the one used")
    }

    @Test
    fun `the ripgrep backend reports itself even when it finds nothing`() {
        assumeTrue(Searches.ripgrepAvailable(), "rg is not on PATH")
        val request = SearchRequest("zzzznotpresent", SearchMode.Literal, SearchScope.All(fixture.root), BUDGET)
        val hits = Searches.ripgrep().find(request).requireHits()
        assertEquals(SearchBackend.Ripgrep, hits.backend)
        assertTrue(hits.hits.isEmpty())
        assertTrue(hits.complete)
    }

    @Test
    fun `a missing ripgrep executable is a failure, not a silent fallback`() {
        val request = SearchRequest("beta", SearchMode.Literal, SearchScope.All(fixture.root), BUDGET)
        val outcome = Searches.ripgrep("astrolabe-no-such-ripgrep").find(request)
        val failed = outcome as? SearchOutcome.Failed ?: error("expected Failed, got $outcome")
        assertTrue("astrolabe-no-such-ripgrep" in failed.reason, failed.reason)
    }

    @Test
    fun `permission-only diagnostics are classified as denied, anything else as failed`() {
        assertEquals(
            listOf("locked/a.txt", "locked/b.txt"),
            RipgrepStderr.deniedPaths(
                "rg: locked/b.txt: Permission denied (os error 13)\nrg: locked/a.txt: Permission denied (os error 13)\n",
            ),
        )
        assertEquals(
            listOf("locked\\a.txt"),
            RipgrepStderr.deniedPaths("rg: locked\\a.txt: Access is denied. (os error 5)"),
        )
        assertNull(RipgrepStderr.deniedPaths(""))
        assertNull(RipgrepStderr.deniedPaths("rg: regex parse error:\n    (?:(?=x))\n"))
        assertNull(
            RipgrepStderr.deniedPaths(
                "rg: a.txt: Permission denied (os error 13)\nrg: b.txt: No such file or directory (os error 2)",
            ),
        )
    }

    companion object {
        private const val BUDGET = 1L shl 20

        private lateinit var fixture: SearchFixture

        @JvmStatic
        @BeforeAll
        fun createFixture() {
            fixture = SearchFixture.plainTree()
        }

        @JvmStatic
        @AfterAll
        fun deleteFixture() {
            if (::fixture.isInitialized) fixture.delete()
        }
    }
}
