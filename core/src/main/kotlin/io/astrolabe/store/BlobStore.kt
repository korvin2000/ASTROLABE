package io.astrolabe.store

import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.Hashing
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.security.MessageDigest

/** What a blob holds (§4.3). Closed vocabulary, so a `gc` policy can reason about a class of bytes. */
public enum class BlobKind {
    /** Captured tool output. */
    OUTPUT,

    /** Bytes of a file before an edit. */
    PREIMAGE,

    /** Bytes of a file after an edit. */
    POSTIMAGE,

    /** A computed diff between two versions. */
    DIFF,

    /** Captured process output of a run or check. */
    LOG,

    /** A delegation or result packet. */
    PACKET,

    /** A knowledge-base module a `SKILL`/`BMAP` note links (P4.3). */
    MODULE,
}

/**
 * A referenced blob is absent. §4.3: orphaned blobs may be collected, a *missing referenced* blob is
 * an integrity failure — never a recoverable miss the caller may paper over.
 */
public class MissingBlob(public val digest: Digest, message: String) : IllegalStateException(message)

/** The ordering points of [BlobStore.put] a crash can fall between (§4.3 consequential-action ordering). */
public enum class BlobPoint {
    /** Bytes written to `blobs/tmp`, not yet flushed. */
    BEFORE_FSYNC,

    /** Bytes flushed, not yet published under their digest. */
    AFTER_FSYNC_BEFORE_MOVE,

    /** File published, `blobs` row not yet committed — the orphan case. */
    AFTER_MOVE_BEFORE_ROW,

    /** Row committed; the blob may now be referenced. */
    AFTER_ROW,
}

/** Test seam: throws at a chosen [BlobPoint] so crash safety is exercised rather than argued. */
public fun interface CrashPoint {
    public fun at(point: BlobPoint)
}

/** Injected fault schedule; [NONE] in production. */
public class FaultPoints(private val crash: CrashPoint) {
    internal fun at(point: BlobPoint): Unit = crash.at(point)

    public companion object {
        @JvmField
        public val NONE: FaultPoints = FaultPoints(CrashPoint { })
    }
}

/** What one [BlobStore.gc] pass changed. */
public data class BlobGc(
    /** Rows and files removed because nothing references them and they aged past the grace period. */
    val collected: Int,
    /** Published files that had no row and were referenced, so they got their row back. */
    val adopted: Int,
    /** Abandoned `blobs/tmp` files removed. */
    val discardedTemp: Int,
    /** True when the pass stopped at [BlobStore.MAX_ORPHANS_PER_PASS]; the next pass continues. */
    val bounded: Boolean,
)

/**
 * Content-addressed bytes (§4.3). Blobs are never truncated — only views are — and publication
 * always precedes the transaction that references them:
 *
 * `write blobs/tmp/<random>` → `force(true)` → atomic rename to `blobs/<digest>` → directory fsync
 * where the platform has one → insert the `blobs` row in its own transaction.
 *
 * A crash between any two of those steps leaves either nothing, or a file with no row (an orphan
 * [gc] adopts or collects). It can never leave a row without a file, which is what makes the foreign
 * key from `receipts.raw_blob` a real integrity guarantee rather than a hope.
 */
public class BlobStore internal constructor(
    private val layout: Layout,
    private val db: Db,
    private val clock: Clock,
    private val faults: FaultPoints,
) {
    /**
     * Publishes [bytes] and returns their digest. Publishing the same bytes twice is a no-op beyond
     * ensuring the row exists: the content address makes the operation idempotent.
     *
     * [recovery] places the file under `blobs/recovery/` — owner-only where the filesystem supports
     * it — for material that must survive collection while an incident is investigated.
     */
    public fun put(
        bytes: ByteArray,
        kind: BlobKind,
        ids: Identities,
        recovery: Boolean = false,
    ): Digest {
        val digest = Digest.of(bytes)
        val target = directory(recovery).resolve(digest.hex)
        if (!Files.exists(target)) {
            publish(bytes, target)
        }
        faults.at(BlobPoint.AFTER_MOVE_BEFORE_ROW)
        insertRow(digest, bytes.size.toLong(), kind, ids, recovery)
        faults.at(BlobPoint.AFTER_ROW)
        return digest
    }

    /** The bytes of [digest]; a referenced blob that is not on disk is an integrity failure. */
    public fun get(digest: Digest): ByteArray {
        val recovery = recoveryOf(digest)
            ?: throw MissingBlob(digest, "no blobs row for ${digest.hex} in ${db.path}")
        val file = directory(recovery).resolve(digest.hex)
        if (!Files.exists(file)) throw MissingBlob(digest, "blobs row ${digest.hex} has no file at $file")
        val bytes = Files.readAllBytes(file)
        val actual = Digest.of(bytes)
        if (actual != digest) {
            throw MissingBlob(digest, "blob $file hashes to ${actual.hex}: content-addressed bytes were altered")
        }
        return bytes
    }

    /** True when both the row and its file are present; a half-published blob does not exist. */
    public fun exists(digest: Digest): Boolean {
        val recovery = recoveryOf(digest) ?: return false
        return Files.exists(directory(recovery).resolve(digest.hex))
    }

    /**
     * True when [put] of bytes hashing to [digest] with this [recovery] would write nothing: the file
     * is already in that directory and the row exists. The row is keyed by digest alone (first writer's
     * kind and ids stand), so such a `put` is a no-op and a caller holding the digest may skip it.
     */
    internal fun holds(digest: Digest, recovery: Boolean): Boolean =
        Files.exists(directory(recovery).resolve(digest.hex)) && recoveryOf(digest) != null

    /** Where [digest] lives once published; the file may not exist yet. */
    public fun path(digest: Digest, recovery: Boolean = false): Path =
        directory(recovery).resolve(digest.hex)

    private val scans = listOf(layout.blobsTemp, layout.blobs, layout.blobsRecovery).map(::FileScan)
    private var rowCursor = ""

    /**
     * Repairs/checks every referenced digest before deleting anything. This mandatory integrity
     * work is proportional to [referenced]; orphan adoption hashes bytes with bounded memory.
     * Cleanup examines at most [MAX_ORPHANS_PER_PASS] directory entries and rows in total, with
     * separate continuing cursors so young files and referenced rows cannot starve other work.
     * Cursors last for this store's lifetime; reopening starts a new sweep.
     */
    @Synchronized
    public fun gc(referenced: Set<Digest>, grace: Duration = DEFAULT_GRACE): BlobGc {
        val cutoff = clock.instant().minus(grace)
        var adopted = 0
        for (digest in referenced) {
            if (recoveryOf(digest) == null) {
                for (recovery in listOf(false, true)) {
                    val file = path(digest, recovery)
                    if (Files.isRegularFile(file, NOFOLLOW_LINKS) && fileDigest(file) == digest) {
                        insertRow(digest, Files.size(file), BlobKind.OUTPUT, ADOPTED_IDS, recovery)
                        adopted++
                        break
                    }
                }
            }
            if (!exists(digest)) throw MissingBlob(digest, "referenced blob ${digest.hex} is missing from ${layout.blobs}")
        }

        val share = MAX_ORPHANS_PER_PASS / 4
        var discardedTemp = 0
        var bounded = false
        for ((index, scan) in scans.withIndex()) {
            val more = scan.visit(share) { file ->
                if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                    if (index == 0) {
                        if (agedOut(file, cutoff) && Files.deleteIfExists(file)) discardedTemp++
                    } else {
                        val digest = runCatching { Digest(file.fileName.toString()) }.getOrNull()
                        if (digest != null && digest !in referenced && recoveryOf(digest) == null && agedOut(file, cutoff)) {
                            Files.deleteIfExists(file)
                        }
                    }
                }
            }
            bounded = bounded || more
        }

        // Keyset pagination uses the primary key; even protected/young rows count toward the cap.
        val rows = db.query(
            "SELECT digest, recovery, created_at FROM blobs WHERE digest > ? ORDER BY digest LIMIT ?",
            rowCursor, share,
        ) { Triple(it.string("digest"), it.bool("recovery"), it.instant("created_at")) }
        var collected = 0
        for ((hex, recovery, createdAt) in rows) {
            if (Digest(hex) !in referenced && !createdAt.isAfter(cutoff)) {
                Files.deleteIfExists(directory(recovery).resolve(hex))
                db.tx { it.execute("DELETE FROM blobs WHERE digest = ?", hex) }
                collected++
            }
            rowCursor = hex
        }
        if (rows.size < share) rowCursor = "" else bounded = true
        return BlobGc(collected, adopted, discardedTemp, bounded)
    }

    @Synchronized
    internal fun closeScans() {
        try { scans.forEach { it.close() } } finally { rowCursor = "" }
    }

    private fun fileDigest(file: Path): Digest {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return Digest(Hashing.hex(hash.digest()))
    }

    /** A live directory iterator avoids re-enumeration/sorting on each bounded pass. */
    private class FileScan(private val directory: Path) {
        private var stream: DirectoryStream<Path>? = null
        private var iterator: Iterator<Path>? = null

        fun visit(limit: Int, action: (Path) -> Unit): Boolean {
            try {
                val entries = iterator ?: Files.newDirectoryStream(directory).let {
                    stream = it
                    it.iterator().also { opened -> iterator = opened }
                }
                var visited = 0
                while (visited < limit && entries.hasNext()) {
                    action(entries.next())
                    visited++
                }
                if (entries.hasNext()) return true
                close()
                return false
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun close() {
            val opened = stream
            stream = null
            iterator = null
            opened?.close()
        }
    }

    /**
     * A failed publication deliberately leaves its `blobs/tmp` file behind: a crash cannot run
     * cleanup either, so [gc] — not a `finally` block — owns temporary-file hygiene, and the two
     * paths then behave identically.
     */
    private fun publish(bytes: ByteArray, target: Path) {
        Files.createDirectories(target.parent)
        Files.createDirectories(layout.blobsTemp)
        val token = java.lang.Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), Character.MAX_RADIX)
        val temp = layout.blobsTemp.resolve("put-$token")
        FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            faults.at(BlobPoint.BEFORE_FSYNC)
            channel.force(true)
        }
        faults.at(BlobPoint.AFTER_FSYNC_BEFORE_MOVE)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        forceDirectory(target.parent)
    }

    /**
     * Flushes the directory entry so the rename survives OS crash and power loss, where the
     * platform allows a directory to be opened as a channel at all. Windows does not
     * (`AccessDeniedException`), so there the rename's durability rests on NTFS metadata
     * journaling — stated in the durability statement on [Layout] rather than silently assumed.
     *
     * The blob is already published when this runs, so a refusal here is not a failed publication.
     */
    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (unsupported: IOException) {
            // Platform refuses a directory channel; see the durability statement on Layout (D-44).
        }
    }

    private fun insertRow(digest: Digest, size: Long, kind: BlobKind, ids: Identities, recovery: Boolean) {
        db.tx { tx ->
            tx.execute(
                "INSERT INTO blobs (digest, work_id, attempt_id, candidate_id, context_id, " +
                    "bytes, kind, recovery, schema_version, created_at, body) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (digest) DO NOTHING",
                digest.hex,
                ids.work.value,
                ids.attempt.value,
                ids.candidate?.digest?.hex,
                ids.context?.value,
                size,
                kind.name,
                recovery,
                Migrations.SCHEMA_VERSION,
                clock.instant().toString(),
                JsonObject(emptyMap()),
            )
        }
    }

    /** `null` when no row exists; otherwise whether the bytes live under `blobs/recovery/`. */
    private fun recoveryOf(digest: Digest): Boolean? =
        db.query("SELECT recovery FROM blobs WHERE digest = ?", digest.hex) { it.bool("recovery") }.firstOrNull()

    private fun directory(recovery: Boolean): Path = if (recovery) layout.blobsRecovery else layout.blobs

    private fun agedOut(file: Path, cutoff: Instant): Boolean {
        val modified = modifiedAt(file) ?: return true
        return !modified.isAfter(cutoff)
    }

    private fun modifiedAt(file: Path): Instant? = try {
        Files.getLastModifiedTime(file, NOFOLLOW_LINKS).toInstant()
    } catch (_: java.nio.file.NoSuchFileException) {
        null
    }

    public companion object {
        /** Cleanup directory entries and rows examined per [gc] pass; the bound keeps a large backlog from stalling a campaign. */
        public const val MAX_ORPHANS_PER_PASS: Int = 4_096

        /** How long an unreferenced blob survives, so an in-flight writer is never collected under it. */
        @JvmField
        public val DEFAULT_GRACE: Duration = Duration.ofHours(1)

        /** Identity of a blob recovered from the filesystem: the store itself, not a campaign. */
        private val ADOPTED_IDS = Identities(work = WorkId("store"), attempt = AttemptId("recovery"))
    }
}
