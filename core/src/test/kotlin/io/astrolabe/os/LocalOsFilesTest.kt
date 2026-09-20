package io.astrolabe.os

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P0.6.1 file operations: atomic replacement (D-44, D-47) and real-path resolution. */
class LocalOsFilesTest {

    private lateinit var directory: Path
    private lateinit var os: LocalOs

    @BeforeEach
    fun setUp(@TempDir temporary: Path) {
        directory = temporary
        os = LocalOs()
    }

    @AfterEach
    fun tearDown() {
        os.close()
    }

    @Test
    fun `an atomic replace creates the file and leaves no temporary behind`() {
        val target = directory.resolve("record.json")

        os.replaceFileAtomically(target, """{"v":1}""".toByteArray())

        assertEquals("""{"v":1}""", Files.readString(target))
        assertEquals(listOf(target), directory.listDirectoryEntries().sorted())
    }

    @Test
    fun `an atomic replace overwrites existing content in place`() {
        val target = directory.resolve("record.json")
        os.replaceFileAtomically(target, "old-and-longer".toByteArray())

        os.replaceFileAtomically(target, "new".toByteArray())

        assertEquals("new", Files.readString(target))
        assertEquals(listOf(target), directory.listDirectoryEntries().sorted())
    }

    @Test
    fun `an atomic replace creates missing parent directories`() {
        val target = directory.resolve("nested").resolve("deeper").resolve("record.json")

        os.replaceFileAtomically(target, "value".toByteArray())

        assertEquals("value", Files.readString(target))
    }

    @Test
    fun `realPath canonicalises an existing path and reports a missing one as null`() {
        val target = directory.resolve("present.txt")
        Files.writeString(target, "x")
        Files.createDirectory(directory.resolve("nested"))

        val resolved = os.realPath(directory.resolve("nested").resolve("..").resolve("present.txt"))

        assertEquals(target.toRealPath(), resolved)
        assertNull(os.realPath(directory.resolve("absent.txt")))
    }

    @Test
    fun `an empty replacement truncates the file`() {
        val target = directory.resolve("record.json")
        os.replaceFileAtomically(target, "content".toByteArray())

        os.replaceFileAtomically(target, ByteArray(0))

        assertEquals(0L, Files.size(target))
        assertTrue(Files.exists(target))
    }
}
