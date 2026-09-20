package io.astrolabe.store

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * The directory layout of one project's durable state (§4, D-44, D-15).
 *
 * `root = <Config.stateRoot or OS user-state dir>/astrolabe/projects/<repo-identity>/`. Nothing here
 * lives inside the source tree and nothing lives under a disposable cache path, so state updates
 * never disturb workspace stamps and a cache eviction never destroys authority.
 *
 * | Entry | Contents | Disposable |
 * |---|---|---|
 * | `state.sqlite` | canonical structured records and event ordering | no |
 * | `blobs/<digest>` | captured bytes, preimages, diffs, logs, packets | no |
 * | `blobs/tmp/` | partially written blobs awaiting their atomic rename | yes |
 * | `blobs/recovery/` | bounded orphan recovery material, owner-only | no |
 * | `native/` | protected provider replay material (D-25), owner-only | no |
 * | `kb/` | exported knowledge-base Markdown views | yes (rebuildable) |
 * | `exports/` | derived human-readable views for a UI (risk 19) | yes (rebuildable) |
 * | `indexes/` | search and symbol indexes | yes |
 * | `candidates/` | worktrees or snapshot refs | no |
 * | `campaigns/` | frozen evaluation manifests | no |
 * | `controller.lock` | [ProjectLock] ownership file | yes |
 *
 * **Durability statement (D-44).**
 *
 * *Process termination* — the harness killed, an uncaught failure, the JVM exiting — is always
 * covered. [BlobStore.put] writes the bytes, flushes them with `FileChannel.force(true)` and
 * publishes them with an atomic rename, all before the transaction that references them; [Db] runs
 * with `journal_mode=WAL` and `synchronous=FULL`, so a committed transaction has already left the
 * process. A crash therefore leaves either no reference or a reference to bytes that exist.
 *
 * *OS crash and power loss* are covered only as far as the filesystem and the device honour fsync,
 * and only partly. The blob's **bytes** are flushed on every platform. The blob's **directory
 * entry** is flushed only where the JDK will open a directory as a channel: on Windows it will not
 * (`FileChannel.open` on a directory fails with `AccessDeniedException`, verified on JDK 26), so
 * there the rename's survival rests on NTFS metadata journaling. Power-loss behaviour is not
 * exercised by this project's tests on any platform, so it is stated as a limit, not a guarantee.
 *
 * *Guarantees that cannot be established fail closed* rather than degrade silently: if
 * `foreign_keys`, `journal_mode=WAL` or `synchronous=FULL` cannot be set and read back, [Db.open]
 * throws [StoreUnsupported] instead of opening a store weaker than the records assume.
 */
public data class Layout(val root: Path) {
    /** Canonical structured records (D-03). */
    val database: Path get() = root.resolve("state.sqlite")

    val blobs: Path get() = root.resolve("blobs")

    /** Staging area for [BlobStore.put]; every file here is either adopted or collected. */
    val blobsTemp: Path get() = blobs.resolve("tmp")

    /** Restricted: bounded orphan recovery material. */
    val blobsRecovery: Path get() = blobs.resolve("recovery")

    /** Restricted: protected provider replay material (D-25). */
    val native: Path get() = root.resolve("native")

    val kb: Path get() = root.resolve("kb")

    val exports: Path get() = root.resolve("exports")

    /** Disposable: rebuilt on demand, never an authority. */
    val indexes: Path get() = root.resolve("indexes")

    val candidates: Path get() = root.resolve("candidates")

    val campaigns: Path get() = root.resolve("campaigns")

    val lockFile: Path get() = root.resolve("controller.lock")

    /**
     * Creates every directory, and restricts [blobsRecovery] and [native] to the owner where the
     * filesystem supports POSIX permissions. On Windows the POSIX view does not exist, so the two
     * directories inherit the parent ACL: the restriction there is best effort and is *not* a
     * security boundary this project relies on.
     */
    public fun create(): Layout {
        for (directory in directories) Files.createDirectories(directory)
        for (directory in restricted) restrictToOwner(directory)
        return this
    }

    /** Every directory this layout owns, parents before children. */
    public val directories: List<Path>
        get() = listOf(root, blobs, blobsTemp, blobsRecovery, native, kb, exports, indexes, candidates, campaigns)

    private val restricted: List<Path> get() = listOf(blobsRecovery, native)

    private fun restrictToOwner(directory: Path) {
        val view = Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) ?: return
        try {
            view.setPermissions(OWNER_ONLY)
        } catch (ignored: IOException) {
            // Best effort: a filesystem may expose the POSIX view and still refuse chmod (some
            // network mounts). The restriction is a hardening measure, never a correctness one.
        }
    }

    public companion object {
        /** `rwx------`. */
        private val OWNER_ONLY: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

        /**
         * `<stateRoot ?: userStateDirectory()>/astrolabe/projects/<identity>`. [stateRoot] is the
         * host-configured root ([io.astrolabe.Config.stateRoot]); it is used verbatim, so a host can
         * place the state on a specific volume.
         */
        @JvmStatic
        public fun resolve(stateRoot: Path?, identity: RepoIdentity): Layout {
            val base = (stateRoot ?: userStateDirectory()).toAbsolutePath().normalize()
            return Layout(base.resolve("astrolabe").resolve("projects").resolve(identity.directoryName))
        }

        /**
         * The OS directory for durable per-user *state* — never a cache directory, because nothing
         * authoritative may live somewhere the OS is entitled to delete (D-15). Windows:
         * `%LOCALAPPDATA%`, falling back to `%APPDATA%`. Elsewhere: `$XDG_STATE_HOME`, falling back
         * to `~/.local/state`.
         */
        @JvmStatic
        public fun userStateDirectory(): Path {
            val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
            val home = Path.of(System.getProperty("user.home"))
            val configured = if (windows) {
                System.getenv("LOCALAPPDATA") ?: System.getenv("APPDATA")
            } else {
                System.getenv("XDG_STATE_HOME")
            }
            if (!configured.isNullOrBlank()) return Path.of(configured).toAbsolutePath().normalize()
            val fallback = if (windows) home.resolve("AppData").resolve("Local") else home.resolve(".local").resolve("state")
            return fallback.toAbsolutePath().normalize()
        }
    }
}
