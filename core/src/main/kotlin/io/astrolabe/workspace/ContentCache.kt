package io.astrolabe.workspace

import io.astrolabe.id.Digest
import io.astrolabe.id.Hashing
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
 * A hit needs the size, the nanosecond modification time and the file key observed **before** the
 * cached read. Like git's racily-clean rule, content read less than [RACY_WINDOW_NANOS] after its
 * modification time is never cached: a same-size rewrite within one timestamp tick would otherwise
 * keep a stale digest.
 */
internal class ContentCache(private val clock: Clock = Clock.systemUTC()) {

    /** What a read produced: the raw digest, the size, and git object ids by hash algorithm. */
    internal class Content(val digest: Digest, val sizeBytes: Long, val objectIds: Map<String, String>)

    private data class Observed(val sizeBytes: Long, val modifiedNanos: Long, val fileKey: Any?)

    private class Entry(val observed: Observed, val content: Content)

    private val entries = ConcurrentHashMap<Path, Entry>()

    /** Content reads performed so far; a hit performs none. */
    internal val reads: AtomicLong = AtomicLong()

    /**
     * The content of the regular file [file], read through [read] unless a valid entry exists. With
     * [objectAlgorithm] (`SHA-1` or `SHA-256`) the result also carries the git blob id under it.
     * `null` when [read] finds no file.
     */
    fun of(file: Path, objectAlgorithm: String?, read: () -> ByteArray?): Content? {
        val before = observe(file)
        val cached = entries[file]
        if (before != null && cached != null && cached.observed == before &&
            (objectAlgorithm == null || objectAlgorithm in cached.content.objectIds)
        ) return cached.content
        val readAt = nowNanos()
        val bytes = read()
        if (bytes == null) {
            entries.remove(file)
            return null
        }
        reads.incrementAndGet()
        val objectIds = HashMap<String, String>()
        if (cached != null && before != null && cached.observed == before) objectIds.putAll(cached.content.objectIds)
        if (objectAlgorithm != null) objectIds[objectAlgorithm] = objectId(objectAlgorithm, bytes)
        val content = Content(Digest.of(bytes), bytes.size.toLong(), objectIds)
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
        else Observed(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey())
    } catch (_: IOException) {
        null
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
