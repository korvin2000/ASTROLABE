package io.astrolabe.os.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchLimitsTest {
    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `JVM search bounds regex work for a long near match`(@TempDir root: Path) {
        Files.writeString(root.resolve("near.txt"), "a".repeat(100_000) + "!")
        val result = Searches.jvm().find(SearchRequest("a+a+$", SearchMode.Regex, SearchScope.All(root), 1024))
        assertTrue("limit" in assertIs<SearchOutcome.Failed>(result).reason)
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `JVM search scans a repository larger than one line budget`(@TempDir root: Path) {
        val line = "val value = compute(input, 42) // ordinary source text\n"
        // About 12 MB of text in total, above the former search-wide budget of 10M character reads.
        repeat(4) { i -> Files.writeString(root.resolve("f$i.txt"), line.repeat(3_000_000 / line.length)) }
        val result = Searches.jvm().find(SearchRequest("absent_token", SearchMode.Literal, SearchScope.All(root), 1024))
        assertTrue(assertIs<SearchOutcome.Found>(result).hits.hits.isEmpty())
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a large binary file is skipped rather than failing the search`(@TempDir root: Path) {
        Files.write(root.resolve("image.bin"), ByteArray(9 * 1024 * 1024))
        Files.writeString(root.resolve("a.txt"), "needle\n")
        val result = Searches.jvm().find(SearchRequest("needle", SearchMode.Literal, SearchScope.All(root), 1024))
        assertTrue(assertIs<SearchOutcome.Found>(result).hits.hits.single().path == "a.txt")
    }

    @Test
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `JVM search refuses files larger than its bounded read`(@TempDir root: Path) {
        Files.write(root.resolve("large.txt"), ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        val result = Searches.jvm().find(SearchRequest("absent", SearchMode.Literal, SearchScope.All(root), 1024))
        assertTrue("file limit" in assertIs<SearchOutcome.Failed>(result).reason)
    }
}
