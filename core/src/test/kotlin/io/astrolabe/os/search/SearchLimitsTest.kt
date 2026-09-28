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
    @Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `JVM search refuses files larger than its bounded read`(@TempDir root: Path) {
        Files.write(root.resolve("large.txt"), ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        val result = Searches.jvm().find(SearchRequest("absent", SearchMode.Literal, SearchScope.All(root), 1024))
        assertTrue("file limit" in assertIs<SearchOutcome.Failed>(result).reason)
    }
}
