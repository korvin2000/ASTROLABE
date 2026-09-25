package io.astrolabe.store

import io.astrolabe.id.AttemptId
import io.astrolabe.id.Digest
import io.astrolabe.id.Identities
import io.astrolabe.id.WorkId
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import kotlin.streams.asSequence

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

    /** Where [digest] lives once published; the file may not exist yet. */
    public fun path(digest: Digest, recovery: Boolean = false): Path =
        directory(recovery).resolve(digest.hex)

    /**
     * Collects what nothing references, adopts what does. In order: abandoned `blobs/tmp` files are
     * discarded, published files missing their row are adopted when [referenced] names them, every
     * referenced digest is then checked (a missing one throws [MissingBlob] *before* anything is
     * deleted, so an integrity failure never compounds), and finally unreferenced blobs older than
     * [grace] are removed.
     *
     * The pass is bounded by [MAX_ORPHANS_PER_PASS] files so a store with a large orphan backlog
     * still makes progress in bounded time; [BlobGc.bounded] says whether more work remains.
     */
    public fun gc(referenced: Set<Digest>, grace: Duration = DEFAULT_GRACE): BlobGc {
        val now = clock.instant()
        val cutoff = now.minus(grace)
        var budget = MAX_ORPHANS_PER_PASS
        var bounded = false

        var discardedTemp = 0
        for (temp in listFiles(layout.blobsTemp)) {
            if (budget-- <= 0) { bounded = true; break }
            if (agedOut(temp, cutoff)) {
                Files.deleteIfExists(temp)
                discardedTemp++
            }
        }

        val known = db.query("SELECT digest FROM blobs") { it.string("digest") }.toHashSet()
        var adopted = 0
        orphans@ for (recovery in listOf(false, true)) {
            for (file in listFiles(directory(recovery))) {
                val name = file.fileName.toString()
                val digest = runCatching { Digest(name) }.getOrNull() ?: continue
                if (name in known) continue
                if (budget-- <= 0) { bounded = true; break@orphans }
                if (digest in referenced && Digest.of(Files.readAllBytes(file)) == digest) {
                    // The kind is not recoverable from the filesystem; the row records the recovery
                    // provenance instead of guessing.
                    insertRow(digest, Files.size(file), BlobKind.OUTPUT, ADOPTED_IDS, recovery)
                    adopted++
                } else if (agedOut(file, cutoff)) {
                    Files.deleteIfExists(file)
                }
            }
        }

        // §4.3: check integrity before collecting, so a missing artifact is reported rather than
        // masked by the deletions of the same pass.
        for (digest in referenced.sortedBy { it.hex }) {
            if (!exists(digest)) throw MissingBlob(digest, "referenced blob ${digest.hex} is missing from ${layout.blobs}")
        }

        val collectable = db.query(
            "SELECT digest, recovery FROM blobs WHERE created_at <= ? ORDER BY digest",
            cutoff.toString(),
        ) { it.string("digest") to it.bool("recovery") }
        var collected = 0
        for ((hex, recovery) in collectable) {
            if (Digest(hex) in referenced) continue
            Files.deleteIfExists(directory(recovery).resolve(hex))
            db.tx { it.execute("DELETE FROM blobs WHERE digest = ?", hex) }
            collected++
        }
        return BlobGc(collected = collected, adopted = adopted, discardedTemp = discardedTemp, bounded = bounded)
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
            channel.write(ByteBuffer.wrap(bytes))
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

    private fun listFiles(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { stream ->
            stream.asSequence().filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.toList()
        }
    }

    private fun modifiedAt(file: Path): Instant? =
        runCatching { Files.getLastModifiedTime(file).toInstant() }.getOrNull()

    public companion object {
        /** Orphans examined per [gc] pass; the bound keeps a large backlog from stalling a campaign. */
        public const val MAX_ORPHANS_PER_PASS: Int = 4_096

        /** How long an unreferenced blob survives, so an in-flight writer is never collected under it. */
        @JvmField
        public val DEFAULT_GRACE: Duration = Duration.ofHours(1)

        /** Identity of a blob recovered from the filesystem: the store itself, not a campaign. */
        private val ADOPTED_IDS = Identities(work = WorkId("store"), attempt = AttemptId("recovery"))
    }
}
