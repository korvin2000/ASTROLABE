package io.astrolabe.store

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: `Layout` resolution and creation (§4, D-15, D-44). */
class LayoutTest {

    @TempDir
    lateinit var stateRoot: Path

    private val identity = RepoIdentity.of(emptyList(), "/repo/.git")

    @Test
    fun `an explicit state root keys the project by repository identity`() {
        val layout = Layout.resolve(stateRoot, identity)
        assertEquals(
            stateRoot.resolve("astrolabe").resolve("projects").resolve(identity.digest.hex),
            layout.root,
        )
        assertEquals(layout.root.resolve("state.sqlite"), layout.database)
        assertEquals(layout.blobs.resolve("recovery"), layout.blobsRecovery)
        assertEquals(layout.blobs.resolve("tmp"), layout.blobsTemp)
        assertEquals(layout.root.resolve("controller.lock"), layout.lockFile)
    }

    @Test
    fun `two identities never share a root`() {
        val other = RepoIdentity.of(emptyList(), "/other/.git")
        assertTrue(Layout.resolve(stateRoot, identity).root != Layout.resolve(stateRoot, other).root)
    }

    @Test
    fun `create makes every directory and is idempotent`() {
        val layout = Layout.resolve(stateRoot, identity)
        layout.directories.forEach { assertFalse(Files.exists(it), "$it exists before create") }
        layout.create()
        layout.create()
        layout.directories.forEach { assertTrue(Files.isDirectory(it), "$it was not created") }
        assertFalse(Files.exists(layout.database), "the database is created by Db.open, not by the layout")
    }

    @Test
    fun `restricted directories are owner-only where the filesystem has POSIX permissions`() {
        val layout = Layout.resolve(stateRoot, identity).create()
        val view = Files.getFileAttributeView(layout.native, PosixFileAttributeView::class.java)
        if (view == null) {
            // Windows: documented best effort, no POSIX view to assert on.
            assertTrue(Files.isDirectory(layout.native))
            return
        }
        for (directory in listOf(layout.native, layout.blobsRecovery)) {
            val permissions = Files.getPosixFilePermissions(directory)
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                permissions,
                "$directory must be owner-only",
            )
        }
    }

    @Test
    fun `the OS user-state directory is absolute and is not a cache path`() {
        val directory = Layout.userStateDirectory()
        assertTrue(directory.isAbsolute, "state directory must be absolute, got $directory")
        val text = directory.toString().replace('\\', '/').lowercase()
        assertFalse(text.contains("/cache"), "authoritative state must not live under a cache path: $directory")
    }
}
