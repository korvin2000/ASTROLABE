package io.astrolabe.store

import io.astrolabe.fixtures.StoreInspector
import io.astrolabe.id.Digest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** P0.5.1: content-addressed blobs, publication before reference, crash safety and gc (§4.3, D-44). */
class BlobStoreTest {

    @TempDir
    lateinit var root: Path

    private fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `put publishes bytes under their digest and get returns them`() {
        openStore(root).use { store ->
            val payload = bytes("tool output")
            val digest = store.blobs.put(payload, BlobKind.OUTPUT, TEST_IDS)

            assertEquals(Digest.of(payload), digest)
            assertTrue(store.blobs.exists(digest))
            assertContentEquals(payload, store.blobs.get(digest))
            assertTrue(Files.exists(store.layout.blobs.resolve(digest.hex)))
            val recorded = store.db.query("SELECT bytes FROM blobs WHERE digest = ?", digest.hex) { it.long("bytes") }
            assertEquals(payload.size.toLong(), recorded.single())
            assertEquals(emptyList(), StoreInspector(store).temporaryBlobFiles(), "no temporary file survives a put")
        }
    }

    @Test
    fun `publishing the same bytes twice is idempotent`() {
        openStore(root).use { store ->
            val payload = bytes("same")
            val first = store.blobs.put(payload, BlobKind.LOG, TEST_IDS)
            val second = store.blobs.put(payload, BlobKind.LOG, TEST_IDS)

            assertEquals(first, second)
            assertEquals(1L, store.db.count("SELECT count(*) FROM blobs"))
            assertEquals(listOf(first.hex), StoreInspector(store).blobFiles())
        }
    }

    @Test
    fun `recovery material is published into the restricted area`() {
        openStore(root).use { store ->
            val digest = store.blobs.put(bytes("preimage"), BlobKind.PREIMAGE, TEST_IDS, recovery = true)

            assertTrue(Files.exists(store.layout.blobsRecovery.resolve(digest.hex)))
            assertFalse(Files.exists(store.layout.blobs.resolve(digest.hex)))
            assertTrue(store.blobs.exists(digest))
            assertContentEquals(bytes("preimage"), store.blobs.get(digest))
        }
    }

    @Test
    fun `a missing blob is an integrity failure, never an empty result`() {
        openStore(root).use { store ->
            val absent = Digest.ofUtf8("never published")
            assertFalse(store.blobs.exists(absent))
            assertFailsWith<MissingBlob> { store.blobs.get(absent) }

            val digest = store.blobs.put(bytes("published"), BlobKind.OUTPUT, TEST_IDS)
            Files.delete(store.layout.blobs.resolve(digest.hex))
            assertFalse(store.blobs.exists(digest), "a row without its file is not an existing blob")
            assertFailsWith<MissingBlob> { store.blobs.get(digest) }
        }
    }

    @Test
    fun `altered content-addressed bytes are refused`() {
        openStore(root).use { store ->
            val digest = store.blobs.put(bytes("original"), BlobKind.OUTPUT, TEST_IDS)
            Files.write(store.layout.blobs.resolve(digest.hex), bytes("tampered"))
            assertFailsWith<MissingBlob> { store.blobs.get(digest) }
        }
    }

    @Test
    fun `no accepted receipt can reference a missing artifact, whatever the ordering point`() {
        for (point in BlobPoint.entries) {
            val directory = Files.createDirectory(root.resolve("crash-$point"))
            val payload = bytes("output for $point")
            val digest = Digest.of(payload)

            openStore(directory, faults = crashAt(point)).use { store ->
                val crash = assertFailsWith<InjectedCrash> { store.blobs.put(payload, BlobKind.OUTPUT, TEST_IDS) }
                assertEquals(point, crash.point)
            }

            // Reopen: the crash took the process, the next controller sees only what reached disk.
            openStore(directory).use { store ->
                val published = point == BlobPoint.AFTER_ROW
                assertEquals(published, store.blobs.exists(digest), "exists() after a crash at $point")

                if (published) {
                    insertReceipt(store, digest.hex)
                    assertContentEquals(payload, store.blobs.get(digest))
                } else {
                    // The blobs row is what the foreign key needs, and it is written last.
                    assertFailsWith<StoreError> { insertReceipt(store, digest.hex) }
                    assertEquals(0L, store.db.count("SELECT count(*) FROM receipts"))
                }

                val inspector = StoreInspector(store)
                for (referenced in inspector.referencedBlobs()) {
                    assertTrue(
                        store.blobs.exists(Digest(referenced)),
                        "receipt at $point references a missing artifact $referenced",
                    )
                }

                val fileOnDisk = Files.exists(store.layout.blobs.resolve(digest.hex))
                when (point) {
                    BlobPoint.BEFORE_FSYNC, BlobPoint.AFTER_FSYNC_BEFORE_MOVE -> {
                        assertFalse(fileOnDisk, "$point must not publish the file")
                        assertEquals(1, inspector.temporaryBlobFiles().size, "$point leaves the temporary file")
                    }
                    BlobPoint.AFTER_MOVE_BEFORE_ROW, BlobPoint.AFTER_ROW ->
                        assertTrue(fileOnDisk, "$point must have published the file")
                }
            }
        }
    }

    @Test
    fun `gc discards abandoned temporary files and orphans, and adopts referenced ones`() {
        // Real time: gc compares row timestamps and file mtimes, which the filesystem writes.
        val clock = Clock.systemUTC()
        val payload = bytes("orphan payload")
        val orphan = Digest.of(payload)

        openStore(root, clock = clock, faults = crashAt(BlobPoint.AFTER_MOVE_BEFORE_ROW)).use { store ->
            assertFailsWith<InjectedCrash> { store.blobs.put(payload, BlobKind.OUTPUT, TEST_IDS) }
        }
        openStore(root, clock = clock, faults = crashAt(BlobPoint.BEFORE_FSYNC)).use { store ->
            assertFailsWith<InjectedCrash> { store.blobs.put(bytes("abandoned"), BlobKind.OUTPUT, TEST_IDS) }
        }

        openStore(root, clock = clock).use { store ->
            val inspector = StoreInspector(store)
            assertEquals(1, inspector.temporaryBlobFiles().size)
            assertEquals(listOf(orphan.hex), inspector.blobFiles())
            assertFalse(store.blobs.exists(orphan), "the orphan has no row yet")

            val adopting = store.blobs.gc(setOf(orphan), grace = Duration.ZERO)
            assertEquals(1, adopting.adopted, "a referenced orphan is adopted, not deleted")
            assertEquals(1, adopting.discardedTemp, "the abandoned temporary file is discarded")
            assertFalse(adopting.bounded)
            assertTrue(store.blobs.exists(orphan))
            assertContentEquals(payload, store.blobs.get(orphan))
            assertEquals(emptyList(), inspector.temporaryBlobFiles())

            val collecting = store.blobs.gc(emptySet(), grace = Duration.ZERO)
            assertEquals(1, collecting.collected, "nothing references the blob any more")
            assertEquals(emptyList(), inspector.blobFiles())
            assertEquals(0L, store.db.count("SELECT count(*) FROM blobs"))
        }
    }

    @Test
    fun `gc keeps referenced and young blobs`() {
        // Real time: gc compares row timestamps and file mtimes, which the filesystem writes.
        val clock = Clock.systemUTC()
        openStore(root, clock = clock).use { store ->
            val kept = store.blobs.put(bytes("referenced"), BlobKind.OUTPUT, TEST_IDS)
            val young = store.blobs.put(bytes("young"), BlobKind.OUTPUT, TEST_IDS)

            val result = store.blobs.gc(setOf(kept))
            assertEquals(0, result.collected, "the default grace period protects a fresh blob")
            assertTrue(store.blobs.exists(kept))
            assertTrue(store.blobs.exists(young))

            assertEquals(1, store.blobs.gc(setOf(kept), grace = Duration.ZERO).collected)
            assertTrue(store.blobs.exists(kept))
            assertFalse(store.blobs.exists(young))
        }
    }

    @Test
    fun `gc reports a missing referenced blob before it deletes anything`() {
        // Real time: gc compares row timestamps and file mtimes, which the filesystem writes.
        val clock = Clock.systemUTC()
        openStore(root, clock = clock).use { store ->
            val present = store.blobs.put(bytes("present"), BlobKind.OUTPUT, TEST_IDS)
            val gone = store.blobs.put(bytes("gone"), BlobKind.OUTPUT, TEST_IDS)
            Files.delete(store.layout.blobs.resolve(gone.hex))

            assertFailsWith<MissingBlob> { store.blobs.gc(setOf(present, gone), grace = Duration.ZERO) }
            assertTrue(store.blobs.exists(present), "an integrity failure must not compound into deletions")
        }
    }

    private fun insertReceipt(store: Store, rawBlob: String) {
        store.insertRow(
            "receipts",
            "receipt_id" to "receipt-1",
            "check_id" to "check-1",
            "outcome" to "pass",
            "raw_blob" to rawBlob,
        )
    }
}
