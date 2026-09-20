package io.astrolabe.store

import io.astrolabe.Astrolabe
import io.astrolabe.id.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Who owns a project store right now; written into `controller.lock` so a loser can say so. */
@Serializable
public data class LockHolder(
    val pid: Long,
    @Serializable(with = InstantSerializer::class) val startedAt: Instant,
    val harnessVersion: String,
)

/**
 * A second controller tried to own a project store. [holder] is the owner as recorded in the lock
 * file, or `null` when the record could not be read — a crash can leave the file empty, and a read
 * can race a rewrite, so an absent holder is a missing diagnostic and never evidence that the lock
 * is stale.
 */
public class ProjectLockHeld(
    public val lockFile: Path,
    public val holder: LockHolder?,
) : IllegalStateException(
    "project store at ${lockFile.parent} is already owned by " +
        (holder?.let { "pid ${it.pid} (harness ${it.harnessVersion}, since ${it.startedAt})" } ?: "another controller"),
)

/**
 * The OS file lock that enforces **one controller process per project store** (D-44, D-26). A
 * process-local mutex would exclude nothing outside the process, so ownership is an exclusive
 * `FileLock` on `controller.lock`.
 *
 * OS file locks are per *process*: a second [acquire] from the same JVM would either be granted or
 * raise `OverlappingFileLockException` depending on the channel, so this class also keeps a
 * process-wide registry of held roots. A second acquisition therefore fails the same way whether it
 * comes from another process or from this one, which is what makes the in-process test meaningful.
 */
public class ProjectLock private constructor(
    public val layout: Layout,
    public val holder: LockHolder,
    private val key: String,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    /** Releases ownership. Idempotent: closing twice is not an error. */
    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } finally {
            try {
                channel.close()
            } finally {
                HELD.remove(key)
            }
        }
    }

    public companion object {
        private val HELD = ConcurrentHashMap<String, LockHolder>()
        private val JSON = Json { prettyPrint = false }

        /**
         * The single byte the lock covers, past any content the file will ever hold. Windows
         * byte-range locks are mandatory: locking the whole file would make the holder record
         * unreadable to exactly the process that needs to report who owns the store. Locking a
         * sentinel region beyond the record keeps ownership exclusive and the record readable.
         */
        private const val OWNERSHIP_REGION: Long = Long.MAX_VALUE - 1

        /**
         * Takes ownership of [layout]'s store, or throws [ProjectLockHeld]. The layout's
         * directories must already exist ([Layout.create]).
         */
        @JvmStatic
        public fun acquire(
            layout: Layout,
            clock: Clock,
            harnessVersion: String = Astrolabe.VERSION,
        ): ProjectLock {
            val key = layout.root.toAbsolutePath().normalize().toString()
            val holder = LockHolder(ProcessHandle.current().pid(), clock.instant(), harnessVersion)
            HELD.putIfAbsent(key, holder)?.let { throw ProjectLockHeld(layout.lockFile, it) }
            var owned = false
            val channel = try {
                FileChannel.open(
                    layout.lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ,
                )
            } catch (failure: IOException) {
                HELD.remove(key)
                throw failure
            }
            try {
                val lock = try {
                    channel.tryLock(OWNERSHIP_REGION, 1L, false)
                } catch (overlapping: OverlappingFileLockException) {
                    null
                } ?: throw ProjectLockHeld(layout.lockFile, readHolder(layout.lockFile))
                channel.truncate(0)
                channel.position(0)
                val recorded = JSON.encodeToString(LockHolder.serializer(), holder)
                channel.write(ByteBuffer.wrap(recorded.toByteArray(StandardCharsets.UTF_8)))
                channel.force(true)
                owned = true
                return ProjectLock(layout, holder, key, channel, lock)
            } finally {
                if (!owned) {
                    HELD.remove(key)
                    channel.close()
                }
            }
        }

        /** Best-effort read of the recorded owner; unreadable or malformed content yields `null`. */
        private fun readHolder(lockFile: Path): LockHolder? = runCatching {
            val text = String(Files.readAllBytes(lockFile), StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) null else JSON.decodeFromString(LockHolder.serializer(), text)
        }.getOrNull()
    }
}
