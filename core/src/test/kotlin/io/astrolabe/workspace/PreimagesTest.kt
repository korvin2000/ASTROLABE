package io.astrolabe.workspace

import io.astrolabe.id.FileVersion
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * P1.2.5 / FX-05: preimages in protected recovery storage and the version-checked inverse of one
 * edit. The preimage exists before any byte moves, and the inverse refuses divergent content.
 */
class PreimagesTest {

    @Test
    fun `the preimage is saved before the write`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val order = CopyOnWriteArrayList<String>()
            val before = fixture.bytes("src/a.py")
            val version = fixture.registry.version("src/a.py")!!

            val preimage = fixture.preimages.saveThenWrite("edit-1", "src/a.py", version, before) {
                // By the time the write runs, the bytes are already recoverable.
                order.add("blob-present=${fixture.store.blobs.exists(fixture.preimages.of("edit-1", "src/a.py")!!.preimageDigest)}")
                fixture.repo.write("src/a.py", "def a():\n    return 2\n")
                order.add("write")
            }

            assertEquals(listOf("blob-present=true", "write"), order)
            assertContentEquals(before, fixture.preimages.bytesOf(preimage))
            assertEquals(version, preimage.versionBefore)
            assertNull(preimage.versionAfter, "the postimage is recorded by the edit tool afterwards")
        }
    }

    @Test
    fun `a preimage blob lands in the restricted recovery area`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val bytes = fixture.bytes("src/a.py")
            val preimage = fixture.preimages.save("edit-1", "src/a.py", FileVersion.of(bytes), bytes)

            val recovery = fixture.store.layout.blobsRecovery.resolve(preimage.preimageDigest.hex)
            assertTrue(java.nio.file.Files.exists(recovery), "D-14: exact preimages live in protected storage")
            assertContentEquals(bytes, fixture.store.blobs.get(preimage.preimageDigest))
        }
    }

    @Test
    fun `revert restores the preimage and returns a diff receipt`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val before = fixture.bytes("src/a.py")
            val versionBefore = FileVersion.of(before)
            fixture.preimages.saveThenWrite("edit-1", "src/a.py", versionBefore, before) {
                fixture.repo.write("src/a.py", "def a():\n    return 2\n    # extra\n")
            }
            val after = fixture.registry.version("src/a.py")!!
            fixture.preimages.recordPostimage("edit-1", "src/a.py", after)

            val result = fixture.preimages.revert("edit-1", "src/a.py", fixture.os)

            assertIs<RevertResult.Reverted>(result)
            assertContentEquals(before, fixture.bytes("src/a.py"))
            assertEquals("src/a.py", result.receipt.path)
            assertEquals(after, result.receipt.versionBefore)
            assertEquals(versionBefore, result.receipt.versionAfter)
            assertEquals(1, result.receipt.addedLines, "the restored region is one line")
            assertEquals(2, result.receipt.removedLines)
        }
    }

    @Test
    fun `revert refuses when the current bytes are not this edit's postimage`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val before = fixture.bytes("src/a.py")
            fixture.preimages.saveThenWrite("edit-1", "src/a.py", FileVersion.of(before), before) {
                fixture.repo.write("src/a.py", "def a():\n    return 2\n")
            }
            val after = fixture.registry.version("src/a.py")!!
            fixture.preimages.recordPostimage("edit-1", "src/a.py", after)

            // FX-05: someone else moved the file since this edit.
            val theirs = "def a():\n    return 'theirs'\n".toByteArray()
            fixture.repo.write("src/a.py", theirs)

            val result = fixture.preimages.revert("edit-1", "src/a.py", fixture.os)

            assertIs<RevertResult.Diverged>(result)
            assertEquals(after, result.expected)
            assertEquals(FileVersion.of(theirs), result.actual)
            assertContentEquals(theirs, fixture.bytes("src/a.py"), "the other writer's bytes stay")
        }
    }

    @Test
    fun `revert refuses by name when there is no preimage or no recorded postimage`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val unknown = fixture.preimages.revert("edit-none", "src/a.py", fixture.os)
            assertIs<RevertResult.Refused>(unknown)
            assertTrue(unknown.reason.contains("no preimage"), unknown.reason)

            val bytes = fixture.bytes("src/a.py")
            fixture.preimages.save("edit-1", "src/a.py", FileVersion.of(bytes), bytes)
            val open = fixture.preimages.revert("edit-1", "src/a.py", fixture.os)
            assertIs<RevertResult.Refused>(open)
            assertTrue(open.reason.contains("no postimage"), open.reason)

            assertFailsWith<IllegalStateException> {
                fixture.preimages.recordPostimage("edit-2", "src/a.py", FileVersion.of(bytes))
            }
        }
    }

    @Test
    fun `revert refuses a path the contract does not allow`(@TempDir state: Path) {
        WorkspaceFixture.create(state).use { fixture ->
            val bytes = "ref: refs/heads/main\n".toByteArray()
            fixture.preimages.save("edit-1", ".git/HEAD", FileVersion.of(bytes), bytes)
            fixture.preimages.recordPostimage("edit-1", ".git/HEAD", FileVersion.of(bytes))

            val result = fixture.preimages.revert("edit-1", ".git/HEAD", fixture.os)

            assertIs<RevertResult.Refused>(result)
            assertEquals(RejectionReason.Protected, result.rejection?.reason)
        }
    }

    @Test
    fun `the changed-region counts trim the common prefix and suffix`() {
        val before = "a\nb\nc\nd\n".toByteArray()
        val after = "a\nX\nY\nd\n".toByteArray()

        assertEquals(2 to 2, Preimages.changedRegion(before, after))
        assertEquals(0 to 0, Preimages.changedRegion(before, before))
        assertEquals(2 to 0, Preimages.changedRegion("a\nd\n".toByteArray(), after))
    }
}
