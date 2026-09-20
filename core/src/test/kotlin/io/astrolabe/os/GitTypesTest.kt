package io.astrolabe.os

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure parsing and validation of the typed records; no repository involved. */
class GitTypesTest {

    @Test
    fun `object id accepts sha1 and sha256 names and rejects anything else`() {
        val sha1 = ObjectId("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        assertEquals(40, sha1.hex.length)
        val sha256 = ObjectId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertEquals(64, sha256.hex.length)

        assertFailsWith<IllegalArgumentException> { ObjectId("abc") }
        assertFailsWith<IllegalArgumentException> { ObjectId("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709") }
        assertFailsWith<IllegalArgumentException> { ObjectId("za39a3ee5e6b4b0d3255bfef95601890afd80709") }
    }

    @Test
    fun `object id parse normalises case and whitespace`() {
        assertEquals(
            ObjectId("da39a3ee5e6b4b0d3255bfef95601890afd80709"),
            ObjectId.parse("  DA39A3EE5E6B4B0D3255BFEF95601890AFD80709\n"),
        )
        assertNull(ObjectId.parseOrNull("not an id"))
        assertFailsWith<IllegalArgumentException> { ObjectId.parse("not an id") }
    }

    @Test
    fun `zero id matches the width of the repository hash`() {
        val sha1 = ObjectId("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        val sha256 = ObjectId("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertEquals("0".repeat(40), ObjectId.zeroLike(sha1).hex)
        assertEquals("0".repeat(64), ObjectId.zeroLike(sha256).hex)
        assertTrue(ObjectId.zeroLike(sha1).isZero)
        assertFalse(sha1.isZero)
    }

    @Test
    fun `git version parses the forms git prints on every platform`() {
        val windows = GitVersion.parse("git version 2.45.1.windows.1")
        assertEquals(GitVersion(2, 45, 1, "git version 2.45.1.windows.1"), windows)

        // D-04: 2.20 is the version this wrapper is tested against.
        val floor = GitVersion.parse("git version 2.20.0")
        assertEquals(2, floor.major)
        assertEquals(20, floor.minor)
        assertEquals(0, floor.patch)

        val apple = GitVersion.parse("git version 2.39.3 (Apple Git-146)")
        assertEquals(2, apple.major)
        assertEquals(39, apple.minor)
        assertEquals(3, apple.patch)

        val twoPart = GitVersion.parse("git version 2.20")
        assertEquals(GitVersion(2, 20, 0, "git version 2.20"), twoPart)

        assertFailsWith<IllegalArgumentException> { GitVersion.parse("not a version") }
    }

    @Test
    fun `git version compares and answers the tested minimum`() {
        val floor = GitVersion.parse("git version 2.20.0")
        assertTrue(floor.atLeast(2, 20))
        assertFalse(floor.atLeast(2, 21))
        assertTrue(GitVersion.parse("git version 3.0.0").atLeast(2, 20))
        assertFalse(GitVersion.parse("git version 2.19.9").atLeast(2, 20))
        assertTrue(GitVersion.parse("git version 2.45.1") > floor)
    }

    @Test
    fun `file modes are the closed git set`() {
        assertEquals(FileMode.REGULAR, FileMode.parse("100644"))
        assertEquals(FileMode.EXECUTABLE, FileMode.parse("100755"))
        assertEquals(FileMode.SYMLINK, FileMode.parse("120000"))
        assertEquals(FileMode.GITLINK, FileMode.parse("160000"))
        assertEquals(FileMode.TREE, FileMode.parse("040000"))
        assertEquals(FileMode.ABSENT, FileMode.parse("000000"))
        assertNull(FileMode.parseOrNull("777777"))
        assertFailsWith<IllegalArgumentException> { FileMode.parse("777777") }
    }

    @Test
    fun `index entries refuse gitlinks and non-relative paths`() {
        val id = ObjectId("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        IndexEntry(FileMode.REGULAR, id, "a/b.txt")
        IndexEntry(FileMode.EXECUTABLE, id, "run.sh")
        IndexEntry(FileMode.SYMLINK, id, "link")

        // D-53 refuses submodules by name rather than indexing a gitlink.
        assertFailsWith<IllegalArgumentException> { IndexEntry(FileMode.GITLINK, id, "sub") }
        assertFailsWith<IllegalArgumentException> { IndexEntry(FileMode.TREE, id, "dir") }
        assertFailsWith<IllegalArgumentException> { IndexEntry(FileMode.REGULAR, id, "/abs.txt") }
        assertFailsWith<IllegalArgumentException> { IndexEntry(FileMode.REGULAR, id, "") }
    }

    @Test
    fun `submodule state parses both porcelain v2 forms`() {
        assertEquals(SubmoduleState.NOT_A_SUBMODULE, SubmoduleState.parse("N..."))
        assertEquals(
            SubmoduleState(isSubmodule = true, commitChanged = true, hasTrackedChanges = false, hasUntrackedChanges = true),
            SubmoduleState.parse("SC.U"),
        )
        assertFailsWith<IllegalArgumentException> { SubmoduleState.parse("X") }
    }

    @Test
    fun `identity rejects blank fields`() {
        Identity("A", "a@b")
        assertFailsWith<IllegalArgumentException> { Identity(" ", "a@b") }
        assertFailsWith<IllegalArgumentException> { Identity("A", "") }
    }
}
