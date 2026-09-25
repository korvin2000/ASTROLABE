package io.astrolabe.workspace

import io.astrolabe.id.AttemptId
import io.astrolabe.id.CandidateId
import io.astrolabe.id.Digest
import io.astrolabe.id.WorkId
import io.astrolabe.id.WorkspaceId
import io.astrolabe.os.FileMode
import io.astrolabe.os.Git
import io.astrolabe.os.ObjectId
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * One writer's or one integration's own editable tree (§10.4): a detached `git worktree` under `candidates/` that
 * reproduces the main line's candidate [base] at creation. Its [workspace] id qualifies every version, coverage entry,
 * stamp and shadow ref it produces (F02), so the same path and hash in two worktrees share nothing (FX-51). A worktree
 * is edit isolation, not a security boundary.
 */
public data class Worktree(
    val workspace: Workspace,
    val work: WorkId,
    val attempt: AttemptId,
    val increment: String,
    /** The commit the worktree was added at, detached: no branch is created, so none is ever removed. */
    val baseCommit: ObjectId,
    /** The main line's candidate at creation, which the worktree's own stamp equals. */
    val base: CandidateId,
) {
    val id: WorkspaceId get() = workspace.id
    val root: Path get() = workspace.root
}

/** A worktree that cannot be created as a faithful copy of the main candidate, or cannot be removed. */
public class WorktreeRefused(message: String) : IllegalStateException(message)

/**
 * The worktrees of one main [main] workspace (§10.4, D-180). [createWorktree] adds a detached worktree at the main
 * line's base commit and copies the main candidate's tracked delta and untracked files into it, raw bytes through the
 * path contract of both workspaces (D-47), then checks that the new tree stamps equal to the main candidate under
 * [env]; anything it cannot reproduce (a symlink or directory entry, a concurrent change) refuses and removes the
 * worktree. [remove] only ever removes a worktree this instance created, with `git worktree remove`, which never
 * touches a branch or a ref.
 */
public class Workspaces(
    public val main: Workspace,
    candidates: Path,
    private val env: EnvFingerprint,
) {
    private val root: Path = candidates.resolve("worktrees")
    private val open = LinkedHashMap<WorkspaceId, Worktree>()

    /** The worktrees created and not yet removed, in creation order. */
    public val worktrees: List<Worktree> get() = synchronized(open) { open.values.toList() }

    public fun createWorktree(work: WorkId, attempt: AttemptId, increment: String): Worktree {
        require(increment.isNotBlank()) { "a worktree belongs to an increment" }
        val id = idOf(work, attempt, increment)
        synchronized(open) { require(id !in open) { "increment $increment of ${work.value}/${attempt.value} already has worktree ${id.value}" } }
        val report = Stamper(main, env).report()
        val commit = ObjectId.parseOrNull(report.baseCommit)
            ?: throw WorktreeRefused("the main line has no base commit to add a worktree at")
        val dir = root.resolve(id.value)
        if (Files.exists(dir)) throw WorktreeRefused("$dir already exists; a worktree is never created over existing files")
        Files.createDirectories(root)
        main.git.worktreeAdd(dir, commit)
        val worktree = try {
            val workspace = Workspace(id, dir, Git(dir, main.git.executable), main.paths.protectedPaths)
            copyDelta(report, workspace)
            val own = Stamper(workspace, env).report()
            if (own.candidateId != report.candidateId) {
                throw WorktreeRefused("worktree ${id.value} stamps @${own.candidateId.hash8}, the main candidate @${report.candidateId.hash8}: differing ${Stamper.diff(report, own).sorted()}")
            }
            Worktree(workspace, work, attempt, increment, commit, report.candidateId)
        } catch (failure: Throwable) {
            runCatching { main.git.worktreeRemove(dir, force = true) }
            throw failure
        }
        synchronized(open) { open[id] = worktree }
        return worktree
    }

    /** Removes [worktree] and its directory; its shadow refs and every user branch stay. */
    public fun remove(worktree: Worktree) {
        synchronized(open) {
            val known = open[worktree.id] ?: throw WorktreeRefused("worktree ${worktree.id.value} was not created here; refusing to remove it")
            check(known.root == worktree.root) { "worktree ${worktree.id.value} moved" }
        }
        main.git.worktreeRemove(worktree.root, force = true)
        synchronized(open) { open.remove(worktree.id) }
    }

    private fun copyDelta(report: StampReport, target: Workspace) {
        for (entry in report.trackedDelta + report.untracked) {
            val destination = target.resolve(entry.path, Intent.Mutate) as? PathResolution.Resolved
                ?: throw WorktreeRefused("the worktree's path contract refuses ${entry.path}")
            when (entry.type) {
                EntryType.Deleted -> Files.deleteIfExists(destination.real)
                EntryType.File -> {
                    val source = main.resolve(entry.path, Intent.Read) as? PathResolution.Resolved
                        ?: throw WorktreeRefused("the main line's path contract refuses ${entry.path}")
                    val bytes = main.bytes(source) ?: throw WorktreeRefused("${entry.path} vanished from the main line while its worktree was created")
                    destination.real.parent?.let(Files::createDirectories)
                    Files.write(destination.real, bytes)
                    if (POSIX && entry.mode == FileMode.EXECUTABLE) {
                        Files.setPosixFilePermissions(destination.real, Files.getPosixFilePermissions(destination.real) + PosixFilePermission.OWNER_EXECUTE)
                    }
                }
                EntryType.Symlink, EntryType.Directory -> throw WorktreeRefused("a ${entry.type.name.lowercase()} delta entry (${entry.path}) is not reproduced in a worktree (D-180)")
            }
        }
    }

    public companion object {
        private val POSIX: Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

        /**
         * `wt-<increment>-<hash8>`: readable, a valid git ref component (the shadow ref embeds it) and short enough for
         * Windows paths; the hash of work, attempt and increment keeps two increments with one spelling apart.
         */
        @JvmStatic
        public fun idOf(work: WorkId, attempt: AttemptId, increment: String): WorkspaceId {
            val readable = increment.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }.joinToString("").take(24)
            return WorkspaceId("wt-$readable-${Digest.ofUtf8("${work.value}\u0000${attempt.value}\u0000$increment").hash8}")
        }
    }
}
