package io.astrolabe.verify

import io.astrolabe.atlas.HostProbe
import java.nio.file.Files
import java.nio.file.Path

/**
 * P8.C.15: why a check whose command is `gradle …` cannot run when no `gradle` resolves on `PATH` — the typed reason its
 * `unavailable` receipt carries to the result. A wrapper beside it is named, never run in gradle's place nor read: one
 * that appeared after the checks were declared may be the model's code (the declared check would then name the wrapper).
 */
internal object GradleWrapper {
    /** The reason for [argv] declared for a check running in [cwd] under the run's [root]; `null` when it is not an off-PATH `gradle`. */
    fun missing(argv: List<String>, root: Path, cwd: Path, probe: HostProbe): String? {
        if (argv.firstOrNull() != PROGRAM || probe.onPath(PROGRAM)) return null
        val dir = runCatching { root.toRealPath().relativize(cwd.toRealPath()).joinToString("/") }.getOrNull()?.ifEmpty { "the workspace root" }
            ?: "the check's directory"
        val wrapper = WRAPPERS.firstOrNull { Files.isRegularFile(cwd.resolve(it)) }
            ?: return "$PROGRAM is not on PATH and $dir has no Gradle wrapper: the Gradle check cannot run"
        return "$PROGRAM is not on PATH; $dir holds the wrapper $wrapper, which is not run in gradle's place: declare the check through the wrapper"
    }

    private const val PROGRAM = "gradle"
    private val WRAPPERS = listOf("gradlew.bat", "gradlew")
}
