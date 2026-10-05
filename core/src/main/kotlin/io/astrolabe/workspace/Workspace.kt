package io.astrolabe.workspace

import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.Git
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path

/**
 * One editable tree the harness works in (§3.3, §4.6).
 *
 * [id] is the namespace qualifier of every version, coverage entry, stamp and shadow ref: worktrees
 * of one repository share a project store and are told apart by this id alone, and shared content
 * hashes therefore never share edit authority across workspaces (F2, FX-51).
 *
 * The workspace root is the repository's working-tree root, held as a **real** path, and [paths] is
 * the single entry point every built-in filesystem operation goes through (D-47).
 */
public class Workspace @JvmOverloads public constructor(
    public val id: WorkspaceId,
    root: Path,
    public val git: Git,
    protectedPaths: ProtectedPaths = ProtectedPaths(),
) {
    /** Canonical real path of the working-tree root. */
    public val root: Path = root.toRealPath()

    /** The path contract bound to [root]; look, edit, registry, stamps and closures share it. */
    public val paths: WorkspacePath = WorkspacePath.of(this.root, protectedPaths)

    /**
     * Runtime writers of this workspace are serialized here (§9.1 "a hash is not a lock", D-26): an edit batch
     * holds it from preflight through publication, a check that claims `exclusive` inputs holds it for its whole
     * run (D-45). It excludes nothing outside this process; the project lock does that (D-44).
     */
    public val mutation: Mutex = Mutex()

    /**
     * P8.C.12: background runs still live after a stop's verification settled them (a cancellation not delivered or not
     * confirmed). While any is, no receipt certifies a tree of this workspace — whichever scheduler reads it. Each stop
     * sets it.
     */
    @Volatile
    internal var unquiet: List<String> = emptyList()

    /**
     * False when the repository sets `core.fileMode=false` (D-293): the filesystem's executable bit is then
     * noise (WSL/NTFS, vfat) and git's reported/index mode is the mode. Read once per workspace.
     */
    internal val fileModeTrusted: Boolean by lazy { git.configBool("core.fileMode") != false }

    /** The repository's object-name hash as a JDK algorithm, for a capture that knows no object id yet (an empty repository). */
    internal val objectAlgorithm: String by lazy { git.objectAlgorithm() }

    private val filesReadCount = java.util.concurrent.atomic.AtomicLong()
    private val bytesReadCount = java.util.concurrent.atomic.AtomicLong()

    /** §7.2 phase counter: files [bytes] read from this tree, ever (monotonic; callers take differences). */
    internal val filesRead: Long get() = filesReadCount.get()

    /** §7.2 phase counter: bytes [bytes] read from this tree, ever (monotonic). */
    internal val bytesRead: Long get() = bytesReadCount.get()

    private val blobsReadCount = java.util.concurrent.atomic.AtomicLong()
    private val blobBytesReadCount = java.util.concurrent.atomic.AtomicLong()

    /** §7.2 phase counter: store blobs read back on this tree's behalf (capture, snapshot, restore), ever (monotonic). */
    internal val blobsRead: Long get() = blobsReadCount.get()

    /** §7.2 phase counter: bytes of [blobsRead], ever (monotonic). */
    internal val blobBytesRead: Long get() = blobBytesReadCount.get()

    /** Counts one store blob of [sizeBytes] read on this tree's behalf (§7.2). */
    internal fun blobRead(sizeBytes: Int) {
        blobsReadCount.incrementAndGet()
        blobBytesReadCount.addAndGet(sizeBytes.toLong())
    }

    /** Content digests every [Stamper] of this workspace shares for the workspace's lifetime (D-364). */
    internal val contents: ContentCache = ContentCache()

    init {
        val repo = runCatching { git.repo.toRealPath() }.getOrDefault(git.repo)
        require(this.root == repo) {
            "workspace root ${this.root} must be the git working-tree root, which is $repo"
        }
        // The newest workspace over a root wins; removing first keeps the map's weak key this instance's own root.
        synchronized(LIVE) {
            LIVE.remove(this.root)
            LIVE[this.root] = java.lang.ref.WeakReference(this)
        }
    }

    /** Shorthand for `paths.resolve(userPath, intent)`. */
    @JvmOverloads
    public fun resolve(userPath: String, intent: Intent = Intent.Read): PathResolution =
        paths.resolve(userPath, intent)

    /**
     * The raw bytes of [resolved], with no filter, decoding or end-of-line conversion — what the
     * version registry hashes and what a snapshot manifest records (I-12, D-53). `null` when the
     * file is not there.
     */
    public fun bytes(resolved: PathResolution.Resolved): ByteArray? = bytesAt(resolved.real)

    /**
     * [bytes] of a real path the caller already resolved inside this tree (the atlas), counted the same way. WD-05: a
     * file that exists but cannot be read (another process holds a lock, access is denied) is retried a bounded number
     * of times and then named by [UnreadableInput] — never read as absent, so it never leaves a candidate's identity.
     */
    internal fun bytesAt(real: Path): ByteArray? {
        var attempt = 1
        while (true) {
            try {
                return if (Files.isDirectory(real)) null else Files.readAllBytes(real).also { read ->
                    filesReadCount.incrementAndGet()
                    bytesReadCount.addAndGet(read.size.toLong())
                }
            } catch (missing: java.nio.file.NoSuchFileException) {
                return null
            } catch (refused: java.io.IOException) {
                if (attempt >= READ_ATTEMPTS) throw UnreadableInput(relative(real), attempt, refused)
                try {
                    Thread.sleep(READ_RETRY_MILLIS * attempt)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw UnreadableInput(relative(real), attempt, refused)
                }
                attempt++
            }
        }
    }

    private fun relative(real: Path): String =
        if (real.startsWith(root)) root.relativize(real).toString().replace('\\', '/') else real.toString()

    override fun toString(): String = "Workspace(${id.value} at $root)"
}

/**
 * A file of the tree that exists and could not be read after [attempts] tries (WD-05): another process holds it locked
 * or access is denied. It is an unresolved input named by [path] (workspace-relative), never a missing file, so no
 * stamp or snapshot is taken without it; the run stops resumably on it instead of failing.
 */
public class UnreadableInput(
    public val path: String,
    public val attempts: Int,
    cause: java.io.IOException,
) : java.io.IOException("'$path' exists but could not be read after $attempts attempts: ${cause.message}", cause)

private const val READ_ATTEMPTS = 3
private const val READ_RETRY_MILLIS = 50L

/** The newest live workspace over each root, both held weakly (§7.2 counters). */
private val LIVE: MutableMap<Path, java.lang.ref.WeakReference<Workspace>> = java.util.WeakHashMap()

/**
 * The newest live [Workspace] over [root], or `null`: a reader handed only a root (the atlas at open) runs its git
 * commands and reads through it, so the phase counters see them (§7.2).
 */
internal fun liveWorkspace(root: Path): Workspace? = synchronized(LIVE) { LIVE[root.toAbsolutePath().normalize()]?.get() }
