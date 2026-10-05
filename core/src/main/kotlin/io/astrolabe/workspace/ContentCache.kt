package io.astrolabe.workspace

import io.astrolabe.id.Digest
import io.astrolabe.id.Hashing
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw-content digests of regular files, shared by every [Stamper] of one [Workspace] so an unchanged
 * file is not re-read on every stamp (D-364). The digests are the ones a fresh read would produce, so
 * stamps are byte-identical with or without a hit.
 *
 * A hit needs the size, the nanosecond modification time, the file key and — where the file system
 * has the `unix` attribute view — the status-change time observed **before** the cached read. User
 * tools can restore a modification time (`cp -p`, `touch -r`, archive extraction) but not a ctime, so
 * without ctime a same-size rewrite with a restored mtime keeps a stale digest; acceptance therefore
 * never trusts this cache alone ([Stamper.report] with `fresh`). Like git's racily-clean rule, content
 * read less than [RACY_WINDOW_NANOS] after its modification time is never cached: a same-size
 * rewrite within one timestamp tick would otherwise keep a stale digest.
 */
internal class ContentCache(private val clock: Clock = Clock.systemUTC()) {

    /** What a read produced: the raw digest, the size, and git object ids by hash algorithm. */
    internal class Content(val digest: Digest, val sizeBytes: Long, val objectIds: Map<String, String>)

    internal data class Observed(val sizeBytes: Long, val modifiedNanos: Long, val fileKey: Any?, val changedNanos: Long?)

    internal class Entry(val observed: Observed, val content: Content)

    private val entries = ConcurrentHashMap<Path, Entry>()

    /** Content reads performed so far; a hit performs none. */
    internal val reads: AtomicLong = AtomicLong()

    /**
     * The content of the regular file [file], read through [read] unless a valid entry exists. With
     * [objectAlgorithm] (`SHA-1` or `SHA-256`) the result also carries the git blob id under it.
     * `null` when [read] finds no file.
     */
    fun of(file: Path, objectAlgorithm: String?, read: () -> ByteArray?): Content? =
        cached(file, objectAlgorithm) ?: load(file, objectAlgorithm, read)

    /** The valid entry for [file] (carrying [objectAlgorithm] when given), without reading any content. */
    fun cached(file: Path, objectAlgorithm: String? = null): Content? {
        val before = observe(file) ?: return null
        val cached = entries[file] ?: return null
        return cached.content.takeIf {
            cached.observed == before && (objectAlgorithm == null || objectAlgorithm in it.objectIds)
        }
    }

    /**
     * The reads of one capture (WD-02), by real path: what each file looked like just before it was read and what the
     * read held. A capture's manifest, stamp and integrity recheck share these instead of reading the file again, so the
     * manifest and the stamp always describe the same bytes. Unlike the shared cache this holds racy reads too: a
     * rewrite within one timestamp tick after the read is not seen by the recheck, but nothing reads from here after the
     * capture, and the shared cache never keeps such a read, so the next capture reads the file again.
     */
    internal class Reads {
        internal val taken = HashMap<Path, Entry>()
    }

    /**
     * [file]'s content inside one capture: what [reads] already took while the file still looks as it did before that
     * read, else one read — from disk with [fresh], otherwise through this cache — recorded in [reads]. A fresh capture
     * therefore reads every file from disk exactly once (D-374), and metadata never stands in for a read across captures.
     */
    fun within(reads: Reads, file: Path, objectAlgorithm: String?, fresh: Boolean, read: () -> ByteArray?): Content? {
        val now = observe(file)
        val seen = reads.taken[file]
        if (seen != null && now != null && seen.observed == now && (objectAlgorithm == null || objectAlgorithm in seen.content.objectIds)) {
            return seen.content
        }
        val content = if (fresh) load(file, objectAlgorithm, read) else of(file, objectAlgorithm, read)
        if (content == null || now == null) reads.taken.remove(file) else reads.taken[file] = Entry(now, content)
        return content
    }

    /** Reads [file] through [read] unconditionally and records the result when it may be trusted later. */
    fun load(file: Path, objectAlgorithm: String?, read: () -> ByteArray?): Content? {
        val before = observe(file)
        val cached = entries[file]
        val readAt = nowNanos()
        val bytes = read()
        if (bytes == null) {
            entries.remove(file)
            return null
        }
        reads.incrementAndGet()
        val digest = Digest.of(bytes)
        val objectIds = HashMap<String, String>()
        if (cached != null && cached.content.digest == digest) objectIds.putAll(cached.content.objectIds)
        if (objectAlgorithm != null) objectIds[objectAlgorithm] = objectId(objectAlgorithm, bytes)
        val content = Content(digest, bytes.size.toLong(), objectIds)
        if (before != null && before.sizeBytes == content.sizeBytes && readAt - before.modifiedNanos >= RACY_WINDOW_NANOS) {
            entries[file] = Entry(before, content)
        } else {
            entries.remove(file)
        }
        return content
    }

    private fun observe(file: Path): Observed? = try {
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) null
        else Observed(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey(), changedNanos(file))
    } catch (_: IOException) {
        null
    }

    private fun changedNanos(file: Path): Long? {
        if ("unix" !in file.fileSystem.supportedFileAttributeViews()) return null
        return try {
            (Files.getAttribute(file, "unix:ctime", LinkOption.NOFOLLOW_LINKS) as? FileTime)?.to(TimeUnit.NANOSECONDS)
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun nowNanos(): Long = clock.instant().let { it.epochSecond * 1_000_000_000L + it.nano }

    internal companion object {
        val RACY_WINDOW_NANOS: Long = TimeUnit.SECONDS.toNanos(2)

        /** The id git gives [bytes] as a blob: `<algorithm>("blob <size>\0" + bytes)`. */
        fun objectId(algorithm: String, bytes: ByteArray): String {
            val hash = MessageDigest.getInstance(algorithm)
            hash.update("blob ${bytes.size}\u0000".toByteArray(StandardCharsets.US_ASCII))
            return Hashing.hex(hash.digest(bytes))
        }
    }
}
