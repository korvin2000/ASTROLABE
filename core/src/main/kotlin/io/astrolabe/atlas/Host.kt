package io.astrolabe.atlas

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The operating-system family the harness runs on, as the prime's host block names it (D-366). */
public enum class OsFamily(public val wire: String) {
    Windows("windows"),
    Linux("linux"),
    Macos("macos"),
    ;

    public companion object {
        /** The family of a `os.name` value; anything not Windows or macOS is treated as Linux (POSIX). */
        @JvmStatic
        public fun of(osName: String): OsFamily = when {
            osName.startsWith("Windows", ignoreCase = true) -> Windows
            osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> Macos
            else -> Linux
        }
    }
}

/**
 * What the prime's host block asks of the machine (D-366): the OS family, whether a program resolves on `PATH`
 * and an environment value. Read from the environment and the filesystem only — a probe never starts a process.
 */
public interface HostProbe {
    public val os: OsFamily

    /** True when [program] resolves on `PATH` (with `PATHEXT` on Windows). */
    public fun onPath(program: String): Boolean

    /** The environment variable [name], or `null` when unset. */
    public fun env(name: String): String?

    public companion object {
        /** The running process: `os.name` and its environment. */
        @JvmStatic
        public fun system(): HostProbe = PathProbe(OsFamily.of(System.getProperty("os.name").orEmpty()), System.getenv())
    }
}

/** A [HostProbe] over an [environment] map: `PATH` entries looked up on disk, with `PATHEXT` on Windows. */
public class PathProbe(override val os: OsFamily, environment: Map<String, String>) : HostProbe {
    private val environment: Map<String, String> = environment.toMap()

    // Windows environment names are case-insensitive (`Path`, `PATH`).
    override fun env(name: String): String? =
        if (os == OsFamily.Windows) environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value else environment[name]

    override fun onPath(program: String): Boolean {
        val separator = if (os == OsFamily.Windows) ';' else ':'
        val dirs = env("PATH").orEmpty().split(separator).map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        val extensions = if (os == OsFamily.Windows) {
            env("PATHEXT")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_PATHEXT
        } else {
            listOf("")
        }
        for (dir in dirs) {
            for (extension in extensions) {
                val candidate = try {
                    Path.of(dir, program + extension)
                } catch (_: InvalidPathException) {
                    continue
                }
                if (Files.isRegularFile(candidate) && (os == OsFamily.Windows || Files.isExecutable(candidate))) return true
            }
        }
        return false
    }

    private companion object {
        val DEFAULT_PATHEXT = listOf(".COM", ".EXE", ".BAT", ".CMD")
    }
}

/**
 * The host block of the repository prime (D-366): OS family, how `run` starts programs, which common toolchain
 * programs resolve on `PATH`, the project-local wrappers in the workspace root and an unset `JAVA_HOME` beside a
 * Gradle or Maven build. Program names only — no locations, no versions, no clock — so it is byte-stable for a host.
 */
public data class HostFacts(
    val os: OsFamily,
    val onPath: List<String>,
    val notOnPath: List<String>,
    /** Wrapper scripts in the workspace root (`gradlew`, `mvnw`, …). */
    val wrappers: List<String> = emptyList(),
    /** `JAVA_HOME` is unset while a Gradle or Maven build file exists. */
    val javaHomeUnset: Boolean = false,
) {
    /** At most six lines, each ending in `\n`. */
    public fun render(): String = buildString {
        append("host: ").append(os.wire).append(" · ")
        append(if (os == OsFamily.Windows) "workspace paths use /; native paths use \\" else "paths use /").append('\n')
        append("  run: ").append(RUN_LINE).append('\n')
        append("  on PATH: ").append(onPath.joinToString(", ").ifEmpty { "none of the probed programs" }).append('\n')
        if (notOnPath.isNotEmpty()) append("  not on PATH: ").append(notOnPath.joinToString(", ")).append('\n')
        if (wrappers.isNotEmpty()) append("  wrappers: ").append(wrappers.joinToString(", ")).append('\n')
        if (javaHomeUnset) append("  JAVA_HOME: unset (a Gradle or Maven build is present)\n")
    }

    public companion object {
        /** The programs whose `PATH` presence the block reports, in this order. */
        @JvmField
        public val PROGRAMS: List<String> = listOf(
            "git", "node", "npm", "npx", "pnpm", "yarn", "java", "javac", "gradle", "mvn",
            "python", "python3", "pip", "go", "cargo", "dotnet", "docker",
        )

        /** Project-local wrappers looked for in the workspace root. */
        @JvmField
        public val WRAPPERS: List<String> = listOf("gradlew", "gradlew.bat", "mvnw", "mvnw.cmd")

        public const val RUN_LINE: String = "argv starts the program directly, no shell; shell syntax (pipes, &&, set VAR=…) " +
            "needs the `cmd` form — cmd.exe on Windows, sh on POSIX"

        private val JVM_BUILDS = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "pom.xml")

        /** The facts [probe] reports for the workspace [atlas] describes. */
        @JvmStatic
        public fun of(probe: HostProbe, atlas: Atlas): HostFacts {
            val (present, absent) = PROGRAMS.partition(probe::onPath)
            val paths = atlas.rows.map { it.path }
            val rootFiles = paths.filter { '/' !in it }.toSet()
            val jvmBuild = paths.any { it.substringAfterLast('/') in JVM_BUILDS }
            return HostFacts(
                os = probe.os,
                onPath = present,
                notOnPath = absent,
                wrappers = WRAPPERS.filter { it in rootFiles },
                javaHomeUnset = jvmBuild && probe.env("JAVA_HOME").isNullOrBlank(),
            )
        }
    }
}
