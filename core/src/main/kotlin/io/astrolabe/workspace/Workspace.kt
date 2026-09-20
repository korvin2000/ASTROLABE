package io.astrolabe.workspace

import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.Git
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

    init {
        val repo = runCatching { git.repo.toRealPath() }.getOrDefault(git.repo)
        require(this.root == repo) {
            "workspace root ${this.root} must be the git working-tree root, which is $repo"
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
    public fun bytes(resolved: PathResolution.Resolved): ByteArray? = try {
        if (Files.isDirectory(resolved.real)) null else Files.readAllBytes(resolved.real)
    } catch (missing: java.nio.file.NoSuchFileException) {
        null
    }

    override fun toString(): String = "Workspace(${id.value} at $root)"
}
