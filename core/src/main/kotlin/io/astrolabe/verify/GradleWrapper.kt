package io.astrolabe.verify

import io.astrolabe.atlas.HostProbe
import io.astrolabe.atlas.OsFamily
import io.astrolabe.id.FileVersion
import io.astrolabe.os.UntrackedFiles
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.Snapshot
import io.astrolabe.workspace.SnapshotEntryKind
import io.astrolabe.workspace.Workspace
import io.astrolabe.workspace.WorkspacePath
import java.nio.file.Files
import java.nio.file.Path

/**
 * P8.C.15: a check whose command is `gradle …` while no `gradle` resolves on `PATH` runs through the repository's own
 * wrapper in the check's directory — `gradlew.bat` on Windows, `gradlew` elsewhere — found through [WorkspacePath], so a
 * link out of the tree is never followed, and only when its bytes are the base tree's (s0): a wrapper written or changed
 * in the task would be the model's code run on the check's authority. The receipt keeps the declared command and records
 * the substitution; otherwise the declared command is launched as before and its `unavailable` receipt carries
 * [Plan.Missing]'s reason.
 */
internal object GradleWrapper {
    sealed interface Plan {
        /** What the runner starts. */
        val argv: List<String>

        data class Direct(override val argv: List<String>) : Plan

        data class Wrapped(override val argv: List<String>, val note: String) : Plan

        data class Missing(override val argv: List<String>, val reason: String) : Plan
    }

    /**
     * How [argv], declared for a check running in [cwd] under the run's [root], is launched; [fromBase] says whether the
     * wrapper at its root-relative path, resolved to the real file, holds the base tree's bytes.
     */
    fun plan(argv: List<String>, root: Path, cwd: Path, probe: HostProbe, fromBase: (relative: String, real: Path) -> Boolean): Plan {
        if (argv.firstOrNull() != PROGRAM || probe.onPath(PROGRAM)) return Plan.Direct(argv)
        val name = if (probe.os == OsFamily.Windows) "gradlew.bat" else "gradlew"
        val dir = runCatching { root.toRealPath().relativize(cwd.toRealPath()).joinToString("/") }.getOrNull()
            ?: return Plan.Missing(argv, "$PROGRAM is not on PATH and the check's directory is outside the run's tree")
        val relative = if (dir.isEmpty()) name else "$dir/$name"
        val wrapper = (WorkspacePath.of(root).resolve(relative, Intent.Read) as? PathResolution.Resolved)?.real?.takeIf { Files.isRegularFile(it) }
            ?: return Plan.Missing(argv, "$PROGRAM is not on PATH and ${dir.ifEmpty { "the workspace root" }} has no $name wrapper: the Gradle check cannot run")
        if (!fromBase(relative, wrapper)) {
            return Plan.Missing(argv, "$PROGRAM is not on PATH and the wrapper $relative is not the base tree's (written or changed in this task, or no base commit): it is not run in gradle's place")
        }
        return Plan.Wrapped(listOf(wrapper.toString()) + argv.drop(1), "$PROGRAM is not on PATH: ran the repository's wrapper $relative instead")
    }

    /**
     * Whether [bytes] are [path]'s bytes in [s0]: a file s0 captured as dirty by its raw digest; any other by git — tracked
     * at s0's base commit, `HEAD` still there, and unchanged in `git status`, which reads line endings as the checkout does.
     */
    fun atS0(workspace: Workspace, s0: Snapshot, path: String, bytes: ByteArray): Boolean {
        s0.entry(path)?.let { return it.kind == SnapshotEntryKind.File && it.digest == FileVersion.of(bytes).digest }
        val git = workspace.git
        return runCatching {
            git.revParse("HEAD").hex == s0.baseCommit &&
                git.revParse("${s0.baseCommit}:$path").hex.isNotEmpty() &&
                git.status(UntrackedFiles.NO).entries.none { it.path == path } &&
                (WorkspacePath.of(workspace.root).resolve(path, Intent.Read) as? PathResolution.Resolved)?.real
                    ?.let { Files.isRegularFile(it) && FileVersion.of(Files.readAllBytes(it)) == FileVersion.of(bytes) } == true
        }.getOrDefault(false)
    }

    private const val PROGRAM = "gradle"
}
