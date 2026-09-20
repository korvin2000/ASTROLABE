package io.astrolabe.workspace

import io.astrolabe.evidence.RedactionMask
import io.astrolabe.fixtures.TempRepo
import io.astrolabe.id.ContextId
import io.astrolabe.id.FileVersion
import io.astrolabe.id.Generation
import io.astrolabe.id.WorkspaceId
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1.2.1 / IX-05 / FX-51: raw-byte versions, metadata caches as hints only, coverage keyed by the
 * full namespace and change notifications that fire once per transition.
 */
class VersionRegistryTest {

    private val repo: TempRepo = TempRepo.create().also {
        it.write("src/a.py", "def a():\n    return 1\n")
        it.commit("initial")
    }

    private val workspace = Workspace(WorkspaceId("ws-1"), repo.root, repo.git)
    private val registry = VersionRegistry(workspace)

    private val context = ContextId("ctx-1")
    private val generation = Generation.INITIAL
    private val other = WorkspaceId("ws-2")

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    // -------------------------------------------------------------- versions

    @Test
    fun `a version is the hash of the raw bytes and an absent path has none`() {
        val version = registry.version("src/a.py")

        assertEquals(FileVersion.of(repo.resolve("src/a.py").toFile().readBytes()), version)
        assertNull(registry.version("src/missing.py"))
        assertNull(registry.version("../escape.py"), "a refused path has no version")
    }

    @Test
    fun `a same-size external rewrite with a restored mtime is detected and rejects a stale CAS`() {
        val file = repo.resolve("src/a.py")
        val before = registry.version("src/a.py")!!
        val originalTime = Files.getLastModifiedTime(file)
        val hint = registry.versionHint("src/a.py")
        assertEquals(before, hint, "the cache is primed with the real digest")

        // IX-05: equal-length bytes, timestamp restored — nothing in the metadata moved.
        val rewritten = "def a():\n    return 9\n".toByteArray()
        assertEquals(Files.size(file), rewritten.size.toLong(), "the rewrite must keep the size")
        Files.write(file, rewritten)
        Files.setLastModifiedTime(file, originalTime)

        assertEquals(
            before,
            registry.versionHint("src/a.py"),
            "the metadata hint is deliberately wrong here — that is why it is only a hint (I-05)",
        )
        val now = registry.version("src/a.py")!!
        assertNotEquals(before, now, "a consequential lookup hashes the bytes and sees the rewrite")

        val stale = registry.checkExpected("src/a.py", before)
        assertIs<CasCheck.Stale>(stale)
        assertEquals(now, stale.actual)
        assertIs<CasCheck.Current>(registry.checkExpected("src/a.py", now))
    }

    @Test
    fun `a read returns the bytes it hashed`() {
        val content = registry.read("src/a.py")!!

        assertEquals(FileVersion.of(content.bytes), content.version)
        assertEquals(false, content.raced)
    }

    @Test
    fun `a refused path is reported rather than collapsed to absent`() {
        val refused = registry.versionOf(".git/config")
        assertIs<CasCheck.Refused>(refused)
        assertEquals(RejectionReason.Protected, refused.rejection.reason)

        assertIs<CasCheck.Refused>(registry.checkExpected(".git/config", FileVersion.of(ByteArray(0))))
    }

    // -------------------------------------------------------------- coverage

    @Test
    fun `displayed ranges are the union of what was shown and merge adjacent ranges`() {
        val version = registry.version("src/a.py")!!

        registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(1, 3))
        registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(7, 9))
        val merged = registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(4, 6))

        assertEquals(Ranges.single(1, 9), merged, "1-3, 7-9 and 4-6 merge into one interval")
        assertEquals(merged, registry.displayed(context, generation, workspace.id, "src/a.py", version))
    }

    @Test
    fun `redacted lines grant no coverage`() {
        val version = registry.version("src/a.py")!!
        val mask = RedactionMask(hiddenLines = Ranges.single(3, 4))

        val granted = registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(1, 6), mask)

        assertEquals(Ranges.of(LineRange(1, 2), LineRange(5, 6)), granted, "D-49: hidden lines are subtracted")
    }

    @Test
    fun `coverage is keyed by context, generation and workspace, never by content alone`() {
        val version = registry.version("src/a.py")!!
        registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(1, 4))

        // FX-51: the same path at the same content hash in another workspace has its own coverage.
        assertTrue(registry.displayed(context, generation, other, "src/a.py", version).isEmpty)
        assertTrue(registry.displayed(context, generation.next(), workspace.id, "src/a.py", version).isEmpty)
        assertTrue(registry.displayed(ContextId("ctx-2"), generation, workspace.id, "src/a.py", version).isEmpty)
        assertEquals(Ranges.single(1, 4), registry.displayed(context, generation, workspace.id, "src/a.py", version))
    }

    @Test
    fun `coverage of one version never serves another`() {
        val version = registry.version("src/a.py")!!
        registry.show(context, generation, workspace.id, "src/a.py", version, LineRange(1, 4))
        repo.write("src/a.py", "def a():\n    return 42\n")
        val after = registry.version("src/a.py")!!

        assertNotEquals(version, after)
        assertTrue(registry.displayed(context, generation, workspace.id, "src/a.py", after).isEmpty)
    }

    // --------------------------------------------------------------- changes

    @Test
    fun `change notifications fire once per version transition`() {
        val seen = CopyOnWriteArrayList<String>()
        registry.addListener { c -> seen.add("${c.path}:${c.from?.hash8}->${c.to?.hash8} (${c.cause})") }
        val v1 = registry.version("src/a.py")!!
        repo.write("src/a.py", "def a():\n    return 2\n")
        val v2 = registry.version("src/a.py")!!

        registry.change("src/a.py", v1, v2, "edit #1")
        registry.change("src/a.py", v1, v2, "edit #1")
        registry.change("src/a.py", v2, v2, "edit #1")

        assertEquals(listOf("src/a.py:${v1.hash8}->${v2.hash8} (edit #1)"), seen)
        assertEquals(v2, registry.recorded("src/a.py"))
    }

    @Test
    fun `a deletion is a transition and a removed listener stops hearing about it`() {
        val seen = CopyOnWriteArrayList<String>()
        val subscription = registry.addListener { c -> seen.add("${c.path}->${c.to?.hash8 ?: "gone"}") }
        val v1 = registry.version("src/a.py")!!

        registry.change("src/a.py", null, v1, "created by run #2")
        registry.change("src/a.py", v1, null, "deleted by run #2")
        registry.change("src/a.py", v1, null, "deleted by run #2")
        assertEquals(listOf("src/a.py->${v1.hash8}", "src/a.py->gone"), seen)
        assertNull(registry.recorded("src/a.py"))

        subscription.close()
        registry.change("src/a.py", null, v1, "created by run #3")
        assertEquals(2, seen.size, "a removed listener hears nothing")
    }

    @Test
    fun `clearing the hints makes the next hint hash the bytes again`() {
        val file = repo.resolve("src/a.py")
        val original = Files.getLastModifiedTime(file)
        val before = registry.versionHint("src/a.py")!!
        Files.write(file, "def a():\n    return 8\n".toByteArray())
        Files.setLastModifiedTime(file, FileTime.from(original.toInstant()))

        registry.clearHints()

        assertNotEquals(before, registry.versionHint("src/a.py"))
    }
}
