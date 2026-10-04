package io.astrolabe.verify

import io.astrolabe.atlas.HostProbe
import io.astrolabe.atlas.OsFamily
import io.astrolabe.workspace.Intent
import io.astrolabe.workspace.PathResolution
import io.astrolabe.workspace.WorkspacePath
import java.nio.file.Files
import java.nio.file.Path

/**
 * P8.C.15: a check whose command is `gradle …` while no `gradle` resolves on `PATH` runs through the repository's own
 * wrapper in the check's directory — `gradlew.bat` on Windows, `gradlew` elsewhere — found through [WorkspacePath], so a
 * link out of the tree is never followed. The receipt keeps the declared command and records the substitution; with
 * neither, the declared command is launched as before and its `unavailable` receipt carries [Plan.Missing]'s reason.
 */
internal object GradleWrapper {
    sealed interface Plan {
        /** What the runner starts. */
        val argv: List<String>

        data class Direct(override val argv: List<String>) : Plan

        data class Wrapped(override val argv: List<String>, val note: String) : Plan

        data class Missing(override val argv: List<String>, val reason: String) : Plan
    }

    /** How [argv], declared for a check running in [cwd] under the run's [root], is launched. */
    fun plan(argv: List<String>, root: Path, cwd: Path, probe: HostProbe): Plan {
        if (argv.firstOrNull() != PROGRAM || probe.onPath(PROGRAM)) return Plan.Direct(argv)
        val name = if (probe.os == OsFamily.Windows) "gradlew.bat" else "gradlew"
        val dir = runCatching { root.toRealPath().relativize(cwd.toRealPath()).joinToString("/") }.getOrNull()
            ?: return Plan.Missing(argv, "$PROGRAM is not on PATH and the check's directory is outside the run's tree")
        val relative = if (dir.isEmpty()) name else "$dir/$name"
        val wrapper = (WorkspacePath.of(root).resolve(relative, Intent.Read) as? PathResolution.Resolved)?.real?.takeIf { Files.isRegularFile(it) }
            ?: return Plan.Missing(argv, "$PROGRAM is not on PATH and ${dir.ifEmpty { "the workspace root" }} has no $name wrapper: the Gradle check cannot run")
        return Plan.Wrapped(listOf(wrapper.toString()) + argv.drop(1), "$PROGRAM is not on PATH: ran the repository's wrapper $relative instead")
    }

    private const val PROGRAM = "gradle"
}
